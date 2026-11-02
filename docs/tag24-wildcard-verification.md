# Wildcard completion work for #24

This records a partial change to #24; it does not close the issue. See
`tag-autocomplete-acceptance-24.md` for the full remaining scope.

Typing `__` offers wildcard files from the existing Dynamic Prompts roots. With multiple matches,
Tab extends their common prefix and preserves typed case. Enter or an explicit selection inserts
the normal `__name__` reference. If a name or closing delimiter already follows the caret, partial
Tab leaves it intact. After selection, a second popup offers the file's lines; choosing one replaces
the reference, while Escape keeps it. The popup displays page numbers and Previous/Next controls;
PageUp/PageDown also work. Each page respects the configured maximum results.

File reading is asynchronous, ordered by configured root, and limited to 1 MiB and 5,000 non-empty,
non-comment lines. The popup reports omitted lines. Absolute/traversal names and static symlink
escapes are rejected. Pending results and errors are discarded after edits, caret movement, Escape,
focus loss, or a settings change. Validation of a path followed by replacement of an intermediate
directory is not an atomic security boundary; the existing Dynamic Prompts loader shares this limit.

## Checks

- JDK 21: `WildcardLinesSuite`, `CompletionEngineSuite` and `TagCompletionSuite`: 25 passed.
  Real JavaFX `NativeWildcardAsyncCheck`, `NativeWildcardPartialCheck` and the existing
  `NativeTagAutocompleteCheck` passed (`outputs/codex-tag24-async-green.log`). These include
  suffix preservation, case handling, static symlink rejection, page limits, stale results/errors,
  settings changes and synthetic IME. Physical Japanese IME remains untested.
- `RealTagAutocompleteExtrasCheck`: real MainWindow and engine, SD1.5 `animekawa_v10` and SDXL
  `waiIllustriousSDXL_v170`, 28 checks each. It reads actual wildcard files from the app home,
  uses JavaFX Robot native mouse input to click the Next button and a non-empty `orange` cell in
  the showing PopupWindow, checks editor focus and Enter, then generates through Dynamic Prompts.
  The saved PNG's prompt contains the expanded wildcard. Logs:
  `outputs/codex-tag24-real-sd15-final.log`, `outputs/codex-tag24-real-sdxl.log`.
- Inspected SD1.5 output: a blue-haired fox-eared person. Inspected SDXL output: a blue-haired
  fox-eared person with a strong black/red mesh-like pattern across the image. These 8-step results
  establish the exercised path, not general image quality. The SDXL popup screenshot visibly shows
  page 2/2 and both navigation buttons.
- Earlier SD1.5 attempt timed out waiting for lines because a queued update invalidated a valid
  async result. `NativeWildcardAsyncCheck` reproduced that ordering before the fix and passed after.
  A subsequent click test timed out because its test selector could target a list in the main window;
  the final test restricts the click to the visible popup and checks the named cell.

The extracted Windows package GUI has not been verified for this change. Upstream wildcard next-folder
and always-full modes, category colours and the other #24 gaps remain open.
