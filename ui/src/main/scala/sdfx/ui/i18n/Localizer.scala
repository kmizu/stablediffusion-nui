package sdfx.ui.i18n

import javafx.animation.{KeyFrame, Timeline}
import javafx.scene.{Node, Parent}
import javafx.scene.control.*
import javafx.scene.text.Text
import javafx.stage.{PopupWindow, Stage, Window}
import javafx.util.{Duration, StringConverter}
import sdfx.core.i18n.*

import java.util.{IdentityHashMap, WeakHashMap}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Scope information of a JavaFX node for `##scope##text` keys. */
private final class FxScope(node: Node, val parentNode: Option[ScopeNode]) extends ScopeNode:
  def nodeId: String = Option(node.getId).getOrElse("")
  def styleClasses: Set[String] = node.getStyleClass.asScala.toSet
  lazy val typeNames: Seq[String] =
    Iterator.iterate[Class[?]](node.getClass)(_.getSuperclass).takeWhile(_ != null).map(_.getSimpleName).filter(_.nonEmpty).toVector

/** Translates the text of the whole JavaFX scene graph in place, the way the localization extensions translate a page:
  * labels, buttons, check boxes, tab titles, titled panes, menus, table headers, tool tips, prompt texts, window titles and
  * the shown text of drop-down items. The English original of every text is remembered, so the language can be switched or
  * turned off at any time, and texts the application changes later are translated again.
  *
  * Only displayed strings change. Values (combo selections, the text in text fields, model names) are never touched, and
  * what the application reads back from `getText` of a translated control is the translation; code that needs the English
  * text uses [[Localizer.original]].
  *
  * Everything runs on the FX thread.
  */
final class Localizer:
  import Localizer.*

  private final class Slot(var original: String, var shown: String, var gen: Int, var translated: Boolean)
  private final class Slots:
    var text: Slot = null
    var tooltip: Slot = null
    var prompt: Slot = null
    var title: Slot = null
    var converter: ConverterSlot = null
    var combo: ComboSlot = null
    var watching: Boolean = false
  private final class ComboSlot(val factory: javafx.util.Callback[ListView[Any], ListCell[Any]], val button: ListCell[Any])
  /** Cell of a drop-down: shows the translated text of an item (the item itself, and so the selected value, is untouched). */
  private final class LocalizedCell(box: ComboBox[Any]) extends ListCell[Any]:
    cells.put(this, java.lang.Boolean.TRUE)
    override def updateItem(item: Any, empty: Boolean): Unit =
      super.updateItem(item, empty)
      show(item, empty)
    def refresh(): Unit = show(getItem, isEmpty)
    private def show(item: Any, empty: Boolean): Unit =
      if empty || item == null then { setText(null); setGraphic(null) }
      else item match
        case n: Node => setText(null); setGraphic(n)
        case _ =>
          setGraphic(null)
          val conv = box.getConverter
          setText(itemText(if conv != null then conv.toString(item) else String.valueOf(item)))
  private val cells = new WeakHashMap[LocalizedCell, java.lang.Boolean]()
  private final class ConverterSlot(val original: StringConverter[Any], var wrapper: StringConverter[Any], var gen: Int)

  private val slots = new WeakHashMap[AnyRef, Slots]()
  private var translations: Translations = new LayeredTranslations(Nil)
  private var display: Display = Display.Default
  private var translateTooltips = true
  private var translateItems = true
  private var gen = 1
  private var active = false
  private var needScope = false
  private val missingSet = mutable.LinkedHashSet.empty[String]
  private var lastSweepNanos = 0L
  private var sweeps = 0L
  private val timer = new Timeline(new KeyFrame(Duration.millis(400), _ => tick()))
  timer.setCycleCount(javafx.animation.Animation.INDEFINITE)
  private var skipUntil = 0L
  private var applying = false

  def isActive: Boolean = active

  /** (Re)configures the language and re-translates every window now. An empty translation set switches back to English. */
  def configure(newTranslations: Translations, newDisplay: Display, tooltips: Boolean, items: Boolean): Unit =
    translations = newTranslations
    display = newDisplay
    translateTooltips = tooltips
    translateItems = items
    needScope = newTranslations.hasScoped
    gen += 1
    missingSet.clear()
    val wasActive = active
    active = !newTranslations.isEmpty
    sweepNow() // also restores English when switching off
    cells.keySet.asScala.toVector.foreach(_.refresh())
    if active then timer.play()
    else if wasActive then timer.stop()

  /** Translates all windows now (called after the UI structure changed). */
  def sweepNow(): Unit =
    val start = System.nanoTime()
    try
      for w <- Window.getWindows.asScala.toVector do
        try sweepWindow(w)
        catch case NonFatal(_) => () // one broken window must not stop the others
    finally
      lastSweepNanos = System.nanoTime() - start
      sweeps += 1

  /** Translates one scene graph that is not shown yet (so the first frame is already in the chosen language). */
  def sweepRoot(root: Node): Unit =
    if root != null then try walk(root, null) catch case NonFatal(_) => ()

  def stop(): Unit =
    timer.stop()
    active = false

  private def tick(): Unit =
    val now = System.nanoTime()
    if now < skipUntil then return
    sweepNow()
    // a slow sweep (very large scene) backs off so the UI stays responsive: wait ten times as long as it took
    if lastSweepNanos > 15_000_000L then skipUntil = now + lastSweepNanos * 10

  def lastSweepMillis: Double = lastSweepNanos / 1e6
  def sweepCount: Long = sweeps

  /** English texts without a translation that were seen since the last [[configure]] (up to 20,000). */
  def untranslated: Vector[String] = missingSet.toVector

  /** Translated / untranslated counts over the texts currently known (the texts of live nodes). */
  def coverage: (Int, Int) =
    var yes = 0
    var no = 0
    for s <- slots.values().asScala do
      for sl <- Seq(s.text, s.tooltip, s.prompt, s.title) if sl != null && sl.gen == gen && hasLetters(sl.original) do
        if sl.translated then yes += 1 else no += 1
    (yes, no)

  // ---- windows -----------------------------------------------------------------------------------------------
  private def sweepWindow(w: Window): Unit =
    w match
      case s: Stage =>
        val sl = slotsOf(s)
        if s.getTitle != null then process(sl, TitleKey, s.getTitle, TextKind.Title, null, s.setTitle)
      case cm: ContextMenu =>
        cm.getItems.asScala.foreach(walkMenuItem(_, null))
      case _ => ()
    val scene = w.getScene
    if scene != null && scene.getRoot != null then walk(scene.getRoot, null)

  // ---- slot bookkeeping --------------------------------------------------------------------------------------
  private def slotsOf(o: AnyRef): Slots =
    var s = slots.get(o)
    if s == null then { s = new Slots; slots.put(o, s) }
    s

  private val TextKey = 0
  private val TooltipKey = 1
  private val PromptKey = 2
  private val TitleKey = 3

  private def slotGet(s: Slots, k: Int): Slot = k match
    case TextKey => s.text
    case TooltipKey => s.tooltip
    case PromptKey => s.prompt
    case _ => s.title
  private def slotPut(s: Slots, k: Int, v: Slot): Unit = k match
    case TextKey => s.text = v
    case TooltipKey => s.tooltip = v
    case PromptKey => s.prompt = v
    case _ => s.title = v

  /** Brings one displayed string up to date: finds the English original (the current value unless it is what we showed),
    * translates it for the current configuration and writes the result when it differs.
    * @return the English original
    */
  private def process(holder: Slots, key: Int, current: String, kind: TextKind, scope: ScopeNode, set: String => Unit, enabled: Boolean = true): String =
    var slot = slotGet(holder, key)
    if slot != null && slot.gen == gen && current == slot.shown then return slot.original
    val original = if slot != null && current == slot.shown then slot.original else current
    val translated = if active && enabled && original != null && original.nonEmpty then translations.translate(original, Option(scope)) else None
    val shown = translated.map(t => display.render(kind, original, t)).getOrElse(original)
    if shown != current then
      applying = true
      try set(shown) finally applying = false
    if slot == null then { slot = new Slot(original, shown, gen, translated.isDefined); slotPut(holder, key, slot) }
    else { slot.original = original; slot.shown = shown; slot.gen = gen; slot.translated = translated.isDefined }
    if active && enabled && translated.isEmpty && hasLetters(original) && missingSet.size < MaxMissing then missingSet += original.trim
    original

  // ---- traversal ---------------------------------------------------------------------------------------------
  private def child(n: Node, parent: ScopeNode): ScopeNode = if needScope then new FxScope(n, Option(parent)) else null

  private def walk(n: Node, parent: ScopeNode): Unit =
    if n == null then return
    val props = n.getProperties
    if props.containsKey(SkipKey) then
      if props.get(SkipKey) == SkipTree then return
    val scope = child(n, parent)
    n match
      case t: Text =>
        if !isSkinText(t) && !props.containsKey(SkipKey) then
          process(slotsOf(t), TextKey, t.getText, TextKind.Text, scope, t.setText)
      case _ => ()
    n match
      case c: Control => walkControl(c, scope)
      case p: Parent => p.getChildrenUnmodifiable.forEach(walk(_, scope))
      case _ => ()
    tooltipOf(n).foreach(t => walkTooltip(t, scope))

  private def tooltipOf(n: Node): Option[Tooltip] = n match
    case c: Control if c.getTooltip != null => Some(c.getTooltip)
    case _ => n.getProperties.get(TooltipProperty) match
      case t: Tooltip => Some(t)
      case _ => None

  private def walkTooltip(t: Tooltip, scope: ScopeNode): Unit =
    if t.getText != null then process(slotsOf(t), TextKey, t.getText, TextKind.Tooltip, scope, t.setText, translateTooltips)

  private def isSkinText(t: Text): Boolean =
    // the Text inside the skin of a Label/Button/TextField mirrors the control's own value; the control is translated instead
    var p: Node = t.getParent
    var depth = 0
    while p != null && depth < 6 do
      if p.isInstanceOf[Control] then return true
      p = p.getParent
      depth += 1
    false

  private def walkControl(c: Control, scope: ScopeNode): Unit =
    val skip = c.getProperties.containsKey(SkipKey)
    c match
      case l: Labeled =>
        if !skip then
          val holder = slotsOf(l)
          process(holder, TextKey, l.getText, TextKind.Text, scope, l.setText)
          // retranslate right away when the application sets a new text (the sweep alone would show English for up to 400 ms)
          if !holder.watching && !needScope then
            holder.watching = true
            l.textProperty.addListener((_, _, now) =>
              if active && !applying && now != null && !l.getProperties.containsKey(SkipKey) then
                val h = slotsOf(l)
                val sl = h.text
                if sl == null || now != sl.shown then process(h, TextKey, now, TextKind.Text, null, l.setText))
        if l.getGraphic != null then walk(l.getGraphic, scope)
      case _ => ()
    c match
      case tp: TabPane =>
        tp.getTabs.forEach { tab =>
          if tab.getProperties.get(SkipKey) == null && tab.getText != null then
            val orig = process(slotsOf(tab), TextKey, tab.getText, TextKind.Title, scope, tab.setText) // tab headers have one line: never stacked
            tab.getProperties.put(OriginalKey, Array(orig, tab.getText))
          if tab.getTooltip != null then walkTooltip(tab.getTooltip, scope)
          if tab.getGraphic != null then walk(tab.getGraphic, scope)
          walk(tab.getContent, scope)
        }
      case tp: TitledPane => walk(tp.getContent, scope)
      case sp: ScrollPane => walk(sp.getContent, scope)
      case sp: SplitPane => sp.getItems.forEach(walk(_, scope))
      case ac: Accordion => ac.getPanes.forEach(walk(_, scope))
      case tb: ToolBar => tb.getItems.forEach(walk(_, scope))
      case bb: ButtonBar => bb.getButtons.forEach(walk(_, scope))
      case mb: MenuBar => mb.getMenus.forEach(walkMenuItem(_, scope))
      case mb: MenuButton => mb.getItems.forEach(walkMenuItem(_, scope))
      case tv: TableView[?] => tv.getColumns.forEach(walkColumn(_, scope))
      case tv: TreeTableView[?] => tv.getColumns.forEach(walkColumn(_, scope))
      case cb: ComboBox[?] =>
        if cb.getPromptText != null && !skip then process(slotsOf(cb), PromptKey, cb.getPromptText, TextKind.Prompt, scope, cb.setPromptText)
        if !cb.isEditable then wrapCombo(cb.asInstanceOf[ComboBox[Any]])
      case ch: ChoiceBox[?] =>
        wrapConverter(ch, scope, ch.converterProperty.asInstanceOf[javafx.beans.property.ObjectProperty[StringConverter[Any]]])
      case ti: TextInputControl =>
        if ti.getPromptText != null && !skip then
          process(slotsOf(ti), PromptKey, ti.getPromptText, TextKind.Prompt, scope, ti.setPromptText)
      case _ => ()
    if c.getContextMenu != null then c.getContextMenu.getItems.forEach(walkMenuItem(_, scope))

  private def walkColumn(col: TableColumnBase[?, ?], scope: ScopeNode): Unit =
    if col.getText != null && col.getProperties.get(SkipKey) == null then
      process(slotsOf(col), TextKey, col.getText, TextKind.Text, scope, col.setText)
    if col.getGraphic != null then walk(col.getGraphic, scope)
    col.getColumns.forEach(walkColumn(_, scope))

  private def walkMenuItem(item: MenuItem, scope: ScopeNode): Unit =
    if item.getText != null && item.getProperties.get(SkipKey) == null then
      process(slotsOf(item), TextKey, item.getText, TextKind.Text, scope, item.setText)
    if item.getGraphic != null then walk(item.getGraphic, scope)
    item match
      case m: Menu => m.getItems.forEach(walkMenuItem(_, scope))
      case c: CustomMenuItem => walk(c.getContent, scope)
      case _ => ()

  // ---- drop-down items: cells (ComboBox) or the converter (ChoiceBox) decide what is displayed, the values stay untouched ---
  /** Display text of a drop-down item for the current configuration. */
  private def itemText(base: String): String =
    if base == null || base.isEmpty then base
    else if !(active && translateItems) then base
    else
      translations.translate(base) match
        case Some(t) => display.render(TextKind.Item, base, t)
        case None =>
          if missingSet.size < MaxMissing && hasLetters(base) then missingSet += base.trim
          base

  private def wrapCombo(box: ComboBox[Any]): Unit =
    val holder = slotsOf(box)
    val cs = holder.combo
    val ours = cs != null && (box.getButtonCell eq cs.button) && (box.getCellFactory eq cs.factory)
    if translateItems && active then
      if !ours && box.getCellFactory == null && box.getButtonCell == null then
        val factory: javafx.util.Callback[ListView[Any], ListCell[Any]] = _ => new LocalizedCell(box)
        val button = new LocalizedCell(box)
        holder.combo = new ComboSlot(factory, button)
        box.setCellFactory(factory)
        box.setButtonCell(button)
    else if ours then
      box.setCellFactory(null)
      box.setButtonCell(null)
      holder.combo = null

  private def wrapConverter(box: Control, scope: ScopeNode, property: javafx.beans.property.ObjectProperty[StringConverter[Any]]): Unit =
    val holder = slotsOf(box)
    val current = property.get
    var cs = holder.converter
    val ours = cs != null && (current eq cs.wrapper)
    if translateItems && active then
      if ours && cs.gen == gen then return
      val original = if ours then cs.original else current
      val wrapper = new StringConverter[Any]:
        override def toString(value: Any): String =
          val base = if original != null then original.toString(value) else if value == null then null else String.valueOf(value)
          itemText(base) // scoped rules need a node; items have none and the wrapper must not keep nodes alive
        override def fromString(s: String): Any = if original != null then original.fromString(s) else s
      holder.converter = new ConverterSlot(original, wrapper, gen)
      property.set(wrapper)
    else if ours then
      property.set(cs.original)
      holder.converter = null

object Localizer:
  /** Node property: `skip` leaves that node's own text alone, `skip-tree` leaves the whole subtree alone. */
  val SkipKey = "sdfx.i18n.skip"
  val SkipTree = "tree"
  /** Property filled on translated tabs with their English text (for code that looks tabs up by name). */
  val OriginalKey = "sdfx.i18n.original"
  private val TooltipProperty = "javafx.scene.control.Tooltip"
  private val MaxMissing = 20000

  def hasLetters(s: String): Boolean = s != null && s.exists(_.isLetter)

  /** Marks `node` so its own text is never translated (for texts that are data). */
  def skip[N <: Node](node: N): N = { node.getProperties.put(SkipKey, "self"); node }
  def skipTree[N <: Node](node: N): N = { node.getProperties.put(SkipKey, SkipTree); node }

  /** English text of a tab even while it shows a translation. */
  def original(tab: Tab): String = tab.getProperties.get(OriginalKey) match
    case Array(orig: String, shown: String) if shown == tab.getText => orig
    case _ => tab.getText

/** Applies the localization preferences of the settings to a [[Localizer]]. */
final class LocalizationController(home: java.nio.file.Path, initialExtraDirs: Seq[String]):
  @volatile private var extra: Seq[java.nio.file.Path] = initialExtraDirs.map(d => java.nio.file.Path.of(d))
  val catalog = new PackCatalog(home, () => extra)
  val localizer = new Localizer

  /** The folders the user listed for extra packs (the Settings pane changes them before they are saved). */
  def setExtraDirs(dirs: Seq[String]): Unit = extra = dirs.map(d => java.nio.file.Path.of(d))

  /** Loads the selected packs and (re)configures the localizer. Returns problems to show the user (unreadable packs). */
  def apply(prefs: sdfx.core.config.LocalizationPreferences): Vector[String] =
    setExtraDirs(prefs.extraDirs)
    val (translations, problems) = catalog.resolve(prefs.pack, prefs.fallbackPack)
    localizer.configure(translations, Display.parse(prefs.style, prefs.order), prefs.tooltips, prefs.items)
    problems
