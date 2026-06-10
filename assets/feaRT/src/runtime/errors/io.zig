const std = @import("std");

pub fn writeStderr(bytes: []const u8) void {
  _ = std.posix.system.write(std.posix.STDERR_FILENO, bytes.ptr, bytes.len);
}
