package main;

import java.util.List;
import java.util.Optional;

/// A set of packages compiled together and cached together.
///
/// A unit is compiled on its own, and what it writes is read by every later compilation: its
/// package type information, its implementation information and its generated code. Because the
/// unit that is cached is the unit that was compiled, an analysis over a unit stays true of the
/// text that unit wrote, and a pass needs no knowledge of the cache to be sound.
///
/// A package that belongs to no unit is compiled with the program that names it, and everything
/// computed about it lives and dies with that compilation.
///
/// A pattern is a package name, or a name and `.*` for that package and everything under it.
public record CompilationUnit(String name, List<String> patterns) {
  /// Every unit, ordered so that a unit may read only the units before it.
  ///
  /// This is the one place that decides what a unit is. A command line flag or a project file
  /// would feed this method, and no pass would change.
  private static final List<CompilationUnit> ALL =
    List.of(new CompilationUnit("base", List.of("base", "base.*")));

  public static List<CompilationUnit> all() { return ALL; }

  /// The unit that holds `pkg`, when one does.
  public static Optional<CompilationUnit> of(String pkg) {
    return ALL.stream().filter(unit -> unit.contains(pkg)).findFirst();
  }

  /// Whether `pkg` belongs to a unit, and so whether the code generated for it is written once
  /// and read by later compilations. A whole-program answer must not be written into such text
  /// unless the declaration itself guarantees it.
  public static boolean isCached(String pkg) { return of(pkg).isPresent(); }

  /// The units this one may read, which are the ones declared before it.
  public List<CompilationUnit> dependencies() { return ALL.subList(0, ALL.indexOf(this)); }

  /// Whether any unit of `units` holds `pkg`.
  public static boolean anyContains(List<CompilationUnit> units, String pkg) {
    return units.stream().anyMatch(unit -> unit.contains(pkg));
  }

  public boolean contains(String pkg) {
    return patterns.stream().anyMatch(pattern -> matches(pattern, pkg));
  }

  private static boolean matches(String pattern, String pkg) {
    if (!pattern.endsWith(".*")) { return pattern.equals(pkg); }
    return pkg.startsWith(pattern.substring(0, pattern.length() - 1));
  }
}
