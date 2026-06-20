package codegen.zig;

import utils.Bug;
import utils.IoErr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/// Locates the pinned Zig toolchain used by the FeaRT backend.
///
/// Resolution order: the `FEARLESS_ZIG` environment variable (path to a zig
/// executable, for developers who manage their own toolchain), then a previously
/// downloaded toolchain under the given cache directory, else the official release
/// tarball is downloaded from ziglang.org, checksum-verified and unpacked there.
/// A `zig` on PATH is never used implicitly: builds must be reproducible
/// against the exact pinned compiler version.
public final class ZigToolchain {
  public static final String ZIG_VERSION = "0.16.0";
  /// Official sha256 sums from https://ziglang.org/download/index.json for ZIG_VERSION.
  private static final Map<String, String> SHA256 = Map.of(
    "x86_64-linux", "70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00",
    "aarch64-linux", "ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17",
    "x86_64-macos", "0387557ed1877bc6a2e1802c8391953baddba76081876301c522f52977b52ba7",
    "aarch64-macos", "b23d70deaa879b5c2d486ed3316f7eaa53e84acf6fc9cc747de152450d401489"
  );

  /// One mirror base URL per line, maintained by the Zig project.
  private static final String MIRRORS_URL = "https://ziglang.org/download/community-mirrors.txt";
  /// Used when ziglang.org itself is unreachable, so an upstream outage does not
  /// block toolchain installation. Manually fetched from MIRRORS_URL; only needs
  /// updating if the upstream list changes significantly.
  private static final List<String> FALLBACK_MIRRORS = List.of(
    "https://pkg.machengine.org/zig",
    "https://zigmirror.hryx.net/zig",
    "https://zig.linus.dev/zig",
    "https://zig.squirl.dev",
    "https://zig.florent.dev",
    "https://zig.mirror.mschae23.de/zig",
    "https://zigmirror.meox.dev",
    "https://ziglang.freetls.fastly.net",
    "https://zig.tilok.dev",
    "https://zig-mirror.tsimnet.eu/zig",
    "https://zig.karearl.com/zig",
    "https://pkg.earth/zig",
    "https://fs.liujiacai.net/zigbuilds"
  );
  /// Mirrors ask download tooling to identify itself via this query parameter.
  private static final String SOURCE_PARAM = "?source=fearless-compiler";

  private ZigToolchain() {}

  /// Returns the path to the zig executable, downloading the toolchain into
  /// `toolchainDir` if needed.
  public static Path resolve(Path toolchainDir) {
    var env = System.getenv("FEARLESS_ZIG");
    if (env != null) { return Path.of(env); }
    var platform = platform();
    var exe = toolchainDir.resolve(dirName(platform)).resolve("zig");
    if (Files.isExecutable(exe)) { return exe; }
    return IoErr.of(() -> download(platform, toolchainDir, exe));
  }

  private static String dirName(String platform) {
    return "zig-" + platform + "-" + ZIG_VERSION;
  }

  private static String platform() {
    var osName = System.getProperty("os.name").toLowerCase();
    var os = osName.contains("linux") ? "linux"
      : osName.contains("mac") ? "macos"
      : null;
    var archName = System.getProperty("os.arch").toLowerCase();
    var arch = archName.equals("amd64") || archName.equals("x86_64") ? "x86_64"
      : archName.equals("aarch64") || archName.equals("arm64") ? "aarch64"
      : null;
    if (os == null || arch == null) {
      throw Bug.of("The FeaRT backend is not supported on " + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
        + ". Use the default Java backend, or set FEARLESS_ZIG to a zig " + ZIG_VERSION + " executable to try anyway.");
    }
    return arch + "-" + os;
  }

  /// Serialised on a lock file so concurrent compiler processes (e.g. forked test
  /// runners) sharing a cache directory download the toolchain only once.
  private static Path download(String platform, Path toolchainDir, Path exe) throws IOException {
    Files.createDirectories(toolchainDir);
    try (var lockCh = FileChannel.open(toolchainDir.resolve(".toolchain-lock"),
      StandardOpenOption.CREATE, StandardOpenOption.WRITE); var _ = lockCh.lock()) {
      if (Files.isExecutable(exe)) { return exe; }

      var tarName = dirName(platform) + ".tar.xz";
      var tarball = toolchainDir.resolve(tarName + ".part");
      try (var http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()) {
        fetchVerified(http, tarName, tarball, SHA256.get(platform));
      }

      // The tarball unpacks to a single zig-<platform>-<version>/ directory,
      // which lands exactly where resolve() looks for the executable.
      var tar = new ProcessBuilder("tar", "-xJf", tarball.toAbsolutePath().toString())
        .directory(toolchainDir.toFile())
        .redirectErrorStream(true)
        .start();
      var output = new String(tar.getInputStream().readAllBytes());
      int exitCode;
      try { exitCode = tar.waitFor(); }
      catch (InterruptedException e) { throw new RuntimeException(e); }
      if (exitCode != 0) {
        throw Bug.of("Failed to unpack the Zig toolchain (tar exit " + exitCode + "):\n" + output);
      }
      Files.deleteIfExists(tarball);
      if (!Files.isExecutable(exe)) {
        throw Bug.of("Zig toolchain unpacked but no executable found at " + exe);
      }
      return exe;
    }
  }

  /// Downloads `tarName` to `tarball`, preferring the community mirrors over
  /// ziglang.org, which asks tooling to spare its bandwidth. Mirrors are tried in
  /// random order to spread load; every download is verified against the pinned
  /// sha256, so a stale or malicious mirror is rejected and the next one is tried.
  /// ziglang.org itself is the last resort.
  private static void fetchVerified(HttpClient http, String tarName, Path tarball, String sha256) throws IOException {
    var urls = new ArrayList<String>();
    for (var mirror : mirrors(http)) { urls.add(mirror + "/" + tarName + SOURCE_PARAM); }
    urls.add("https://ziglang.org/download/" + ZIG_VERSION + "/" + tarName);
    System.err.println("Downloading the Zig " + ZIG_VERSION + " toolchain (one-time, ~55MB)...");
    IOException last = null;
    for (var url : urls) {
      try {
        var res = http.send(
          HttpRequest.newBuilder(URI.create(url)).build(),
          HttpResponse.BodyHandlers.ofFile(
            tarball,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          ));
        if (res.statusCode() != 200) { throw new IOException("HTTP " + res.statusCode()); }
        verifyChecksum(tarball, sha256, url);
        return;
      } catch (IOException e) {
        System.err.println("Download from " + url + " failed (" + e.getMessage() + "); trying the next mirror...");
        Files.deleteIfExists(tarball);
        last = e;
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    throw new IOException("Could not download the Zig toolchain from any mirror or ziglang.org", last);
  }

  /// The current community mirror list, shuffled so no single mirror is hammered;
  /// falls back to FALLBACK_MIRRORS when the list cannot be fetched.
  private static List<String> mirrors(HttpClient http) {
    List<String> mirrors;
    try {
      var res = http.send(
        HttpRequest.newBuilder(URI.create(MIRRORS_URL)).timeout(Duration.ofSeconds(10)).build(),
        HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) { throw new IOException("HTTP " + res.statusCode()); }
      mirrors = new ArrayList<>(res.body().lines().filter(l->!l.isBlank()).toList());
    } catch (IOException | InterruptedException e) {
      mirrors = new ArrayList<>(FALLBACK_MIRRORS);
    }
    Collections.shuffle(mirrors);
    return mirrors;
  }

  private static void verifyChecksum(Path tarball, String expected, String url) throws IOException {
    MessageDigest digest;
    try { digest = MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException e) { throw Bug.of(e); }
    var actual = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(tarball)));
    if (!actual.equals(expected)) {
      throw new IOException("Checksum mismatch for " + url + ": expected sha256 " + expected + " but got " + actual);
    }
  }
}
