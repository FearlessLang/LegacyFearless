const std = @import("std");
const builtin = @import("builtin");

// Logging configuration from build options
const log_scheduling = @import("build_options").log_scheduling;
const log_safety = @import("build_options").log_safety;
const log_dispatch = @import("build_options").log_dispatch;
const log_trace = @import("build_options").log_trace;
const log_alloc_caching = @import("build_options").log_alloc_caching;

/// Conditional scheduling-related logging.
/// Compiles away to zero-cost when disabled (-Dlog-scheduling=false, the default).
pub inline fn scheduling(comptime fmt: []const u8, args: anytype) void {
	if (!log_scheduling) return;
	std.debug.print("[SCHEDULING] " ++ fmt ++ "\n", args);
}

/// Conditional safety-related logging.
/// Compiles away to zero-cost when disabled (-Dlog-safety=false, the default).
pub inline fn safety(comptime fmt: []const u8, args: anytype) void {
	if (!log_safety) return;
	std.debug.print("[SAFETY] " ++ fmt ++ "\n", args);
}

/// Conditional dispatch/method resolution logging.
/// Compiles away to zero-cost when disabled (-Dlog-dispatch=false, the default).
pub inline fn dispatch(comptime fmt: []const u8, args: anytype) void {
	if (!log_dispatch) return;
	std.debug.print("[DISPATCH] " ++ fmt ++ "\n", args);
}

// ==========================================
// Trace ring buffer infrastructure
// ==========================================

pub const TraceEvent = struct {
	tag: Tag,
	ts: u64,
	a: usize,
	b: usize,
	c: usize,

	pub const Tag = enum(u8) {
		// Heartbeat events
		hb_entry,
		hb_promote,
		hb_cas_ok,
		hb_cas_fail,
		hb_enqueue,
		hb_enqueue_fail,

		// Application events (MF_Fib_apply)
		app_push_frame,
		app_cas_claimed,
		app_cas_promoted,
		app_fulfill_child,
		app_wait_enter,
		app_wait_return,

		// Thief events
		thief_start,
		thief_child_wait,
		thief_fn_return,
		thief_fulfill,

		// Obligation lifecycle
		obl_alloc,
		obl_fulfill,
		obl_wait_ready,
		obl_wait_park,
		obl_wait_resume,

		// Fiber lifecycle
		fiber_enqueue,
		fiber_dequeue,
		fiber_switch_to,
		fiber_switch_back,
		fiber_done,

		// Alloc recycler events
		alloc_recycle_miss,
		alloc_recycle_drain,
		alloc_raw_free,
	};
};

pub const RawFreeSource = enum(u8) {
	rc_decrement = 0,
	var_release = 1,
	isopod_release = 2,
	flow_release = 3,
	list_release = 4,
	flow_op_release = 5,
	fiber_destroy = 6,
	error_release = 7,
};

pub const TRACE_BUF_SIZE: usize = 65536;

pub const TraceBuffer = struct {
	events: [TRACE_BUF_SIZE]TraceEvent = undefined,
	/// Atomic head counter — signal handler (heartbeat) and normal code
	/// both call log() on the same OS thread, so we need fetchAdd to
	/// ensure each gets a unique slot.
	head: std.atomic.Value(usize) = std.atomic.Value(usize).init(0),

	pub fn log(self: *TraceBuffer, tag: TraceEvent.Tag, a: usize, b: usize, v: usize) void {
		if (!log_trace) return;
		const ts = globalTs();
		// fetchAdd gives each caller (fiber code or signal handler) a unique slot.
		const idx = self.head.fetchAdd(1, .monotonic) & (TRACE_BUF_SIZE - 1);
		self.events[idx] = .{
			.tag = tag,
			.ts = ts,
			.a = a,
			.b = b,
			.c = v,
		};
	}

	/// Dump the trace buffer contents to stderr using write(2) (signal-safe).
	pub fn dump(self: *const TraceBuffer, worker_id: usize) void {
		var buf: [256]u8 = undefined;
		const h = self.head.load(.monotonic);

		// Header
		var n = bufPrintHeader(&buf, worker_id, h);
		sysWrite(&buf, n);

		// Print events from oldest to newest
		const count = @min(h, TRACE_BUF_SIZE);
		const start = if (h > TRACE_BUF_SIZE) h - TRACE_BUF_SIZE else 0;

		var i: usize = 0;
		while (i < count) : (i += 1) {
			const idx = (start +% i) & (TRACE_BUF_SIZE - 1);
			const ev = &self.events[idx];
			n = bufPrintEvent(&buf, ev);
			sysWrite(&buf, n);
		}
	}
};

/// Thread-local trace buffer pointer (set per worker thread).
pub threadlocal var tls_trace_buffer: ?*TraceBuffer = null;

/// Global array of trace buffer pointers, indexed by worker ID.
pub var trace_buffers: [MAX_WORKERS]?*TraceBuffer = [_]?*TraceBuffer{null} ** MAX_WORKERS;
pub var num_workers: usize = 0;

const MAX_WORKERS = 64;

/// Global monotonic timestamp counter.
var global_ts_counter: std.atomic.Value(u64) = std.atomic.Value(u64).init(0);

fn globalTs() u64 {
	return global_ts_counter.fetchAdd(1, .monotonic);
}

/// Fresh read of tls_trace_buffer. See getCurrentWorker() for rationale.
pub noinline fn getTraceBuffer() ?*TraceBuffer {
	const tb = tls_trace_buffer;
	asm volatile ("" ::: .{ .memory = true });
	return tb;
}

/// Convenience: log to the current thread's trace buffer if available.
pub inline fn trace(tag: TraceEvent.Tag, a: usize, b: usize, v: usize) void {
	if (!log_trace) return;
	if (getTraceBuffer()) |tb| {
		tb.log(tag, a, b, v);
	}
}

pub inline fn trace_scheduling(tag: TraceEvent.Tag, a: usize, b: usize, v: usize) void {
	if (!log_scheduling) return;
	trace(tag, a, b, v);
}

pub inline fn trace_alloc_caching(tag: TraceEvent.Tag, a: usize, b: usize, v: usize) void {
	if (!log_alloc_caching) return;
	trace(tag, a, b, v);
}

// ==========================================
// Signal-safe crash handler
// ==========================================

pub fn installCrashHandler() void {
	if (!log_trace) return;
	const signals = [_]std.posix.SIG{ .SEGV, .ILL, .ABRT, .BUS };
	for (signals) |sig| {
		var sa = std.posix.Sigaction{
			.handler = .{ .handler = @ptrCast(&crashHandler) },
			.mask = std.posix.sigemptyset(),
			.flags = std.posix.SA.RESETHAND,
		};
		std.posix.sigaction(sig, &sa, null);
	}
}

pub fn dumpAllTraceBuffers() void {
	if (!log_trace) return;
	const nw = @atomicLoad(usize, &num_workers, .monotonic);
	for (0..nw) |i| {
		const tb_ptr = @atomicLoad(?*TraceBuffer, &trace_buffers[i], .monotonic);
		if (tb_ptr) |tb| {
			tb.dump(i);
		}
	}
}

fn crashHandler(sig: c_int) callconv(.c) void {
	// Write crash header
	const header = "\n=== CRASH TRACE DUMP (signal ";
	sysWrite(header, header.len);
	var decbuf: [20]u8 = undefined;
	const n = fmtDec(sig, &decbuf);
	sysWrite(&decbuf, n);
	const trailer = ") ===\n";
	sysWrite(trailer, trailer.len);

	// Dump all trace buffers
	dumpAllTraceBuffers();

	const footer = "=== END TRACE DUMP ===\n";
	sysWrite(footer, footer.len);

	// Re-raise to get default behavior (core dump etc.)
	std.posix.raise(@enumFromInt(sig)) catch {};
}

// ==========================================
// Minimal signal-safe formatting helpers
// ==========================================

fn sysWrite(buf: [*]const u8, len: usize) void {
	_ = std.posix.system.write(std.posix.STDERR_FILENO, buf, len);
}

fn bufPrintHeader(buf: *[256]u8, worker_id: usize, head: usize) usize {
	const prefix = "\n--- Worker ";
	var pos: usize = 0;
	pos = appendStr(buf, pos, prefix);
	pos = appendDec(buf, pos, worker_id);
	pos = appendStr(buf, pos, " (");
	pos = appendDec(buf, pos, @min(head, TRACE_BUF_SIZE));
	pos = appendStr(buf, pos, " events, head=");
	pos = appendDec(buf, pos, head);
	pos = appendStr(buf, pos, ") ---\n");
	return pos;
}

fn bufPrintEvent(buf: *[256]u8, ev: *const TraceEvent) usize {
	var pos: usize = 0;
	// ts
	pos = appendHex(buf, pos, ev.ts);
	pos = appendStr(buf, pos, " ");
	// tag name
	pos = appendStr(buf, pos, @tagName(ev.tag));
	pos = appendStr(buf, pos, " a=");
	pos = appendHex(buf, pos, ev.a);
	pos = appendStr(buf, pos, " b=");
	pos = appendHex(buf, pos, ev.b);
	pos = appendStr(buf, pos, " c=");
	pos = appendHex(buf, pos, ev.c);
	pos = appendStr(buf, pos, "\n");
	return pos;
}

fn appendStr(buf: *[256]u8, pos: usize, s: []const u8) usize {
	const end = @min(pos + s.len, 256);
	const copy_len = end - pos;
	@memcpy(buf[pos..end], s[0..copy_len]);
	return end;
}

fn appendDec(buf: *[256]u8, pos: usize, val: anytype) usize {
	var tmp: [20]u8 = undefined;
	const n = fmtDec(val, &tmp);
	return appendStr(buf, pos, tmp[0..n]);
}

fn appendHex(buf: *[256]u8, pos: usize, val: usize) usize {
	var tmp: [18]u8 = undefined;
	const n = fmtHex(val, &tmp);
	return appendStr(buf, pos, tmp[0..n]);
}

fn fmtDec(val: anytype, buf: *[20]u8) usize {
	const v: u64 = switch (@typeInfo(@TypeOf(val))) {
		.int => if (@typeInfo(@TypeOf(val)).int.signedness == .signed)
			@bitCast(@as(i64, val))
		else
			@intCast(val),
		.comptime_int => @intCast(val),
		else => @intCast(val),
	};
	if (v == 0) {
		buf[0] = '0';
		return 1;
	}
	var tmp: [20]u8 = undefined;
	var n: usize = 0;
	var x = v;
	while (x > 0) : (n += 1) {
		tmp[n] = @intCast((x % 10) + '0');
		x /= 10;
	}
	// Reverse
	for (0..n) |i| {
		buf[i] = tmp[n - 1 - i];
	}
	return n;
}

fn fmtHex(val: usize, buf: *[18]u8) usize {
	const hex = "0123456789abcdef";
	buf[0] = '0';
	buf[1] = 'x';
	if (val == 0) {
		buf[2] = '0';
		return 3;
	}
	var tmp: [16]u8 = undefined;
	var n: usize = 0;
	var x = val;
	while (x > 0) : (n += 1) {
		tmp[n] = hex[x & 0xf];
		x >>= 4;
	}
	// Reverse
	for (0..n) |i| {
		buf[2 + i] = tmp[n - 1 - i];
	}
	return 2 + n;
}
