package sdfx.mcp;
import java.nio.file.*;
import scala.runtime.AbstractFunction0;
import scala.runtime.AbstractFunction1;
import scala.runtime.BoxedUnit;

public class LinuxVaeRaceCheck {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("vae-linux-race-");
    Path inside = Files.createDirectory(root.resolve("inside"));
    Path outside = Files.createTempDirectory("vae-linux-outside-");
    Path input = Files.write(inside.resolve("input.safetensors"), new byte[]{1,2,3});
    Files.write(outside.resolve("input.safetensors"), new byte[]{9,8,7});
    PathPolicy policy = new PathPolicy(new AbstractFunction0<scala.collection.immutable.Seq<Path>>() {
      public scala.collection.immutable.Seq<Path> apply() { return scala.collection.immutable.List$.MODULE$.<Path>empty().$colon$colon(root); }
    });
    try {
      VaeInputSnapshot$.MODULE$.capture(input.toString(), "input_paths", false, policy, root,
        new AbstractFunction1<String,Path>() { public Path apply(String suffix) { try { return Files.createTempFile(root, "stage-", suffix); } catch(Exception e) { throw new RuntimeException(e); } } },
        new AbstractFunction0<BoxedUnit>() { public BoxedUnit apply() { try { Files.move(inside, root.resolve("moved")); Files.createSymbolicLink(inside, outside); return BoxedUnit.UNIT; } catch(Exception e) { throw new RuntimeException(e); } } });
      throw new AssertionError("outside replacement was accepted");
    } catch (Exception expected) {
      if (!(expected instanceof ToolError) || !expected.getMessage().contains("verified")) throw expected;
      System.out.println("LINUX_VAE_PARENT_SWAP_REJECTED " + root);
    }
  }
}
