package sdfx.ui.vm

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Only known tag sources map to fixed hosts. All tag bytes are one URL path component. */
object TagWiki:
  def uri(enabled: Boolean, file: Option[String], kind: SuggestionKind, tag: String, category: Option[Int]): Option[URI] =
    if !enabled || kind != SuggestionKind.Tag || tag.isEmpty || tag == "." || tag == ".." then None
    else
      val name = file.map(_.replace('\\', '/').split('/').lastOption.getOrElse("").toLowerCase(Locale.ROOT)).getOrElse("")
      val host =
        if name.startsWith("danbooru_e621_merged") && category.exists(_ >= 6) then Some("e621.net")
        else if name.startsWith("danbooru") then Some("danbooru.donmai.us")
        else if name.startsWith("e621") then Some("e621.net")
        else None
      host.map { h =>
        val encoded = URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20")
        URI.create(s"https://$h/wiki_pages/$encoded")
      }
