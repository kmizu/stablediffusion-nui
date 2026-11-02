package sdfx.core.i18n

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.Duration
import scala.util.control.NonFatal

/** One upstream language pack file, pinned to a commit and a SHA-256 (see scripts/localization_store.py). */
final case class StorePack(id: String, language: String, index: Int, repo: String, commit: String, path: String, size: Long,
    sha256: String, entries: Int, license: String, licensePath: Option[String], licenseSha256: Option[String]):
  def rawUrl: String = PackStore.rawUrl(repo, commit, path)
  def licenseUrl: Option[String] = licensePath.map(PackStore.rawUrl(repo, commit, _))
  def display: String = s"${LanguageNames.of(language)} ($id) - $entries entries, licence: $license"

/** The pinned catalogue of the upstream packs and the installer. Nothing is bundled: a pack is downloaded on request,
  * from the pinned commit, verified against the recorded hash and saved in the user's `localizations` folder next to
  * a `.source.txt` (origin, commit, licence) and, when the repository has one, its licence text.
  */
object PackStore:
  def rawUrl(repo: String, commit: String, path: String): String =
    "https://raw.githubusercontent.com/" + repo + "/" + commit + "/" + path.split("/").map(encode).mkString("/")

  private def encode(segment: String): String =
    java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

  def catalog(): Vector[StorePack] =
    PackCatalog.readResource("store.json") match
      case None => Vector.empty
      case Some(text) => parseCatalog(text)

  def parseCatalog(text: String): Vector[StorePack] =
    ujson.read(text).arr.toVector.map { o =>
      StorePack(o("id").str, o("language").str, o("index").num.toInt, o("repo").str, o("commit").str, o("path").str, o("size").num.toLong,
        o("sha256").str, o("entries").num.toInt, o("license").str, o("licensePath").strOpt, o("licenseSha256").strOpt)
    }

  def stemOf(file: Path): String = file.getFileName.toString.replaceAll("(?i)[.]json$", "")

  def sha256(bytes: Array[Byte]): String = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** Plain HTTPS GET with redirects and a size cap. */
  def httpFetch(url: String): Array[Byte] =
    require(url.startsWith("https://raw.githubusercontent.com/"), "only raw.githubusercontent.com is allowed")
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    val response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
      HttpResponse.BodyHandlers.ofInputStream())
    val in = response.body()
    try
      require(response.statusCode() == 200, s"HTTP ${response.statusCode()} for $url")
      val bytes = in.readNBytes(LocalizationPack.MaxFileBytes.toInt + 1)
      require(bytes.length <= LocalizationPack.MaxFileBytes, "download is larger than the pack size limit")
      bytes
    finally in.close()

  /** Downloads and verifies one pack. Returns the installed file. An existing file with the same name is kept when `overwrite` is false. */
  def install(pack: StorePack, destDir: Path, fetch: String => Array[Byte] = httpFetch, overwrite: Boolean = false): Either[String, Path] =
    try
      val safe = pack.id.replaceAll("[\\\\/:*?\"<>|]", "_")
      val target = destDir.resolve(safe + ".json")
      if Files.exists(target) && !overwrite then return Left(s"${target.getFileName} already exists")
      val bytes = fetch(pack.rawUrl)
      if bytes.length.toLong != pack.size then return Left(s"size mismatch for ${pack.path}: expected ${pack.size}, got ${bytes.length}")
      if sha256(bytes) != pack.sha256 then return Left(s"SHA-256 mismatch for ${pack.path}; the download was not saved")
      LocalizationPack.parse(pack.id, new String(bytes, StandardCharsets.UTF_8)) match
        case Left(e) => return Left(s"downloaded file is not a usable pack: $e")
        case Right(_) => ()
      val licenseBytes = pack.licenseUrl.map { u =>
        val b = fetch(u)
        pack.licenseSha256.foreach(h => require(sha256(b) == h, "licence text SHA-256 mismatch"))
        b
      }
      Files.createDirectories(destDir)
      licenseBytes.foreach(b => Files.write(destDir.resolve(safe + ".LICENSE.txt"), b))
      val note = s"Language pack ${pack.id}\nsource: https://github.com/${pack.repo}/blob/${pack.commit}/${pack.path}\ncommit: ${pack.commit}\n" +
        s"sha256: ${pack.sha256}\nlicence: ${pack.license}\n" + (if licenseBytes.isEmpty then "No licence file in the repository; downloaded for your own use, not redistributed.\n" else "")
      Files.writeString(destDir.resolve(safe + ".source.txt"), note, StandardCharsets.UTF_8)
      val tmp = Files.createTempFile(destDir, safe, ".part")
      try
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
      finally Files.deleteIfExists(tmp)
      Right(target)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
