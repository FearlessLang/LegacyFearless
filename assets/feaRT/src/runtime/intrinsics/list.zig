//! Public API for the FeaRT List/UList intrinsics.
//!
//! The real implementation lives in `lists/`; this module exposes the symbols
//! the compiler-generated code and other intrinsics reach for. The compiler
//! hardcodes `@import("runtime/intrinsics/list.zig")`, so keep this file in
//! place and narrow.

const storage = @import("lists/storage.zig");
const instance = @import("lists/instance.zig");
const factory = @import("lists/factory.zig");

pub const ListStorage = storage.ListStorage;
pub const ListCaptures = storage.ListCaptures;
pub const deref_list = storage.deref_list;
pub const make_storage = storage.make_storage;

pub const wrap_list_storage = instance.wrap_list_storage;
pub const wrap_ulist_storage = instance.wrap_ulist_storage;
pub const VT_List = instance.VT_List;
pub const VT_UList = instance.VT_UList;

pub const VT_ListFactory = factory.VT_ListFactory;
pub const VT_UListFactory = factory.VT_UListFactory;
