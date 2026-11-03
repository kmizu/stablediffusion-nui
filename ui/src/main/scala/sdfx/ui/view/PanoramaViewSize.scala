package sdfx.ui.view

/** Bounds image allocations from a potentially unlaid-out or extremely narrow viewport. */
object PanoramaViewSize:
  val MaxSide = 4096
  val MaxPixels = 1600L * 4096L
  def forViewport(viewportWidth: Double, viewportHeight: Double, width: Int = 1600): Either[String, (Int, Int)] =
    if !viewportWidth.isFinite || !viewportHeight.isFinite || viewportWidth <= 0 || viewportHeight <= 0 || width < 2 || width > MaxSide then
      Left("The view has invalid dimensions")
    else
      val height = width.toDouble * (viewportHeight / viewportWidth)
      if !height.isFinite || height > MaxSide || width.toDouble * math.max(2.0, height) > MaxPixels then
        Left("The view is too tall to save safely")
      else Right((width, math.max(2, height.toInt)))
