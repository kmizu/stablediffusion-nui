# #6 Img2Img フォルダ入力（実装途中）

main に部分統合時、`VerifiedFolderInputSuite`、`Img2ImgFolderManifestSuite`、`Img2ImgFolderRunnerSuite`、`JobQueueSuite` 計44件、`McpToolsSuite` 43件、`ui/assembly` が exit 0。JAR SHA-256 は `c2ffb54da092c4930dadaab039da41c872e292d6ce710d786ecfd4afacede70f`。下記の実モデル試験は分離 worktree の JAR と source によるもので、main JAR と配布版の実測ではない。#6 は別 checkpoint などが残り open。

上流の README は img2img で単一画像か画像フォルダを選び、各画像に Batch Face Swap を適用することを説明する。元コードは無許諾なので読まず、既存 Img2Img と AlwaysOn を使う独立実装とする。顔だけ別チェックポイントは別の未完了要件。

1. バッチの入力列挙・検証は専用コンポーネントで実装し、順序、対応拡張子、上限、非通常ファイル、変更と中断を検証する。入力の内容は job ごとに確定させ、元ファイルを変更しない。
2. 画像ごとに同一 queue job の `PluginContext.generate` を使い、AlwaysOn と画像保護、処理数制限、中断・途中結果・各画像の infotext を保つ。無効な画像や途中失敗の扱いを明示し、意図しない上書きをしない。
3. Img2Img 画面にフォルダ選択を接続し、単一画像／WD batch との排他と queue 時の値固定を検証する。出力先と結果の見せ方を日本語で説明する。
4. MCP からも同じ機能を利用できるよう、既存 `PathPolicy` の許可済みディレクトリ範囲と JobLimits を守る専用 tool または同等の安全な経路を設ける。未許可・UNC・リンク経由の脱出や race を試験する。
5. 保護 PNG、キャンセル／復旧、途中失敗、他の AlwaysOn、SD1.5 と SDXL の実画像で、GUI と公式 MCP SDK による出力を確認し目視する。配布版と日本語マニュアルも確認し、未検証の項目を残して issue を誤って閉じない。

現状は queue、GUI、MCP の非同期 tool を接続した試作。Windows で `engine/testOnly sdfx.engine.Img2ImgFolderManifestSuite sdfx.engine.Img2ImgFolderRunnerSuite` 10 件、`mcp/testOnly sdfx.mcp.McpToolsSuite -- --tests *img2img*folder*` 1 件、`ui/compile` が通過。MCP のテストは fake engine を使い、2 入力に対する PNG の元パスと許可外フォルダの拒否を確認した。manifest の初回テストは Windows のファイル ID 取得の初回タイムアウトで失敗し、バッチ用の待機時間を延長した。runner の未実装時と許可範囲検査の未実装時にコンパイルが失敗することも確認した。WD v3 専用 batch は active extension を拒否するため、このまま流用できない。

生成要求には一時ファイルを渡し、保存された PNG の `NUI img2img source` と結果パラメータには元入力のパスを記録する。失敗時の部分結果、各種 Script と AlwaysOn の組み合わせ、配布版、大規模フォルダでの負荷は未確認。MCP の制限付きフォルダは後述の handle 固定方式を使い、GUI の直接選択フォルダはこの制約を受けない。単なる入力列挙だけでは #6 のフォルダ機能を完了したことにはならない。

追加検証（2026-11-03）: fake engine の MCP テスト 2 件で途中キャンセルの部分結果と後続ジョブの復旧を確認。Java 21 assembly で作成した jar SHA-256 `34ecef47c7cac0699235ec09a6579aa72ba8f83cd835fcfd80ed29f765d8cbc7` を公式 MCP SDK から使い、SD1.5 `animekawa_v10` と SDXL `waiIllustriousSDXL_v170` それぞれ 2 入力の通常／Batch Face Swap 処理、入力ハッシュ不変と出力 PNG の元パスを確認。画像を両モデルとも目視した（`outputs/img2img-folder-real-sd15/receipt.json`、`outputs/img2img-folder-real-sdxl/receipt.json`）。実 MainWindow の SD1.5 でチェックボックスの `fire()`、2 件の画像処理とギャラリーを確認、`IMG2IMG_FOLDER_GUI_OK`。操作欄が見える撮り直し画像と結果画像を目視した（`outputs/img2img-folder-gui2/img2img-native-564126436027731936`）。SDXL の native GUI、実マウス操作、上記以外の Script と保護 PNG、配布版は引き続き未検証。この段落の初期 MCP 実モデル試験は grid 修正前の jar に対するもの。修正後の再検証は下記。

保護入力に関する追加の fake engine テスト: ジョブ投入後に GUI 側の session をロックしても、取得済みの secret で入力を扱い、出力が保護されることを確認。secret なしのジョブと MCP ジョブは保護された入力を処理せず失敗する。実モデル・実 GUI での保護画像の検証は未実施。

Script が `ctx.saveImage` で grid などを保存すると返却 `OutputImage.params` に一時入力パスが残る問題を RED テストで再現し、修正後に GREEN を確認した。2 入力 × 2 sub-generations、AlwaysOn、grid の元パスとジョブ全体の生成上限を `JobQueueSuite` で確認。MCP folder 3 件とパスに日本語・カンマを含む metadata を含む runner 5 件も通過した。修正後の jar SHA-256 `cda3e58bfde7d485a610376ace52f6d2be20faec84ae5a8ced86f652f23ad156` を使用し、異なる 2 枚の入力についても同じ実 MCP 検証が SD1.5 と SDXL で通過。各入力と通常出力の差、顔処理の変化、入力ハッシュ不変、元パスを確認し、画像を目視した（`outputs/img2img-folder-distinct-sd15/receipt.json`、`outputs/img2img-folder-distinct-sdxl/receipt.json`）。独立レビューとパス安全性の判断までは統合しないこと。

独立レビューで非 Script の番号付き自動 grid に単独の `NUI img2img source` PNG chunk が欠ける問題を指摘され、`JobQueueSuite` で RED を再現して修正後 GREEN。関連 engine 6 件・MCP 3 件と Java 21 assembly が通過。最終 jar SHA-256 `5147a81301846cff202d7d1372ce10be9bbb3f27a1748de94f60bee9f0b18fe9` を公式 MCP SDK で実行し、SD1.5・SDXL とも異なる 2 入力、通常／Batch Face Swap 処理、入力不変、2 枚の番号付き grid の単独 PNG chunk を確認し、grid を目視した（`outputs/img2img-folder-grid-sd15/receipt.json`、`outputs/img2img-folder-grid-sdxl/receipt.json`）。

MCP の制限付きフォルダについて、Windows の open handle の最終パスを許可 root と照合し、その同一 handle から scan のハッシュ計算と staging を行う。外部 junction を open 前に差し替えた場合は拒否、open 後に差し替えた場合は元の handle から読む、未対応 OS・UNC・case が異なる canonical root は拒否、open 後および起動中の中断と復旧、追記・短縮の拒否を `VerifiedFolderInputSuite` 8 件で確認。runner 全体でも 2 画像間で親を外部 junction に差し替えて 2 件目が実行されないことを確認。helper と runner は計 14 件、MCP folder 3 件が通過。通常 NTFS のユーザー指定で case を変えた場合は MCP が `toRealPath` で canonical 化し、正常処理できることも確認。独立静的レビューでは新規 blocker は見つからず、mapped network drive の実環境、大規模フォルダ性能は未確認。2 入力の fake engine MCP テストは計 3.47 秒（モデル・テスト処理を含む）。

handle 固定方式を含む jar SHA-256 `199efafc48c90fe8c600094a9f7f4b03cf23264b37e938246e7b5bb194963c91` を公式 MCP SDK で試し、SD1.5・SDXL とも異なる 2 入力の通常／Batch Face Swap 処理、入力不変、2 枚の番号付き grid と元パス PNG chunk を確認した。画像と grid は両モデルとも目視済み（`outputs/img2img-folder-handle-sd15/receipt.json`、`outputs/img2img-folder-handle-sdxl/receipt.json`）。

queue は `generator.forJob` をバッチ全体で一度だけ作り、生成回数の制限、AlwaysOn、秘密情報、中断状態を共有する。選択された script と AlwaysOn は各入力で実行し、fake engine で複数生成と上限を確認。MCP は専用の非同期 tool を追加し、送信時と実行中に許可済みルートに収まることを検査する。中断時の部分結果は fake engine で確認済み、途中失敗は追加検証が必要。
