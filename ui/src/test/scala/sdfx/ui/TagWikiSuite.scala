package sdfx.ui

import munit.FunSuite
import sdfx.core.config.TagCompleteSettings
import sdfx.ui.vm.{TagWiki, SuggestionKind, CompletionData, CompletionEngine, TagCompletion}

class TagWikiSuite extends FunSuite:
  test("wiki links are opt-in and limited to ordinary tags from known sources") {
    assertEquals(TagWiki.uri(false, Some("danbooru.csv"), SuggestionKind.Tag, "blue_hair", Some(0)), None)
    assertEquals(TagWiki.uri(true, Some("other.csv"), SuggestionKind.Tag, "blue_hair", Some(0)), None)
    assertEquals(TagWiki.uri(true, Some("danbooru.csv"), SuggestionKind.Extra, "blue_hair", Some(0)), None)
    assertEquals(TagWiki.uri(true, Some("danbooru.csv"), SuggestionKind.Tag, "", Some(0)), None)
    assertEquals(TagWiki.uri(true, Some("danbooru.csv"), SuggestionKind.Tag, ".", Some(0)), None)
    assertEquals(TagWiki.uri(true, Some("danbooru.csv"), SuggestionKind.Tag, "..", Some(0)), None)
  }

  test("source and merged category select the intended fixed HTTPS host") {
    def url(file: String, category: Option[Int]) = TagWiki.uri(true, Some(file), SuggestionKind.Tag, "blue_hair", category).map(_.toString)
    assertEquals(url("C:\\tags\\Danbooru.csv", None), Some("https://danbooru.donmai.us/wiki_pages/blue_hair"))
    assertEquals(url("/tags/e621_tags.csv", Some(0)), Some("https://e621.net/wiki_pages/blue_hair"))
    assertEquals(url("danbooru_e621_merged.csv", Some(5)), Some("https://danbooru.donmai.us/wiki_pages/blue_hair"))
    assertEquals(url("danbooru_e621_merged.csv", Some(6)), Some("https://e621.net/wiki_pages/blue_hair"))
  }

  test("tag is encoded as one path component and cannot change the target host") {
    val uri = TagWiki.uri(true, Some("danbooru.csv"), SuggestionKind.Tag, "猫 /?#%", None).get
    assertEquals(uri.getHost, "danbooru.donmai.us")
    assertEquals(uri.getRawPath, "/wiki_pages/%E7%8C%AB%20%2F%3F%23%25")
    assertEquals(uri.getRawQuery, null)
    assertEquals(uri.getRawFragment, null)
  }

  test("alias or translated display links to the canonical tag") {
    val data = CompletionData(tags = TagCompletion.parse(Iterator("blue_hair,4,20,azure_hair")))
    val result = CompletionEngine.complete(data, TagCompleteSettings(onlyShowAlias = true), "azure", 5, 5, 5).get
    assertEquals(result.items.head.label, "azure_hair")
    assertEquals(result.items.head.wikiTag, Some("blue_hair"))
  }
