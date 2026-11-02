package sdfx.core.i18n

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Native names of the languages the known packs are written for. Unknown codes are shown as they are. */
object LanguageNames:
  private val names = Map(
    "en" -> "English", "ja" -> "日本語", "ko" -> "한국어", "zh" -> "中文", "de" -> "Deutsch", "es" -> "Español", "fr" -> "Français",
    "it" -> "Italiano", "pt" -> "Português", "ru" -> "Русский", "tr" -> "Türkçe", "fi" -> "Suomi", "no" -> "Norsk", "th" -> "ไทย",
    "ua" -> "Українська", "uk" -> "Українська", "ar" -> "العربية", "ks" -> "関西弁 (ks_JP)")
  private val regional = Map(
    "zh_CN" -> "简体中文", "zh_Hans" -> "简体中文", "zh-Hans" -> "简体中文", "zh_TW" -> "繁體中文", "zh_Hant" -> "繁體中文", "zh-Hant" -> "繁體中文",
    "pt_BR" -> "Português (Brasil)")

  /** `ja_JP (Stable)` -> `日本語`; the tail of the file name after the first space is not part of the code. */
  def of(codeOrStem: String): String =
    val code = codeOrStem.trim.takeWhile(c => c != ' ' && c != '(' && c != '[').replace('-', '_')
    regional.get(code).orElse(regional.get(codeOrStem.trim.takeWhile(_ != ' '))).orElse(names.get(code.takeWhile(_ != '_').toLowerCase)).getOrElse(code)

enum PackOrigin:
  case Bundled, File

/** One selectable language pack. `id` is what Settings stores. */
final case class PackInfo(id: String, language: String, label: String, origin: PackOrigin, location: String, bytes: Long):
  def display: String = origin match
    case PackOrigin.Bundled => s"$label ($language, built in)"
    case PackOrigin.File    => s"$label ($language) - ${Path.of(location).getFileName}"

/** Finds language packs and loads them.
  *
  * Folders searched, later ones replace earlier ones with the same file name (as in the A1111 `localizations` handling):
  * `<app>/localizations`, `<app>/plugins-ext/NAME/localizations`, `<app>/extensions/NAME/localizations` and every folder
  * the user lists (the folder itself, its `localizations` sub-folder, or an extension folder that contains one).
  * Packs inside the application (`builtin/...`) are listed first.
  */
final class PackCatalog(home: Path, extraDirs: () => Seq[Path] = () => Nil, bundledStems: () => Seq[String] = () => PackCatalog.bundledStems()):
  private val cache = mutable.HashMap.empty[String, (Long, Long, LocalizationPack)]

  def userDir: Path = home.resolve("localizations")

  def searchDirs(): Vector[Path] =
    def children(parent: Path): Vector[Path] =
      if !Files.isDirectory(parent) then Vector.empty
      else
        val s = Files.list(parent)
        try s.iterator().asScala.filter(Files.isDirectory(_)).toVector.sortBy(_.getFileName.toString.toLowerCase) finally s.close()
    val base = Vector(userDir) ++ children(home.resolve("plugins-ext")).map(_.resolve("localizations")) ++
      children(home.resolve("extensions")).map(_.resolve("localizations"))
    val extra = extraDirs().toVector.flatMap { d =>
      if Files.isDirectory(d.resolve("localizations")) then Vector(d.resolve("localizations"))
      else Vector(d)
    }
    (base ++ extra).filter(Files.isDirectory(_)).distinct

  def list(): Vector[PackInfo] =
    val bundled = bundledStems().distinct.sorted.map { stem =>
      PackInfo(PackCatalog.BundledPrefix + stem, stem, LanguageNames.of(stem), PackOrigin.Bundled, "/" + PackCatalog.ResourceDir + "/" + stem + ".json", 0)
    }.toVector
    val files = mutable.LinkedHashMap.empty[String, PackInfo]
    for dir <- searchDirs(); p <- jsonFiles(dir) do
      val stem = p.getFileName.toString.dropRight(5)
      files(stem) = PackInfo(stem, stem, LanguageNames.of(stem), PackOrigin.File, p.toString, try Files.size(p) catch case NonFatal(_) => 0L)
    bundled ++ files.values.toVector.sortBy(i => (i.label.toLowerCase, i.id.toLowerCase))

  private def jsonFiles(dir: Path): Vector[Path] =
    try
      val stream = Files.list(dir)
      try stream.iterator().asScala.filter(p => Files.isRegularFile(p) && p.getFileName.toString.toLowerCase.endsWith(".json")).toVector
        .sortBy(_.getFileName.toString.toLowerCase)
      finally stream.close()
    catch case NonFatal(_) => Vector.empty

  def find(id: String): Option[PackInfo] = list().find(_.id == id)

  /** Loads (and caches) a pack by id. */
  def load(id: String): Either[String, LocalizationPack] =
    if id.trim.isEmpty then Right(LocalizationPack.Empty)
    else find(id) match
      case None => Left(s"language pack '$id' was not found")
      case Some(info) =>
        info.origin match
          case PackOrigin.Bundled => PackCatalog.loadBundled(info.language)
          case PackOrigin.File =>
            val path = Path.of(info.location)
            try
              val mod = Files.getLastModifiedTime(path).toMillis
              val size = Files.size(path)
              cache.get(info.location) match
                case Some((m, s, pack)) if m == mod && s == size && pack.id == id => Right(pack)
                case _ =>
                  if size > LocalizationPack.MaxFileBytes then Left(s"${path.getFileName} is larger than ${LocalizationPack.MaxFileBytes / 1024 / 1024} MiB")
                  else
                    LocalizationPack.parse(id, new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).map { pack =>
                      cache(info.location) = (mod, size, pack)
                      pack
                    }.left.map(e => s"${path.getFileName}: $e")
            catch case NonFatal(e) => Left(s"${path.getFileName}: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")

  /** Primary pack, then the optional fallback. Packs that cannot be loaded are reported and skipped. */
  def resolve(primary: String, fallback: String): (Translations, Vector[String]) =
    val problems = Vector.newBuilder[String]
    val loaded = Seq(primary, fallback).map(_.trim).filter(_.nonEmpty).distinct.flatMap { id =>
      load(id) match
        case Right(p) => Some(p)
        case Left(e)  => problems += e; None
    }
    (new LayeredTranslations(loaded), problems.result())

  /** Copies a pack file into the user folder after checking that it parses. Never overwrites: a taken name gets ` (2)`, ` (3)`... */
  def importFile(source: Path): Either[String, PackInfo] =
    try
      if !Files.isRegularFile(source) then return Left("not a file")
      if Files.size(source) > LocalizationPack.MaxFileBytes then return Left("file is too large for a language pack")
      val bytes = Files.readAllBytes(source)
      val stem0 = source.getFileName.toString.replaceAll("(?i)\\.json$", "").replaceAll("[\\\\/:*?\"<>|]", "_")
      LocalizationPack.parse(stem0, new String(bytes, StandardCharsets.UTF_8)).flatMap { pack =>
        if pack.size == 0 then Left("the file has no usable translations")
        else
          Files.createDirectories(userDir)
          var stem = stem0
          var n = 1
          while Files.exists(userDir.resolve(stem + ".json")) do { n += 1; stem = s"$stem0 ($n)" }
          val dest = userDir.resolve(stem + ".json")
          Files.write(dest, bytes)
          Right(PackInfo(stem, stem, LanguageNames.of(stem), PackOrigin.File, dest.toString, bytes.length.toLong))
      }
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

object PackCatalog:
  val ResourceDir = "sdfx/localizations"
  val BundledPrefix = "builtin/"

  /** Names listed in `sdfx/localizations/index.txt` (one per line). */
  def bundledStems(): Seq[String] =
    readResource("index.txt").map(_.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toSeq).getOrElse(Nil)

  def loadBundled(stem: String): Either[String, LocalizationPack] =
    readResource(stem + ".json") match
      case None => Left(s"built-in language pack '$stem' is missing")
      case Some(text) => LocalizationPack.parse(BundledPrefix + stem, text)

  private[i18n] def readResource(name: String): Option[String] =
    val stream = getClass.getClassLoader.getResourceAsStream(ResourceDir + "/" + name)
    if stream == null then None
    else try Some(new String(stream.readAllBytes(), StandardCharsets.UTF_8)) finally stream.close()
