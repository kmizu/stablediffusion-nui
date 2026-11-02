---
title: Image Browser
nav: Image Browser
description: How to browse, rank, favourite, send and delete the images you have generated with the Image Browser tab of Stable Diffusion NUI.
---

The **Image Browser** tab lists the images you generated earlier, shows the generation settings stored inside them
and sends those settings (or the image itself) to other tabs. It is the native counterpart of the A1111
"Image browser" extension and keeps its tab layout, sort keys, ranking, favourites and keyboard shortcuts.
While the tab is open the preview and the gallery on the right of the main window are hidden so the browser can use the full width.

![Image Browser tab](shots/image-browser.png)

## Tabs

| Tab | What it lists |
|---|---|
| **txt2img**, **img2img**, **Extras** | The matching folder below the outputs folder (`txt2img`, `img2img`, `extras`). |
| **txt2img-grids**, **img2img-grids** | The shared `grids` folder (SDNUI keeps one grid folder for both modes). |
| **Favorites** | The favourites folder (`favorites` in the outputs folder by default). |
| **Others** | Any folder you type or browse to. See below. |
| **All** | All of the folders above in one list. |
| **Maintenance** | Housekeeping for the browser's own data. See below. |

Folder names you add to the **Active tabs** setting become extra tabs. Files are read as `.png`, `.jpg`, `.jpeg`,
`.webp` and `.gif`; a `.txt` file next to an image supplies its generation text when the image has none. Videos
(`.mp4`, `.webm` and similar) are listed with their name, size and date; double-click or use **Open video** to play
them with the system player. SDNUI cannot show a video frame itself.

## Browsing

- **First Page**, **Prev Page**, **Next Page**, **End Page** and the page box move between pages. The number of
  thumbnails per page is *columns × rows* from the settings. **⟳** scans the folder again.
- Click a thumbnail to select it. The right side shows a preview, the file name, the file time, size and
  dimensions, and the generation info. Tick **Formatted display** for a Key / Value table in the order set in the
  settings, or clear it for the raw text. Hover a thumbnail for its dimensions and size. Double-click it to show it in the main preview.
- **Sort by** has: path name, date, aesthetic_score, random, cfg scale, steps, seed, sampler, size, model, model hash
  and ranking. The arrow button flips the order. Images without the value are listed last, never dropped.
- **Filename keyword search** matches the path. **EXIF keyword search** matches the generation text; **Search
  negative prompt** chooses *No* (ignore it), *Yes* (include it) or *Only*. Tick **regex** for regular expressions,
  for example `^(?!.*Hires).*$`; an invalid expression shows a warning and is ignored.
- **Ranking filter**: All, 1–5, None or Min-max with minimum and maximum. **Minimum score** and **Maximum score**
  filter by the `aesthetic_score` that the Aesthetic Score plugin writes.

## Ranking and favourites

Select an image and choose **Set ranking to** 1–5 or None, or press `1`–`5` (`0` clears). Rankings are stored in
`config/image-browser.json` and follow the file when the browser moves it. With **Save ranking in image's pnginfo**
on, the ranking is also written to the PNG as a `ranking` text chunk (the pixels are untouched), and **Rebuild
ranking from exif info** can restore rankings from such files.

**Move to favorites** (`F`) moves the image and its `.txt` file into the favourites folder; with **Move buttons copy
instead of move** it is **Copy to favorites** and the original stays. The **Copy to directory** panel does the same
for any folder, and fills from your saved directories. Existing files are never overwritten; a number is added to the name.

## Sending

**Send to txt2img** restores the generation parameters. **Send to img2img** and **Send to inpaint** use the image as
the input image and restore its parameters. **Send to extras** puts the image in the Extras input. **Send to txt2img
ControlNet** and **Send to img2img ControlNet** set the image of the ControlNet unit chosen under **ControlNet number**
without changing the unit's other values.

## Deleting

**delete next** *N* deletes the selected image and the *N−1* after it on the current page; tick **also delete
off-screen images** to continue onto the following pages. A confirmation names the files first. Files go to the
operating system's recycle bin (or to `.trash` in the outputs folder when it is not available), together with their
`.txt` files. Clear **Use recycle bin when deleting images** in the settings to remove them permanently; the
confirmation then says so. `Delete` on the keyboard asks the same question.

## Others

Type a folder (or **Browse**) and press **Load**. **Sub directory depth** is 0 for the folder only, *N* for N levels
below it and −1 for no limit. **Add to / replace in saved directories** remembers the folder and depth;
**Saved directories** loads one and **Remove from saved directories** forgets it. **Get sub directories** fills the
**Sub directories** list so you can narrow to one child folder.

## Maintenance

| Button | Effect |
|---|---|
| Rebuild exif cache | Reads every image again and rebuilds the metadata cache. |
| Delete 0-entries from exif cache | Drops cache entries of images that have no generation text. |
| Update directory names in database | Rewrites a renamed folder path in the saved directories, the cache and the rankings (tick which). |
| Recreate hash for existing files | Recomputes the content hash used to find moved files. |
| Reapply ranking after moving files | Rankings of vanished files follow a file with the same content hash. |
| Rebuild ranking from exif info | Takes rankings stored inside the PNG files. |

These run in the background and report in **Last message**. Nothing here changes your images.

## Keyboard

| Key | Action |
|---|---|
| `0`–`5` | Set the ranking (0 = None) |
| `F` | Add to favourites |
| `R` | Refresh |
| `Delete` | Delete (asks first) |
| `Ctrl+←` / `Ctrl+→` | Previous / next page |

Keys act on the selected image when focus is not in a text box. **Change CTRL keybindings to SHIFT** and **or to
CTRL+SHIFT** in the settings change the page-turning modifier. **Swipe left/right navigates to the next image**
turns on touch swipes.

## Settings

**Settings…** at the top of the tab opens: active tabs, favourites folder, include images in sub directories,
preload the first tab at startup, copy instead of move, console messages on deletion, matching `.txt` files, recycle
bin, scan EXIF/.txt data, the key modifier, swipe, ranking in pnginfo, columns, rows, thumbnail size and square crop,
tooltips, progress text, formatted info and its order, additional generation info, and the components you want to hide
(sort, searches, ranking, delete bar, send buttons and so on). Changes apply at once and are saved in
`config/image-browser.json`. With **Scan Exif-/.txt-data** off the browser only lists files, and sorting, searching
and the info panel need that scan.

## Not included

Playing a video or showing its first frame, the openOutpaint button, and the web UI's own debug, JavaScript-log and
page-loading options (a native window needs none of them) are not provided.
