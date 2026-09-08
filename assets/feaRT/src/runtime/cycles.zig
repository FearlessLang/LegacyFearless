//! Synchronous cycle collection: Bacon and Rajan, "Concurrent Cycle Collection
//! in Reference Counted Systems", ECOOP 2001, Figure 2.
//!
//! Reference counting reclaims everything acyclic as it dies; this is the
//! backstop for the groups of objects that name each other.
//!
//! Candidates come from the stores, not from the counts: Figure 2 takes a
//! candidate from every decrement that stops short of zero, which puts a test
//! on the hottest path in the runtime and buffers millions of nodes no cycle
//! ever runs through. Fearless answers the same question from the shape of the
//! language instead; see [`noteStore`].
//!
//! `CollectCycles` is `MarkRoots; ScanRoots; CollectRoots`, run with the world
//! stopped. The paper's concurrent form (Figures 4 and 5) buys mid-graph
//! mutation at the cost of deferred reference counting, an epoch protocol and
//! two safety tests per candidate; stopping the world costs a pause and deletes
//! all of it. See `safepoint.zig` for how the pause is taken, and why one that
//! cannot be taken is skipped rather than waited out.

const std = @import("std");

const objs = @import("objs.zig");
const gc = @import("gc.zig");
const heap = @import("heap.zig");
const safepoint = @import("safepoint.zig");
const op_counters = @import("op_counters.zig");
const worker_mod = @import("worker.zig");
const WriteLock = @import("sync/write_lock.zig").WriteLock;

const FatPtr = objs.FatPtr;
const Colour = objs.Colour;

/// Candidate cycle roots: every node that has taken an edge it could not have
/// been born with. A node is in here exactly while its `buffered` flag is set.
///
/// Unlike the paper's Roots buffer this one persists across collections: a
/// candidate is a node that *can* close a cycle, so it stays one for as long as
/// it lives, which is what lets the mutator side hold no state per decrement.
/// Membership carries a reference of its own, which keeps the node standing
/// between the store that made it a candidate and the pass that judges it; a
/// pass gives that reference up along with a garbage node and drops the entry.
var candidates: std.ArrayList(FatPtr) = .empty;

/// Covers `candidates` against two threads storing into two different
/// containers at the same time. Taken once per container lifetime, not per
/// store.
var candidates_lock: WriteLock = .{};

/// The traversals below are iterative: a recursive one would put the depth of
/// the object graph on the collector's stack.
var work: std.ArrayList(FatPtr) = .empty;

/// False while a fault or an unwind is being handled. Those paths run with the
/// program in a state a traversal has no business walking.
var armed = std.atomic.Value(bool).init(true);

/// Stands cycle collection down (`gc.disable_cycle_collection` calls this).
/// Candidates keep accumulating while it is down, so nothing is lost.
pub fn disarm() void {
    armed.store(false, .release);
}

/// The counterpart of [`disarm`].
pub fn rearm() void {
    armed.store(true, .release);
}

inline fn colourOf(ref: anytype) Colour {
    return @enumFromInt(ref.colour.*);
}

inline fn setColour(ref: anytype, c: Colour) void {
    ref.colour.* = @intFromEnum(c);
}

// -- The mutator side ------------------------------------------------------

/// `container` has taken an edge to `value` after it was built. This is where
/// every candidate cycle root comes from, and the claim it rests on is a
/// property of the language rather than of the counts:
///
///  * An object literal fixes its captures when built, so a graph of literals
///    only is acyclic: order its nodes by build time and every edge points
///    backwards.
///  * A cycle must therefore run through a node that took an edge after it was
///    built -- in this runtime, only the standard library's mutable containers,
///    and every store on one calls this.
///  * A store of an acyclic value closes no cycle: the value would have to
///    name the container back, and an acyclic value names nothing a traversal
///    can follow.
///
/// So the candidate set holds every node a cycle can run through, from the
/// store that made it one, and `Release` keeps only its own counting work.
pub inline fn noteStore(container: FatPtr, value: FatPtr) void {
    if (!objs.isAcyclic(value)) {
        @branchHint(.unlikely);
        addCandidate(container);
    }
}

/// Candidates accumulated before a cycle collection pass triggers.
pub const CANDIDATES_THRESHOLD: usize = 65536;

/// Candidate count recorded at the end of the previous collection pass.
var candidates_at_collection: usize = 0;

/// `node` holds a slot that takes edges this file cannot see one at a time:
/// a flow's scan accumulator and an actor's state are written from inside the
/// chunk executor, which holds the cell and not the node that owns it. Such a
/// chain enters the candidate set when it is built, not at the store.
pub fn noteMutable(node: FatPtr) void {
    addCandidate(node);
}

noinline fn addCandidate(node: FatPtr) void {
    const mark = objs.nodeMark(node);
    // A container is written by one thread at a time -- the language answers
    // for that -- so its flag is read here without the lock, and a container
    // already in the set costs nothing beyond that read.
    if (mark.buffered.*) return;
    var should_collect = false;
    candidates_lock.acquire();
    mark.buffered.* = true;
    setColour(mark, .purple);
    // Membership holds a reference, so the node stands until a pass judges it.
    _ = node.share();
    candidates.append(gc.allocator, node) catch @panic("OOM");
    op_counters.bump(.cycle_candidate_added);
    if (candidates.items.len >= candidates_at_collection + CANDIDATES_THRESHOLD) {
        should_collect = true;
    }
    candidates_lock.release();

    if (should_collect) {
        collectCycles(worker_mod.currentWorkerId());
    }
}

// -- CollectCycles ---------------------------------------------------------

/// Runs one collection with the world stopped, if it can be stopped. `me` is
/// the worker asking, and the one thread that keeps running, so the passes
/// charge every free they make to it.
pub fn collectCycles(me: u32) void {
    if (!armed.load(.acquire)) return;
    _ = safepoint.stopTheWorld(me, &runPasses);
}

/// The worker running the collection under way, the only identity live while
/// no other thread runs. Written by the passes rather than by
/// [`collectCycles`], because a worker that asks for a collection another
/// thread already runs carries on and must not name itself here.
var collecting_worker: u32 = 0;

fn runPasses(me: u32) void {
    collecting_worker = me;
    markRoots();
    scanRoots();
    collectRoots();
    candidates_at_collection = candidates.items.len;
}

/// What a traversal hands to `children`, and what every edge comes back to.
/// The visitor travels in the context rather than in a global, because one
/// traversal can begin inside another: `scanBlack` runs while `scan` is
/// walking `work`.
const VisitCtx = struct {
    visit: *const fn (*VisitCtx, FatPtr) void,
    /// Where the visitor puts the nodes it wants walked next.
    list: *std.ArrayList(FatPtr),
};

fn visitEdge(ctx_raw: *anyopaque, edge: FatPtr) callconv(.c) void {
    // A primitive, a singleton or a transient holds no count, so it is no node.
    if (!objs.isNode(edge)) return;
    const ctx: *VisitCtx = @ptrCast(@alignCast(ctx_raw));
    ctx.visit(ctx, edge);
}

fn forEachChild(node: FatPtr, ctx: *VisitCtx) void {
    objs.children(node, visitEdge, @ptrCast(ctx));
}

fn pushEdge(ctx: *VisitCtx, node: FatPtr) void {
    ctx.list.append(gc.allocator, node) catch @panic("OOM");
}

// -- MarkRoots -------------------------------------------------------------

/// Every candidate is a root of the trial deletion. None of them can have died
/// in the meantime, because membership of the set holds a reference.
fn markRoots() void {
    for (candidates.items) |s| {
        op_counters.bump(.cycle_root_examined);
        markGray(s);
    }
}

/// The reference the candidate set holds to `S`: one for a candidate, none for
/// anything else. Trial deletion takes it off like an internal edge, because
/// the set's reference names nothing the program can reach; without this, every
/// candidate looks held from outside and no cycle is ever collected.
inline fn setRefs(ref: objs.NodeRef) i32 {
    return if (ref.buffered.*) 1 else 0;
}

// -- MarkGray --------------------------------------------------------------

/// Trial deletion over the cyclic count. Decrements on the first visit too: the
/// paper's lazy `CRC = RC` loses the edge that found the node and collects nothing.
fn markGray(root: FatPtr) void {
    const ref = objs.nodeRef(root);
    // Already reached from an earlier root in this same pass, which has already
    // accounted for every edge into it. Being a root adds no reference.
    if (colourOf(ref) == .grey) return;
    setColour(ref, .grey);
    ref.crc.* = ref.rc - setRefs(ref);

    work.clearRetainingCapacity();
    work.append(gc.allocator, root) catch @panic("OOM");
    var ctx: VisitCtx = .{ .visit = markGrayEdge, .list = &work };
    while (work.pop()) |s| forEachChild(s, &ctx);
}

fn markGrayEdge(ctx: *VisitCtx, t: FatPtr) void {
    const ref = objs.nodeRef(t);
    if (colourOf(ref) != .grey) {
        setColour(ref, .grey);
        // Less the edge that found it.
        ref.crc.* = ref.rc - 1 - setRefs(ref);
        pushEdge(ctx, t);
        return;
    }
    ref.crc.* -= 1;
}

// -- ScanRoots and Scan ----------------------------------------------------

fn scanRoots() void {
    for (candidates.items) |s| scan(s);
}

/// A grey node whose cyclic count reached zero is reachable only from inside the
/// subgraph, so it goes white; one with references left over is live, and
/// `ScanBlack` takes back everything it holds. Only grey nodes are looked at,
/// which leaves a node an earlier root coloured white alone; `ScanBlack`
/// recurses through children whatever their colour, so liveness is not lost.
fn scan(root: FatPtr) void {
    work.clearRetainingCapacity();
    work.append(gc.allocator, root) catch @panic("OOM");
    var ctx: VisitCtx = .{ .visit = pushEdge, .list = &work };
    while (work.pop()) |s| {
        const ref = objs.nodeRef(s);
        if (colourOf(ref) != .grey) continue;
        if (ref.crc.* > 0) {
            scanBlack(s);
            continue;
        }
        setColour(ref, .white);
        forEachChild(s, &ctx);
    }
}

/// The inverse of `MarkGray`: everything reachable goes back to black. The true
/// count is never touched, because the trial deletion ran on the cyclic count.
/// A worklist of its own, because this runs while `scan` is walking `work`.
var black_work: std.ArrayList(FatPtr) = .empty;

fn scanBlack(root: FatPtr) void {
    const root_ref = objs.nodeMark(root);
    if (colourOf(root_ref) == .black) return;
    setColour(root_ref, .black);
    black_work.clearRetainingCapacity();
    black_work.append(gc.allocator, root) catch @panic("OOM");
    var ctx: VisitCtx = .{ .visit = scanBlackEdge, .list = &black_work };
    while (black_work.pop()) |s| forEachChild(s, &ctx);
}

fn scanBlackEdge(ctx: *VisitCtx, t: FatPtr) void {
    const ref = objs.nodeMark(t);
    if (colourOf(ref) == .black) return;
    setColour(ref, .black);
    pushEdge(ctx, t);
}

// -- CollectRoots and CollectWhite -----------------------------------------

/// Frees every garbage cycle the scan left behind, in three passes over the
/// candidates rather than one: a member is unreachable from the program but
/// still reachable from another member, so nothing may be given back until
/// every member is known; and a member's own references have to go before its
/// storage does, because giving up a reference can free what it names.
fn collectRoots() void {
    for (candidates.items) |s| {
        const mark = objs.nodeMark(s);
        // Anything not white is live, and stays a candidate: it can close a
        // cycle again at any time, and nothing would tell the collector so.
        // Red is a member of a group an earlier candidate of this pass gathered.
        if (colourOf(mark) == .white) gatherWhite(s);
    }

    // The set gives up its reference to every member it is about to lose, which
    // has to happen before any storage goes back.
    var kept: usize = 0;
    for (candidates.items) |s| {
        if (colourOf(objs.nodeMark(s)) == .red) continue;
        candidates.items[kept] = s;
        kept += 1;
    }
    candidates.shrinkRetainingCapacity(kept);

    // Each member takes one reference of its own. A decrement that comes back
    // into the group from inside it can then never reach zero, which is what
    // keeps `Release` from running for a node this pass is about to free
    // outright.
    for (dying.items) |s| _ = s.share();
    for (dying.items) |s| objs.releaseChildren(s, collecting_worker);

    for (dying.items) |s| {
        const mark = objs.nodeMark(s);
        mark.buffered.* = false;
        setColour(mark, .black);
        objs.freeNode(s, collecting_worker);
        op_counters.bump(.cycle_node_freed);
    }
    dying.clearRetainingCapacity();
}

/// Gathers every member of one garbage cycle, colouring them red so that a
/// later candidate reaching the same group knows they are spoken for, and so
/// that the candidate set knows which of its entries it is about to lose.
fn gatherWhite(root: FatPtr) void {
    work.clearRetainingCapacity();
    work.append(gc.allocator, root) catch @panic("OOM");
    var ctx: VisitCtx = .{ .visit = pushEdge, .list = &work };
    while (work.pop()) |s| {
        const mark = objs.nodeMark(s);
        if (colourOf(mark) != .white) continue;
        setColour(mark, .red);
        dying.append(gc.allocator, s) catch @panic("OOM");
        forEachChild(s, &ctx);
    }
}

/// The members of the garbage cycles this pass is taking apart.
var dying: std.ArrayList(FatPtr) = .empty;

// -- Tests ----------------------------------------------------------------
//
// No checksum can see a leaked cycle or a premature free, so these tests are
// the only evidence this file works. Each measures collection through a
// `probe` object the cycle holds and the test also holds.

const testing = std.testing;
const h = objs.hash_signature;
const var_rt = @import("intrinsics/var.zig");
const isopod_rt = @import("intrinsics/isopod.zig");
const storage_mod = @import("intrinsics/lists/storage.zig");
const map_rt = @import("intrinsics/map.zig");
const ops_node = @import("intrinsics/flows/ops_node.zig");
const flow_types = @import("intrinsics/flows/types.zig");

/// Runs a collection on the calling thread, to a fixed point: a cycle holding
/// another cycle up comes apart one pass at a time.
pub fn collectNowForTest() void {
    for (0..4) |_| collectCycles(1);
}

const Empty = extern struct {};

const VT_Probe: objs.VTable = .{
    .type_name = "<test cycle probe>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
};

/// Holds one container and one probe, which is what closes a cycle and what
/// makes the collection observable.
const LinkCaptures = extern struct { container: FatPtr, probe: FatPtr };
const VT_Link: objs.VTable = .{
    .type_name = "<test cycle link>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .trace_fn = objs.captureTraceFn(LinkCaptures),
};

/// A unit-test build has no generated base package, so `base.List`,
/// `base.UList` and `base.LinkedHashMap` have no default bodies to reach. The
/// list and map tests therefore wrap the real storage and the real hooks in a
/// vtable of their own.
const VT_TestListWrapper: objs.VTable = .{
    .type_name = "<test list wrapper>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .drop_fn = storage_mod.list_drop,
    .trace_fn = storage_mod.list_trace,
};

const VT_TestMap: objs.VTable = .{
    .type_name = "<test map>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .drop_fn = map_rt.map_drop,
    .free_fn = map_rt.map_free,
    .trace_fn = map_rt.map_trace,
};

const VT_TestSingleton: objs.VTable = .{
    .type_name = "<test cycle singleton>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .storage_mode = .singleton,
};

/// A literal the compiler proved acyclic for every instance, whatever it was
/// built with. It is the static fast path over the per-instance answer.
const VT_Green: objs.VTable = .{
    .type_name = "<test green>",
    .hashes = &.{},
    .methods = &.{},
    .method_names = &.{},
    .trace_fn = objs.captureTraceFn(LinkCaptures),
    .green = true,
};

fn probeCount(probe: FatPtr) u32 {
    return probe.boxed_value().refCountForTest();
}

/// Appends to a storage the way `ulist_add` does, candidate and all. The item
/// is on loan, so the storage takes a reference of its own.
fn testListAdd(storage: *storage_mod.ListStorage, item: FatPtr) void {
    noteStore(storage_mod.storageEdge(storage), item);
    storage.al.append(gc.allocator, item.share().box_transient()) catch @panic("OOM");
}

fn beginTest() void {
    gc.init_gc();
    rearm();
}

test "a Var that reaches itself is collected" {
    beginTest();

    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    const cell = var_rt.make(probe);
    // The link holds the cell, and the cell is about to hold the link.
    const link = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = cell, .probe = probe });
    objs.call(cell, comptime h("mut .set/1"), .{link}, @src()).rc_decrement();

    // Nothing outside the cycle names either half of it now.
    cell.rc_decrement();
    link.rc_decrement();

    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "two objects that reach each other through IsoPods are collected" {
    beginTest();

    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    const pod_a = isopod_rt.make(probe);
    const pod_b = isopod_rt.make(probe);
    const link_a = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = pod_b, .probe = probe });
    const link_b = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = pod_a, .probe = probe });

    // pod_a -> link_a -> pod_b -> link_b -> pod_a
    objs.call(pod_a, comptime h("mut .next/1"), .{link_a}, @src()).rc_decrement();
    objs.call(pod_b, comptime h("mut .next/1"), .{link_b}, @src()).rc_decrement();

    pod_a.rc_decrement();
    pod_b.rc_decrement();
    link_a.rc_decrement();
    link_b.rc_decrement();

    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "a cycle something outside still names is left alone" {
    beginTest();

    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    const cell = var_rt.make(probe);
    const link = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = cell, .probe = probe });
    objs.call(cell, comptime h("mut .set/1"), .{link}, @src()).rc_decrement();

    // The test keeps its own reference to the cell, so the cycle is live.
    link.rc_decrement();

    collectNowForTest();
    // Two: the test's probe and the link's.
    try testing.expectEqual(@as(u32, 2), probeCount(probe));

    // The cycle is still whole: the cell still answers with the link.
    const got = objs.call(cell, comptime h("mut .get/0"), .{}, @src());
    try testing.expect(got.vt == &VT_Link);
    got.rc_decrement();

    cell.rc_decrement();
    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "a cycle through a list storage is collected" {
    beginTest();

    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    const storage = storage_mod.make_storage(2);
    testListAdd(storage, probe);
    const wrapper = objs.obj_k(storage_mod.ListCaptures, &VT_TestListWrapper, .{ .list_ptr = @intFromPtr(storage) });
    testListAdd(storage, wrapper);

    wrapper.rc_decrement();

    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "one storage under two wrappers is decremented once, not once per wrapper" {
    beginTest();

    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    const storage = storage_mod.make_storage(2);
    testListAdd(storage, probe);
    const first = objs.obj_k(storage_mod.ListCaptures, &VT_TestListWrapper, .{ .list_ptr = @intFromPtr(storage) });
    // A zero-copy retag: a second wrapper over the one storage.
    storage_mod.retain_storage(storage);
    const second = objs.obj_k(storage_mod.ListCaptures, &VT_TestListWrapper, .{ .list_ptr = @intFromPtr(storage) });
    testListAdd(storage, second);

    // The storage holds the second wrapper, and both wrappers hold the storage.
    // Only the storage being its own node stops the two wrappers from each
    // claiming the storage's one reference to the probe.
    first.rc_decrement();
    second.rc_decrement();

    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "a cycle through a Map is collected" {
    beginTest();

    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    const cell = var_rt.make(probe);
    // The map's hash closure holds the cell, so the map is inside the cycle.
    const hash_fn = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = cell, .probe = probe });
    const key_eq = objs.obj_k_singleton(&VT_TestSingleton);
    const storage = gc.recycleAlloc(map_rt.MapStorage);
    storage.* = .{ .map = .empty, .keyEq = key_eq, .hashFn = hash_fn.share() };
    const map = objs.obj_k(map_rt.MapCaptures, &VT_TestMap, .{ .storage_ptr = @intFromPtr(storage) });

    // cell -> map -> hash_fn -> cell
    objs.call(cell, comptime h("mut .set/1"), .{map}, @src()).rc_decrement();
    cell.rc_decrement();
    map.rc_decrement();
    hash_fn.rc_decrement();

    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "a store of a green value makes no candidate" {
    beginTest();

    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    const cell = var_rt.make(probe);
    const green = objs.obj_k(LinkCaptures, &VT_Green, .{ .container = cell, .probe = probe });

    // The green value names the cell, so this store closes a loop in the graph,
    // but the vtable says green: the compiler proved every instance of this
    // literal acyclic, and this one is only shaped like a counter-example.
    objs.call(cell, comptime h("mut .set/1"), .{green}, @src()).rc_decrement();

    try testing.expect(!objs.cellHeader(cell).buffered);
    try testing.expectEqual(objs.Green.green, objs.greenOf(green.boxed_value()));
    for (candidates.items) |c| try testing.expect(c.data.raw_cell != cell.data.raw_cell);

    // Nothing collects this, so the test takes the loop apart by hand.
    objs.call(cell, comptime h("mut .set/1"), .{objs.obj_k_singleton(&VT_TestSingleton)}, @src()).rc_decrement();
    green.rc_decrement();
    cell.rc_decrement();
    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "a literal whose captures are all green is born green" {
    beginTest();

    // No vtable flag anywhere: the probe is green because it captures nothing,
    // and the link is green because everything it captures is.
    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    try testing.expectEqual(objs.Green.green, objs.greenOf(probe.boxed_value()));

    const inner = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = probe, .probe = probe });
    try testing.expectEqual(objs.Green.green, objs.greenOf(inner.boxed_value()));

    const outer = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = inner, .probe = probe });
    try testing.expectEqual(objs.Green.green, objs.greenOf(outer.boxed_value()));

    // A store of it can close no cycle, so the container it goes into stays out
    // of the candidate set.
    const cell = var_rt.make(probe);
    objs.call(cell, comptime h("mut .set/1"), .{outer}, @src()).rc_decrement();
    try testing.expect(!objs.cellHeader(cell).buffered);
    for (candidates.items) |c| try testing.expect(c.data.raw_cell != cell.data.raw_cell);

    objs.call(cell, comptime h("mut .set/1"), .{objs.obj_k_singleton(&VT_TestSingleton)}, @src()).rc_decrement();
    outer.rc_decrement();
    inner.rc_decrement();
    cell.rc_decrement();
    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "a literal that captures a container is not green and its cycle is collected" {
    beginTest();

    // Same `VT_Link`, no static flag. Only what the instance was built with is
    // different, which is the point of answering per instance: a `Var` is a
    // cell the program can store into, so nothing that holds one is green.
    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    const cell = var_rt.make(probe);
    const link = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = cell, .probe = probe });
    try testing.expectEqual(objs.Green.not_green, objs.greenOf(link.boxed_value()));

    // A literal that holds one of those is not green either, however deep.
    const outer = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = link, .probe = probe });
    try testing.expectEqual(objs.Green.not_green, objs.greenOf(outer.boxed_value()));
    outer.rc_decrement();

    objs.call(cell, comptime h("mut .set/1"), .{link}, @src()).rc_decrement();
    try testing.expect(objs.cellHeader(cell).buffered);

    cell.rc_decrement();
    link.rc_decrement();

    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "a list wrapper is not green, so a cycle through its storage is still found" {
    beginTest();

    // `VT_TestListWrapper` traces through a hook of its own, and its one capture
    // is an uncounted `usize`. Answering it from the captures would call it
    // vacuously green and lose every cycle a `UList` closes.
    const storage = storage_mod.make_storage(2);
    const wrapper = objs.obj_k(storage_mod.ListCaptures, &VT_TestListWrapper, .{ .list_ptr = @intFromPtr(storage) });
    try testing.expectEqual(objs.Green.not_green, objs.greenOf(wrapper.boxed_value()));

    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    testListAdd(storage, probe);
    testListAdd(storage, wrapper);
    wrapper.rc_decrement();

    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

test "a cycle through a flow's scan accumulator is collected" {
    beginTest();

    // The chunk executor writes a `scan` accumulator while holding the cell and
    // not the chain that owns it: the only candidate from `noteMutable`, and
    // the only one entered before the edge exists.
    const probe = objs.obj_k(Empty, &VT_Probe, .{});

    const cell = gc.recycleAlloc(flow_types.ScanCell);
    cell.* = .{ .acc = objs.obj_k_singleton(&VT_TestSingleton) };
    const ops = gc.recycleAllocSlice(flow_types.OpDesc, 1);
    ops[0] = .{
        .kind = .scan,
        .closure = objs.obj_k_singleton(&VT_TestSingleton),
        .state = @intFromPtr(cell),
        .flags = .{ .stateless = false },
    };
    const chain = ops_node.make_ops(ops);
    const chain_edge = ops_node.opsEdge(chain);

    // chain -> cell.acc -> link -> chain. The accumulator takes the reference the
    // literal was born with, which is what the executor does on each element.
    cell.acc = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = chain_edge, .probe = probe });

    // The flow that would have held the chain never existed, so this is the one
    // reference outside the cycle.
    chain_edge.rc_decrement();

    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}

/// Builds a cycle and breaks it by hand, over and over.
fn churn(probe: *const FatPtr, rounds: usize) void {
    for (0..rounds) |_| {
        const cell = var_rt.make(probe.*);
        const link = objs.obj_k(LinkCaptures, &VT_Link, .{ .container = cell, .probe = probe.* });
        objs.call(cell, comptime h("mut .set/1"), .{link}, @src()).rc_decrement();
        // Breaking the cycle by hand is what lets the count come back exactly:
        // what a collection must not do is take a second decrement for it.
        objs.call(cell, comptime h("mut .set/1"), .{objs.obj_k_singleton(&VT_TestSingleton)}, @src()).rc_decrement();
        cell.rc_decrement();
        link.rc_decrement();
    }
}

test "a cycle built and broken on several threads is neither leaked nor freed twice" {
    beginTest();

    const probe = objs.obj_k(Empty, &VT_Probe, .{});
    const thread_count = 4;
    const rounds = 300;

    var threads: [thread_count]std.Thread = undefined;
    for (&threads) |*t| t.* = try std.Thread.spawn(.{}, churn, .{ &probe, rounds });
    for (threads) |t| t.join();

    // Every cycle was broken by hand, so reference counting has taken all of
    // them. A count above one is a reference a pass failed to give back, and
    // the process would have died on a double free.
    collectNowForTest();
    try testing.expectEqual(@as(u32, 1), probeCount(probe));
    probe.rc_decrement();
}
