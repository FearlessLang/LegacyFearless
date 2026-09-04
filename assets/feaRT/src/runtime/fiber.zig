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

pub const STACK_SIZE = 2 * 1024 * 1024;
/// One page: 4KB on x86_64, 16KB on macOS ARM.
const GUARD_SIZE = std.heap.page_size_min;

/// Stamped into every `Fiber` header so `currentFiber` can tell a real fiber
/// mapping from whatever a masked stack pointer lands on. Cleared before the
/// mapping goes away, which catches a use after destroy.
pub const FIBER_MAGIC: u64 = 0xF1BE_4A11_0000_F00D;

/// Bytes at the base of the mapping holding the `Fiber` itself, page-rounded so
/// the guard page that follows it is aligned.
pub const FIBER_REGION = std.mem.alignForward(usize, @sizeOf(Fiber), std.heap.page_size_min);

/// The fiber whose stack the caller is running on.
///
/// A fiber's mapping is `STACK_SIZE`-aligned with the `Fiber` at its base, so
/// masking the stack pointer finds it in two instructions with no memory access
/// -- and, unlike a thread-local, it cannot go stale when a fiber resumes on a
/// different OS thread.
///
/// Valid only on a mapped fiber stack. Generated Fearless code always is, which
/// is what lets this skip any validity check. NOT valid on a worker's scheduler
/// fiber, which runs on the OS thread stack.
pub inline fn currentFiber() *Fiber {
	const sp: usize = switch (builtin.cpu.arch) {
		.x86_64 => asm ("mov %%rsp, %[ret]"
			: [ret] "=r" (-> usize),
		),
		.aarch64 => asm ("mov %[ret], sp"
			: [ret] "=r" (-> usize),
		),
		else => @compileError("Unsupported architecture"),
	};
	const f: *Fiber = @ptrFromInt(sp & ~@as(usize, STACK_SIZE - 1));
	// Free in ReleaseFast. In a safe mode, a call from a non-fiber stack panics
	// instead of corrupting memory.
	if (std.debug.runtime_safety) {
		std.debug.assert(f.magic == FIBER_MAGIC);
	}
	return f;
}

/// The fiber the calling thread is running, or null on a worker's scheduler
/// stack.
///
/// For paths that may run off a fiber stack, where masking `sp` would land on
/// unrelated memory. It costs the thread-local read that `currentFiber` avoids,
/// so keep it off the hot paths. On the recovery stack it deliberately gives the
/// *faulting* fiber, which is the one an unwind must walk.
pub fn currentFiberOrNull() ?*Fiber {
	const worker = @import("worker.zig").getCurrentWorker() orelse return null;
	const fiber = worker.current_fiber orelse return null;
	return if (fiber.hasMappedStack()) fiber else null;
}

/// One `STACK_SIZE`-aligned, `STACK_SIZE`-sized read/write mapping, which is
/// what makes `currentFiber`'s mask work. Over-allocate and trim, because mmap
/// gives no way to ask for alignment.
pub fn mapAlignedBlock() !usize {
	const raw = try std.posix.mmap(
		null,
		2 * STACK_SIZE,
		.{ .READ = true, .WRITE = true },
		.{ .TYPE = .PRIVATE, .ANONYMOUS = true },
		-1,
		0,
	);
	const raw_addr = @intFromPtr(raw.ptr);
	const base_addr = std.mem.alignForward(usize, raw_addr, STACK_SIZE);

	if (base_addr > raw_addr) {
		std.posix.munmap(raw[0 .. base_addr - raw_addr]);
	}
	const tail = base_addr + STACK_SIZE;
	const raw_end = raw_addr + 2 * STACK_SIZE;
	if (raw_end > tail) {
		const tail_ptr: [*]align(std.heap.page_size_min) u8 = @ptrFromInt(tail);
		std.posix.munmap(tail_ptr[0 .. raw_end - tail]);
	}
	return base_addr;
}

pub fn unmapAlignedBlock(base_addr: usize) void {
	const base: [*]align(std.heap.page_size_min) u8 = @ptrFromInt(base_addr);
	std.posix.munmap(base[0..STACK_SIZE]);
}

pub const Fiber = struct {
	/// Saved stack pointer -- MUST be first field (offset 0) for assembly.
	sp: usize,

	/// `FIBER_MAGIC` while the mapping is live, and 0 once it is on its way out.
	/// See `currentFiber`.
	magic: u64 = 0,

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

	/// APM tokens. Incremented by `tryPromote`, halved on a promotion attempt.
	///
	/// Atomic because a thief credits it from its own thread while the owner runs:
	/// each thief credits when its own work finishes, not when the parent joins.
	/// The owner uses a relaxed load/store pair, so a credit that lands between
	/// them is lost. APM 5.3 already forfeits the tokens of a fiber that never
	/// fulfills, and a refund can only move tokens, never make them.
	tokens: std.atomic.Value(u32) = std.atomic.Value(u32).init(0),

	/// False wherever promotion must be suppressed: a scheduler fiber, a fiber
	/// past its last frame, and the pseudo-fiber backing the recovery stack.
	vpf_enabled: bool = false,

	/// True while a panic on this fiber is being turned into a Fearless error.
	/// That conversion runs generated code, which can promote and can panic
	/// again, and a second panic has no way to report itself through the first.
	in_panic: bool = false,

	/// The cancellation scope active on this fiber's stack. A thief fiber seeds
	/// it from the promoter's scope.
	saved_scope: ?*scope_mod.Scope = null,

	/// The worker running this fiber, as its id plus one, or 0 for a fiber no
	/// worker has picked up. The worker writes it before each switch in, so
	/// reference counting reads it off the masked stack pointer rather than a
	/// thread-local.
	worker_id: u32 = 0,

	/// Set for every fiber whose completion a parent awaits: thieves and
	/// Try/CapTry children. The trampoline fulfills it on a normal finish,
	/// `feart_unwind` fulfills it with an error payload on an abandon.
	root_obligation: ?*shadow_stack.JoinObligation = null,

	/// The fiber this one was promoted from, whose token counter takes the join
	/// credit. Null for a fiber with no parent. Holding the fiber rather than a
	/// bare pointer to its counter is what lets the credit release the mapping
	/// reference it travels with.
	parent: ?*Fiber = null,

	/// Live references to this fiber's mapping: one for the fiber itself, and one
	/// for every promotion of it that can still pay a join credit into `tokens`.
	/// Whoever brings it to zero unmaps the block.
	///
	/// The `Fiber` sits at the base of the mapping, so `tokens` goes with it. An
	/// abandoning parent does not wait for its thieves -- `feart_unwind` fulfills
	/// the child obligation, which releases the thief, and then finishes -- so
	/// without this the block is unmapped while a thief is still on its way to
	/// `creditParentTokens`.
	mapping_refs: std.atomic.Value(u32) = std.atomic.Value(u32).init(1),

	/// Intrusive link for the worker pool's shared ready list. A link inside the
	/// fiber is what makes that list unbounded with no buffer to retire.
	shared_ready_next: ?*Fiber = null,


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
	/// Runs exactly once per fiber that has a parent: the entry path credits
	/// before it fulfills, and `feart_unwind` credits on the path that abandons.
	/// A fiber that never fulfills forfeits its tokens, matching APM 5.3.
	///
	/// The parent may already be destroyed by the time this runs, because an
	/// abandoning parent releases its thieves without waiting for them. The
	/// credit stays safe because the promotion took a reference to the parent's
	/// mapping, which this call is the last holder of.
	pub fn creditParentTokens(self: *Fiber) void {
		const parent = self.parent orelse return;
		self.parent = null;
		_ = parent.tokens.fetchAdd(self.tokens.load(.monotonic), .monotonic);
		// Last use of the parent's memory on this path, so the reference the
		// promotion took goes back here.
		parent.releaseMapping();
	}

	/// Drop one reference to this fiber's mapping, unmapping it at zero.
	///
	/// Nothing may touch the fiber afterwards: the struct lives in the mapping
	/// this can unmap.
	pub fn releaseMapping(self: *Fiber) void {
		if (self.mapping_refs.fetchSub(1, .release) != 1) return;
		// Pairs with the releases above, so the unmap follows every write the
		// other holders made through this mapping.
		_ = self.mapping_refs.load(.acquire);
		unmapAlignedBlock(@intFromPtr(self));
	}

	/// Take a reference for a promotion, so the promoter's `tokens` stays
	/// writable until that promotion pays its join credit back.
	pub fn retainMapping(self: *Fiber) void {
		_ = self.mapping_refs.fetchAdd(1, .monotonic);
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
	///
	/// The mapping is `STACK_SIZE`-aligned and laid out base-upwards as the
	/// `Fiber`, a guard page, then the stack growing down from the top. That
	/// alignment is what `currentFiber` masks for, and the guard sits between the
	/// two so an overflow faults instead of overwriting the header.
	pub fn create(
		entry_fn: *const fn (*Fiber) void,
		context: ?*anyopaque,
	) !*Fiber {
		const base_addr = try mapAlignedBlock();

		const guard: *align(std.heap.page_size_min) anyopaque = @ptrFromInt(base_addr + FIBER_REGION);
		const mp_rc = std.posix.system.mprotect(guard, GUARD_SIZE, .{});
		if (mp_rc != 0) return error.OutOfMemory;

		const stack_bottom: [*]align(std.heap.page_size_min) u8 =
			@ptrFromInt(base_addr + FIBER_REGION + GUARD_SIZE);

		const fiber: *Fiber = @ptrFromInt(base_addr);
		fiber.* = .{
			.sp = 0,
			.stack_bottom = stack_bottom,
			.stack_size = STACK_SIZE - FIBER_REGION - GUARD_SIZE,
			.entry_fn = entry_fn,
			.context = context,
			.state = .Fresh,
			.vpf_enabled = true,
			.magic = FIBER_MAGIC,
		};

		// The initial stack makes switchTo land at fiber_entry with the fiber
		// pointer in the callee-saved register fiber_entry reads.
		// Below the end of the block, and `sp` only decreases from here, so every
		// live stack pointer masks back to `base_addr`.
		const aligned_top = ((base_addr + STACK_SIZE) & ~@as(usize, 15)) - 16;

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

		return fiber;
	}

	/// Nothing may touch `self` afterwards: the struct lives in the mapping this
	/// drops its last reference to.
	///
	/// The block outlives this call while a promotion of this fiber has yet to
	/// pay its join credit. It is dead either way: nothing runs on the stack, and
	/// the only reader left is `creditParentTokens` writing `tokens`.
	pub fn destroy(self: *Fiber) void {
		// Catches a destroy of a worker's scheduler fiber, which owns no mapping.
		std.debug.assert(self.hasMappedStack());

		// Before the mapping can go away, so a second destroy or a `currentFiber` on
		// a stale stack pointer fails the magic assert instead of using a dead header.
		self.magic = 0;

		self.releaseMapping();
	}
};

extern fn fiber_entry() void;
pub extern fn switchTo(from: *Fiber, to: *Fiber) void;

/// Switch fibers.
///
/// The shadow stack, tokens and scope live in the incoming fiber's header, so
/// they arrive with the stack pointer. There is nothing to swap and no window in
/// which the heartbeat could see a mismatched pair.
pub fn switchFiber(from: *Fiber, to: *Fiber) void {
	switchTo(from, to);
}

/// Called from the assembly `fiber_entry`. Exported as a C symbol.
export fn fiber_trampoline(fiber: *Fiber) callconv(.c) noreturn {
	fiber.state = .Running;
	if (fiber.entry_fn) |entry| {
		entry(fiber);
	}
	fiber.state = .Done;
	fiber.vpf_enabled = false;

	const worker_mod = @import("worker.zig");
	const worker = worker_mod.getCurrentWorker().?;

	switchTo(fiber, &worker.scheduler_fiber);
	unreachable;
}
