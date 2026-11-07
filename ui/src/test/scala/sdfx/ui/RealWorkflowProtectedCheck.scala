package sdfx.ui

import sdfx.core.config.{AppHome, ModelDirs, Settings}
import sdfx.core.model.{GenerationParams, Img2ImgParams}
import sdfx.core.png.{ImageScrambleSession, PngMetadata, ScrambledPng}
import sdfx.core.plugins.api.Values
import sdfx.engine.{AppServices, EngineLaunch, EngineTimeouts, Job}
import sdfx.plugins.{Img2ImgWorkflowPlugin, StandardPlugins}
import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import scala.concurrent.Await
import scala.concurrent.duration.*

/** Actual protected worker execution, without a visible Stage or desktop input. */
object RealWorkflowProtectedCheck:
  def main(args: Array[String]): Unit =
    require(args.length == 5, "python checkpoint source-root output side")
    val python = Path.of(args(0)).toAbsolutePath
    val checkpoint = Path.of(args(1)).toAbsolutePath
    val source = Path.of(args(2)).toAbsolutePath
    val output = Path.of(args(3)).toAbsolutePath
    val side = args(4).toInt
    Files.createDirectories(output)
    val home = AppHome(Files.createTempDirectory(output, "home-"))
    val services = new AppServices(home, Settings(pythonExe = Some(python.toString), vramMode = "model_offload",
      modelDirs = ModelDirs(checkpoints = Seq(checkpoint.getParent.toString)), outputDir = output.resolve("images").toString),
      dryRun = false, builtins = StandardPlugins.withOwnAxes, persistSettings = false, installShutdownHook = false,
      launchOverride = Some(() => EngineLaunch(Seq(python.toString, "-m", "sdfx"), source.resolve("python"),
        Map("PYTHONPATH" -> source.resolve("python").toString, "USE_TF" -> "0"))), timeouts = EngineTimeouts(load = 10.minutes))
    val session = new ImageScrambleSession; session.unlock("workflow test secret")
    val img = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until side; x <- 0 until side do
      val middle = x >= side / 4 && x <= side * 3 / 4 && y >= side / 4 && y <= side * 3 / 4
      img.setRGB(x, y, if middle then 0x2255aa else 0xffffff)
    val input = output.resolve("input-protected.png")
    ScrambledPng.write(img, input, Nil, Some("workflow test secret"))
    val plugin = new Img2ImgWorkflowPlugin
    val values = Values(Values.defaults(plugin.controls).map ++ Map("quick_mask" -> "White", "mask_threshold" -> "1", "return_mask" -> "true"))
    try
      services.start()
      services.loadCheckpoint(checkpoint.toString, remember = false)
      val p = GenerationParams(prompt = "anime portrait, looking at viewer", negativePrompt = "lowres, blurry", width = side,
        height = side, seed = 2468, steps = 12, cfgScale = 5, samplerName = "Euler",
        img2img = Some(Img2ImgParams(input.toString, denoisingStrength = .65)))
      val job = Job(p).copy(script = Some(plugin -> values), imageSecret = session.snapshot)
      val result = Await.result(services.queue.submit(job), 10.minutes)
      require(result.images.size == 2)
      val saved = result.images.head.path
      val maskPath = result.images.last.path
      require(PngMetadata.readTexts(maskPath).contains("Encrypt"))
      require(PngMetadata.readParameters(maskPath).exists(_.contains("Img2Img Workflow output")))
      val decodedMask = ScrambledPng.read(maskPath, Some("workflow test secret")).image
      require((decodedMask.getRGB(0, 0) & 0xffffff) == 0xffffff)
      require((decodedMask.getRGB(side / 2, side / 2) & 0xffffff) == 0)
      require(scala.util.Try(ScrambledPng.read(maskPath, None)).isFailure)
      require(PngMetadata.readTexts(saved).contains("Encrypt"))
      val decoded = ScrambledPng.read(saved, Some("workflow test secret")).image
      require(decoded.getWidth == side && decoded.getHeight == side)
      require(scala.util.Try(ScrambledPng.read(saved, None)).isFailure)
      require(PngMetadata.readParameters(saved).exists(_.contains("Img2Img Workflow mask")))
      val tmp = home.tmpDir(services.settings)
      val leftovers = scala.util.Using.resource(Files.list(tmp))(_.toArray.map(_.asInstanceOf[Path]).toSeq)
      require(leftovers.isEmpty, s"temporary inputs were not cleaned: $leftovers")
      val preview = output.resolve("protected-decoded-for-review.png")
      javax.imageio.ImageIO.write(decoded, "png", preview.toFile)
      println(s"REAL_WORKFLOW_PROTECTED_OK $saved $preview")
    finally services.shutdown()
