package sdfx.core.presets

/** Search name and selected saved text fields using case-insensitive, comma-separated AND terms. */
object HistorySearch:
  private val keys = Set("generation.prompt", "generation.negativePrompt", "generation.sampler", "model.checkpoint")
  def matches(record: PresetRecord, query: String): Boolean =
    val terms = Option(query).getOrElse("").split(",").toVector.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty)
    val values = record.fields.toVector.collect { case (key, PresetAtom.Text(text)) if keys(key.render) => text }
    val searchable = (record.name +: values).map(_.toLowerCase(java.util.Locale.ROOT))
    terms.forall(term => searchable.exists(_.contains(term)))
