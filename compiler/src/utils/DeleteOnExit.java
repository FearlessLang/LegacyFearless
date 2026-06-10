package utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public interface DeleteOnExit {
  static void of(Path dir) {
    Runtime.getRuntime().addShutdownHook(new Thread(()->{
      if (!Files.exists(dir)) { return; }
      try(var tree = IoErr.of(()->Files.walk(dir))) {
        tree.map(Path::toFile).forEach(ThrowingConsumer.of(File::deleteOnExit));
      }
    }));
  }
}
