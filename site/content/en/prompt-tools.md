---
title: Prompt Tools
nav: Prompt Tools
description: How to save prompts, reuse the history, insert tags with buttons and cards, and edit the tags of a prompt with the Prompt Tools tab of Stable Diffusion NUI.
---

The **Prompt Tools** tab collects the helpers that make typing prompts less repetitive. It is the native counterpart
of four A1111 extensions: Multi-Prompt Manager (saved prompts), Prompt All in One (tag editor, history, favourites,
blacklist), D2 Prompt Selector (tag buttons from YAML files) and Easy Tag Insert (tag cards). Translation is not part
of it (see the end of the page).

![Prompt Tools tab](shots/prompt-tools.png)

At the top, **Target tab** chooses which generation tab (txt2img, img2img or Inpaint) the tools write to; it follows
the generation tab you came from. The two boxes below show the prompt and the negative prompt of that tab as they change.
Tick **Insert into the negative prompt** to make the tag buttons, cards and the tag editor work on the negative prompt.
The line under the boxes tells you what the last action did.

## Saved prompts

Type or paste a text, give it a name and press **Save**. A blank name becomes `prompt 1`, `prompt 2`, and so on. Tick
**This is a negative prompt** for texts that belong in the negative prompt. **Use** replaces the target prompt (or
negative prompt) with the text; **Copy from active prompt** does the opposite and fills the text box from the target tab.
**Rename**, **Delete** (with a confirmation), **Import…** and **Export…** work on the whole list. The file is
`config/prompt-tools/prompts.json` in the same format as the Multi-Prompt Manager extension, so you can exchange it.

## History & favourites

Every time you press Generate or Queue, the prompt and negative prompt are added to the history (newest first, 500
kept, an identical pair moves to the top instead of repeating). **Restore** puts both texts back into the target tab.
**Bookmark selected** copies a history entry to the favourites; **Bookmark current** bookmarks what is in the boxes now,
with the optional name. Clearing the history keeps the favourites.

## Tag editor

Shows the tags of the prompt as a list. **Up** and **Down** move the selected tag, **Weight +0.1** and **Weight -0.1**
change it in the `(tag:1.2)` form (a weight of 1.0 gives the bare tag again; `<lora:…>` tags are left alone),
**Delete tag** removes it and **Remove duplicates** keeps the first of repeated tags. Commas inside brackets do not split
a tag. The field is rewritten as `a, b, c`, so line breaks are not kept when you use these buttons.

The **Blacklist** (one tag per line) is applied only when you press **Apply blacklist**; it removes the listed tags
whatever their weight, ignoring case.

## Tag buttons

Put YAML files in `config/prompt-tools/tag-selector` (or choose another folder with **Folder…**, for example one in a
cloud drive). Each file is a tab. A file is a tree: a group holds either a list of tags or names mapped to tags, and
groups can be nested.

```yaml
hair color:
  single:
    - black hair
    - blonde hair
  multi:
    gradient: gradient hair
quality:
  - masterpiece
```

* Click a button to add its tag to the prompt (a tag that is already there is not added twice).
* Click a group name (the orange button) to add a random tag from that group, including its sub groups.
* **Ctrl-click** adds to the negative prompt; **right-click** removes the tag from the prompt.
* Hover to read what a button inserts. The search box finds buttons by their name or their tag across all files.
* A file named `__config__.yml` with `sort:` and a list of file names sets the order of the tabs; the rest follow alphabetically.
* A file that cannot be read is shown with `(!)` and the line of the problem. The reader understands block mappings and
  lists, quoted or plain text and `[a, b]` lists, but not anchors, multi-line text or `{}` mappings.

The first time, a small `sample.yml` is created so the tab is not empty.

## Tag cards

A card is a short text saved as `config/prompt-tools/tag-cards/<category>/<name>.tag`. Cards are grouped by category.
Click a card to insert it; right-click for *Insert into the negative prompt*, *Remove from the prompt* and *Edit*. To
make a card, fill **Category** (use `/` for sub folders), **Name** and the text, then press **Save card**. Names cannot
contain `< > : " / \ | ? *` and a card can never be written outside the cards folder.

## Not included

Prompt All in One also offers online and offline **translation**, ChatGPT prompt generation, highlighting of LoRA and
embedding words, a built-in vocabulary of thousands of tags, custom themes and dragging tags. These are not in this
tab. The tag editor works with the visible tags only; disabling a tag without deleting it is not possible.
