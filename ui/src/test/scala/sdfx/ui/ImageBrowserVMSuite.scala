package sdfx.ui

import sdfx.core.imagebrowser.*
import sdfx.core.png.PngMetadata
import sdfx.ui.vm.*

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime

class ImageBrowserVMSuite extends munit.FunSuite:
  private def tmp(): Path = Files.createTempDirectory("ibvm-test-")
  private def env(root: Path): BrowserEnv = new BrowserEnv(root.resolve("outputs"), root.resolve("config"), new FolderTrash(root.resolve("trash")))
  private def png(p: Path, text: Option[String], mtime: Long): Path =
    Files.createDirectories(p.getParent)
    PngMetadata.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), p, text.map(PngMetadata.ParametersKey -> _).toSeq)
    Files.setLastModifiedTime(p, FileTime.fromMillis(mtime))
    p
  private def text(prompt: String, seed: Int, extra: String = "") =
    s"$prompt\nNegative prompt: ugly\nSteps: 20, Sampler: Euler, CFG scale: 7, Seed: $seed, Size: 512x512, Model hash: abc, Model: m$extra"

  /** 25 images in the txt2img folder (img-00 oldest ... img-24 newest), page size 10 (5 columns x 2 rows). */
  private def fixture(): (BrowserEnv, ImageBrowserTabVM) =
    val e = env(tmp())
    e.store.updateSettings(_.copy(pageColumns = 5, pageRows = 2))
    (0 until 25).foreach(i => png(e.outputs.resolve(f"txt2img/2026-11-01/img-$i%02d.png"), Some(text(s"prompt $i", i)), 1000000L + i * 1000L))
    val vm = new ImageBrowserTabVM(BrowserTabs.resolve("txt2img", e.outputs, e.settings), e)
    vm.refresh()
    (e, vm)

  test("keybindings: plain keys, default Ctrl paging, Shift and Ctrl+Shift variants"):
    val s = BrowserSettings()
    assertEquals(BrowserKeys.resolve(s, "3", false, false), Some(BrowserKeyAction.Rank(Some(3))))
    assertEquals(BrowserKeys.resolve(s, "0", false, false), Some(BrowserKeyAction.Rank(None)))
    assertEquals(BrowserKeys.resolve(s, "6", false, false), None)
    assertEquals(BrowserKeys.resolve(s, "f", false, false), Some(BrowserKeyAction.Favorite))
    assertEquals(BrowserKeys.resolve(s, "R", false, false), Some(BrowserKeyAction.Refresh))
    assertEquals(BrowserKeys.resolve(s, "DELETE", false, false), Some(BrowserKeyAction.Delete))
    assertEquals(BrowserKeys.resolve(s, "LEFT", true, false), Some(BrowserKeyAction.PrevPage))
    assertEquals(BrowserKeys.resolve(s, "RIGHT", true, false), Some(BrowserKeyAction.NextPage))
    assertEquals(BrowserKeys.resolve(s, "RIGHT", false, false), None)
    assertEquals(BrowserKeys.resolve(s, "RIGHT", false, true), None)
    assertEquals(BrowserKeys.resolve(s, "F", true, false), None) // modified plain keys are left to the system
    val shift = s.copy(modShift = true)
    assertEquals(BrowserKeys.resolve(shift, "RIGHT", false, true), Some(BrowserKeyAction.NextPage))
    assertEquals(BrowserKeys.resolve(shift, "RIGHT", true, false), None)
    val both = s.copy(modCtrlShift = true)
    assertEquals(BrowserKeys.resolve(both, "LEFT", true, true), Some(BrowserKeyAction.PrevPage))
    assertEquals(BrowserKeys.resolve(both, "LEFT", true, false), None)

  test("paging: 25 images, 10 per page; first/prev/next/end/goTo clamp; newest first by default"):
    val (_, vm) = fixture()
    assertEquals((vm.filtered.size, vm.pager.pageCount, vm.page, vm.pageLabel), (25, 3, 1, "Page Index (1/3)"))
    assertEquals(vm.pageItems.map(_.fileName).head, "img-24.png")
    vm.next(); vm.next(); vm.next()
    assertEquals(vm.page, 3); assertEquals(vm.pageItems.size, 5)
    vm.prev(); assertEquals(vm.page, 2)
    vm.end(); assertEquals(vm.page, 3)
    vm.first(); assertEquals(vm.page, 1)
    vm.goTo(99); assertEquals(vm.page, 3)
    vm.goTo(-4); assertEquals(vm.page, 1)

  test("query changes reset the page, sort flip keeps it, filters shrink the page count"):
    val (_, vm) = fixture()
    vm.goTo(3)
    vm.flipSortOrder()
    assertEquals(vm.page, 3)
    assertEquals(vm.filtered.head.fileName, "img-00.png")
    vm.setQuery(vm.query.copy(filenameKeyword = "img-1"))
    assertEquals((vm.filtered.size, vm.page, vm.pager.pageCount), (10, 1, 1))
    vm.setQuery(vm.query.copy(exifKeyword = "seed: 14", filenameKeyword = ""))
    assertEquals(vm.filtered.map(_.fileName), Vector("img-14.png"))
    vm.setQuery(BrowserQuery(exifKeyword = "[", useRegex = true))
    assertEquals(vm.filtered.size, 25); assert(vm.warnings.nonEmpty)
    vm.setQuery(BrowserQuery(exifKeyword = "nothing matches"))
    assertEquals((vm.filtered.size, vm.pager.pageCount, vm.pageItems), (0, 1, Vector.empty))

  test("selection survives filtering only when still visible; neighbour navigation turns pages"):
    val (_, vm) = fixture()
    vm.select(Some(vm.filtered(9).path))
    vm.selectNeighbor(1)
    assertEquals((vm.selectedRecord.get.fileName, vm.page), ("img-14.png", 2))
    vm.selectNeighbor(-1)
    assertEquals((vm.selectedRecord.get.fileName, vm.page), ("img-15.png", 1))
    vm.setQuery(vm.query.copy(filenameKeyword = "img-0"))
    assertEquals(vm.selected, None)
    vm.selectNeighbor(1); assert(vm.selected.isDefined)
    vm.select(Some(Path.of("/not/listed.png"))); assertEquals(vm.selected, None)

  test("ranking via the VM is persisted, filterable and sortable"):
    val (e, vm) = fixture()
    assertEquals(vm.setRanking(Some(3)).ok, false) // nothing selected
    vm.select(Some(vm.filtered(2).path))
    val name = vm.selectedRecord.get.fileName
    assert(vm.setRanking(Some(5)).ok)
    vm.setQuery(vm.query.copy(rankingFilter = "5"))
    assertEquals(vm.filtered.map(_.fileName), Vector(name))
    assertEquals(env(e.outputs.getParent).store.ranking(vm.filtered.head.path), Some(5))
    vm.setQuery(BrowserQuery(sortBy = "ranking"))
    assertEquals(vm.filtered.head.fileName, name)
    vm.select(Some(vm.filtered.head.path)); assert(vm.setRanking(None).ok)
    vm.setQuery(BrowserQuery(rankingFilter = "None"))
    assertEquals(vm.filtered.size, 25)

  test("favorite moves the file, updates the list and selects the neighbour; Favorites tab lists it"):
    val (e, vm) = fixture()
    val first = vm.filtered.head
    vm.select(Some(first.path))
    val out = vm.favorite()
    assert(out.ok, out.message)
    assert(!Files.exists(first.path) && Files.exists(e.favoritesDir.resolve(first.fileName)))
    assertEquals(vm.filtered.size, 24)
    assertEquals(vm.selectedRecord.map(_.fileName), Some("img-23.png"))
    val fav = new ImageBrowserTabVM(BrowserTabs.resolve("Favorites", e.outputs, e.settings), e)
    fav.refresh()
    assertEquals(fav.filtered.map(_.fileName), Vector(first.fileName))
    // copy mode keeps it in the source tab
    e.store.updateSettings(_.copy(copyInsteadOfMove = true))
    vm.select(Some(vm.filtered.head.path))
    val copied = vm.favorite()
    assert(copied.ok && copied.message.startsWith("Copied"))
    assertEquals(vm.filtered.size, 24)
    assertEquals(new ImageBrowserTabVM(BrowserTabs.resolve("Favorites", e.outputs, e.settings), e).tap(_.refresh()).filtered.size, 2)

  test("copy/move to a directory"):
    val (e, vm) = fixture()
    val dest = e.outputs.getParent.resolve("dest").toString
    assert(!vm.toDirectory(dest).ok) // nothing selected
    vm.select(Some(vm.filtered.head.path))
    assert(!vm.toDirectory("  ").ok)
    val res = vm.toDirectory(dest)
    assert(res.ok, res.message)
    assertEquals(Files.list(Path.of(dest)).count(), 1L)
    assertEquals(vm.filtered.size, 24)

  test("delete next N: limited to the page unless off-screen is allowed; confirmation step separates plan from action"):
    val (e, vm) = fixture()
    vm.goTo(2)
    vm.select(Some(vm.pageItems(7).path)) // 3 images left on this page
    assertEquals(vm.deletePlan(5, offscreen = false).size, 3)
    assertEquals(vm.deletePlan(5, offscreen = true).size, 5)
    assertEquals(vm.deletePlan(0, offscreen = true), Vector.empty)
    val plan = vm.deletePlan(2, offscreen = false)
    assert(plan.forall(r => Files.exists(r.path))) // planning deletes nothing
    val out = vm.deleteConfirmed(plan)
    assert(out.ok, out.message)
    assert(out.message.contains("recycle bin"))
    assert(plan.forall(r => !Files.exists(r.path)))
    assertEquals(vm.filtered.size, 23)
    assert(vm.selected.isDefined)
    val trashed = Files.walk(e.outputs.getParent.resolve("trash")).filter(Files.isRegularFile(_)).count()
    assertEquals(trashed, 2L)
    assertEquals(vm.deleteConfirmed(Vector.empty).ok, false)
    // already gone on disk -> reported, list kept consistent
    val ghost = plan.head
    val again = vm.deleteConfirmed(Vector(ghost))
    assert(!again.ok && again.message.contains("failed"))

  test("others tab: folder, depth and sub directory selection"):
    val e = env(tmp())
    val base = e.outputs.getParent.resolve("elsewhere")
    png(base.resolve("top.png"), None, 1000)
    png(base.resolve("a/mid.png"), None, 2000)
    png(base.resolve("a/b/deep.png"), None, 3000)
    val vm = new ImageBrowserTabVM(BrowserTabs.resolve("Others", e.outputs, e.settings), e)
    vm.refresh(); assertEquals(vm.filtered, Vector.empty) // no folder yet
    vm.othersDir = base.toString
    vm.othersDepth = 0; vm.refresh(); assertEquals(vm.filtered.map(_.fileName), Vector("top.png"))
    vm.othersDepth = 1; vm.refresh(); assertEquals(vm.filtered.size, 2)
    vm.othersDepth = -1; vm.refresh(); assertEquals(vm.filtered.size, 3)
    vm.subdir = Some(base.resolve("a")); vm.refresh(); assertEquals(vm.filtered.size, 2)
    assertEquals(e.subdirectories(base).map(_.getFileName.toString), Vector("a"))
    e.store.addSavedDir(base.toString, 2)
    e.store.addSavedDir(base.toString, 3) // replaces
    assertEquals(env(e.outputs.getParent).store.savedDirs.map(_.depth), Seq(3))
    e.store.removeSavedDir(base.toString)
    assertEquals(e.store.savedDirs, Nil)

  test("generation info rows follow the configured order; additional rows hold the rest"):
    val meta = ImageMeta(Some(text("a cat", 5)))
    val rows = BrowserInfo.formattedRows(meta, Seq("Prompt", "Seed", "Steps", "Missing"))
    assertEquals(rows, Vector("Prompt" -> "a cat", "Seed" -> "5", "Steps" -> "20"))
    val extra = BrowserInfo.additionalRows(meta, Seq("Steps", "Seed", "Sampler")).map(_._1)
    assertEquals(extra, Vector("CFG scale", "Size", "Model hash", "Model"))
    assertEquals(BrowserInfo.formattedRows(ImageMeta(None), BrowserSettings.DefaultInfoOrder), Vector.empty)
    assertEquals(BrowserInfo.sizeText(512), "512 B"); assertEquals(BrowserInfo.sizeText(2048), "2.0 KB"); assertEquals(BrowserInfo.sizeText(3 * 1048576), "3.00 MB")
    val r = ImageRecord(Path.of("/x/a.png"), 0, 2048, meta)
    assert(BrowserInfo.thumbnailTooltip(r, Some((512, 768))).contains("512 x 768  2.0 KB"))

  test("maintenance wrappers produce the Last message"):
    val (e, _) = fixture()
    val m = new ImageBrowserVM(e).maintenance
    assertEquals(m.rebuildCache(), "Exif cache rebuilt: 25 files read")
    assert(m.deleteZeroEntries().startsWith("Deleted 0"))
    assert(m.updateDirectoryNames("", "x", true, true, true).contains("need"))
    assert(m.updateDirectoryNames("a", "b", false, false, false).contains("Select at least one"))
    assert(m.recreateHash().startsWith("Hash recreated for 0"))
    assert(m.reapplyRanking().startsWith("Ranking reapplied for 0"))
    assert(m.rebuildRanking().contains("0 images"))
    assertEquals(new ImageBrowserVM(e).specs.size, 9)

  extension [A](a: A) private def tap(f: A => Unit): A = { f(a); a }
