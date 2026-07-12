const std = @import("std");
const alloc_recycler = @import("alloc_recycler.zig");
const log = @import("log.zig");
const destroyer = @import("destroyer.zig");
const process = @import("process_singletons.zig");

const libgc = @import("libgc");

/// Set by `main` from `init.environ_map.get("FEART_ALLOCS_OUT")`. `null` means
/// fall back to the default path.
pub var allocs_out_path: ?[]const u8 = null;

pub fn set_allocs_out_path(path: ?[]const u8) void {
	allocs_out_path = path;
}

const TRACK_ALLOCS = @import("build_options").track_allocs;
const DEFAULT_RC_COLLECTION_THRESHOLD: usize = 256;

threadlocal var rc_delta: usize = 0;

/// Starts false: a stop-the-world during startup deadlocks, because glibc
/// masks all signals in a thread while it is inside `pthread_create`, so the
/// collector's suspend signal can never be acknowledged by the main thread
/// mid-spawn-loop. `WorkerPool.run` enables collection once every runtime
/// thread exists.
var isSafeToCollectCycles = std.atomic.Value(bool).init(false);

/// Stop-the-world guard for fiber context switches. Mid-switch, the GC's
/// recorded stack bottom (`mem_base`) and the hardware stack pointer can refer
/// to two different fiber stacks; a collection snapshotting a thread in that
/// window scans across disjoint mappings and segfaults in
/// `GC_push_all_stacks`. Switches register as readers around the window;
/// `collectCycles` is the writer and stops the world only once no thread is
/// mid-switch, with writer priority (pending collection makes new switches
/// spin). Everything is seq_cst: the reader's increment/re-check and the
/// writer's flag-set/counter-read form a Dekker pair.
var switching_threads = std.atomic.Value(usize).init(0);
var collect_pending = std.atomic.Value(bool).init(false);

/// Enter the switch window (mem_base and stack pointer about to disagree).
/// The matching `endStackSwitch` runs on the same OS thread but in the
/// switched-to context: the landing side of the swap carries the baton.
pub fn beginStackSwitch() void {
	while (true) {
		while (collect_pending.load(.seq_cst)) std.atomic.spinLoopHint();
		_ = switching_threads.fetchAdd(1, .seq_cst);
		if (!collect_pending.load(.seq_cst)) return;
		// A collection slipped in between the check and our increment; back
		// out so its drain can complete, then wait it out.
		_ = switching_threads.fetchSub(1, .seq_cst);
	}
}

/// Leave the switch window: mem_base and the stack pointer agree again.
pub fn endStackSwitch() void {
	_ = switching_threads.fetchSub(1, .seq_cst);
}

fn gc_alloc(ctx: *anyopaque, len: usize, ptr_align: std.mem.Alignment, ret_addr: usize) ?[*]u8 {
	_ = ctx; _ = ptr_align;
	if (TRACK_ALLOCS) track_record(ret_addr, len);
	const ptr = libgc.GC_malloc(len);
	if (ptr == null) return null;
	return @ptrCast(ptr);
}

/// We do not implement realloc because Zig's resize contract is stricter than libgc's realloc
fn gc_resize(ctx: *anyopaque, buf: []u8, buf_align: std.mem.Alignment, new_len: usize, ret_addr: usize) bool {
	_ = ctx; _ = buf; _ = buf_align; _ = new_len; _ = ret_addr;
	return false;
}

fn gc_remap(ctx: *anyopaque, buf: []u8, buf_align: std.mem.Alignment, new_len: usize, ret_addr: usize) ?[*]u8 {
	_ = ctx; _ = buf; _ = buf_align; _ = new_len; _ = ret_addr;
	return null;
}

fn gc_free(ctx: *anyopaque, buf: []u8, buf_align: std.mem.Alignment, ret_addr: usize) void {
	_ = ctx; _ = buf_align; _ = ret_addr;
	libgc.GC_free(buf.ptr);
}

const gc_vtable = std.mem.Allocator.VTable{
	.alloc = gc_alloc,
	.resize = gc_resize,
	.remap = gc_remap,
	.free = gc_free,
};

pub const allocator = std.mem.Allocator{
	.ptr = undefined,
	.vtable = &gc_vtable,
};

/// Initialize the garbage collector with thread support.
pub fn init_gc() void {
	libgc.GC_init();

	// Because reference counting will catch most uses, we manually GC to catch cycles lazily
	libgc.GC_disable();

	libgc.GC_allow_register_threads();

	// Pre-grow the heap to skip warmup collection thrash. Must be after GC_init.
	// _ = libgc.GC_expand_hp(256 * 1024 * 1024);

	// TODO: this is questionably safe with my fiber system, lets see...
	libgc.GC_enable_incremental();

	// bdwgc doesn't scan TLS by default, so register the main thread's
	// recycler pool as a root range. Worker threads do the same in
	// `register_thread`.
  const recycle_pool_info = alloc_recycler.get_pool_info();
	libgc.GC_add_roots(recycle_pool_info.start, recycle_pool_info.end);

	destroyer.init();

	if (TRACK_ALLOCS) {
		track_table = std.AutoHashMap(usize, TrackEntry).init(std.heap.page_allocator);
		track_initialized = true;
	}
}

pub inline fn recycleDestroy(comptime T: type, ptr: *T, comptime source: log.RawFreeSource) void {
    // The destroyer overlays a `?*Node` link onto the first 8 bytes of every
    // freed allocation, so anything sent its way must be ≥ 8 bytes. Pad the
    // type at the call site if you hit this -- see types.OpsRefCount for an
    // example.
    comptime std.debug.assert(@sizeOf(T) >= @sizeOf(destroyer.Node));
    const size = @sizeOf(T);
    const align_log2: u8 = @intFromEnum(std.mem.Alignment.of(T));
    const raw: [*]u8 = @ptrCast(ptr);
    @memset(raw[0..size], 0);
    if (!alloc_recycler.push(raw, size, align_log2)) {
        log.trace_alloc_caching(.alloc_raw_free, size, @intFromEnum(source), align_log2);
        destroyer.submit(@ptrCast(raw));
    }
}

/// Mirror of `recycleDestroy`. Try the per-thread pool first; fall back to a
/// fresh GC_malloc on miss. Only the fallback bumps `rc_delta` -- pool hits
/// reuse storage that was already counted at its original alloc, keeping the
/// counter paired with `recycleDestroy` (which never decrements).
pub inline fn recycleAlloc(comptime T: type) *T {
    comptime std.debug.assert(@sizeOf(T) >= @sizeOf(destroyer.Node));
    const align_log2: u8 = @intFromEnum(std.mem.Alignment.of(T));
    if (alloc_recycler.pop(@sizeOf(T), align_log2)) |recycled| {
        return @ptrCast(@alignCast(recycled.ptr));
    }
    const fresh = allocator.create(T) catch @panic("OOM");
    recordRcAlloc();
    return fresh;
}

/// Slice flavour. Byte size is computed at runtime; pool lookup picks the
/// smallest class >= that size, so callers whose byte size doesn't fall in
/// CLASS_SIZES silently degrade to a fresh `allocator.alloc` (same behavior
/// as today). Returned slice has length `n`; any class slack is unused.
pub inline fn recycleAllocSlice(comptime T: type, n: usize) []T {
    const align_log2: u8 = @intFromEnum(std.mem.Alignment.of(T));
    const bytes = @sizeOf(T) * n;
    if (alloc_recycler.pop(bytes, align_log2)) |recycled| {
        const ptr: [*]T = @ptrCast(@alignCast(recycled.ptr));
        return ptr[0..n];
    }
    const fresh = allocator.alloc(T, n) catch @panic("OOM");
    recordRcAlloc();
    return fresh;
}

/// Mirror of `recycleDestroy` for slices. Byte size is computed at runtime;
/// `alloc_recycler.push` requires an exact class match, so non-class sizes
/// fall through to `destroyer.submit` (which batches the bdwgc lock). The
/// runtime asserts capture the Treiber-overlay invariant (need >= 8 bytes).
pub inline fn recycleDestroySlice(comptime T: type, slice: []T, comptime source: log.RawFreeSource) void {
    std.debug.assert(slice.len >= 1);
    const size = @sizeOf(T) * slice.len;
    std.debug.assert(size >= @sizeOf(destroyer.Node));
    const align_log2: u8 = @intFromEnum(std.mem.Alignment.of(T));
    const raw: [*]u8 = @ptrCast(slice.ptr);
    @memset(raw[0..size], 0);
    if (!alloc_recycler.push(raw, size, align_log2)) {
        log.trace_alloc_caching(.alloc_raw_free, size, @intFromEnum(source), align_log2);
        destroyer.submit(@ptrCast(raw));
    }
}

pub fn free(ptr: ?*anyopaque) void {
	if (ptr) |p| destroyer.submit(p);
}

pub fn recordRcAlloc() void {
	rc_delta += 1;
	maybeCollectCycles();
}

pub fn recordRcFree() void {
	rc_delta = rc_delta -| 1;
	maybeCollectCycles();
}

pub fn enable_cycle_collection() void {
	isSafeToCollectCycles.store(true, .seq_cst);
}

pub fn disable_cycle_collection() void {
	isSafeToCollectCycles.store(false, .seq_cst);
}

pub fn maybeCollectCycles() void {
	if (rc_delta < DEFAULT_RC_COLLECTION_THRESHOLD) return;
	if (!isSafeToCollectCycles.load(.seq_cst)) return;
	collectCycles();
}

fn collectCycles() void {
	rc_delta = 0;
	// Single collector at a time; a concurrent loser skips: its garbage is
	// picked up by the winner's collection.
	if (collect_pending.cmpxchgStrong(false, true, .seq_cst, .seq_cst) != null) return;
	defer collect_pending.store(false, .seq_cst);
	// Drain mid-switch threads; new switches spin on `collect_pending`, so
	// this terminates within a register-swap's time.
	while (switching_threads.load(.seq_cst) != 0) std.atomic.spinLoopHint();
	libgc.GC_enable();
	defer libgc.GC_disable();
	libgc.GC_gcollect();
}

pub fn rcDeltaForTest() usize {
	return rc_delta;
}

/// Register the current thread with the garbage collector.
/// Call this when a new thread is spawned.
pub fn register_thread() void {
	var sb: libgc.struct_GC_stack_base = undefined;
	_ = libgc.GC_get_stack_base(&sb);
	_ = libgc.GC_register_my_thread(&sb);

	const recycle_pool_info = alloc_recycler.get_pool_info();
	libgc.GC_add_roots(recycle_pool_info.start, recycle_pool_info.end);
}

/// Unregister the current thread from the garbage collector.
/// Call this when a thread is about to exit.
pub fn unregister_thread() void {
	_ = libgc.GC_unregister_my_thread();
}

/// Register a memory range `[start, end)` so the GC scans it for pointers.
pub fn addRoots(start: *anyopaque, end: *anyopaque) void {
	libgc.GC_add_roots(start, end);
}

/// Unregister a previously registered root range.
pub fn removeRoots(start: *anyopaque, end: *anyopaque) void {
	libgc.GC_remove_roots(start, end);
}

const SetStackBottomArgs = struct { mem_base: *anyopaque };

fn setStackBottomLocked(arg: ?*anyopaque) callconv(.c) ?*anyopaque {
	const args: *SetStackBottomArgs = @ptrCast(@alignCast(arg.?));
	var sb: libgc.struct_GC_stack_base = std.mem.zeroes(libgc.struct_GC_stack_base);
	sb.mem_base = args.mem_base;
	libgc.GC_set_stackbottom(null, &sb);
	return null;
}

/// Update the GC's notion of where this thread's stack bottom lives. Performed
/// under libgc's alloc lock so it's safe to call from a fiber-switch.
pub fn setStackBottom(mem_base: *anyopaque) void {
	var args = SetStackBottomArgs{ .mem_base = mem_base };
	_ = libgc.GC_call_with_alloc_lock(&setStackBottomLocked, @ptrCast(&args));
}

/// Returns the OS-thread stack bottom as libgc currently sees it. Useful when
/// switching off a fiber stack back onto the scheduler's OS stack.
pub fn currentStackBase() *anyopaque {
	var sb: libgc.struct_GC_stack_base = undefined;
	_ = libgc.GC_get_stack_base(&sb);
	return sb.mem_base.?;
}

/// Print this thread's current RC delta. Gated on `-Dtrack_allocs=true`. Each
/// worker should call this just before exiting so we can see whether RC was
/// balanced on the way out, since the delta is thread-local.
pub fn dump_rc_delta() void {
	if (!TRACK_ALLOCS) return;
	const tid = std.Thread.getCurrentId();
	std.debug.print("[track_allocs] thread {d}: rc_delta={d}\n", .{ tid, rc_delta });
}

/// Dump per-call-site allocation counts/bytes to a TSV file. Only does anything
/// when built with `-Dtrack_allocs=true`. Output path is `FEART_ALLOCS_OUT` env
/// var, defaulting to `./feart-allocs.tsv`. Resolve addresses with
/// `addr2line -e ./zig-out/bin/feart -f -i 0xADDR`.
pub fn dump_allocs() void {
	if (!TRACK_ALLOCS) return;
	track_lock.lockUncancelable(process.runtime_io);
	defer track_lock.unlock(process.runtime_io);
	if (!track_initialized) return;

	const out_path: []const u8 = allocs_out_path orelse "feart-allocs.tsv";

	const file = std.Io.Dir.cwd().createFile(process.runtime_io, out_path, .{}) catch |e| {
		std.debug.print("[track_allocs] failed to open {s}: {s}\n", .{ out_path, @errorName(e) });
		return;
	};
	defer file.close(process.runtime_io);
	var line_buf: [128]u8 = undefined;
	const header = "# addr\tcount\tbytes\n";
	file.writeStreamingAll(process.runtime_io, header) catch {};
	var it = track_table.iterator();
	while (it.next()) |kv| {
		const line = std.fmt.bufPrint(&line_buf, "0x{x}\t{d}\t{d}\n", .{ kv.key_ptr.*, kv.value_ptr.count, kv.value_ptr.bytes }) catch break;
		file.writeStreamingAll(process.runtime_io, line) catch break;
	}
	std.debug.print("[track_allocs] wrote {d} call sites to {s}\n", .{ track_table.count(), out_path });
}

const TrackEntry = struct { count: u64, bytes: u64 };
var track_table: std.AutoHashMap(usize, TrackEntry) = undefined;
var track_lock: std.Io.Mutex = .init;
var track_initialized: bool = false;

fn track_record(ret_addr: usize, len: usize) void {
	if (!TRACK_ALLOCS) return;
	track_lock.lockUncancelable(process.runtime_io);
	defer track_lock.unlock(process.runtime_io);
	if (!track_initialized) return;
	const gop = track_table.getOrPut(ret_addr) catch return;
	if (!gop.found_existing) gop.value_ptr.* = .{ .count = 0, .bytes = 0 };
	gop.value_ptr.count += 1;
	gop.value_ptr.bytes += len;
}

test "explicit cycle collection resets thread-local RC delta" {
	const testing = std.testing;
	init_gc();
	const before = rcDeltaForTest();
	recordRcAlloc();
	try testing.expectEqual(before + 1, rcDeltaForTest());
	collectCycles();
	try testing.expectEqual(@as(usize, 0), rcDeltaForTest());
}
