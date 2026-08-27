const std = @import("std");
const builtin = @import("builtin");
const fiber_mod = @import("fiber.zig");
const Fiber = fiber_mod.Fiber;
const switchFiber = fiber_mod.switchFiber;
const JoinObligation = @import("sync/join_obligation.zig").JoinObligation;
const MpmcBoundedQueue = @import("sync/mpmc.zig").MpmcBoundedQueue;
const ChaseLevDeque = @import("sync/chase_lev.zig").ChaseLevDeque;
const gc = @import("gc.zig");
const process = @import("process_singletons.zig");
const log = @import("log.zig");
const scope_mod = @import("scope.zig");
const signals = @import("errors/signals.zig");
const trace = @import("errors/trace.zig");
const shadow_stack = @import("shadow_stack.zig");
const build_options = @import("build_options");
const op_counters = @import("op_counters.zig");

const objs = @import("objs.zig");

const FatPtr = objs.FatPtr;
const TraceFrame = trace.TraceFrame;
const TRACE_CAP = shadow_stack.TRACE_CAP;
const LocalsDropHook = shadow_stack.LocalsDropHook;

pub const StolenTask = struct {
	thief_fn: *const fn (*anyopaque, ?*JoinObligation) FatPtr,
	locals_copy: [256]u8 align(8),
	locals_drop_fn: LocalsDropHook,
	obligation: *JoinObligation,
	child_obligation: ?*JoinObligation,
	initial_tokens: u32,
	/// The promoter. The thief fiber copies this into its own `parent` and pays
	/// the token refund through it, just before it fulfills the obligation that
	/// can wake the promoter.
	parent: *Fiber,
	/// True while this task, rather than a thief fiber built from it, holds the
	/// reference that keeps `parent`'s mapping alive. It moves to the fiber at
	/// creation, so exactly one of the two releases it.
	holds_parent_ref: bool,
	/// The scope of the promoted shadow frame. The thief inherits it through
	/// `saved_scope`, so cancel propagates across the fork. Null when no scope was
	/// pushed where that frame was.
	scope: ?*scope_mod.Scope,
	/// The promoter's trace stack at heartbeat time, copied into the thief fiber
	/// with a boundary sentinel, so a crash on a stolen subtree shows the
	/// promoter's call chain. Zero-size when `trace_frames` is off.
	trace_frames: [if (build_options.trace_frames) TRACE_CAP else 0]TraceFrame = undefined,
	trace_top: usize = 0,
	/// Execution rights. Every side that could run the work CASes `false -> true`
	/// and only the winner proceeds: the dequeuing worker, the promoter's
	/// publish-CAS-lost path, and the parent reclaiming an unstolen task.
	///
	/// Cleanup follows the claim. The obligations belong to the winner and the
	/// task memory to whoever dequeues it. A task still in a queue must never be
	/// recycled, or the next promotion hands the same pointer out twice and the
	/// queue holds one task in two places.
	claimed: std.atomic.Value(bool) = std.atomic.Value(bool).init(false),
};

pub const Worker = struct {
	const OBL_POOL_CAP = 64;
	const TASK_POOL_CAP = 32;

	id: usize,
	current_fiber: ?*Fiber,
	/// Lives on the OS thread's stack. A fiber that parks or completes switches
	/// back to it.
	scheduler_fiber: Fiber,
	/// Any worker may take any fiber, so this is a full MPMC queue rather than an
	/// owner-private structure, and a steal is just `dequeue` on a victim.
	// TODO: a Chase-Lev deque could remove a few atomic ops if this shows up
	// poorly in a profile.
	ready_queue: *MpmcBoundedQueue(*Fiber),
	/// Promoted tasks. Only this worker pushes and takes, at the bottom end; any
	/// other worker steals from the top. It grows on demand, so a promotion is
	/// never refused for want of space.
	task_queue: *ChaseLevDeque(*StolenTask),

	/// Victim-picking xorshift, seeded from the worker id so no two workers walk
	/// the victim list in lockstep.
	steal_rng: u64,

	trace_buf: log.TraceBuffer,

	/// Heap objects a foreign worker could not release, because this worker's
	/// biased count still covers the reference. Drained between fiber slices and
	/// on the heartbeat fire path.
	merge_queue_head: objs.MergeQueue = .{ .raw = null },

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
			// A recycled task must not carry the bit won by whoever retired the
			// promotion it held before.
			task.claimed.store(false, .monotonic);
			// A recycled task must not carry the reference its last promotion held.
			task.holds_parent_ref = false;
			return task;
		}
		const task = gc.allocator.create(StolenTask) catch return null;
		task.claimed = std.atomic.Value(bool).init(false);
		task.holds_parent_ref = false;
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
		// A task retired without ever becoming a thief fiber still owns the
		// promoter's mapping reference, and nothing else will pay the join credit
		// that would have released it.
		if (task.holds_parent_ref) {
			task.holds_parent_ref = false;
			task.parent.releaseMapping();
		}
		// This runs on the scheduler stack as well as on a fiber, so the worker
		// goes in explicitly rather than off the stack pointer.
		task.locals_drop_fn(@ptrCast(&task.locals_copy), self.workerId());
		if (self.task_pool_len < TASK_POOL_CAP) {
			self.task_pool[self.task_pool_len] = task;
			self.task_pool_len += 1;
		} else {
			gc.allocator.destroy(task);
		}
	}

	/// This worker's reference-counting id: its index plus one. Zero is reserved
	/// for "no worker", so this is never the raw index.
	pub inline fn workerId(self: *const Worker) u32 {
		return @intCast(self.id + 1);
	}

	/// Fold the biased half of every object handed back to this worker into its
	/// shared half. One relaxed load when there is nothing to do.
	///
	/// The scheduler loop calls this off a fiber stack, so the drain carries the
	/// worker rather than reading it off the stack pointer.
	pub inline fn drainMergeQueue(self: *Worker) void {
		if (self.merge_queue_head.load(.monotonic) == null) return;
		objs.drainMergeQueue(&self.merge_queue_head, self.workerId());
	}

	/// Set the fiber state to Parked and switch to the scheduler.
	pub fn parkCurrentFiber(self: *Worker) void {
		const fiber = self.current_fiber.?;
		fiber.state = .Parked;
		// A raw switchTo: the scheduler fiber uses no shadow stack, so there is
		// nothing to swap.
		const switchTo = @import("fiber.zig").switchFiber;
		switchTo(fiber, &self.scheduler_fiber);
	}
};

/// Read this only through `getCurrentWorker`. The compiler can cache a direct
/// read in a callee-saved register, and `switchTo` may resume the fiber on a
/// different OS thread, which makes such a value stale.
pub threadlocal var tls_current_worker: ?*Worker = null;

/// Fresh read of `tls_current_worker`. Noinline with an asm barrier, so the
/// compiler cannot cache the TLS address or the result across a fiber switch:
/// `switchTo` may resume on another OS thread with a different TLS base.
pub noinline fn getCurrentWorker() ?*Worker {
	const w = tls_current_worker;
	asm volatile ("" ::: .{ .memory = true });
	return w;
}

/// The worker running the calling fiber, as `worker.id + 1`.
///
/// Every biased reference-count operation calls this, so it costs a mask of the
/// stack pointer and one load out of the fiber header. That beats a thread-local
/// on Darwin, where the runtime resolves one through `__tls_get_addr` even in a
/// static build, and it cannot go stale: the value travels with the stack, so a
/// fiber that resumes on another OS thread reads the new worker without a
/// barrier.
///
/// VALID ONLY ON A FIBER STACK. Masking any other stack pointer lands on
/// unrelated memory, and a value read from there would put a biased reference
/// count in the hands of a thread it does not belong to. The scheduler
/// loop reaches reference counting through `Worker.drainMergeQueue` and
/// `Worker.recycleTask`, both of which pass `Worker.workerId` down instead.
///
/// A unit test runs on the test runner's own stack, which is no fiber, so the
/// test build answers 0. That is the value an object with no biased half carries,
/// and it sends every operation down the shared path, which is what the reference
/// counts a test reads through `ObjectHeader.refCountForTest` assume.
pub inline fn currentWorkerId() u32 {
	if (builtin.is_test) return 0;
	return fiber_mod.currentFiber().worker_id;
}

/// The merge queue of the worker `worker_id` names.
pub fn mergeQueueFor(worker_id: u32) ?*objs.MergeQueue {
	const pool = global_pool orelse return null;
	if (worker_id == 0 or worker_id > pool.workers.len) return null;
	return &pool.workers[worker_id - 1].merge_queue_head;
}

pub var global_done: std.atomic.Value(bool) = std.atomic.Value(bool).init(false);

/// Keeps the pool alive: the GC scans the data segment, but not Zig
/// thread-locals or OS stack frames that are inactive during fiber execution.
var global_pool: ?*WorkerPool = null;

/// Total: it always places the fiber, and never blocks or refuses. A fiber that
/// exists has to go somewhere, and a caller that spins waiting to place one
/// stops draining queues, which is a livelock. Nothing in the scheduler applies
/// backpressure by refusing work; `enqueueTask` is total for the same reason.
///
/// Fibers have no worker affinity, so the running worker's queue is a locality
/// preference only and the shared ready list takes the rest.
pub fn enqueueFiber(fiber: *Fiber) void {
	fiber.state = .Ready;
	if (getCurrentWorker()) |w| {
		if (w.ready_queue.enqueue(fiber)) return;
	}
	const pool = global_pool orelse @panic("enqueueFiber before the worker pool exists");
	pool.sharedReadyPush(fiber);
}

/// Admit `task` for stealing on the promoting worker's deque. Total: the deque
/// grows rather than refusing, so a task that does not fit needs no second path.
///
/// Totality keeps the token accounting honest. A promotion that could fail
/// leaves the fiber's tokens above the threshold, so every following frame tries
/// again and the attempt count tracks total work rather than work over the
/// threshold.
///
/// Only a worker thread runs Fearless code, so neither failure below is
/// reachable. Both panic: a fallback would hide the bug that produced them.
pub fn enqueueTask(task: *StolenTask) void {
	const worker = getCurrentWorker() orelse @panic("enqueueTask with no current worker");
	worker.task_queue.push(task) catch @panic("out of memory growing a worker task deque");
}

pub const WorkerPool = struct {
	workers: []Worker,
	threads: []std.Thread,
	/// Unbounded overflow list for ready fibers. Takes what a full local queue
	/// refuses, and everything pushed from a thread that runs no worker.
	shared_ready: std.atomic.Value(?*Fiber),
	num_workers: usize,

	pub fn init(num_workers: usize) !*WorkerPool {
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
				.ready_queue = try MpmcBoundedQueue(*Fiber).init(gc.allocator, 1024),
				.task_queue = try ChaseLevDeque(*StolenTask).init(gc.allocator, 1024),
				.steal_rng = i +% 0x9E3779B97F4A7C15,
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
			.shared_ready = std.atomic.Value(?*Fiber).init(null),
			.num_workers = num_workers,
		};
		global_pool = pool;
		return pool;
	}

	fn sharedReadyPush(self: *WorkerPool, fiber: *Fiber) void {
		var old = self.shared_ready.load(.monotonic);
		while (true) {
			fiber.shared_ready_next = old;
			if (self.shared_ready.cmpxchgWeak(old, fiber, .release, .monotonic)) |observed| {
				old = observed;
			} else return;
		}
	}

	/// Splices a pre-linked chain back with one CAS. The `.release` publishes the
	/// caller's non-atomic link writes to the next thread that swaps the head.
	fn sharedReadyPushChain(self: *WorkerPool, chain_head: *Fiber, chain_tail: *Fiber) void {
		var old = self.shared_ready.load(.monotonic);
		while (true) {
			chain_tail.shared_ready_next = old;
			if (self.shared_ready.cmpxchgWeak(old, chain_head, .release, .monotonic)) |observed| {
				old = observed;
			} else return;
		}
	}

	/// Takes the whole chain, returns its first fiber to run now and absorbs the
	/// rest into `worker`'s local queue. What does not fit goes back to the
	/// shared list, so it stays visible to thieves.
	///
	/// Swaps the whole head rather than popping one node: a fiber can be taken,
	/// parked and re-pushed at the same address while another thread holds a
	/// stale `shared_ready_next`.
	fn sharedReadyDrain(self: *WorkerPool, worker: *Worker) ?*Fiber {
		const first = self.shared_ready.swap(null, .acquire) orelse return null;
		var chain = first.shared_ready_next;
		first.shared_ready_next = null;
		while (chain) |fiber| {
			const next = fiber.shared_ready_next;
			fiber.shared_ready_next = null;
			if (!worker.ready_queue.enqueue(fiber)) {
				fiber.shared_ready_next = next;
				var tail = fiber;
				while (tail.shared_ready_next) |n| tail = n;
				self.sharedReadyPushChain(fiber, tail);
				break;
			}
			chain = next;
		}
		return first;
	}

	/// The calling thread becomes worker 0; workers 1..N-1 get an OS thread.
	pub fn run(self: *WorkerPool) void {
		for (self.threads, 1..) |*t, i| {
			t.* = std.Thread.spawn(.{}, workerLoop, .{&self.workers[i]}) catch @panic("Failed to spawn worker thread");
		}

		// Every runtime thread now exists, which is what stop-the-world cycle
		// collection needs. See `isSafeToCollectCycles` in gc.zig.
		gc.enable_cycle_collection();

		workerLoop(&self.workers[0]);

		for (self.threads) |t| {
			t.join();
		}
	}
};

fn thiefTrampoline(fiber: *Fiber) void {
	const task: *StolenTask = @ptrCast(@alignCast(fiber.context.?));

	// No claim check: `workerLoop` wins the claim before it builds this fiber, so
	// a fiber only exists for work this side owns.
	log.trace_scheduling(.thief_start, @intFromPtr(task), @intFromPtr(task.obligation), @intFromPtr(task.child_obligation));

	// The thief function waits on the child obligation after its own parallel
	// work, not before.
	const result = task.thief_fn(&task.locals_copy, task.child_obligation);
	log.trace_scheduling(.thief_fn_return, @intFromPtr(result.vt), @bitCast(result.data), 0);

	// The wait on the child obligation may have migrated this fiber to another
	// worker, so the worker MUST be re-fetched.
	const current_worker = getCurrentWorker().?;

	// Before the fulfill: that can wake the parent, which may then finish and be
	// destroyed while this fiber still holds a pointer into it.
	current_worker.current_fiber.?.creditParentTokens();

	task.obligation.fulfill(result);
	log.trace_scheduling(.thief_fulfill, @intFromPtr(task.obligation), @intFromPtr(result.vt), @bitCast(result.data));

	// The generated thief function frees the child obligation.
	current_worker.recycleTask(task);
}

fn nextRandom(worker: *Worker) u64 {
	var x = worker.steal_rng;
	x ^= x << 13;
	x ^= x >> 7;
	x ^= x << 17;
	worker.steal_rng = x;
	return x;
}

/// Own queue first, which is warmest, then the shared ready list, then one
/// random victim. Null when the whole pool looks empty.
fn findFiber(worker: *Worker) ?*Fiber {
	if (worker.ready_queue.dequeue()) |fiber| return fiber;

	const pool = global_pool orelse return null;
	if (pool.sharedReadyDrain(worker)) |fiber| return fiber;

	const n = pool.workers.len;
	if (n < 2) return null;
	// One victim per scheduler turn. A full sweep would let a worker spend its
	// turn probing queues that one producer is refilling anyway.
	var victim = nextRandom(worker) % n;
	if (victim == worker.id) victim = (victim + 1) % n;
	return pool.workers[victim].ready_queue.dequeue();
}

/// Sweeps of the whole pool before `stealTask` gives the turn back. A sweep
/// repeats only after a lost race, which means a task was there to be had, so
/// one retry buys the common case without spinning against a busy victim.
const STEAL_PASSES = 2;

/// Starts at a random offset, so no two workers converge on one victim order.
fn stealTask(worker: *Worker, pool: *WorkerPool) ?*StolenTask {
	const n = pool.workers.len;
	if (n < 2) return null;

	var pass: usize = 0;
	while (pass < STEAL_PASSES) : (pass += 1) {
		var contended = false;
		const start = nextRandom(worker) % n;
		var i: usize = 0;
		while (i < n) : (i += 1) {
			const victim = (start + i) % n;
			if (victim == worker.id) continue;
			switch (pool.workers[victim].task_queue.steal()) {
				.task => |task| {
					op_counters.bump(.task_stolen);
					return task;
				},
				// A lost race, not an empty deque: the victim still holds work.
				.abort => contended = true,
				.empty => {},
			}
		}
		if (!contended) return null;
		std.atomic.spinLoopHint();
	}
	return null;
}

/// Own deque first, then a sweep of every other worker.
fn findTask(worker: *Worker) ?*StolenTask {
	if (worker.task_queue.take()) |task| return task;
	const pool = global_pool orelse return null;
	return stealTask(worker, pool);
}

fn workerLoop(worker: *Worker) void {
	if (worker.id != 0) {
		gc.register_thread();
	}

	tls_current_worker = worker;
	log.tls_trace_buffer = &worker.trace_buf;

	// The signal alt stack and recovery stack are what make a fiber stack
	// overflow on this worker a catchable ND error.
	signals.initThreadSignalStacks(worker.workerId());

	while (!global_done.load(.monotonic)) {
		// 0. Release the objects foreign workers handed back. Nothing else may
		// touch their biased counts, so a scheduler turn is where this belongs.
		worker.drainMergeQueue();

		// 1. A runnable fiber.
		if (findFiber(worker)) |fiber| {
			log.trace_scheduling(.fiber_dequeue, @intFromPtr(fiber), @intFromEnum(fiber.state), worker.id);
			// Spin until the fiber's state is saved, so this never switches to a
			// fiber that was enqueued before it parked.
			while (!fiber.resume_gate.load(.acquire)) {
				std.atomic.spinLoopHint();
			}
			worker.current_fiber = fiber;
			// Before the switch: the fiber reads this for every reference count
			// operation it runs, from its first instruction on.
			fiber.worker_id = worker.workerId();
			fiber.state = .Running;
			log.trace_scheduling(.fiber_switch_to, @intFromPtr(fiber), worker.id, 0);
			switchFiber(&worker.scheduler_fiber, fiber);

			log.trace_scheduling(.fiber_switch_back, @intFromPtr(fiber), @intFromEnum(fiber.state), worker.id);
			// Before the gate opens: the worker spinning on it may then resume
			// and destroy the fiber. Plain, because the release store below
			// cannot move ahead of this load.
			const finished = fiber.state == .Done;
			worker.current_fiber = null;

			if (finished) {
				// A finished fiber sits in no queue: it reached the scheduler from
				// fiber_trampoline rather than from a park, so nobody waits at its
				// gate and this worker is its only owner.
				log.trace_scheduling(.fiber_done, @intFromPtr(fiber), worker.id, 0);
				fiber.destroy();
			} else {
				// Open the resume gate, so the worker that re-enqueues the fiber,
				// or already waits on it, can proceed.
				fiber.resume_gate.store(true, .release);
			}
			continue;
		}

		// 2. No fiber anywhere: turn a stolen task into a thief fiber.
		if (findTask(worker)) |task| {
			// Claim before spending a fiber stack. A parent that reached its join
			// point first has taken the work back and run it inline. The task is
			// out of every queue by now, so its memory, and the retained locals
			// copy that `recycleTask` releases, are this worker's to retire. The
			// claim winner recycled the obligations.
			if (task.claimed.cmpxchgStrong(false, true, .acq_rel, .acquire) != null) {
				op_counters.bump(.task_dequeue_claim_lost);
				worker.recycleTask(task);
				continue;
			}
			op_counters.bump(.thief_fiber_created);
			const fiber = Fiber.create(&thiefTrampoline, @ptrCast(task)) catch @panic("OOM creating thief fiber");
			fiber.state = .Ready;
			fiber.tokens = task.initial_tokens;
			// The mapping reference moves with the parent: from here the fiber's
			// join credit is what releases it, not `recycleTask`.
			fiber.parent = task.parent;
			task.holds_parent_ref = false;
			fiber.saved_scope = task.scope;
			if (build_options.trace_frames) {
				trace.inheritStackTrace(fiber, &task.trace_frames, task.trace_top);
			}
			// A parent awaits the thief through `task.obligation`. Recording it as
			// the root obligation makes `feart_unwind` deliver an error to the
			// same obligation `thiefTrampoline` would otherwise fulfill.
			fiber.root_obligation = task.obligation;
			log.trace_scheduling(.fiber_enqueue, @intFromPtr(fiber), 0xFF, worker.id);
			enqueueFiber(fiber);
			continue;
		}

		// 3. Nothing to do. Sleep a little rather than burn the CPU.
		std.atomic.spinLoopHint();
		std.Io.sleep(process.runtime_io, std.Io.Duration.fromMilliseconds(1), .awake) catch {};
	}

	gc.dump_rc_delta();
	signals.deinitThreadSignalStacks();
	if (worker.id != 0) {
		gc.unregister_thread();
	}
}
