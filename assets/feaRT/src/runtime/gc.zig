const std = @import("std");
const builtin = @import("builtin");
const heap = @import("heap.zig");
const log = @import("log.zig");
const process = @import("process_singletons.zig");
const op_counters = @import("op_counters.zig");
const worker_mod = @import("worker.zig");

/// From `FEART_ALLOCS_OUT`. Null selects the default path.
pub var allocs_out_path: ?[]const u8 = null;

pub fn set_allocs_out_path(path: ?[]const u8) void {
	allocs_out_path = path;
}

const TRACK_ALLOCS = @import("build_options").track_allocs;
/// The least growth in the heap that starts a trace.
///
/// A trace is a backstop for reference cycles, not the way a program gets its memory
/// back. Reference counting already reclaims everything acyclic as it dies, so this
/// floor delays only cyclic garbage. It must therefore not run for a program that simply
/// holds what it makes.
///
/// A block is one object, and a small object is near 64 bytes, so this floor lets a heap
/// grow by tens of megabytes before a trace. That leaves every small program alone, and
/// it bounds a leaked cycle to a size worth less than the trace that would find it. A
/// full trace over a heap of some hundreds of megabytes costs tens of milliseconds, which
/// is why the floor is not lower.
/// Starts false: glibc masks all signals inside `pthread_create`, so a
/// stop-the-world during the spawn loop never gets its suspend signal
/// acknowledged. `WorkerPool.run` enables collection once every thread exists.
var isSafeToCollectCycles = std.atomic.Value(bool).init(false);

/// The heap this thread should take blocks from, or 0 for the shared heap.
///
/// Only which free lists answer, never whether a block is safe to hand out: a
/// block goes back to the heap its own chunk records, so a thread that cannot
/// name one takes the shared heap and loses locality, nothing else. A worker
/// sets this when its loop starts and the collector sets its own; the reactor
/// threads and the process's first thread leave it at 0.
///
/// A thread-local rather than a masked stack pointer because `gc.allocator` is
/// reached off a fiber stack as well as from generated code.
pub threadlocal var tls_heap_id: u32 = 0;

inline fn currentHeapId() u32 {
	return tls_heap_id;
}

fn gc_alloc(ctx: *anyopaque, len: usize, ptr_align: std.mem.Alignment, ret_addr: usize) ?[*]u8 {
	_ = ctx;
	if (TRACK_ALLOCS) track_record(ret_addr, len);
	return heap.alloc(currentHeapId(), len, ptr_align.toByteUnits());
}

/// No realloc: a block belongs to one size class for its whole life, so the
/// only way to change its size is to take a new one and copy.
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
	heap.free(currentHeapId(), @ptrCast(buf.ptr));
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
	heap.init();

	if (TRACK_ALLOCS) {
		track_table = std.AutoHashMap(usize, TrackEntry).init(std.heap.page_allocator);
		track_initialized = true;
	}
}

/// Gives one block of a fixed type back.
///
/// A block goes onto the free list of its own size class, on the heap its chunk
/// names. Nothing is zeroed first: a free list holds no live references, and
/// nothing scans a free block.
pub inline fn recycleDestroy(comptime T: type, ptr: *T, comptime source: log.RawFreeSource) void {
	// The free list overlays a link on the first 8 bytes of a dead block, so
	// anything freed must be at least that big. Pad the type at the call site.
	comptime std.debug.assert(@sizeOf(T) >= @sizeOf(heap.Node));
	traceIfLarge(@sizeOf(T), source, @intFromEnum(std.mem.Alignment.of(T)));
	heap.free(currentHeapId(), @ptrCast(ptr));
}

/// Mirror of `recycleDestroy`.
pub inline fn recycleAlloc(comptime T: type) *T {
	comptime std.debug.assert(@sizeOf(T) >= @sizeOf(heap.Node));
	const raw = heap.allocComptime(currentHeapId(), @sizeOf(T), @alignOf(T)) orelse @panic("OOM");
	return @ptrCast(@alignCast(raw));
}

/// Slice flavour. The class that answers holds at least `n` items, and any
/// slack in it is unused.
pub inline fn recycleAllocSlice(comptime T: type, n: usize) []T {
	const raw = heap.alloc(currentHeapId(), @sizeOf(T) * n, @alignOf(T)) orelse @panic("OOM");
	const ptr: [*]T = @ptrCast(@alignCast(raw));
	return ptr[0..n];
}

/// Mirror of `recycleDestroy` for slices.
pub inline fn recycleDestroySlice(comptime T: type, slice: []T, comptime source: log.RawFreeSource) void {
	std.debug.assert(slice.len >= 1);
	const size = @sizeOf(T) * slice.len;
	std.debug.assert(size >= @sizeOf(heap.Node));
	traceIfLarge(size, source, @intFromEnum(std.mem.Alignment.of(T)));
	heap.free(currentHeapId(), @ptrCast(slice.ptr));
}

pub fn free(ptr: ?*anyopaque) void {
	if (ptr) |p| heap.free(currentHeapId(), p);
}

/// Records a block too big for any size class. Such a block has its own
/// mapping and costs a system call at both ends, so which call sites make them
/// is worth being able to see under `-Dlog_alloc_caching=true`.
inline fn traceIfLarge(size: usize, comptime source: log.RawFreeSource, align_log2: u8) void {
	if (size > heap.MAX_SMALL) {
		log.trace_alloc_caching(.alloc_raw_free, size, @intFromEnum(source), align_log2);
	}
}

pub fn enable_cycle_collection() void {
	isSafeToCollectCycles.store(true, .seq_cst);
	@import("cycles.zig").rearm();
}

pub fn disable_cycle_collection() void {
	isSafeToCollectCycles.store(false, .seq_cst);
	@import("cycles.zig").disarm();
	@import("safepoint.zig").forceResume();
}

pub fn collectCycles(worker_id: u32) void {
	op_counters.bump(.cycle_collection);
	@import("cycles.zig").collectCycles(worker_id);
}

pub fn dump_rc_delta() void {}

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


