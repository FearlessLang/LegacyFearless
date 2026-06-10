const std = @import("std");
const objs = @import("../objs.zig");
const bool_intrinsics = @import("bool.zig");
const int_intrinsics = @import("int.zig");
const nat_intrinsics = @import("nat.zig");
const list_intrinsics = @import("list.zig");
const gc = @import("../gc.zig");

const FatPtr = objs.FatPtr;
const FearlessValue = objs.FearlessValue;
const h = objs.hash_signature;

/// String captures: pointer to UTF-8 data + length.
/// Must be extern struct for compatibility with obj_k/deref.
pub const StrCaptures = extern struct {
    ptr: usize, // [*]const u8 stored as usize (extern struct can't hold pointers to non-extern types)
    len: i64,
    owns: u32,
};

// C-ABI thunks for vtable dispatch
fn T_str_concat(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    return str_concat(self, other);
}
fn T_str_eq(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    return str_eq(self, other);
}
fn T_str_neq(self: FatPtr, other: FatPtr) callconv(.c) FatPtr {
    return str_neq(self, other);
}
fn T_str_str(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return self.share();
}
fn T_str_size(self: FatPtr) callconv(.c) FatPtr {
    return str_size(self);
}
fn T_str_isEmpty(self: FatPtr) callconv(.c) FatPtr {
    return str_is_empty(self);
}
fn T_str_join(self: FatPtr, flow: FatPtr) callconv(.c) FatPtr {
    return str_join(self, flow);
}

fn str_drop(header: *anyopaque) callconv(.c) void {
    const Layout = objs.GenObjectLayoutType(StrCaptures);
    const self: *const Layout = @ptrCast(@alignCast(header));
    const caps = self.captures;
    if (caps.owns == 0) return;
    const ptr: [*]u8 = @ptrFromInt(caps.ptr);
    gc.allocator.free(ptr[0..@intCast(caps.len)]);
}

pub const VT_Str: objs.VTable = .{
    .type_name = "base.Str/0",
    .hashes = &.{
        h("imm +/1"),
        h("imm ==/1"),
        h("imm !=/1"),
        h("read .str/0"),
        h("imm .size/0"),
        h("read .isEmpty/0"),
        h("imm .join/1"),
    },
    .methods = &.{
        @ptrCast(&T_str_concat),
        @ptrCast(&T_str_eq),
        @ptrCast(&T_str_neq),
        @ptrCast(&T_str_str),
        @ptrCast(&T_str_size),
        @ptrCast(&T_str_isEmpty),
        @ptrCast(&T_str_join),
    },
    .method_names = &.{
        "imm +/1",
        "imm ==/1",
        "imm !=/1",
        "read .str/0",
        "imm .size/0",
        "read .isEmpty/0",
        "imm .join/1",
    },
    .drop_fn = str_drop,
};

/// Create a string FatPtr from a raw pointer and length.
pub fn make_str(ptr: [*]const u8, len: usize) FatPtr {
    return make_str_with_ownership(ptr, len, false);
}

fn make_owned_str(ptr: [*]const u8, len: usize) FatPtr {
    return make_str_with_ownership(ptr, len, true);
}

fn make_str_with_ownership(ptr: [*]const u8, len: usize, owns: bool) FatPtr {
    return objs.obj_k(StrCaptures, &VT_Str, .{
        .ptr = @intFromPtr(ptr),
        .len = @as(i64, @intCast(len)),
        .owns = if (owns) 1 else 0,
    });
}

/// Create a string FatPtr from a comptime string literal.
/// The data lives in the binary's rodata section (zero-copy).
pub fn make_str_from_literal(comptime s: []const u8) FatPtr {
    return make_str(s.ptr, s.len);
}

/// Extract the UTF-8 slice from a string FatPtr.
pub fn deref_str(fp: FatPtr) []const u8 {
    const caps = objs.deref(StrCaptures, fp);
    const ptr: [*]const u8 = @ptrFromInt(caps.ptr);
    const len: usize = @intCast(caps.len);
    return ptr[0..len];
}

/// Concatenate two strings. Allocates new storage via GC.
pub fn str_concat(a: FatPtr, b: FatPtr) FatPtr {
    defer a.rc_decrement();
    defer b.rc_decrement();
    const sa = deref_str(a);
    const sb = deref_str(b);
    const new_len = sa.len + sb.len;
    const buf = gc.allocator.alloc(u8, new_len) catch @panic("OOM");
    @memcpy(buf[0..sa.len], sa);
    @memcpy(buf[sa.len..], sb);
    return make_owned_str(buf.ptr, new_len);
}

/// String equality. Returns a Bool FatPtr.
pub fn str_eq(a: FatPtr, b: FatPtr) FatPtr {
    defer a.rc_decrement();
    defer b.rc_decrement();
    const sa = deref_str(a);
    const sb = deref_str(b);
    return bool_intrinsics.to_bool(std.mem.eql(u8, sa, sb));
}

/// String inequality. Returns a Bool FatPtr.
pub fn str_neq(a: FatPtr, b: FatPtr) FatPtr {
    defer a.rc_decrement();
    defer b.rc_decrement();
    const sa = deref_str(a);
    const sb = deref_str(b);
    return bool_intrinsics.to_bool(!std.mem.eql(u8, sa, sb));
}

/// String size (number of bytes, not graphemes — simplified for now).
/// Returns a Nat FatPtr.
pub fn str_size(s: FatPtr) FatPtr {
    defer s.rc_decrement();
    const data = deref_str(s);
    return nat_intrinsics.make(@as(u64, @intCast(data.len)));
}

/// String isEmpty. Returns a Bool FatPtr.
pub fn str_is_empty(s: FatPtr) FatPtr {
    defer s.rc_decrement();
    const data = deref_str(s);
    return bool_intrinsics.to_bool(data.len == 0);
}

/// Convert an Int/Nat (i64) to a decimal string. Allocates via GC.
pub fn int_to_str(n: FatPtr) FatPtr {
    const uval: u64 = if (n.vt == &nat_intrinsics.VT_Nat) nat_intrinsics.deref(n) else @bitCast(int_intrinsics.deref(n));
    var buf: [20]u8 = undefined; // max u64 decimal digits
    var len: usize = 0;
    if (uval == 0) {
        buf[0] = '0';
        len = 1;
    } else {
        var tmp = uval;
        while (tmp > 0) : (len += 1) {
            buf[len] = @intCast('0' + (tmp % 10));
            tmp /= 10;
        }
        // Reverse
        var i: usize = 0;
        var j: usize = len - 1;
        while (i < j) {
            const t = buf[i];
            buf[i] = buf[j];
            buf[j] = t;
            i += 1;
            j -= 1;
        }
    }
    // Copy to GC-allocated memory
    const result = gc.allocator.alloc(u8, len) catch @panic("OOM");
    @memcpy(result, buf[0..len]);
    return make_owned_str(result.ptr, len);
}

/// Join a Flow[Str] using the receiver as separator.
/// Materialises the flow into a List, then concatenates with separator between elements.
/// Could definitely be written better by calling fold on the flow with a singleton folder.
pub fn str_join(separator: FatPtr, flow: FatPtr) FatPtr {
    // Materialise the flow into a List[Str]
    const list = objs.call(flow, comptime h("mut .list/0"), .{}, @src());
    defer separator.rc_decrement();
    defer list.rc_decrement();

    // Access the list's backing ArrayList
    const al = list_intrinsics.deref_list(list);
    const items = al.items;

    if (items.len == 0) {
        return make_str("".ptr, 0);
    }
    if (items.len == 1) {
        return items[0].share();
    }

    const sep = deref_str(separator);

    // Calculate total length
    var total_len: usize = 0;
    for (items) |item| {
        total_len += deref_str(item).len;
    }
    total_len += sep.len * (items.len - 1);

    // Allocate and build result
    const buf = gc.allocator.alloc(u8, total_len) catch @panic("OOM");
    var pos: usize = 0;
    for (items, 0..) |item, i| {
        if (i > 0) {
            @memcpy(buf[pos..][0..sep.len], sep);
            pos += sep.len;
        }
        const s = deref_str(item);
        @memcpy(buf[pos..][0..s.len], s);
        pos += s.len;
    }

    return make_owned_str(buf.ptr, total_len);
}
