---
title: Extensions
nav: Extensions
description: How to use the extensions built into Stable Diffusion NUI (AlwaysOn plugins and Scripts), with a reference grouped by purpose.
---

Stable Diffusion NUI ships rewritten versions of the A1111 / reForge built-in scripts and extensions, plus many extensions from the A1111 extension index, as built-in plugins. There is nothing extra to install: you use them straight from the generation tabs.

## AlwaysOn plugins and Scripts

There are two kinds of plugin.

| Kind | What it does | Where it is |
|---|---|---|
| AlwaysOn | Applies to every generation while it is enabled. You can enable several at once. | A collapsible section per plugin near the bottom of the generation form. |
| Script | Takes over the whole job (builds a grid, generates many times, and so on). Only one can be selected at a time. | The dropdown in the **Script** card of the generation form. |

![The full txt2img form, with the Script card and the AlwaysOn plugin sections at the bottom](shots/txt2img-form.png)

### Using an AlwaysOn plugin

1. Open the **txt2img**, **img2img** or **Inpaint** tab.
2. Scroll down the form to the plugin's section.
3. Tick the checkbox at the left of the section header. An enabled section has a highlighted header.
4. Expand the section, adjust its values and generate as usual.

The plugin's description appears at the top of its section. Hovering over the header shows the same text.

### Using a Script

1. In the **Script** card of the generation form, pick a script from the dropdown. The default is **None** (no script).
2. The selected script's settings appear below the dropdown.
3. Generate as usual and the script runs the job.

Scripts and AlwaysOn plugins work together. For example, every image of an **X/Y/Z plot** also gets the AlwaysOn plugins you have enabled, and the **X/Y/Z plot** axes include settings contributed by plugins.

### Settings per tab

- Plugin values and the selected script are kept separately for the **txt2img**, **img2img** and **Inpaint** tabs, and are restored the next time you start the app.
- The **Inpaint** tab uses the same plugins as img2img.
- Some plugins only work in one tab. In tabs they do not support, their section or dropdown entry is not shown. The reference below notes plugins that are limited to one tab.
- These plugins appear in a dedicated place instead of a generic section:
    - **Hires Fix Tweaks**: inside the **Hires. fix** section of txt2img
    - **Inpaint Difference**: inside the image panel of the **Inpaint** tab
- **Tokenizer** and **VAE Tools** are not shown in the generation forms; you use them as tabs of the same name (see [Tools](tools.html)).

### Hiding plugins

To remove a plugin from the UI, add its id to `disabled_plugins` in `config/settings.json`. The same file stores control defaults under `plugin_values`. Edit the file while the app is closed; the change takes effect the next time it starts.

### Using plugins from MCP

AI agents can use the same plugins. Call `list_always_on` to discover AlwaysOn plugins and their values, then pass them in the `always_on` object of a generation request. These values apply to that job only and do not change the desktop settings. For Scripts, call `list_scripts` to list them and `run_script` to run one. **Custom code** can never be run from MCP. See [MCP](mcp.html) for details.

## AlwaysOn plugin reference

Names in the tables are the section names shown in the app.

### Prompt processing

| Name | What it does |
|---|---|
| **Prompt Format** | Removes duplicate spaces and commas (and, optionally, duplicate tags) before generating. |
| **Prompt Filter** | Removes banned words and applies "find => replace" rules to both prompts. |
| **Negative Prompt Weight** | Applies one weight to the whole negative prompt. |
| **NovelAI prompt converter** | Converts NovelAI emphasis `{tag}` / `[tag]` into A1111 weights before generating. |
| **Style variables** | Inserts saved styles into prompts with `{style:Name}` (or `$Name`). |
| **Weight Gradient** | Changes the weight of words written like `{words::0-1:e}` during sampling. |
| **NegPiP** | Negative weights such as `(black hair:-1)` suppress a concept in the positive prompt and boost it in the negative prompt. |
| **Neutral Prompt** | Combines prompts by their predictions with `AND`, `AND_PERP`, `AND_SALT`, `AND_TOPK` and more. |
| **Prompt Fusion** | Interpolates prompt embeddings during each image's sampling, using curves, attention ramps and variables. |
| **Model Keyword** | Inserts keywords for the checkpoint and selected LoRAs into the prompt (see [Tools](tools.html)). |

### Randomising settings

| Name | What it does |
|---|---|
| **Randomize** | Picks sampler, schedule, steps, CFG and size at random for each generation (reproducible from the seed). |
| **Random Resolutions** | Each generation uses a random size from a list (or from a pixel budget and aspect ratios). txt2img only. |

### LoRA

| Name | What it does |
|---|---|
| **LoRA Block Weight** | Weights each U-Net block of a LoRA separately (presets or explicit weights) and limits a LoRA to a step range. |
| **Composable LoRA / LyCORIS** | Keeps each adapter with its own `AND` prompt, and controls negative conditioning and per-step weights. |

For general LoRA use, see [LoRA and embeddings](lora-embeddings.html).

### Guidance (CFG)

| Name | What it does |
|---|---|
| **FreeU** | Re-weights U-Net backbone and skip features (FreeU v2). |
| **Dynamic Thresholding (CFG-Fix)** | Clamps the guidance so high CFG scales look like a lower scale. |
| **APG is now your CFG** | Adaptive Projected Guidance: replaces CFG and reduces oversaturation at high CFG. |
| **Mahiro CFG** | Leans towards the positive prompt where it agrees with CFG. |
| **RescaleCFG** | Rescales the guided prediction's standard deviation to the positive one (for v-prediction / ZTSNR models). |
| **Perturbed-Attention Guidance** | Adds guidance from a perturbed self-attention pass (PAG). |
| **Semantic Guidance** | Adds the comma-separated concepts in its own prompt to the image and removes those in its negative prompt, by editing the prompt conditioning after the warmup steps, so the composition mostly stays. The effect is strong even at the default edit guidance scale of 1.0, and removing concepts can break the image, so tune it between 0.3 and 1.0. |
| **Characteristic Guidance** | Corrects CFG at large CFG scales by iteratively shifting the U-Net input, which reduces over-saturation and odd colours, backgrounds and details while keeping the prompt adherence of the large scale. It is slow: each step runs several extra U-Net evaluations, so try plain CFG first and then add it with the same seed. Steps whose iteration does not converge use plain CFG (the log reports how many converged); larger Regularization Strength and Range Over Time converge more easily and stay closer to CFG. For SDXL start with Regularization Strength 5. Setting CHG End Step to about 0.4 is much faster with a similar result. |
| **Incantations** | Four guidance techniques, each with its own Active box. Smoothed Energy Guidance blurs the middle-block self-attention of the unconditional prediction; keep CFG near 3 and adjust SEG Blur Sigma (11 is the strongest; 0 turns it off). S-CFG evens out CFG over the regions of the prompt's tokens and needs much memory. Perturbed Attention Guidance adds guidance from an extra pass with a disabled self-attention (raise PAG Scale from 0, for example to 3; Saliency-Adaptive Noise Fusion mixes it with CFG). The CFG Scheduler changes CFG over the steps and applies it only inside the noise interval (SDXL: about 0.28 to 5.42); Constant ignores the interval. Multi-Concept T2I-Zero keeps concepts apart: CTNMS is on by default, CbS needs a Correction Strength above 0. Steps count from 0 per pass. SEG, S-CFG, PAG and the scheduler need CFG above 1. Cannot be combined with the PAG and SAG plugins, Reference-only, Neutral Prompt or Composable LoRA; PAG, S-CFG and the scheduler not with other CFG changes either. Seek for Incantations is not included. |
| **Sonar** | Momentum on the sampler update: each step follows a mix of the current latent difference and a decaying history of the earlier ones, which moves the result to a similar image nearby (the source's own wording: "perhaps" better). While it is on, the Base Sampler chosen here (Euler a, Euler or the plain Naive sampler) replaces the sampler of the request. Momentum current 1 turns the momentum off; keep it at 0.85 or more, and keep the sign at pos and the history start at zero unless you want to experiment (history start `rand_init` collapses the image at ordinary settings). Naive can be pulled towards a Reference image by a linear or Euler step inside a step range (values of 1 or less count as a fraction of the steps; the strength is the slider divided by 10). Upscaling resizes the finished image by a ratio or to a size, covering the target and cropping the rest. For the source's grid search use X/Y/Z axes of Sonar momentum and Sonar momentum_hist. The reference image is not stored in the PNG; choose it again to repeat such a picture. |
| **Batch Face Swap** | Detects the faces of every finished image and redraws each one (after ADetailer, also in img2img). The face pass has its own prompt and negative prompt (`[PROMPT]` inserts the main one, `[SEP]` gives each face, left to right, its own), steps, CFG scale and sampler, a face area resolution (the magnification of the redrawn crop), an elliptical or rectangular mask scaled around each face, mask blur, crop padding, denoising strength and a colour match. Detectors: the Bingsu/adetailer YOLO face models (downloaded on first use, need the `ultralytics` package) or `opencv_face` (no download, finds few anime faces). Not included: a different checkpoint for the face pass, the img2img folder input. |
| **LLuL** | Local Latent upscaLer. A square region of the image (1/2^N of its width and height, centred or at the X / Y position) is upscaled inside the U-Net, so it is drawn with finer detail, and the result is blended back into the image with the Weight (0.15 by default, negative values work too). A mask image scales the Weight per pixel (white = full, black = none). The advanced settings (tick "I know what I am doing.") choose the layers: only the output layer by default, or resblocks, transformers and self / cross attention filtered by the source's layer names (IN01, M00, OUT03, ...), the up- and downscalers with anti-aliasing, Lerp or SLerp, and the first and last step. Steps count from 1 in each pass. Not applied in ADetailer passes. The extra output images that show the effective area are not included. |
| **Aesthetic Gradients** | Moves the CLIP text encoder towards an aesthetic embedding for each prompt. An aesthetic embedding is the mean CLIP ViT-L/14 embedding of a few pictures, stored as a `.pt` file in an `aesthetic_embeddings` folder (set `SDFX_AESTHETIC_EMBEDDINGS`, or the default `~/.cache/stablediffusion-nui/aesthetic_gradients/embeddings`); create one with `python -m sdfx.aesthetic_gradients create NAME IMAGES...`. For each prompt the encoder takes the given number of Adam steps (learning rate 0.0001 by default) towards the embedding, and the conditioning is blended with the original by the Aesthetic weight (0.9 by default). Slerp interpolation mixes the embedding with the CLIP embedding of an aesthetic text, and one option applies everything to the negative prompt instead. Works with SD 1.x and SDXL (only SDXL's CLIP-L half is personalised); SD 2.x is rejected. CLIP ViT-L/14 (1.7 GB) is downloaded once on first use. There is no embedding-creation dialog yet. |
| **Clip I/O** | Export and feed CLIP conditioning. **Clip Output** writes the conditioning of the Positive (and optionally Negative) prompt to a `.csv` or `.pt` file in the conditioning folder (`SDFX_CLIP_IO_DIR`, default `~/.cache/stablediffusion-nui/clip_io/conditioning`) while generating: one row per token and one column per dimension (**Transpose matrix** swaps them), **Don't add bos / eos / pad tokens**, **Ignore emphasis** (all weights 1; the emphasis syntax is still parsed), **Bypass conditioning normalization** (no mean rescale after weighting), **Add token strings**, **Overwrite**; an existing file is kept unless Overwrite is on. **Clip Input** bypasses CLIP: the Positive / Negative prompt boxes become lists of conditioning files (Simple: space separated names, quote names with spaces, a name without extension also tries `.csv` then `.pt`, `"""text"""` is encoded by CLIP, everything is concatenated along the token axis). **Directive** mode adds `?prompt("""text""", clip_skip=2, no_padding=False)` (lists allowed) and `?eval(expression)` / `?eval_ORDER(expression)`, which does component-wise arithmetic on `i` (the conditioning), `o`, `t` (token index) and `d` (dimension index) with `pi`, `e`, `sin`, `cos`, `exp`, `log`, `sqrt`, `abs`, `min`, `max`, `clamp`, `where` and more. `eval` is a restricted arithmetic interpreter, not Python, and `?exec` is not provided. Prompt editing, AND and BREAK are not parsed in input prompts. Input replaces only the generation's own prompts (not ADetailer or Hires override prompts); SDXL `.pt` files carry the pooled vector, a CSV uses the empty prompt's. The width must fit the loaded model (768 SD1.x, 1024 SD2.x, 2048 SDXL). Settings are stored in the infotext as `Clip Input positive` / `Clip Output ...` and restored from PNG and MCP; a replay needs the same conditioning files. |
| **Aesthetic Score** | Scores every final image (after censoring and any upscale, just before it is saved) and writes the score to its infotext and, by default, to PNG info chunks; the pixels do not change. **Aesthetic Image Scorer** (on by default): CLIP ViT-L/14 with the improved aesthetic predictor, 0 to 10, written as `aesthetic_score` with one decimal. **Aesthetic Scorer**: the simulacra predictor on CLIP ViT-L/14 or ViT-B/16, 0 to 10, written as `Aesthetic Scorer ViT-L/14` (or B/16) and as the PNG chunk `score`. **cafe-aesthetic**: three classifiers, aesthetic / not_aesthetic, style (anime, real_life, 3d, manga_like, other) and waifu / not_waifu; the infotext holds the most probable class with its probability (`Cafe waifu: waifu 0.9100`), the PNG chunk `cafe_*` holds all probabilities. Optional: sampler, cfg_scale, sd_model_hash and seed as PNG chunks, and the experimental `hash` (md5 of the generation parameters; not comparable with the web UI's, because the sampler names and model hashes differ). The models download on first use and are checked against pinned hashes (CLIP ViT-L/14 1.7 GB, ViT-B/16 0.6 GB, each cafeai classifier 0.4 GB); they wait in RAM and use the GPU only while an image is scored, or never with Force CPU. Scores are a taste model, not a quality guarantee. Windows file tags and categories and EXIF are not provided. |
| **Self Attention Guidance** | Guides with blurred self-attention regions. SD1.x txt2img only; needs DDIM / UniPC / DPM++ samplers. |
| **CFG Schedule** | Moves the CFG scale from a start value to an end value over the steps (needs CFG > 1). |
| **CADS** | Adds scheduled noise to the prompt conditioning for more diverse results. |

### Sampling and latents

| Name | What it does |
|---|---|
| **Detail Daemon** | Tells the model there is less (or more) noise than there is, over a schedule, to add or remove detail. |
| **Vectorscope CC** | Applies brightness, contrast, saturation and RGB offsets to the latent noise while sampling. |
| **CD Tuner** | Changes the amount of detail (Detail 1/2, with separate hires values), contrast, brightness, colour tone and saturation by adjusting a few model and VAE weights and one step of the sampling. Color Map steers the colour of image regions in the first steps, which helps the prompt pick up colours. Values of about 5 or less are a good start; more detail also means more noise. `<cdt:d1=2;col1=-3>` and `<cdtc:...>` in the prompt override the settings, also from X/Y/Z Prompt S/R. |
| **Latent Mirroring** | Mirrors or rotates the latent during the first steps, from balanced compositions up to perfect reflections. |
| **Asymmetric Tiling** | Makes seamless tiles, configured separately for the X and Y axes (U-Net and VAE). |
| **Anti-burn** | Skips the last few steps and averages the latents of the steps before them for smoother results. |
| **ReSharpen** | Pushes every step further along its change (more detail) or back (smoother). |
| **Diffusion Color Grading** | Recentres the latent colour channels (removing casts or tinting towards a colour) and stretches the range. |
| **Latent Modifier** | Sharpness, tonemapping, contrast, CFG-drift correction, noise and spectral tweaks. |
| **Advanced Model Sampling** | Overrides the model's prediction type (eps / v-prediction) and zero terminal SNR. |

### Large images, speed and memory

| Name | What it does |
|---|---|
| **HyperTile** | Speeds up large images by tiling self-attention. |
| **Kohya HRFix (Deep Shrink)** | Shrinks a deep U-Net block in early steps so high resolutions stay coherent. |
| **MultiDiffusion (tiled diffusion)** | Denoises overlapping latent tiles and blends them, for very large images. |
| **Token Downsampling** | Reduces self-attention keys and values to trade detail for speed. Cannot be combined with PAG or HyperTile. |
| **Agent Attention** | Merges similar tokens in the U-Net self-attention and attends through a few agent tokens (AgentSD), for speed and often finer detail. The first-pass settings apply until Second Pass Step, where the effect stops, or switches to the second-pass settings when Use Second Pass is on. Apply to Hires. Fix Only moves it to the hires pass. For SDXL set Max Downsample to 2 or more; at 1 nothing changes. Cannot be combined with PAG, SAG, HyperTile, Token Downsampling, Reference-only or ControlNet-LLLite. |
| **Never OOM** | Lowers peak VRAM for the job with sliced attention and VAE, and tiled VAE. |
| **GPU temperature guard** | Pauses sampling above the sleep temperature and resumes below the wake temperature. |

### Hires. fix and img2img

| Name | What it does |
|---|---|
| **Hires Fix Tweaks** | Refines one first-pass result with independent seeds, prompt modes and styles. Found inside the **Hires. fix** section. |
| **img2img Hires Fix** | Upscales and refines each completed image in txt2img, img2img or Inpaint, with pixel upscaling and VAE re-encoding. Separate from the native latent Hires. |
| **Conditioning Highres Fix** | Adjusts the image conditioning of 9-channel inpainting models relative to the denoising strength. |

For Hires. fix itself, see [Generating images](generate.html).

### Image prompts and control

| Name | What it does |
|---|---|
| **Reference only** | Self-attention also attends to a reference image, keeping its style or subject without a model. |
| **Revision (SDXL image prompt)** | SDXL: adds an image's CLIP-G embedding to the prompt (the encoder downloads once). |
| **IP-Adapter** | Image prompt adapter. Put the models in the ControlNet folders. |
| **ControlNet-LLLite** | kohya's lightweight ControlNet for SDXL. Without an image it uses the init image. |
| **PhotoMaker** | SDXL: takes a person's identity from photos. Put the trigger word after the class word, e.g. `a man img`. txt2img only. |
| **ControlNet Fastload** | Loads ControlNet units from an image or `.cni` file and saves them with generated images (see [ControlNet](controlnet.html)). |

### Inpainting and repair

| Name | What it does |
|---|---|
| **Soft Inpainting** | Blends original and generated content smoothly along a soft (blurred) mask. img2img / Inpaint only. |
| **Fooocus Inpaint** | SDXL inpainting with the Fooocus patch. img2img / Inpaint only. |
| **Inpaint Difference** | Creates an inpaint mask from the difference between a base and an altered image. Found inside the image panel of the **Inpaint** tab. |
| **Restore faces** | Runs GFPGAN or CodeFormer on detected faces. |
| **ADetailer** | Finds faces, hands or people in each result and redraws them at full resolution. |
| **NSFW Checker** | Checks each final image after ADetailer and Hires refinement, just before it is saved, with up to three checkers. **NudeNet** (on by default) detects NSFW regions with the bundled detector (on the CPU) and blurs, pixelates or fills them, also in live previews. By default it censors exposed breasts, genitalia and anuses; you can set a threshold and expansion for each of the 18 categories (a threshold of 1 turns a category off). When something is censored, an uncensored copy is saved with the `-before-censor` suffix unless you turn that off, and the mask can be saved as `-censor-mask`. **SD safety checker** replaces a flagged image with a black image; live previews are checked only if you ask. **NSFWJS filter** blurs the whole image when its top class is NSFW at the safety level (**safe**: hentai, porn, sexy; **questionable**: hentai, porn; **explicit**: off), in live previews and in every saved file. The last two download their model on first use (1.2 GB and 10 MB) and check its hash. No checker catches everything. |

### Output and records

| Name | What it does |
|---|---|
| **Save intermediate images** | Keeps the denoising steps as PNG frames or a video, separately from the final images. |
| **Grid image numbers** | Labels grid tiles with their saved image numbers and X/Y/Z values. |
| **PNG resource hashes** | Records hashes of the local models and other resources used in the PNG generation metadata. |

## Script reference

### Grids and comparisons

| Name | What it does |
|---|---|
| **X/Y/Z plot** | Generates a grid of images varying up to three parameters. |
| **Prompt matrix** | Generates every combination of the `|`-separated prompt parts. |
| **Test my prompt!** | Generates the prompt with each part removed in turn (same seed) and a labelled grid. |
| **LoRA queue** | Generates once per selected LoRA (all LoRAs when empty) and makes a grid. |
| **Batch checkpoints and prompts** | One generation per line of `checkpoint | prompt template` (`{prompt}` is the current prompt). |

### Generating and varying prompts

| Name | What it does |
|---|---|
| **Prompts from file or textbox** | Runs one generation per line; lines may use options such as `--prompt` and `--steps`. |
| **Dynamic Prompts / Wildcards** | Builds prompts from templates such as `{a|b|c}` and `__wildcards__` from `wildcards/*.txt`. |
| **One Button Prompt** | Generates complete random prompts (subject, style, setting, lighting and more) and renders them. |
| **Booru prompt** | Turns the tags of a booru post into the prompt: a random result of your search tags (every image gets its own post) or specific posts by link, id or md5. Danbooru, AIBooru, e621, Gelbooru, Rule34, Safebooru, Xbooru, Konachan and Yande.re; rating filter, tag categories, remove/append/shuffle/limit tags, mixing, chaos and negative mode, optional img2img from the post's picture and random LoRAs. Gelbooru and Rule34 need a user id and API key. The buttons under the form add the tags of a post or a random post to the visible prompt. The tags are saved with the image; later replays need no network. |
| **StylePile** | Mixes weighted image types, artists and art movements, with sequential and random prompt variables. |
| **Scramble prompts** | Creates prompt variations by shuffling and randomly dropping comma-separated tags. |
| **Tweak weights** | Creates variations by randomly re-weighting tags that match a pattern (all tags when empty). |

### Animation and video

| Name | What it does |
|---|---|
| **Seed travel** | Makes frames between seeds by interpolating the noise, and saves the frames and an animated GIF. txt2img only. |
| **Shift attention** | Write `(word:0.5~1.5)` in the prompt; the frames sweep the weight. Saves the frames and a GIF. |
| **Steps Animation** | Creates a movie from the denoised intermediate steps of one generation. |
| **AnimateDiff** | Generates a video with an AnimateDiff motion module (SD 1.x modules for SD 1.x checkpoints, SDXL modules for SDXL ones). The module (a file such as `mm_sd15_v3.safetensors`, a diffusers folder, or a Hugging Face repo id like `guoyww/animatediff-motion-adapter-v1-5-2`) is inserted into the loaded checkpoint at run time, so LoRAs, embeddings and the other settings keep working. Set the number of frames, FPS, loop count and the save formats (GIF, MP4, WEBP, WEBM; PNG saves every frame to the gallery, TXT an infotext file). Longer videos than the context batch size run as overlapping windows (overlap, stride, closed loop N / R-P / R+P / A). Prompt travel: head lines, `frame: prompt` lines, tail lines. Img2Vid in the img2img tab blends the init image (and an optional last frame) into the start noise; FreeInit refines the start noise (txt2img). The first frame goes to the gallery with the infotext (key `AnimateDiff`); the videos are saved beside it. Batch size 1; Hires. fix, ControlNet V2V, SparseCtrl, Motion LoRAs and FILM interpolation are not provided. Needs ffmpeg on PATH for MP4 and WEBM. |
| **SD-CN-Animation** | Video to video: restyles a video frame by frame with the current checkpoint, prompt, LoRAs and ControlNet units. Each frame starts from the previous styled frame warped onto the next source frame with RAFT optical flow, and an occlusion mask (where the flow cannot be trusted) is redrawn; step 1 (processing strength) restyles, step 2 (fix frame strength) cleans up, and every result is histogram-matched to the source frame. Choose the video file, the number of frames (0 = all), frame skip and FPS; save MP4, GIF, WEBM or WEBP beside the first frame (and, optionally, every frame in the gallery). Add a ControlNet unit (canny, depth or softedge) and leave its image empty: each unit receives the current video frame. Extra params: occlusion blur / trailing / flow / diff multipliers, "inpaint occlusions" instead of blending, blend alpha, step seeds, RAFT model. The first frame goes to the gallery with the infotext (key `SD-CN-Animation`). Needs ffmpeg on PATH for MP4 and WEBM; RAFT weights are downloaded by torchvision on first use. Text-to-video (it needs the FloweR network) is not provided. |
| **Stable Video Diffusion** | Animates the init image with an SVD model and saves a GIF plus the first frame. img2img only. |
| **Zero123 novel view** | Renders the object in the init image from another angle. img2img only. |

### img2img, upscaling and outpainting

| Name | What it does |
|---|---|
| **Loopback** | Runs img2img repeatedly, using each output as the next input. img2img only. |
| **SD upscale** | Upscales the image, then refines it tile by tile. img2img only. |
| **Ultimate SD upscale** | Upscales, then redraws tile by tile with masked inpainting; optional seam fixing. img2img only. |
| **Loopback Scaler** | Repeats img2img while the size grows step by step towards the maximum (or scaled) size along an easing curve, changing the denoising strength each loop; optional sharpness, colour and filter adjustments on the final image. Batch size must be 1. img2img only. |
| **Old SD firstpasser** | Generates the first pass with another checkpoint (an SD 1.x one for an SDXL main model, or the reverse), upscales it and lets the main checkpoint finish it as img2img, so SD 1.x LoRAs, embeddings and ControlNet units can steer an SDXL model. Pick the checkpoint for the family you use (Auto = the other family than the main checkpoint), the firstpass steps, the upscaler and the denoising of the main pass. LoRA tags, manual LoRAs and ControlNet units apply in the firstpass only unless "LoRAs in the main pass" is ticked. Hires fix runs afterwards on the main checkpoint. Both checkpoints are loaded one after the other, which takes time. txt2img only. |
| **Latent Upscale** | Encodes the image before resizing, interpolates its latents, then denoises for the full step count. img2img only. |
| **img2img alternative test** | Estimates the init image's noise so a changed prompt edits it (DDIM noise inversion). img2img only. |
| **Poor man's outpainting** | Extends the image in the chosen directions and inpaints the new area. img2img only. |
| **Outpainting mk2** | Extends the canvas with colour-matched noise and inpaints it (use high denoising, e.g. 0.8). img2img only. |
| **Depth aware img2img mask** | Estimates depth and inpaints only the parts between the near and far thresholds. img2img only. |
| **Inpaint background** | Masks the people and redraws only the background from the prompt. img2img only. |
| **Seamless tile inpainting** | Moves the tile edges to the centre, then inpaints only their seams. img2img only. |

### Image processing

These scripts process the init image in img2img, and each generated image in txt2img.

| Name | What it does |
|---|---|
| **Upscale (extras)** | Upscales the image with an upscaler. |
| **Waifu2x upscale** | Upscales 2x to 16x with the Anime, Photo or CARN V2 models. Weights download on first use. |
| **FSR-style upscale (CAS)** | Fast non-AI upscale (Lanczos followed by sharpening). |
| **Restore faces (extras)** | Runs GFPGAN / CodeFormer on the image. |
| **Caption (BLIP / WD14 tags)** | Captions the image and saves a copy with the caption in its infotext and a `.txt` next to it. |
| **Create flipped copies** | Saves horizontally and vertically flipped copies. |
| **Split oversized images** | Splits very wide or tall images into overlapping pieces of the target size. |
| **Auto-sized crop** | Picks a 64-divisible size close to the target area and the image's aspect, then crops and resizes. |
| **Auto focal point crop** | Crops to the target width x height around the most detailed region. |
| **Remove background** | Cuts out the subject and saves a transparent PNG (and, optionally, the mask). The rembg models need a separate install. |
| **Person mask** | Saves a black-and-white mask of the people in the image. |
| **Pixel art** | Turns the image into pixel art with downscaling, a reduced palette, dithering and outlines. |
| **Color enhance** | Stretches colourfulness to the full range while keeping lightness and hue. |
| **Color correction** | Matches the colours of a reference image. An empty reference uses the original input. |
| **NSFW Checker** | With **NudeNet**, censors the regions NudeNet detects and/or the white areas of a custom mask image. **Entire image (always)** censors the whole image; **No censor** with **Output only the mask** saves only the mask. With **NSFWJS** or **SD safety checker**, blurs or blacks out the whole image when it is flagged; **Only report** keeps the image and writes the result to its infotext. |
| **Aesthetic Score** | Scores the image with the chosen scorers (Aesthetic Image Scorer, Aesthetic Scorer, cafe-aesthetic) and writes the result to the infotext; the pixels do not change. Use Extras batch from a directory to score a folder. Same models and keys as the generation plugin. |

You can also upscale or colour-correct a single image in the **Extras** tab.

### Model tools

| Name | What it does |
|---|---|
| **Checkpoint merger** | Merges two or three checkpoints into a new `.safetensors` next to model A. Press **Generate** to run it. |
| **Checkpoint converter** | Writes a cleaned copy of a checkpoint (precision, EMA weights, junk keys, baked VAE and more). Press **Generate** to run it. |

### Other

| Name | What it does |
|---|---|
| **Custom code** | Runs Python in the engine. Works only when **Allow custom code** is on in Settings → **Engine**. |

## A1111 extension index status

The status of every entry in the A1111 / reForge extension index (353 entries) is listed in [docs/EXTENSIONS.md](https://github.com/kmizu/stablediffusion-nui/blob/main/docs/EXTENSIONS.md) on GitHub. Each entry is `done` (built in), `todo` (planned) or `na` (does not apply to a desktop app).

Some extensions are implemented as part of the app or as tabs rather than as plugins (the gallery, the Image Browser, the Character Searcher, the Panorama viewer, the Interrogator, Styles, the embedding tabs, the interface language and others). These are covered in [Tools](tools.html), [Image Browser](image-browser.html), [Character Searcher](character-searcher.html), [LoRA and embeddings](lora-embeddings.html) and [Interface language](localization.html) (the Bilingual Localization extensions and the A1111 language packs).

## Adding external plugins

You can write your own plugins and add them.

- Implement `sdfx.core.plugins.api.Plugin` and register it with `java.util.ServiceLoader` in a jar.
- A plugin is either AlwaysOn or Script. The controls it declares are drawn as a form in the GUI and exposed to MCP as a JSON schema.
- Put the jar in the app's `plugins-ext/` folder and restart the app to load it.
- A plugin with the same id as a built-in one is not loaded (the built-in wins).

For sample code and the full steps, see [how to write external plugins](https://github.com/kmizu/stablediffusion-nui#外部プラグインの作り方) in the README.
