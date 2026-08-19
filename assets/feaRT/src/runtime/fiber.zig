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

const STACK_SIZE = 2 * 1024 * 1024;
/// One page: 4KB on x86_64, 16KB on macOS ARM.
const GUARD_SIZE = std.heap.page_size_min;

const gc = @import("gc.zig");

pub const Fiber = struct {
	/// Saved stack pointer -- MUST be first field (offset 0) for assembly.
	sp: usize,
	stack_bottom: [*]align(std.heap.page_size_min) u8,
	stack_size: usize,
	entry_fn: ?*const fn (*Fiber) void,
	context: ?*anyopaque,
	state: State,

	/// False from the moment a fiber is enqueued before it parks (the CAS-fail
	/// path in `wait`) until the scheduler's `switchFiber` returns with its
	/// state saved. A worker must not switch to the fiber while it is false.
	resume_gate: std.atomic.Value(bool) = std.atomic.Value(bool).init(true),

	/// Per-fiber shadow stack: a fiber may park and resume on different workers.
	shadow_frames: [MAX_SHADOW_DEPTH]ShadowFrame = undefined,
	shadow_cursor: shadow_stack.ShadowCursor = .{},

	/// For crash reporting. Zero-size when `trace_frames` is off.
	trace_frames: [if (build_options.trace_frames) TRACE_CAP else 0]TraceFrame = undefined,
	trace_top: usize = 0,

	/// APM tokens. Incremented by `tryPromote`, halved on a promotion attempt,
	/// swapped in through `tls_tokens_ptr` on a fiber switch.
	tokens: u32 = 0,

	/// The cancellation scope active on this fiber's stack. `switchFiber`
	/// swaps it with TLS `active_scope`, so push/pop touches TLS only. A thief
	/// fiber seeds it from the promoter's scope.
	saved_scope: ?*scope_mod.Scope = null,

	/// Set for every fiber whose completion a parent awaits: thieves and
	/// Try/CapTry children. The trampoline fulfills it on a normal finish,
	/// `feart_unwind` fulfills it with an error payload on an abandon.
	root_obligation: ?*shadow_stack.JoinObligation = null,

	/// The parent fiber's tokens counter. Null for a fiber with no parent.
	parent_tokens_ptr: ?*u32 = null,

	/// Intrusive link for the worker pool's shared ready list. A link inside the
	/// fiber is what makes that list unbounded with no buffer to retire.
	shared_ready_next: ?*Fiber = null,

	/// Slot in the GC's live-fiber registry, so `destroy` clears it in constant
	/// time. See gc.zig for why stacks are scanned through `push_other_roots`.
	registry_slot: gc.RegSlot = .{},

	pub const State = enum {
		Fresh,
		Running,
		Ready,
		Parked,
		Done,
	};

	/// Hand unspent APM tokens back to the parent (APM 4.3 join credit). Sound
	/// because promotion destroys `TOKENS_THRESHOLD` tokens, so a refund can
	/// redistribute but never manufacture.
	///
	/// MUST run before the obligation that wakes the parent is fulfilled: after
	/// that the parent may be destroyed and `parent_tokens_ptr` dangles. A
	/// fiber that never fulfills forfeits its tokens, matching APM 5.3.
	pub fn creditParentTokens(self: *Fiber) void {
		const ptr = self.parent_tokens_ptr orelse return;
		self.parent_tokens_ptr = null;
		ptr.* += self.tokens;
	}

	/// False for a scheduler fiber, which runs on the OS thread's stack.
	pub fn hasMappedStack(self: *const Fiber) bool {
		return self.stack_size > 0;
	}

	pub fn stackTop(self: *const Fiber) *anyopaque {
		return @ptrCast(self.stack_bottom + self.stack_size);
	}

	/// A hit means the faulting access was a stack overflow, which is how the ND
	/// signal handler tells one from a wild write. A scheduler fiber never hits.
	pub fn guardContains(self: *const Fiber, addr: usize) bool {
		if (!self.hasMappedStack()) return false;
		const hi = @intFromPtr(self.stack_bottom);
		const lo = hi - GUARD_SIZE;
		return addr >= lo and addr < hi;
	}

	/// TODO: each fiber costs two VMAs (guard page + stack), so `vm.max_map_count`
	/// caps live fibers at roughly half its value -- about 500K at the usual
	/// 1048576. Pool the mmap'd stacks if that limit is ever reached.
	pub fn create(
		entry_fn: *const fn (*Fiber) void,
		context: ?*anyopaque,
	) !*Fiber {
		const total_size = GUARD_SIZE + STACK_SIZE;
		const base = try std.posix.mmap(
			null,
			total_size,
			.{ .READ = true, .WRITE = true },
			.{ .TYPE = .PRIVATE, .ANONYMOUS = true },
			-1,
			0,
		);

		// Guard page at the lowest address.
		const guard: *align(std.heap.page_size_min) anyopaque = @ptrCast(@alignCast(base.ptr));
		const mp_rc = std.posix.system.mprotect(guard, GUARD_SIZE, .{});
		if (mp_rc != 0) return error.OutOfMemory;

		const stack_bottom: [*]align(std.heap.page_size_min) u8 = @alignCast(base.ptr + GUARD_SIZE);

		const fiber = try gc.allocator.create(Fiber);
		fiber.* = .{
			.sp = 0,
			.stack_bottom = stack_bottom,
			.stack_size = STACK_SIZE,
			.entry_fn = entry_fn,
			.context = context,
			.state = .Fresh,
		};

		// The initial stack makes switchTo land at fiber_entry with the fiber
		// pointer in the callee-saved register fiber_entry reads.
		const stack_top_addr = @intFromPtr(stack_bottom) + STACK_SIZE;
		const aligned_top = stack_top_addr & ~@as(usize, 15);

		switch (builtin.cpu.arch) {
			.x86_64 => {
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
				// 20 slots (10 stp pairs) in the assembly's bottom-to-top ldp order.
				const num_slots = 20;
				const sp_base = aligned_top - num_slots * @sizeOf(usize);
				const stack_slots: [*]usize = @ptrFromInt(sp_base);
				stack_slots[0] = 0; // d8
				stack_slots[1] = 0; // d9
				stack_slots[2] = 0; // d10
				stack_slots[3] = 0; // d11
				stack_slots[4] = 0; // d12
				stack_slots[5] = 0; // d13
				stack_slots[6] = 0; // d14
				stack_slots[7] = 0; // d15
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

		// Only now that `sp` points at the prepared frame: the mark hook scans
		// `sp`..stack top and may run the instant the slot goes live.
		fiber.registry_slot = gc.registerFiber(@ptrCast(fiber));

		return fiber;
	}

	pub fn destroy(self: *Fiber) void {
		// `recycleDestroy` zeroes the struct, so a second destroy arrives with
		// no mapped stack. Catching it here beats an underflow in the munmap
		// length. Also catches a destroy of a worker's scheduler fiber.
		std.debug.assert(self.hasMappedStack());

		// Strictly before the munmap: the mark hook scans whatever a live slot
		// points at, and an unmapped stack faults.
		gc.unregisterFiber(self.registry_slot);

		const base: [*]align(std.heap.page_size_min) u8 = @ptrFromInt(@intFromPtr(self.stack_bottom) - GUARD_SIZE);
		std.posix.munmap(@alignCast(base[0 .. GUARD_SIZE + self.stack_size]));

		// The GC is for cycle collection only, never a fallback for ordinary
		// frees.
		gc.recycleDestroy(Fiber, self, .fiber_destroy);
	}
};

extern fn fiber_entry() void;
pub extern fn switchTo(from: *Fiber, to: *Fiber) void;

/// Switch fibers, swapping the thread-local shadow stack pointers and updating
/// the GC's stack bounds.
pub fn switchFiber(from: *Fiber, to: *Fiber) void {
	// Snapshot the outgoing fiber's scope TLS so it survives its next resume.
	from.saved_scope = scope_mod.active_scope;

	// Cursor first: the heartbeat handler checks it before shadow_stack, so a
	// null cursor makes it return early during the transition.
	shadow_stack.shadow_cursor = null;
	heartbeat.tls_tokens_ptr = null;
	// The handler runs on this thread and so sees program order; a compiler
	// barrier against reordering the null write is enough.
	asm volatile ("" ::: .{ .memory = true });

	if (to.hasMappedStack()) {
		shadow_stack.shadow_stack = &to.shadow_frames;
		// shadow_stack must land before the cursor becomes non-null, so the
		// heartbeat never sees a mismatched pair.
		asm volatile ("" ::: .{ .memory = true });
		shadow_stack.shadow_cursor = &to.shadow_cursor;
		heartbeat.tls_tokens_ptr = &to.tokens;
		scope_mod.active_scope = to.saved_scope;
	} else {
		shadow_stack.shadow_stack = null;
		scope_mod.active_scope = null;
	}

	// Same null-first ordering as the shadow stack. Comptime-elides when
	// trace_frames is off.
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

	// mem_base and the stack pointer disagree from the setStackBottom below
	// until the landing side's endStackSwitch, so block cycle collection.
	gc.beginStackSwitch();

	if (to.hasMappedStack()) {
		gc.setStackBottom(to.stackTop());
	}

	switchTo(from, to);

	// Back on `from`: restore the GC stack bounds.
	if (!from.hasMappedStack()) {
		// The scheduler runs on the OS stack, so let the GC re-detect it.
		gc.setStackBottom(gc.currentStackBase());
	}
	gc.endStackSwitch();
}

/// Called from the assembly `fiber_entry`. Exported as a C symbol.
export fn fiber_trampoline(fiber: *Fiber) callconv(.c) noreturn {
	// Landing side of the switch that started this fiber.
	gc.endStackSwitch();
	fiber.state = .Running;
	if (fiber.entry_fn) |entry| {
		entry(fiber);
	}
	fiber.state = .Done;

	const worker_mod = @import("worker.zig");
	const worker = worker_mod.getCurrentWorker().?;

	// Otherwise the heartbeat could read stale shadow frames whose locals point
	// into this fiber's about-to-be-unmapped stack.
	shadow_stack.shadow_cursor = null;
	shadow_stack.shadow_stack = null;
	heartbeat.tls_tokens_ptr = null;
	scope_mod.active_scope = null;
	if (build_options.trace_frames) {
		shadow_stack.trace_top = null;
		shadow_stack.trace_stack = null;
	}

	// mem_base points at the OS stack while the stack pointer is still on this
	// fiber's; the scheduler ends the window after its switchFiber returns.
	gc.beginStackSwitch();
	gc.setStackBottom(gc.currentStackBase());

	switchTo(fiber, &worker.scheduler_fiber);
	unreachable;
}
