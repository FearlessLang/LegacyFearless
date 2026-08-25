package codegen.zig;

/// Build configuration for the FeaRT (Zig) backend.
///
/// `tokensThreshold` is the heartbeat promotion token threshold forwarded to the
/// Zig build (null = runtime default). Tests use it to force aggressive VPF promotion.
///
/// `vpfEnabled` compiles the heartbeat/VPF promotion system in or out entirely.
///
/// `stackTraces` pushes a per-call trace stack so an uncaught crash prints a
/// Fearless stack trace, at some runtime cost.
///
/// `debug` enables stack traces and the trace ring buffer (`-Dlog_trace=true`)
/// and compiles with `ReleaseSafe` instead of `ReleaseFast`.
///
/// `fastTestBuild` swaps the production backend (LLVM + `ReleaseFast`)
/// for the self-hosted backend + `Debug`, which compiles ~9x faster at the cost
/// of unoptimised runtime code. Used only by the codegen test harness, where compile
/// time dominates and the programs are tiny.
public record ZigBuildOpts(
  Integer tokensThreshold,
  boolean vpfEnabled,
  boolean stackTraces,
  boolean debug,
  boolean fastTestBuild
) {
  public static final ZigBuildOpts DEFAULT = new ZigBuildOpts(null, true, false, false, false);

  /// Codegen test-harness configuration: fast self-hosted Debug builds, with the
  /// trace stack on because the harness asserts on Fearless stack traces.
  public static ZigBuildOpts forTests(Integer tokensThreshold) {
    return new ZigBuildOpts(tokensThreshold, true, true, false, true);
  }

  public String optimizeMode() {
    if (fastTestBuild) { return "Debug"; }
    return debug ? "ReleaseSafe" : "ReleaseFast";
  }
  /// The self-hosted backend is only viable on Linux. On aarch64-macOS it balloons past
  /// 100GB of RSS and gets killed, so LLVM stays on there even for test builds.
  private static final boolean SELF_HOSTED_USABLE =
    System.getProperty("os.name").toLowerCase().contains("linux");

  public boolean useLlvm() { return !fastTestBuild || !SELF_HOSTED_USABLE; }
  public boolean traceFrames() { return stackTraces || debug; }
  public boolean logTrace() { return debug; }
}
