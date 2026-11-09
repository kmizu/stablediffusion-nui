package sdfx.plugins

import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.plugins.api.*
import sdfx.core.plugins.api.Control.*
import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import javax.imageio.{IIOImage, ImageIO, ImageTypeSpecifier, ImageWriteParam}
import javax.imageio.metadata.{IIOMetadata, IIOMetadataNode}

/** GIF frame extraction and assembly with the JDK's ImageIO (own implementation; no upstream code). */
object GifFrames:
  final case class Frame(image: BufferedImage, delayCs: Int)

  /** Reads all frames composited onto the logical screen (handles partial frames and the common disposal modes). */
  def read(path: Path, maxFrames: Int): Vector[Frame] =
    val in = ImageIO.createImageInputStream(path.toFile)
    require(in != null, s"Cannot open $path")
    val readers = ImageIO.getImageReadersByFormatName("gif")
    require(readers.hasNext, "No GIF reader available")
    val r = readers.next(); r.setInput(in)
    try
      val n = r.getNumImages(true)
      require(n >= 1, "The GIF has no frames")
      val sm = r.getStreamMetadata
      val root = sm.getAsTree(sm.getNativeMetadataFormatName).asInstanceOf[IIOMetadataNode]
      def attr(node: org.w3c.dom.Node, k: String, d: Int) = Option(node.getAttributes.getNamedItem(k)).map(_.getNodeValue.toInt).getOrElse(d)
      val lsd = Option(root.getElementsByTagName("LogicalScreenDescriptor").item(0))
      val w = lsd.map(attr(_, "logicalScreenWidth", 0)).filter(_ > 0).getOrElse(r.getWidth(0))
      val h = lsd.map(attr(_, "logicalScreenHeight", 0)).filter(_ > 0).getOrElse(r.getHeight(0))
      var canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
      val out = Vector.newBuilder[Frame]
      var i = 0
      while i < math.min(n, maxFrames) do
        val img = r.read(i)
        val md = r.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0")
        def child(name: String) = (0 until md.getChildNodes.getLength).map(md.getChildNodes.item).find(_.getNodeName == name)
        val desc = child("ImageDescriptor")
        val (x, y) = (desc.map(attr(_, "imageLeftPosition", 0)).getOrElse(0), desc.map(attr(_, "imageTopPosition", 0)).getOrElse(0))
        val gce = child("GraphicControlExtension")
        val delay = gce.map(attr(_, "delayTime", 10)).getOrElse(10)
        val disposal = gce.flatMap(g => Option(g.getAttributes.getNamedItem("disposalMethod")).map(_.getNodeValue)).getOrElse("none")
        val before = canvas
        val g = canvas.createGraphics(); g.drawImage(img, x, y, null); g.dispose()
        val flat = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val fg = flat.createGraphics(); fg.setColor(java.awt.Color.WHITE); fg.fillRect(0, 0, w, h); fg.drawImage(canvas, 0, 0, null); fg.dispose()
        out += Frame(flat, math.max(2, delay))
        canvas = disposal match
          case "restoreToBackgroundColor" =>
            val c = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val cg = c.createGraphics(); cg.drawImage(before, 0, 0, null); cg.clearRect(x, y, img.getWidth, img.getHeight); cg.dispose(); c
          case _ => canvas
        i += 1
      out.result()
    finally { r.dispose(); in.close() }

  /** Writes an endlessly looping GIF; delay in 1/100 s per frame. */
  def write(path: Path, frames: Seq[Frame]): Unit =
    require(frames.nonEmpty, "No frames to write")
    val w = ImageIO.getImageWritersByFormatName("gif").next()
    val os = ImageIO.createImageOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))
    w.setOutput(os); w.prepareWriteSequence(null)
    try
      for (f, i) <- frames.zipWithIndex do
        val meta: IIOMetadata = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(f.image), null)
        val fmt = meta.getNativeMetadataFormatName
        val root = meta.getAsTree(fmt).asInstanceOf[IIOMetadataNode]
        def node(name: String): IIOMetadataNode =
          (0 until root.getLength).map(root.item(_).asInstanceOf[IIOMetadataNode]).find(_.getNodeName == name).getOrElse {
            val n = new IIOMetadataNode(name); root.appendChild(n); n }
        val gce = node("GraphicControlExtension")
        gce.setAttribute("disposalMethod", "none"); gce.setAttribute("userInputFlag", "FALSE")
        gce.setAttribute("transparentColorFlag", "FALSE"); gce.setAttribute("delayTime", f.delayCs.toString); gce.setAttribute("transparentColorIndex", "0")
        if i == 0 then
          val apps = node("ApplicationExtensions"); val app = new IIOMetadataNode("ApplicationExtension")
          app.setAttribute("applicationID", "NETSCAPE"); app.setAttribute("authenticationCode", "2.0")
          app.setUserObject(Array[Byte](1, 0, 0)); apps.appendChild(app)
        meta.setFromTree(fmt, root)
        w.writeToSequence(new IIOImage(f.image, null, meta), null)
      w.endWriteSequence()
    finally { os.close(); w.dispose() }

class Gif2GifPlugin extends Plugin:
  def id = "gif2gif"
  def name = "gif2gif"
  def kind = PluginKind.Script
  override def tabs: Set[Tab] = Set(Tab.Img2Img)
  override def description = "Runs img2img on every frame of a GIF (the img2img prompt, strength and size apply) and writes the result as " +
    "a looping GIF with the original frame delays into the folder you give. Every processed frame is also returned. Existing files are never overwritten."
  override def controls: Seq[Control] = Seq(
    Text("gif", "Input GIF file", ""),
    Text("folder", "Output folder (created if missing)", ""),
    Text("name", "Output file name (no extension)", "gif2gif"),
    IntField("max_frames", "Max frames", 1, 512, 48),
    IntField("every", "Use every n-th frame", 1, 32, 1),
    Check("fixed_seed", "Same seed for every frame", true)
  )

  override def run(p0: GenerationParams, v: Values, ctx: PluginContext): JobOutput =
    val src = v.str("gif").trim; val folder = v.str("folder").trim; val stem = v.str("name", "gif2gif").trim
    require(src.nonEmpty && folder.nonEmpty, "An input GIF and an output folder are required")
    require(stem.matches("[A-Za-z0-9._ -]+") && !stem.contains(".."), "File name may only contain letters, digits, space, . _ -")
    val gifPath = Paths.get(src)
    require(Files.isRegularFile(gifPath) && Files.size(gifPath) <= 64L * 1024 * 1024, s"Cannot read $src (missing or larger than 64 MiB)")
    val frames = GifFrames.read(gifPath, v.int("max_frames", 48) * v.int("every", 1)).zipWithIndex.collect { case (f, i) if i % v.int("every", 1) == 0 => f }
    val dir = Paths.get(folder).toAbsolutePath.normalize(); Files.createDirectories(dir)
    val seed = if p0.seed >= 0 then p0.seed else scala.util.Random.nextInt(Int.MaxValue).toLong
    val outs = scala.collection.mutable.ArrayBuffer[OutputImage]()
    val done = scala.collection.mutable.ArrayBuffer[GifFrames.Frame]()
    var running = true; val it = frames.zipWithIndex.iterator
    val base = p0.img2img.getOrElse(Img2ImgParams(initImage = ""))
    while running && it.hasNext do
      val (f, i) = it.next()
      ctx.status(s"gif2gif ${i + 1}/${frames.size}", i, frames.size)
      val tmp = Support.writeTemp(ctx, f.image)
      try
        val q = p0.copy(width = f.image.getWidth, height = f.image.getHeight, batchSize = 1, nIter = 1,
          seed = if v.bool("fixed_seed", true) then seed else -1L, img2img = Some(base.copy(initImage = tmp.toString)),
          extra = p0.extra + ("Script" -> "gif2gif") + ("gif2gif frame" -> s"${i + 1}/${frames.size}"))
        Support.step(ctx)(ctx.generate(q)) match
          case Some(o) =>
            outs ++= o
            done += GifFrames.Frame(ctx.readImage(o.head.path), f.delayCs)
          case None => running = false
      finally Support.deleteQuietly(tmp)
    if done.nonEmpty then
      val name = Iterator.from(0).map(n => if n == 0 then stem else s"$stem-$n").find(s => !Files.exists(dir.resolve(s + ".gif"))).get
      GifFrames.write(dir.resolve(name + ".gif"), done.toSeq)
      ctx.log(s"gif2gif: ${dir.resolve(name + ".gif")} (${done.size} frames)")
    JobOutput(outs.toSeq)
