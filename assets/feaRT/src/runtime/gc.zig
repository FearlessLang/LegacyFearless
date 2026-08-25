const std = @import("std");
const builtin = @import("builtin");
const alloc_recycler = @import("alloc_recycler.zig");
const log = @import("log.zig");
const destroyer = @import("destroyer.zig");
const process = @import("process_singletons.zig");

const libgc = @import("libgc");

/// From `FEART_ALLOCS_OUT`. Null selects the default path.
pub var allocs_out_path: ?[]const u8 = null;

pub fn set_allocs_out_path(path: ?[]const u8) void {
	allocs_out_path = path;
}

const TRACK_ALLOCS = @import("build_options").track_allocs;
const DEFAULT_RC_COLLECTION_THRESHOLD: usize = 256;

threadlocal var rc_delta: usize = 0;

/// Starts false: glibc masks all signals inside `pthread_create`, so a
/// stop-the-world during the spawn loop never gets its suspend signal
/// acknowledged. `WorkerPool.run` enables collection once every thread exists.
var isSafeToCollectCycles = std.atomic.Value(bool).init(false);

/// Stop-the-world guard for fiber context switches. Mid-switch, `mem_base` and
/// the hardware stack pointer can name two different fiber stacks, and a
/// collection that snapshots a thread there scans across disjoint mappings and
/// segfaults in `GC_push_all_stacks`. Switches are readers, `collectCycles` is
/// the writer and has priority. All seq_cst: the reader's increment/re-check
/// and the writer's flag-set/counter-read are a Dekker pair.
var switching_threads = std.atomic.Value(usize).init(0);
var collect_pending = std.atomic.Value(bool).init(false);

/// Enter the switch window. The matching `endStackSwitch` runs on the same OS
/// thread but in the switched-to context: the landing side carries the baton.
pub fn beginStackSwitch() void {
	while (true) {
		while (collect_pending.load(.seq_cst)) std.atomic.spinLoopHint();
		_ = switching_threads.fetchAdd(1, .seq_cst);
		if (!collect_pending.load(.seq_cst)) return;
		// A collection slipped in between the check and the increment; back out
		// so its drain can complete, then wait it out.
		_ = switching_threads.fetchSub(1, .seq_cst);
	}
}

pub fn endStackSwitch() void {
	_ = switching_threads.fetchSub(1, .seq_cst);
}

/// What `GC_malloc` gives unasked. Anything stricter needs `GC_memalign`.
const GC_MALLOC_ALIGNMENT: usize = @alignOf(std.c.max_align_t);

fn gc_alloc(ctx: *anyopaque, len: usize, ptr_align: std.mem.Alignment, ret_addr: usize) ?[*]u8 {
	_ = ctx;
	if (TRACK_ALLOCS) track_record(ret_addr, len);
	const alignment = ptr_align.toByteUnits();
	// `GC_memalign` returns a pointer into the middle of its block, so the result
	// can never go to `GC_free`. That suits the policy of leaving `gc.allocator`
	// memory to the collector, and it is the only way to allocate an over-aligned
	// type such as a cache-line-padded queue.
	const ptr = if (alignment > GC_MALLOC_ALIGNMENT)
		libgc.GC_memalign(alignment, len)
	else
		libgc.GC_malloc(len);
	if (ptr == null) return null;
	return @ptrCast(ptr);
}

/// No realloc: Zig's resize contract is stricter than libgc's.
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

pub fn init_gc() void {
	libgc.GC_init();

	// Reference counting catches most garbage, so collect cycles lazily by hand.
	libgc.GC_disable();

	libgc.GC_allow_register_threads();

	// TODO: confirm incremental collection is safe with the fiber system.
	//
	// Never on Darwin. There bdwgc tracks dirty pages through a Mach exception
	// handler rather than a SIGSEGV handler, and Mach exceptions are delivered
	// ahead of POSIX signals. It claims every EXC_BAD_ACCESS in the task,
	// including a fiber guard-page hit, which it cannot forward to the handler
	// `errors/signals.zig` installs. The faulting instruction then re-executes
	// forever instead of becoming a catchable ND error.
	if (comptime !builtin.os.tag.isDarwin()) {
		libgc.GC_enable_incremental();
	}

	// bdwgc does not scan TLS, so the recycler pool needs an explicit root
	// range. Worker threads do the same in `register_thread`.
  const recycle_pool_info = alloc_recycler.get_pool_info();
	libgc.GC_add_roots(recycle_pool_info.start, recycle_pool_info.end);

	initFiberRegistry();
	prev_push_other_roots = libgc.GC_get_push_other_roots();
	libgc.GC_set_push_other_roots(&pushFiberStacks);

	destroyer.init();

	if (TRACK_ALLOCS) {
		track_table = std.AutoHashMap(usize, TrackEntry).init(std.heap.page_allocator);
		track_initialized = true;
	}
}

pub inline fn recycleDestroy(comptime T: type, ptr: *T, comptime source: log.RawFreeSource) void {
    // The destroyer overlays a `?*Node` link onto the first 8 bytes of a freed
    // allocation, so anything sent there must be >= 8 bytes. Pad the type at the
    // call site; types.OpsRefCount is an example.
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

/// Mirror of `recycleDestroy`. Only the miss path bumps `rc_delta`: a pool hit
/// reuses storage already counted at its first alloc.
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

/// Slice flavour. The pool picks the smallest class >= the byte size, so a size
/// outside CLASS_SIZES falls back to `allocator.alloc`. The returned slice has
/// length `n` and any class slack is unused.
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

/// Mirror of `recycleDestroy` for slices. `alloc_recycler.push` needs an exact
/// class match, so a non-class size falls through to `destroyer.submit`.
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
	// One collector at a time. A loser skips: the winner picks up its garbage.
	if (collect_pending.cmpxchgStrong(false, true, .seq_cst, .seq_cst) != null) return;
	defer collect_pending.store(false, .seq_cst);
	// New switches spin on `collect_pending`, so this drain terminates within a
	// register swap's time.
	while (switching_threads.load(.seq_cst) != 0) std.atomic.spinLoopHint();
	libgc.GC_enable();
	defer libgc.GC_disable();
	libgc.GC_gcollect();
}

pub fn rcDeltaForTest() usize {
	return rc_delta;
}

pub fn register_thread() void {
	var sb: libgc.struct_GC_stack_base = undefined;
	_ = libgc.GC_get_stack_base(&sb);
	_ = libgc.GC_register_my_thread(&sb);

	const recycle_pool_info = alloc_recycler.get_pool_info();
	libgc.GC_add_roots(recycle_pool_info.start, recycle_pool_info.end);
}

pub fn unregister_thread() void {
	_ = libgc.GC_unregister_my_thread();
}

pub fn addRoots(start: *anyopaque, end: *anyopaque) void {
	libgc.GC_add_roots(start, end);
}

pub fn removeRoots(start: *anyopaque, end: *anyopaque) void {
	libgc.GC_remove_roots(start, end);
}

// Fiber stacks do not use `GC_add_roots`: bdwgc holds root ranges in a fixed
// 2048-entry static array, and a program with more live fibers than that aborts
// with "Too many root sets". Every live fiber is listed here instead, and a
// `push_other_roots` hook pushes its live stack region at the end of each mark
// root scan.
//
// The registry is a chain of fixed-size segments of atomic slots. Every mutation
// is one atomic store, so a world-stopped snapshot is always consistent, and
// segments are append-only, so the hook never walks reused storage.

const REG_SEG_SLOTS = 1024;

const RegSegment = struct {
	slots: [REG_SEG_SLOTS]std.atomic.Value(?*anyopaque),
	next: std.atomic.Value(?*RegSegment),
};

/// A null `seg` means "not registered".
pub const RegSlot = struct {
	seg: ?*RegSegment = null,
	idx: usize = 0,
};

/// A plain global, so the data-segment scan keeps the segments -- and through
/// them the `Fiber` structs -- alive across a cycle collection.
var reg_head: ?*RegSegment = null;
var reg_seg_count: std.atomic.Value(usize) = std.atomic.Value(usize).init(0);
/// Guards a segment append only. Slot claim and release are lock-free.
var reg_append_lock: std.atomic.Value(bool) = std.atomic.Value(bool).init(false);

/// Where this thread stopped last time, so a claim usually succeeds on its
/// first probe instead of rescanning the chain.
threadlocal var reg_cursor_seg: ?*RegSegment = null;
threadlocal var reg_cursor_idx: usize = 0;

fn newRegSegment() *RegSegment {
	const seg = allocator.create(RegSegment) catch @panic("OOM allocating fiber registry segment");
	for (&seg.slots) |*slot| slot.* = std.atomic.Value(?*anyopaque).init(null);
	seg.next = std.atomic.Value(?*RegSegment).init(null);
	_ = reg_seg_count.fetchAdd(1, .monotonic);
	return seg;
}

/// Never moves or frees an existing segment: a stop-the-world scan may be
/// walking the chain.
fn appendRegSegment() *RegSegment {
	while (reg_append_lock.cmpxchgWeak(false, true, .acquire, .monotonic) != null) {
		std.atomic.spinLoopHint();
	}
	defer reg_append_lock.store(false, .release);

	var tail = reg_head.?;
	while (tail.next.load(.acquire)) |n| tail = n;
	const seg = newRegSegment();
	tail.next.store(seg, .release);
	return seg;
}

fn initFiberRegistry() void {
	reg_head = newRegSegment();
}

/// Call only once the stack is mapped and `sp` points at a prepared frame: the
/// hook may read the slot the instant it lands.
pub fn registerFiber(fiber: *anyopaque) RegSlot {
	while (true) {
		var seg = reg_cursor_seg orelse reg_head.?;
		var idx = reg_cursor_idx;
		// One pass over every segment that existed at entry. A slot freed behind
		// the cursor is picked up on the wrap.
		var segments_left = reg_seg_count.load(.monotonic) + 1;
		while (segments_left > 0) : (segments_left -= 1) {
			while (idx < REG_SEG_SLOTS) : (idx += 1) {
				const slot = &seg.slots[idx];
				if (slot.load(.monotonic) != null) continue;
				if (slot.cmpxchgStrong(null, fiber, .release, .monotonic) == null) {
					reg_cursor_seg = seg;
					reg_cursor_idx = idx + 1;
					return .{ .seg = seg, .idx = idx };
				}
			}
			seg = seg.next.load(.acquire) orelse reg_head.?;
			idx = 0;
		}
		const fresh = appendRegSegment();
		reg_cursor_seg = fresh;
		reg_cursor_idx = 0;
	}
}

/// MUST happen strictly before the stack is unmapped: the hook scans whatever a
/// live slot points at, and an unmapped stack faults.
pub fn unregisterFiber(slot: RegSlot) void {
	const seg = slot.seg orelse return;
	seg.slots[slot.idx].store(null, .release);
}

/// The hook bdwgc already had, chained rather than replaced.
var prev_push_other_roots: libgc.GC_push_other_roots_proc = null;

/// Runs at the end of `GC_push_roots` with the world stopped, so it must not
/// allocate or lock.
///
/// A Running fiber's saved `sp` is stale, but the thread-stack scan already
/// covers its live region, so pushing it again is redundant, never unsound.
/// Scanning `sp`..top also keeps dead pointers above `sp` from holding garbage.
fn pushFiberStacks() callconv(.c) void {
	const Fiber = @import("fiber.zig").Fiber;
	var seg = reg_head;
	while (seg) |s| {
		for (&s.slots) |*slot| {
			const raw = slot.load(.acquire) orelse continue;
			const fiber: *Fiber = @ptrCast(@alignCast(raw));

			// The struct sits at the base of its own mapping rather than in the
			// GC heap, so nothing else traces the shadow frames, the scope or the
			// obligation it holds.
			const header = @intFromPtr(fiber);
			libgc.GC_push_all_eager(@ptrFromInt(header), @ptrFromInt(header + @sizeOf(Fiber)));

			const sp = fiber.sp;
			const bottom = @intFromPtr(fiber.stack_bottom);
			const top = bottom + fiber.stack_size;
			if (sp < bottom or sp >= top) continue;
			libgc.GC_push_all_eager(@ptrFromInt(sp), @ptrFromInt(top));
		}
		seg = s.next.load(.acquire);
	}
	if (prev_push_other_roots) |prev| prev();
}

const SetStackBottomArgs = struct { mem_base: *anyopaque };

fn setStackBottomLocked(arg: ?*anyopaque) callconv(.c) ?*anyopaque {
	const args: *SetStackBottomArgs = @ptrCast(@alignCast(arg.?));
	var sb: libgc.struct_GC_stack_base = std.mem.zeroes(libgc.struct_GC_stack_base);
	sb.mem_base = args.mem_base;
	libgc.GC_set_stackbottom(null, &sb);
	return null;
}

/// Runs under libgc's alloc lock, so a fiber switch may call it.
pub fn setStackBottom(mem_base: *anyopaque) void {
	var args = SetStackBottomArgs{ .mem_base = mem_base };
	_ = libgc.GC_call_with_alloc_lock(&setStackBottomLocked, @ptrCast(&args));
}

pub fn currentStackBase() *anyopaque {
	var sb: libgc.struct_GC_stack_base = undefined;
	_ = libgc.GC_get_stack_base(&sb);
	return sb.mem_base.?;
}

/// This thread's RC delta, which shows whether reference counting balanced.
/// Gated on `-Dtrack_allocs=true`. Call from each worker just before it exits.
pub fn dump_rc_delta() void {
	if (!TRACK_ALLOCS) return;
	const tid = std.Thread.getCurrentId();
	std.debug.print("[track_allocs] thread {d}: rc_delta={d}\n", .{ tid, rc_delta });
}

/// Per-call-site allocation counts and bytes, as TSV. Needs
/// `-Dtrack_allocs=true`. The path is `FEART_ALLOCS_OUT`, default
/// `./feart-allocs.tsv`. Resolve addresses with
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
