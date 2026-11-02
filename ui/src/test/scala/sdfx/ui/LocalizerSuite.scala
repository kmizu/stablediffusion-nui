package sdfx.ui

import javafx.application.Platform
import javafx.scene.control.*
import javafx.scene.layout.{HBox, VBox}
import javafx.scene.text.Text
import sdfx.core.i18n.*
import sdfx.ui.i18n.Localizer

import java.util.concurrent.{Callable, CountDownLatch, FutureTask, TimeUnit}

/** Needs the JavaFX toolkit (no window is shown). Skipped where the toolkit cannot start. */
class LocalizerSuite extends munit.FunSuite:
  private lazy val toolkit: Boolean =
    try
      val latch = new CountDownLatch(1)
      try Platform.startup(() => latch.countDown())
      catch case _: IllegalStateException => latch.countDown() // already started by another suite
      latch.await(20, TimeUnit.SECONDS)
    catch case _: Throwable => false

  private def fx[A](body: => A): A =
    assume(toolkit, "JavaFX toolkit unavailable")
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body
    )
    Platform.runLater(task)
    task.get(30, TimeUnit.SECONDS)

  private def pack(json: String): Translations =
    new LayeredTranslations(Seq(LocalizationPack.parse("t", json).fold(e => fail(e), identity)))

  private val ja = """{"Generate": "生成", "Seed": "シード", "Sampling": "サンプリング", "Run it": "実行", "Prompt": "プロンプト",
    "Name here": "ここに名前", "Euler a": "オイラー a", "Steps": "ステップ数", "Copy": "コピー", "Column": "列",
    "@@/^(\\d+) images$/": "$1 枚"}"""

  private def configure(l: Localizer, t: Translations, style: DisplayStyle = DisplayStyle.TranslationOnly,
      order: DisplayOrder = DisplayOrder.TranslationFirst, tooltips: Boolean = true, items: Boolean = true): Unit =
    l.configure(t, Display(style, order), tooltips, items)

  test("labels, buttons, check boxes, titled panes, tabs, tool tips, prompts, menus and columns are translated and restored"):
    fx {
      val label = new Label("Seed")
      val button = new Button("Generate")
      button.setTooltip(new Tooltip("Steps"))
      val check = new CheckBox("Sampling")
      val field = new TextField("typed text stays: Seed")
      field.setPromptText("Name here")
      val area = new TextArea()
      area.setPromptText("Prompt")
      val tp = new TitledPane("Sampling", new Label("Run it"))
      val tab = new Tab("Prompt", new Label("Copy"))
      val tabs = new TabPane(tab)
      val menu = new MenuButton("Copy", null, new MenuItem("Run it"))
      val table = new TableView[String]()
      val col = new TableColumn[String, String]("Column")
      table.getColumns.add(col)
      val root = new VBox(label, button, check, field, area, tp, tabs, menu, table)
      val l = new Localizer
      configure(l, pack(ja))
      l.sweepRoot(root)
      assertEquals(label.getText, "シード")
      assertEquals(button.getText, "生成")
      assertEquals(button.getTooltip.getText, "ステップ数")
      assertEquals(check.getText, "サンプリング")
      assertEquals(field.getText, "typed text stays: Seed") // user text is never touched
      assertEquals(field.getPromptText, "ここに名前")
      assertEquals(area.getPromptText, "プロンプト")
      assertEquals(tp.getText, "サンプリング")
      assertEquals(tp.getContent.asInstanceOf[Label].getText, "実行")
      assertEquals(tab.getText, "プロンプト")
      assertEquals(tab.getContent.asInstanceOf[Label].getText, "コピー") // content of a tab that is not in the scene graph
      assertEquals(Localizer.original(tab), "Prompt")
      assertEquals(menu.getText, "コピー")
      assertEquals(menu.getItems.get(0).getText, "実行")
      assertEquals(col.getText, "列")
      // switching the language off restores every English text
      configure(l, new LayeredTranslations(Nil))
      l.sweepRoot(root)
      assertEquals(label.getText, "Seed")
      assertEquals(button.getTooltip.getText, "Steps")
      assertEquals(field.getPromptText, "Name here")
      assertEquals(tab.getText, "Prompt")
      assertEquals(Localizer.original(tab), "Prompt")
      assertEquals(col.getText, "Column")
      assertEquals(menu.getItems.get(0).getText, "Run it")
    }

  test("sweeping again changes nothing, and a text the application sets later is translated from its own English"):
    fx {
      val label = new Label("Seed")
      val root = new HBox(label)
      val l = new Localizer
      configure(l, pack(ja), DisplayStyle.Stacked)
      l.sweepRoot(root)
      assertEquals(label.getText, "シード\nSeed")
      l.sweepRoot(root); l.sweepRoot(root)
      assertEquals(label.getText, "シード\nSeed") // not translated twice
      label.setText("Generate") // the application changes the text: translated at once by the text listener, no sweep needed
      assertEquals(label.getText, "生成\nGenerate")
      l.sweepRoot(root)
      assertEquals(label.getText, "生成\nGenerate")
      assertEquals(l.untranslated, Vector.empty[String]) // our own writes are not recorded as missing translations
      label.setText("12 images") // dynamic text through a regex rule
      l.sweepRoot(root)
      assertEquals(label.getText, "12 枚\n12 images")
      label.setText("Something untranslated")
      l.sweepRoot(root)
      assertEquals(label.getText, "Something untranslated")
      assert(l.untranslated.contains("Something untranslated"))
    }

  test("bilingual layouts: one line, original first, tool tips and prompt texts"):
    fx {
      val button = new Button("Generate")
      button.setTooltip(new Tooltip("Seed"))
      val field = new TextField()
      field.setPromptText("Name here")
      val area = new TextArea()
      area.setPromptText("Prompt")
      val root = new VBox(button, field, area)
      val l = new Localizer
      configure(l, pack(ja), DisplayStyle.Inline, DisplayOrder.OriginalFirst)
      l.sweepRoot(root)
      assertEquals(button.getText, "Generate (生成)")
      assertEquals(button.getTooltip.getText, "Seed\nシード")
      assertEquals(field.getPromptText, "Name here (ここに名前)")
      assertEquals(area.getPromptText, "Prompt (プロンプト)") // JavaFX strips line breaks from prompt texts
      configure(l, pack(ja), DisplayStyle.Stacked, DisplayOrder.TranslationFirst)
      l.sweepRoot(root)
      assertEquals(button.getText, "生成\nGenerate")
      assertEquals(field.getPromptText, "ここに名前 / Name here")
      configure(l, pack(ja), tooltips = false)
      l.sweepRoot(root)
      assertEquals(button.getText, "生成")
      assertEquals(button.getTooltip.getText, "Seed") // tool tips off
    }

  /** Text a drop-down shows for the item at `index`: through the popup cell factory or the button cell. */
  private def cellText(box: ComboBox[?], index: Int, button: Boolean = false): String =
    val view = new ListView[Any]()
    box.getItems.forEach(i => view.getItems.add(i))
    val cell: ListCell[Any] =
      if button then box.getButtonCell.asInstanceOf[ListCell[Any]]
      else box.asInstanceOf[ComboBox[Any]].getCellFactory.call(view)
    cell.updateListView(view)
    cell.updateIndex(index)
    cell.getText

  test("drop-downs show translated items while the selected value stays the English one"):
    fx {
      val combo = new ComboBox[String]()
      combo.getItems.addAll("Euler a", "Other")
      combo.setValue("Euler a")
      val choice = new ChoiceBox[String]()
      choice.getItems.addAll("Euler a", "Other")
      choice.setValue("Other")
      val editable = new ComboBox[String]()
      editable.setEditable(true)
      editable.getItems.add("Euler a")
      val custom = new ComboBox[String]() // an application cell factory is left alone
      custom.setCellFactory(_ => new ListCell[String]())
      custom.getItems.add("Euler a")
      val root = new VBox(combo, choice, editable, custom)
      val l = new Localizer
      configure(l, pack(ja))
      l.sweepRoot(root)
      assertEquals(combo.getValue, "Euler a")
      assertEquals(cellText(combo, 0), "オイラー a")
      assertEquals(cellText(combo, 0, button = true), "オイラー a")
      assertEquals(cellText(combo, 1), "Other")
      assertEquals(combo.getItems.get(0), "Euler a") // the data is unchanged
      assertEquals(choice.getValue, "Other")
      assertEquals(choice.getConverter.toString("Euler a"), "オイラー a")
      assert(editable.getCellFactory == null && editable.getButtonCell == null, "editable boxes keep their own text")
      assert(!custom.getCellFactory.call(null).getClass.getName.contains("LocalizedCell"), "application cells are left alone")
      configure(l, pack(ja), items = false)
      l.sweepRoot(root)
      assert(combo.getCellFactory == null && combo.getButtonCell == null)
      assert(choice.getConverter == null, "the original (default) converter is back")
      configure(l, pack(ja), DisplayStyle.Inline)
      l.sweepRoot(root)
      assertEquals(cellText(combo, 0), "オイラー a (Euler a)")
      assertEquals(combo.getValue, "Euler a")
      // changing the language refreshes the cells that already exist
      val popupCell = combo.asInstanceOf[ComboBox[Any]].getCellFactory.call(new ListView[Any]())
      val view = new ListView[Any](javafx.collections.FXCollections.observableArrayList[Any]("Euler a"))
      popupCell.updateListView(view); popupCell.updateIndex(0)
      assertEquals(popupCell.getText, "オイラー a (Euler a)")
      configure(l, pack("""{"Euler a": "別名"}"""))
      assertEquals(popupCell.getText, "別名")
      configure(l, new LayeredTranslations(Nil))
      l.sweepRoot(root)
      assert(combo.getCellFactory == null && combo.getButtonCell == null)
      assert(choice.getConverter == null, "the original (default) converter is back")
    }

  test("an existing converter keeps working underneath the translation"):
    fx {
      val combo = new ComboBox[Int]()
      combo.getItems.addAll(1, 2)
      combo.setConverter(new javafx.util.StringConverter[Int]:
        override def toString(i: Int): String = if i == 1 then "Copy" else "Seed"
        override def fromString(s: String): Int = 0)
      val l = new Localizer
      configure(l, pack(ja))
      l.sweepRoot(new HBox(combo))
      assertEquals(cellText(combo, 0), "コピー")
      assertEquals(cellText(combo, 1), "シード")
    }

  test("skip markers keep data texts untouched, and Tooltip.install tool tips are found"):
    fx {
      val data = Localizer.skip(new Label("Seed"))
      val tree = Localizer.skipTree(new HBox(new Label("Seed")))
      val plain = new javafx.scene.layout.Pane()
      Tooltip.install(plain, new Tooltip("Steps"))
      val installed = plain.getProperties.get("javafx.scene.control.Tooltip").asInstanceOf[Tooltip]
      val root = new VBox(data, tree, plain)
      val l = new Localizer
      configure(l, pack(ja))
      l.sweepRoot(root)
      assertEquals(data.getText, "Seed")
      assertEquals(tree.getChildren.get(0).asInstanceOf[Label].getText, "Seed")
      assertEquals(installed.getText, "ステップ数")
    }

  test("Text nodes in a flow are translated, the Text inside a control skin is left to the control"):
    fx {
      val free = new Text("Seed")
      val label = new Label("Seed")
      val l = new Localizer
      configure(l, pack(ja))
      l.sweepRoot(new VBox(free, label))
      assertEquals(free.getText, "シード")
      assertEquals(label.getText, "シード")
    }

  test("scoped rules follow the node tree: id ancestors, style classes"):
    fx {
      val inScope = new Label("Seed")
      val outScope = new Label("Seed")
      val box = new VBox(inScope)
      box.setId("tab_ti")
      val styled = new Label("Seed")
      styled.getStyleClass.add("special")
      val root = new VBox(box, outScope, styled)
      val l = new Localizer
      configure(l, pack("""{"Seed": "シード", "##tab_ti##Seed": "種", "##@Label.special##Seed": "特別"}"""))
      l.sweepRoot(root)
      assertEquals(inScope.getText, "種")
      assertEquals(outScope.getText, "シード")
      assertEquals(styled.getText, "特別")
    }

  test("coverage counts translated and untranslated texts"):
    fx {
      val root = new VBox(new Label("Seed"), new Label("Generate"), new Label("Not in the pack"), new Label("12"))
      val l = new Localizer
      configure(l, pack(ja))
      l.sweepRoot(root)
      assertEquals(l.coverage, (2, 1)) // the number has no letters and is ignored
      assertEquals(l.untranslated, Vector("Not in the pack"))
    }

  test("a large scene is swept quickly"):
    fx {
      val root = new VBox()
      var i = 0
      while i < 3000 do
        root.getChildren.add(new HBox(new Label(if i % 2 == 0 then "Seed" else "Label " + i), new Button("Generate"), new CheckBox("Sampling")))
        i += 1
      val l = new Localizer
      configure(l, pack(ja))
      val t0 = System.nanoTime()
      l.sweepRoot(root)
      val first = (System.nanoTime() - t0) / 1e6
      val t1 = System.nanoTime()
      l.sweepRoot(root)
      val again = (System.nanoTime() - t1) / 1e6
      println(f"LocalizerSuite: 12,000 controls: first sweep $first%.1f ms, repeat sweep $again%.1f ms")
      assert(again < 1000, s"repeat sweep took $again ms")
      assertEquals(root.getChildren.get(0).asInstanceOf[HBox].getChildren.get(1).asInstanceOf[Button].getText, "生成")
    }
