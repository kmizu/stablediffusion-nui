package sdfx.engine

import java.io.OutputStream
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import sdfx.core.modelcards.LocalPreviewFiles
import scala.jdk.CollectionConverters.*

/** Read-only snapshot of one flat image folder. No output is published by this component. */
private[engine] object Img2ImgFolderManifest:
  val MaxEntries = 20000
  val MaxFiles = 1000
  val MaxFileBytes = 128L * 1024 * 1024
  val MaxTotalBytes = 2L * 1024 * 1024 * 1024
  private val extensions = Set("png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff")
  private val noFollow = LinkOption.NOFOLLOW_LINKS
  final case class Item(path: Path, rootKey: String, size: Long, modified: java.nio.file.attribute.FileTime, key: String, sha256: Vector[Byte])

  private def attrs(path: Path): BasicFileAttributes =
    Files.readAttributes(path, classOf[BasicFileAttributes], noFollow)

  private def identity(path: Path, expected: BasicFileAttributes): String =
    val stamp = LocalPreviewFiles.systemPlatform.stampForBatch(path).fold(error => throw new IllegalArgumentException(error), value => value)
    require(stamp.reparseTarget.isEmpty && stamp.directory == expected.isDirectory &&
      stamp.regularFile == expected.isRegularFile && stamp.size == expected.size &&
      stamp.modifiedMillis == expected.lastModifiedTime.toMillis, s"Input identity changed: $path")
    stamp.fileKey.filter(_.nonEmpty).getOrElse(throw new IllegalArgumentException(s"Input identity is unavailable: $path"))

  private def validFile(path: Path): (BasicFileAttributes, String) =
    val a = attrs(path)
    require(a.isRegularFile && !a.isSymbolicLink, s"Not a regular image file: $path")
    require(a.size > 0 && a.size <= MaxFileBytes, s"Image file size exceeds limits: $path")
    (a, identity(path, a))

  private def same(value: (BasicFileAttributes, String), item: Item): Boolean =
    val (a, key) = value
    a.isRegularFile && !a.isSymbolicLink && key == item.key &&
      a.size == item.size && a.lastModifiedTime == item.modified

  private def checkRoot(root: Path, key: String): Unit =
    val a = attrs(root)
    require(a.isDirectory && !a.isSymbolicLink && identity(root, a) == key,
      s"Input folder changed during batch: $root")

  private def digestAndCopy(path: Path, output: OutputStream, check: () => Unit, allowedRoots: Seq[Path]): (Long, Vector[Byte]) =
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    val counted = new OutputStream:
      override def write(value: Int): Unit = write(Array(value.toByte), 0, 1)
      override def write(bytes: Array[Byte], offset: Int, n: Int): Unit =
        count += n
        require(count <= MaxFileBytes, s"Image file grew beyond limits: $path")
        digest.update(bytes, offset, n)
        output.write(bytes, offset, n)
    if allowedRoots.nonEmpty then VerifiedFolderInput.copy(path, allowedRoots, counted, check)
    else
      val in = Files.newInputStream(path, StandardOpenOption.READ, noFollow)
      val buffer = new Array[Byte](64 * 1024)
      try
        var n = 0
        while { check(); n = in.read(buffer); n != -1 } do counted.write(buffer, 0, n)
      finally in.close()
    (count, digest.digest().toVector)

  def scan(directory: Path, check: () => Unit = () => (), allowedRoots: Seq[Path] = Nil): Vector[Item] =
    check()
    val input = directory.toAbsolutePath.normalize()
    val rootAttributes = attrs(input)
    require(rootAttributes.isDirectory && !rootAttributes.isSymbolicLink,
      s"Input folder is not an identifiable directory: $input")
    val rootKey = identity(input, rootAttributes)
    val root = input.toRealPath()
    checkRoot(root, rootKey)
    checkRoot(input, rootKey)
    val stream = Files.newDirectoryStream(root)
    val found = Vector.newBuilder[Path]
    var entries = 0
    var files = 0
    try
      stream.iterator().asScala.foreach { path =>
        check()
        entries += 1
        require(entries <= MaxEntries, s"Input folder exceeds $MaxEntries entries")
        val name = path.getFileName.toString
        val ext = name.lastIndexOf('.') match
          case -1 => ""
          case n => name.substring(n + 1).toLowerCase(Locale.ROOT)
        if extensions(ext) then
          validFile(path)
          files += 1
          require(files <= MaxFiles, s"Input folder exceeds $MaxFiles image files")
          found += path
      }
    finally stream.close()
    val paths = found.result().sortBy(p => (p.getFileName.toString.toLowerCase(Locale.ROOT), p.getFileName.toString))
    require(paths.nonEmpty, "Input folder contains no supported images")
    var total = 0L
    paths.map { path =>
      check()
      val (before, fileKey) = validFile(path)
      total = Math.addExact(total, before.size)
      require(total <= MaxTotalBytes, "Input folder exceeds total image byte limit")
      val (length, digest) = digestAndCopy(path, OutputStream.nullOutputStream(), check, allowedRoots)
      val item = Item(path, rootKey, before.size, before.lastModifiedTime, fileKey, digest)
      checkRoot(root, item.rootKey)
      require(length == item.size && same(validFile(path), item), s"Input image changed while scanning: $path")
      item
    }

  /** The caller owns and cleans the destination stream, including on failure. */
  def copyVerified(item: Item, output: OutputStream, check: () => Unit = () => (), allowedRoots: Seq[Path] = Nil): Unit =
    check()
    checkRoot(item.path.getParent, item.rootKey)
    require(same(validFile(item.path), item), s"Input image changed since scanning: ${item.path}")
    val (length, digest) = digestAndCopy(item.path, output, check, allowedRoots)
    checkRoot(item.path.getParent, item.rootKey)
    require(length == item.size && digest == item.sha256 && same(validFile(item.path), item),
      s"Input image changed while reading: ${item.path}")
