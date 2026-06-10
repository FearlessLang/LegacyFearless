//! Optionally at build time, a stack trace can be enabled.
//! Each time a method is entered it will record itself on a ring buffer here.
//! We can't just use the shadow stack for this because that only records call-sites relevant
//! to VPF. The real stack is unusable even with demangling because of the amount of aggressive inlining
//! and comptime optimisations done.
//!
//! On the happy path each method pops its frame via its `defer` block. A throwing method never runs
//! that, so the stack trace still holds the entire live call-chain. When an error is constructed we
//! capture this as part of the error payload.

const std = @import("std");
const build_options = @import("build_options");
const objs = @import("../objs.zig");
const shadow_stack = @import("../shadow_stack.zig");
const gc = @import("../gc.zig");
const Fiber = @import("../fiber.zig").Fiber;
const writeStderr = @import("./io.zig").writeStderr;

pub const MAX_FRAMES = 256;

/// A single trace frame. A `null` vtable marks a fiber boundary sentinel,
/// inserted when a child fiber inherits its parent's trace.
pub const TraceFrame = struct {
  vtable: ?*const objs.VTable,
  method: u64,
};

/// A flattened, throw-time copy of a fiber's trace. Captured into the error
/// payload at the throw site (see [error.zig]) so that even after the error
/// re-propagates onto other fibers.
pub const TraceSnapshot = struct {
  frames: []const TraceFrame,
  /// Frames dropped to ring-buffer overflow (reported as "N earlier frames omitted").
	omitted: usize,
};

/// Snapshot the current fiber's live trace into GC memory. Returns null when
/// `trace_frames` is off, no fiber is running, the trace is empty, or the
/// allocation fails. Called at the throw site, where the trace TLS still points
/// at the throwing fiber.
pub fn captureTrace() ?*TraceSnapshot {
  if (!build_options.trace_frames) return null;
  const top_ptr = shadow_stack.getTraceTop() orelse return null;
  const ring = shadow_stack.getStackTrace() orelse return null;
  const top = top_ptr.*;
  if (top == 0) return null;

  const shown = if (top > shadow_stack.TRACE_CAP) shadow_stack.TRACE_CAP else top;
  const omitted = top - shown;
  const buf = gc.allocator.alloc(TraceFrame, shown) catch return null;
  for (buf, 0..) |*slot, i| {
    slot.* = ring[(omitted + i) % shadow_stack.TRACE_CAP];
  }
  const snap = gc.allocator.create(TraceSnapshot) catch return null;
  snap.* = .{ .frames = buf, .omitted = omitted };
  return snap;
}

/// Push a trace frame at method entry. Comptime-elides to nothing when the
/// `trace_frames` build option is off. The store is a ring write while `top`
/// tracks true depth, so deep recursion keeps the most-recent frames.
pub inline fn tracePush(vt: ?*const objs.VTable, method: u64) void {
  if (!build_options.trace_frames) return;
  const top_ptr = shadow_stack.getTraceTop() orelse return;
  const frames = shadow_stack.getStackTrace() orelse return;
  const idx = top_ptr.*;
  frames[idx % shadow_stack.TRACE_CAP] = .{ .vtable = vt, .method = method };
  top_ptr.* = idx + 1;
}

/// Pop a trace frame on normal method return. Comptime-elides when off. On an
/// unwinding throw the surrounding `defer` never runs, so the frame is left in
/// place to form the crash trace.
pub inline fn tracePop() void {
  if (!build_options.trace_frames) return;
  const top_ptr = shadow_stack.getTraceTop() orelse return;
  if (top_ptr.* > 0) top_ptr.* -= 1;
}

/// Seed a freshly-created child fiber's trace with a copy of its parent's live
/// frames followed by a single boundary sentinel, so a crash on the child shows
/// the parent's call chain beneath a `--- fibre boundary ---` marker.
pub fn inheritStackTrace(child: *Fiber, parent_frames: *const [shadow_stack.TRACE_CAP]TraceFrame, parent_top: usize) void {
  if (!build_options.trace_frames) return;

  const child_frames = &child.trace_frames;
  const child_top = &child.trace_top;

  @memcpy(child_frames[0..], parent_frames[0..]);
  child_frames[parent_top % shadow_stack.TRACE_CAP] = .{ .vtable = null, .method = 0 };
  child_top.* = parent_top + 1;
}

/// Strip a trailing `/<gen>` off a vtable `type_name` (e.g. `test.Test/0` →
/// `test.Test`) for trace rendering. Leaves names without that suffix untouched.
fn strippedTypeName(name: []const u8) []const u8 {
  var i = name.len;
  while (i > 0 and name[i - 1] >= '0' and name[i - 1] <= '9') i -= 1;
  if (i > 0 and i < name.len and name[i - 1] == '/') return name[0 .. i - 1];
  return name;
}

/// Render one trace line. Returns true if a numbered frame was written, false
/// for a boundary sentinel (so the caller leaves the frame counter unchanged).
fn writeTraceFrame(buf: []u8, frame_no: usize, frame: TraceFrame) bool {
  const vt = frame.vtable orelse {
    writeStderr("  --- fibre boundary ---\n");
    return false;
  };
  const tn = strippedTypeName(vt.type_name);
  const line = if (vt.method_name(frame.method)) |mn|
    std.fmt.bufPrint(buf, "  #{d} {s} {s}\n", .{ frame_no, tn, mn }) catch "  <frame>\n"
  else
    std.fmt.bufPrint(buf, "  #{d} <runtime @ 0x{x} {s}>\n", .{ frame_no, @intFromPtr(vt), tn }) catch "  <frame>\n";
  writeStderr(line);
  return true;
}

fn writeOmitted(buf: []u8, omitted: usize) void {
  if (omitted == 0) return;
  const line = std.fmt.bufPrint(buf, "  ({d} earlier frames omitted)\n", .{omitted}) catch "  (earlier frames omitted)\n";
  writeStderr(line);
}

/// Render a throw-site trace snapshot, newest call first. Each frame is
/// `#<n> <type> <method>` using the Fearless names on the vtable; a vtable whose
/// method hash isn't found falls back to `<runtime @ 0x<addr> <type>>`, and
/// boundary sentinels print as `--- fiber boundary ---`.
pub fn printTraceSnapshot(snap: *const TraceSnapshot) void {
  if (snap.frames.len == 0 and snap.omitted == 0) return;
  writeStderr("Stack trace (most recent call first):\n");
  var buf: [256]u8 = undefined;
  var frame_no: usize = 0;
  var i = snap.frames.len;
  while (i > 0) {
    i -= 1;
    if (writeTraceFrame(&buf, frame_no, snap.frames[i])) frame_no += 1;
  }
  writeOmitted(&buf, snap.omitted);
}

/// Render the *current* fiber's live trace stack, newest call first. Used by
/// `abortProgram`, which exits in-place with no error payload to carry a
/// snapshot. Comptime-elides to nothing when `trace_frames` is off.
pub fn printCrashTrace() void {
  if (!build_options.trace_frames) return;
  const top_ptr = shadow_stack.getTraceTop() orelse return;
  const ring = shadow_stack.getStackTrace() orelse return;
  const top = top_ptr.*;
  if (top == 0) return;

  const CAP = shadow_stack.TRACE_CAP;
  const shown = if (top > CAP) CAP else top;
  const omitted = top - shown;

  writeStderr("Stack trace (most recent call first):\n");
  var buf: [256]u8 = undefined;
  var frame_no: usize = 0;
  var depth = top;
  while (depth > omitted) {
    depth -= 1;
    if (writeTraceFrame(&buf, frame_no, ring[depth % CAP])) frame_no += 1;
  }
  writeOmitted(&buf, omitted);
}
