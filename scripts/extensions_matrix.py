"""Coverage of the A1111 / reForge extension index ("Available" tab) in stablediffusion-fx.

Every entry of docs/extensions-index.json (snapshot of the official index) gets one status:
  done:<id>   built into stablediffusion-fx (plugin id, or `core:<feature>`)
  todo:<id>   accepted, planned under that plugin id
  na:<reason> does not apply to a standalone desktop app (reason codes below)

Run `python scripts/extensions_matrix.py` to regenerate docs/EXTENSIONS.md.
"""
import json
import os
import sys

NA_REASONS = {
    "webui-css": "styles / JavaScript tweaks of the Gradio web page; the JavaFX UI has its own theme and layout",
    "webui-ux": "Gradio page behaviour (tabs, buttons, dialogs); the desktop UI provides the equivalent natively",
    "webui-api": "adds HTTP routes to the A1111 server for other tools; this app's integration surface is MCP",
    "account": "needs a third-party account / paid API key and posts data to that service",
    "obsolete": "superseded: the feature is in the base app (A1111 >= 1.5) or the extension is deprecated",
    "external-app": "bridge to a specific external application (Photoshop, Krita, ComfyUI, ...)",
    "server-ops": "operating a shared web server (tunnels, TLS, multi-instance, kill buttons)",
}

# index -> status. Index numbers follow docs/extensions-index.json (official index order).
S = {
    0: "done:core:job-queue", 1: "todo:temporal_kit", 2: "todo:core:localization", 3: "todo:batch_checkpoints_prompts",
    4: "todo:kandinsky", 5: "todo:shap_e", 6: "todo:openpose_editor", 7: "todo:segment_anything",
    8: "todo:aesthetic_gradients", 9: "todo:train_dreambooth", 10: "todo:training_picker", 11: "todo:dataset_tag_editor",
    12: "todo:dreamartist", 13: "todo:hypernetwork_training", 14: "todo:custom_diffusion", 15: "todo:smart_process",
    16: "done:core:embedding-editor", 17: "todo:model_merge", 18: "todo:model_converter", 19: "done:core:lora",
    20: "todo:model_merge", 21: "done:core:embedding-merge", 22: "todo:model_merge", 23: "todo:lora_block_weight",
    24: "done:core:gallery", 25: "done:core:gallery", 26: "todo:inspiration", 27: "todo:inspiration",
    28: "todo:prompt_gallery", 29: "todo:infinity_grid", 30: "todo:core:config-presets", 31: "todo:core:preset-utilities",
    32: "todo:outpaint_canvas", 33: "na:webui-css", 34: "done:core:size-presets", 35: "na:webui-css", 36: "na:webui-css",
    37: "todo:core:localization", 38: "todo:dynamic_prompts", 39: "todo:unprompted", 40: "todo:stylepile",
    41: "todo:core:tag-autocomplete", 42: "todo:novelai_prompt", 43: "todo:tokenizer", 44: "todo:randomize",
    45: "done:conditioning_highres_fix", 46: "done:model_keyword", 47: "todo:prompt_generator", 48: "todo:prompt_generator",
    49: "todo:text2prompt", 50: "na:account", 51: "todo:deforum", 52: "todo:gif2gif", 53: "todo:video_loopback",
    54: "todo:seed_travel", 55: "todo:shift_attention", 56: "todo:prompt_travel", 57: "todo:steps_animation",
    58: "na:external-app", 59: "todo:batch_face_swap", 60: "todo:depth_maps", 61: "todo:multi_subject_render",
    62: "todo:depthmap2mask", 63: "todo:remove_background", 64: "todo:pixel_art", 65: "todo:pixel_art", 66: "todo:haku_img",
    67: "todo:asymmetric_tiling", 68: "todo:latent_mirroring", 69: "done:sonar", 70: "todo:depth_image_io",
    71: "todo:ultimate_sd_upscale", 72: "todo:prompt_fusion", 73: "todo:neutral_prompt", 74: "done:dynamic_thresholding",
    75: "todo:anti_burn", 76: "done:core:controlnet", 77: "todo:latent_couple", 78: "todo:composable_lora",
    79: "na:server-ops", 80: "todo:booru_prompt", 81: "todo:booru_prompt", 82: "todo:booru_prompt", 83: "done:nsfw_checker",
    84: "todo:prompt_filter", 85: "na:obsolete", 86: "todo:riffusion", 87: "todo:save_intermediate", 88: "todo:grid_numbers",
    89: "todo:hypernetworks", 90: "todo:core:system-info", 91: "todo:openpose_editor", 92: "na:account", 93: "na:account",
    94: "todo:minecraft_schematic", 95: "todo:aesthetic_score", 96: "todo:aesthetic_score", 97: "todo:aesthetic_score",
    98: "todo:clip_interrogator", 99: "todo:cross_attention_viz", 100: "todo:dump_unet", 101: "todo:openpose_editor",
    102: "done:llul", 103: "todo:cfg_schedule", 104: "todo:ebsynth_utility", 105: "todo:core:vram-estimator",
    106: "todo:tiled_diffusion", 107: "todo:openpose_editor", 108: "todo:corridor_crawler", 109: "todo:core:panorama-viewer",
    110: "na:account", 111: "todo:remove_background", 112: "na:server-ops", 113: "todo:openpose_editor", 114: "na:webui-css",
    115: "done:core:ui-state", 116: "todo:text2video", 117: "done:core:size-presets", 118: "done:core:mask-canvas",
    119: "todo:regional_prompter", 120: "todo:prompt_translate", 121: "todo:prompt_translate", 122: "todo:core:temporal-lab",
    123: "todo:negative_prompt_weight", 124: "na:account", 125: "todo:remove_background", 126: "na:obsolete",
    127: "todo:outpaint_canvas", 128: "todo:infinite_zoom", 129: "todo:adetailer", 130: "todo:core:localization",
    131: "todo:core:localization", 132: "todo:core:localization", 133: "todo:core:localization",
    134: "todo:core:localization", 135: "todo:core:localization", 136: "todo:core:localization",
    137: "todo:core:localization", 138: "todo:core:localization", 139: "todo:core:localization",
    140: "todo:core:localization", 141: "todo:core:localization", 142: "todo:core:localization",
    143: "todo:core:localization", 144: "todo:core:localization", 145: "todo:model_organizer",
    146: "done:core:model-presets", 147: "todo:cn_animation", 148: "todo:model_converter", 149: "todo:core:prompt-tools",
    150: "todo:model_organizer", 151: "todo:adetailer", 152: "todo:weight_gradient", 153: "todo:one_button_prompt",
    154: "todo:model_organizer", 155: "todo:image_editor", 156: "todo:stable_sr", 157: "done:core:job-queue",
    158: "na:server-ops", 159: "na:webui-api", 160: "na:webui-ux", 161: "todo:lua_scripting", 162: "todo:model_downloader",
    163: "todo:core:styles-editor", 164: "todo:clip_io", 165: "todo:inpaint_anything", 166: "todo:qr_code",
    167: "todo:face_swap", 168: "todo:color_enhance", 169: "na:account", 170: "done:core:gallery", 171: "todo:pixel_art",
    172: "na:webui-css", 173: "na:webui-css", 174: "todo:llm_prompt", 175: "todo:openpose_editor",
    176: "done:core:size-presets", 177: "done:core:gallery", 178: "todo:qr_code", 179: "todo:sadtalker",
    180: "na:external-app", 181: "todo:model_downloader", 182: "done:caption", 183: "done:loopback_scaler",
    184: "todo:inpaint_background", 185: "na:external-app", 186: "todo:gpu_temperature_guard", 187: "done:core:zoom",
    188: "na:server-ops", 189: "todo:latent_upscale", 190: "done:core:gallery", 191: "todo:prompt_filter",
    192: "todo:animatediff", 193: "na:webui-ux", 194: "todo:adetailer", 195: "todo:seamless_tile",
    196: "todo:face_swap", 197: "todo:style_selector", 198: "todo:prompt_library", 199: "todo:custom_hires_fix",
    200: "todo:refiner", 201: "todo:deoldify", 202: "todo:wav2lip", 203: "done:controlnet_fastload", 204: "todo:lora_control",
    205: "todo:dynamic_prompts", 206: "done:core:size-presets", 207: "na:account", 208: "na:account",
    209: "todo:core:localization", 210: "todo:vectorscope_cc", 211: "todo:prompt_format", 212: "todo:prompt_format",
    213: "todo:fabric", 214: "done:core:close-confirm", 215: "na:account", 216: "todo:model_downloader",
    217: "todo:batch_interrogate", 218: "done:core:gallery", 219: "todo:depth_library", 220: "todo:model_downloader",
    221: "todo:scenario_loader", 222: "todo:prompt_library", 223: "todo:model_merge", 224: "done:core:png-info",
    225: "todo:mov2mov", 226: "todo:easyphoto", 227: "todo:rich_text", 228: "todo:remove_background",
    229: "todo:booru_prompt", 230: "todo:model_downloader", 231: "done:nsfw_checker", 232: "todo:negpip",
    233: "done:cd_tuner", 234: "na:webui-css", 235: "na:webui-api", 236: "todo:core:compile",
    237: "done:core:image-scrambling", 238: "done:nsfw_checker", 239: "todo:lcm", 240: "todo:cads",
    241: "todo:face_swap", 242: "todo:hotshot_xl", 243: "done:inpaint_difference", 244: "todo:temporal",
    245: "todo:facechain", 246: "todo:style_selector", 247: "todo:panorama_tools", 248: "todo:core:styles-editor",
    249: "done:semantic_guidance", 250: "done:core:png-info", 251: "todo:person_mask", 252: "todo:style_variables",
    253: "todo:core:api-payload", 254: "todo:adetailer", 255: "todo:lora_queue", 256: "done:kohya_hrfix",
    257: "todo:test_my_prompt", 258: "na:webui-ux", 259: "na:account", 260: "todo:replacer",
    261: "todo:color_correction", 262: "todo:core:state-manager", 263: "done:agent_attention", 264: "todo:deepdanbooru",
    265: "todo:deepdanbooru", 266: "todo:ocr", 267: "done:characteristic_guidance", 268: "done:core:embedding-inspector",
    269: "todo:lama_cleaner", 270: "todo:regional_prompter", 271: "todo:model_organizer", 272: "na:account",
    273: "todo:video_extras", 274: "todo:inpaint_background", 275: "todo:daam", 276: "todo:controlnet_preprocess_extras",
    277: "na:account", 278: "todo:rpg_diffusion", 279: "todo:creaprompt", 280: "todo:img2img_workflow",
    281: "na:server-ops", 282: "na:webui-ux", 283: "todo:dataset_tag_editor", 284: "todo:resharpen", 285: "na:webui-ux",
    286: "todo:core:image-compare", 287: "na:webui-ux", 288: "todo:mosaic_outpaint", 289: "na:webui-ux",
    290: "todo:color_grading", 291: "na:webui-ux", 292: "todo:old_photo_restoration", 293: "na:webui-ux",
    294: "todo:core:prompt-tools", 295: "todo:token_downsampling", 296: "done:core:size-presets",
    297: "done:hires_fix_tweaks", 298: "todo:manga_maker", 299: "todo:model_organizer", 300: "na:server-ops",
    301: "todo:demofusion", 302: "done:incantations", 303: "done:old_sd_firstpasser", 304: "todo:image_editor",
    305: "todo:triposr", 306: "na:webui-ux", 307: "todo:core:prompt-tools", 308: "todo:model_merge",
    309: "todo:core:hardware-info", 310: "done:core:png-info", 311: "na:webui-css", 312: "done:core:metadata-utils",
    313: "todo:prompt_expansion", 314: "todo:core:localization", 315: "na:external-app",
    316: "todo:segment_anything", 317: "done:core:size-presets", 318: "todo:scramble_prompts", 319: "todo:tweak_weights",
    320: "todo:core:prevent-interrupt", 321: "todo:waifu2x", 322: "todo:depth_anything", 323: "na:external-app",
    324: "todo:detail_daemon", 325: "todo:masked_content_extras", 326: "todo:core:prompt-tools",
    327: "todo:masked_content_extras", 328: "done:core:theme", 329: "todo:manga_inpainting", 330: "na:webui-api",
    331: "todo:chara_searcher", 332: "todo:fsr_upscale", 333: "todo:core:nsfw-card-blur", 334: "na:webui-ux",
    335: "todo:face_manipulation", 336: "todo:pnginfo_injection", 337: "todo:llm_prompt", 338: "todo:remove_background",
    339: "done:img2img_hires_fix", 340: "todo:ditail", 341: "todo:live_portrait", 342: "todo:tipo",
    343: "na:webui-ux", 344: "na:account", 345: "done:core:gallery", 346: "na:account", 347: "todo:booru_prompt",
    348: "na:account", 349: "todo:model_downloader", 350: "done:core:size-presets", 351: "todo:style_selector",
    352: "todo:vae_tools",
}


# plugin ids / core features that are implemented: their `todo:` entries are reported as `done:`
IMPLEMENTED = {
    "token_downsampling",
    "waifu2x",
    "core:vram-estimator",
    "stylepile",
    "steps_animation",
    "pnginfo_injection",
    "gpu_temperature_guard",
    "color_correction",
    "core:config-presets", "core:preset-utilities", "core:nsfw-card-blur", "core:temporal-lab",
    "dynamic_prompts", "randomize", "prompt_format", "prompt_filter", "negative_prompt_weight", "novelai_prompt",
    "style_variables", "test_my_prompt", "scramble_prompts", "tweak_weights", "lora_queue", "batch_checkpoints_prompts",
    "seed_travel", "shift_attention", "one_button_prompt",
    "detail_daemon", "vectorscope_cc", "latent_mirroring", "asymmetric_tiling", "anti_burn", "resharpen",
    "color_grading", "cfg_schedule", "cads",
    "ultimate_sd_upscale", "adetailer", "lora_block_weight", "model_merge", "model_converter",
    "pixel_art", "color_enhance", "fsr_upscale", "person_mask", "remove_background", "depthmap2mask", "inpaint_background",
    "seamless_tile",
    "weight_gradient", "negpip", "neutral_prompt",
    "prompt_fusion", "tokenizer", "save_intermediate",
    "grid_numbers", "latent_upscale",
    "composable_lora", "core:system-info",
    "core:styles-editor",
    "core:image-compare", "vae_tools",
    "core:prevent-interrupt", "core:hardware-info", "core:api-payload",
}


SCOPE_NOTES = {
    "sonar": "AlwaysOn Sonar from the behaviour of the MIT source (README and script, nothing copied): momentum on the Euler a / Euler / Naive sampler updates (current and history momentum, pos/neg/rand sign, zero/rand_init/rand_new history start), Naive reference-image shallow fusion (linear and euler methods, step range), the final upscale by ratio or size, Sonar sampler/momentum/momentum_hist infotext plus extra keys for exact PNG/MCP replay, hires and img2img. The k-diffusion samplers map onto diffusers' Euler and Euler a schedulers (momentum 1.0 is bit-identical to them); while active the Sonar sampler replaces the request sampler. Random choices (rand sign, rand_new history, Naive 1e-5 noise) come from a seeded generator and the reference latent is the VAE posterior mean, so replays are exact; the reference image file cannot be restored from a PNG; the grid-search script mode is covered by X/Y/Z axes of momentum and momentum_hist (the `start:stop:step` list syntax is not provided); the AlwaysVisible/Script morph switch is not applicable. SD1.5/SDXL real MCP checks with image inspection. See sonar-verification.md.",
    "old_sd_firstpasser": "txt2img Script from the README only (the source has no licence and was not read): firstpass with an SD 1.x or SDXL checkpoint (steps, network type Auto/SD1/SDXL, one checkpoint list per family, firstpass resolution), upscale with a pixel upscaler, main-checkpoint img2img with the firstpass denoising, Hires fix as one more img2img pass (latent hires upscalers resize instead), PNG/MCP replay of the script values. Own design where the README is silent: firstpass size = native 512/1024 on the shorter side; Auto = the other family than the main checkpoint (LoRA metadata is not read); LoRA tags, manual LoRAs and ControlNet units act in the firstpass only unless LoRAs in the main pass is ticked; AlwaysOn plugins apply to both passes (the host applies them to every script generation); the main checkpoint is reloaded after an interrupted run. Not provided: the img2img-tab variant with an empty init image (extensions on the second pass), automatic LoRA-metadata type guess, restoring the firstpass AlwaysOn plugins/ControlNet units from the PNG beyond the AlwaysOn keys the main pass records. Upstream infotext key names are undocumented, so this app uses its own `Old SD firstpasser ...` keys. Verified on real SD1.5 and SDXL, docs/old-sd-firstpasser-verification.md.",
    "llul": "AlwaysOn LLuL (Local Latent upscaLer): the U-Net draws a region of the image at 2^N times the resolution and blends it back with a weight (optional mask); OUT layer by default, advanced layer filters (Resblock / Transformer / attention, source layer names), up- and downscalers with AA, Lerp / SLerp, step window, 12 XYZ axes, PNG/MCP replay, hires; the extra output images that mark the effective area are not provided. Verified on real SD1.5 and SDXL, docs/llul-verification.md.",
    "incantations": "AlwaysOn Incantations: Smoothed Energy Guidance, S-CFG, Perturbed Attention Guidance with Saliency-Adaptive Noise Fusion, CFG scheduler with noise interval (17 schedule types) and Multi-Concept T2I-Zero (CbS, CTNMS), 29 XYZ axes, PNG/MCP replay, hires; Seek for Incantations (needs interrogators) and the attention-map saver are not provided; native PAG/SAG/Reference/Neutral/Composable combinations are rejected. Verified on real SD1.5 and SDXL, docs/incantations-verification.md.",
    "characteristic_guidance": "AlwaysOn characteristic guidance as the pinned extension: per-step iteration with all 13 controls, eps/v models, k-diffusion/DDIM/UniPC, SD1.5/SD2/SDXL incl. 9-channel inpainting, base pass only (hires restarts, ADetailer skipped), CHG infotext with PNG/MCP replay. QR solve replaces CPU lstsq so replays are exact. Many other guidance/branch plugins are rejected by name. SD1.5/SDXL real MCP checks with image inspection. See characteristic-guidance-verification.md.",
    "nsfw_checker": "Indices 238 (NudeNet NSFW Censor), 83 (A1111 safety-checker censor) and 231 (NSFWJS blurrer) in one plugin: bundled pinned NudeNet ONNX with hash check, 18 categories with thresholds/expansion, NMS, all mask shapes and filters, generation censoring after refinement with optional before/mask outputs, live preview, Extras with custom-mask union, MCP and PNG replay; SD safety checker (black image) and NSFWJS (safe/questionable/explicit blur, report-only Extras) are downloaded on first use with pinned revisions and SHA-256 checks. Classifiers have known false negatives; no HTTP /nudenet or /safety routes. SD1.5/SDXL real MCP checks with image inspection. See nsfw-checker-verification.md.",
    "cd_tuner": "AlwaysOn colour/detail tuner as the pinned extension: detail d1/d2 and hires hd1/hd2 via conv_in/norm_out/conv_out weights restored after every pass, hr-scaling, stop steps, Contrast 2, brightness and three colour axes, VAE saturation 1/2, the colour map, <cdt:>/<cdtc:> prompt tags, Apply once and CDT/CDTC infotext with PNG/MCP replay. Source quirks kept (Hr-Stop Step 0). Stop steps count pipeline steps (differs for second-order samplers). SD1.5/SDXL real MCP checks with image inspection. See cd-tuner-verification.md.",
    "agent_attention": "AlwaysOn agent attention with token merging as the pinned extension: first/second pass sx, sy, ratio and agent ratio, second pass step, max downsample, FP32, hires-only, source infotext keys, PNG/MCP replay and all 13 XYZ axes. Merging uses a deterministic reduction (seeded from the image seed) so PNG replays are pixel-exact, not pixel-equal to A1111. Rejects PAG, SAG, HyperTile, Token Downsampling, Reference-only and ControlNet-LLLite combinations. SD1.5/SDXL real MCP checks with image inspection. See agent-attention-verification.md.",
    "loopback_scaler": "Native img2img Script with every source control: eased per-loop sizes to the max or scaled target, denoise change factor, per-loop seeds, batch restarts, saved loop outputs with source infotext and the final Pillow filters. Fixes the source's landscape height bug; rejects txt2img and batch size > 1. SD1.5/SDXL real MCP runs including cancel/recovery with image inspection (SD1.5 colour drift comes from repeated img2img with that model). See loopback-scaler-verification.md.",
    "semantic_guidance": "AlwaysOn conditioning-space concept edit as the pinned extension (not the SEGA paper noise guidance): comma-separated positive/negative concepts with first-segment attention strength, warmup, scale, tail threshold, inert momentum, SDXL pooled edit, source infotext keys, PNG/MCP replay and XYZ axes. SD1.5/SDXL real MCP checks with image inspection. XYZ axes need the plugin enabled; Neutral Prompt/Composable LoRA branches and PhotoMaker are rejected. See semantic-guidance-verification.md.",
    "controlnet_fastload": "AlwaysOn load/save of ControlNet units in the upstream marker+gzip+pickle format (embedded, .cni, both), current/loaded priority, non-executing bounded reader, inspect/preview/send/capture tab, 36-per-page filter gallery and MCP inspect/always_on. Native ControlNet gains source control modes, canny processor resolution/pixel perfect, hires canny/MLSD softening and non-inpaint mask selection. SD1.5/SDXL real MCP load/save/round-trip/priority/hires/cancel verified with image inspection. Inpaint/CLIP masks, batch inputs, hires-only units and other preprocessors' resolutions are rejected; NUI writes no ControlNet infotext for gallery filters. See controlnet-fastload-verification.md.",
    "core:embedding-editor": "Native owned-vector editing with full coordinate pages, real-tokenizer guidance, independent SDXL encoders, unsaved previews and source-bound overwrite with verified original backups/live refresh. Independent implementation from unlicensed pinned behavior; actual PT preservation, SD1.5/SDXL protected native/package save-collision-recovery and genuine PhotoMaker/EM cancellation verified. SD2 checkpoints, native multi-image preview and A1111 training interoperability are unverified; existing PhotoMaker composition guards remain. Dedicated MCP editor API is not included. See embedding-editor-verification.md.",
    "conditioning_highres_fix": "Pinned seven-mode inpainting conditioning formulas, native controls and PNG replay; 9-channel SD1.5/SDXL source and MainWindow, protected input/output, cancellation/recovery verified. Actual 4-channel SD1.5/SDXL masked and Hires output is unchanged. Disabled retains native baseline; SDXL is a native adaptation. Extracted Windows package, full Torch CPU and Scala suites verified. ControlNet/ADetailer composition is not real-model verified; see conditioning-highres-verification.md.",
    "core:embedding-inspector": "Native inspector, tokenizer, vector mixer, arbitrary-code-gated eval, atomic save and active-model refresh with SD1.5/SDXL support; loaded/catalog ownership is explicit. Eval can write readable files; SD2 actual checkpoints and A1111 training interoperability remain unverified. See embedding-inspector-verification.md.",
    "core:embedding-merge": "Native raw CLIP/TI inspection with six groupings, bounded source-compatible expression algebra, scoped prompt expressions and PNG replay, PT/safetensors export and SD1/SDXL conversion. No-overwrite publication and fail-closed model restoration; SD2 checkpoint and A1111 training interoperability are unverified. Real SD1.5/SDXL, PhotoMaker/offload/cancellation and package evidence plus limits are in embedding-merge-verification.md.",
    "core:metadata-utils": "Native checkpoint/LoRA JSON metadata editing and non-overwriting copies preserving tensor bytes; cancellable source fingerprints bind edits to the loaded file and reject stale sources. Bounded safetensors/JSON input; atomic publication requires hard-link support. Real SD1.5/SDXL native and extracted-package read-back, cancellation/recovery and harmless-edit RGB equality verified. See metadata-utils-verification.md for evidence and retained timeout diagnostics.",
    "core:model-presets": "Native checkpoint-bound raw infotext libraries with preset CRUD/default, trigger controls, capture/send in all three generation modes, JSON/marker sharing, Civitai model information and bounded thumbnails. Independent implementation from unlicensed pinned behavior; full SHA256 identities, revision checks and no-overwrite exports. Real SD1.5/SDXL source and extracted-package replay, protected-image capture/replay, live Civitai retrieval and native hash cancellation verified. Review corrections preserve draft text and require explicit remote-library overwrite; full SHA requests are disclosed. Corrected package SD1.5/SDXL, full Torch CPU and Scala regressions passed; review corrections were checked before the local wave commit. See model-presets-verification.md for evidence and limits.",
    "img2img_hires_fix": "Pinned source pixel upscale/VAE encode/refine behavior in all three modes, full controls and PNG replay; existing native latent Hires stays independent. SD1.5/SDXL extracted-package replay, cancellation and recovery verified. Refiner architectures remain unsupported; ControlNet/ADetailer interaction is not GPU-verified. See img2img-hires-fix-verification.md.",
    "core:image-scrambling": "Native session and batch compatibility with upstream pixel permutations. This is not secure encryption; readable metadata and temporary files remain. See encrypt-image-verification.md.",
    "token_downsampling": "Native two-depth K/V resampling, requested-step evaluation cutoff, XYZ axes and PNG replay. Actual SD1.5/SDXL extracted-package interruption/recovery verified; explicit PAG/HyperTile conflict and per-pass hires clock adaptation. See token-downsampling-verification.md.",
    "waifu2x": "Nine pinned neural upscalers and native Script Anime/Photo/CARN controls, 2x-16x. RGB output, 64MP bound, first-use weight downloads; exact CPU reference parity and SD1.5/SDXL extracted-package cancellation/recovery verified. See waifu2x-verification.md.",
    "core:vram-estimator": "Native four-mode CUDA calibration/charts, bounded profile history/import/export and compatible live estimates. Vanilla square calibration excludes extensions; bounded real OOM/recovery and SD1.5/SDXL packaged checks recorded in vram-estimator-verification.md.",
    "core:tag-autocomplete": "v0.03: local CSV tag/alias completion and native keyboard/IME guards; upstream extras remain under #24. See tag-autocomplete.md.",
    "core:state-manager": "v0.03: per-mode named generation settings save/list/restore/delete; upstream history and other extras remain under #23. See generation-states.md.",
}


def status(i: int) -> str:
    st = S[i]
    kind, _, target = st.partition(":")
    return f"done:{target}" if kind == "todo" and target in IMPLEMENTED else st


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    idx = json.load(open(os.path.join(root, "docs", "extensions-index.json"), encoding="utf-8"))
    missing = [i for i in range(len(idx)) if i not in S]
    if missing:
        sys.exit(f"unclassified index entries: {missing}")
    rows, counts = [], {"done": 0, "todo": 0, "na": 0}
    for i, e in enumerate(idx):
        st = status(i)
        kind, _, target = st.partition(":")
        counts[kind] += 1
        note = NA_REASONS.get(target, "") if kind == "na" else SCOPE_NOTES.get(target, "")
        rows.append(f"| {i} | [{e['name']}]({e['url']}) | {e['stars']} | {kind} | `{target}` | {note} |")
    md = ["# A1111 / reForge extension index coverage", "",
          f"Snapshot of the official extension index ({len(idx)} extensions), the list reForge's Extensions > Available "
          "tab installs from. Status per extension: `done` = built in, `todo` = accepted and planned, `na` = does not "
          "apply to a standalone desktop app.", "",
          f"Totals: done {counts['done']}, todo {counts['todo']}, na {counts['na']}.", "",
          "| # | Extension | Stars | Status | stablediffusion-nui | Scope / notes |", "|---|---|---|---|---|---|"] + rows
    md += ["", "Reason codes for `na`:", ""] + [f"- `{k}`: {v}" for k, v in NA_REASONS.items()]
    open(os.path.join(root, "docs", "EXTENSIONS.md"), "w", encoding="utf-8", newline="\n").write("\n".join(md) + "\n")
    status_json = [{"index": i, "name": e["name"], "url": e["url"], "status": status(i)} for i, e in enumerate(idx)]
    with open(os.path.join(root, "docs", "extensions-status.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(status_json, fh, ensure_ascii=False, indent=0)
    print(counts)


if __name__ == "__main__":
    main()
