"""Collects the licenses of every project stablediffusion-fx ports code from, re-implements, or depends on.

  python scripts/third_party.py        (needs the GitHub CLI `gh`, logged in)
  python scripts/third_party.py --use-archived-licenses
                                       (no network; requires existing notice labels and archived license files)

Writes licenses/<owner>__<repo>.txt (the upstream license text, verbatim) and THIRD_PARTY_NOTICES.md.
Sources: the hand-written BASE list below + the repository of every extension-index entry marked `done`
(docs/extensions-status.json), so new plugins pick up their notice automatically.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (repo, how it is used, what)
BASE = [
    ("Malisius/booru2prompt", "reimplemented", "Unlicensed behavior reference 4bd88839a6454428d098c7ad6672cc0989dd6a60; only its README was used (post link / id selection, search, extra tag choices, underscore and comma options), re-implemented in the `booru_prompt` script; no upstream code was read or copied"),
    ("antis0007/sd-webui-gelbooru-prompt", "reimplemented", "Pinned GPL-3.0 source b0128f931a8306a8f1a030c515792e3128b97f04; only its README was used (tags of a saved Gelbooru image from its md5 file name), re-implemented as post references in the `booru_prompt` script; no upstream code copied"),
    ("nochnoe/sd-webui-e621-prompt", "reimplemented", "Pinned MIT source 74ce0a50d788abfa42d67b0614f7195d3f71294c; only its README was used (e621 link / id / md5, tag categories with prefixes, underscores, exclude and append tags), re-implemented in the `booru_prompt` script; no upstream code copied"),
    ("Inzaniak/sd-webui-ranbooru", "reimplemented", "Unlicensed behavior reference dfbf48b0684770199f9025b4e8b27a3a30fa3e62; only its README and usage guide were used (random posts, tag filters, mixing, chaos / negative mode, img2img, LoRAnado), re-implemented in the `booru_prompt` script; no upstream code was read or copied"),
    ("RED1cat/Gelbooru-Prompt-Randomizer", "reimplemented", "Unlicensed behavior reference 032805388740dad2bcd06493f0286c8792e71230; only its README was used (random Gelbooru post with required and excluded tags), re-implemented in the `booru_prompt` script; no upstream code was read or copied"),
    ("Kahsolt/stable-diffusion-webui-sonar", "reimplemented", "Pinned MIT source 15f92859f70f624513796dde91396109134da47b; independent momentum Euler / Euler a / Naive scheduler steps, reference-image guidance and upscale from the source behaviour; no upstream code copied"),
    ("Elldreth/loopback_scaler", "reimplemented", "Pinned MIT source 184a53e6d22579c578c6ee1bf55d821479213c49; independent native img2img loop schedule and final filters; no upstream code copied"),
    ("light-and-ray/sd-webui-old-sd-firstpasser", "reimplemented", "Unlicensed behavior reference 74eb6b965bc5dbb65be99999b4a86bfbb0ee2b13; only its README was used (firstpass with an old SD 1.x / SDXL checkpoint, upscale, main-checkpoint img2img, Hires fix supported), re-implemented as the `old_sd_firstpasser` txt2img script; no upstream code was read or copied (its README screenshot was viewed only to name the controls)"),
    ("AUTOMATIC1111/stable-diffusion-webui-aesthetic-gradients", "reimplemented", "Unlicensed behavior reference 2624e5dd4902cb731287b426ad483e741ece85bb; only its README and the method of the paper it cites (Gallego, Personalizing Text-to-Image Generation via Aesthetic Gradients, 2022) were used, re-implemented as the `aesthetic_gradients` AlwaysOn hook; no upstream code was read or copied"),
    ("openai/clip-vit-large-patch14", "model dependency", "CLIP ViT-L/14 with its text and visual projections for the Aesthetic Gradients embeddings (the openai/CLIP code repository is MIT; the Hugging Face model card states no licence). config.json (4,519 bytes, SHA-256 8a09b467700c58138c29d53c605b34ebc69beaadd13274a8a2af8ad2c2f4032a) and model.safetensors (1,710,540,580 bytes, SHA-256 a2bf730a0c7debf160f7a6b50b3aaf3703e7e88ac73de7a314903141db026dcb) of Hugging Face revision 32bd64288804d66eefd0ccbe215aa642df71cc41 are downloaded on first use and size/SHA-256 checked; not bundled"),
    ("tsngo/stable-diffusion-webui-aesthetic-image-scorer", "reimplemented", "Pinned MIT source 1c3ab7a116ab5bc3b81d718e6383edd98903a01f; independent CLIP ViT-L/14 + improved-predictor scoring written as `aesthetic_score` (parameters text and PNG chunk) with the source's optional sampler, cfg_scale, sd_model_hash, seed and experimental hash fields, as the `aesthetic_score` plugin; Windows tags/categories are not provided; no upstream code copied"),
    ("vladmandic/sd-extension-aesthetic-scorer", "reimplemented", "Unlicensed behavior reference b60c3c825409f9b5c1ccbbb63b1713bf8ce7f253; only its README was used (CLIP ViT-L/14 or ViT-B/16 plus a small pretrained head, score 0..10 written as the PNG info chunk `score`, a CLI-like batch run through Extras), re-implemented as the `aesthetic_score` plugin; no upstream code was read or copied, and its model files were taken from the original MIT source crowsonkb/simulacra-aesthetic-models instead"),
    ("p1atdev/stable-diffusion-webui-cafe-aesthetic", "reimplemented", "Pinned AGPL-3.0 source d1c7ee842736f9d9cc6564c2c92a5bff54d19e26; independent cafeai aesthetic / style / waifu classification with softmax class probabilities, as the `aesthetic_score` plugin and its Extras (batch) plugin; no upstream code copied. The three classifiers are Hugging Face models, not bundled: cafeai/cafe_aesthetic (revision 48a343764f786abc1ca8aaddfcd60a688a70da9b), cafeai/cafe_style (de57fdb6bda44ac3d9c02641b6d8c3e36901138e) and cafeai/cafe_waifu (c817cefe0f47d060e29e959a3915722735170f6e), each a BEiT image classifier under AGPL-3.0 according to its model card (the repositories hold no licence file); config.json, preprocessor_config.json and pytorch_model.bin (about 376 MB) are downloaded on first use and size/SHA-256 checked, and pytorch_model.bin is loaded with weights_only"),
    ("christophschuhmann/improved-aesthetic-predictor", "model dependency", "Apache-2.0 aesthetic predictor weight sac+logos+ava1-l14-linearMSE.pth (3,714,759 bytes, SHA-256 21dd590f3ccdc646f0d53120778b296013b096a035a2718c9cb0d511bff0f1e0) at pinned commit 6934dd81792f086e613a121dbce43082cb8be85e, downloaded on first use for the `aesthetic_score` plugin and size/SHA-256 checked; not bundled; the MLP is written independently"),
    ("crowsonkb/simulacra-aesthetic-models", "model dependency", "MIT simulacra linear predictors sac_public_2022_06_29_vit_l_14_linear.pth (4,135 bytes) and vit_b_16 (3,111 bytes) at pinned commit 701f7678b305acf8a0634350365a86beb95dd87d, downloaded on first use for the `aesthetic_score` plugin and size/SHA-256 checked; not bundled; the linear head is written independently"),
    ("openai/CLIP", "model dependency", "MIT CLIP project. The ViT-L/14 (openai/clip-vit-large-patch14, revision 32bd64288804d66eefd0ccbe215aa642df71cc41, model.safetensors 1,710,540,580 bytes) and ViT-B/16 (openai/clip-vit-base-patch16, revision 57c216476eefef5ab752ec549e440a49ae4ae5f3, pytorch_model.bin 598,641,023 bytes) Hugging Face conversions of its weights are downloaded on first use for the `aesthetic_score` plugin and size/SHA-256 checked; their model cards state no licence; not bundled"),
    ("continue-revolution/sd-webui-animatediff", "reimplemented", "Pinned CC BY-NC-SA 4.0 source a88e88912bcbae0531caccfc50fd639f6ea83fd0 (non-commercial, share-alike); only its README and docs/*.md were used for the behaviour (context batch size, closed loop, stride and overlap, prompt travel, Img2Vid latent blend, FreeInit, save formats), re-implemented as the `animatediff` script and engine hook on diffusers' MotionAdapter; no upstream code was read or copied. Motion module weights are downloaded by the user (guoyww/animatediff: Apache-2.0; conrevo/AnimateDiff-A1111: no licence declared) and are not redistributed"),
    ("hnmr293/sd-webui-llul", "reimplemented", "Unlicensed behavior reference 6c5ac1b0aa29736e0aad3476939e4e05f25d20d7 (its LICENSE holds only the heading `MIT License`); independent local latent upscaling hooks on the diffusers U-Net; no upstream code copied"),
    ("v0xie/sd-webui-incantations", "reimplemented", "Pinned GPL-3.0 source 980d63838a2b8c8659a01b242e55551870619d6a; independent Smoothed Energy Guidance, S-CFG, PAG with SANF, CFG scheduler and Multi-Concept T2I-Zero; no upstream code copied"),
    ("scraed/CharacteristicGuidanceWebUI", "reimplemented", "Pinned GPL-3.0 source e2f9fdca460beed1366647e2eea8276a63f98484; independent characteristic guidance iteration; no upstream code copied"),
    ("hako-mikan/sd-webui-cd-tuner", "reimplemented", "Pinned AGPL-3.0 source aafdbdda88b8d4e717fdb9aa9abe9e6df4943d7a; independent weight, latent and VAE adjustments, colour map and prompt tags; no upstream code copied"),
    ("v0xie/sd-webui-agentattention", "reimplemented", "Pinned GPL-3.0 source 8c2fd24ed5203e68450100064a49fa7873eea0bb; independent agent attention and token merging (behaviour of LeapLabTHU/Agent-Attention 207ce3e and dbolya/tomesd 27a14a3 as the source documents them); no upstream code copied"),
    ("w-e-w/sd-webui-nudenet-nsfw-censor", "ported", "Pinned AGPL-3.0 source 1fb21cf9c3f72915ea1b5091bd51aedf91eae9d0; NudeNet letterbox detection, per-category thresholds and expansion, NMS, mask shapes, censor filters and the generation / live preview / Extras workflows re-implemented as `nsfw_checker` and `nsfw_checker_extras`; its unmodified nudenet/best.onnx (Git blob 12f4f7808735fb2f8a0fb9beca635a1426bfdcad) is bundled as models/nudenet/best.onnx"),
    ("notAI-tech/NudeNet", "data", "Unmodified NudeNet 320n detector weight models/nudenet/best.onnx (12,125,773 bytes, SHA-256 9832f15515bdb06bcb5a77beb60bc8ea54439bd7ecbaac46dac3b760b3dd13cc) as redistributed by w-e-w/sd-webui-nudenet-nsfw-censor@1fb21cf, whose nudenet/LICENSE.md cites the NudeNet licence at b5e7fa859f43594f72a13a34c60a06d8343105f5; verified before loading and packaging"),
    ("AUTOMATIC1111/stable-diffusion-webui-nsfw-censor", "reimplemented", "Unlicensed behavior reference c21cc99b731ff295039b0531e73968d1bf384570; SD safety checker on every final image, flagged images replaced with black, re-implemented as the `nsfw_checker` SD safety checker option with diffusers' StableDiffusionSafetyChecker; no upstream code copied. Its checker model CompVis/stable-diffusion-safety-checker (Hugging Face revision cb41f3a270d63d454d385fc2e4f571c487c253c5; the model card states no licence) is not bundled: config.json, preprocessor_config.json and pytorch_model.bin (1,216,067,303 bytes, SHA-256 64b8393f1afd5a0c1ed2aa5f341fa7c08286839a48f3743162a76a2835c808bd) are downloaded on first use from that revision and size/SHA-256 checked"),
    ("aria1th/sd-webui-nsfw-filter", "reimplemented", "Unlicensed behavior reference ab49c733c86ad24358417b5bff4adf4b3803baf8; NSFWJS top-class check with the safe / questionable / explicit levels and radius-20 Gaussian blur on final images, saved files and live previews, plus the /safety/predict check, re-implemented as the `nsfw_checker` NSFWJS filter option and the `nsfw_checker_extras` NSFWJS checker; no upstream code copied"),
    ("infinitered/nsfwjs", "model dependency", "MIT NSFWJS classifier (classes drawings, hentai, neutral, porn, sexy). Its ONNX conversion nsfw/nsfwjs.onnx from the Hugging Face repository deepghs/imgutils-models (model card licence MIT, revision 35e0ed8f35ce0dbea674568161a48b31ba7d2a99; 10,318,774 bytes, SHA-256 510dce55edcd1cf8281cbe906dedb3f4963f8814bc1edfbd42d3bf261148df2d) is downloaded on first use and size/SHA-256 checked; not bundled"),
    ("deepghs/imgutils", "model dependency", "MIT project behind the Hugging Face model repository deepghs/imgutils-models that publishes the NSFWJS ONNX conversion used by the `nsfw_checker` NSFWJS filter; no code used"),
    ("v0xie/sd-webui-semantic-guidance", "reimplemented", "Pinned GPL-3.0 source bdbd03c92ab5e2d3926cedd7364da61ed40e48d1; independent conditioning-space concept edit with upstream thresholds, warmup, infotext keys and XYZ axes; no upstream code copied"),
    ("pk5ls20/sd-webui-controlnet-fastload", "reimplemented", "Pinned Apache-2.0 source bbdb929f4285ea69d5099268496e4322dc7203ab; independent data-only pickle interpreter, compatible writer, native load/save, viewer and gallery; ControlNet control mode, canny resolution and mask selection re-implemented from Mikubill/sd-webui-controlnet behaviour; no upstream code copied"),
    ("CodeExplode/stable-diffusion-webui-embedding-editor", "reimplemented", "Unlicensed behavior reference c8f290230f9360729abc3a3a57fe1befc2d43b23; independent native coordinate editing, vocabulary guidance, safe save and temporary preview for SD1.5/SDXL; no upstream code or assets copied"),
    ("klimaleksus/stable-diffusion-webui-embedding-merge", "reimplemented", "Pinned SDXL-branch behavior 40d439de3cda5965fc1f9cb4412ef329885d1d6d; independent native vector algebra, scoped prompt expressions, inspection and export; original parser used only in isolated numerical reference fixtures"),
    ("Tzigo/metadata_utils", "reimplemented", "GPL-3.0 behavior reference a4cda9fe899f95de769496946497d20daedb516b; independent native checkpoint/LoRA metadata editor with raw tensor preservation and no-overwrite copy publication"),
    ("rifeWithKaiju/model_preset_manager", "reimplemented", "Unlicensed behavior reference 4e25eebd4b24fb903d2815ac30b787f37a98923c; independent native checkpoint preset library, sharing, Civitai information and thumbnail workflows; no upstream code copied, imported or evaluated"),
    ("Amadeus-AI/img2img-hires-fix", "reimplemented", "MIT behavior reference b8930527a8cc606dd79dd54ed23bc967314d1859; independent post-image pixel upscale, VAE re-encode and refinement controls for all generation modes"),
    ("klimaleksus/stable-diffusion-webui-conditioning-highres-fix", "reimplemented", "Independent inpainting conditioning reference 34da5b3461b89121f79f4375d4e798cd87450210; original license heading says WTFPL but its body is an Unlicense-style dedication"),
    ("w-e-w/embedding-inspector", "reimplemented", "Unlicense behavior reference 5f3451ec0cb5c9359feb37c0b0f984f09d337509; reupload of original tkalayci71 project; independent implementation"),
    ("haraldk/TwelveMonkeys", "dependency", "Bundled TwelveMonkeys ImageIO WebP reader 3.12.0 and its supporting modules"),
    ("viyiviyi/sd-encrypt-image", "reimplemented", "AGPL-3.0 behavior reference 3a44d3c264632aa90442bdc50e42af0770289bfd; independently implemented pixel-scrambling compatibility; upstream core used only for reference fixtures"),
    ("mix1009/model-keyword", "data and behavior reference", "MIT repository at c3d0833a9434bb41ecd7ea715b5abd5e6b43a5af; checkpoint and LoRA mapping databases copied verbatim; LoRA entries include Civitai-derived data, individual third-party rights not independently established"),
    ("PladsElsker/sd-webui-inpaint-difference", "reimplemented", "MIT behavior reference 656eed0a214186f4fb12fd6100fef582a01558bf; native difference masks and preview; upstream pure functions used only for test fixtures"),
    ("w-e-w/sd-webui-hires-fix-tweaks", "reimplemented", "AGPL3 behavior reference 28902f742a795afc31d86641ad85466afdd280e6; native Hires prompt modes, repeats/seeds, styles and workflow settings; original pure prompt functions used only for reference test fixtures"),
    ("feffy380/sd-webui-token-downsampling", "reimplemented", "MIT behavior reference f1f1cbae82b75d5658b08abbc3cb8ddc0b876cc5; native two-depth K/V nearest-exact downsampling, evaluation cutoff and XYZ controls"),
    ("ethansmith2000/comfy-todo", "reference", "MIT Token Downsampling lineage ddd75a3e62e0a67f0b9be839a9916b553041494f; no runtime dependency"),
    ("light-and-ray/sd-webui-waifu2x-upscaler", "reimplemented", "Native Waifu2x controls and nine upscaler choices; no unlicensed wrapper code copied/imported; pinned behavior feede81c2b15ebcd5ff881b3f5ad4c3dbf017368"),
    ("nagadomi/waifu2x", "model dependency", "MIT UpConv7 Anime/Photo noise 0..3 weights downloaded on first use from pinned cc385f97a9debfe611316aabfd5d8bb30ba2dbeb; mirror/original Git blobs verified identical; exact SHA256/size manifest in python/sdfx/waifu2x_assets.json"),
    ("yu45020/Waifu2x", "model dependency", "GPL-3.0 CARN V2 weights, byte-identical to original archive at 1e6c456e9769bb8f418024c0782ef965d8dfd026; optional first-use download, restricted weights-only loading; independently expressed inference graph, licensed original used only as numerical reference"),
    ("nmhkahn/CARN-pytorch", "upstream model reference", "Original CARN architecture referenced by yu45020/Waifu2x; no code imported or copied"),
    ("w-e-w/stable-diffusion-webui-GPU-temperature-protection", "reimplemented", "Temperature pause/wake hysteresis and controls; behavior reference f388d7081945576ca2361c365d16f5edb3250412; independent native/engine implementation"),
    ("openhardwaremonitor/openhardwaremonitor", "dependency", "Optional external running Windows GPU temperature monitor through WMI; not installed or bundled; schema reference 09c16898a4a84c36644e7c07baebf1efd299691e"),
    ("ROCm/rocm_smi_lib", "dependency", "Optional external rocm-smi temperature query on Linux; not installed or bundled"),
    ("SenshiSentou/sd-webui-state-manager", "reimplemented", "Manual named generation settings, per-mode list/inspect/restore/delete, "
     "including shared model settings and inactive script/extension controls -> `core:state-manager`; independent Scala/JavaFX "
     "implementation, no upstream code/assets copied; no history/autosave/thumbnail or upstream storage-format parity; "
     "[source 2af62ec](https://github.com/SenshiSentou/sd-webui-state-manager/tree/2af62ecd5287438bbc309f7e840d34aa5c8e3cb6)"),
    ("DominikDoom/a1111-sd-webui-tagcomplete", "reimplemented", "Local CSV tag/alias completion with keyboard selection "
     "in native prompt editors -> `core:tag-autocomplete`; independent Scala/JavaFX implementation, no upstream code or tag "
     "datasets copied; no wildcard/extra-network/chant/translation parity; "
     "[source 4170882](https://github.com/DominikDoom/a1111-sd-webui-tagcomplete/tree/4170882f90b47be130a0ff9314f663c230b9153d)"),
    ("AUTOMATIC1111/stable-diffusion-webui", "ported", "infotext format, built-in scripts (X/Y/Z, prompt matrix, loopback, "
     "SD upscale, outpainting, img2img alternative, custom code, postprocessing), styles.csv, soft inpainting"),
    ("Panchovix/stable-diffusion-webui-reForge", "ported", "reference implementation; built-in extensions (APG, RescaleCFG, "
     "advanced model sampling, Mahiro, random resolutions), sampler / schedule tables"),
    ("lllyasviel/stable-diffusion-webui-forge", "ported", "forge built-in extensions (FreeU, Dynamic Thresholding, HyperTile, SAG, "
     "Kohya HRFix, MultiDiffusion, Latent Modifier, Never OOM, IP-Adapter, ControlLLLite, PhotoMaker, Fooocus inpaint, SVD, "
     "Z123, preprocessors)"),
    ("comfyanonymous/ComfyUI", "ported", "sigma schedules, APG / Mahiro / RescaleCFG guidance math, Deep Shrink, FreeU"),
    ("AbyszOne/Abysz-LAB-Ext", "ported", "Temporal Lab DFI, deflicker, fuse and frame-extraction controls and "
     "algorithms informed by the pinned implementation; numerical equivalence is not claimed. Native Inter Blur "
     "is active, video sampling uses frame-index timestamps, and output is staged into a new directory; "
     "[source deff0d4](https://github.com/AbyszOne/Abysz-LAB-Ext/tree/deff0d4b4a208f862299b14b93e088f613373153)"),
    ("crowsonkb/k-diffusion", "ported", "Karras / exponential / polyexponential sigma schedules"),
    ("mcmonkeyprojects/sd-dynamic-thresholding", "ported", "Dynamic Thresholding (CFG-Fix) math"),
    ("tfernd/HyperTile", "ported", "HyperTile attention tiling"),
    ("ljleb/sd-webui-neutral-prompt", "ported", "Neutral Prompt parser, nested/signed prompt composition, denoised-x0 operators and CFG rescale"),
    ("ljleb/prompt-fusion-extension", "ported", "Prompt Fusion parser, AST and interpolation algorithms: "
     "upstream lib_prompt_fusion/{ast_nodes,geometries,interpolation_functions,interpolation_tensor,prompt_parser,t_scaler}.py "
     "at [1d86996](https://github.com/ljleb/prompt-fusion-extension/tree/1d86996ac4c3d32efaacb8a115600aa6fde6dab6/lib_prompt_fusion) "
     "adapted into python/sdfx/fusion/; upstream MIT notice also preserved in python/sdfx/fusion/LICENSE"),
    ("AUTOMATIC1111/stable-diffusion-webui-tokenizer", "reimplemented", "CLIP text/ID token inspection -> `tokenizer`; "
     "clean implementation in python/sdfx/tokenizer_tool.py and native Tokenizer tab from documented behaviour "
     "at [ac6d541](https://github.com/AUTOMATIC1111/stable-diffusion-webui-tokenizer/tree/ac6d541c7032e9f9c69c8ead2ed201302b06a4fe); "
     "no upstream code copied"),
    ("AlUlkesh/sd_save_intermediate_images", "reimplemented", "Sampling snapshots, intermediate gallery and video export "
     "-> `save_intermediate`; standalone python/sdfx/save_intermediate.py from upstream behaviour "
     "at [a1d7cec](https://github.com/AlUlkesh/sd_save_intermediate_images/tree/a1d7cecff893280d709767794d5654e8cbc6746f) "
     "(upstream Unlicense)"),
    ("feynlee/latent-upscale", "reimplemented", "Source-resolution VAE encoding, latent interpolation and full-step "
     "img2img scheduling -> `latent_upscale`; "
     "[source b9f75f4](https://github.com/feynlee/latent-upscale/tree/b9f75f44de15170a38b195692cff28246846da7d)"),
    ("AlUlkesh/sd_grid_add_image_number", "reimplemented", "Filename numbers and XYZ axis labels on grid tiles "
     "-> `grid_numbers`; standalone Java2D renderer; "
     "[source 693a8a7](https://github.com/AlUlkesh/sd_grid_add_image_number/tree/693a8a710e1872d3ba9b63a41f69ee705f4ad54c)"),
    ("a2569875/stable-diffusion-webui-composable-lora", "ported", "Composable LoRA/LyCORIS controllers and adapter "
     "algebra -> `composable_lora`; standalone branch routing and native controls; "
     "[source a03d40e](https://github.com/a2569875/stable-diffusion-webui-composable-lora/tree/a03d40eb3a3bc63728f46f1c00229310734c8fef)"),
    ("vladmandic/sd-extension-system-info", "ported", "Fixed benchmark prompts -> native System Info; remaining "
     "diagnostics, benchmark, local history, metadata and explicit legacy submission independently implemented; "
     "[source 006f08f](https://github.com/vladmandic/sd-extension-system-info/tree/006f08f499bbe69c484f0f1cc332bbf0e75526c2). "
     "Optional preinstalled NVIDIA driver diagnostics are queried when available; no driver utility is bundled."),
    ("chrisgoringe/Styles-Editor", "reimplemented", "Native CSV editing, categories, notes, backups and AES-Crypt v2 "
     "-> `core:styles-editor`; [source 025f17f](https://github.com/chrisgoringe/Styles-Editor/tree/025f17f747975a1fc914f67806e09dcb80e6eb69)"),
    ("harukei-tech/sd-webui-extended-style-saver", "reimplemented", "Independent native UUID presets and six-setting "
     "save/apply -> `core:styles-editor`; no upstream code copied; "
     "[source 7750cc6](https://github.com/harukei-tech/sd-webui-extended-style-saver/tree/7750cc6b2dcba91951402a682819bcb4fcc43bea)"),
    ("marcobellaccini/pyAesCrypt", "reimplemented", "AES-Crypt v2 protocol interoperability reference only; generated "
     "test fixture and bidirectional external checks at13a188f8799306ad9034167bf0bc0489319db274. "
     "No reference code or Python crypto dependency is bundled or imported by the product."),
    ("Haoming02/sd-webui-image-comparison", "reimplemented", "Native paired image wipe/opacity/view transforms, "
     "source buttons and metadata differences -> `core:image-compare`; "
     "[source 4354eda](https://github.com/Haoming02/sd-webui-image-comparison/tree/4354edab87ca6634fe1f0b94d3e0dba65f6221f0)"),
    ("light-and-ray/sd-webui-vae-tools", "reimplemented", "Independent native latent encode/decode and failed VAE "
     "decode recovery -> `vae_tools`; no upstream code copied; "
     "[source 47fe6db](https://github.com/light-and-ray/sd-webui-vae-tools/tree/47fe6db4f3a8f12320c2fa040d0ba542d6325425)"),
    ("Zyin055/Config-Presets", "data", "Original 14 txt2img and four img2img starter presets (including each "
     "mode's `None` entry) -> `core:config-presets`; preset behaviour independently implemented with no upstream "
     "code copied; [source e252e60](https://github.com/Zyin055/Config-Presets/tree/e252e60288b1104a45ea12106d92a8d145364fa1)"),
    ("Gerschel/sd_web_ui_preset_utils", "reimplemented", "Shared preset schema and selective apply behaviour "
     "-> `core:preset-utilities`; independently implemented from observed behaviour; no upstream code, prose, "
     "assets, or default JSON copied; [source e301734](https://github.com/Gerschel/sd_web_ui_preset_utils/tree/e301734567f37ac53c6225aec3a5c9858b357e7e) "
     "has no published license file in its tracked tree"),
    ("CurtisDS/stupid-nsfw-card-blur-a1111", "reimplemented", "Native card preview show/blur/hide behaviour -> `core:nsfw-card-blur`; independently reimplemented in Scala/JavaFX, with native URI loading, blur, keyboard and image codec adaptations; no upstream CSS, JavaScript or icons copied; [source b2cdcebf97d1074278fb84e0a6eec3006dab0fe1](https://github.com/CurtisDS/stupid-nsfw-card-blur-a1111/tree/b2cdcebf97d1074278fb84e0a6eec3006dab0fe1)"),
    ("madebyollin/taesd", "dependency", "Optional separately downloaded TAESD/TAESDXL model weights for VAE Tools. "
     "Weights are not bundled; model repositories madebyollin/taesd and madebyollin/taesdxl on Hugging Face "
     "are revision-pinned by python/sdfx/vae_tools.py and download requires explicit enablement."),
    ("light-and-ray/sd-webui-prevent-interruption", "reimplemented", "Runtime-global interruption protection "
     "and explicit user restart admission -> `core:prevent-interrupt`; independent native/JSON Lines implementation, "
     "no upstream code or assets copied; "
     "[source ea22bc0](https://github.com/light-and-ray/sd-webui-prevent-interruption/tree/ea22bc0de11875f3889f09c8e6ed7b879f321d05)"),
    ("light-and-ray/sd-webui-hardware-info-in-metadata", "reimplemented", "Compact hardware, per-image batch timing, "
     "remark, Extras provenance and informational paste notices -> `core:hardware-info`; independently implemented, "
     "no upstream code copied; "
     "[source 784a0dd](https://github.com/light-and-ray/sd-webui-hardware-info-in-metadata/tree/784a0dd8521108815aff007d7a4807ac6f98d251)"),
    ("huchenlei/sd-webui-api-payload-display", "reimplemented", "API payload display behaviour -> `core:api-payload`; "
     "independently reimplemented in Scala/JavaFX from "
     "[source 2d3332d](https://github.com/huchenlei/sd-webui-api-payload-display/tree/2d3332d94ffac0943bd35a1943924ed4650c6e63); "
     "no upstream JavaScript or CSS copied"),
    ("summerstyle/jsonTreeViewer", "reimplemented", "JSON tree interactions referenced by the vendored viewer in "
     "huchenlei/sd-webui-api-payload-display; behaviour independently reimplemented in Scala/JavaFX; "
     "no vendored JavaScript or CSS copied; "
     "[license source 6abfcc1](https://github.com/summerstyle/jsonTreeViewer/blob/6abfcc1202396af12e2806d129bcae0e75b37365/LICENSE)"),
    ("space-nuko/a1111-stable-diffusion-webui-vram-estimator", "reimplemented", "Native VRAM calibration and estimation behavior; "
     "four generation modes, measured memory profiles, plots and model/runtime-matched prediction. Independently implemented without WebUI imports. "
     "[source 8ecde47](https://github.com/space-nuko/a1111-stable-diffusion-webui-vram-estimator/tree/8ecde47f7aac1304058ab5af09702b703191c5f6)"),
    ("some9000/StylePile", "data", "Pinned GPL-3.0 StylePile catalog, reference images and inspiration texts; "
     "original bytes and license are bundled under plugins/src/main/resources/stylepile, with SHA-256 provenance. "
     "Processing and native UI are independently implemented; no WebUI script is bundled or executed. "
     "[source 85b549a](https://github.com/some9000/StylePile/tree/85b549a10ff6c5b1604e980cd2107253b088e832)"),
    ("vladmandic/sd-extension-steps-animation", "reimplemented", "Native Steps Animation: denoised-step movies, "
     "duration, interpolation, codec choice and partial-export behavior; independently implemented using this app's recorder. "
     "No upstream code or assets copied; no published license file at inspected revision. "
     "[source acac7f1](https://github.com/vladmandic/sd-extension-steps-animation/tree/acac7f166d5613ebfddac4c0a286ba2e5f7f29d1)"),
    ("bluelovers/sd-webui-pnginfo-injection", "reimplemented", "Optional generation-time Hashes metadata -> pnginfo_injection; "
     "independent native and Python implementation of resource provenance; no upstream code, assets or static wildcard maps copied. "
     "No published license file at inspected revision; "
     "[source 93bf780](https://github.com/bluelovers/sd-webui-pnginfo-injection/tree/93bf780426d3571056c6ef96fb7734943f16e711)"),
    ("kohya-ss/sd-scripts", "ported", "ControlNet-LLLite module layout"),
    ("lllyasviel/Fooocus", "ported", "Fooocus inpaint patch / head application"),
    ("threestudio-project/threestudio", "reimplemented", "Zero123 diffusers layout and sampling"),
    ("TencentARC/PhotoMaker", "dependency", "PhotoMaker pipeline (optional Python package)"),
    ("huggingface/diffusers", "dependency", "inference pipelines"),
    ("huggingface/transformers", "dependency", "text / image encoders, BLIP, Marigold support"),
    ("huggingface/peft", "dependency", "LoRA adapters"),
    ("huggingface/accelerate", "dependency", "model offloading"),
    ("huggingface/safetensors", "dependency", "weights I/O"),
    ("chaiNNer-org/spandrel", "dependency", "upscaler / face restoration model loading"),
    ("pytorch/pytorch", "dependency", "tensor runtime"),
    ("opencv/opencv-python", "dependency", "image processing (canny, face detection)"),
    ("python-pillow/Pillow", "dependency", "images"),
    ("scikit-image/scikit-image", "dependency", "Optional LAB histogram matching for A1111 reference color correction"),
    ("FHPythonUtils/BlendModes", "dependency", "Optional luminosity blending for A1111 reference color correction"),
    ("light-and-ray/sd-webui-color-correction-extras", "reimplemented", "Reference color correction behavior, original-input fallback and independent method comparisons; no wrapper code copied; source 0d86f5a55d5648ca693584a1078761cf8ae26395"),
    ("Alvi-alvarez/sd-Img2img-batch-interrogator", "ported", "Per-image prompt composition, including source-derived emoticon exceptions, MIT; partial WD v3 batch only; [source ed91cec](https://github.com/Alvi-alvarez/sd-Img2img-batch-interrogator/tree/ed91cec663bcddd7ca5514264a61483c7d80f8d2)"),
    ("onnx/onnx", "dependency", "Optional local WD v3 model structure inspection"),
    ("microsoft/onnxruntime", "dependency", "WD14 tagger and optional local WD v3 batch inference"),
    ("ultralytics/ultralytics", "dependency", "YOLO detectors for ADetailer (optional Python package)"),
    ("danielgatis/rembg", "dependency", "background removal models (optional Python package)"),
    ("FFmpeg/FFmpeg", "dependency", "Optional external ffmpeg executable for Save Intermediate, Steps Animation and AnimateDiff (MP4 / WEBM) video export; "
     "no FFmpeg binary is bundled or installed by this app. Upstream LICENSE.md explains build-dependent licensing: "
     "LGPL-2.1-or-later by default; --enable-gpl changes the combined build to GPL-2.0-or-later, "
     "--enable-version3 selects version 3, and linked libraries can change the terms. "
     "The selected executable's build and accompanying notices determine its effective license; "
     "--enable-nonfree builds are not redistributable. The collected upstream file is the licensing overview "
     "([inspected source](https://github.com/FFmpeg/FFmpeg/blob/46f23be8b1976ed4c8ec85584daabcf0e57a7b9e/LICENSE.md))."),
    ("openjdk/jfx", "dependency", "JavaFX UI toolkit"),
    ("com-lihaoyi/upickle", "dependency", "JSON"),
    ("scala/scala3", "dependency", "Scala runtime"),
    ("AUTOMATIC1111/stable-diffusion-webui-extensions", "data", "extension index snapshot (docs/extensions-index.json)"),
]

# Keep these source-pinned original LICENSE bytes; do not normalize newlines.
# (commit, license path, SHA-256, SPDX)
PINNED_LICENSES = {
    "antis0007/sd-webui-gelbooru-prompt": ("b0128f931a8306a8f1a030c515792e3128b97f04", "LICENSE", "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986", "GPL-3.0"),
    "nochnoe/sd-webui-e621-prompt": ("74ce0a50d788abfa42d67b0614f7195d3f71294c", "LICENSE", "442e98fcf4a5025b373765e15a9e41a3d1aef54b6f5413313e3e669c5f209252", "MIT"),
    "continue-revolution/sd-webui-animatediff": ("a88e88912bcbae0531caccfc50fd639f6ea83fd0", "LICENSE", "9e502ba75fd0fd30cea5497b438a4bc12f07f02bc932a7a6041fd6a18b8f46a0", "CC-BY-NC-SA-4.0"),
    "Kahsolt/stable-diffusion-webui-sonar": ("15f92859f70f624513796dde91396109134da47b", "LICENSE", "db9e98edd8524ac09e959b256bfb5687d27f88a9a442be629b0e55d102bfc0eb", "MIT"),
    "tsngo/stable-diffusion-webui-aesthetic-image-scorer": ("1c3ab7a116ab5bc3b81d718e6383edd98903a01f", "LICENSE", "f5ca122d1eed10b6471854b92bb9ea69da0d40ec229a2677d83e0f2166b3c517", "MIT"),
    "p1atdev/stable-diffusion-webui-cafe-aesthetic": ("d1c7ee842736f9d9cc6564c2c92a5bff54d19e26", "LICENSE", "8486a10c4393cee1c25392769ddd3b2d6c242d6ec7928e1414efff7dfb2f07ef", "AGPL-3.0"),
    "christophschuhmann/improved-aesthetic-predictor": ("6934dd81792f086e613a121dbce43082cb8be85e", "LICENSE", "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4", "Apache-2.0"),
    "crowsonkb/simulacra-aesthetic-models": ("701f7678b305acf8a0634350365a86beb95dd87d", "LICENSE", "d6c412c78ce18a08f053ef91bbf367e1d34320c2dc74961f024d19f9e1a85a19", "MIT"),
    "openai/CLIP": ("d05afc436d78f1c48dc0dbf8e5980a9d471f35f6", "LICENSE", "987e63b32f6c89ff5160e429458a872ff048e6860b590a3912e938f9da8f14db", "MIT"),
    "Elldreth/loopback_scaler": ("184a53e6d22579c578c6ee1bf55d821479213c49", "LICENSE", "d664856e8ec3180ea54d889cd2bbbd5971b7cb14f91b882c4af3b94b3e86747a", "MIT"),
    "v0xie/sd-webui-incantations": ("980d63838a2b8c8659a01b242e55551870619d6a", "LICENSE", "230184f60bae2feaf244f10a8bac053c8ff33a183bcc365b4d8b876d2b7f4809", "GPL-3.0"),
    "scraed/CharacteristicGuidanceWebUI": ("e2f9fdca460beed1366647e2eea8276a63f98484", "LICENSE", "8b1ba204bb69a0ade2bfcf65ef294a920f6bb361b317dba43c7ef29d96332b9b", "GPL-3.0"),
    "hako-mikan/sd-webui-cd-tuner": ("aafdbdda88b8d4e717fdb9aa9abe9e6df4943d7a", "LICENSE", "8486a10c4393cee1c25392769ddd3b2d6c242d6ec7928e1414efff7dfb2f07ef", "AGPL-3.0"),
    "v0xie/sd-webui-agentattention": ("8c2fd24ed5203e68450100064a49fa7873eea0bb", "LICENSE", "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986", "GPL-3.0"),
    "w-e-w/sd-webui-nudenet-nsfw-censor": ("1fb21cf9c3f72915ea1b5091bd51aedf91eae9d0", "LICENSE", "3918cb0f0add9f911a7ad0c4f188e8a9be865a4f14b860820cca1fbf6fefe1e7", "AGPL-3.0"),
    "notAI-tech/NudeNet": ("b5e7fa859f43594f72a13a34c60a06d8343105f5", "LICENSE.md", "8486a10c4393cee1c25392769ddd3b2d6c242d6ec7928e1414efff7dfb2f07ef", "AGPL-3.0"),
    "v0xie/sd-webui-semantic-guidance": ("bdbd03c92ab5e2d3926cedd7364da61ed40e48d1", "LICENSE", "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986", "GPL-3.0"),
    "pk5ls20/sd-webui-controlnet-fastload": ("bbdb929f4285ea69d5099268496e4322dc7203ab", "LICENCE", "43070e2d4e532684de521b885f385d0841030efa2b1a20bafb76133a5e1379c1", "Apache-2.0"),
    "klimaleksus/stable-diffusion-webui-embedding-merge": ("40d439de3cda5965fc1f9cb4412ef329885d1d6d", "LICENSE", "2c42de328b26a7318910d10b3cc4db282be0ad8146dc6543686797fbee4cde3b", "Unlicense (upstream also states WTFPL)"),
    "mix1009/model-keyword": ("c3d0833a9434bb41ecd7ea715b5abd5e6b43a5af", "LICENSE", "665df3b62b63216be2ca94177c762f511d3df58e4850e875e4857100ec3d5d5f", "MIT"),
    "Tzigo/metadata_utils": ("a4cda9fe899f95de769496946497d20daedb516b", "LICENSE", "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986", "GPL-3.0"),
    "klimaleksus/stable-diffusion-webui-conditioning-highres-fix": (
        "34da5b3461b89121f79f4375d4e798cd87450210", "LICENSE",
        "2c42de328b26a7318910d10b3cc4db282be0ad8146dc6543686797fbee4cde3b", "WTFPL heading; Unlicense-style text"),
    "w-e-w/embedding-inspector": (
        "5f3451ec0cb5c9359feb37c0b0f984f09d337509", "LICENSE",
        "6b0382b16279f26ff69014300541967a356a666eb0b91b422f6862f6b7dad17e", "Unlicense"),
    "haraldk/TwelveMonkeys": (
        "119700487b78717d45ab74bf5a61ba9824442697", "LICENSE.txt",
        "10e1e2688c74b337e0ca9e3a6a02e16319c3ee4cec79b768bca28ab8823ffde2", "BSD-3-Clause"),
    "viyiviyi/sd-encrypt-image": (
        "3a44d3c264632aa90442bdc50e42af0770289bfd", "LICENSE",
        "8486a10c4393cee1c25392769ddd3b2d6c242d6ec7928e1414efff7dfb2f07ef", "AGPL-3.0"),
    "w-e-w/stable-diffusion-webui-GPU-temperature-protection": (
        "f388d7081945576ca2361c365d16f5edb3250412", "LICENSE",
        "248a1ab6a57e6f677770a1498efc7a567f22aaae68923ae84c47d8a3db0ee699", "AGPL-3.0"),
    "SenshiSentou/sd-webui-state-manager": (
        "2af62ecd5287438bbc309f7e840d34aa5c8e3cb6", "LICENSE",
        "3fe9f2a7f539d60ed0e8edaab5f9645c0ee144f9df7f3cf29296153fc643bc4e", "MIT"),
    "DominikDoom/a1111-sd-webui-tagcomplete": (
        "4170882f90b47be130a0ff9314f663c230b9153d", "LICENSE",
        "c1532c29734187eddd3948321379fa95ba79191bc3cb41d2787d7dff6e5913b5", "MIT"),
    "AbyszOne/Abysz-LAB-Ext": (
        "deff0d4b4a208f862299b14b93e088f613373153", "LICENSE",
        "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986", "GPL-3.0"),
    "CurtisDS/stupid-nsfw-card-blur-a1111": (
        "b2cdcebf97d1074278fb84e0a6eec3006dab0fe1", "LICENSE.txt",
        "e6b9326517557ffdbf4a4a2a9f10ab20a037e95dd08645412e64a8baf7549742", "MIT"),
    "Zyin055/Config-Presets": (
        "e252e60288b1104a45ea12106d92a8d145364fa1", "LICENSE",
        "c19b98789c10ffe53568c46f4daae8a49024a02715cf2d4f05de04aa05d3a5f9", "MIT"),
    "huchenlei/sd-webui-api-payload-display": (
        "2d3332d94ffac0943bd35a1943924ed4650c6e63", "LICENSE",
        "990b5c560b655fb8623874940f1ac02cd21c3c76c714c997dff0ec3fa8c744fb", "MIT"),
    "summerstyle/jsonTreeViewer": (
        "6abfcc1202396af12e2806d129bcae0e75b37365", "LICENSE",
        "0e6f8814a1368da259cdca5d218c422fcb278b3579651c55079d774799ae7692", "MIT"),
}

# Audited source has no license file. Do not fetch another branch or invent a license text.
# (commit, notice label)
PINNED_UNLICENSED = {
    "Malisius/booru2prompt": (
        "4bd88839a6454428d098c7ad6672cc0989dd6a60", "no license file published at pinned source"),
    "Inzaniak/sd-webui-ranbooru": (
        "dfbf48b0684770199f9025b4e8b27a3a30fa3e62", "no license file published at pinned source"),
    "RED1cat/Gelbooru-Prompt-Randomizer": (
        "032805388740dad2bcd06493f0286c8792e71230", "no license file published at pinned source"),
    "CodeExplode/stable-diffusion-webui-embedding-editor": (
        "c8f290230f9360729abc3a3a57fe1befc2d43b23", "no license file published at pinned source"),
    "rifeWithKaiju/model_preset_manager": (
        "4e25eebd4b24fb903d2815ac30b787f37a98923c", "no license file published at pinned source"),
    "Gerschel/sd_web_ui_preset_utils": (
        "e301734567f37ac53c6225aec3a5c9858b357e7e", "no license file published"),
    "AUTOMATIC1111/stable-diffusion-webui-nsfw-censor": (
        "c21cc99b731ff295039b0531e73968d1bf384570", "no license file published at pinned source"),
    "aria1th/sd-webui-nsfw-filter": (
        "ab49c733c86ad24358417b5bff4adf4b3803baf8", "no license file published at pinned source"),
    "light-and-ray/sd-webui-old-sd-firstpasser": (
        "74eb6b965bc5dbb65be99999b4a86bfbb0ee2b13", "no license file published at pinned source"),
    "AUTOMATIC1111/stable-diffusion-webui-aesthetic-gradients": (
        "2624e5dd4902cb731287b426ad483e741ece85bb", "no license file published at pinned source"),
    "openai/clip-vit-large-patch14": (
        "32bd64288804d66eefd0ccbe215aa642df71cc41", "Hugging Face model card states no license (the openai/CLIP code repository is MIT)"),
    "vladmandic/sd-extension-aesthetic-scorer": (
        "b60c3c825409f9b5c1ccbbb63b1713bf8ce7f253", "no license file published at pinned source"),
    "hnmr293/sd-webui-llul": (
        "6c5ac1b0aa29736e0aad3476939e4e05f25d20d7", "no usable license at pinned source (LICENSE is only the heading `MIT License`)"),
}


def repo_of(url: str):
    m = re.match(r"https?://github\.com/([^/]+)/([^/#?]+?)(?:\.git)?/?$", url.strip())
    return f"{m.group(1)}/{m.group(2)}" if m else None


def verify_license_bytes(raw: bytes, expected: str):
    """Verify a trusted digest, allowing only uniform LF/CRLF transport variants.

    Do not strip whitespace or final newlines; content changes remain failures.
    """
    lf = raw.replace(b"\r\n", b"\n")
    candidates = (raw, lf, lf.replace(b"\n", b"\r\n"))
    if not any(hashlib.sha256(candidate).hexdigest() == expected for candidate in candidates):
        raise ValueError("license content does not match trusted digest")


def gh_license(repo: str):
    if repo in PINNED_LICENSES:
        pin, path, expected, spdx = PINNED_LICENSES[repo]
        out = subprocess.run(["gh", "api", f"repos/{repo}/contents/{path}?ref={pin}"],
                             capture_output=True, text=True, timeout=60, check=True)
        raw = base64.b64decode(json.loads(out.stdout)["content"])
        try:
            verify_license_bytes(raw, expected)
        except ValueError as exc:
            raise RuntimeError(f"pinned license hash mismatch for {repo}@{pin}") from exc
        return spdx, raw
    error = ""
    for _ in range(3):
        try:
            out = subprocess.run(["gh", "api", f"repos/{repo}/license"], capture_output=True, text=True, timeout=60)
        except FileNotFoundError:
            sys.exit("the GitHub CLI `gh` is required")
        except subprocess.TimeoutExpired:
            error = "GitHub request timed out"
            continue
        if out.returncode == 0:
            j = json.loads(out.stdout)
            text = base64.b64decode(j.get("content", "")).decode("utf-8", "replace")
            return (j.get("license") or {}).get("spdx_id") or "NOASSERTION", text
        if "HTTP 404" in out.stderr:
            return None, None
        error = out.stderr.strip() or f"gh exited {out.returncode}"
    # Do not rewrite the notice table with a false 'no license' claim after a network/auth/API failure.
    raise RuntimeError(f"could not retrieve license for {repo}: {error}")


def archived_license_labels():
    """Reuse the existing table's SPDX labels without refetching unrelated projects."""
    path = os.path.join(ROOT, "THIRD_PARTY_NOTICES.md")
    labels = {}
    with open(path, encoding="utf-8") as notice:
        for line in notice:
            match = re.match(r"\| \[([^]]+)\]\(https://github\.com/[^)]+\) \| [^|]+ \| ([^|]+) \|", line)
            if match:
                labels[match.group(1)] = match.group(2).strip()
    return labels


def main(argv=()):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--use-archived-licenses", action="store_true",
                        help="regenerate notices from checked-in license files and existing SPDX labels; no network")
    args = parser.parse_args(argv)
    archived = archived_license_labels() if args.use_archived_licenses else {}
    status = json.load(open(os.path.join(ROOT, "docs", "extensions-status.json"), encoding="utf-8"))
    entries = list(BASE)
    seen = {r for r, _, _ in BASE}
    by_repo = {}
    for e in status:
        st = e["status"]
        if not st.startswith("done:"):
            continue
        r = repo_of(e["url"])
        if r:
            by_repo.setdefault(r, []).append((e["name"], st[5:]))
    for r, items in sorted(by_repo.items()):
        if r in seen:
            continue
        seen.add(r)
        what = "; ".join(f"{n} -> `{t}`" for n, t in items)
        entries.append((r, "reimplemented", f"extension rewritten as {what}"))
    os.makedirs(os.path.join(ROOT, "licenses"), exist_ok=True)
    rows = []
    for repo, how, what in entries:
        fname = repo.replace("/", "__") + ".txt"
        license_path = os.path.join(ROOT, "licenses", fname)
        if repo in PINNED_UNLICENSED:
            _, lic = PINNED_UNLICENSED[repo]
        elif args.use_archived_licenses:
            if repo in PINNED_LICENSES:
                _, _, expected, spdx = PINNED_LICENSES[repo]
                with open(license_path, "rb") as source:
                    try:
                        verify_license_bytes(source.read(), expected)
                    except ValueError as exc:
                        raise RuntimeError(f"pinned archived license hash mismatch for {repo}") from exc
                lic = f"{spdx} ([text](licenses/{fname}))"
            elif repo in archived:
                lic = archived[repo]
                if "([text](licenses/" in lic and not os.path.isfile(license_path):
                    raise RuntimeError(f"archived license missing for {repo}")
            else:
                raise RuntimeError(f"no archived license label for {repo}; run full collector")
        else:
            spdx, source_text = gh_license(repo)
            if source_text:
                if isinstance(source_text, bytes):
                    with open(license_path, "wb") as output:
                        output.write(source_text)
                else:
                    with open(license_path, "w", encoding="utf-8", newline="\n") as output:
                        output.write(source_text)
                lic = f"{spdx} ([text](licenses/{fname}))"
            elif os.path.isfile(license_path):
                lic = f"upstream file unavailable; archived license ([text](licenses/{fname}))"
            else:
                lic = "no license file published"
        rows.append(f"| [{repo}](https://github.com/{repo}) | {how} | {lic} | {what} |")
        print(f"{repo:60s} {lic.split(' ', 1)[0]}")
    md = [
        "# Third-party notices",
        "",
        "stablediffusion-fx is licensed under the GNU Affero General Public License v3.0 (see LICENSE), because it ports",
        "code from AGPL-3.0 (AUTOMATIC1111 web UI, Forge, reForge) and GPL-3.0 (ComfyUI, Fooocus) projects.",
        "",
        "How each project is used:",
        "",
        "- **ported**: code or algorithms were translated from the project; its license applies to those parts.",
        "- **reimplemented**: the feature was written from scratch from the project's documented behaviour; no code was",
        "  copied. The license is listed for attribution.",
        "- **dependency**: installed separately (pip / Maven / external executable), not redistributed in this repository.",
        "- **data**: data files included in this repository.",
        "",
        "Projects without a published license file were not copied from; their features were re-implemented from the",
        "description of their behaviour only.",
        "",
        "| Project | Use | License | What |",
        "|---|---|---|---|",
    ] + rows
    open(os.path.join(ROOT, "THIRD_PARTY_NOTICES.md"), "w", encoding="utf-8", newline="\n").write("\n".join(md) + "\n")
    print(len(rows), "projects")


if __name__ == "__main__":
    main(sys.argv[1:])
