---
title: Panorama
nav: Panorama
description: How to look around a 360 degree picture, convert it between equirectangular and cubemap and send it on with the Panorama tab of Stable Diffusion NUI.
---

The **Panorama** tab shows an equirectangular picture (2:1) or a cubemap net (4:3) as an interactive 360° scene. It is the native
counterpart of the A1111 extension "Panorama Viewer". While the tab is open the preview and gallery on the right of the main window
are hidden so the viewer can use the full width.

![Panorama tab](shots/panorama.png)

## Opening a picture

* Click **Pano** under the preview, choose **View as panorama** in the gallery's context menu, or press **Send to panorama** in the
  [Image Browser](image-browser.html).
* **Open…** chooses a file, and you can drop an image file on the viewer.
* **◀ ▶** step to the previous or next picture of the same folder (natural order, wrapping around).

The picture's shape selects the projection: 2:1 is read as **Equirectangular**, 4:3 with square faces as **Cubemap: Polyhedron net**,
anything else as equirectangular with a hint under the viewer. Use the **Projection** list to override. A picture that cannot be a cube
net (the faces must be square) is refused with an explanation.
Generate 2:1 pictures (for example 1024x512) for equirectangular and 4:3 (1024x768) for a cube net. A general Stable Diffusion
model does not make the left and right edges meet, so a visible seam is normal; fix it with Inpaint.

## Looking around

| Action | Result |
|---|---|
| Drag | Turn the camera; the scene follows the pointer. |
| Wheel, **+** / **-** | Zoom (field of view 20° to 120°). |
| Arrow keys | Turn by 1/18 of the field of view. |
| Double click, **Home**, **Reset view** | Look straight ahead at 90°. |
| **Yaw**, **Pitch**, **FOV** | Type or slide an exact value. |
| **Auto-rotate** | Turn slowly to the right (speed in degrees per second). |
| **Fullscreen** | Open a full-screen window that shares the camera; **Esc** closes it. |

The view is drawn coarsely while you move and sharp once it settles.

## Converting and saving

* **→ Cubemap** converts an equirectangular picture to a 4:3 cube net with faces a quarter of the width (a 1024x512 picture becomes
  1024x768) and **→ Equirectangular** converts a net back. The result is saved as `<name>-cubemap.png` or `<name>-equirect.png` in the
  `panorama` folder of the outputs, keeps the generation parameters, and is shown at once. Converting is resampling, so a round trip is
  slightly soft.
* **Save view** writes what the camera sees now as a 1600 px wide PNG (`<name>-view.png`).
* **⇢ img2img**, **⇢ Inpaint** and **⇢ Extras** send the shown picture on, for example the cube net to repair the poles.

During a password-protected session in [Image Scrambling](tools.html) protected pictures are decoded in memory only, and the pictures
the tab saves are scrambled.

## Not provided

The video panorama of the extension (uploaded movies and Infinite Zoom) is not provided, nor are cube layouts other than the 4x3 cross
and the openOutpaint hand-off.
