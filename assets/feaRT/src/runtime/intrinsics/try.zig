const std = @import("std");
const objs = @import("../objs.zig");
const gc = @import("../gc.zig");
const error_rt = @import("../error.zig");
const unwind = @import("../errors/unwind.zig");
const fiber_mod = @import("../fiber.zig");
const worker_mod = @import("../worker.zig");
const shadow_stack = @import("../shadow_stack.zig");
const scope_mod = @import("../scope.zig");
const trace = @import("../errors/trace.zig");
const build_options = @import("build_options");
const root = @import("root");
const pb = root.pkg_base;
const actions = @import("../conversions/actions.zig");

const FatPtr = objs.FatPtr;
const Fiber = fiber_mod.Fiber;
const JoinObligation = shadow_stack.JoinObligation;
const h = objs.hash_signature;

// `Try`/`CapTry` run their lambda on a disposable child fiber, so that a
// deterministic `Error!` -- which lowers to `feart_unwind` and abandons the
// failing Zig frames -- has a fiber boundary to unwind to. The child's
// `root_obligation` carries the lambda's result or the tag-typed error payload.
// `.run` then classifies it: an ordinary value becomes `m.ok`, a deterministic
// error becomes `m.info`, and an ND error becomes `m.info` only for `CapTry`.
// A deterministic handler must not absorb ND, so `Try` re-propagates it.

const TryActionCaptures = extern struct {
    f: FatPtr,
    /// The `iso` data for `#/2`, or a Void singleton for `#/1`. Always a valid
    /// FatPtr, so the uniform capture share/drop path applies.
    data: FatPtr,
    has_data: bool,
    catch_nd: bool,
};

fn make_try_action(f: FatPtr, data: FatPtr, has_data: bool, catch_nd: bool) FatPtr {
    return objs.obj_k(TryActionCaptures, &VT_TryAction, .{
        .f = f,
        .data = data,
        .has_data = has_data,
        .catch_nd = catch_nd,
    });
}

const ChildCtx = struct {
    f: FatPtr,
    data: ?FatPtr,
};

/// Runs the lambda and delivers its result through `root_obligation`. On an
/// unwind `feart_unwind` fulfills that same obligation with the error payload,
/// and this never reaches its own fulfill.
fn childEntry(fiber: *Fiber) void {
    const ctx: *ChildCtx = @ptrCast(@alignCast(fiber.context.?));
    const result = if (ctx.data) |d|
        objs.call(ctx.f, comptime h("read #/1"), .{d}, @src())
    else
        objs.call(ctx.f, comptime h("read #/0"), .{}, @src());
    fiber.creditParentTokens();
    fiber.root_obligation.?.fulfill(result.box_transient());
}

/// Run `f#`/`f#data` on a fresh child fiber and block until it completes.
/// Returns the lambda's value or a tag-typed error payload; classify with
/// `error_rt.tagOf`. Ownership of `f`/`data` transfers to the child.
pub fn runInChildFiber(f: FatPtr, data: ?FatPtr) FatPtr {
    const worker = worker_mod.getCurrentWorker().?;
    const parent_fiber = worker.current_fiber.?;
    const obl = worker.allocObligation() orelse @panic("OOM allocating Try obligation");

    // `ctx` lives on the parent's stack. The parent parks in `wait` below and
    // resumes only once the child has fulfilled the obligation, which is after
    // it consumed `ctx`, so the pointer stays valid for the child's read.
    var ctx = ChildCtx{ .f = f, .data = data };
    const child = Fiber.create(&childEntry, &ctx) catch @panic("OOM creating Try fiber");
    child.root_obligation = obl;
    child.saved_scope = scope_mod.active_scope;
    child.parent_tokens_ptr = &parent_fiber.tokens;
    if (build_options.trace_frames) {
        trace.inheritStackTrace(child, &parent_fiber.trace_frames, parent_fiber.trace_top);
    }
    worker_mod.enqueueFiber(child);

    const result = obl.wait(worker_mod.getCurrentWorker().?);
    shadow_stack.freeObligation(obl);
    return result;
}

fn tryaction_run(self: FatPtr, m: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    const caps = objs.deref(TryActionCaptures, self);
    const catch_nd = caps.catch_nd;
    const result = runInChildFiber(
        caps.f.share(),
        if (caps.has_data) caps.data.share() else null,
    );
    switch (error_rt.tagOf(result)) {
        .none => return objs.call(m, comptime h("mut .ok/1"), .{result}, @src()),
        .deterministic => {
            const info = error_rt.infoOf(result);
            result.rc_decrement();
            return objs.call(m, comptime h("mut .info/1"), .{info}, @src());
        },
        .nd => {
            if (catch_nd) {
                const info = error_rt.infoOf(result);
                result.rc_decrement();
                return objs.call(m, comptime h("mut .info/1"), .{info}, @src());
            }
            // `Try` does not catch ND: re-propagate on the parent fiber.
            unwind.feart_unwind(result);
        },
    }
}

pub const VT_TryAction = actions.ActionVTable("<runtime try action>", &tryaction_run, null);

fn try_make_1(self: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer f.rc_decrement();
    return make_try_action(f, objs.obj_k_singleton(&pb.VT_Void_0), false, false);
}
fn try_make_2(self: FatPtr, data: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer data.rc_decrement();
    defer f.rc_decrement();
    return make_try_action(f, data, true, false);
}

fn captry_make_1(self: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer f.rc_decrement();
    return make_try_action(f, objs.obj_k_singleton(&pb.VT_Void_0), false, true);
}
fn captry_make_2(self: FatPtr, data: FatPtr, f: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    defer data.rc_decrement();
    defer f.rc_decrement();
    return make_try_action(f, data, true, true);
}

/// `ToIso[CapTry]` methods. CapTry is a stateless singleton, so both hand it
/// straight back.
fn captry_iso(self: FatPtr) callconv(.c) FatPtr {
    return self;
}
fn captry_self(self: FatPtr) callconv(.c) FatPtr {
    return self;
}

pub const VT_Try: objs.VTable = .{
    .type_name = "base.Try/0",
    .hashes = &.{ h("imm #/1"), h("imm #/2") },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&try_make_1)),
        @as(*const anyopaque, @ptrCast(&try_make_2)),
    },
    .method_names = &.{ "imm #/1", "imm #/2" },
    .storage_mode = .singleton,
};

pub const VT_CapTry: objs.VTable = .{
    .type_name = "base.caps.CapTry/0",
    .hashes = &.{ h("mut #/1"), h("mut #/2"), h("mut .iso/0"), h("mut .self/0") },
    .methods = &.{
        @as(*const anyopaque, @ptrCast(&captry_make_1)), @as(*const anyopaque, @ptrCast(&captry_make_2)),
        @as(*const anyopaque, @ptrCast(&captry_iso)),    @as(*const anyopaque, @ptrCast(&captry_self)),
    },
    .method_names = &.{ "mut #/1", "mut #/2", "mut .iso/0", "mut .self/0" },
    .storage_mode = .singleton,
};
