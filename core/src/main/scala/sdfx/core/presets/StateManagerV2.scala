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

  /** Export only original imported source records; current native edits are not projected onto them. */
  def exportOriginal(records: Vector[PresetRecord], limits: PresetLimits = PresetLimits()): Either[PresetError, Array[Byte]] =
    if records.isEmpty || records.size > limits.maxRecords then return Left(error("$", "Select a bounded, non-empty set of imported records"))
    type Exported = (Map[String, RawJson], Map[String, RawJson])
    val parts = records.foldLeft[Either[PresetError, Exported]](Right((Map.empty, Map.empty))) { (acc, record) =>
      for
        previous <- acc
        source <- record.source.filter(_.format == "state-manager-v2").toRight(error(record.name, "Only imported State Manager V2 originals can be exported"))
        mode <- source.mode.toRight(error(record.name, "Original mode is missing"))
        original <- obj(source.originalFields, limits)
        _ <- Either.cond(original.keySet == Set("entry", "defaults"), (), error(record.name, "Invalid original metadata"))
        entryRaw <- required(original, "entry", record.name)
        defaultsRaw <- required(original, "defaults", record.name)
        entry <- obj(entryRaw, limits)
        createdRaw <- required(entry, "createdAt", record.name)
        created <- PresetJson.scalarAtom(createdRaw, limits).flatMap {
          case Some(PresetAtom.Integer(n)) if n >= 0 => Right(n.toString)
          case _ => Left(error(record.name, "Original timestamp key is unavailable"))
        }
        defaultsKeyRaw <- required(entry, "defaults", record.name)
        defaultsKey <- text(defaultsKeyRaw, record.name, limits)
        digest = java.security.MessageDigest.getInstance("SHA-256").digest(
          (mode.toString + "\u0000" + created + "\u0000" + entryRaw.text + "\u0000" + defaultsRaw.text).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        buffer = java.nio.ByteBuffer.wrap(digest)
        expectedId = new java.util.UUID(buffer.getLong(), buffer.getLong())
        _ <- Either.cond(record.id == expectedId, (), error(record.name, "Original timestamp key cannot be verified"))
        (defaults, entries) = previous
        _ <- Either.cond(!entries.contains(created), (), error(record.name, "Duplicate original timestamp"))
        _ <- Either.cond(defaults.get(defaultsKey).forall(_ == defaultsRaw), (), error(record.name, "Conflicting defaults for the same key"))
      yield (defaults.updated(defaultsKey, defaultsRaw), entries.updated(created, entryRaw))
    }

    parts.flatMap { (defaults, entries) =>
      val lowerBound = (defaults.valuesIterator ++ entries.valuesIterator).map(_.text.length.toLong).sum
      def members(values: Map[String, RawJson]): String = values.toVector.sortBy(_._1).map { (key, value) =>
        ujson.Str(key).render() + ":" + value.text
      }.mkString(",")
      if limits.maxBytes < 1 || lowerBound > limits.maxBytes then Left(error("$", "Export exceeds the size limit"))
      else
        val bytes = s"{\"defaults\":{${members(defaults)}},\"entries\":{${members(entries)}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        if bytes.length > limits.maxBytes then Left(error("$", "Export exceeds the size limit"))
        else
          try
            val out = new java.io.ByteArrayOutputStream()
            val gzip = new java.util.zip.GZIPOutputStream(out)
            try gzip.write(bytes) finally gzip.close()
            val compressed = out.toByteArray
            if compressed.length > limits.maxBytes then Left(error("$", "Compressed export exceeds the size limit"))
            else decode(compressed, limits).map(_ => compressed)
          catch case NonFatal(_) => Left(error("$", "Cannot create gzip export"))
    }

  /** Write a new file only; never overwrite an existing target. */
  def exportOriginalToNewFile(records: Vector[PresetRecord], destination: java.nio.file.Path,
      limits: PresetLimits = PresetLimits()): Either[PresetError, Unit] =
    exportOriginal(records, limits).flatMap { bytes =>
      try
        val target = destination.toAbsolutePath.normalize()
        val parent = target.getParent
        if parent == null || !java.nio.file.Files.isDirectory(parent) then Left(error("$", "Export directory is unavailable"))
        else
          val temp = java.nio.file.Files.createTempFile(parent, ".state-export-", ".tmp")
          try
            val channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE,
              java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            try
              val buffer = java.nio.ByteBuffer.wrap(bytes)
              while buffer.hasRemaining do channel.write(buffer)
              channel.force(true)
            finally channel.close()
            // A hard link creates the final name only if it does not already exist.
            // The temporary file is in the same directory, so a completed file is exposed at once.
            java.nio.file.Files.createLink(target, temp)
            Right(())
          finally try java.nio.file.Files.deleteIfExists(temp) catch case NonFatal(_) => ()
      catch case NonFatal(e) => Left(error("$", "Export failed: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    }
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
      "quickSettings/CLIP_stop_at_last_layers" -> (key("model", "clipSkip"), Kind.Integer)) ++ (if mode == "img2img" then Map(root + "Denoising strength" -> (key("generation", "denoisingStrength"), Kind.Decimal)) else Map.empty)

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
