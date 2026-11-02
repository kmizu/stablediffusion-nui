package sdfx.core.imagebrowser

import sdfx.core.json.SnakePickle.{ReadWriter => RW, *}

/** Options of the Image Browser. Names follow the documented upstream option labels (see docs/images-browser-verification.md). */
case class BrowserSettings(
    activeTabs: Seq[String] = BrowserTabs.Defaults,
    hiddenComponents: Seq[String] = Nil,
    withSubdirs: Boolean = true,
    preload: Boolean = false,
    copyInsteadOfMove: Boolean = false,
    deleteMessage: Boolean = true,
    txtFiles: Boolean = true,
    deleteRecycle: Boolean = true,
    scanExif: Boolean = true,
    modShift: Boolean = false,
    modCtrlShift: Boolean = false,
    swipe: Boolean = false,
    rankingPnginfo: Boolean = false,
    pageColumns: Int = 6,
    pageRows: Int = 6,
    thumbnailSize: Int = 140,
    thumbnailCrop: Boolean = false,
    imgTooltips: Boolean = true,
    showProgress: Boolean = true,
    infoFormat: Boolean = true,
    infoOrder: Seq[String] = BrowserSettings.DefaultInfoOrder,
    infoAdd: Boolean = false,
    favoritesDir: String = "favorites"
) derives RW:
  def perPage: Int = math.max(1, pageColumns) * math.max(1, pageRows)
  def hidden(component: String): Boolean = hiddenComponents.contains(component)
  /** Normalises values a hand-edited file could make unusable. */
  def sanitized: BrowserSettings =
    copy(pageColumns = pageColumns.max(1).min(30), pageRows = pageRows.max(1).min(30),
      thumbnailSize = thumbnailSize.max(48).min(512),
      activeTabs = Option(activeTabs.map(_.trim).filter(_.nonEmpty).distinct).filter(_.nonEmpty).getOrElse(BrowserTabs.Defaults),
      hiddenComponents = hiddenComponents.filter(BrowserSettings.Components.contains),
      favoritesDir = if favoritesDir.trim.isEmpty then "favorites" else favoritesDir.trim)

object BrowserSettings:
  val DefaultInfoOrder: Seq[String] = Seq("Prompt", "Negative prompt", "Model", "VAE", "Sampler", "Schedule type", "Steps", "CFG scale", "Seed", "Size")
  /** Components the user can hide (same list as the documented upstream option). */
  val Components: Seq[String] = Seq("Sort by", "Filename keyword search", "EXIF keyword search", "Ranking Filter", "Aesthetic Score",
    "Generation Info", "File Name", "File Time", "Open Folder", "Send to buttons", "Copy to directory", "Gallery Controls Bar",
    "Ranking Bar", "Delete Bar", "Additional Generation Info")
