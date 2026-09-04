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

/// Abandon the current fiber and deliver `payload` (a tag-typed error FatPtr,
/// see `error.zig`) to the parent.
///
/// Walks the shadow stack newest->oldest, abandoning each frame: a promoted
/// frame hands the error to its thief, which is parked waiting for our `r1`, so
/// it cannot deadlock. At the root we fulfill `root_obligation` and switch to
/// the scheduler, so this never returns.
///
/// `Error!` lowers to this instead of a checked return through every `rt.call`,
/// which keeps the deeply-nested call codegen untouched. The cost is that
/// abandoned non-promoted Zig frames skip their `defer rc_decrement`s; lazy
/// cycle collection (gc.zig) catches those.
pub fn feart_unwind(payload: FatPtr) noreturn {
    gc.disable_cycle_collection();
    const cursor = shadow_stack.getShadowCursor() orelse dieNoFiber();
    // One read for the whole walk: an unwind stays on the stack it started on,
    // so every frame releases on behalf of the same worker.
    const releasing_worker_id = worker_mod.currentWorkerId();

    var top = cursor.top;
    while (top > 0) {
        top -= 1;
        const ss = shadow_stack.getShadowStack().?;
        const frame = &ss[top];

        // A non-null result other than CLAIMED means the frame was promoted:
        // its thief is parked on `child_obligation` waiting for our `r1`.
        // Do NOT recycle the returned join obligation -- the thief still owns
        // it, and a pool slot a live thief writes to would corrupt an
        // unrelated promotion. The GC reclaims it instead.
        const prev = frame.join_obligation.cmpxchgStrong(null, CLAIMED, .acquire, .acquire);
        if (prev) |obl| {
            if (obl != CLAIMED) shadow_stack.fulfillChildObligation(top, payload.share());
        }

        frame.drop_fn(frame.locals, releasing_worker_id);
        cursor.top = top;
        cursor.lowest_unpromoted = @min(cursor.lowest_unpromoted, top);
    }

    const worker = worker_mod.getCurrentWorker().?;
    const fiber = worker.current_fiber.?;
    fiber.state = .Done;

    if (fiber.root_obligation) |obl| {
        fiber.creditParentTokens();
        obl.fulfill(payload.box_transient());
    } else {
        // Root with no obligation to deliver to: surface the error and exit.
        crashAndExit(payload);
    }

    // The loop above emptied the shadow stack; this stops the heartbeat
    // refilling it before the scheduler retires the fiber.
    fiber.vpf_enabled = false;
    gc.enable_cycle_collection();
    fiber_mod.switchTo(fiber, &worker.scheduler_fiber);
    unreachable;
}

/// Lowering target for `Error!info` (`base.Error`'s `!/1`). `noreturn` so it
/// coerces into the FatPtr sub-expression slot of the surrounding `rt.call`
/// codegen. Takes ownership of the reference held by `info`.
pub fn throwDeterministic(info: FatPtr) noreturn {
    feart_unwind(error_rt.makeDeterministic(info));
}

/// Custom Zig panic handler, installed via `pub const panic` in `main.zig`.
/// Inside a fiber a `@panic`-class fault becomes a non-deterministic error for
/// the nearest `CapTry`/top-level boundary. Outside any fiber there is no
/// boundary, so fall back to Zig's default panic.
pub fn ndPanicHandler(msg: []const u8, first_trace_addr: ?usize) noreturn {
    @branchHint(.cold);
    if (shadow_stack.getShadowCursor() == null) {
        std.debug.defaultPanic(msg, first_trace_addr);
    }
    const fiber = fiber_mod.currentFiber();
    // A panic raised while building the info of an earlier one cannot report
    // through the boundary the first one is still on its way to, so it takes
    // Zig's own handler, which prints and aborts.
    if (fiber.in_panic) {
        std.debug.defaultPanic(msg, first_trace_addr);
    }
    fiber.in_panic = true;
    // `buildInfo` calls a generated method, and every one of those offers a
    // promotion. Promoting here would run the frame that panicked a second time.
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
/// Uses the flat `.msg` rather than the JSON `.str`: `.str` routes through the
/// `base.json` serialiser, which is not yet wired up in this backend.
fn crashAndExit(payload: FatPtr) noreturn {
    const info = error_rt.infoOf(payload);
    const msg_fp = objs.call(info, comptime h("imm .msg/0"), .{}, @src());
    writeStderr("Program crashed with: ");
    writeStderr(str_rt.deref_str(msg_fp));
    writeStderr("\n");
    // The throw-site snapshot shows the call chain on the fiber where the
    // error originated; this root fiber's live trace lost that on re-throw.
    if (build_options.trace_frames) {
        if (error_rt.traceOf(payload)) |snap| trace.printTraceSnapshot(snap);
    }
    std.process.exit(1);
}

/// `base.Info` from a message Str, mirroring `Error.msg`'s `Infos.msg msg`.
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
