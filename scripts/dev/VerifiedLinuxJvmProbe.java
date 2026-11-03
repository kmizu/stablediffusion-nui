import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import scala.runtime.AbstractFunction0;
import scala.runtime.AbstractFunction1;
import scala.runtime.BoxedUnit;
import sdfx.engine.VerifiedFolderInput$;
import sdfx.mcp.Images$;
import sdfx.mcp.PathPolicy;

/** Run against the assembled JAR on a real Linux filesystem. */
public final class VerifiedLinuxJvmProbe {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("mcp-verified-jvm-").toRealPath();
    Path directory = Files.createDirectory(root.resolve("images"));
    Path outside = Files.createTempDirectory("mcp-outside-jvm-").toRealPath();
    Files.writeString(directory.resolve("in.png"), "inside");
    Files.writeString(outside.resolve("in.png"), "outside");
    var roots = scala.jdk.javaapi.CollectionConverters.asScala(List.of(root)).toList();
    var noop = VerifiedFolderInput$.MODULE$.copy$default$4();
    var afterOpen = new AbstractFunction0<BoxedUnit>() {
      @Override public BoxedUnit apply() {
        try {
          Files.move(directory, root.resolve("moved"));
          Files.createSymbolicLink(directory, outside);
          return BoxedUnit.UNIT;
        } catch (Exception e) { throw new RuntimeException(e); }
      }
    };
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    VerifiedFolderInput$.MODULE$.copy(directory.resolve("in.png"), roots, bytes, noop, afterOpen, "Linux", 48);
    if (!bytes.toString(StandardCharsets.UTF_8).equals("inside")) throw new AssertionError("opened file changed");
    try {
      VerifiedFolderInput$.MODULE$.copy(directory.resolve("in.png"), roots, new ByteArrayOutputStream(), noop, noop, "Linux", 48);
      throw new AssertionError("symlink accepted");
    } catch (IllegalArgumentException expected) { }
    Files.delete(directory);
    Files.createDirectory(directory);
    Process fifo = new ProcessBuilder("/usr/bin/mkfifo", directory.resolve("in.png").toString()).start();
    if (fifo.waitFor() != 0) throw new AssertionError("mkfifo failed");
    try {
      VerifiedFolderInput$.MODULE$.copy(directory.resolve("in.png"), roots, new ByteArrayOutputStream(), noop, noop, "Linux", 48);
      throw new AssertionError("FIFO accepted");
    } catch (IllegalArgumentException expected) { }
    Path png = root.resolve("ordinary.png");
    var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
    if (!javax.imageio.ImageIO.write(image, "png", png.toFile())) throw new AssertionError("PNG writer missing");
    var allowed = new AbstractFunction0<scala.collection.immutable.Seq<Path>>() {
      @Override public scala.collection.immutable.Seq<Path> apply() { return roots; }
    };
    var temp = new AbstractFunction1<String, Path>() {
      @Override public Path apply(String suffix) {
        try { return Files.createTempFile(root, "snapshot-", suffix); }
        catch (Exception e) { throw new RuntimeException(e); }
      }
    };
    var result = Images$.MODULE$.materialize(png.toString(), "init_image", new PathPolicy(allowed), root, temp, "Linux");
    if (!java.util.Arrays.equals(Files.readAllBytes(png), Files.readAllBytes(result._1())))
      throw new AssertionError("materialized bytes differ");
    System.out.println("VERIFIED_LINUX_JVM_OK");
  }
}
