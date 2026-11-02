package sdfx.core.i18n

/** Where a piece of text is shown; decides how a bilingual pair is laid out. */
enum TextKind:
  case Text, Tooltip, Prompt, Item, Title

enum DisplayStyle(val label: String):
  /** The translation replaces the English text. */
  case TranslationOnly extends DisplayStyle("Translation only")
  /** Two lines, like the upstream bilingual extension. */
  case Stacked extends DisplayStyle("Bilingual (two lines)")
  /** `translation (original)` on one line. */
  case Inline extends DisplayStyle("Bilingual (one line)")

enum DisplayOrder(val label: String):
  case TranslationFirst extends DisplayOrder("Translation first")
  case OriginalFirst extends DisplayOrder("Original first")

/** How a translation is combined with the original English text. */
final case class Display(style: DisplayStyle = DisplayStyle.TranslationOnly, order: DisplayOrder = DisplayOrder.TranslationFirst):
  def render(kind: TextKind, original: String, translation: String): String =
    if style == DisplayStyle.TranslationOnly then translation
    else
      val (a, b) = if order == DisplayOrder.OriginalFirst then (original, translation) else (translation, original)
      (style, kind) match
        // tool tips always have room for two lines; prompt texts cannot contain line breaks (JavaFX strips them)
        case (_, TextKind.Tooltip)                     => s"$a\n$b"
        case (_, TextKind.Title | TextKind.Item)       => s"$a ($b)"
        case (DisplayStyle.Stacked, TextKind.Prompt)   => s"$a / $b"
        case (DisplayStyle.Stacked, _)                 => s"$a\n$b"
        case _                                         => s"$a ($b)"

object Display:
  val Default: Display = Display()
  def parse(style: String, order: String): Display =
    Display(DisplayStyle.values.find(_.label == style).getOrElse(DisplayStyle.TranslationOnly),
      DisplayOrder.values.find(_.label == order).getOrElse(DisplayOrder.TranslationFirst))
