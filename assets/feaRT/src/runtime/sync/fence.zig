//! A sequentially consistent CPU memory fence.
//!
//! Not the `asm volatile ("" ::: .{ .memory = true })` barrier of `fiber.zig`,
//! `shadow_stack.zig` and `worker.zig`. Those order a thread against a signal
//! handler that interrupts it, which is a compiler-only problem, because the
//! handler runs on the same thread and already sees program order. This one
//! orders a thread against a different thread.
//!
//! It is needed for a store followed by a load of a second location that must
//! stay in that order. x86 reorders that pair and nothing else, so it is the
//! one place the hardware needs telling.

const builtin = @import("builtin");

/// Orders every access before the call against every access after it, in one
/// total order shared by all threads. C11
/// `atomic_thread_fence(memory_order_seq_cst)`, which Zig 0.16 does not expose.
pub inline fn seqCst() void {
	switch (builtin.cpu.arch) {
		// A `lock`ed op on a dead stack slot is cheaper, but the callers keep this
		// off their hot path, so use the instruction that says what it is.
		.x86_64 => asm volatile ("mfence" ::: .{ .memory = true }),
		.aarch64 => asm volatile ("dmb ish" ::: .{ .memory = true }),
		else => @compileError("no sequentially consistent fence for this target"),
	}
}
