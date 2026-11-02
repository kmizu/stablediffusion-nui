# State Manager history and category restore (#23, partial)

## 2026-11-03: 個別設定の復元（部分統合）

この節の `outputs/` は検証時の worktree `C:/Users/Mizushima/.codex/worktrees/state-manager23/stablediffusion-nui` にある。

main への統合時にも `NamedGenerationStatesSuite` と `GenerationHistorySuite` の計10件、`RealNamedGenerationStatesCheck` の画面回帰、`ui/assembly` が exit 0。main の JAR SHA-256 は `2b5628c228274e26edc126c7dfd3bb3c5b7d5dd3f301a8d994244574faf6bf69`。下記の実モデル実測は分離 worktree の source-class GUI によるもので、main JAR や配布版 GUI での実モデル検証ではない。

名前付き設定または履歴を一件選び、保存済み設定を選んで `Restore selected setting` を押すと、その項目だけを既存の確認・resource preflight・トランザクション付き復元経路で適用する。選択・モードが確認中に変わった場合は適用を中止する。WD バッチの有効化・入力の部分復元は変更後の有効状態と現在または復元された入力フォルダを照合する。

独立レビューで、部分復元でも全数値エディタの未確定値を確定し、無関係の不正入力が復元を阻む問題が見つかった。実 MainWindow テストで再現後、単一項目・一部カテゴリの復元では他の draft を触らず、成功時に復元対象の数値 editor だけ現在値に同期するように修正した。通常の spinner、plugin の spinner、共有 Clip skip、非表示 script の editor を確認。取消・失敗では draft を維持し、選択を失ったとき古いフィールド選択を解除する。`outputs/state-draft-green3.log` は GUI と `NamedGenerationStatesSuite`・`GenerationHistorySuite` の計10件の成功、`outputs/state-draft-final.log` は取消を加えた GUI と以下の両実モデル試験の成功を記録する。以前の plugin draft 試験の一度の失敗は、同名 ID を持つ別モードの UI をテストが取得したためで、対象モードに限定して再試験した。

最終修正後、実 MainWindow と実エンジンで SD1.5 は `outputs/state-draft-sd15/history-real-15105899207516660902`、SDXL は `outputs/state-draft-sdxl/history-real-15664001889307348840` の `report.json` が成功。単一 prompt 復元、生成後 PNG の prompt と seed、既存履歴の復元と生成まで確認し、両モデルの `00003-7102.png` は桜と赤い着物の人物として目視した。実機チェックに使ったのは source-class GUI であり、配布 EXE GUI は未検証。

個別 UI 追加前の実 MainWindow テストは対象コントロールが存在せず失敗、追加後は missing checkpoint と独立した prompt 復元・他の幅が変わらないことを含む offline 実 MainWindow テストが通過。`NamedGenerationStatesSuite` と `GenerationHistorySuite` は計10件成功。画面で復元ボタンの文言が省略されるのを見つけて幅を調整し、同じテストを再実行して成功。`outputs/state-field-green2/named-states-single-field.png` を目視しボタン全文を確認した。

`ui/Test/runMain sdfx.ui.RealGenerationHistoryCheck <output> <python> <checkpoint> <side> <steps>` を共有 GPU ロック確保下で実行。Python は `C:/Users/Mizushima/dev/stablediffusion-nui/python/.venv-smoke/Scripts/python.exe`、モデルは `D:/stable-diffusion-webui-reForge/models/Stable-diffusion/animekawa_v10.safetensors` (512, 8 steps) と `waiIllustriousSDXL_v170.safetensors` (832, 8 steps)。ともに実 MainWindow で履歴の prompt 一項目を復元し、他の seed・steps・CFG が変化していないこと、生成 PNG の prompt と seed を確認した。既存の完全復元、同一画像再生成、履歴停止と削除も確認し両方 exit 0。出力は `outputs/state-field-real-sd15/history-real-10736622833453242679` と `outputs/state-field-real-sdxl/history-real-15343461822596594072`、ログは対応する `outputs/state-field-real-{sd15,sdxl}.log`。各 `report.json` の `single-field history restore ... generated image` を目視し、ともに桜の下の赤い着物の人物と確認した。画像 SHA-256 はそれぞれ `720006d73e6113b03c2435a3123a4467e69a31eb6293118c54b8c1711e134533` と `c92e297e7a04cba17c2d6933696fafc08f8447176bd21019d764c205cc381803`。配布版 GUI、すべての項目・機能の組み合わせは未確認。元拡張の残りは本記録末尾に記載、issue は open のまま。

Upstream: SenshiSentou/sd-webui-state-manager, MIT, pinned `2af62ecd5287438bbc309f7e840d34aa5c8e3cb6`
(LICENSE SHA256 recorded in `scripts/third_party.py`). Independent implementation; no upstream code or assets copied.

## Added on top of v0.03 named states

- Automatic per-mode history of the full form (same snapshot as a named state) on every Generate/Queue, newest 50 per
  mode, identical-newest dedupe, on/off flag, 100 px thumbnail of the job's first image, delete / multi-select delete /
  Clear history / Keep as named state. Stored in `config/generation-history` (new collection ids `history-<mode>` in the
  preset JSON whitelist; separate store from `generation-states`).
- Category-selective restore (6 categories derived from field keys) for named states and history entries; the resource
  preflight only covers the selected fields.

## Verification

| Check | Result |
|---|---|
| `GenerationHistorySuite` (append/dedupe/cap+eviction, disk store record/list/delete/clear/mode isolation/thumbnail 300x150 -> 100x50, autosave flag, category restore changing only chosen categories) and the existing `NamedGenerationStatesSuite` | 9 + 9 pass |
| Real MainWindow + real engine, SD1.5 `animekawa_v10` 512x512, 8/9 steps (`ui/Test/runMain sdfx.ui.RealGenerationHistoryCheck`, receipts `outputs/history-real-sd15`) | passed |
| Same, SDXL `waiIllustriousSDXL_v170`, 832x832 (square; not 832x1216) | passed |

Each real run exercises: default-on recording and a 100x100 thumbnail (8067 / 9439 colours) after the first Generate;
a second entry shown newest first; regenerating an identical newest state adds no entry; prompts-only restore of the
older entry changes only the prompt (seed/steps/CFG unchanged); full restore reproduces `GenerationParams` exactly and
the regenerated image's pixels are identical to the first image; with recording off Generate adds no entry;
Keep as named state adds the entry to the named list; multi-select Delete removes two entries and their thumbnail files.
Images were viewed: SD1.5 and SDXL both show the red-kimono girl (entry A), the blue-armour knight at a castle (entry B,
identical on the repeat), and the History tab with thumbnail and prompt text in the screenshots.

## Findings and notes

- First full-restore attempt in the real run was blocked by the existing preflight: the img2img Hires Fix plugin's
  default upscaler "R-ESRGAN 4x+" is not in this machine's catalog. This is the long-standing named-state rule
  (`NamedGenerationStatesSuite` "without weakening live catalog checks" asserts it), so I did NOT relax it. The check
  instead provides a placeholder `R-ESRGAN 4x+.pth` in a temp upscaler dir so that name is in the catalog (Hires stays
  off; the file is never loaded). Users without that upscaler need a category-subset restore or to install it.
- The check script's own first run failed on a script bug (window used before assignment); fixed, no assertion changed.
- SDXL ran at 832x832 not 832x1216.

## NOT provided

Upstream IndexedDB / shared .txt storage import and format parity, the `/version` and other HTTP API endpoints, the docked
side-drawer / modal browser UI, ctrl/shift range multi-select driving bulk apply, a favourites flag (use Keep as named
state), MCP exposure of the history. Individual saved settings and the six categories can be applied as described above.
Issue stays open (`Refs #23`).
