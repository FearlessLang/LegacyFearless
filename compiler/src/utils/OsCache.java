package utils;

import java.nio.file.Path;

/// The platform-appropriate per-user cache directory for Fearless. Holds state
/// that is expensive to recreate but safe to delete: the pinned Zig toolchain,
/// Zig's content-addressed build caches and the compiled base library.
/// The `FEARLESS_CACHE` environment variable overrides the platform default.
public final class OsCache {
  private OsCache() {}

  public static Path root() {
    var env = System.getenv("FEARLESS_CACHE");
    if (env != null && !env.isBlank()) { return Path.of(env); }
    var home = System.getProperty("user.home");
    var os = System.getProperty("os.name").toLowerCase();
    if (os.contains("win")) {
      var localAppData = System.getenv("LOCALAPPDATA");
      var base = localAppData != null && !localAppData.isBlank()
        ? Path.of(localAppData)
        : Path.of(home, "AppData", "Local");
      return base.resolve("Fearless").resolve("cache");
    }
    if (os.contains("mac")) { return Path.of(home, "Library", "Caches", "fearless"); }
    var xdg = System.getenv("XDG_CACHE_HOME");
    var base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".cache");
    return base.resolve("fearless");
  }
}
