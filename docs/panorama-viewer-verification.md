# Panorama Viewer verification (issue #21, ledger index 109, target `core:panorama-viewer`)

Status: **partial** (video panorama and other items below are not provided). The commit says `Refs #21`.

## Upstream and licence

* Upstream: [GeorgLegato/sd-webui-panorama-viewer](https://github.com/GeorgLegato/sd-webui-panorama-viewer), GPL-3.0, pinned
  `aa2a238136f68d8943b17378d8c01b6e00179e68`. `LICENSE` Git blob `f288702d2fa16d3cdf0035b15a9fcbc552cd88e7`, SHA-256
  `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`, archived as
  `licenses/GeorgLegato__sd-webui-panorama-viewer.txt` and pinned in `scripts/third_party.py`.
* What was read: the README, `javascript/panoramaviewer-ext.js` (how the Pano buttons attach to the galleries), the head of
  `javascript/e2c.js` (equirectangular to cube conversion, which says it is based on third-party LGPL-3.0 code) and
  `scripts/panorama-3dviewer.py` (the tab and the movie upload). The viewer itself is the MIT-licensed Photo Sphere Viewer
  library in the browser. **No upstream code was copied.** The projection maths, the cube-net layout and every UI element are
  written independently for the native app; the entry is recorded as `reimplemented` in `THIRD_PARTY_NOTICES.md`.

## What was built

| Part | Files |
|---|---|
| Projection maths, no JavaFX (`Raster`, `View`, `Projection`, `Panorama.render`, equirectangular to cube net and back, aspect detection and hints) | `core/src/main/scala/sdfx/core/panorama/Panorama.scala` |
| Folder stepping (natural order, wrapping), collision-free names, PNG saving with generation parameters (scrambled with the session password when one is set), size limits | `core/src/main/scala/sdfx/core/panorama/PanoramaFiles.scala` |
| Viewport (drag, wheel, keys, off-thread coarse-then-sharp rendering) and the tab | `ui/src/main/scala/sdfx/ui/view/PanoramaTab.scala` |
| Mounting, `ctx.sendToPanorama`, Preview "Pano" button, gallery context menu item, Image Browser "Send to panorama" | `MainWindow.scala`, `UiContext.scala`, `PreviewPane.scala`, `GalleryView.scala`, `ImageBrowserTab.scala` |
| Tests | `core/src/test/scala/sdfx/core/PanoramaSuite.scala` (14), `ui/src/test/scala/sdfx/ui/RealPanoramaCheck.scala`, `scripts/dev/check_panorama_real.py` |
| Manual, ledger, notices | `site/content/{en,ja}/panorama.md`, `site/content/*/{tools,extensions}.md`, `scripts/extensions_matrix.py`, `scripts/third_party.py` |

Conventions: yaw 0 looks at the middle of the picture, positive yaw turns right, positive pitch looks up, the field of view is the
horizontal one (20 to 120 degrees). The cube net is the 4x3 cross: top above front, bottom below front, left / front / right /
back in the middle row; the unused corner cells are opaque black.

## Differences from the extension

| Extension | Here |
|---|---|
| Pano button under the txt2img / img2img / Extras / Image Browser galleries that swaps the gallery image for an in-page viewer | A **Panorama tab** (the preview and gallery are hidden while it is open) opened by the Pano button of the preview pane, a gallery context menu item and the Image Browser's Send to panorama button; Open…, drag and drop, previous/next in the folder |
| Equirectangular and cubemap (polyhedron net) viewing | Both, chosen from the shape or by hand; a cube net must have square faces |
| Convert equirectangular to cubemap in the gallery | **→ Cubemap**, and the reverse **→ Equirectangular**; both saved as PNG and shown at once |
| Send to openOutpaint | Not applicable. Replaced by sending to img2img, Inpaint and Extras |
| Video panorama (uploaded movie, Infinite Zoom) | **Not provided** |
| Fullscreen of the browser viewer | A full-screen window sharing the camera |
| Auto-rotate, drag, zoom (Photo Sphere Viewer) | Auto-rotate, drag, wheel, keys, numeric Yaw/Pitch/FOV, Save view (extra) |

## Evidence

All numbers are from this worktree (branch `worktree-agent-aba99ed600499e5a5`), JDK 21, sbt 2.0.12.

### Unit tests (`PanoramaSuite`, 14 tests, all pass)

The test picture encodes the world direction of every pixel as its colour, so each rendered pixel is compared with the ray that
produced it.

* Equirectangular views at five camera settings (including yaw 180 / pitch -40 and pitch 85): worst channel error at most 4/255.
* Axis conventions: yaw 90 shows +x, pitch 90 shows +y, the top right of the view has positive x and y.
* The seam at yaw 180 is continuous (neighbouring pixels differ by at most 4) and yaw 540 renders the same as -180.
* The cube net has the documented layout: each face centre holds the direction of its face, the six blank corner cells are black.
* Neighbouring faces are continuous along all five shared edges tested (top/front, bottom/front, left/front, front/right,
  right/back; worst step at most 8/255).
* Cube-net views agree with the equirectangular scene at four camera settings (at most 6/255); equirectangular to cube and back
  has a worst error at most 8 and a mean below 1 on the direction scene, and a mean red error below 14 on a checkerboard
  gradient.
* Aspect detection (2:1, 4:3, neither), hints, clamping of pitch and field of view, yaw wrapping, refusal of non-square cube faces,
  natural folder order with wrapping, collision-free names, parameters kept in a saved PNG, size limits.

### Real application check (`RealPanoramaCheck`, 21 groups, all pass)

`scripts/dev/check_panorama_real.py` made the pictures through the official MCP SDK (client venv, stdio, my own jar) with the real
engine (RTX 3090, `model_offload`, 24 steps, Euler, seed 360, prompt "equirectangular 360 degree panorama, HDRI, inside a cozy
wooden cabin living room ..."): **SD1.5 `animekawa_v10` at 1024x512** and **SDXL `waiIllustriousSDXL_v170` at 1536x768**. Both
were looked at: they are real cabin interiors with window, sofas, bookshelves and a fireplace, and neither is seamless (mean
difference between the left and right edge columns 35 and 81 of 255), which is what a general model does. The viewer shows the seam
honestly.

`RealPanoramaCheck` then mounted the real `MainWindow` (dry-run engine, 1700x1000) and drove the Panorama tab on those pictures,
plus a real 512x512 SD1.5 picture. Screenshots were inspected (the first view, the cube-net front view, the SDXL view turned to
yaw 120 / pitch 15 showing a bookshelf, the full-screen window showing the fireplace, the 900 px wide layout, the saved net).

1. Send to panorama opens the SD1.5 picture as Equirectangular with the hint "matches"; the sharp viewport frame (1464x563) is
   **pixel-identical** to the reference render.
2. Mouse drag moves the camera by fov/width degrees per pixel (100 px gave yaw 6.148 at fov 90 and width 1464); the settled frame
   equals the reference render. Extreme drags clamp pitch at +-90 and wrap yaw into -180..180.
3. Wheel zoom (10% per notch) and the 20..120 degree clamp, arrow keys (fov/18), +/-, Home and double click reset.
4. Yaw/Pitch spinners and the FOV slider drive the camera; the readout follows; Reset view.
5. Auto-rotate turned the camera (to yaw 9.0 within the wait) and stopped when unchecked.
6. Forcing Cubemap on a 2:1 picture is refused with an explanation.
7. **→ Cubemap**: 1024x768 net (256 px faces) saved as `panorama/a-sd15-cabin-cubemap.png` with the generation parameters; the
   saved file equals the shown picture; the cube view of the front direction differs from the equirectangular view by a mean
   **2.16/255**; views at yaw 90 / 180 / -90, pitch +-80 and a diagonal differ by a mean of 1.7, 1.7, 1.7, 1.3, 1.9, 1.4/255.
   The net picture was looked at: front window in the middle, ceiling above, floor below, wrap-around wall on the left and right.
8. **→ Equirectangular** converts it back to 1024x512 and saves it; mean difference to the original after the round trip
   **2.86/255** (resampled twice).
9. Previous/next walks the folder in natural order and wraps; the real SDXL picture (1536x768) opens; the real 512x512 picture
   shows the "is not 2:1" hint and is still viewable.
10. The real SDXL picture renders pixel-identically at yaw 120 / pitch 15 / fov 75.
11. Save view wrote `b-sdxl-cabin-view.png` (1600x615, same aspect as the viewport), identical to the reference render, with the
    generation parameters.
12. Fullscreen opens a second window (rendered 1920x1080) that follows the camera; Esc closes it.
13. The buttons send the picture to img2img, Inpaint and Extras (their input fields hold the path).
14. The preview pane's Pano button and the Image Browser's Send to panorama button open the right file.
15. A corrupt PNG and a missing file are reported in the status line and the shown picture stays.
16. A scrambled PNG is refused without the session password, decodes to exactly the plain pixels with it, and a conversion made
    during the protected session is saved with the scramble marker.
17. The tab lays out at a 900 px wide window.

Raw outputs (not committed): `outputs/pano-real` (receipt, pictures), `outputs/pano-ui` (screenshots, `panorama-report.txt`).

### Regression

* Scala on this tree (forced `testOnly *` per module): core 651, engine 286 (1 skipped), plugins 344, mcp 105, ui 441, all passed. The first run failed one core test (PixelScrambleSuite) only because the worktree lacked the `python/.venv-test` junction; after adding it everything passed.
* Python Python `test_third_party.py` and `test_third_party_pins.py` pass (7).
  No Python engine file changed, so the full Torch suite was not rerun.

## Not provided / limits

* **Video panorama** (the extension's movie tab / Infinite Zoom) is not provided.
* No in-gallery overlay; the viewer is a tab. The extension's send to openOutpaint is not applicable.
* Only the 4x3 cross cube layout; equirectangular pictures wider than 16384 px (or 134 megapixels) are refused.
* The conversions are bilinear resamplings; a round trip is slightly soft (2.86/255 here). Face edges are clamped inside each
  face, so a cube view shows no bleeding but is not exactly seam-free at one-pixel level (the unit test bounds the step at 8/255).
* The panoramas SD1.5 and SDXL make are not seamless; there is no seam repair or tiling mode in the viewer (use Inpaint, which
  the buttons feed).
* Drag and drop onto the viewer uses the same loader as Open…, but the drop gesture itself was not synthesised in the check
  (no drag-and-drop hardware event); the Open… file dialog is OS UI and was not driven either (the loader behind both is
  exercised by every other group). Fullscreen was verified on the primary display only.
* The SD models only supplied the test pictures. Nothing in the viewer depends on a model, so no generation feature, MCP tool or
  PNG replay was added; there is no MCP tool for the viewer.
* Assertion note: while writing the check I first asserted that a square picture's hint contains "looks like"; that was wrong
  (a square picture matches neither shape, so the hint cannot say what it looks like) and the check now asserts "is not 2:1"
  only. The "looks like" variant is covered by `PanoramaSuite`. No other assertion was changed.
