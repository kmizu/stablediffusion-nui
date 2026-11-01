# CD Tuner verification (#9)

Source: hako-mikan/sd-webui-cd-tuner `aafdbdda88b8d4e717fdb9aa9abe9e6df4943d7a` (AGPL-3.0), `scripts/cdtuner.py` and README, read as a behaviour reference; NUI re-implements it (`python/sdfx/cd_tuner.py`, `plugins/src/main/scala/sdfx/plugins/CdTunerPlugin.scala`).

## Behaviour

- AlwaysOn plugin `cd_tuner` with the source controls, labels, ranges and defaults: Detail d1/d2 and hr-Detail hd1/hd2 (-10..10), Contrast con1/con2, Brightness, saturation/saturation2 and the three colour axes (-20..20), hr-scaling, Stop Step / Hr-Stop Step (-1..20, -1 = never), Apply once, and the Color Map tab (Split by, Split Ratio `1,1`, colors, Mapping Stop Step 2, Strength 1, add from presets). One extra slider, `dis`, holds the source's 14th value (see Contrast 2). Like the source, nothing happens until a value is non-zero.
- Detail/Contrast 1 (model weights). On the first model evaluation of a pass the source rewrites five UNet tensors from their originals; in diffusers naming: `conv_in.weight * (1 - d1/100)`, `conv_in.bias * (1 + d1/50)`, `conv_norm_out.weight * (1 - d2/100)`, `conv_norm_out.bias * (1 + d2/50)`, `conv_out.bias + [con1/50, 0, 0, 0]`, computed in the parameter dtype as the source does. A hires pass is detected as the source does, by the latent area growing. In it hd1/hd2 replace d1/d2 when non-zero and hr-scaling multiplies them by the area ratio (the hires scale squared). Stop Step / Hr-Stop Step restore the originals at that step of the pass. Kept quirk: with Hr-Stop Step 0 the hires pass runs with the base-pass weights (unless Stop Step already restored them).
- Contrast 2, Brightness, Cyan-Red, Magenta-Green, Yellow-Blue. Once per pass, at step `total - 2 - dis`, the denoised prediction of every row is shifted by `-20/3 * ([con2/50] + colour table offsets)` per latent channel; with hires fix on only in the hires pass. The colour table is the source's SD1.x/SD2 table or its SDXL table. NUI writes the equivalent epsilon / v prediction change from the scheduler's sigma, before APG/Mahiro and other output hooks of `extensions.install`.
- saturation / saturation2 scale VAE decoder weights by `1 + sat * 0.075` (ldm `up.1.upsample.conv`, `up.0.block.0.nin_shortcut`) and `1 + sat2 * 0.02` (`up.3`/`up.2` upsample convs, `up.1.block.0.nin_shortcut`, `conv_in`, `conv_out`), mapped to diffusers `decoder.up_blocks.{3 - i}` / `conv_shortcut`, for the main passes of the batch (base decode, hires decode).
- Color Map. Split Ratio follows the source (Regional Prompter style, `;` or `|` rows, a single row gets `1 ` prepended; Horizontal/Vertical exactly as its slicing). colors: per region 3 values (brightness = their mean) or 4, separated by spaces or commas, regions by `;` or `|`. During the first Mapping Stop Step steps of a pass the model input of each region is blended: `cratio = sum|50 v|/10 / (1 + (1+step)^1.5/10) * strength/2 * hr * satt` (hr 0.25 in hires, satt `1.15 - 0.15 stop` below 5 else 0.4), cond rows `(1-cratio) x - shift`, uncond rows `(1-cratio) x + shift`, `shift = 6 * offset * mean|x_channel| * cratio`, channels 1..3, regions in order. The source edits k-diffusion's `x_in`, which also enters `x - sigma*eps`; NUI therefore corrects the model output by the same input change so the scheduler sees the source's denoised value. Without CFG the source's uncond copy is simulated (A1111 still evaluates it at CFG 1); PAG's perturbed rows get the cond edit. Kept quirk: with no detail setting, the map colours only the first hires step (the source's shape is not updated).
- Prompt syntax. The first `<cdt:...>` and `<cdtc:...>` tag of the positive prompt override the controls exactly as the source's `fromprompts`: `name=value` parts (`d1,d2,con1,con2,bri,col1,col2,col3,hd1,hd2,hrs,st1,st2,dis,sat,sat2`; `sp,md,cols,stc,str`) or positional values in that order, `;`-separated; any given value wins (also 0); `hrs` non-zero becomes 1; `md` `H`/`V`; `dis=` (any value) sets all 16 values to 0 and ignores the `<cdtc>` tag. Tags are removed from the text the encoder sees, like A1111 removes extra networks. The Scala plugin reads them (after the prompt processing plugins and X/Y/Z Prompt S/R) so infotext carries the effective values.
- Apply once / later passes. State follows the source per batch: base pass = `process_batch`, hires runs continue it, and follow-up passes (ADetailer, img2img hires fix) run after its `postprocess_batch`: weights restored, saturation off, and with Apply once nothing more. Without it, follow-up passes keep the detail, Contrast 2 and map state as in the source.
- Infotext uses the source keys and text: `CDT` (16 values, written when a detail/contrast/colour/saturation value is non-zero; slider values as sent, prompt values as Python floats such as `2.0`) and `CDTC` (`ratio_mode_colors_stop_strength`). PNG Restore and MCP `inspect_generation_metadata` restore the plugin; other PNGs switch it off in the desktop form.
- X/Y/Z: the source has no axes; it recommends Prompt S/R on `<cdt:...>`, which works here.
- Weights are written into the live tensors (or accelerate's offload map with sequential offload, or a PEFT base layer), so model and sequential CPU offload keep working, and every pass restores them in its cleanup on success, error and interruption. If a restore fails the model is unloaded instead of being left edited.

## Rejected or not provided

- Errors: Color Map with Neutral Prompt / Composable LoRA branches, Self Attention Guidance, Reference, APG, Mahiro, Latent Modifier or Dynamic Thresholding (they evaluate or recombine the model differently); Contrast 2 / Brightness / colour with Neutral Prompt or Composable LoRA branches; models other than SD1.x, SD2.x and SDXL UNets.
- Errors where the source silently ignores input: unknown tag names (for example the README's `by=`), parts without `=` or with two, more than 16 / 5 positional values, non-numbers. Errors where the source crashes: colour regions without 3 or 4 values, fewer colours than regions, empty Split Ratio fields. Extra colours give a warning.
- Not applicable: the Forge / Forge NEO paths (DiT models Flux, Z-Image, Anima, Krea2, MOSS-Image, 16 and 128 channel latents, Forge memory manager) and the refiner switch, since NUI loads none of those.
- UI only, not provided: Visualize Map and the "Toggle startup with Active" ui-config button. "add from presets" appends the preset to colors when generating.

## Differences

- Steps count pipeline steps. For second-order samplers (Heun, DPM2) the source counts sampler steps, so stop steps differ there.
- Dynamic Thresholding's hook runs before the Contrast 2 shift (the source shifts before CFG is combined).
- A PEFT LoRA on `conv_in`, `conv_norm_out` or `conv_out` keeps its own delta unscaled.
- The source's own paste maps a 16-value CDT one slot off (its `dis` lands in saturation). NUI restores by the writer's order and reads the 9-value pre-Brightness layout by its evident intent. Replay fills a missing CDT or CDTC with defaults, and a CDTC-only PNG enables the plugin (the source needs Active ticked).
- NUI's hires repeats each start from the base pass state.

## Evidence (CPU, 2026-11-01)

- `python/tests/test_cd_tuner.py` (33 tests): tables, `fineman`, `colorcalc`, `makecells` and colour parsing equal literal restatements of the source; a restated source Script and NUI's hooks run the same base, hires and follow-up passes on a small model and give identical weights at every evaluation and matching denoised values (detail, stops, hd/hrs, the Hr-Stop 0 quirk, Contrast 2 with `dis`, Color Map in both directions on SD and SDXL tables with batch 2 and its hires restart, the first-hires-step quirk, Apply once); eps and v conversions; no-CFG and PAG rows; offload maps and PEFT base layers; a real diffusers pipeline run with weights and VAE checked during sampling and restored after success and after an error; rejections; tag stripping.
- `CdTunerSuite` (9 tests): controls, inactive defaults, hook fields, exact CDT/CDTC text from sliders and prompt tags, `dis`, presets, malformed tags, Python float repr, PNG round trip, older CDT layouts and malformed replay. MCP: `InspectGenerationMetadataSuite` restores `cd_tuner` from CDT/CDTC and consumes both keys.
- Totals: Torch CPU 2,363 passed / 1 skipped; Scala `plugins/test` 187, `mcp/test` 89, `ui` `StateAndPluginsSuite` 12, `BuiltinsUiSuite` + `NativePresetsCoverageSuite` 9, all passed.

## Real-model checks (2026-11-01)

`scripts/dev/check_cd_tuner_real.py` drives the official MCP Python SDK 2.3.0 against the real engine
(receipts `outputs/cdt-real-sd15/receipt.json`, `outputs/cdt-real-sdxl/receipt.json`), model offload. SD1.5
`animekawa_v10` 512 px and SDXL `waiIllustriousSDXL_v170` 832x1216 both passed:

- Detail +5/+3, detail -5, detail with stop step 4, Contrast 2 + brightness + yellow-blue, saturation 1/2,
  the colour map (`0 0 5;5 0 0`, strength 0.4) and the `<cdt:d1=4;col1=-3>` prompt tag each changed the image.
- PNG replay through `inspect_generation_metadata` restored `cd_tuner` with no `CDT`/`CDTC` left unapplied and
  reproduced the image exactly.
- With Hires 1.5x/1.25x, hires detail (hd1 6, hr-scaling) and the colour adjustments changed the hires result;
  Hr-Stop Step 0 with only hd1 reproduced the hires baseline exactly (the kept source quirk).
- Cancelling an 80-step run with detail and saturation, then generating again, reproduced the baseline exactly, so
  model and VAE weights were restored.

Images inspected: positive detail adds fine texture (very strong on SDXL at +5), negative detail smooths, the colour
settings shift towards blue as expected (SD1.5 mean RGB 181/149/151 to 144/101/152), saturation is vivid, and the
colour map tints the top blue and the bottom red.
