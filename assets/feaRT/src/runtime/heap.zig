//! Size-classed heap with thread-local allocation and thread-local free.
//!
//! Every block comes from one chunk, and a chunk belongs to one heap and one
//! size class for its whole life. A heap is named by a worker id, so a thread
//! finds its lists with an index rather than a thread-local read.
//!
//! The address of a block identifies it: all chunks are carved out of one
//! contiguous reservation, so `(ptr - arena_base) >> CHUNK_SHIFT` is the chunk
//! and `chunk_meta` holds its class and owner. One shift and one load answer
//! "what size is this block, and whose is it", which is what lets a block
//! freed on the wrong thread go back to its owner instead of through a global
//! lock. A block freed by its owner goes on a plain singly-linked list with no
//! atomics; a foreign free pushes it on the owner's remote stack with one
//! compare-and-swap, and the owner takes the whole backlog with one swap the
//! next time a class runs dry.
//!
//! Allocations above `MAX_SMALL` bypass the classes and get their own mapping,
//! recorded in a table under a mutex. They are rare, so nothing about them is
//! on a hot path.

const std = @import("std");
const builtin = @import("builtin");
const assert = std.debug.assert;
const WriteLock = @import("sync/write_lock.zig").WriteLock;

/// The link cell of a free block, overlaid on its first 8 bytes. Every size
/// class is therefore at least 8 bytes and at least 8-byte aligned.
pub const Node = extern struct { next: ?*Node };

pub const CHUNK_SHIFT: usize = 18;
pub const CHUNK_SIZE: usize = @as(usize, 1) << CHUNK_SHIFT;

/// Chunks the arena reserves. The reservation is `PROT_NONE` and never
/// committed until a chunk is carved, so this bounds the heap without costing
/// memory (256k chunks of 256 KiB is 64 GiB).
const ARENA_CHUNKS: usize = 256 * 1024;

/// Bytes made readable in one step, and the alignment the arena starts on. A
/// chunk is the unit one heap owns, and it is small so that a heap holding a
/// class it barely uses rounds up by little. The mapping size and alignment
/// are what a transparent huge page needs to back it, which is what keeps the
/// fault count and TLB pressure of a chunk this small worth the rounding.
const COMMIT_SIZE: usize = 2 * 1024 * 1024;
const COMMIT_CHUNKS: usize = COMMIT_SIZE / CHUNK_SIZE;

comptime {
    assert(COMMIT_SIZE % CHUNK_SIZE == 0);
}

/// The largest allocation a size class answers. Above this the caller gets its
/// own mapping.
pub const MAX_SMALL: usize = 16 * 1024;

/// Alignment stricter than this needs an over-allocated mapping, so it goes
/// down the large path however small the request is.
const MAX_CLASS_ALIGN: usize = 4096;

/// The size classes, from tightest to loosest steps. The steps are graded so
/// the sizes a program makes most of are matched exactly: object literal bodies
/// (header plus one word per capture) land on the 8-byte steps with no waste;
/// the steps widen where an exact match stops being worth a class.
pub const CLASS_SIZES = blk: {
    var out: [64]usize = undefined;
    var n: usize = 0;
    var s: usize = 8;
    while (s <= 128) : (s += 8) { out[n] = s; n += 1; }
    s = 144;
    while (s <= 256) : (s += 16) { out[n] = s; n += 1; }
    s = 288;
    while (s <= 512) : (s += 32) { out[n] = s; n += 1; }
    s = 576;
    while (s <= 1024) : (s += 64) { out[n] = s; n += 1; }
    s = 2048;
    while (s <= MAX_SMALL) : (s *= 2) { out[n] = s; n += 1; }
    break :blk out[0..n].*;
};

pub const NUM_CLASSES: usize = CLASS_SIZES.len;

comptime {
    assert(CLASS_SIZES[0] >= @sizeOf(Node));
    assert(NUM_CLASSES <= std.math.maxInt(u16));
    // A chunk must hold at least a few blocks of the largest class, or a class
    // would spend more time carving chunks than handing out blocks.
    assert(CHUNK_SIZE / CLASS_SIZES[NUM_CLASSES - 1] >= 8);
}

/// The smallest class that holds `i * 8` bytes, indexed by `(size + 7) / 8`.
/// One load replaces a scan over the classes on the allocation path.
const SIZE_CLASS_LUT = blk: {
    @setEvalBranchQuota(100_000);
    var out: [MAX_SMALL / 8 + 1]u8 = undefined;
    var i: usize = 0;
    while (i < out.len) : (i += 1) {
        const want = i * 8;
        var c: usize = 0;
        while (CLASS_SIZES[c] < want) c += 1;
        out[i] = c;
    }
    break :blk out;
};

/// The class that answers `size` at `alignment`, or null for the large path.
/// A block sits at a whole multiple of its class size from a `CHUNK_SIZE`-
/// aligned chunk base, so a block meets an alignment exactly when its class
/// size is a multiple of that alignment.
inline fn classFor(size: usize, alignment: usize) ?usize {
    if (size > MAX_SMALL or alignment > MAX_CLASS_ALIGN) return null;
    var c: usize = SIZE_CLASS_LUT[(size + 7) / 8];
    while (CLASS_SIZES[c] % alignment != 0) {
        c += 1;
        if (c >= NUM_CLASSES) return null;
    }
    return c;
}

/// Class and owner of one chunk, packed so the table is one `u32` per chunk.
/// The low half is the class index, the high half the owning heap.
inline fn packMeta(class_index: usize, owner: u32) u32 {
    return @as(u32, @intCast(class_index)) | (owner << 16);
}

inline fn metaClass(word: u32) usize {
    return word & 0xffff;
}

inline fn metaOwner(word: u32) u32 {
    return word >> 16;
}

/// Heaps a process can have. A heap is indexed by worker id, and ids run from
/// 1, so slot 0 names "no worker".
pub const MAX_HEAPS: usize = 512;

/// One size class's free blocks for one heap. A fresh chunk is handed out by
/// bumping rather than by threading every block onto the free list at once:
/// threading a 2 MiB chunk of the smallest class would touch a quarter of a
/// million blocks before the first allocation returned.
const ClassState = struct {
    free: ?*Node = null,
    bump: usize = 0,
    bump_end: usize = 0,
};

const Heap = struct {
    classes: [NUM_CLASSES]ClassState = @splat(.{}),
    /// Blocks of this heap that other threads have freed. Cache-line aligned:
    /// every foreign free writes it, and the owner reads it, so sharing a line
    /// with the local lists would put that traffic on the interconnect.
    remote: std.atomic.Value(?*Node) align(std.atomic.cache_line) =
        std.atomic.Value(?*Node).init(null),
};

var heaps: [MAX_HEAPS]Heap = @splat(.{});

/// Guards heap 0 alone: worker threads name themselves and never take it, but
/// the reactor threads, the process's first thread and the unit tests all read
/// as "no worker" and share heap 0. A spin lock rather than a fiber-aware one:
/// both users hold it across a handful of instructions, and these paths can run
/// off a fiber, where a fiber-aware lock has nothing to yield to.
var shared_heap_lock: WriteLock = .{};

var chunk_meta: [ARENA_CHUNKS]std.atomic.Value(u32) = @splat(std.atomic.Value(u32).init(0));

var arena_base: usize = 0;
var arena_next: std.atomic.Value(usize) = std.atomic.Value(usize).init(0);

/// Chunks up to which the arena is readable. Guarded by `commit_lock`, which is
/// taken once per `COMMIT_CHUNKS` carves rather than once per carve.
var arena_committed: usize = 0;
var commit_lock: WriteLock = .{};

/// Makes the chunk `idx` names readable, along with the rest of its batch.
/// Chunk indices are handed out by a monotonic counter, so a thread that finds
/// `idx` already committed needs to do nothing.
fn ensureCommitted(idx: usize) bool {
    commit_lock.acquire();
    defer commit_lock.release();
    if (idx < arena_committed) return true;
    const start = arena_committed;
    const end = @min(ARENA_CHUNKS, std.mem.alignForward(usize, idx + 1, COMMIT_CHUNKS));
    _ = std.posix.mmap(
        @ptrFromInt(arena_base + start * CHUNK_SIZE),
        (end - start) * CHUNK_SIZE,
        .{ .READ = true, .WRITE = true },
        .{ .TYPE = .PRIVATE, .ANONYMOUS = true, .FIXED = true },
        -1,
        0,
    ) catch return false;
    arena_committed = end;
    return true;
}

/// 0 before the arena exists, 1 while one thread is reserving it, 2 once it is
/// there. Only `init` writes it.
var init_state: std.atomic.Value(u32) = std.atomic.Value(u32).init(0);

/// Reserves the address space every chunk is carved from, and commits nothing.
/// One reservation is what makes a block's chunk a shift of its address. The
/// mapping is `PROT_NONE` and `NORESERVE`, so the only thing it spends is
/// virtual address space.
fn reserveArena() void {
    const want = ARENA_CHUNKS * CHUNK_SIZE + COMMIT_SIZE;
    const raw = std.posix.mmap(
        null,
        want,
        .{},
        .{ .TYPE = .PRIVATE, .ANONYMOUS = true, .NORESERVE = true },
        -1,
        0,
    ) catch @panic("FeaRT heap: cannot reserve the arena");
    const raw_addr = @intFromPtr(raw.ptr);
    const base = std.mem.alignForward(usize, raw_addr, COMMIT_SIZE);
    if (base > raw_addr) {
        std.posix.munmap(raw[0 .. base - raw_addr]);
    }
    const tail = base + ARENA_CHUNKS * CHUNK_SIZE;
    const raw_end = raw_addr + want;
    if (raw_end > tail) {
        const tail_ptr: [*]align(std.heap.page_size_min) u8 = @ptrFromInt(tail);
        std.posix.munmap(tail_ptr[0 .. raw_end - tail]);
    }
    arena_base = base;
}

/// Idempotent: the runtime's own tests re-enter it, and a second call must not
/// move the arena out from under live blocks. A caller that arrives while
/// another thread is reserving waits, because the arena base every later
/// address calculation reads must be there before the first block is handed out.
pub fn init() void {
    if (init_state.load(.acquire) == 2) return;
    if (init_state.cmpxchgStrong(0, 1, .acquire, .acquire) == null) {
        reserveArena();
        init_state.store(2, .release);
        return;
    }
    while (init_state.load(.acquire) != 2) std.atomic.spinLoopHint();
}

/// The chunk holding `p`, or null when `p` is not a small block. The wrapping
/// subtract puts an address below the arena far above `ARENA_CHUNKS`, so one
/// unsigned compare covers both ends of the range.
inline fn chunkIndex(p: usize) ?usize {
    const idx = (p -% arena_base) >> CHUNK_SHIFT;
    if (idx >= ARENA_CHUNKS) return null;
    return idx;
}

/// The usable bytes of the block at `ptr`, which is its class size. Answers 0
/// for an address the classes did not hand out.
pub fn blockSize(ptr: *const anyopaque) usize {
    const idx = chunkIndex(@intFromPtr(ptr)) orelse return largeSize(@intFromPtr(ptr));
    return CLASS_SIZES[metaClass(chunk_meta[idx].load(.acquire))];
}

/// Takes `size` bytes at `alignment`. `heap_id` only picks which lists answer,
/// so a caller that cannot name its worker may pass 0: a block always goes back
/// to the heap its chunk records, whichever heap hands it out.
pub fn alloc(heap_id: u32, size: usize, alignment: usize) ?[*]u8 {
    const class_index = classFor(size, alignment) orelse return allocLarge(size, alignment);
    return allocIndex(heap_id, class_index);
}

/// [`alloc`] for a caller whose size and alignment are known when it is
/// compiled, which every object literal's and every cell's is. Settling the
/// class at compile time keeps the lookup table, the alignment walk and its
/// divide out of the program.
pub inline fn allocComptime(heap_id: u32, comptime size: usize, comptime alignment: usize) ?[*]u8 {
    const class_index = comptime classFor(size, alignment);
    if (class_index == null) return allocLarge(size, alignment);
    return allocIndex(heap_id, class_index.?);
}

inline fn allocIndex(heap_id: u32, class_index: usize) ?[*]u8 {
    if (heap_id == 0) {
        shared_heap_lock.acquire();
        defer shared_heap_lock.release();
        return allocClass(&heaps[0], 0, class_index);
    }
    if (std.debug.runtime_safety) assert(heap_id < MAX_HEAPS);
    return allocClass(&heaps[heap_id], heap_id, class_index);
}

inline fn allocClass(h: *Heap, heap_id: u32, class_index: usize) ?[*]u8 {
    const cs = &h.classes[class_index];
    if (cs.free) |n| {
        cs.free = n.next;
        return @ptrCast(n);
    }
    const size = CLASS_SIZES[class_index];
    if (cs.bump + size <= cs.bump_end) {
        const p = cs.bump;
        cs.bump = p + size;
        return @ptrFromInt(p);
    }
    return allocClassSlow(h, heap_id, class_index);
}

/// The class has no block ready. Take back what other threads have freed, and
/// carve a chunk only if that leaves the class still empty.
noinline fn allocClassSlow(h: *Heap, heap_id: u32, class_index: usize) ?[*]u8 {
    if (drainRemote(h)) {
        const cs = &h.classes[class_index];
        if (cs.free) |n| {
            cs.free = n.next;
            return @ptrCast(n);
        }
    }
    carveChunk(h, heap_id, class_index) orelse return null;
    const cs = &h.classes[class_index];
    const p = cs.bump;
    cs.bump = p + CLASS_SIZES[class_index];
    return @ptrFromInt(p);
}

/// Moves the whole remote backlog onto the local lists. True when there was
/// anything to move. One swap takes the backlog, and the walk after it needs no
/// atomics because nothing else can reach the chain once it is detached. A
/// remote block may belong to any class, so each one is sorted by the class its
/// chunk records.
fn drainRemote(h: *Heap) bool {
    var n: *Node = h.remote.swap(null, .acquire) orelse return false;
    while (true) {
        const next = n.next;
        const idx = chunkIndex(@intFromPtr(n)).?;
        const cs = &h.classes[metaClass(chunk_meta[idx].load(.acquire))];
        n.next = cs.free;
        cs.free = n;
        n = next orelse return true;
    }
}

/// Commits one chunk of the arena to `class_index` for `heap_id`, and points
/// the class's bump range at it.
fn carveChunk(h: *Heap, heap_id: u32, class_index: usize) ?void {
    // The mapping below is MAP_FIXED, so it must never be aimed at an address
    // derived from an arena that does not exist yet.
    if (arena_base == 0) init();
    const idx = arena_next.fetchAdd(1, .monotonic);
    if (idx >= ARENA_CHUNKS) return null;
    if (!ensureCommitted(idx)) return null;
    const base = arena_base + idx * CHUNK_SIZE;
    // Published before any pointer into the chunk exists, so a thread that
    // reaches a block of this chunk has already ordered against this store
    // through whatever published the block.
    chunk_meta[idx].store(packMeta(class_index, heap_id), .release);

    const size = CLASS_SIZES[class_index];
    const cs = &h.classes[class_index];
    cs.bump = base;
    cs.bump_end = base + (CHUNK_SIZE / size) * size;
}

/// Gives one block back, from the thread `heap_id` names. The chunk says whose
/// block it is: its owner puts it straight on a local list, anyone else hands
/// it to the owner with one compare-and-swap, so a block never changes size
/// class and no free ever takes a global lock.
pub fn free(heap_id: u32, ptr: *anyopaque) void {
    const idx = chunkIndex(@intFromPtr(ptr)) orelse {
        freeLarge(ptr);
        return;
    };
    const word = chunk_meta[idx].load(.monotonic);
    const owner = metaOwner(word);
    const node: *Node = @ptrCast(@alignCast(ptr));
    if (owner == heap_id and heap_id != 0) {
        pushLocal(&heaps[heap_id], metaClass(word), node);
        return;
    }
    if (owner != heap_id) {
        remotePush(&heaps[owner], node);
        return;
    }
    shared_heap_lock.acquire();
    defer shared_heap_lock.release();
    pushLocal(&heaps[0], metaClass(word), node);
}

inline fn pushLocal(h: *Heap, class_index: usize, node: *Node) void {
    const cs = &h.classes[class_index];
    node.next = cs.free;
    cs.free = node;
}

fn remotePush(h: *Heap, node: *Node) void {
    var old = h.remote.load(.monotonic);
    while (true) {
        node.next = old;
        if (h.remote.cmpxchgWeak(old, node, .release, .monotonic)) |observed| {
            old = observed;
        } else return;
    }
}

/// Every mapping the large path handed out, by the pointer it returned. A
/// large allocation gets its own mapping outside the arena, so the chunk table
/// cannot name it and a side table has to. These are a handful per run, so one
/// lock over the table costs nothing measurable.
const LargeEntry = struct { base: usize, len: usize };
var large_table: std.AutoHashMapUnmanaged(usize, LargeEntry) = .empty;
var large_lock: WriteLock = .{};

fn allocLarge(size: usize, alignment: usize) ?[*]u8 {
    const over = if (alignment > std.heap.page_size_min) size + alignment else size;
    const raw = std.posix.mmap(
        null,
        over,
        .{ .READ = true, .WRITE = true },
        .{ .TYPE = .PRIVATE, .ANONYMOUS = true },
        -1,
        0,
    ) catch return null;
    const base = @intFromPtr(raw.ptr);
    const user = std.mem.alignForward(usize, base, @max(alignment, 1));

    large_lock.acquire();
    defer large_lock.release();
    large_table.put(std.heap.page_allocator, user, .{ .base = base, .len = raw.len }) catch {
        std.posix.munmap(raw);
        return null;
    };
    return @ptrFromInt(user);
}

fn freeLarge(ptr: *anyopaque) void {
    large_lock.acquire();
    const entry = large_table.fetchRemove(@intFromPtr(ptr));
    large_lock.release();
    const e = (entry orelse return).value;
    const base: [*]align(std.heap.page_size_min) u8 = @ptrFromInt(e.base);
    std.posix.munmap(base[0..e.len]);
}

fn largeSize(p: usize) usize {
    large_lock.acquire();
    defer large_lock.release();
    const e = large_table.get(p) orelse return 0;
    return e.len - (p - e.base);
}

test "every size lands in a class that holds it" {
    const testing = std.testing;
    var size: usize = 1;
    while (size <= MAX_SMALL) : (size += 1) {
        const c = classFor(size, 8).?;
        try testing.expect(CLASS_SIZES[c] >= size);
        // The class below would not have held it, so nothing is over-served.
        if (c > 0) try testing.expect(CLASS_SIZES[c - 1] < size);
    }
    try testing.expectEqual(@as(?usize, null), classFor(MAX_SMALL + 1, 8));
}

test "an over-aligned class size is always a multiple of the alignment" {
    const testing = std.testing;
    for ([_]usize{ 8, 16, 32, 64, 128 }) |a| {
        var size: usize = 1;
        while (size <= 1024) : (size += 1) {
            const c = classFor(size, a) orelse continue;
            try testing.expect(CLASS_SIZES[c] >= size);
            try testing.expectEqual(@as(usize, 0), CLASS_SIZES[c] % a);
        }
    }
}

test "a block round-trips through its own heap and comes back reused" {
    const testing = std.testing;
    init();
    for (CLASS_SIZES) |size| {
        const first = alloc(1, size, 8).?;
        free(1, first);
        const second = alloc(1, size, 8).?;
        try testing.expectEqual(first, second);
        free(1, second);
    }
}

test "a block knows its own size class from its address alone" {
    const testing = std.testing;
    init();
    for (CLASS_SIZES) |size| {
        const p = alloc(2, size, 8).?;
        try testing.expectEqual(size, blockSize(p));
        free(2, p);
    }
}

test "distinct blocks of one class never overlap" {
    const testing = std.testing;
    init();
    const size = CLASS_SIZES[0];
    var seen: [4096]usize = undefined;
    for (&seen) |*slot| {
        const p = alloc(3, size, 8).?;
        // Writing the whole block proves the next block does not start inside it.
        @memset(p[0..size], 0xAB);
        slot.* = @intFromPtr(p);
    }
    std.mem.sort(usize, &seen, {}, std.sort.asc(usize));
    for (seen[1..], seen[0 .. seen.len - 1]) |hi, lo| {
        try testing.expect(hi - lo >= size);
    }
}

test "the free-list link overlay leaves the neighbouring block alone" {
    const testing = std.testing;
    init();
    const size = CLASS_SIZES[0];
    const a = alloc(4, size, 8).?;
    const b = alloc(4, size, 8).?;
    @memset(b[0..size], 0x5A);
    // Freeing `a` writes a link into `a`'s first bytes and must not reach `b`.
    free(4, a);
    for (b[0..size]) |byte| try testing.expectEqual(@as(u8, 0x5A), byte);
    free(4, b);
}

test "masking finds the right chunk for the first and the last block in it" {
    const testing = std.testing;
    init();
    // The largest class carves few enough blocks per chunk to exhaust one.
    const class_index = NUM_CLASSES - 1;
    const size = CLASS_SIZES[class_index];
    const per_chunk = CHUNK_SIZE / size;
    var blocks: [1024]usize = undefined;
    const n = @min(per_chunk, blocks.len);
    for (blocks[0..n]) |*slot| slot.* = @intFromPtr(alloc(5, size, 8).?);
    for (blocks[0..n]) |p| {
        const idx = chunkIndex(p).?;
        try testing.expectEqual(class_index, metaClass(chunk_meta[idx].load(.acquire)));
        try testing.expectEqual(@as(u32, 5), metaOwner(chunk_meta[idx].load(.acquire)));
    }
    for (blocks[0..n]) |p| free(5, @ptrFromInt(p));
}

test "a block freed on another thread goes to its owner and is reused there" {
    const testing = std.testing;
    init();
    const size = CLASS_SIZES[2];
    const owner: u32 = 6;
    const stranger: u32 = 7;

    const p = alloc(owner, size, 8).?;
    // Drain first, so what the owner has after the foreign free is only `p`.
    _ = drainRemote(&heaps[owner]);
    heaps[owner].classes[classFor(size, 8).?].free = null;

    free(stranger, p);
    try testing.expect(heaps[owner].remote.load(.acquire) != null);
    try testing.expect(heaps[stranger].classes[classFor(size, 8).?].free == null);

    try testing.expect(drainRemote(&heaps[owner]));
    try testing.expectEqual(@as(?*Node, @ptrCast(@alignCast(p))), heaps[owner].classes[classFor(size, 8).?].free);
}

test "many threads freeing one heap's blocks lose none of them" {
    const testing = std.testing;
    init();
    const owner: u32 = 8;
    const size = CLASS_SIZES[1];
    const per_thread = 512;
    const thread_count = 4;

    var blocks: [thread_count][per_thread]usize = undefined;
    for (&blocks) |*batch| {
        for (batch) |*slot| slot.* = @intFromPtr(alloc(owner, size, 8).?);
    }
    _ = drainRemote(&heaps[owner]);
    heaps[owner].classes[classFor(size, 8).?].free = null;

    const Worker = struct {
        fn run(id: u32, batch: *const [per_thread]usize) void {
            for (batch) |p| free(id, @ptrFromInt(p));
        }
    };
    var threads: [thread_count]std.Thread = undefined;
    for (&threads, 0..) |*t, i| {
        t.* = try std.Thread.spawn(.{}, Worker.run, .{ @as(u32, @intCast(100 + i)), &blocks[i] });
    }
    for (&threads) |*t| t.join();

    try testing.expect(drainRemote(&heaps[owner]));
    var count: usize = 0;
    var n = heaps[owner].classes[classFor(size, 8).?].free;
    while (n) |node| : (n = node.next) count += 1;
    try testing.expectEqual(@as(usize, thread_count * per_thread), count);
}

test "an allocation past the largest class gets its own mapping and gives it back" {
    const testing = std.testing;
    init();
    const size = MAX_SMALL * 4;
    const p = alloc(9, size, 8).?;
    try testing.expectEqual(@as(?usize, null), chunkIndex(@intFromPtr(p)));
    @memset(p[0..size], 0x11);
    try testing.expect(blockSize(p) >= size);
    free(9, p);
    try testing.expectEqual(@as(usize, 0), blockSize(p));
}

test "an over-aligned request is aligned however it is answered" {
    const testing = std.testing;
    init();
    for ([_]usize{ 16, 64, 4096, 8192 }) |a| {
        for ([_]usize{ 8, 200, 5000, MAX_SMALL * 2 }) |size| {
            const p = alloc(10, size, a).?;
            try testing.expectEqual(@as(usize, 0), @intFromPtr(p) % a);
            @memset(p[0..size], 0x22);
            free(10, p);
        }
    }
}

test "heap 0 stays consistent when several threads share it" {
    const testing = std.testing;
    init();
    const size = CLASS_SIZES[3];
    const Worker = struct {
        fn run() void {
            for (0..2048) |_| {
                const p = alloc(0, size, 8).?;
                @memset(p[0..size], 0x33);
                free(0, p);
            }
        }
    };
    var threads: [4]std.Thread = undefined;
    for (&threads) |*t| t.* = try std.Thread.spawn(.{}, Worker.run, .{});
    for (&threads) |*t| t.join();
    // Nothing to assert beyond surviving: an unguarded list would have lost or
    // duplicated blocks, which the allocations above would have caught.
    try testing.expect(true);
}
