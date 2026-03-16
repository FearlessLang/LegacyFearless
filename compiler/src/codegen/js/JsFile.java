package codegen.js;

import java.nio.file.Path;

public record JsFile(Path path, String code) {
  public String getName() {
    return path.getFileName().toString();
  }

  public Object toUri() {
    return path.toUri();
  }
}