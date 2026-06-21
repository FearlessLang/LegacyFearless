const std = @import("std");
const objs = @import("../objs.zig");
const nat_rt = @import("nat.zig");
const int_rt = @import("int.zig");
const float_rt = @import("float.zig");
const byte_rt = @import("byte.zig");
const str_rt = @import("strings/index.zig");

const FatPtr = objs.FatPtr;
const h = objs.hash_signature;

// `base.CheapHash` — an implementation-defined, non-cryptographic hasher with
// per-instance mutable state, so each instantiation is a fresh heap object (the
// Java backend likewise does `new CheapHash()`). The exact algorithm only needs
// to be deterministic and to agree for byte-equal strings; it mirrors
// `assets/rt/CheapHash.java`.
pub const CheapHashCaptures = extern struct { result: i32 };

fn deref_caps(fp: FatPtr) *CheapHashCaptures {
    const Layout = objs.GenObjectLayoutType(CheapHashCaptures);
    const self: *Layout = @ptrCast(@alignCast(fp.boxed_value()));
    return &self.captures;
}

pub fn make_cheap_hash() FatPtr {
    return objs.obj_k(CheapHashCaptures, &VT_CheapHash, .{ .result = 1 });
}

/// `((acc << 5) - acc) + part` == `acc*31 + part`, all wrapping (Java int math).
fn combine(acc: i32, part: i32) i32 {
    return (acc *% 31) +% part;
}

/// Java `Long.hashCode(x)`.
fn long_hash(x: u64) i32 {
    return @bitCast(@as(u32, @truncate(x ^ (x >> 32))));
}

fn cheap_compute(self: FatPtr) callconv(.c) FatPtr {
    defer self.rc_decrement();
    return nat_rt.make(@bitCast(@as(i64, deref_caps(self).result)));
}

fn cheap_nat(self: FatPtr, x: FatPtr) callconv(.c) FatPtr {
    const caps = deref_caps(self);
    caps.result = combine(caps.result, long_hash(nat_rt.deref(x)));
    return self;
}

fn cheap_int(self: FatPtr, x: FatPtr) callconv(.c) FatPtr {
    const caps = deref_caps(self);
    caps.result = combine(caps.result, long_hash(@bitCast(int_rt.deref(x))));
    return self;
}

fn cheap_float(self: FatPtr, x: FatPtr) callconv(.c) FatPtr {
    const caps = deref_caps(self);
    caps.result = combine(caps.result, long_hash(@bitCast(float_rt.deref(x))));
    return self;
}

fn cheap_byte(self: FatPtr, x: FatPtr) callconv(.c) FatPtr {
    const caps = deref_caps(self);
    // Java `Byte.hashCode` sign-extends the signed byte value.
    caps.result = combine(caps.result, @as(i8, @bitCast(byte_rt.deref(x))));
    return self;
}

fn cheap_str(self: FatPtr, x: FatPtr) callconv(.c) FatPtr {
    defer x.rc_decrement();
    const caps = deref_caps(self);
    var hv: i32 = 1;
    for (str_rt.deref_str(x)) |b| hv = (hv *% 31) +% @as(i32, b);
    caps.result = combine(caps.result, hv);
    return self;
}

fn cheap_hash(self: FatPtr, x: FatPtr) callconv(.c) FatPtr {
    // Default `Hasher.hash(x)` body: `x.hash(this)`.
    return objs.call(x, comptime h("read .hash/1"), .{self}, @src());
}

pub const VT_CheapHash: objs.VTable = .{
    .type_name = "base.CheapHash/0",
    .hashes = &.{
        h("mut .compute/0"),
        h("mut .nat/1"),
        h("mut .int/1"),
        h("mut .float/1"),
        h("mut .byte/1"),
        h("mut .str/1"),
        h("mut .hash/1"),
    },
    .methods = &.{
        @ptrCast(&cheap_compute),
        @ptrCast(&cheap_nat),
        @ptrCast(&cheap_int),
        @ptrCast(&cheap_float),
        @ptrCast(&cheap_byte),
        @ptrCast(&cheap_str),
        @ptrCast(&cheap_hash),
    },
    .method_names = &.{
        "mut .compute/0",
        "mut .nat/1",
        "mut .int/1",
        "mut .float/1",
        "mut .byte/1",
        "mut .str/1",
        "mut .hash/1",
    },
};
