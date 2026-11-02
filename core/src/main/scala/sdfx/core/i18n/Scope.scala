package sdfx.core.i18n

/** What a scoped translation can see of the element that is being translated: its own identity and its ancestors.
  * The JavaFX layer adapts nodes to this trait, so the matching rules are testable without a toolkit.
  */
trait ScopeNode:
  def nodeId: String
  def styleClasses: Set[String]
  /** Simple class names from the node's own class up to its superclasses (`Button`, `ButtonBase`, `Labeled`, ...). */
  def typeNames: Seq[String]
  def parentNode: Option[ScopeNode]

/** A small CSS selector subset for `##@<SELECTOR>##text` keys: type, `*`, `#id`, `.class` compounds joined by descendant
  * (space) or child (`>`) combinators, separated by commas. Anything else makes the whole selector invalid (the rule is
  * dropped with a warning) instead of matching something unintended.
  */
final class Selector private (private val alternatives: Vector[Vector[(Char, Selector.Compound)]]):
  /** True when `node` itself or one of its ancestors matches (the upstream `closest` semantics). */
  def closest(node: ScopeNode): Boolean =
    var n: Option[ScopeNode] = Some(node)
    var guard = 0
    while n.isDefined && guard < 512 do
      if matches(n.get) then return true
      n = n.get.parentNode
      guard += 1
    false

  def matches(node: ScopeNode): Boolean = alternatives.exists(chain => matchChain(chain, chain.size - 1, node))

  // chain(i)._1 is the combinator that joins compound i to the compound before it ('\u0000' for the first)
  private def matchChain(chain: Vector[(Char, Selector.Compound)], i: Int, node: ScopeNode): Boolean =
    val (comb, compound) = chain(i)
    if !compound.matches(node) then false
    else if i == 0 then true
    else comb match
      case '>' => node.parentNode.exists(matchChain(chain, i - 1, _))
      case _ =>
        var p = node.parentNode
        var guard = 0
        while p.isDefined && guard < 512 do
          if matchChain(chain, i - 1, p.get) then return true
          p = p.get.parentNode
          guard += 1
        false

object Selector:
  final case class Compound(tpe: Option[String], ids: Seq[String], classes: Seq[String]):
    def matches(n: ScopeNode): Boolean =
      tpe.forall(t => n.typeNames.contains(t)) && ids.forall(_ == n.nodeId) && classes.forall(n.styleClasses.contains)

  private val Ident = "[A-Za-z_\\-][A-Za-z0-9_\\-]*"
  private val CompoundRe = s"^(\\*|$Ident)?((?:[#.]$Ident)*)$$".r
  private val PartRe = s"([#.])($Ident)".r

  /** Parses a selector list; `Left` carries the reason it is unsupported. */
  def parse(text: String): Either[String, Selector] =
    val src = text.trim
    if src.isEmpty then Left("empty selector")
    else if src.length > 512 then Left("selector too long")
    else
      val alts = src.split(',').toVector.map(_.trim)
      val parsed = alts.map(parseChain)
      parsed.collectFirst { case Left(e) => e } match
        case Some(e) => Left(e)
        case None    => Right(new Selector(parsed.collect { case Right(c) => c }))

  private def parseChain(alt: String): Either[String, Vector[(Char, Compound)]] =
    if alt.isEmpty then Left("empty selector alternative")
    else
      val tokens = alt.replace(">", " > ").split(" +").toVector.filter(_.nonEmpty)
      var pendingComb = ' '
      var out = Vector.empty[(Char, Compound)]
      var error: Option[String] = None
      val it = tokens.iterator
      while error.isEmpty && it.hasNext do
        val tok = it.next()
        if tok == ">" then
          if out.isEmpty || pendingComb == '>' then error = Some("misplaced '>' in selector")
          else pendingComb = '>'
        else
          tok match
            case CompoundRe(tpe, rest) =>
              val parts = PartRe.findAllMatchIn(Option(rest).getOrElse("")).toVector
              val c = Compound(Option(tpe).filter(t => t.nonEmpty && t != "*"), parts.filter(_.group(1) == "#").map(_.group(2)), parts.filter(_.group(1) == ".").map(_.group(2)))
              out :+= ((if out.isEmpty then '\u0000' else pendingComb) -> c)
              pendingComb = ' '
            case _ => error = Some(s"unsupported selector syntax '$tok'")
      error match
        case Some(e) => Left(e)
        case None => if pendingComb == '>' || out.isEmpty then Left("dangling combinator in selector") else Right(out)
