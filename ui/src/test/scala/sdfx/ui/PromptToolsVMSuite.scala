package sdfx.ui

import javafx.beans.property.SimpleStringProperty
import sdfx.core.prompttools.*
import sdfx.ui.vm.PromptToolsVM

import java.nio.file.Files
import scala.util.Random

class PromptToolsVMSuite extends munit.FunSuite:
  private def make(seed: Long = 1) =
    val root = Files.createTempDirectory("prompt-tools-vm-")
    val svc = new PromptToolsService(root.resolve("pt"))
    val p = new SimpleStringProperty("")
    val n = new SimpleStringProperty("")
    (new PromptToolsVM(svc, () => (p, n), new Random(seed)), svc, p, n)

  test("insert, remove and negative routing"):
    val (vm, _, p, n) = make()
    assert(vm.insert("masterpiece, best quality", negative = false))
    assertEquals(p.get, "masterpiece, best quality")
    assert(!vm.insert("masterpiece", negative = false), "duplicates change nothing")
    assert(vm.insert("lowres", negative = true))
    assertEquals(n.get, "lowres")
    assertEquals(p.get, "masterpiece, best quality", "positive field is untouched")
    assert(vm.remove("best quality", negative = false))
    assertEquals(p.get, "masterpiece")
    assert(!vm.remove("absent", negative = false))
    assert(vm.insert("masterpiece", negative = false, skipDuplicates = false))
    assertEquals(p.get, "masterpiece, masterpiece")

  test("random group insertion uses leaves of the group only"):
    val (vm, _, p, _) = make(7)
    val group: TagNode.Group = TagNode.Group("hair",Vector(TagNode.Leaf("a", "black hair"),
      TagNode.Group("multi", Vector(TagNode.Leaf("b", "gradient hair, glossy")))))
    val picked = (1 to 30).flatMap { _ => p.set(""); vm.insertRandom(group, negative = false) }.toSet
    assertEquals(picked, Set("black hair", "gradient hair, glossy"))
    assert(vm.insertRandom(TagNode.Group("e", Vector.empty), negative = false).isEmpty)

  test("saved prompts: save the editor text, auto names, use into the right field"):
    val (vm, svc, p, n) = make()
    assertEquals(vm.saveText("", "1girl, solo", negative = false), Right("prompt 1"))
    assertEquals(vm.saveText("bad", "blurry", negative = true), Right("bad"))
    assert(vm.saveText("x", "   ", negative = false).isLeft, "empty text is refused")
    assert(vm.saveText("bad\nname", "t", negative = false).isLeft, "control characters in names are refused")
    assertEquals(vm.use("bad"), Right(true))
    assertEquals(n.get, "blurry")
    assertEquals(p.get, "")
    assertEquals(vm.use("prompt 1"), Right(false))
    assertEquals(p.get, "1girl, solo")
    assert(vm.use("missing").isLeft)
    assertEquals(svc.library.names, Vector("prompt 1", "bad"))
    assertEquals(vm.rename("bad", "negative base"), Right(()))
    assert(vm.rename("prompt 1", "negative base").isLeft)
    assertEquals(vm.delete("negative base"), Right(()))
    assertEquals(svc.library.names, Vector("prompt 1"))
    p.set("from the box")
    assertEquals(vm.saveCurrent("", negative = false), Right("prompt 2"))
    p.set(""); n.set("")
    assert(vm.saveCurrent("z", negative = true).isLeft, "an empty negative prompt cannot be saved")

  test("history and favourites restore both fields"):
    val (vm, svc, p, n) = make()
    vm.recordUsed("a cat", "blurry"); vm.recordUsed("a dog", "")
    vm.recordUsed("a cat", "blurry")
    assertEquals(svc.history.history.map(_.prompt), Vector("a cat", "a dog"))
    p.set("fav"); n.set("fav neg")
    assertEquals(vm.favoriteCurrent("mine"), Right(()))
    p.set("other"); n.set("")
    vm.restore(svc.history.favorites.head)
    assertEquals((p.get, n.get), ("fav", "fav neg"))
    p.set(""); n.set("")
    assert(vm.favoriteCurrent("").isLeft)

  test("tag editor operations rewrite the field"):
    val (vm, svc, p, _) = make()
    p.set("a, (b, c:1.2), <lora:x:1>, A, d")
    assertEquals(vm.tags(false), Vector("a", "(b, c:1.2)", "<lora:x:1>", "A", "d"))
    vm.weightTag(false, 0, 0.1)
    assertEquals(p.get, "(a:1.1), (b, c:1.2), <lora:x:1>, A, d")
    vm.weightTag(false, 2, 0.1)
    assertEquals(vm.tags(false)(2), "<lora:x:1>")
    vm.moveTag(false, 4, 0)
    assertEquals(vm.tags(false).head, "d")
    vm.removeTag(false, 0)
    vm.removeTag(false, 99)
    assertEquals(vm.tags(false).size, 4)
    p.set("a, A, b, a")
    assertEquals(vm.dedupe(false), 2)
    assertEquals(p.get, "a, b")
    svc.setBlacklist(Seq("B"))
    assertEquals(vm.applyBlacklist(false), 1)
    assertEquals(p.get, "a")
    assertEquals(vm.applyBlacklist(false), 0)
