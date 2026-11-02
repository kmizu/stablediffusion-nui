# Aesthetic Gradients verification (#2)

> 2026-11-03 作業中: 以下は以前の検証記録。GUI と MCP での embedding 作成は追加作業中で、下の「GUI/MCP では作成できない」という記述は現行の作業ツリーには当てはまらない。新しい操作方法は [日本語ガイド](aesthetic-embeddings.md) を参照。SD1.5 と SDXL の実モデルを公式 MCP SDK から使い、作成・一覧・適用・既定フォルダからの PNG 再生成を確認した。GUI の実モデル確認、別フォルダと同名ファイルの再現、配布版の検証は未完了。Issue #2 は未完了のまま。

> 追加の作業ツリー検証: embedding の内容を SHA-256 で識別する復元、登録フォルダの別プロセス間ロック、出力先変更後の MCP 検査、未発見・曖昧な場合の拒否は、CPU テストと fake worker を使う実画面テストで確認した。Python 25 件、Scala core 4 件・plugin 6 件・engine 40 件・MCP 75 件が成功（`outputs/codex-aesthetic-provenance-final3.log`、`outputs/codex-aesthetic-replay-review-fixes.log`）。これらの変更後の実モデルと配布版は未検証。旧 PNG は hash がないため、名前が一意でも元ファイルの内容は保証できない。

> 同日の追試: hash 対応後の JAR SHA-256 `0ebd75e16d0335ba93aef48ed89637aa34e88379c2b64385ab955d66f31d4946` を使い、公式 MCP SDK の stdio 経由で実 SD1.5・SDXL の embedding 作成、生成、PNG 内の hash とファイル内容の照合、PNG からの再生成を確認した。両モデルとも画像を目視し、再生成の RGB は一致した（`outputs/aesthetic-hash-sd15/receipt.json`、`outputs/aesthetic-hash-sdxl/receipt.json`）。これはソース作業ツリーの JAR による確認で、Windows ZIP 展開版や GUI の実モデル確認ではない。

> 配布候補 ZIP の追試: SHA-256 `65129737d33a63f117d431f14240eae701aee7013bcfceaa2044bc812eae6fbb` の ZIP を展開し、同梱 JAR が上記と一致し、配布物 338 ファイルの照合で欠落・差分がないことを確認した。展開した `SDNUI.exe` と同梱 JVM を公式 MCP SDK から使い、SD1.5・SDXL で作成・生成・hash 照合・RGB 一致の再生成を確認、画像を目視した（`outputs/aesthetic-package-sd15/receipt.json`、`outputs/aesthetic-package-sdxl/receipt.json`）。エンジンの Python は既存の smoke venv を使った。GUI からの実モデル操作は未検証。

> GUI 追試: 実 MainWindow から SD1.5・SDXL の参照画像による作成・適用・生成を実行し、PNG の hash と embedding の一致、適用前後の画像の変化を確認した（`outputs/aesthetic-real-gui-sd15/receipt.json`、`outputs/aesthetic-real-gui-sdxl/receipt.json`）。両モデルの画像を目視した。参照画像は実画面のリストへ投入し、OS のファイル選択ダイアログは手操作していない。sbt から起動した MainWindow の検証であり、配布 EXE の GUI 経路は未検証。

> 全体回帰: JDK 21 の `sbt test` は exit 0（集計は 183、38、335、42、250 件、うち1件 skip；`outputs/aesthetic-full-scala.log`）。Torch 環境の Python 全体は CPU 指定で 2,962 pass・2 skip、exit 0（`outputs/aesthetic-full-python-cpu-correct.log`）。初回の 33 件失敗は、Windows で空の `CUDA_VISIBLE_DEVICES` が GPU を隠さず tiny CPU モデルと不一致になった31件と、子プロセスに worktree の `PYTHONPATH` がなかった2件。`CUDA_VISIBLE_DEVICES=-1` とこの worktree の `python` を `PYTHONPATH` に設定して対象4ファイル133件も再実行し、すべて成功した。

## 以前の実装と検証の記録

以下は上記の追加実装より前の記録です。当時の未対応事項や未検証事項を現在の状態と混同しないでください。

Source: AUTOMATIC1111/stable-diffusion-webui-aesthetic-gradients `2624e5dd4902cb731287b426ad483e741ece85bb` (ledger index 8, target `aesthetic_gradients`). The repository has no licence file (GitHub reports none), so it is recorded in `PINNED_UNLICENSED` (`scripts/third_party.py`) with no file under `licenses/`. Only its README was read; that README is a stub that points to the original method, "Personalizing Text-to-Image Generation via Aesthetic Gradients" (Gallego, 2022). The feature is re-implemented from that documented method (`python/sdfx/aesthetic_gradients.py`, `plugins/src/main/scala/sdfx/plugins/AestheticGradientsPlugin.scala`); no upstream code was read or copied.

## Behaviour

- One AlwaysOn plugin `aesthetic_gradients` (hook `aesthetic_gradients`). Settings: embedding (name or path), weight (0.9), steps (20), learning rate (0.0001), slerp + aesthetic text + slerp angle (0.1), negative-prompt mode.
- An aesthetic embedding is the L2-normalised mean of the L2-normalised CLIP ViT-L/14 image embeddings (after the visual projection) of some pictures, stored as a `(1, 768)` tensor in `.pt` (also read from `.safetensors`). It is built by `python -m sdfx.aesthetic_gradients create NAME IMAGES...` or the engine request `aesthetic_embedding` (`op: create | list`); `python -m sdfx.aesthetic_gradients score EMBEDDING IMAGES...` prints the CLIP cosine similarity of pictures to an embedding.
- For each positive prompt (negative with the option) the text encoder is copied in float32 and takes `steps` Adam steps (learning rate `lr`, all parameters) that raise the cosine similarity between the normalised, projected text embedding (EOS token of each 77-token chunk, mean over chunks) and the aesthetic embedding. The prompt is encoded with the personalised copy, and the conditioning is `(1 - weight) * original + weight * personalised`. The checkpoint's own encoder is never modified (verified), so a following run without the plugin reproduces the baseline.
- Slerp: the target becomes `slerp(aesthetic embedding, CLIP text embedding of the aesthetic text, angle)`; without text the plain embedding is used.
- SD 1.x: the text encoder is personalised. SDXL: only its first text encoder (CLIP ViT-L/14); the bigG half and the pooled embedding stay as they are. SD 2.x is rejected with a clear error. A text encoder that is not 768 wide is rejected.
- Personalised conditionings are cached per (text encoder, prompt, settings, embedding file state), so batches and the hires pass do not train again.
- Infotext keys: `Aesthetic LR`, `Aesthetic weight`, `Aesthetic steps`, `Aesthetic embedding` (file name without extension), `Aesthetic slerp`, and with slerp `Aesthetic text` / `Aesthetic slerp angle`, `Aesthetic text negative` when set. PNG Restore and MCP `inspect_generation_metadata` restore them. X/Y/Z axes (my addition, not from the extension): weight, steps, learning rate, slerp angle, embedding, text.
- CLIP ViT-L/14: `openai/clip-vit-large-patch14` revision `32bd64288804d66eefd0ccbe215aa642df71cc41`, `config.json` (4,519 bytes) and `model.safetensors` (1,710,540,580 bytes, SHA-256 `a2bf730a…26dcb`), downloaded on first use into `SDFX_AESTHETIC_GRADIENTS_CACHE` (default `~/.cache/stablediffusion-nui/aesthetic_gradients`) through the size/SHA-256 verified downloader of the NSFW checker. Generation reads only `text_projection.weight` from it; embedding creation loads the whole model. Not bundled. The Hugging Face model card states no licence (the openai/CLIP code repository is MIT); recorded in `THIRD_PARTY_NOTICES.md`.

## Differences and not provided

- **No GUI or MCP tool creates embeddings.** The README says the extension "allows training an embedding from one or few pictures"; here creation is the CLI / engine request only (verified through the CLI, not through the GUI or MCP). The GUI field for the embedding is a free text box (name or path), not a dropdown.
- The upstream README documents no options. The option set (weight, steps, learning rate, slerp, aesthetic text, slerp angle, negative) and the infotext key names are recalled from the extension's UI, not verified against its code; the meaning of slerp / aesthetic text is my reading of it. PNGs written by the original extension may therefore not restore perfectly.
- SDXL checkpoints hold 11 of the 12 layers of their first text encoder and no final layer norm (loading one gives NaN weights for the rest). Those are filled from the downloaded CLIP ViT-L/14 for the loss only; conditioning never reads them. Found by the first real SDXL run (NaN latents), fixed and unit-tested.
- With `sequential_offload` the encoder weights are lifted from the accelerate hooks for each copy (the Embedding Inspector's access path); this was exercised on the real SDXL run (VRAM mode `sequential_offload`).
- Not applied inside the negpip encoder and the Embedding Merge text path (they bypass the plain encoder); not applied in ADetailer passes.
- Learning rates far above the default are unstable: lr 0.001 with 30 steps collapses the conditioning to a blank image (both models, below). The default 0.0001 / 20 steps / weight 0.9 already moves a prompt strongly: on SD1.5 it replaces the castle by the girl of the reference sketches, and on SDXL (only the CLIP-L half is moved, so the two halves of the context disagree) it gives a pale blank-ish picture. Weights around 0.3 to 0.5 are the useful range on SDXL.
- Training is not interruptible (at most `steps` <= 200 small Adam steps, seconds).

## Verification

- Python (CPU, a tiny 768-wide CLIP text model with the real CLIP tokenizer): `python/tests/test_aesthetic_gradients.py`, 14 tests: settings validation, embedding formats and errors, name resolution, slerp, the personalised copy gets closer to the target and the checkpoint's encoder stays unchanged, blending (weight 0 and 1, half way), cache and determinism, slerp target, SDXL keeps the pooled embedding and rejects SD2, missing embedding, wrong encoder width, hook registration. The 14 tests include the SDXL completion of the encoder and the offloaded-weight lift. Full Python suite (`python/tests`, CUDA hidden) exit 0 (final run after the last change).
- Scala: `AestheticGradientsSuite` (defaults, slerp / negative keys, name display, infotext round trip, malformed values, axes), `InspectGenerationMetadataSuite` Aesthetic case, `BuiltinsSuite`; full `sbt test` exit 0.
- Real models through the official MCP SDK (`scripts/dev/check_aesthetic_gradients_real.py`, stdio, packaged jar `target/stablediffusion-nui.jar`, engine `python/.venv-smoke`). The script builds two embeddings from the checkpoint's own pictures (4 monochrome ink sketches, 4 saturated neon pictures) with the CLI, then generates seed 2468, Euler.

### SD1.5 `animekawa_v10`, 512x512, 24 steps, CFG 6, Euler (`outputs/ag-real-sd15`, VRAM mode `model_offload`)

Success. Prompt "a castle on a hill above a village and a river, trees, detailed illustration", seed 2468. Reference pictures: 4 monochrome ink sketches (mean saturation 0.03) and 4 neon pictures from the same checkpoint (seeds 100 to 103), embedded by the CLI.

| run | settings | mean saturation | CLIP similarity to `mono` | to `neon` |
|---|---|---|---|---|
| baseline | none | 0.151 | 0.544 | 0.638 |
| weight 0 | mono, weight 0 | 0.151 (byte-identical to baseline) | | |
| gentle_mono | weight 0.3, steps 10 | 0.136 | 0.545 | |
| half_mono | weight 0.5 | 0.103 | 0.509 | |
| default_mono | mono, defaults | 0.039 | 0.859 | 0.561 |
| slerp_mono | slerp, text "a castle, masterpiece", angle 0.4 | 0.047 | 0.657 | |
| neon | neon, defaults | 0.354 | 0.519 | 0.767 |
| neon_half | neon, weight 0.5 | 0.148 | | 0.676 |
| high_lr | lr 0.001, steps 30 | 0.202 | | |

- Every case differs from the baseline; weight 0 and the run after a cancelled job are byte-identical to the baseline (the checkpoint's encoder is not touched, no hook is left). Negative-prompt mode changes the image. Invalid settings (unknown embedding, weight 2, empty embedding) are rejected with an error, not a crash.
- PNG round trip: `inspect_generation_metadata` restores the plugin (`enabled`, `embedding: mono`) with no `Aesthetic ...` key left unapplied, and replaying the restored arguments reproduces the image byte for byte. Hires (Latent 1.5x, half_mono) changes the image; a cancelled job is followed by a baseline-identical run.
- Images inspected on a contact sheet (`outputs/ag-real-sd15/sheet.jpg`): baseline = weight0 = after_cancel (blue-sky castle on a hill); gentle_mono keeps the castle with slightly flatter colour; half_mono a greyer, more European castle in a forest; default_mono a monochrome ink portrait of a girl in a bonnet (the subject of the reference sketches takes over, the prompt is lost); slerp_mono a monochrome vertical-line sketch; neon a purple and cyan cyber corridor; neon_half a tall pale castle beside a lake; high_lr a flat pink image (collapsed); the replay settings (weight 0.5, steps 12, slerp "sketch") a desaturated misty castle and river, identical to its replay; the hires result is the same style in the larger size.

### SDXL `waiIllustriousSDXL_v170`, 832x1216, 20 steps, CFG 5, Euler (`outputs/ag-real-sdxl`, VRAM mode `sequential_offload`)

Success (the GPU was shared with a 18 GB job of another user, so `model_offload` stalled; `sequential_offload` is also what exercised the offloaded-weights path above). Same prompt, seed and reference method.

| run | mean saturation | CLIP similarity to `mono` | to `neon` |
|---|---|---|---|
| baseline | 0.399 | 0.470 | 0.531 |
| weight 0 | 0.399 (byte-identical) | | |
| gentle_mono (0.3 / 10 steps) | 0.227 | 0.485 | |
| half_mono (0.5) | 0.148 | 0.519 | |
| default_mono | 0.067 | 0.623 | 0.502 |
| slerp_mono | 0.111 | 0.611 | |
| neon (defaults) | 0.713 | 0.623 | 0.686 |
| neon_half (0.5) | 0.440 | | 0.532 |
| high_lr | 0.065 | | |

- Same checks as SD1.5, all passed: every case differs, weight 0 and the post-cancel run are byte-identical to the baseline, invalid settings are rejected, the PNG replay is byte-identical, hires (1.5x) changes the image.
- Images inspected (`outputs/ag-real-sdxl/sheet.jpg`): baseline a green valley with a walled town and a river seen from above; gentle_mono and half_mono progressively desaturated ink-wash versions of the same valley (good, usable look); slerp_mono a detailed monochrome engraving with a castle; default_mono and neon (defaults) are poor: a pale grey mush and a blue and red noise texture, i.e. the defaults are too strong for SDXL because only the CLIP-L half of the context moves; neon_half is a vivid yellow-green stylisation of the valley; high_lr a white speckled image (collapsed); the replay settings give a muted, misty valley, identical to its replay; hires shows the same look at 1.5x.
- A first SDXL run with an earlier version of the check found the NaN bug above (all aesthetic cases failed with "latent must be finite"), and one with settings (lr 0.0008, weight 0.8) whose result was a blank grey picture, which made its byte-identical replay a weak test; the replay case was changed to weight 0.5 / lr 0.0001 and both models were rerun in full.

## Not verified

- Embedding creation and scoring through anything but the command line (no GUI, no MCP tool, the engine request `aesthetic_embedding` only through its Python handler: the CLI calls the same functions).
- The GUI form (the JavaFX controls render from the plugin's control list like every other plugin; not opened here).
- Compatibility with PNGs and `.pt` files written by the original extension: the key names and the file layout follow the documented method and the original embeddings' (1, 768) shape, not a byte-level check.
- SD 2.x (rejected by design), negpip and Embedding Merge text paths (bypassed), ADetailer passes (skipped), Characteristic Guidance / Neutral Prompt style multi-branch encoders.

## Windows packaged GUI chooser check (2026-11-03)

A local test ZIP was built from main product jar SHA-256 `36dcbd696365bcbf7bd9d56359948f78bd236d824a1d382c0844632e46cc6271` (main `0f9ff4a`; `7cc6322` changes handoff only). ZIP SHA-256: `c65d1f924ec7b54f57037e31c7868bcfd3672c45d63980051dd5e1fa69cc8241`. The extracted `SDNUI.exe` was launched with an isolated home in dry-run mode.

Using computer-use clicks and text input in the actual Windows file chooser, two synthetic PNGs were added in separate operations; cancelling a subsequent chooser preserved both. In a later single chooser operation, two additional PNG filenames were entered together in quotes and opened; the GUI accessibility tree showed all four paths. This checks chooser-to-list wiring, including multiple files in one operation and cancellation. It does not check physical Ctrl-click, real embedding creation, a model, or GPU generation through this packaged GUI. The earlier historical unverified notes above refer to their own original test run. Original extension `.pt` and PNG compatibility remains unverified without an authorized authentic fixture.
