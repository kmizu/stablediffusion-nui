---
title: Interface language
nav: Interface language
description: How to switch the Stable Diffusion NUI interface to Japanese or to an A1111 language pack, show the English text next to the translation, and download, import or write language packs.
---

Stable Diffusion NUI can show its labels, buttons, tab titles, tool tips, placeholders and drop-down items in another
language. It covers what the A1111 language pack extensions and the **Bilingual Localization** extension do, and uses the
same pack files.

![The txt2img tab in Japanese](shots/localization-ja.png)

## Choose a language

Open **Settings** (⚙) and the **Language** tab.

| Control | Meaning |
|---|---|
| **Language** | The pack to use. **None (English)** turns the translation off. **日本語 (ja_JP, built in)** is written for this app. Packs you added show their file name. |
| **Fallback pack** | A second pack for the texts the first one does not have, for example a downloaded A1111 pack behind the built-in one. |
| **Display** | **Translation only**, **Bilingual (two lines)** or **Bilingual (one line)**. Tab titles, window titles and drop-down items are always one line. |
| **Order** | **Translation first** or **Original first** for the bilingual layouts. |
| **Translate tool tips**, **Translate drop-down items** | Switch these two off separately. |

Press **OK**. The window changes at once, without a restart. Only what is displayed changes: the values of drop-downs
(the sampler, the schedule), what you type in text fields, the generation parameters, the PNG metadata and the MCP
interface stay English. You can switch back to English at any time and every text returns exactly.

## Language packs

A pack is the A1111 localization file: one JSON object with the English text as key and the translation as value.
The app looks for `.json` files in

- the `localizations` folder of the app,
- `plugins-ext/NAME/localizations` and `extensions/NAME/localizations`, so a language pack extension that you copied
  there works without importing it again,
- the folders you add under **Extra pack folders** (a `localizations` folder, an extension folder, or a whole A1111
  install).

Buttons on the tab:

- **Download pack…** lists the language packs of the A1111 community (Ukrainian, Chinese, Korean, Thai, Spanish,
  Italian, German, Japanese, Portuguese, Turkish, Norwegian, Russian, Finnish and the old A1111 packs, 35 files). The
  chosen file is downloaded from its author's repository at a fixed version, checked against a stored SHA-256 and saved
  in `localizations` together with a note about its origin and licence. Nothing is downloaded until you press the
  button.
- **Import pack file…** copies a `.json` file into `localizations` (an existing file is never overwritten).
- **Export untranslated strings…** writes the English texts that were shown and have no translation as an empty pack. Fill
  in the values and import it to translate the rest.
- **Reload list** reads the folders again.

The pack format also supports the extension syntax for regular expressions, `"@@/^(\\d+) images$/": "$1 枚"`, and for
scoped translations, `"##tab_ti##Normal": "..."` (an element with that id) and `"##@.card .btn##Lora": "..."` (a
simple selector on type, id and style class).

## How much is translated

The built-in Japanese pack translates about 80 % of the distinct texts of the window, including almost all labels,
buttons and short tool tips; the long tool tips of extension controls are mostly still English. The packs of the
A1111 community use A1111's wording and cover 2 % to 18 % of this app's texts (the best are the zh_Hant, zh_Hans, Chinese and
Italian packs; see the verification document). Texts inside lists and tables, progress lines with
timings and error messages stay English unless a pack has a rule for the whole message.

![Bilingual layout, original first](shots/localization-bilingual.png)

The details and the measurements are in
[docs/localization-verification.md](https://github.com/kmizu/stablediffusion-nui/blob/main/docs/localization-verification.md).
