package rt;

public final class Clock implements base.caps.Clock_0 {
  public static final Clock $self = new Clock();
  @Override public Long monotonic$mut() { return System.nanoTime(); }
  @Override public Clock iso$mut() { return this; }
  @Override public Clock self$mut() { return this; }
}
