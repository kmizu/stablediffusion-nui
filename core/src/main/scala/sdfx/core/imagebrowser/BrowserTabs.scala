package sdfx.core.imagebrowser

import java.nio.file.{Path, Paths}

/** What a browser tab shows. */
enum TabKind:
  case Standard, Favorites, Others, All, Maintenance, Custom

/** A resolved tab. `dirs` are the folders it lists (empty for Others/Maintenance, which choose their own). */
final case class TabSpec(name: String, kind: TabKind, dirs: Seq[Path]):
  def id: String = "ib-" + name.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
  def lists: Boolean = kind != TabKind.Maintenance
  def isFavorites: Boolean = kind == TabKind.Favorites

object BrowserTabs:
  val FavoritesName = "Favorites"
  val Defaults: Seq[String] = Seq("txt2img", "img2img", "txt2img-grids", "img2img-grids", "Extras", FavoritesName, "Others", "All", "Maintenance")

  /** Output sub folders written by the engine (`<outputs>/<kind>/<date>/NNNNN-seed.png`). NUI keeps one shared `grids`
    * folder for both modes, so the two grid tabs list the same folder.
    */
  private val standardFolders = Map("txt2img" -> "txt2img", "img2img" -> "img2img", "txt2img-grids" -> "grids", "img2img-grids" -> "grids", "Extras" -> "extras")

  def favoritesDir(outputs: Path, settings: BrowserSettings): Path =
    val p = Paths.get(settings.favoritesDir)
    (if p.isAbsolute then p else outputs.resolve(p)).normalize()

  /** Splits the comma separated "active tabs" option (CSV quoting is honoured, as folder names may contain commas). */
  def parseList(text: String): Seq[String] =
    val out = Vector.newBuilder[String]
    val cur = new StringBuilder
    var quoted = false
    text.foreach {
      case '"' => quoted = !quoted
      case ',' if !quoted => out += cur.toString.trim; cur.clear()
      case c => cur += c
    }
    out += cur.toString.trim
    out.result().filter(_.nonEmpty)

  def formatList(tabs: Seq[String]): String = tabs.map(t => if t.contains(',') then "\"" + t + "\"" else t).mkString(", ")

  def resolve(name: String, outputs: Path, settings: BrowserSettings): TabSpec =
    standardFolders.get(name) match
      case Some(folder) => TabSpec(name, TabKind.Standard, Seq(outputs.resolve(folder).normalize()))
      case None => name match
        case FavoritesName => TabSpec(name, TabKind.Favorites, Seq(favoritesDir(outputs, settings)))
        case "Others" => TabSpec(name, TabKind.Others, Nil)
        case "Maintenance" => TabSpec(name, TabKind.Maintenance, Nil)
        case "All" =>
          val dirs = (Seq("txt2img", "img2img", "txt2img-grids", "img2img-grids", "Extras").map(standardFolders).map(outputs.resolve(_).normalize())
            :+ favoritesDir(outputs, settings)).distinct
          TabSpec(name, TabKind.All, dirs)
        case custom => TabSpec(name, TabKind.Custom, Seq(Paths.get(custom).toAbsolutePath.normalize()))

  def resolveAll(settings: BrowserSettings, outputs: Path): Vector[TabSpec] =
    settings.activeTabs.map(resolve(_, outputs, settings)).distinct.toVector
