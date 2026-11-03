package sdfx.core.panorama

import sdfx.core.png.PngMetadata

import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters.*

/** File-side helpers of the Panorama tab: stepping through a folder and writing converted pictures. */
object PanoramaFiles:
  val Extensions: Seq[String] = Seq("png", "jpg", "jpeg", "webp", "bmp")
  /** Largest side accepted; 16384 x 8192 as ARGB is already half a gigabyte of pixels. */
  val MaxWidth = 16384
  val MaxPixels = 16384L * 8192L

  def isImage(p: Path): Boolean =
    val n = p.getFileName.toString.toLowerCase
    Extensions.exists(e => n.endsWith("." + e))

  /** Images next to `path` in natural name order (so image-2 comes before image-10). */
  def siblings(path: Path): Vector[Path] =
    val dir = path.toAbsolutePath.normalize.getParent
    if dir == null || !Files.isDirectory(dir) then Vector(path)
    else
      val s = Files.list(dir)
      try s.iterator().asScala.filter(p => Files.isRegularFile(p) && isImage(p)).toVector.sortBy(p => naturalKey(p.getFileName.toString))(using keyOrdering)
      finally s.close()

  private type Key = Vector[Either[Long, String]]
  private def naturalKey(name: String): Key =
    "\\d+|\\D+".r.findAllIn(name.toLowerCase).map(s => if s.head.isDigit && s.length <= 18 then Left(s.toLong) else Right(s)).toVector
  private val keyOrdering: Ordering[Key] = (a: Key, b: Key) =>
    a.zip(b).iterator.map {
      case (Left(x), Left(y)) => java.lang.Long.compare(x, y)
      case (Right(x), Right(y)) => x.compareTo(y)
      case (Left(_), Right(_)) => -1
      case (Right(_), Left(_)) => 1
    }.find(_ != 0).getOrElse(Integer.compare(a.length, b.length))

  /** The picture `step` places after `path` in its folder, wrapping around; None when the folder holds nothing else. */
  def neighbour(path: Path, step: Int): Option[Path] =
    val all = siblings(path)
    val here = all.indexWhere(p => p.toAbsolutePath.normalize == path.toAbsolutePath.normalize)
    if all.size < 2 || here < 0 then None
    else Some(all(((here + step) % all.size + all.size) % all.size))

  /** A name for a converted picture that does not exist yet: `<stem>-<tag>.png`, then `-2`, `-3` and so on. */
  def uniqueTarget(dir: Path, sourceName: String, tag: String): Path =
    val stem = sourceName.lastIndexOf('.') match
      case i if i > 0 => sourceName.substring(0, i)
      case _ => sourceName
    Iterator.from(1).map(i => dir.resolve(if i == 1 then s"$stem-$tag.png" else s"$stem-$tag-$i.png")).find(!Files.exists(_)).get

  /** Writes `raster` as PNG carrying the generation parameters of `from`, so a converted picture can still be restored.
    * With a `password` the pixels are scrambled like every other image saved during a protected session.
    */
  def savePng(raster: Raster, target: Path, from: Option[Path], password: Option[String] = None): Unit =
    Files.createDirectories(target.toAbsolutePath.getParent)
    val parameters = from.flatMap(p => try PngMetadata.readParameters(p) catch case _: Exception => None)
    sdfx.core.png.ScrambledPng.write(raster.toBufferedImage, target, parameters.map(PngMetadata.ParametersKey -> _).toSeq, password)

  /** Publish completed staging bytes without replacing any existing entry. */
  private[panorama] def publishNew(stage: Path, target: Path,
      link: (Path, Path) => Unit = (dest, src) => { Files.createLink(dest, src); () },
      open: Path => java.io.OutputStream = p => Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)): Unit =
    try link(target, stage)
    catch
      case exists: FileAlreadyExistsException => throw exists
      case NonFatal(_) =>
        val dest = open(target)
        try Files.copy(stage, dest)
        catch
          case error: Throwable =>
            try dest.close() catch case close: Throwable => error.addSuppressed(close)
            if NonFatal(error) then throw new java.io.IOException(s"PNG copy failed; an incomplete file may remain at $target", error)
            else throw error
        try dest.close() catch
          case NonFatal(error) => throw new java.io.IOException(s"PNG copy failed; an incomplete file may remain at $target", error)

  /** Encode privately, then publish without replacing the target, including on hardlink-less filesystems. */
  def savePngNew(raster: Raster, target: Path, from: Option[Path], password: Option[String] = None): Unit =
    val parameters = from.flatMap(p => try PngMetadata.readParameters(p) catch case _: Exception => None)
    savePngWithParametersNew(raster, target, parameters, password)

  /** Same non-overwriting publication using parameters captured with a source snapshot. */
  def savePngWithParametersNew(raster: Raster, target: Path, parameters: Option[String], password: Option[String] = None): Unit =
    val parent = target.toAbsolutePath.normalize.getParent
    Files.createDirectories(parent)
    val stage = Files.createTempFile(parent, ".panorama-", ".png.part")
    var failure: Throwable = null
    try
      sdfx.core.png.ScrambledPng.write(raster.toBufferedImage, stage, parameters.map(PngMetadata.ParametersKey -> _).toSeq, password)
      publishNew(stage, target)
    catch
      case e: Throwable => failure = e; throw e
    finally
      try Files.deleteIfExists(stage)
      catch case NonFatal(cleanup) =>
        if failure != null then failure.addSuppressed(cleanup)
        else System.err.println("[sdfx] panorama staging cleanup failed: " + cleanup)

  /** A cube net needs a 4:3 picture of square faces. */
  def isCubeNet(width: Int, height: Int): Boolean = width % 4 == 0 && height % 3 == 0 && width / 4 == height / 3

  /** Accepts a decoded picture only when its size is something the viewer can hold. */
  def checkSize(width: Int, height: Int): Either[String, Unit] =
    if width < 2 || height < 2 then Left("The picture is too small to view")
    else if width > MaxWidth || width.toLong * height > MaxPixels then Left(s"${width}x$height is larger than the ${MaxWidth}x${MaxPixels / MaxWidth} the panorama viewer holds")
    else Right(())
