package sdfx.ui.vm

import javafx.scene.paint.Color

/** User colour maps use upstream's source-stem -> category -> [dark, light] JSON shape. */
object TagCategoryColors:
  type Palette = Map[String, Map[Int, (Color, Color)]]
  private val Defaults = Map(0 -> "#59a8ee", 1 -> "#ee7777", 3 -> "#bd8df1", 4 -> "#73c98b", 5 -> "#e9b45f")

  def parse(text: String): Palette =
    require(text.length <= 65536, "Category colour map exceeds 65,536 characters")
    if text.trim.isEmpty then return Map.empty
    val root = ujson.read(text).obj
    require(root.size <= 128, "Category colour map has too many sources")
    root.iterator.map { (source, value) =>
      require(source.nonEmpty && source.length <= 255 && !source.contains('/') && !source.contains('\\'), "Invalid colour-map source name")
      val entries = value.obj
      require(entries.size <= 256, "Colour-map source has too many categories")
      val parsed = entries.iterator.map { (key, raw) =>
        val category = key.toIntOption.getOrElse(throw new IllegalArgumentException(s"Invalid category: $key"))
        val pair = raw.arr
        require(pair.size == 2, s"Category $key needs dark and light colours")
        def color(i: Int): Color =
          val value = pair(i).str.trim
          require(value.nonEmpty && value.length <= 40, "Invalid colour")
          try Color.web(value) catch case _: IllegalArgumentException => throw new IllegalArgumentException(s"Invalid colour: $value")
        category -> (color(0), color(1))
      }.toMap
      source -> parsed
    }.toMap

  def stem(path: Option[String]): Option[String] = path.map(_.replace('\\', '/').split('/').lastOption.getOrElse(""))
    .map { name => val i = name.lastIndexOf('.'); if i > 0 then name.substring(0, i) else name }.filter(_.nonEmpty)

  def color(palette: Palette, source: Option[String], category: Int, light: Boolean): Option[Color] =
    source.flatMap(palette.get) match
      case Some(colors) => colors.get(category).map(pair => if light then pair._2 else pair._1)
      case None => Defaults.get(category).map(Color.web)
