# Booru prompt verification

Target `booru_prompt` (issue #8): ledger indices 80 booru2prompt, 81 Gelbooru Prompt, 82 e621 Prompt, 229 Ranbooru and
347 Gelbooru Prompt Randomizer. One built-in txt2img/img2img Script, id `booru_prompt`.

## Sources and licences

None of the five projects' code was read or copied. The behaviour comes from their READMEs (and Ranbooru's `usage.md`).

| Index | Project | Pinned commit | Licence |
|---|---|---|---|
| 80 | Malisius/booru2prompt | `4bd88839a6454428d098c7ad6672cc0989dd6a60` | none published |
| 81 | antis0007/sd-webui-gelbooru-prompt | `b0128f931a8306a8f1a030c515792e3128b97f04` | GPL-3.0 (text in `licenses/`) |
| 82 | nochnoe/sd-webui-e621-prompt | `74ce0a50d788abfa42d67b0614f7195d3f71294c` | MIT (text in `licenses/`) |
| 229 | Inzaniak/sd-webui-ranbooru | `dfbf48b0684770199f9025b4e8b27a3a30fa3e62` | none published |
| 347 | RED1cat/Gelbooru-Prompt-Randomizer | `032805388740dad2bcd06493f0286c8792e71230` | none published |

The booru APIs are public web services; this app only calls the documented JSON endpoints and sends a descriptive
`User-Agent` (`StableDiffusionNUI/0.06 ...`). Pictures and tags belong to their uploaders and the sites' terms apply.
The notices are in `THIRD_PARTY_NOTICES.md`.

## What the Script does

* Sites: Danbooru, AIBooru (Danbooru API), e621 (e621 API), Gelbooru, Rule34, Safebooru, Xbooru (Gelbooru `dapi`),
  Konachan and Yande.re (Moebooru). Gelbooru and Rule34 refuse requests without a user id and API key; the others work
  anonymously (Danbooru allows 2 search tags then).
* Random post: search tags, rating filter (also sent as a rating metatag when the site and tag budget allow), max pages,
  score preference (random / high / low), animated posts skipped, optional cross-run page cache, a random page shrunk
  when it lies past the last result. Every image of the batch gets its own post, chosen from the seed, so the same seed
  and the same result pages give the same prompts.
* Specific posts: link (the site is taken from the link, query strings ignored), `123`, `id:123`, a 32 hex md5, or an
  md5-named file name or path (`sample_` names are refused). Several references are used one per image in turn.
* Tags: category selection with artist prefix (Danbooru family and e621), bad-tag list, remove tags with `*` wildcards,
  tag files `booru/search/*.txt` and `booru/remove/*.txt` in the app home, shuffle, limit %, max tags, underscores to
  spaces (emoticons kept), bracket escaping, always-add tags, background change, colour change, rating tag.
* Mixing posts, chaos / less chaos / negative mode, position of the tags (after / before / replace the prompt), same
  prompt and same seed for all images.
* img2img from the post picture (sample picture, https and named hosts only, PNG temp file removed afterwards),
  denoising, crop or stretch, reuse of the first picture.
* LoRAnado: random LoRAs from a subfolder with amount, min/max or custom weights, lock of the previous pick.
* Two buttons under the form (add the tags of the typed post / of a random post to the visible prompt), MCP `run_script`.
* The full prompt is saved in the PNG, so replay (GUI "send to", MCP `inspect_generation_metadata` +
  `txt2img`) needs no script and no network. The infotext fields `Booru`, `Booru posts` and `Booru search` record the
  origin; they are provenance only and show up as `unapplied_fields` in the MCP inspection by design.

Own design where the READMEs are silent: the score preference weighting (sorted page, pick near the front by r^2), the
chaos semantics, the background/colour heuristics, the `rating:` category tag, the rating normalisation across sites,
one post per image in specific mode. Credentials are plain plugin settings on this machine; they are never put into
infotext, logs or error messages (errors show host and path only).

## Not provided

* Deepbooru interrogation of the booru picture instead of its tags (Ranbooru) and "send to ControlNet".
* The booru2prompt thumbnail search browser (use search tags with Random post; the Specific post mode takes a link).
* Custom booru hosts, proxy settings, Moebooru login, Gelbooru "Fringe Benefits", e926 (the API answers 403 to this
  client; e621 with rating General is the same content).
* Gelbooru and Rule34 could not be exercised live (no account); their request URLs, parsing and the missing-key message
  are covered by canned responses only.

## Tests

`plugins/src/test/scala/sdfx/plugins/BooruPromptSuite.scala`, 35 tests: post references for all site families, parsing of
Danbooru / e621 / Gelbooru-style (wrapped, array, empty, HTML escapes, `hash`) / Moebooru, request URLs with
credentials, tag pipeline (categories, bad tags, globs, limit, shuffle determinism, emoticons, background, colour,
mixing, chaos), random picks (page range, rating, animation, 2-tag anonymous Danbooru budget, empty far pages, score
order, caches), configuration validation and tag files (path traversal rejected), the run (seeds, infotext, positions,
same prompt, same seed, specific posts cycling, mix, chaos on both prompts, empty tag list error, img2img download
count and cleanup, host restrictions, LoRAnado, interruption, buttons, plugin surface, key never in params/logs) and the
real JDK client against a local HTTP server (User-Agent, 403 hint, size cap, 429 retry, no query string in errors).
Full Scala suite: green (plugins 235 tests with this suite).

## Real checks (`scripts/dev/check_booru_prompt_real.py`)

Official MCP Python SDK 2.3.0 over stdio, my own jar, live Danbooru / e621 / Safebooru / Konachan APIs, one engine
process per model. `receipt.json` and the images are in `outputs/booru-real-sd15` and `outputs/booru-real-sdxl`
(ignored files). Asserted: the script is listed; Specific post 1 on Danbooru changes the image against the plain prompt;
the saved prompt starts with the typed prompt, holds the post's tags and none of the bad tags; infotext has
`Booru: Danbooru, Booru posts: 1`; plain txt2img with the saved prompt reproduces the script image byte for byte
(no network); the same specific post repeats exactly; artist prefix / meta / remove `*ears` / append tags edit the prompt;
negative mode moves the tags to the negative prompt; random posts: two images of a batch use two different posts, the
same request repeats exactly (page cache), the PNG replay reproduces image 0, `Booru search` is recorded, same-prompt
gives equal prompts; mix of 3 posts records 3 distinct ids; e621, Safebooru and Konachan random posts; Gelbooru and
Rule34 fail with the missing-key message; a bad post reference fails cleanly; img2img from the post picture differs
from txt2img, depends on the denoising (0.25 / 0.6 / 0.9 all differ) and repeats exactly; an async run cancelled at
about 6 % progress is reported `cancelled` and the next generation equals the baseline pixel for pixel.

### SD1.5 `animekawa_v10`, 512x512, 20 steps, Euler, model_offload

All assertions passed (`success: true`). Random Danbooru posts used: 12496313 and 12496738 (mix 12496313 12496586
12496464), e621 6825369, Safebooru 7286064, Konachan 343559. Images looked at (contact sheet of all 17 outputs):
the specific post gives a pink-haired cat girl in a sailor uniform with a red bow and striped panties, matching the
tags (cat ears, pink hair, sailor collar, striped background); the artist/remove variant keeps the same picture
without the ears tag; negative mode loses all subject and produces a forest (the tags are now negative); random posts
give a black-haired twin-tail girl in a teal frilled dress and a blonde maid with an apron; the same-prompt pair shows
the same character twice; the mix is a blonde girl in a blue kimono with a cat tail; e621 gives a spotted canid cub,
Safebooru a black-haired girl next to a spiky-haired armoured boy, Konachan a waterfall landscape with clouds; the
img2img results carry the source picture's orange striped background and pose and move further away from it as the
denoising grows; the picture after the cancel is identical to the baseline.

### SDXL `waiIllustriousSDXL_v170`, 832x1216, 20 steps, Euler, sequential_offload

All assertions passed (`success: true`), same jar (SHA-256 `f50bde4e6aa0b8e8fad7c71d9781b7f0f611251f343a36066ccbd64584d74a2a`)
as the SD1.5 run; the async run was cancelled at about 5 % progress and the next generation equalled the baseline pixel
for pixel. Random Danbooru posts 12497241 and 12497519 (mix 12497241 12497619 12497263), e621 6826100, Safebooru 7286591,
Konachan 343559. Images looked at (contact sheet of all 17 outputs): post 1 gives a red-haired cat girl in a sailor
uniform with a blue bow and striped panties on an orange background, with and without the artist/meta/remove options;
negative mode again drops the subject (a dark latex figure that has nothing to do with the tags); random posts give a
brown-haired girl in a bunny outfit with fishnets and a red-haired girl eating a donut in a striped dress; the
same-prompt pair is the same character twice; the mix shows two crowned girls in pink sweaters; e621 gives a purple
winged unicorn (Twilight Sparkle tags), Safebooru a red-haired chibi group with black outfits, Konachan a waterfall
landscape; img2img keeps the source picture's colours, background and pose and loosens with the denoising.

The SD1.5 run used the same jar. Python tests were not re-run: no Python file changed (the Script is Scala only and needs
no engine hook; Torch/engine code is untouched).
