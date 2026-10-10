const std = @import("std");
const shadow_stack = @import("../shadow_stack.zig");
const worker_mod = @import("../worker.zig");
const fiber_mod = @import("../fiber.zig");
const error_rt = @import("../error.zig");
const objs = @import("../objs.zig");
const gc = @import("../gc.zig");
const heartbeat = @import("../heartbeat.zig");
const scope_mod = @import("../scope.zig");
const str_rt = @import("../intrinsics/strings/index.zig");
const trace = @import("./trace.zig");
const build_options = @import("build_options");

const h = objs.hash_signature;
const FatPtr = objs.FatPtr;
const CLAIMED = shadow_stack.CLAIMED;
const writeStderr = @import("./io.zig").writeStderr;

/// Abandons the current fiber and gives `payload` (a tag-typed error FatPtr,
/// see `error.zig`) to the parent.
///
/// Walks the shadow stack newest to oldest. A promoted frame gives the error to
/// its thief, which waits for this frame's `r1`, so no deadlock occurs. At the
/// root, fulfills `root_obligation` and switches to the scheduler.
///
/// `Error!` lowers to this, not to a checked return through every `rt.call`.
/// Abandoned non-promoted Zig frames thus skip their `defer` releases.
pub fn feart_unwind(payload: FatPtr) noreturn {
    gc.disable_cycle_collection();
    const cursor = shadow_stack.getShadowCursor() orelse dieNoFiber();

    var top = cursor.top;
    while (top > 0) {
        top -= 1;
        const ss = shadow_stack.getShadowStack().?;
        const frame = &ss[top];

        // Non-null and not CLAIMED: the frame was promoted, and its thief waits
        // on `child_obligation`. Do not recycle the join obligation. The thief
        // still writes to it, which would corrupt an unrelated promotion.
        const prev = frame.join_obligation;
        if (prev == null) frame.join_obligation = CLAIMED;
        if (prev) |obl| {
            if (obl != CLAIMED) shadow_stack.fulfillChildObligation(top, payload.share());
        }

        // No release of `frame.locals`: a frame borrows its locals, from the
        // caller or from the task of a thief.
        cursor.top = top;
        cursor.lowest_unpromoted = @min(cursor.lowest_unpromoted, top);
    }

    const worker = worker_mod.getCurrentWorker().?;
    const fiber = worker.current_fiber.?;
    fiber.state = .Done;

    // A thief fiber owns the counts in the locals copy of its task, and an
    // unwind does not go back to the trampoline that releases them. The task
    // stays allocated: its promoter can still read it in `reclaimPromotion`.
    if (worker_mod.stolenTaskOf(fiber)) |task| {
        task.locals_drop_fn(@ptrCast(&task.locals_copy), worker.workerId());
    }

    if (fiber.root_obligation) |obl| {
        fiber.creditParentTokens();
        obl.fulfill(payload.box_transient());
    } else {
        crashAndExit(payload);
    }

    // Stops the heartbeat from promoting on this fiber before it retires.
    fiber.vpf_enabled = false;
    gc.enable_cycle_collection();
    fiber_mod.switchTo(fiber, &worker.scheduler_fiber);
    unreachable;
}

/// Lowering target for `Error!info`. `noreturn`, so it coerces into a FatPtr
/// argument slot. Takes ownership of `info`.
pub fn throwDeterministic(info: FatPtr) noreturn {
    feart_unwind(error_rt.makeDeterministic(info));
}

/// Installed as `panic` in `main.zig`. On a fiber, a panic becomes a
/// non-deterministic error for the nearest `CapTry` or top-level boundary. Off a
/// fiber, uses the default Zig panic.
pub fn ndPanicHandler(msg: []const u8, first_trace_addr: ?usize) noreturn {
    @branchHint(.cold);
    if (shadow_stack.getShadowCursor() == null) {
        std.debug.defaultPanic(msg, first_trace_addr);
    }
    const fiber = fiber_mod.currentFiber();
    // A panic during `buildInfo` of an earlier panic cannot use the boundary,
    // so it aborts through the default handler.
    if (fiber.in_panic) {
        std.debug.defaultPanic(msg, first_trace_addr);
    }
    fiber.in_panic = true;
    // `buildInfo` calls generated code, which can promote. A promotion here
    // runs the panicking frame again.
    const vpf_was_enabled = fiber.vpf_enabled;
    fiber.vpf_enabled = false;
    const payload = error_rt.makeNd(buildInfo(msg));
    fiber.vpf_enabled = vpf_was_enabled;
    fiber.in_panic = false;
    feart_unwind(payload);
}

/// Copies the message into GC memory so it outlives the panicking frame.
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

/// Reached only when an error unwinds to a fiber with no `root_obligation`.
///
/// Uses `.msg`, not `.str`: `.str` needs the `base.json` serialiser, which this
/// backend does not have.
fn crashAndExit(payload: FatPtr) noreturn {
    const info = error_rt.infoOf(payload);
    const msg_fp = objs.call(info, comptime h("imm .msg/0"), .{}, @src());
    writeStderr("Program crashed with: ");
    writeStderr(str_rt.deref_str(msg_fp));
    writeStderr("\n");
    // The throw-site snapshot has the call chain of the origin fiber. The live
    // trace of the root fiber does not.
    if (build_options.trace_frames) {
        if (error_rt.traceOf(payload)) |snap| trace.printTraceSnapshot(snap);
    }
    std.process.exit(1);
}

/// As `Infos.msg msg` in `Error.msg`.
fn infosMsg(msg: FatPtr) FatPtr {
    const root = @import("root");
    return objs.call(
        objs.obj_k_singleton(&root.pkg_base.VT_Infos_0),
        comptime h("imm .msg/1"),
        .{msg},
        @src(),
    );
}

/// `base.Error`'s `!/1`.
fn errork_throw(self: FatPtr, info: FatPtr) callconv(.c) FatPtr {
    _ = self;
    throwDeterministic(info);
}

/// `base.Error`'s `.msg/1`.
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
