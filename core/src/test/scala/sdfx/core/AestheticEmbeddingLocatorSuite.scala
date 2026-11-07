package sdfx.core

import sdfx.core.aesthetic.{AestheticEmbeddingDirectories, AestheticEmbeddingLocator}
import java.nio.file.{Files, Path}

class AestheticEmbeddingLocatorSuite extends munit.FunSuite:
  test("resolve only a unique authorized stem and verify an optional content fingerprint") {
    val root = Files.createTempDirectory("aesthetic-locator-")
    val first = Files.createDirectory(root.resolve("first"))
    val second = Files.createDirectory(root.resolve("second"))
    val a = Files.writeString(first.resolve("style.pt"), "first")
    val b = Files.writeString(second.resolve("style.safetensors"), "second")
    val digest = AestheticEmbeddingLocator.fingerprint(a)
    val allow: Path => Boolean = p => p.startsWith(root.toAbsolutePath.normalize()) || p.startsWith(root.toRealPath())
    assert(AestheticEmbeddingLocator.resolve("style", None, Seq(first, second), allow).isLeft)
    assertEquals(AestheticEmbeddingLocator.resolve("style.pt", None, Seq(first, second), allow), Right(a.toRealPath()))
    assertEquals(AestheticEmbeddingLocator.resolve("style", Some(digest), Seq(first, second), allow), Right(a.toRealPath()))
    assert(AestheticEmbeddingLocator.resolve("style", Some("0" * 64), Seq(first, second), allow).isLeft)
    assert(AestheticEmbeddingLocator.resolve("style", Some(digest), Seq(first, second), _ => false).isLeft)
    Files.writeString(a, "changed")
    assert(AestheticEmbeddingLocator.resolve("style", Some(digest), Seq(first, second), allow).isLeft)
    assertEquals(AestheticEmbeddingLocator.resolve("style", None, Seq(second), allow), Right(b.toRealPath()))
  }

  test("reject path-bearing names, invalid fingerprints, and escaping symlinks") {
    val root = Files.createTempDirectory("aesthetic-locator-root-")
    val outside = Files.createTempFile("aesthetic-locator-outside-", ".pt")
    val allow: Path => Boolean = p => p.startsWith(root.toAbsolutePath.normalize()) || p.startsWith(root.toRealPath())
    assert(AestheticEmbeddingLocator.resolve("../secret", None, Seq(root), allow).isLeft)
    assert(AestheticEmbeddingLocator.resolve("style", Some("bad"), Seq(root), allow).isLeft)
    try
      Files.createSymbolicLink(root.resolve("style.pt"), outside)
      assert(AestheticEmbeddingLocator.resolve("style", None, Seq(root), allow).isLeft)
    catch case _: UnsupportedOperationException | _: java.nio.file.FileSystemException | _: SecurityException => ()
  }

  test("explicit authorized directories persist, but are reauthorized on every read") {
    val home = Files.createTempDirectory("aesthetic-registry-")
    val allowed = Files.createDirectory(home.resolve("allowed"))
    val denied = Files.createDirectory(home.resolve("denied"))
    val registry = home.resolve("config/aesthetic-directories.json")
    val authorize: Path => Boolean = p => p == allowed.toAbsolutePath.normalize() || p == allowed.toRealPath()
    assert(AestheticEmbeddingDirectories.register(registry, allowed, authorize).isRight)
    assert(AestheticEmbeddingDirectories.register(registry, denied, authorize).isLeft)
    assertEquals(AestheticEmbeddingDirectories.read(registry, authorize), Right(Vector(allowed.toRealPath())))
    assertEquals(AestheticEmbeddingDirectories.read(registry, _ => false), Right(Vector.empty))
    Files.writeString(registry, "not valid json")
    assert(AestheticEmbeddingDirectories.register(registry, allowed, authorize).isLeft)
    assertEquals(Files.readString(registry), "not valid json")
  }

  test("directory registration respects an exclusive lock held by another process") {
    val root = Files.createTempDirectory("aesthetic-registry-lock-")
    val directory = Files.createDirectory(root.resolve("images"))
    val registry = root.resolve("index.json")
    val lockPath = root.resolve("index.json.lock")
    val ready = root.resolve("ready")
    val done = root.resolve("done")
    val loaders = Iterator.iterate(getClass.getClassLoader)(_.getParent).takeWhile(_ != null).toVector
    val urls = loaders.collect { case u: java.net.URLClassLoader => u.getURLs.toSeq }.flatten.map(u => Path.of(u.toURI).toString)
    val cp = (urls ++ System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct.mkString(java.io.File.pathSeparator)
    val javaBin = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString
    val channel = java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
    val lock = channel.lock()
    val process = new ProcessBuilder(javaBin, "-cp", cp, "sdfx.core.AestheticRegistryChild", registry.toString,
      directory.toString, ready.toString, done.toString).redirectErrorStream(true).start()
    try
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
      while !Files.exists(ready) && process.isAlive && System.nanoTime() < deadline do Thread.sleep(20)
      assert(Files.exists(ready), "child never reached registration")
      Thread.sleep(300)
      assert(!Files.exists(done), "registration ignored a lock held by another process")
      lock.release()
      assert(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "child did not finish")
      assertEquals(process.exitValue(), 0, new String(process.getInputStream.readAllBytes()))
      assert(Files.exists(done))
      assertEquals(AestheticEmbeddingDirectories.read(registry, _ => true), Right(Vector(directory.toRealPath())))
    finally
      if lock.isValid then lock.release()
      channel.close()
      if process.isAlive then process.destroyForcibly()
  }

object AestheticRegistryChild:
  def main(args: Array[String]): Unit =
    Files.writeString(Path.of(args(2)), "ready")
    val result = AestheticEmbeddingDirectories.register(Path.of(args(0)), Path.of(args(1)), _ => true)
    require(result.isRight, result.toString)
    Files.writeString(Path.of(args(3)), "done")
