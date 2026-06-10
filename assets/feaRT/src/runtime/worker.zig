const std = @import("std");
const Fiber = @import("fiber.zig").Fiber;
const switchFiber = @import("fiber.zig").switchFiber;
const JoinObligation = @import("sync/join_obligation.zig").JoinObligation;
const MpmcBoundedQueue = @import("sync/mpmc.zig").MpmcBoundedQueue;
const gc = @import("gc.zig");
const log = @import("log.zig");
const scope_mod = @import("scope.zig");
const signals = @import("errors/signals.zig");
const trace = @import("errors/trace.zig");
const shadow_stack = @import("shadow_stack.zig");
const build_options = @import("build_options");

const FatPtr = @import("objs.zig").FatPtr;
const TraceFrame = trace.TraceFrame;
const TRACE_CAP = shadow_stack.TRACE_CAP;
const LocalsDropHook = *const fn (*anyopaque) void;

pub const StolenTask = struct {
	thief_fn: *const fn (*anyopaque, ?*JoinObligation) FatPtr,
	locals_copy: [256]u8 align(8),
	locals_drop_fn: LocalsDropHook,
	obligation: *JoinObligation,
	child_obligation: ?*JoinObligation,
	initial_tokens: u32,
	/// Points at the parent fiber's tokens counter. The thief fiber copies
	/// this into its own parent_tokens_ptr field on construction, and the
	/// scheduler writes any leftover tokens back through this pointer when
	/// the thief fiber completes (state == .Done).
	parent_tokens_ptr: *u32,
	/// Captured from the promoter's TLS `active_scope` at heartbeat time.
	/// The thief fiber inherits this via its `saved_scope` slot so cancel
	/// propagates across the fork. Null if the promoter had no scope pushed.
	scope: ?*scope_mod.Scope,
	/// Snapshot of the promoter's trace stack at heartbeat time, copied into the
	/// thief fiber (with a boundary sentinel) so a crash on a stolen subtree
	/// shows the promoter's call chain. Zero-size when `trace_frames` is off.
	trace_frames: [if (build_options.trace_frames) TRACE_CAP else 0]TraceFrame = undefined,
	trace_top: usize = 0,
	/// Set by `doPromote` when the publish-obligation CAS fails after the
	/// task was already enqueued. The thief trampoline checks this first
	/// thing on entry: if true, it skips `thief_fn`, recycles the unclaimed
	/// obligations, and returns. Required because the enqueue-first ordering
	/// in heartbeat.zig means a queued task may correspond to a frame the
	/// parent has already claimed (and so has no consumer for our result).
	cancelled: std.atomic.Value(bool) = std.atomic.Value(bool).init(false),
};

pub const Worker = struct {
	const OBL_POOL_CAP = 64;
	const TASK_POOL_CAP = 32;

	id: usize,
	current_fiber: ?*Fiber,
	/// The scheduler fiber lives on the OS thread's stack.
	/// We context-switch back to it when a fiber parks or completes.
	scheduler_fiber: Fiber,
	ready_queue: *MpmcBoundedQueue(*Fiber),
	task_queue: *MpmcBoundedQueue(*StolenTask),

	/// Per-worker trace buffer
	trace_buf: log.TraceBuffer,

	obligation_pool: [OBL_POOL_CAP]?*JoinObligation = .{null} ** OBL_POOL_CAP,
	obligation_pool_len: usize = 0,
	task_pool: [TASK_POOL_CAP]?*StolenTask = .{null} ** TASK_POOL_CAP,
	task_pool_len: usize = 0,

	pub fn allocObligation(self: *Worker) ?*JoinObligation {
		if (self.obligation_pool_len > 0) {
			self.obligation_pool_len -= 1;
			const obl = self.obligation_pool[self.obligation_pool_len].?;
			self.obligation_pool[self.obligation_pool_len] = null;
			obl.* = JoinObligation.init();
			log.trace_scheduling(.obl_alloc, @intFromPtr(obl), 0, self.id);
			return obl;
		}
		const obl = gc.allocator.create(JoinObligation) catch return null;
		obl.* = JoinObligation.init();
		log.trace_scheduling(.obl_alloc, @intFromPtr(obl), 0, self.id);
		return obl;
	}

	pub fn allocTask(self: *Worker) ?*StolenTask {
		if (self.task_pool_len > 0) {
			self.task_pool_len -= 1;
			const task = self.task_pool[self.task_pool_len].?;
			self.task_pool[self.task_pool_len] = null;
			// Reset the cancel flag — recycled tasks must not carry the bit
			// set by a previous CAS-fail rollback into a fresh promotion.
			task.cancelled.store(false, .monotonic);
			return task;
		}
		const task = gc.allocator.create(StolenTask) catch return null;
		task.cancelled = std.atomic.Value(bool).init(false);
		return task;
	}

	pub fn recycleObligation(self: *Worker, obl: *JoinObligation) void {
		if (self.obligation_pool_len < OBL_POOL_CAP) {
			self.obligation_pool[self.obligation_pool_len] = obl;
			self.obligation_pool_len += 1;
		} else {
			gc.allocator.destroy(obl);
		}
	}

	pub fn recycleTask(self: *Worker, task: *StolenTask) void {
		task.locals_drop_fn(@ptrCast(&task.locals_copy));
		if (self.task_pool_len < TASK_POOL_CAP) {
			self.task_pool[self.task_pool_len] = task;
			self.task_pool_len += 1;
		} else {
			gc.allocator.destroy(task);
		}
	}

	/// Park the current fiber: set its state to Parked and switch to the scheduler.
	pub fn parkCurrentFiber(self: *Worker) void {
		const fiber = self.current_fiber.?;
		fiber.state = .Parked;
		// Switch back to scheduler — scheduler_fiber has no shadow stack to swap to,
		// so we just do a raw switchTo here (the scheduler doesn't use shadow stacks).
		const switchTo = @import("fiber.zig").switchFiber;
		switchTo(fiber, &self.scheduler_fiber);
	}
};

/// Thread-local pointer to the current worker.
/// IMPORTANT: Always use getCurrentWorker() to read this — direct reads can be
/// cached by the compiler in callee-saved registers, producing stale values
/// after fiber migration (switchTo can resume on a different OS thread).
pub threadlocal var tls_current_worker: ?*Worker = null;

/// Fresh read of tls_current_worker. Must be noinline with an asm barrier to
/// prevent the compiler from caching the TLS address or return value across
/// fiber context switches (switchTo can resume a fiber on a different OS thread
/// with a different TLS base, so cached TLS values become stale).
pub noinline fn getCurrentWorker() ?*Worker {
	const w = tls_current_worker;
	asm volatile ("" ::: .{ .memory = true });
	return w;
}

pub var global_done: std.atomic.Value(bool) = std.atomic.Value(bool).init(false);

/// Global reference to the worker pool to prevent GC from collecting it.
/// (GC scans the data segment but might not scan Zig thread-locals or
/// OS stack frames that aren't active during fiber execution.)
var global_pool: ?*WorkerPool = null;

pub const WorkerPool = struct {
	workers: []Worker,
	threads: []std.Thread,
	ready_queue: *MpmcBoundedQueue(*Fiber),
	task_queue: *MpmcBoundedQueue(*StolenTask),
	num_workers: usize,

	pub fn init(num_workers: usize) !*WorkerPool {
		const ready_queue = try MpmcBoundedQueue(*Fiber).init(gc.allocator, 1024);
		const task_queue = try MpmcBoundedQueue(*StolenTask).init(gc.allocator, 1024);

		const workers = try gc.allocator.alloc(Worker, num_workers);
		for (workers, 0..) |*w, i| {
			w.* = .{
				.id = i,
				.current_fiber = null,
				.scheduler_fiber = .{
					.sp = 0,
					.stack_bottom = undefined,
					.stack_size = 0,
					.entry_fn = null,
					.context = null,
					.state = .Running,
				},
				.ready_queue = ready_queue,
				.task_queue = task_queue,
				.trace_buf = .{},
			};
			for (0..Worker.OBL_POOL_CAP) |j| {
				w.obligation_pool[j] = gc.allocator.create(JoinObligation) catch null;
				if (w.obligation_pool[j] != null) w.obligation_pool_len = j + 1;
			}
			for (0..Worker.TASK_POOL_CAP) |j| {
				w.task_pool[j] = gc.allocator.create(StolenTask) catch null;
				if (w.task_pool[j] != null) w.task_pool_len = j + 1;
			}
			log.trace_buffers[i] = &w.trace_buf;
		}
		log.num_workers = num_workers;

		const threads = try gc.allocator.alloc(std.Thread, if (num_workers > 1) num_workers - 1 else 0);

		const pool = try gc.allocator.create(WorkerPool);
		pool.* = .{
			.workers = workers,
			.threads = threads,
			.ready_queue = ready_queue,
			.task_queue = task_queue,
			.num_workers = num_workers,
		};
		// Store globally so GC can find it (prevents collection)
		global_pool = pool;
		return pool;
	}

	/// Enqueue a fiber to the global ready queue.
	pub fn enqueueFiber(self: *WorkerPool, fiber: *Fiber) void {
		fiber.state = .Ready;
		self.ready_queue.enqueueWithSpin(fiber);
	}

	/// Run the worker pool. Main thread becomes worker 0.
	/// Spawns N-1 OS threads for workers 1..N-1.
	pub fn run(self: *WorkerPool) void {
		// Spawn worker threads 1..N-1
		for (self.threads, 1..) |*t, i| {
			t.* = std.Thread.spawn(.{}, workerLoop, .{&self.workers[i]}) catch @panic("Failed to spawn worker thread");
		}

		// Main thread runs worker 0
		workerLoop(&self.workers[0]);

		// Join all worker threads
		for (self.threads) |t| {
			t.join();
		}
	}
};

fn thiefTrampoline(fiber: *Fiber) void {
	const task: *StolenTask = @ptrCast(@alignCast(fiber.context.?));

	// Cancellation check: if doPromote enqueued us but its publish-obligation
	// CAS lost the race, our parent has already finished the work itself and
	// nobody is waiting on `task.obligation`. Running thief_fn would just burn
	// a fiber on dead work. Recycle the unclaimed obligations and bail.
	if (task.cancelled.load(.acquire)) {
		log.trace_scheduling(.thief_start, @intFromPtr(task), @intFromPtr(task.obligation), 0xCA11);
		const w = getCurrentWorker().?;
		// task.obligation was never observable to the parent (the CAS that
		// would have published it failed), so no one will ever wait on it.
		w.recycleObligation(task.obligation);
		if (task.child_obligation) |co| w.recycleObligation(co);
		w.recycleTask(task);
		return;
	}

	log.trace_scheduling(.thief_start, @intFromPtr(task), @intFromPtr(task.obligation), @intFromPtr(task.child_obligation));

	// Call the thief function with copied locals and the child obligation.
	// The thief function is responsible for waiting on the child obligation
	// *after* it does its own parallel work.
	const result = task.thief_fn(&task.locals_copy, task.child_obligation);
	log.trace_scheduling(.thief_fn_return, @intFromPtr(result.vt), @bitCast(result.data), 0);

	// The fiber may have migrated to a different worker while waiting for the child obligation.
	// MUST re-fetch the current worker!
	const current_worker = getCurrentWorker().?;

	// Fulfill the obligation with the result
	task.obligation.fulfill(result, current_worker.ready_queue);
	log.trace_scheduling(.thief_fulfill, @intFromPtr(task.obligation), @intFromPtr(result.vt), @bitCast(result.data));

	// Recycle task (child obligation is freed by the generated thief function)
	current_worker.recycleTask(task);
}

fn workerLoop(worker: *Worker) void {
	// Register thread with GC
	if (worker.id != 0) {
		gc.register_thread();
	}

	// Set thread-local worker pointer and trace buffer
	tls_current_worker = worker;
	log.tls_trace_buffer = &worker.trace_buf;

	// Register this thread's signal alt stack + recovery stack so a fiber
	// stack overflow on this worker becomes a catchable ND error.
	signals.initThreadSignalStacks();

	// Worker loop
	while (!global_done.load(.monotonic)) {
		// 1. Check for stolen tasks → wrap in thief fiber
		if (worker.task_queue.dequeue()) |task| {
			const fiber = Fiber.create(&thiefTrampoline, @ptrCast(task)) catch @panic("OOM creating thief fiber");
			fiber.state = .Ready;
			fiber.tokens = task.initial_tokens;
			fiber.parent_tokens_ptr = task.parent_tokens_ptr;
			fiber.saved_scope = task.scope;
			if (build_options.trace_frames) {
				trace.inheritStackTrace(fiber, &task.trace_frames, task.trace_top);
			}
			// The thief's completion is awaited through `task.obligation`.
			// Record it as the fiber's root obligation so that if the thief
			// unwinds (panic / `Error!`) `feart_unwind` delivers the error to
			// the same obligation `thiefTrampoline` would otherwise fulfill.
			fiber.root_obligation = task.obligation;
			log.trace_scheduling(.fiber_enqueue, @intFromPtr(fiber), 0xFF, worker.id);
			worker.ready_queue.enqueueWithSpin(fiber);
			continue;
		}

		// 2. Check for ready fibers → switch to them
		if (worker.ready_queue.dequeue()) |fiber| {
			log.trace_scheduling(.fiber_dequeue, @intFromPtr(fiber), @intFromEnum(fiber.state), worker.id);
			// Spin until the fiber's state has been fully saved (prevents
			// switching to a fiber that was enqueued before it parked).
			while (!fiber.resume_gate.load(.acquire)) {
				std.atomic.spinLoopHint();
			}
			worker.current_fiber = fiber;
			fiber.state = .Running;
			log.trace_scheduling(.fiber_switch_to, @intFromPtr(fiber), worker.id, 0);
			switchFiber(&worker.scheduler_fiber, fiber);

			// Fiber returned to scheduler — its state is now saved.
			log.trace_scheduling(.fiber_switch_back, @intFromPtr(fiber), @intFromEnum(fiber.state), worker.id);
			// Open the resume gate so the next dequeue can proceed.
			fiber.resume_gate.store(true, .release);
			worker.current_fiber = null;

			if (fiber.state == .Done) {
				// Credit any leftover APM tokens back to the parent fiber.
				// The parent may be concurrently running on another worker
				// and touching its own tokens counter via tryPromote, so this
				// is a benign race on a single u32: at worst a handful of
				// increments are lost, delaying the next promotion by an
				// imperceptible amount. Tokens are heuristic, not accounting.
				if (fiber.parent_tokens_ptr) |ptr| {
					ptr.* += fiber.tokens;
				}
				log.trace_scheduling(.fiber_done, @intFromPtr(fiber), worker.id, 0);
				fiber.destroy();
			}
			// If Parked, leave it — it will be re-enqueued when its obligation is fulfilled
			continue;
		}

		// 3. Nothing to do — spin, with a small sleep to prevent spamming CPU usage
		std.atomic.spinLoopHint();
		// std.Thread.yield() catch {};
		// gc.maybeCollectCycles();
		std.Io.sleep(gc.runtime_io, std.Io.Duration.fromMilliseconds(1), .awake) catch {};
	}

	gc.dump_rc_delta();
	signals.deinitThreadSignalStacks();
	if (worker.id != 0) {
		gc.unregister_thread();
	}
}
