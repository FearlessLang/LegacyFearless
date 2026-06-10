const std = @import("std");
const shadow_stack = @import("../shadow_stack.zig");
const worker_mod = @import("../worker.zig");
const fiber_mod = @import("../fiber.zig");
const error_rt = @import("../error.zig");
const objs = @import("../objs.zig");
const gc = @import("../gc.zig");
const heartbeat = @import("../heartbeat.zig");
const scope_mod = @import("../scope.zig");
const str_rt = @import("../intrinsics/str.zig");
const trace = @import("./trace.zig");
const build_options = @import("build_options");

const h = objs.hash_signature;
const FatPtr = objs.FatPtr;
const CLAIMED = shadow_stack.CLAIMED;
const writeStderr = @import("./io.zig").writeStderr;

/// Abandon the current fiber and deliver `payload` (a tag-typed error FatPtr,
/// see `error.zig`) to the parent.
///
/// Walks the current fiber's shadow stack newest→oldest, abandoning each frame:
/// promoted frames hand the error to their thief (which is parked waiting for
/// our `r1`) so it can never deadlock; every frame's locals get a best-effort
/// release (the GC backstops anything missed). At the root we fulfill the
/// fiber's `root_obligation` with the payload and switch to the scheduler, so
/// this never returns.
///
/// `Error!` lowers to this directly rather than threading a checked return
/// through every `rt.call`, which keeps the deeply-nested call codegen
/// untouched. The cost is that abandoned non-promoted Zig frames skip their
/// `defer rc_decrement`s. This is fine because our lazy cycle collection (in gc.zig)
/// will catch them if it happens a lot.
pub fn feart_unwind(payload: FatPtr) noreturn {
    gc.disable_cycle_collection();
    const shadow_top_ptr = shadow_stack.getShadowTop() orelse dieNoFiber();

    var top = shadow_top_ptr.*;
    while (top > 0) {
        top -= 1;
        const ss = shadow_stack.getShadowStack().?;
        const frame = &ss[top];

        // Claim the frame the same way `popAndClaim` does. A non-null result
        // (other than CLAIMED) means the frame was promoted: its thief is
        // parked on `child_obligation` waiting for our `r1`. Deliver the error
        // there — with its own reference — so the thief unblocks instead of
        // hanging. We deliberately do NOT recycle the returned join obligation:
        // the thief still owns it and will fulfill it from its own completion
        // or unwind. Nobody waits on it now, so the GC reclaims it; recycling
        // it into a pool slot a live thief could still write to would corrupt
        // an unrelated promotion.
        const prev = frame.join_obligation.cmpxchgStrong(null, CLAIMED, .acquire, .acquire);
        if (prev) |obl| {
            if (obl != CLAIMED) shadow_stack.fulfillChildObligation(top, payload.share());
        }

        // Best-effort release of this frame's locals; drop_fn is documented as
        // best-effort (it may never run if the GC reclaims first).
        frame.drop_fn(frame.locals);
        shadow_top_ptr.* = top;
    }

    const worker = worker_mod.getCurrentWorker().?;
    const fiber = worker.current_fiber.?;
    fiber.state = .Done;

    if (fiber.root_obligation) |obl| {
        obl.fulfill(payload.box_transient(), worker.ready_queue);
    } else {
        // Reached the root with no obligation to deliver to. Once `main` is
        // wrapped in a catch boundary this is unreachable; until then, surface
        // the error and exit.
        crashAndExit(payload);
    }

    // Mirror `fiber_trampoline`'s hand-off to the scheduler: clear the TLS
    // shadow stack so the heartbeat can't touch this fiber's about-to-be-freed
    // locals, restore the scheduler's stack bounds, then switch.
    shadow_stack.shadow_top = null;
    shadow_stack.shadow_stack = null;
    heartbeat.tls_tokens_ptr = null;
    scope_mod.active_scope = null;
    if (build_options.trace_frames) {
        shadow_stack.trace_top = null;
        shadow_stack.trace_stack = null;
    }
    gc.setStackBottom(gc.currentStackBase());
    gc.enable_cycle_collection();
    fiber_mod.switchTo(fiber, &worker.scheduler_fiber);
    unreachable;
}

/// Lowering target for `Error!info` (`base.Error`'s `!/1`). Wraps `info` as a
/// deterministic error payload and abandons the current fiber. `noreturn` so it
/// coerces into the FatPtr sub-expression slot of the surrounding `rt.call`
/// codegen, leaving the deeply-nested call lowering untouched. Takes ownership
/// of the single reference held by `info`.
pub fn throwDeterministic(info: FatPtr) noreturn {
    feart_unwind(error_rt.makeDeterministic(info));
}

/// Custom Zig panic handler (installed via `pub const panic` in `main.zig`).
/// Inside a fiber, a `@panic`-class fault (explicit panic, integer overflow,
/// div-by-zero, OOB in safe modes, …) becomes a non-deterministic error
/// delivered to the nearest `CapTry`/top-level boundary. Outside any fiber
/// (e.g. on the scheduler/OS stack) there is no boundary to unwind to, so we
/// fall back to Zig's default panic — which also keeps test-runner reporting
/// intact if this override is ever active during a test build.
pub fn ndPanicHandler(msg: []const u8, first_trace_addr: ?usize) noreturn {
    @branchHint(.cold);
    if (shadow_stack.getShadowTop() == null) {
        std.debug.defaultPanic(msg, first_trace_addr);
    }
    feart_unwind(error_rt.makeNd(buildInfo(msg)));
}

/// Build a `base.Info` describing a panic message. Copies the message into GC
/// memory so it outlives the panicking frame.
pub fn buildInfo(msg: []const u8) FatPtr {
    const buf = gc.allocator.alloc(u8, msg.len) catch return str_rt.make_str_from_literal("panic");
    @memcpy(buf, msg);
    const str = str_rt.make_str(buf.ptr, buf.len);
    const root = @import("root");
    if (@hasDecl(root, "pkg_base")) {
        return objs.call(
            objs.obj_k_singleton(&root.pkg_base.VT_Infos_0),
            comptime h("imm .msg/1"),
            .{str},
            @src(),
        );
    }
    return str;
}

/// Print `Program crashed with: <info.msg>` and exit 1. Reached only when an
/// error unwinds to a fiber that has no `root_obligation`.
///
/// Uses the flat `.msg` rather than the JSON `.str`: `.str` routes through the
/// `base.json` serialiser, which is not yet wired up in this backend.
fn crashAndExit(payload: FatPtr) noreturn {
    const info = error_rt.infoOf(payload);
    const msg_fp = objs.call(info, comptime h("imm .msg/0"), .{}, @src());
    writeStderr("Program crashed with: ");
    writeStderr(str_rt.deref_str(msg_fp));
    writeStderr("\n");
    // Prefer the throw-site snapshot the payload carries: it shows the call
    // chain on the fiber where the error *originated*, including any fiber
    // boundaries, whereas this (root) fiber's live trace lost that on re-throw.
    if (build_options.trace_frames) {
        if (error_rt.traceOf(payload)) |snap| trace.printTraceSnapshot(snap);
    }
    std.process.exit(1);
}

/// `base.Info` from a message Str (mirrors `Error.msg`'s `Infos.msg msg`).
fn infosMsg(msg: FatPtr) FatPtr {
    const root = @import("root");
    return objs.call(
        objs.obj_k_singleton(&root.pkg_base.VT_Infos_0),
        comptime h("imm .msg/1"),
        .{msg},
        @src(),
    );
}

/// `base.Error`'s `!/1`: throw the given `Info` as a deterministic error.
fn errork_throw(self: FatPtr, info: FatPtr) callconv(.c) FatPtr {
    _ = self;
    throwDeterministic(info);
}

/// `base.Error`'s `.msg/1`: wrap the message and throw deterministically.
fn errork_msg(self: FatPtr, msg: FatPtr) callconv(.c) FatPtr {
    _ = self;
    throwDeterministic(infosMsg(msg));
}

pub const VT_ErrorK: objs.VTable = .{
    .type_name = "base.Error/0",
    .hashes = &.{ h("imm !/1"), h("imm .msg/1") },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&errork_throw)),
        @as(*const anyopaque, @ptrCast(&errork_msg)),
    },
    .method_names = &.{ "imm !/1", "imm .msg/1" },
    .storage_mode = .singleton,
};

fn dieNoFiber() noreturn {
    writeStderr("feart_unwind called with no active fiber\n");
    std.process.exit(1);
}
