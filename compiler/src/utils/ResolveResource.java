package utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
This file will have a compile time error on the first git checkout.
This project needs to know how to locate some resources on your machine.
You need to add a file LocalResources.java (that is already in the gitignore)
following the template LocalResourcesTemplate.java
*/
public record ResolveResource(Path assetRoot, Path artefactRoot, Optional<Path> testsRoot, FileSystem virtualFs) {
  static private final ResolveResource instance= ResolveResource.infer(LocalResources.compilerPath);
  static public final String javaVersion= LocalResources.javaVersion;

  public ResolveResource{
    assert Files.exists(assetRoot):assetRoot;
    assert Files.exists(artefactRoot):artefactRoot;
  }

  static ResolveResource infer(Path start) {
    var url= ResolveResource.class.getResource("/base");
    var testResourceUrl= Optional.ofNullable(ResolveResource.class.getResource("/.compiler-tests"));
    if(url==null) {
      // We're running with a working dir of the Fearless Compiler project, likely in something like Eclipse.
      var root = start.toAbsolutePath().getParent();
      return new ResolveResource(
        root.resolve("assets"),
        root.resolve("artefacts"),
        Optional.of(root.resolve("compiler-tests")),
        null
      );
    }

    URI resourcesUri; try { resourcesUri= url.toURI();}
    catch (URISyntaxException e) { throw Bug.of(e); }
    Optional<URI> testResourceUri= testResourceUrl.map(testResourceUrl_ -> {
      try {
        return testResourceUrl_.toURI();
      } catch (URISyntaxException e) {
        throw Bug.of(e);
      }
    });

    var inBundle = resourcesUri.getScheme().equals("jar") || resourcesUri.getScheme().equals("resource");
    if (!inBundle){
      // We're running in an IDE/build tool that uses an output/build dir as the working dir. Likely IntelliJ or Maven.
      var resourcesRoot= Path.of(resourcesUri).getParent();
      var testResourcesRoot= testResourceUri.map(uri->Path.of(uri).getParent());
      return new ResolveResource(resourcesRoot, resourcesRoot, testResourcesRoot, null);
    }
    // We're in a JAR or AOT Application Image.
    return IoErr.of(()->{
      var virtualFs= FileSystems.newFileSystem(resourcesUri, Map.of());
      var both= virtualFs.getPath("/");
      return new ResolveResource(both, both, null, virtualFs);
    });
  }
  static public Path asset(String relativePath) {
    return of(instance.assetRoot, relativePath);
  }
  static public Path artefact(String relativePath) {
    return of(instance.artefactRoot, relativePath);
  }
  static public Path test(String relativePath) {
    return of(instance.testsRoot.orElseThrow(), relativePath);
  }
  static private Path of(Path root, String relativePath) {
    assert relativePath.startsWith("/");
    if (instance.virtualFs != null) {
      return instance.virtualFs.getPath(relativePath);
    }
    URI absolutePath = root.resolve(relativePath.substring(1)).toUri();
    return Path.of(absolutePath);
  }

  static public String getAndReadAsset(String path) {
    return read(asset(path));
  }

  static public String read(Path path) {
    return IoErr.of(()->Files.readString(path, StandardCharsets.UTF_8));
  }
  // Tmp dirs are swept eagerly via cleanTmpPaths() because long-lived JVMs (reused surefire
  // forks) would otherwise accumulate one zig-build tree per compiled program until exit;
  // DeleteOnExit only covers the dirs still alive when the JVM shuts down.
  private static final Queue<Path> TMP_PATHS = new ConcurrentLinkedQueue<>();
  static public Path freshTmpPath(){
    /*var res= Paths.get(
      System.getProperty("java.io.tmpdir"),
      "fearOut"+UUID.randomUUID());*/
    var res = ResolveResource.artefact("/.tmp")
      .resolve("fearOut"+UUID.randomUUID());
    IoErr.of(()->Files.createDirectories(res));
    TMP_PATHS.add(res);
    DeleteOnExit.of(res);
    return res;
  }
  /// Deletes every tmp dir handed out by freshTmpPath so far. Only safe once their
  /// compilations have finished; tests call this via Main.resetAll().
  static public void cleanTmpPaths(){
    for (Path p; (p = TMP_PATHS.poll()) != null;) { DeleteDir.of(p); }
  }
}
