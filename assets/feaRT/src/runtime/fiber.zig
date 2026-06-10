const std = @import("std");
const builtin = @import("builtin");
const shadow_stack = @import("shadow_stack.zig");
const heartbeat = @import("heartbeat.zig");
const scope_mod = @import("scope.zig");
const trace = @import("./errors/trace.zig");
const build_options = @import("build_options");

const ShadowFrame = shadow_stack.ShadowFrame;
const MAX_SHADOW_DEPTH = shadow_stack.MAX_SHADOW_DEPTH;
const TraceFrame = trace.TraceFrame;
const TRACE_CAP = shadow_stack.TRACE_CAP;

/// Default fiber stack size: 2MB
const STACK_SIZE = 2 * 1024 * 1024;
/// Guard page size: one page (4KB on x86_64, 16KB on macOS ARM)
const GUARD_SIZE = std.heap.page_size_min;

const gc = @import("gc.zig");

pub const Fiber = struct {
	/// Saved stack pointer — MUST be first field (offset 0) for assembly.
	sp: usize,
	stack_bottom: [*]align(std.heap.page_size_min) u8,
	stack_size: usize,
	entry_fn: ?*const fn (*Fiber) void,
	context: ?*anyopaque,
	state: State,

	/// Gate to prevent a worker from switching to this fiber before its state
	/// has been fully saved. Set to false when the fiber will be enqueued before
	/// it parks (CAS-fail path in wait()), set back to true by the scheduler
	/// after the park's switchFiber returns (fiber state is saved).
	resume_gate: std.atomic.Value(bool) = std.atomic.Value(bool).init(true),

	/// Per-fiber shadow stack (avoids corruption when fibers park/resume on different workers)
	shadow_frames: [MAX_SHADOW_DEPTH]ShadowFrame = undefined,
	shadow_top: usize = 0,

	/// Per-fiber trace stack for crash reporting. Zero-size (and the TLS swap in
	/// switchFiber comptime-elides) when the `trace_frames` build option is off.
	trace_frames: [if (build_options.trace_frames) TRACE_CAP else 0]TraceFrame = undefined,
	trace_top: usize = 0,

	/// APM tokens counter. Incremented by tryPromote; halved on promotion
	/// attempts. Swapped in via tls_tokens_ptr on fiber switch.
	tokens: u32 = 0,

	/// Points at the parent fiber's tokens counter for thief fibers, so that
	/// when this fiber finishes the scheduler can credit any leftover tokens
	/// back to the parent. Null for fibers with no parent (root, non-thief).
	parent_tokens_ptr: ?*u32 = null,

	/// Per-fiber slot for the cancellation scope currently active on this
	/// fiber's stack. switchFiber saves TLS active_scope into the outgoing
	/// fiber's slot and loads the incoming slot into TLS — so push/pop only
	/// touches TLS and the fiber struct mirrors it across switches. Thief
	/// fibers seed this from the parent's scope at creation time (see
	/// workerLoop's thief path in worker.zig).
	saved_scope: ?*scope_mod.Scope = null,

	/// Set for every fiber whose completion a parent awaits via an obligation
	/// (thief fibers; Try/CapTry child fibers). When the fiber finishes
	/// normally the obligation is fulfilled by the relevant trampoline; when
	/// the fiber is abandoned by `feart_unwind` the unwinder fulfills this same
	/// obligation with the tag-typed error payload — unifying both hand-offs.
	root_obligation: ?*shadow_stack.JoinObligation = null,

	pub const State = enum {
		Fresh,
		Running,
		Ready,
		Parked,
		Done,
	};

	/// True if this fiber has an mmap'd stack (as opposed to the scheduler fiber).
	pub fn hasMappedStack(self: *const Fiber) bool {
		return self.stack_size > 0;
	}

	/// Get the top of this fiber's stack (highest address).
	pub fn stackTop(self: *const Fiber) *anyopaque {
		return @ptrCast(self.stack_bottom + self.stack_size);
	}

	/// True if `addr` lands in this fiber's guard page — i.e. the access that
	/// faulted was a stack overflow (the machine stack grew down past
	/// `stack_bottom` into the PROT_NONE guard). Used by the ND signal handler
	/// to classify a SIGSEGV as a recoverable stack-overflow rather than a wild
	/// memory access. The scheduler fiber (no mapped stack) never matches.
	pub fn guardContains(self: *const Fiber, addr: usize) bool {
		if (!self.hasMappedStack()) return false;
		const hi = @intFromPtr(self.stack_bottom);
		const lo = hi - GUARD_SIZE;
		return addr >= lo and addr < hi;
	}

	/// Create a new fiber with an mmap'd stack + guard page.
	pub fn create(
		entry_fn: *const fn (*Fiber) void,
		context: ?*anyopaque,
	) !*Fiber {
		// mmap stack: guard_page + usable_stack
		const total_size = GUARD_SIZE + STACK_SIZE;
		const base = try std.posix.mmap(
			null,
			total_size,
			.{ .READ = true, .WRITE = true },
			.{ .TYPE = .PRIVATE, .ANONYMOUS = true },
			-1,
			0,
		);

		// Guard page at the bottom (lowest address)
		const guard: *align(std.heap.page_size_min) anyopaque = @ptrCast(@alignCast(base.ptr));
		const mp_rc = std.posix.system.mprotect(guard, GUARD_SIZE, .{});
		if (mp_rc != 0) return error.OutOfMemory;

		const stack_bottom: [*]align(std.heap.page_size_min) u8 = @alignCast(base.ptr + GUARD_SIZE);

		// Register fiber stack with BDW-GC as root range so it scans for GC pointers
		gc.addRoots(
			@as(*anyopaque, @ptrCast(stack_bottom)),
			@as(*anyopaque, @ptrCast(stack_bottom + STACK_SIZE)),
		);

		// Allocate the Fiber struct itself via GC so it's tracked
		const fiber = try gc.allocator.create(Fiber);
		fiber.* = .{
			.sp = 0,
			.stack_bottom = stack_bottom,
			.stack_size = STACK_SIZE,
			.entry_fn = entry_fn,
			.context = context,
			.state = .Fresh,
		};

		// Set up the initial stack so switchTo lands at fiber_entry
		// with the fiber pointer in the appropriate register.
		const stack_top_addr = @intFromPtr(stack_bottom) + STACK_SIZE;
		const aligned_top = stack_top_addr & ~@as(usize, 15);

		switch (builtin.cpu.arch) {
			.x86_64 => {
				// 7 slots: r15, r14, r13, r12=Fiber*, rbp, rbx, ret_addr=fiber_entry
				const num_slots = 7;
				const sp_base = aligned_top - num_slots * @sizeOf(usize);
				const stack_slots: [*]usize = @ptrFromInt(sp_base);
				stack_slots[0] = 0; // r15
				stack_slots[1] = 0; // r14
				stack_slots[2] = 0; // r13
				stack_slots[3] = @intFromPtr(fiber); // r12 = Fiber*
				stack_slots[4] = 0; // rbp
				stack_slots[5] = 0; // rbx
				stack_slots[6] = @intFromPtr(&fiber_entry); // return address
				fiber.sp = sp_base;
			},
			.aarch64 => {
				// 20 slots (10 stp pairs), matching the ldp restore order in assembly.
				// The assembly restores bottom-to-top: first ldp = slots[0..1], etc.
				const num_slots = 20;
				const sp_base = aligned_top - num_slots * @sizeOf(usize);
				const stack_slots: [*]usize = @ptrFromInt(sp_base);
				// SIMD callee-saved (d8-d15, stored as 64-bit)
				stack_slots[0] = 0; // d8
				stack_slots[1] = 0; // d9
				stack_slots[2] = 0; // d10
				stack_slots[3] = 0; // d11
				stack_slots[4] = 0; // d12
				stack_slots[5] = 0; // d13
				stack_slots[6] = 0; // d14
				stack_slots[7] = 0; // d15
				// GP callee-saved
				stack_slots[8] = @intFromPtr(fiber); // x19 = Fiber*
				stack_slots[9] = 0; // x20
				stack_slots[10] = 0; // x21
				stack_slots[11] = 0; // x22
				stack_slots[12] = 0; // x23
				stack_slots[13] = 0; // x24
				stack_slots[14] = 0; // x25
				stack_slots[15] = 0; // x26
				stack_slots[16] = 0; // x27
				stack_slots[17] = 0; // x28
				stack_slots[18] = 0; // x29 (fp)
				stack_slots[19] = @intFromPtr(&fiber_entry); // x30 (lr)
				fiber.sp = sp_base;
			},
			else => @compileError("Unsupported architecture"),
		}

		return fiber;
	}

	/// Destroy a fiber and unmap its stack.
	pub fn destroy(self: *Fiber) void {
		gc.removeRoots(
			@as(*anyopaque, @ptrCast(self.stack_bottom)),
			@as(*anyopaque, @ptrCast(self.stack_bottom + self.stack_size)),
		);

		const base: [*]align(std.heap.page_size_min) u8 = @ptrFromInt(@intFromPtr(self.stack_bottom) - GUARD_SIZE);
		std.posix.munmap(@alignCast(base[0 .. GUARD_SIZE + self.stack_size]));

		// Free the Fiber struct itself — we only lean on the GC for cycle
		// collection, never as a fallback for ordinary frees.
		gc.recycleDestroy(Fiber, self, .fiber_destroy);
	}
};

// Assembly-defined symbols
extern fn fiber_entry() void;
pub extern fn switchTo(from: *Fiber, to: *Fiber) void;

/// Switch from one fiber to another, swapping thread-local shadow stack pointers
/// and updating GC's stack bounds.
pub fn switchFiber(from: *Fiber, to: *Fiber) void {
	// Snapshot the outgoing fiber's scope TLS before we clobber anything,
	// so it survives the next time this fiber is resumed.
	from.saved_scope = scope_mod.active_scope;

	// Null out shadow_top FIRST so the heartbeat handler returns early
	// during the transition (it checks shadow_top before shadow_stack).
	shadow_stack.shadow_top = null;
	heartbeat.tls_tokens_ptr = null;
	// Compiler barrier: prevent the compiler from reordering or eliminating
	// the null write. Signal handlers on the same thread see program-order
	// writes, so a compiler barrier is sufficient (no CPU fence needed).
	asm volatile ("" ::: .{ .memory = true });

	if (to.hasMappedStack()) {
		shadow_stack.shadow_stack = &to.shadow_frames;
		// Compiler barrier: ensure shadow_stack is updated before shadow_top
		// becomes non-null, so the heartbeat never sees a mismatched pair.
		asm volatile ("" ::: .{ .memory = true });
		shadow_stack.shadow_top = &to.shadow_top;
		heartbeat.tls_tokens_ptr = &to.tokens;
		scope_mod.active_scope = to.saved_scope;
	} else {
		shadow_stack.shadow_stack = null;
		scope_mod.active_scope = null;
	}

	// Swap the trace stack TLS in lockstep with the shadow stack, mirroring the
	// null-first ordering above. Comptime-elides entirely when trace_frames is off.
	if (build_options.trace_frames) {
		shadow_stack.trace_top = null;
		asm volatile ("" ::: .{ .memory = true });
		if (to.hasMappedStack()) {
			shadow_stack.trace_stack = &to.trace_frames;
			asm volatile ("" ::: .{ .memory = true });
			shadow_stack.trace_top = &to.trace_top;
		} else {
			shadow_stack.trace_stack = null;
		}
	}

	// Update GC stack bottom for the target fiber before switching
	if (to.hasMappedStack()) {
		gc.setStackBottom(to.stackTop());
	}

	switchTo(from, to);

	// After returning (we're back on 'from'), restore GC stack bounds
	if (!from.hasMappedStack()) {
		// We returned to the scheduler (OS stack) — let GC re-detect
		gc.setStackBottom(gc.currentStackBase());
	}
}

/// Trampoline called from assembly fiber_entry.
/// Exported as C symbol so the assembly can `call` it.
export fn fiber_trampoline(fiber: *Fiber) callconv(.c) noreturn {
	fiber.state = .Running;
	if (fiber.entry_fn) |entry| {
		entry(fiber);
	}
	fiber.state = .Done;

	// Switch back to the scheduler fiber (worker loop)
	const worker_mod = @import("worker.zig");
	const worker = worker_mod.getCurrentWorker().?;

	// Clear TLS shadow stack before switching to scheduler.
	// Without this, the heartbeat could access stale shadow frames
	// with locals pointers into this fiber's about-to-be-unmapped stack.
	shadow_stack.shadow_top = null;
	shadow_stack.shadow_stack = null;
	heartbeat.tls_tokens_ptr = null;
	scope_mod.active_scope = null;
	if (build_options.trace_frames) {
		shadow_stack.trace_top = null;
		shadow_stack.trace_stack = null;
	}

	// Restore GC to scheduler's OS stack before switching
	gc.setStackBottom(gc.currentStackBase());

	switchTo(fiber, &worker.scheduler_fiber);
	unreachable;
}
