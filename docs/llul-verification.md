# LLuL verification (#75)

Source: hnmr293/sd-webui-llul `6c5ac1b0aa29736e0aad3476939e4e05f25d20d7` (ledger index 102, target `llul`). Its `LICENSE` file holds only the heading `MIT License` (12 bytes, no grant text, no copyright line) and GitHub reports `NOASSERTION`, so it is recorded as having no usable licence (`PINNED_UNLICENSED` in `scripts/third_party.py`, no file under `licenses/`). The README and `scripts/` were read as a behaviour reference; NUI re-implements the behaviour (`python/sdfx/llul.py`, `plugins/src/main/scala/sdfx/plugins/LlulPlugin.scala`) and copies no code.

## Behaviour

- One AlwaysOn plugin `llul` (hook `llul`). At each hooked UNet layer a square region of the layer input (1/M of its width and height, M = 2^Multiplication, at the origin X / Y; centred when empty) is cut out and upscaled M times. It runs through the layer next to the untouched input, and the output is scaled back down and blended into the region: `region = interpolate(region, downscaled, weight)` with Lerp or SLerp. A mask (grey levels, resized to the region) multiplies the weight per pixel and forces Lerp, as in the source.
- Default (the source's simple mode): only the output layer (final norm, SiLU, conv), Bilinear up- and downscaling, Lerp, steps 5 to the pass' step count. Ticking "I know what I am doing." exposes the layers filter, Apply to (Resblock, Transformer, S. Attn., X. Attn., OUT), the up- and downscalers with AA, the interpolation, Start / Max steps.
- Layer names follow the source's scheme (`IN01_00_resblock`, `IN01_00_trans`, `IN01_00_00_sattn`, `M00_01_resblock`, `OUT03_00_00_xattn`, ...) derived from the diffusers blocks (input / output block index as in the original UNet). A layer is hooked when any filter entry is a substring of its name; the OUT layer ignores the filter, as in the source.
- The OUT layer evaluates the region on its own, so with weight 0, outside the step window or without matching layers the image is bit-identical to a run without LLuL (the doubled-batch fp16 convolution differs by up to 64 levels otherwise; found by the first real run). The other layer kinds double the batch like the source, so Weight 0 there equals the baseline only to float precision.
- Steps count UNet calls from 1 in each pass (the source's hook keeps one counter, so in A1111 a hires pass most likely starts past Max steps; not tested). Max steps 0 means the pass' step count. Hires passes keep the same relative region.
- Infotext uses the source keys (`LLuL Enabled`, `Multiply` (= 2^N), `Weight`, `Layers` and `Apply to` as Python list text, `Start steps`, `Max steps`, `Upscaler` (`nearest-exact`, `bilinear-aa`), `Downscaler`, `Interpolation`, `x`, `y`); PNG Restore and MCP `inspect_generation_metadata` restore them (Max steps equal to Steps restores as 0). The mask image is not in the PNG, as in the source.
- X/Y/Z: the source's axes `LLuL Enabled`, `Multiply`, `Weight`, `Layers`, `Apply to`, `Start steps`, `Max steps`, `Upscaler`, `Upscaler AA`, `Downscaler`, `Downscaler AA`, `Interpolation method`.
- ADetailer passes skip the plugin.

## Differences and not provided

- The extra output images that darken everything outside the effective area ("Add the effective area to output images") are not provided: AlwaysOn plugins cannot add images.
- Layer names are derived, not read from an LDM UNet; for SDXL the `IN`/`OUT` numbers count the original input / output blocks the same way.
- SLerp near-parallel vectors fall back to Lerp; the source's A1111 `slerp` helper blends them with swapped weights above a 0.9995 dot product.
- Anti-aliasing is rejected for the nearest, area and pooling scalers (torch has no such mode; the source fails there at run time). Multiplication 6 or more, a start step below 1, an image side below 128 and unknown names are rejected with a clear error.
- No combination checks: non-OUT layer kinds under hooks that change the batch (Neutral Prompt / Composable LoRA branches, Characteristic Guidance's extra evaluations) are untested.

## Verification

- Python (torch CPU, tiny UNet): `python/tests/test_llul.py`, 17 tests: settings, origin / region maths, scalers, SLerp, the OUT layer against an independent restatement of the source maths (crop, upscale, output layers, downscale, lerp; outside the region unchanged), bit-identical weight 0, step window, layer names and filters, every layer kind with weight 0 equal to the baseline and weight 1 changing it, mask, install errors. Full Python suite exit 0.
- Scala: `LlulSuite` (defaults, advanced box, mask, infotext round trip, upstream PNG text, malformed values, 12 axes), `InspectGenerationMetadataSuite` LLuL case, full `sbt test` exit 0.
- Real models through the official MCP SDK (`scripts/dev/check_llul_real.py`, stdio, packaged jar `target/stablediffusion-nui.jar`), seed 2468, Euler, CFG 5:
  - SD1.5 `animekawa_v10`, 512x512, 24 steps (`outputs/llul-real-sd15`), SDXL `waiIllustriousSDXL_v170`, 832x1216, 20 steps (`outputs/llul-real-sdxl`). Both: success.
  - 14 settings each change the image (default, strong, negative weight, multiply 3, corner origin, SLerp, bicubic + AA + area, pooling, resblock, transformer, attention, all layers, late step window, mask); 3 no-op settings (weight 0, start step after the end, filter matching nothing) are byte-identical to the baseline.
  - Centre-quarter region, weight 0.6 / multiply 2: mean difference from the baseline inside / outside the region 0.128 / 0.035 (SD1.5) and 0.153 / 0.080 (SDXL).
  - PNG round trip: `inspect_generation_metadata` restores the plugin with no `LLuL ...` key left unapplied, and replaying it reproduces the image byte for byte (SD1.5 and SDXL). Hires (Latent 1.5x) with LLuL changes the image; a cancelled job leaves no hook behind (the next run equals the baseline).
  - Images inspected on contact sheets. The default weight (0.15) gives clean images that differ in detail (more fur / texture detail in the region on both models). Weights from about 0.5 up, and the "everything" and resblock settings, put noise-like speckle into the region (zebra-like stripes on SD1.5, coloured speckle on SDXL); the corner origin shows a speckle patch in the corner at 0.5. The mask case applies the effect mainly on the white (left) side. The source's default is 0.15 and its README animation runs the weight from 0 to 0.2, so large weights are outside what it shows. Hires output is clean.
