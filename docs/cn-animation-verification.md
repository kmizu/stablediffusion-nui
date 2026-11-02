# SD-CN-Animation (video to video) verification

Ledger target `cn_animation` (index 147, volotat/SD-CN-Animation, MIT, pinned `05e265ff3f08cd07582fb0915f2fd4a9accb3be0`).
Issue #14 stays open: **partial** (text-to-video is not provided).

## What was built

- Script `cn_animation` (txt2img and img2img tabs; the init image is ignored) in `CnAnimationPlugin.scala`. Each frame is a normal
  img2img job of the host, so checkpoint, prompt, LoRAs, embeddings, AlwaysOn plugins and ControlNet units apply. Every enabled
  ControlNet unit gets the current source frame as its image.
- Engine request `cn_animation` (`python/sdfx/cn_animation.py`): `prepare` (decode and resize the video), `flow` (RAFT both ways,
  backward warp of the previous styled frame, occlusion mask, init image), `finish` (histogram match to the source frame, blend the
  occlusions), `encode` (MP4/GIF/WEBM/WEBP through the AnimateDiff encoder) and `release`.
- Per frame: flow -> step 1 (img2img at the processing strength, full image or inpainting the occlusions) -> blend with the warped
  frame by the mask -> step 2 (img2img at the fix-frame strength) -> histogram match. The first frame is a plain img2img of source
  frame 0.
- Infotext key `SD-CN-Animation` (Python-dict spelling, video recorded by file name only); GUI, PNG and MCP (`inspect_generation_metadata`)
  replay.
- Licence: MIT upstream, written from its README and scripts, nothing copied (`THIRD_PARTY_NOTICES.md`, `licenses/volotat__SD-CN-Animation.txt`).

## Differences from the source

- RAFT is torchvision's (`raft_large` by default, `raft_small` selectable), with torchvision's published weights; the source fetches
  `raft-things.pth` from Google Drive. Measured: a 6 px shift of a textured image gives a mean flow of -6.02 px.
- Flow is normalised by the width for x and the height for y (the source divides by the height for both, only right for square frames).
- The occlusion blur uses one sigma for both axes; the first frame has its own strength (default 0.75); a step seed of -1 is job seed +
  frame number (the source draws a random seed per frame), so a video is reproducible.
- An inpaint-mode frame whose mask is empty is not redrawn. An interrupted run keeps the frames saved so far and writes no video.

## Not provided

- **Text-to-video** (FloweR network): its weights are only distributed from Google Drive, which this machine cannot reach, so it could
  be neither fetched nor verified.
- Reference-type ControlNet preprocessors per frame, params.json export, audio, X/Y/Z axes, batch size or count above 1, Hires. fix,
  image scrambling sessions.

## Tests

- `python/tests/test_cn_animation.py`: 15 tests (histogram matching, warp, occlusion, trailing, blur, video decode / skip, flow request
  files, finish, encode, server routing, real `raft_small` run on CPU, error cases).
- `CnAnimationSuite` (12), `CnAnimationReplaySuite`, the MCP inspect test; full `sbt test` succeeded.

## Real-model checks (official MCP SDK, `scripts/dev/check_cn_animation_real.py`)

A source video was built from a real generated picture (slow pan and zoom, encoded by ffmpeg), then restyled with the prompt changed
to a watercolour painting. Contact sheets are in `outputs/cnanim-real-*/sheets` (looked at).

### SD1.5 `animekawa_v10`, 512x512, 10 frames, 20 steps, canny ControlNet (control_v11p_sd15_canny)

- MP4 and GIF written with 512x512 and 10 frames. Determinism: a second run gave identical frames. PNG replay through
  `inspect_generation_metadata` reproduced the first frame byte for byte. Validation errors (missing video, out-of-range strength, batch
  size 2) are reported. Cancel at 10 % progress, then a normal txt2img was bit-identical to the one before and the repeated video
  identical to the first.
- Stability (mean difference of neighbouring frames): source 10.8, flow-guided 10.5, independent per-frame img2img 20.4 (ratio 0.52).
  The independent frames visibly change composition from frame to frame; the guided ones follow the pan.
- **Colour drift (caveat):** at the default strengths the colours go hazy and grey over the 10 frames; the difference to the source frame
  grows from 13.2 to 24.2 although contrast barely changes (43.1 to 42.2). Only a 4-frame run with step 2 off (raft_small, skip 1) was
  checked, and it looked clean; longer runs without step 2 were not tested.
- Inpaint-occlusion mode (5 frames) and raft_small + frame skip + no step 2 + no trailing (4 frames) ran and were looked at (the
  inpaint-mode frames show the same drift).

### SDXL `waiIllustriousSDXL_v170`, 832x1216, 6 frames, 20 steps, **no ControlNet**

- Ran: MP4 and GIF, PNG replay of the first frame byte for byte, inpaint mode, raft_small + skip, validation errors, cancel at 10 %
  followed by an identical normal txt2img and an identical repeated video (the separate determinism step was skipped).
- **Stability was NOT shown on SDXL.** With the large camera move and no ControlNet the flow-guided video was not measurably steadier
  than independent img2img: neighbouring-frame difference 35.6 (guided) against 35.5 (independent), source 40.1. The frames look
  coherent and follow the pan (difference to the source 25.5 falling to 23.5), but this metric does not separate the two. SDXL verifies
  that the pipeline runs, replays, cancels and recovers on that model, not that it stabilises the video.
- SDXL with a ControlNet was not run (no SDXL ControlNet model was available locally).

## Other caveats

- The RAFT weights are downloaded by torchvision on first use (needs network once).
- Disk use: every styled frame is kept until the video is encoded.
