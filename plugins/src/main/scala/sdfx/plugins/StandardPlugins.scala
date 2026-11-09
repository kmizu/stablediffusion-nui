package sdfx.plugins

import sdfx.core.plugins.api.{Plugin, XyzAxis}

/** Built-in plugins in a stable display order (scripts first, then extras, then AlwaysOn).
  * docs/PLUGINS.md maps each of them to the reForge built-in it rewrites.
  */
object StandardPlugins:
  def alwaysOn: Seq[Plugin] = Seq(
    new ConditioningHighresFixPlugin,
    new ControlNetFastloadPlugin,
    new ModelKeywordPlugin,
    new InpaintDifferencePlugin,
    new HiresFixTweaksPlugin,
    new Img2ImgHiresFixPlugin,
    new PromptFusionPlugin,
    new TokenDownsamplingPlugin,
    new ComposableLoraPlugin,
    new PngInfoInjectionPlugin,
    // guidance
    new FreeUPlugin, new DynamicThresholdingPlugin, new ApgPlugin, new MahiroPlugin, new RescaleCfgPlugin, new PagPlugin,
    new SagPlugin, new SemanticGuidancePlugin, new CharacteristicGuidancePlugin, new IncantationsPlugin, new SonarPlugin, new LlulPlugin, new AestheticGradientsPlugin,
    // structure / sampling
    new HyperTilePlugin, new AgentAttentionPlugin, new KohyaHrFixPlugin, new MultiDiffusionPlugin, new ModelSamplingPlugin, new LatentModifierPlugin,
    new RandomResolutionsPlugin,
    // image prompts / control
    new ReferencePlugin, new RevisionPlugin, new IpAdapterPlugin, new ControlLLLitePlugin, new PhotoMakerPlugin,
    // inpainting / post
    new SoftInpaintingPlugin, new FooocusInpaintPlugin, new FaceRestorePlugin, new AdetailerPlugin, new BatchFaceSwapPlugin, new NsfwCheckerPlugin, new AestheticScorePlugin,
    // memory
    new NeverOomPlugin, new TemperatureGuardPlugin,
    // prompt processing (extension index)
    new RandomizePlugin, new PromptFormatPlugin, new PromptFilterPlugin, new NegativePromptWeightPlugin,
    new NovelAIPromptPlugin, new StyleVariablesPlugin(), new LoraBlockWeightPlugin, new WeightGradientPlugin, new NegPipPlugin, new ClipIoPlugin, new CompilePlugin, new DeepCachePlugin,
    new NeutralPromptPlugin, new TokenizerPlugin, new VaeToolsPlugin,
    // sampling (extension index)
    // after the prompt processors, so CD Tuner reads <cdt:...> from the final prompt
    new DetailDaemonPlugin, new VectorscopeCcPlugin, new CdTunerPlugin, new LatentMirroringPlugin, new AsymmetricTilingPlugin,
    new AntiBurnPlugin, new ReSharpenPlugin, new ColorGradingPlugin, new CfgSchedulePlugin, new CadsPlugin,
    new SaveIntermediatePlugin, new GridNumbersPlugin
  )

  def scripts(extraAxes: () => Seq[XyzAxis], home: sdfx.core.config.AppHome = sdfx.core.config.AppHome.detect()): Seq[Plugin] = Seq(
    new XyzGridPlugin(extraAxes),
    new PromptMatrixPlugin, new PromptGalleryPlugin, new StyleSelectorPlugin, new PromptLibraryPlugin, new ScenarioLoaderPlugin, new PromptGeneratorPlugin,
    new StylePilePlugin,
    new StepsAnimationPlugin,
    new PromptsFromFilePlugin,
    new LoopbackPlugin,
    new SdUpscalePlugin,
    new OutpaintPlugin,
    new OutpaintMk2Plugin,
    new Img2ImgAltPlugin,
    new Img2ImgWorkflowPlugin,
    new LatentUpscalePlugin,
    new SvdPlugin,
    new Zero123Plugin,
    new CustomCodePlugin,
    new UltimateSdUpscalePlugin,
    new LoopbackScalerPlugin,
    new CorridorCrawlerPlugin,
    new OldSdFirstpasserPlugin,
    new AnimateDiffPlugin,
    new CnAnimationPlugin,
    // checkpoint tools (extension index)
    new ModelMergePlugin, new ModelConverterPlugin,
    // mask-driven img2img (extension index)
    new DepthMaskPlugin, new InpaintBackgroundPlugin, new SeamlessTilePlugin,
    // extension index: prompt and sequence scripts
    new DynamicPromptsPlugin, new OneButtonPromptPlugin, new BooruPromptPlugin, new CreaPromptPlugin(() => Some(home.root.resolve("creaprompt").resolve("csv"))), new TestMyPromptPlugin, new ScramblePromptsPlugin,
    new TweakWeightsPlugin, new LoraQueuePlugin, new BatchCheckpointsPromptsPlugin, new SeedTravelPlugin,
    new ShiftAttentionPlugin
  )

  def extras: Seq[Plugin] = Seq(
    new UpscaleExtrasPlugin,
    new FaceRestoreExtrasPlugin,
    new CaptionPlugin,
    new FlippedCopiesPlugin,
    new SplitOversizedPlugin,
    new AutosizedCropPlugin,
    new FocalCropPlugin,
    // image tools (extension index)
    new RemoveBackgroundPlugin, new PersonMaskPlugin, new PixelArtPlugin, new ColorEnhancePlugin, new FsrUpscalePlugin,
    new ColorCorrectionPlugin, new Waifu2xPlugin, new DepthAnythingPlugin, new DepthStereoPlugin, new DepthMeshPlugin, new QrCodePlugin, new QrVerifyPlugin, new NsfwCheckerExtrasPlugin, new AestheticScoreExtrasPlugin
  )

  /** `extraAxes` lets the host contribute axes of all loaded plugins (including these AlwaysOn ones). */
  def all(extraAxes: () => Seq[XyzAxis], home: sdfx.core.config.AppHome = sdfx.core.config.AppHome.detect()): Seq[Plugin] = scripts(extraAxes, home) ++ extras ++ alwaysOn

  /** Convenience: all standard plugins with the X/Y/Z grid seeing the standard AlwaysOn axes. */
  def withOwnAxes: Seq[Plugin] =
    lazy val ps: Seq[Plugin] = all(() => ps.flatMap(_.xyzAxes))
    ps
