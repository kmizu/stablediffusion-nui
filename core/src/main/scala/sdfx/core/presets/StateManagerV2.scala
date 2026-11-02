package sdfx.core.presets

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import scala.util.control.NonFatal

/** Source-derived, read-only interpretation of one upstream State Manager 2.0 record. */
final case class StateManagerV2Entry(
  sourceId: String,
  mode: PresetMode,
  sourceName: Option[String],
  groups: Set[String],
  mapped: Map[FieldKey, PresetAtom],
  unmapped: Vector[String],
  originalEntry: RawJson,
  originalDefaults: RawJson
)

object StateManagerV2:
  private def error(path: String, message: String): PresetError =
    PresetError(Vector(PresetIssue("state-manager-v2", path, message)))
  private def obj(raw: RawJson, limits: PresetLimits): Either[PresetError, Map[String, RawJson]] =
    PresetJson.objectMembers(raw, limits).map(_.map(m => m.name -> m.value).toMap)
  private def required(map: Map[String, RawJson], name: String, path: String): Either[PresetError, RawJson] =
    map.get(name).toRight(error(path, s"Missing $name"))
  private def text(raw: RawJson, path: String, limits: PresetLimits): Either[PresetError, String] =
    PresetJson.scalarAtom(raw, limits).flatMap {
      case Some(PresetAtom.Text(s)) => Right(s)
      case _ => Left(error(path, "Expected text"))
    }
  private def groupNames(raw: RawJson, path: String): Either[PresetError, Set[String]] =
    try ujson.read(raw.text) match
      case array: ujson.Arr =>
        val strings = array.value.collect { case ujson.Str(s) => s }.toVector
        if strings.size != array.value.size then Left(error(path, "Expected a list of group names")) else Right(strings.toSet)
      case _ => Left(error(path, "Expected a list of group names"))
    catch case NonFatal(_) => Left(error(path, "Invalid groups"))

  private enum Kind:
    case Text, Integer, Decimal, Flag
  private def key(a: String, b: String): FieldKey = FieldKey(Vector(a, b))
  private def mappings(mode: String): Map[String, (FieldKey, Kind)] =
    val root = s"$mode/"
    val seed = s"customscript/seed.py/$mode/"
    Map(
      root + "Prompt" -> (key("generation", "prompt"), Kind.Text),
      root + "Negative prompt" -> (key("generation", "negativePrompt"), Kind.Text),
      root + "Sampling method" -> (key("generation", "sampler"), Kind.Text),
      root + "Sampling steps" -> (key("generation", "steps"), Kind.Integer),
      root + "CFG Scale" -> (key("generation", "cfgScale"), Kind.Decimal),
      root + "Width" -> (key("generation", "width"), Kind.Integer),
      root + "Height" -> (key("generation", "height"), Kind.Integer),
      root + "Batch count" -> (key("generation", "batchCount"), Kind.Integer),
      root + "Batch size" -> (key("generation", "batchSize"), Kind.Integer),
      seed + "Seed" -> (key("generation", "seed"), Kind.Integer),
      seed + "Variation seed" -> (key("generation", "subseed"), Kind.Integer),
      seed + "Variation strength" -> (key("generation", "subseedStrength"), Kind.Decimal),
      seed + "Extra" -> (key("generation", "variationEnabled"), Kind.Flag),
      "quickSettings/sd_model_checkpoint" -> (key("model", "checkpoint"), Kind.Text),
      "quickSettings/sd_vae" -> (key("model", "vae"), Kind.Text),
      "quickSettings/CLIP_stop_at_last_layers" -> (key("model", "clipSkip"), Kind.Integer))

  private def compatible(value: PresetAtom, kind: Kind): Option[PresetAtom] = (value, kind) match
    case (v: PresetAtom.Text, Kind.Text) => Some(v)
    case (v: PresetAtom.Integer, Kind.Integer) => Some(v)
    case (v: PresetAtom.Decimal, Kind.Decimal) => Some(v)
    case (PresetAtom.Integer(v), Kind.Decimal) => Some(PresetAtom.Decimal(BigDecimal(v)))
    case (v: PresetAtom.Flag, Kind.Flag) => Some(v)
    case _ => None

  /** Decode the gzip byte stream of a shared history.txt / exported IndexedDB payload.
    * Nothing is written; unknown settings and the exact validated original objects remain available to callers.
    */
  def decode(bytes: Array[Byte], limits: PresetLimits = PresetLimits()): Either[PresetError, Vector[StateManagerV2Entry]] =
    if bytes == null || limits.maxBytes < 1 || limits.maxBytes == Int.MaxValue || bytes.length > limits.maxBytes then
      return Left(error("$", "Compressed input exceeds the size limit"))
    val unpacked = try
      val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
      try in.readNBytes(limits.maxBytes + 1) finally in.close()
    catch case NonFatal(_) => return Left(error("$", "Invalid or truncated gzip data"))
    if unpacked.length > limits.maxBytes then return Left(error("$", "Expanded input exceeds the size limit"))
    for
      source <- PresetJson.readSource(unpacked, limits)
      root <- obj(source, limits)
      defaultsRaw <- required(root, "defaults", "$")
      defaults <- obj(defaultsRaw, limits)
      parsedDefaults <- defaults.toVector.foldLeft[Either[PresetError, Map[String, (RawJson, Map[String, RawJson])]]](Right(Map.empty)) {
        case (previous, (id, raw)) => for
          result <- previous
          fields <- obj(raw, limits)
          _ <- Either.cond(fields.size <= limits.maxFieldsPerRecord, (), error("$.defaults", "Too many default settings"))
        yield result.updated(id, (raw, fields))
      }
      entriesRaw <- required(root, "entries", "$")
      entries <- obj(entriesRaw, limits)
      _ <- Either.cond(entries.size <= limits.maxRecords, (), error("$.entries", "Too many entries"))
      converted <- entries.toVector.sortBy(_._1).foldLeft[Either[PresetError, (Vector[StateManagerV2Entry], Int)]](Right((Vector.empty, 0))) {
        case (previous, (id, raw)) => for
          result <- previous
          entry <- decodeEntry(id, raw, parsedDefaults, limits)
          count = entry.mapped.size + entry.unmapped.size
          _ <- Either.cond(count.toLong + result._2 <= limits.maxNodes, (), error("$.entries", "Too many expanded settings"))
        yield (result._1 :+ entry, result._2 + count)
      }
    yield converted._1

  private def decodeEntry(id: String, raw: RawJson, defaults: Map[String, (RawJson, Map[String, RawJson])], limits: PresetLimits): Either[PresetError, StateManagerV2Entry] =
    val path = s"$$.entries.$id"
    for
      entry <- obj(raw, limits)
      versionRaw <- required(entry, "saveVersion", path)
      version <- text(versionRaw, path, limits)
      _ <- Either.cond(version == "2.0", (), error(path, s"Unsupported save version: $version"))
      modeRaw <- required(entry, "type", path)
      modeName <- text(modeRaw, path, limits)
      mode <- modeName match
        case "txt2img" => Right(PresetMode.Txt2Img)
        case "img2img" => Right(PresetMode.Img2Img)
        case _ => Left(error(path, s"Unsupported generation mode: $modeName"))
      sourceName <- entry.get("name").fold[Either[PresetError, Option[String]]](Right(None)) { value =>
        PresetJson.scalarAtom(value, limits).flatMap {
          case Some(PresetAtom.Text(name)) => Right(Some(name))
          case None if value.text == "null" => Right(None)
          case _ => Left(error(path, "Expected a text name"))
        }
      }
      defaultsKeyRaw <- required(entry, "defaults", path)
      defaultsKey <- text(defaultsKeyRaw, path, limits)
      defaultPair <- defaults.get(defaultsKey).toRight(error(path, s"Missing referenced defaults: $defaultsKey"))
      (originalDefaults, defaultFields) = defaultPair
      changesRaw <- required(entry, "componentSettings", path)
      changes <- obj(changesRaw, limits)
      quickRaw <- required(entry, "quickSettings", path)
      quick <- obj(quickRaw, limits)
      _ <- Either.cond(changes.size.toLong + quick.size <= limits.maxFieldsPerRecord, (), error(path, "Too many changed settings"))
      groups <- entry.get("groups").fold[Either[PresetError, Set[String]]](Right(Set.empty))(groupNames(_, path))
      relevantDefaults = defaultFields.filter((name, _) => name.startsWith(s"$modeName/") || name.contains(s"/$modeName/"))
      effective = (relevantDefaults ++ changes) ++ quick.map((name, value) => s"quickSettings/$name" -> value)
      fieldMappings = mappings(modeName)
      converted <- effective.toVector.sortBy(_._1).foldLeft[Either[PresetError, (Map[FieldKey, PresetAtom], Vector[String])]](Right((Map.empty, Vector.empty))) {
        case (previous, (name, value)) => previous.flatMap { (mapped, unknown) =>
          fieldMappings.get(name) match
            case None => Right((mapped, unknown :+ name))
            case Some((target, kind)) => PresetJson.scalarAtom(value, limits).map { atom =>
              atom.flatMap(compatible(_, kind)) match
                case Some(v) => (mapped.updated(target, v), unknown)
                case None => (mapped, unknown :+ name)
            }
        }
      }
    yield StateManagerV2Entry(id, mode, sourceName, groups, converted._1, converted._2, raw, originalDefaults)
