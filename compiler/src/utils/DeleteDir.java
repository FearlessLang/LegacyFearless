package utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public interface DeleteDir {
  static void of(Path root) {
    if (!Files.exists(root)) { return; }
    IoErr.of(()->{
      try (var walk = Files.walk(root)) {
        walk.sorted(Comparator.reverseOrder())
          .forEach(f->IoErr.of(()->Files.deleteIfExists(f)));
      }
    });
  }
}
