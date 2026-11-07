# State Manager history and category restore (#23, partial)

## 2026-11-03 Clear history の名前付き項目保護

main 統合時も `GenerationHistorySuite` 20件、`RealNamedGenerationStatesCheck`、`ui/assembly` が exit 0。JAR SHA-256 は `91c567ad2b0e1331c3590ae39082d7db261b5056e59207788091c5ff0a6e2c95`。下記の実モデル試験は分離 worktree の source-class GUI で、この main JAR や配布版の実測ではない。

上流固定 revision `2af62ecd5287438bbc309f7e840d34aa5c8e3cb6` の `clearHistory` はお気に入りと明示的に名付けた項目を残す。NUI は自動生成された日時名も含め全履歴に名前があるため、新しい Rename の確定を native record の追加 metadata に記録し、Clear history ではその項目も残す。過去に取り込まれた上流の entry に非空 `name` がある場合も保護する。以前の NUI 版で Rename 済みの項目には記録がないので、再度 Rename を確定すれば保護される。確認画面と日本語文書に明示し、50 件上限による通常の古い項目の退避には保護を適用しない。

修正前の回帰テストは名前付き項目が削除されて失敗。修正後の `GenerationHistorySuite` は 20/20、保存・読込後の名前付き項目とお気に入りの維持、未命名の削除、元の上流名の保護を確認。実 MainWindow の初回試験は clear 後も表示件数が同じため test が完了前に次へ進んで失敗し、完了状態を待つよう修正して再実行が成功。`outputs/history-clear-dialog-final/history-clear-confirmation.png` の注意文を目視した。

source-class 実 MainWindow と実エンジンで、生成履歴を Rename し favourite を解除後、Clear でも名前と thumbnail が残ることを SD1.5 512×512 と SDXL 832×832 の各8 steps で確認（`HISTORY_REAL_OK`）。結果は `outputs/history-clear-real-sd15/history-real-6572943177763100084` と `outputs/history-clear-real-sdxl/history-real-10027898092565766905`。両 `00000-7101.png` は桜と赤い着物の人物と目視。SHA-256 は順に `de17c3627754f43bbaa7d35b0eb6d4d04f7e7bcfde7204941fa0d09dc9cbbac8`、`5e8ec8ff7caa72b1ebe19ff2955251598f2e1cd33cbed98a0705ec7b56990d42`。実上流ファイルと配布 EXE は未確認。

## 2026-11-03 現在の設定をお気に入りに保存

main 統合時に `GenerationHistorySuite` 19件、`RealNamedGenerationStatesCheck` と `ui/assembly` が exit 0。JAR SHA-256 は `02a7c789793acdcf579b5e1fc395785b420e184d1d0a82fd10d94daac9a2da01`。下記の実モデル試験は分離 worktree の source-class GUI によるもので、この main JAR や配布版の実測ではない。

上流の Save current UI settings に対応し、Favourites の `Save current as favourite` から下の名前欄を使って現在の mode の設定を生成せずに保存できる。履歴には追加せず、画像サムネイルも付けない。重複名や ID を拒否し、履歴の操作ロック内で保存直前に再検証する。数値欄が無効なら他の数値を確定せず保存を中止する。数値が有効なら保存に先立って欄が確定され、後の重複名や I/O エラーでもその値は画面に残る。

`GenerationHistorySuite` 19/19 と offline 実 MainWindow で、無効 draft、重複名、mode 切替、保存済み prompt の復元、生成ジョブが始まらないことを確認し、`outputs/favourite-capture-gui/favourite-current-saved.png` を目視した。追加 GUI 試験 `outputs/favourite-capture-disk` は mode 切替中の保存先を disk でも照合。配布 EXE、物理操作、別 JVM の同名同時保存は未確認。

source-class 実 MainWindow と実エンジンで、画像生成前に現在の設定を favourite に保存し、ジョブが開始されないこと、prompt を変更後に復元して実生成できることを SD1.5 512×512 と SDXL 832×832 の各8 steps で確認（`HISTORY_REAL_OK`）。結果は `outputs/favourite-capture-sd15/history-real-12645717205594699804` と `outputs/favourite-capture-sdxl/history-real-908297879063682458`。生成画像 `00000-7101.png` は両方とも桜と赤い着物の人物と目視。SHA-256 は順に `de17c3627754f43bbaa7d35b0eb6d4d04f7e7bcfde7204941fa0d09dc9cbbac8`、`5e8ec8ff7caa72b1ebe19ff2955251598f2e1cd33cbed98a0705ec7b56990d42`。

## 2026-11-03 履歴とお気に入りの名前変更

main 統合時、`GenerationHistorySuite` 16件、`RealNamedGenerationStatesCheck` と `ui/assembly` が exit 0。JAR SHA-256 は `df9adb4cca0cf98a664166002b09a7ea6b17fb8523e112b0b3d2c7d0bb8ec723`。下記の実モデル試験は分離 worktree の source-class GUI で、この JAR や配布版の実測ではない。

上流の項目名編集に対応し、一件を選んで Rename できる。ID と settings・source metadata・thumbnail を維持し、同じ ID が history / favourites の両方にあれば両方を更新する。同一 mode の重複名・無効名・選択時の名前が既に変わった項目は拒否。旧名に基づく二つの同時保存では片方だけ成功し、拒否後の JSON bytes が不変であることを 2 store / 同一 JVM で確認した。別 JVM の同時改名は未試験。

`GenerationHistorySuite` 16/16、offline 実 MainWindow は両群の名前変更・改名で検索対象から外れた項目の選択解除と再表示を確認。`outputs/history-rename-final/named-states-favourites.png` と `named-states-history-search.png` を目視し、画面内のボタンと改名結果が見えることを確認した。配布版・物理操作は未検証。

source-class 実 MainWindow で favourites を検索・改名後、prompt を復元して実生成し、PNG metadata を照合。SD1.5 512×512 と SDXL 832×832 は各8 steps で `HISTORY_REAL_OK`、結果は `outputs/history-rename-real-sd15/history-real-9773342379648639244` と `outputs/history-rename-real-sdxl/history-real-3970178151943878629`。生成した `00002-7102.png` を目視し、いずれも桜と赤い着物の人物を確認。SHA-256 は順に `720006d73e6113b03c2435a3123a4467e69a31eb6293118c54b8c1711e134533`、`c92e297e7a04cba17c2d6933696fafc08f8447176bd21019d764c205cc381803`。

## 2026-11-03 履歴検索と mode 切替

main への部分統合時、`GenerationHistorySuite` 14件、`RealNamedGenerationStatesCheck`、`ui/assembly` が exit 0。JAR SHA-256 は `f4c7ca9245c0bcc1f003f482c7cef3a186971963e87dfdc2efb53085cda184fe`。下記の実モデル試験は分離 worktree の source-class GUI によるもので、この main JAR や配布版の実測ではない。

上流固定 revision の検索は名前・prompt・negative prompt・checkpoint・sampler をカンマ区切りの AND 条件で検索する。NUI の History / Favourites それぞれに同じ対象の検索を追加し、大文字小文字を区別しない。検索で隠れた選択は解除し、Clear history はフィルタ外も消すことを確認画面に明示した。

独立レビューで、mode 切替直後に古い mode の一覧・選択が非同期読込み完了まで残る問題が見つかった。旧一覧を直ちに消して履歴操作を読込み中無効化し、古い request の成功・失敗 callback は無視する。修正前 GUI は旧項目残存で失敗し、修正後 `GenerationHistorySuite` 14/14 と source-class の実 MainWindow offline GUI が成功。検索と選択解除・2 mode の切替を確認し `outputs/history-search-final/named-states-history-search.png` を目視した。callback の到着順を人工的に逆転させた試験、配布 EXE、物理操作は未検証。この時点では追加 GPU 試験は行っていなかった。

追加 SD1.5 実機試験では画像2枚の生成・履歴記録後、お気に入り2件を待つ箇所で timeout（`outputs/history-search-real-sd15`）。サムネイル生成後の非同期再読込み中はボタンが一時無効になるため、テストが無効なボタンを発火した可能性がある。テストで選択数とボタン有効を同じ FX callback で確認して発火するよう改めたが、再試験まで原因確定・成功とは扱わない。SDXL は実行されなかった。

再試験 `outputs/history-search-real2-sd15` と診断 `outputs/history-search-diagnose-sd15` でも test の待機条件が失敗。診断では items=2、selected=2、button disabled=true。test helper が成功した条件をもう一度評価するため、条件内でボタンを発火した直後の pending 状態を見て失敗していた。発火を一回に限定して修正、初回 timeout の原因は確定していない。

修正後の source-class 実 MainWindow と実エンジンで SD1.5 512×512、SDXL 832×832、各8 steps の両方が `HISTORY_REAL_OK`。2 件の favourites を検索語 `girl, KIMONO` で絞り、prompt を復元して seed 7102 で生成、PNG metadata に prompt・seed を確認。結果 `outputs/history-search-real3-sd15/history-real-700894323517528573` と `outputs/history-search-real3-sdxl/history-real-1538823375784163188` の `00002-7102.png` を目視し、両方とも桜と赤い着物の人物と確認した。SHA-256 はそれぞれ `720006d73e6113b03c2435a3123a4467e69a31eb6293118c54b8c1711e134533` と `c92e297e7a04cba17c2d6933696fafc08f8447176bd21019d764c205cc381803`。配布 EXE は未検証。

## 2026-11-03: お気に入りと複数選択（部分統合）

main 統合時に `PresetStoreSuite` 18件、`NamedGenerationStatesSuite` と `GenerationHistorySuite` 計18件、`RealNamedGenerationStatesCheck`、`ui/assembly` が exit 0。JAR SHA-256 は `58cc4ac59ec7abbfa98b1c63c12d83cc731c0e001f394f375b22847db3d407ba`。下記の実モデル検証は分離 worktree の source-class GUI によるもので、main JAR や配布 EXE GUI の実測ではない。

上流 MIT 固定版 `2af62ecd5287438bbc309f7e840d34aa5c8e3cb6` の README と TypeScript を確認。複数選択時に上流が提供するのは一括お気に入り／解除と一括削除で、複数エントリの設定を一括適用する機能は見つからない。以前の「bulk apply が未対応」という記述は誤りだった。NUI では History と Favourites の両タブから複数選択し、お気に入りの一括追加・解除と明示削除ができる。お気に入りは履歴の上限から外れても、また Clear History 後も残り、履歴とお気に入りの双方から削除したときにだけ対応するサムネイルを削除する。消えた選択項目を使う更新はエラーとして拒否する。

履歴は `<home>/config/generation-history/library-v1.json` の同一トランザクション内に最大3モード分の favourites collection を追加して保存する（読取上限は既存の3から6 collection に拡張、従来の3 collection の履歴も読み取れる）。JSON 全体の容量・件数制限に達すると保存はエラーになり、それまでのデータは維持される。上流 IndexedDB / 共有ファイルとの互換性はない。

競合対策は、この版の NUI 同士が同じ `.history-operations.lock` を使い、操作中にロックファイルやディレクトリが置換されないことを前提とする。外部ツールや旧版が JSON やサムネイルを直接変更する場合の整合は保証しない。`thumbs` の既存 symlink / junction は拒否するが、検査後にディレクトリを入れ替える攻撃への耐性は未検証。サムネイルの生成・削除エラーは画像だけが失われ得て、利用者への通知はない。外側の操作ロックの解除や close の異常は成功済み JSON 保存を失敗扱いしないため握りつぶされる（外側の異常注入は未実施）。履歴 JSON への保存失敗は操作画面に表示し、自動記録時はエラーダイアログで通知する。

共通 `PresetStore` では以前、保存やバックアップ復元の atomic replace 成功後に一時ファイル掃除が失敗すると、内容が入れ替わったにもかかわらず失敗と通知された。`outputs/preset-postcommit-red.log` の2件で再現後、replace が正常に戻った以降の掃除と library lock の release / close 異常が成功を覆さないよう修正した。`outputs/preset-postcommit-green.log` の core 18件に、save と restore の掃除失敗、および lock release 前・後／close 後の異常注入と、置換前の既存失敗テストが含まれる。同ログの UI 18件と実 MainWindow も成功。release と close がともに実際に失敗すればリソースやロックが残り、別プロセスが待機し続ける可能性がある。異常注入では release 前に失敗した場合も channel.close は成功しており、この二重失敗は試していない。atomic replace 自体が例外を返した場合に実際のファイル置換が完了していたかまでは、この試験では検証していない。

同一 JVM の2 store による同時記録では当初 `OverlappingFileLockException` を再現した（`outputs/favourites-concurrent-red.log`）。修正後の `outputs/favourites-safe-green.log` は計18テストと実 MainWindow の成功を記録する。同一 JVM・Windows junction 経由の同一 root、別 JVM 同士の記録、clear とお気に入り追加、thumbnail 書き込みと削除・お気に入り解除の交差、別 JVM が操作ロックを実際に保持している間の待機、過大な入力拒否と既存 JSON の byte 不変、`thumbs` の junction による外部ファイルの読取・上書き・削除拒否を確認した。近接起動の競合試験は全ての実行順序を網羅しない。

単体 RED は `outputs/favourites-red2.log`、UI RED は `outputs/favourites-ui-red.log`。`outputs/favourites-ui-green2.log` では `GenerationHistorySuite` と `NamedGenerationStatesSuite` 計12件および実 MainWindow で複数追加・解除・復元・Clear 保護・明示削除が成功。3モードの保存、再起動、履歴上限からの退避とサムネイル保持、消えた ID の拒否も確認した。`outputs/favourites-ui-green/named-states-favourites.png` を目視。これらの `outputs/` は `C:/Users/Mizushima/.codex/worktrees/state-manager23-favourites/stablediffusion-nui` にある。

実 GUI + 実エンジンで履歴2件を一括お気に入り化し、Favourites の一件から prompt を復元して別 seed で生成、PNG の prompt と seed を検証。SD1.5 (`animekawa_v10`, 512×512, 8 steps) と SDXL (`waiIllustriousSDXL_v170`, 832×832, 8 steps) とも成功。ログ `outputs/favourites-real.log`、結果 `outputs/favourites-real-sd15/history-real-6651249467607331458/report.json` と `outputs/favourites-real-sdxl/history-real-627994625375117064/report.json`。生成画像は両方とも桜の下の赤い着物の人物と目視。SHA-256 はそれぞれ `720006d73e6113b03c2435a3123a4467e69a31eb6293118c54b8c1711e134533`、`c92e297e7a04cba17c2d6933696fafc08f8447176bd21019d764c205cc381803`。配布版 GUI は未検証。issue #23 の残りは下記。

競合・共通保存処理の最終修正後にも両モデルで同じ MainWindow 操作と生成を再実行し成功、両画像を目視した。ログ `outputs/favourites-final-real.log`、結果 `outputs/favourites-final-sd15/history-real-9166437097752506640/report.json` と `outputs/favourites-final-sdxl/history-real-7575680490303200089/report.json`。これも source-class GUI の検証で、配布 EXE GUI の実測ではない。

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

Upstream IndexedDB extraction and full shared .txt storage format parity, the `/version` and other upstream HTTP API endpoints, the docked
side-drawer / modal browser UI. Partial V2 file import and read-only MCP history are available. Multiple selection provides bulk favourite and delete actions,
and individual saved settings and the six categories can be applied as described above.
Issue stays open (`Refs #23`).
