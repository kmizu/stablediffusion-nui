# CreaPrompt verification

Target `creaprompt` (issue #26, ledger index 279, tritant/sd-webui-creaprompt, MIT, pinned `31fe03143206399c704cee7939de9dbf80c9a84e`).
Script `creaprompt`; word lists bundled in `plugins/src/main/resources/creaprompt` (42 category .csv files, 801-line collection, 11 presets, upstream LICENSE).

## Current status (2026-11-03, Refs #26)

After merging product changes through main `c4abab3`, focused CreaPrompt and negative-suggestions tests, real JavaFX form/MainWindow checks, and assembly passed. Real SD1.5 and SDXL generation passed separately through the source-class MainWindow's Script selector and Generate button, and through the extracted Windows launcher's MCP server; the generated images were inspected. Direct and launcher MCP discovery also confirmed the explicit home in dry-run. Details, artifacts and hashes are in **After main c4abab3 merge** below. Main `401757c` subsequently added handoff documentation only.

After squash integration in main, JDK 21 `sbt test ui/assembly` exited 0 (`outputs/creaprompt-main-full.log`): the summaries report 306, 220, 105 and 78 tests passed, while two reports contain zero tests due to sbt caching. The assembled JAR SHA-256 `84608b3ad86fb8ae16362dde4e38de7311f607bbb2b6cc776789685b1a55322a` matches the one used for the extracted-package checks. The extracted launcher's GUI generation path and a full release-payload audit remain unverified. Earlier interrupted or untested runs are retained below with their historical scope. Japanese usage instructions and a dry-run MainWindow screenshot are in `site/content/ja/extensions.md` and `site/assets/shots/creaprompt.png`.

## Historical post-merge acceptance plan

This plan was written after the earlier `d9f13ea` merge. It is a reproducible checklist, not a claim that every command below ran; later checks and their actual commands, scope and results are recorded in the dated sections below.

Run from this worktree on Windows. Coordinate the shared sbt and GPU slots first. The test venv junction `python/.venv-test` points to the main checkout's test environment. sbt 2 can skip cached suites: record the counts actually executed instead of calling a cached run a fresh full regression.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
& "$env:LOCALAPPDATA/Coursier/data/bin/sbt.bat" -batch test ui/assembly
python site/build.py --out "$env:TEMP/sdnui-crea-post-merge-site"
& ./scripts/package_windows.ps1 -JdkHome $env:JAVA_HOME -Jar target/stablediffusion-nui.jar -Version 0.1 -OutputRoot outputs/crea-post-merge-package
```

Use the exact ZIP printed by the package command as `$creaZip`; use a fresh extraction directory. Check `missing`, `differ`, and `jar_equal` in the payload checker output, and separately compare all 54 `creaprompt/` resources inside the extracted jar with the repository. This test package is not a release.

```powershell
$creaZip = '<exact ZIP path printed above>'
python -I scripts/dev/payload_check.py . $creaZip outputs/crea-post-merge-extracted
$creaApp = (Resolve-Path outputs/crea-post-merge-extracted/SDNUI).Path
$creaJar = Join-Path $creaApp 'app/stablediffusion-nui.jar'
$creaSdk = 'C:/Users/Mizushima/.codex/worktrees/affc/stablediffusion-nui/outputs/mcp-client-venv/Scripts/python.exe'
$creaPython = 'C:/Users/Mizushima/dev/stablediffusion-nui/python/.venv-smoke/Scripts/python.exe'
$creaTestPython = 'C:/Users/Mizushima/dev/stablediffusion-nui/python/.venv-test/Scripts/python.exe'
$creaJava = 'C:/Program Files/Java/jdk-21/bin/java.exe'
& $creaSdk scripts/dev/check_creaprompt_home.py --source . --output outputs/crea-post-merge-home-direct --jar target/stablediffusion-nui.jar --java $creaJava --python $creaTestPython
& $creaSdk scripts/dev/check_creaprompt_home.py --source $creaApp --output outputs/crea-post-merge-home-launcher --launcher (Join-Path $creaApp 'SDNUI.exe') --jar $creaJar --java $creaJava --python $creaTestPython
```

For each command below acquire `C:/Users/Mizushima/dev/stablediffusion-nui/outputs/.gpu.lock` by creating that directory, release only the lock you own in `finally`, and do not terminate unrelated GPU processes. Each run uses the extracted launcher, verifies that its jar matches `$creaJar`, and removes `SDFX_HOME` from its subprocess environment. Use distinct output directories; inspect `receipt.json`, its jar hash, and the generated images for both families. If GPU contention makes the test impractical, retain the logs and report it as unverified.

```powershell
$creaModels = 'D:/stable-diffusion-webui-reForge/models/Stable-diffusion'
& $creaSdk scripts/dev/check_creaprompt_real.py --source $creaApp --launcher (Join-Path $creaApp 'SDNUI.exe') --jar $creaJar --python $creaPython --models $creaModels --checkpoint animekawa_v10 --width 512 --height 512 --steps 20 --output outputs/crea-post-merge-pkg-sd15
& $creaSdk scripts/dev/check_creaprompt_real.py --source $creaApp --launcher (Join-Path $creaApp 'SDNUI.exe') --jar $creaJar --python $creaPython --models $creaModels --checkpoint waiIllustriousSDXL_v170 --width 832 --height 1216 --steps 20 --output outputs/crea-post-merge-pkg-sdxl
```

## Earlier verification (pre-merge)

* Verified: Scala suite `CreaPromptSuite` (13 tests, passed: bundled lists, preset matching, manual picks, seeding, positions, user folder, script run, interruption, registration); plugins tests ran; real SD1.5 `animekawa_v10` 512x512, 20 steps through the official MCP SDK (`scripts/dev/check_creaprompt_real.py`, `outputs/crea-real-sd15`, `success: true`): different seeded prompt per image, same seed gives identical prompts and images, PNG replay with plain txt2img is pixel-identical, one prompt for a batch with prefix/suffix/After position, collection mode, user category from the app folder, manual picks, four error cases, async cancel at about 9 % and baseline identical afterwards. Images were looked at (contact sheet): portraits matching the woman presets, a skeleton-art collection prompt, a whale in a city for the user list.
* At this stage: full Scala suite after the then-latest change had not been re-run (an earlier full run failed on ledger coverage); Python tests were not run (no Python change); the extracted Windows package had not yet been tested. Later checks are recorded below.
* Subsequent work: Manual mode now has one choice per category, including random and none, while retaining the text-list input. `outputs/crea-manual-red.log` recorded a failing test for missing controls; `outputs/crea-manual-green.log` passed all 14 focused Scala tests and `ui/assembly`. The SDK real-model harness checks that manual controls produce the same prompt and RGB as equivalent text-list picks; SD1.5 and SDXL passed as described below.
* A mounted generic JavaFX form check (`outputs/crea-controls.log`) passed mode and category-choice value bindings. Its screenshot `outputs/crea-controls/controls.png` was viewed: category labels and dropdowns are readable in the expanded, scrollable manual section. This is a generic form, not a MainWindow or model-generation check.
* Actual MainWindow check (`outputs/crea-main-window-lf.log`) selected the script, expanded the manual section and checked a dropdown binding; no engine or generation was started. The screenshot `outputs/crea-main-window-lf/main-window.png` was viewed and shows the category controls in the app's scrollable form.
* New LF-resource assembly SHA-256 `5f9e941a2f703a08813ca5015c8923dd1a0045797d4c62fa5a270b5a94bdf227` passed the official SDK SD1.5 test with 512 × 512 at 20 steps (`outputs/crea-v2-sd15/receipt.json`). Manual dropdown and text-list inputs produced the same prompt and exact RGB, as did random prompt replay; cancellation and subsequent generation passed. `outputs/crea-v2-sd15/sheet.jpg` was viewed: woman portraits and a whale in a city, with matching replay and manual-pick pairs. The same SDK test passed for SDXL `waiIllustriousSDXL_v170`, 832 × 1216 at 20 steps (`outputs/crea-v2-sdxl/receipt.json`, 39 calls, cancellation succeeded). Its contact sheet (`outputs/crea-v2-sdxl/sheet.jpg`) was viewed: portraits, a skeleton illustration, and a whale over a city; replay and manual-pick pairs match. These results are not an extracted Windows package check.
* Category presets, live manual preview and the Active label are now provided. The preset saves random-mode category names, not manual entry choices; the preview displays a `Random: <category>` placeholder rather than claiming a random entry was already drawn. The selected Script displays Active; an unselected script has no visible form. Names are validated, existing files and bundled preset names cannot be overwritten, and the chooser discovers newly saved files when opened.
* The 54 bundled list and preset filenames match the upstream archive at the pinned commit, and their contents match byte-for-byte after CRLF-to-LF normalization (checked 2026-11-03). The worktree files were then normalized to LF to match their Git index entries; the rebuilt assembly was checked to contain all 54 byte-identical LF resources.
* Data note: the upstream collection contains a mojibake sequence (`skeleton?fs`) that is bundled as published; 27 names in 9 upstream presets match no list and are ignored.
* The jar carries the lists as resources. A later package build and byte comparison are recorded below; that is not evidence for the post-merge package.

## Preset and preview follow-up (2026-11-03)

Focused Scala RED (`outputs/crea-preset-preview-red.log`) was followed by GREEN (16 tests), generic JavaFX form checks, MainWindow script selection and assembly (`outputs/crea-preset-preview-final.log`). The form check covers saving, immediate selection, discovery of another new preset, live preview changes and the Active label; the Scala tests check overwrite and unsafe-name rejection. The screenshot `outputs/crea-preset-preview-final/controls.png` was viewed: the status, preview and save controls precede the long category form. The tested jar SHA-256 was `4e9a4f34d595bfa0e9c58d81a6e5396b73b519c0286d8103410c7fbe43a6d5bb`. A later source correction makes preset name conflicts case-insensitive and reports existing user presets correctly; its subsequent checks are recorded below. SD1.5 official SDK check (`outputs/crea-v3-sd15/receipt.json`, 36 calls) passed, including a user .config preset, exact replay, manual list/dropdown equivalence and cancellation/recovery. Its contact sheet was viewed. That same jar passed SDXL at 832 × 1216 (`outputs/crea-v3-sdxl/receipt.json`, 40 calls), including user preset, exact replay, manual list/dropdown equality and cancellation/recovery. Its contact sheet was viewed: a portrait, line drawing, a whale over a city, and matching replay pairs. No extracted Windows package was tested for this particular jar.

## Follow-up build and package check

After the name-conflict fix, `sbt test ui/assembly` exited 0 (`outputs/crea-full-regression.log`): 96 MCP, 299 plugin and 12 UI tests ran; other modules reported zero due to sbt 2 test caching and are not claimed as freshly re-run. The assembly SHA-256 `7266da082df6cd8cf73f15ae5bf50722fe9bf5d43c190d20478304b068a08ae7` matches the jar extracted from `outputs/crea-package-final/build-e811cb88c5d94347bc028bb513c13a95/SDNUI-v0.1-windows-x64.zip` (ZIP SHA-256 `6fc99373e7f0cbd70ceaf97476feefef2659919d4b754eafae22030d55ff3b5c`). All 54 CreaPrompt data resources in that jar match the source bytes. The extracted launcher passed SD1.5 via the official SDK (`outputs/crea-v4-package-sd15/receipt.json`, 36 calls); its contact sheet was viewed. The corresponding SDXL run was interrupted as described below.

## Explicit home correction

Independent review found the direct `McpMain --home` path did not reach CreaPrompt: it called `AppHome.detect()` independently of the host. `outputs/crea-home-red.log` reproduced this via official MCP SDK `list_scripts` without `SDFX_HOME`. The Windows launcher already set the `sdfx.home` property when `--home` was passed (`outputs/crea-home-launcher-red.log` succeeds despite its historical name). The host now passes its `AppHome` explicitly to standard plugins in both `McpMain` and `SdfxApp`. `outputs/crea-home-build.log` records 17 CreaPrompt and 30 MCP tests, the mounted JavaFX form and assembly; `outputs/crea-home-green.log` and `outputs/crea-home-launcher-green.log` record SDK discovery against direct and extracted-launcher entrypoints respectively, without `SDFX_HOME`. These are dry-run `list_scripts` checks; each client closes before the engine finishes starting.

At this stage the extracted ZIP `outputs/crea-home-package/build-2a1a86bc0f324d77b4f0efc99a09b66c/SDNUI-v0.1-windows-x64.zip` SHA-256 was `5a8fd7ce5c649c62343921cd7f859c7443be6a03fa8eb2e4ce336fbfa2ac9861`. Its jar and the built jar matched SHA-256 `938b2f06bb0654a1d893a3e1903ddd460b18c5c3839ff944f2e62992bd1e859e`. Real SD1.5/SDXL runs on that jar had not been completed; later post-merge builds were tested as documented below. A preceding package SDXL run (`outputs/crea-v4-package-sdxl.log`) was interrupted by the test operator after several minutes with no first image, while GPU memory was almost full and another D:/SDNUI process was present. The other process was left untouched.

## After main c484556 merge

Merge `d034892c3e336f1e61c66925abb96f265a81da7d` retained main's negative-suggestions implementation unchanged. JDK 21 CreaPrompt focused (17 tests), negative-suggestions focused (6 tests), actual CreaPrompt generic form and MainWindow, negative-suggestions MainWindow, and assembly passed (`$TEMP/sdnui-crea-merged-c484.log`). The dry-run GUI checks did not generate images. Jar SHA-256 `471a2fc5493388db9197f57028180ccfd79d7339ab3731e1175be445fa7ce7d6` matches the extracted jar from `outputs/crea-merged-c484-package/build-c4c032733d3f41e0925ec34217ee42f5/SDNUI-v0.1-windows-x64.zip` (ZIP SHA-256 `2678e2d085e1c74a8f7c6040e34c8b028ca53eb86b8cfde557ba9c4c71978708`).

The extracted launcher, official MCP client, smoke Python worker and checkout Python source passed real SD1.5 512x512 and SDXL 832x1216, 20 steps, including user category and preset from explicit `--home` with `SDFX_HOME` unset, exact replay, manual choices and interruption/recovery. Receipts are `outputs/crea-merged-c484-sd15/receipt.json` and `outputs/crea-merged-c484-sdxl/receipt.json`; logs are `$TEMP/sdnui-crea-merged-c484-sd15.log` and `$TEMP/sdnui-crea-merged-c484-sdxl.log`. Both contact sheets were viewed: portraits, collection images and whale-in-city examples, including matching manual pairs. These checks apply to the c484556 merge, not the later c4abab3 merge; they do not establish real generation from the extracted GUI.

## After main c4abab3 merge

JDK 21 CreaPrompt focused 17, negative suggestions focused 6, both CreaPrompt form/MainWindow checks, negative suggestions MainWindow and assembly passed (`$TEMP/sdnui-crea-merged-c4.log`). Direct `McpMain --home` and extracted launcher discovery passed without `SDFX_HOME` (`$TEMP/sdnui-crea-c4-home-direct.log`, `$TEMP/sdnui-crea-c4-home-launcher.log`); these dry-run discovery clients close before engine startup completes.

The built jar SHA-256 `84608b3ad86fb8ae16362dde4e38de7311f607bbb2b6cc776789685b1a55322a` matches the extracted jar in `outputs/crea-merged-c4-package/build-917c3492768c45e7b96ef524ae1dc411/SDNUI-v0.1-windows-x64.zip` (ZIP SHA-256 `b7a2d48d16f2289ad76d5caca7bfc7cc98d187e6a4317ef158a109bddc5a62db`). The extracted EXE plus official MCP SDK and checkout Python engine passed SD1.5 and SDXL (`outputs/crea-merged-c4-sd15/receipt.json`, `outputs/crea-merged-c4-sdxl/receipt.json`; logs `$TEMP/sdnui-crea-merged-c4-sd15.log`, `$TEMP/sdnui-crea-merged-c4-sdxl.log`). It exercised user category and preset from `--home` with `SDFX_HOME` unset, replay, manual selections, errors and cancellation/recovery. The two contact sheets were inspected, showing portraits, collection pictures, a whale in a city and matching manual pairs.

Separately, `RealCreaPromptGenerationCheck` drove the actual MainWindow Script selector, selected a custom home category via its manual control and fired Generate using source classes. Real SD1.5 512x512 and SDXL 768x768 both wrote the selected prompt, existing negative and `CreaPrompt: manual from categories` in PNG metadata. Receipts are under `outputs/crea-merged-c4-gui-sd15` and `outputs/crea-merged-c4-gui-sdxl`; image SHA-256 respectively `17d32cbbe9ec9d2f2f5c6c1b683edd744cf6cf7ca149ba7b7723a2613957bab1` and `351b3157905b221bf5e1d4ab9130ae71291e59d1d9f376593f80af4e025d0ec5`. Both images were viewed: a whale over city buildings. The extracted EXE GUI generation path remains untested; the extracted EXE was exercised through MCP as described above.
