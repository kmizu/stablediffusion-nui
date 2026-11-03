# State Manager V2 保存データ読み取り（作業中）

## MCP 履歴検索（2026-11-03）

main 統合時も MCP 44件・履歴20件・`ui/assembly` が exit 0。生成 JAR の SHA-256 は `23b28fc8bb8d8414321f056632b9f5fe1ea19bdb91e2a96a995f47f9c0eb1102` で、下記 SDK transport 試験に使用した JAR と一致した。

`generation_history` の一覧に `query`・`offset`・`limit` を追加し、GUI と共有した名前／prompt／negative prompt／checkpoint／sampler のカンマ区切り AND 検索を行う。既定 limit 1000、total と next_offset を返す。ページ間の履歴変更は snapshot されない。list は従来どおり名前と ID だけだが、検索一致や件数から保存値を推測できるため、その旨をツール説明と日本語文書に記載。get で list 専用引数を拒否し、元 JSON と画像は返さない。

未実装時の RED は query 不明で失敗。変更後の `McpToolsSuite` 44/44、GUI 側 `GenerationHistorySuite` 20/20、source-built `ui/assembly` が成功。JAR SHA-256 `23b28fc8bb8d8414321f056632b9f5fe1ea19bdb91e2a96a995f47f9c0eb1102`。上流 JS fixture を GUI で取り込んだ library に合成レコードを追加し、公式 MCP SDK で stdio と HTTP の両方から 2 ページの検索、AND、0 件、詳細と library bytes 不変を確認（`outputs/search-pages-stdio/receipt.json`、`outputs/search-pages-http/receipt.json`）。stdio は読取り完了後の終了で非同期 engine 起動が中断されている。配布版・ページ間の実際の同時更新・実 A1111 データは未検証。

## 上流 JS を用いた再現 fixture（2026-11-03）

main 統合時も `StateManagerV2Suite` 4件と `RealNamedGenerationStatesCheck`、`ui/assembly` が exit 0。JAR は `91c567ad2b0e1331c3590ae39082d7db261b5056e59207788091c5ff0a6e2c95` と一致。上流の周辺環境は模擬されている。

MIT ライセンスの [上流配布 JavaScript](https://github.com/SenshiSentou/sd-webui-state-manager/blob/2af62ecd5287438bbc309f7e840d34aa5c8e3cb6/javascript/statemanager.js) を固定 revision `2af62ecd5287438bbc309f7e840d34aa5c8e3cb6` から取得し、元 bytes の SHA-256 `e128d7f6d4c9372aa3a01ff8a8e10ac0175fa5710b393ef6b9a729815a01c080` を照合後、改変せず Node 22 VM 内で実行した。`scripts/dev/run_state_manager_upstream_fixture.mjs <upstream.js> <output.gz>` はブラウザの外側と A1111 の component/defaults/quicksettings/storage API を模擬し、上流の `initMemoryStorage` → `getCurrentState` → `saveState` → `updateFileStorage` を通して記録を生成する。上流 JS 原版は repo に同梱せず、上流ライセンスは既存の `licenses/SenshiSentou__sd-webui-state-manager.txt` で記録する。

生成した `core/src/test/resources/fixtures/state-manager-v2-upstream-stubbed.gz` は SHA-256 `24889f722533efb0fb475e5409e8f9cd1b5e9e3a05c9f8bd8a3d24f2fdb5b58a`、2 回生成で一致。個人データを含まない。実上流コードの保存出力だが、値・API・ブラウザ周辺は模擬しており、実 A1111 が稼働して保存したファイルではない。decoder suite 4/4 は prompt の差分、defaults、negative prompt、steps、seed、CLIP skip、未知項目を確認。source-class 実 MainWindow の import と prompt 復元も成功し、`outputs/state-upstream-js-gui/upstream-stubbed-fixture-import.png` を目視した。

この GUI が保存した native library を公式 MCP Python SDK から既存 root jar（SHA-256 `91c567ad2b0e1331c3590ae39082d7db261b5056e59207788091c5ff0a6e2c95`）に渡し、stdio と HTTP で一覧・選択 ID 詳細を確認した（`outputs/upstream-fixture-mcp-stdio/receipt.json`、`outputs/upstream-fixture-mcp-http/receipt.json`）。prompt、seed、CLIP skip が一致、一覧に prompt を含まず、詳細に上流原本を含まず、library bytes は不変。stdio は読取り後の終了により非同期 engine 起動が中断された。これは read-only MCP の試験であり engine 起動・画像生成、配布 EXE、実 A1111・IndexedDB、任意の上流 UI 設定との完全互換を示さない。

main へ部分統合時、JDK 21 の `StateManagerV2Suite` 3件、`GenerationHistorySuite` 13件、`RealNamedGenerationStatesCheck` と `ui/assembly` が exit 0。JAR SHA-256 は `5241ba4fee5dd25119b89662335322bcdb19d9b6e3e237a635724293f0139c35`。実モデルの試験は下記の分離 worktree の source-class GUI によるもので、main JAR や配布 EXE の実測ではない。上流の実物との互換性は未確認で #23 は open のまま。

対象は上流 MIT 版 SenshiSentou/sd-webui-state-manager 固定 revision `2af62ecd5287438bbc309f7e840d34aa5c8e3cb6`。上流 README、`statemanager.ts`、`scripts/api.py` を確認。共有 `history.txt` は gzip バイト列で、展開後は `defaults` と timestamp-keyed `entries` を持つ JSON。IndexedDB 内も圧縮された同じ構造だが、ブラウザからの抽出方法はまだ実装していない。各 entry の `componentSettings` は default との差分なので、参照する defaults の上に差分を重ねる。

`core/src/main/scala/sdfx/core/presets/StateManagerV2.scala` は read-only decoder。gzip および展開後データに上限を設け、既存の JSON parser により不正 UTF-8、重複キーと大きすぎる構造を拒否する。defaults が存在しない entry やバージョン 2.0 以外、未知の generation mode は拒否する。txt2img / img2img の prompt、sampling、寸法、seed、variation、checkpoint/VAE/clip skip の一部を厳密な数値表現で native key に対応させ、型が違うものや対応のないパスは `unmapped` へ記録する。元 entry と参照 defaults の JSON token は保持される。モデル名や sampler 名の同等性は実 catalog で検証する必要がある。

`outputs/state-v2-reader-red.log` は対象 class 不在を検出、`outputs/state-v2-reader-green2.log` は source 説明から作成した合成 gzip fixture の2テスト成功。defaults の上書き、整数精度、未対応パス保持、破損 gzip、欠損 defaults、未知 mode、重複 JSON、サイズ超過を試した。この fixture は実際の上流が出力したファイルではない。その時点では実データ・UI・MCP・実モデルは未検証だった。

## 2026-11-03 追加の試作

未完成ながら、History の chooser・確認画面・native history/favourites への一括保存を接続した。native record の source metadata に entry と参照 defaults を保持し、未知設定を確認画面で表示する。同一 entry と defaults の内容から導く識別子により再 import は重複しない。現在の保存先に追加した結果、履歴の50件上限を超えるときは全件を拒否する。未対応フィールドは保存時に表示するが native UI には適用されない。`outputs/state-v2-import-green.log` は decoder 2件、履歴12件、実 MainWindow で合成 gzip の未知項目表示・インポート・お気に入り prompt 復元が成功した記録。OS chooser はテスト用 callback で差し替えており、物理操作は未検証。

続く `outputs/state-v2-review-green.log` は decoder 3件、履歴12件、実 MainWindow の成功を記録する。キャンセル、同一入力の再取込み、容量上限超過時に既存 JSON の bytes が変わらないことを確認し、スクロール可能なプレビュー `outputs/state-v2-review-ui/state-manager-import-preview.png` を目視した。元 entry に名前がある場合は表示名に使い、衝突を避ける hash を付ける。元の名前はそのまま source metadata に残る。source の JSON 表現が変われば hash も変わるため、意味的に等しいが文字列表現が違うファイルの重複は防がない。

import record の「全復元」も実際には対応済み項目だけの復元である。全編集欄を確定すると未対応欄の不正 draft によって復元が失敗する問題を `outputs/state-v2-full-draft-red.log` で再現し、対象欄以外の draft を触らないように修正。`outputs/state-v2-final-green.log` では decoder 3件・履歴12件と実 MainWindow が成功し、関係のない不正 draft の保持も確認した。


最終コードの実 MainWindow と実エンジンで合成 gzip を読み、未知項目をプレビューして favourites へ保存、prompt・seed・寸法を復元後に画像を生成し、PNG metadata の prompt と seed を照合した。`outputs/state-v2-real.log` は SD1.5 `animekawa_v10` 512×512 と SDXL `waiIllustriousSDXL_v170` 832×832、各8 steps の成功を記録。結果は `outputs/state-v2-real-sd15/history-real-10671212999347841409/report.json` と `outputs/state-v2-real-sdxl/history-real-9949663998952430540/report.json`。画像は両方とも桜と赤い着物の人物と目視し、SHA-256 はそれぞれ `214aeb334ff1a06e3dd3fb85488eb666c0d0063455c1825b7cfa0d1d5f7f42cd` と `ea98780bea283320740110a305c1ca98378347ec95d4ea0fb56481cdb01a6e47`。検証は source-class GUI であり、配布 EXE や実際の上流出力ファイルの検証ではない。

実物 fixture を探し、上流固定 revision の tree と公式 v2.0 リリース ZIP `statemanager-2.0.zip` の内容を確認したが、history.txt や保存データ fixture は含まれていなかった。ZIP は GitHub release の公式 asset から取得し、SHA-256 は `48b5b519166994b78668beffa963a7c6caa11c7fc9290c023748bef1e55bc59a`。上流 issue #28 の [コメント](https://github.com/SenshiSentou/sd-webui-state-manager/issues/28#issuecomment-3030558145) は gzip JSON 形式を確認している。個人の画像や履歴を含むファイルは取得していない。後に上記の模擬環境で上流 JS が出力した fixture を追加したが、実際に A1111 と上流拡張を動かして保存したファイルとの互換性は未検証。

追加の合成 fixture `core/src/test/resources/fixtures/state-manager-v2-webstreams.gz` は `scripts/dev/make_state_manager_fixture.mjs` が Node 22 の Web Streams `CompressionStream("gzip")` で生成する。上流と同じブラウザ API を使うが、内容は手作りの例で、上流アプリを実行した証拠ではない。SHA-256 `364bed76c185013e2c43d7d7d043a07bc0acd5298f2c4690f10d65313da64d6a`。名前・プロンプトを含め個人データは入れていない。

配布版、合成以外の上流データ、IndexedDB 抽出と import の境界条件・独立レビューがまだ残る。group のない entry と未知 group は拒否し、一括 import は全体が失敗する。未知設定の一覧は先頭40項目を表示し、残数を知らせる。source 原本を埋め込むため native JSON の容量制限によって import が拒否されることがある。上流との完全互換を主張しない。関連 issue #23 は open のまま。詳細な実施順は `docs/superpowers/plans/2026-11-03-state-import.md`。

上流の `createPreviewImageData` は canvas に最大辺 100 px で描き、通常 PNG data URI を作る。これを native thumbnail に取り込む処理を追加。埋め込み PNG、decoded 256 KiB 以下、各辺 100 px 以下に限定し、寸法を読んでから decode し metadata を除いた PNG を保存する。外部 URL は取得せず、既存 thumbnail を上書きしない。JSON 保存後に thumbnail 保存を試し、非対応や失敗を別件数で知らせるため、画像のみ失敗した場合も履歴は残る。協調プロセス間ロックと既存の junction チェックを使うが非協調者による交換競合は保証しない。合成データで `GenerationHistorySuite` 18/18 と source-class の実 MainWindow が成功し、`outputs/import-thumbnails-final/state-manager-import-thumbnail.png` の赤いプレビューを目視した。初回の test は callback 内 assertion の例外が隠れて timeout し、結果を callback 外で検証する形に修正した後 focused と全件が成功。実上流生成ファイル、配布版、実 HTTP 接続遮断試験や保存中の非協調ディレクトリ交換は未検証。

main への部分統合時も履歴18件・`RealNamedGenerationStatesCheck`・`ui/assembly` が exit 0。JAR SHA-256 は `8f85e9d42c534ddbbb641d0b6139c4c9978b7ea3e3ec6c692b8fa4dcda4b916e`。下記の実モデル試験は分離 worktree の source-class GUI によるもので、main JAR や配布版の実測ではない。

最終版は `GenerationHistorySuite` 18/18 と source-class 実 MainWindow による SD1.5／SDXL 両モデルで成功。生成画像から作った thumbnail を合成 gzip に埋めて取り込み、画像の保存数、favourites の表示、prompt・seed 復元と PNG metadata を確認した（`outputs/import-preview-real-sd15/history-real-2864515455241078903`、`outputs/import-preview-real-sdxl/history-real-3862274142710093619`）。それぞれ `03-imported-thumbnail.png` の一覧画像と、生成した `00007-7109.png` を目視し、桜と赤い着物の人物を確認。生成画像 SHA-256 は順に `214aeb334ff1a06e3dd3fb85488eb666c0d0063455c1825b7cfa0d1d5f7f42cd` と `ea98780bea283320740110a305c1ca98378347ec95d4ea0fb56481cdb01a6e47`。

独立レビューで、取込み履歴を末尾に追加すると、上限時に既存の新しい履歴が先に削除され得ることを確認。固定 revision の上流 `saveState` は `Date.now()` のミリ秒を作成キーに使うが、native 履歴は比較に使える正確な共通 timestamp を保持しない。新規取込み分は既存履歴の前に置いて古い扱いとし、表示・削除順と原本の metadata 複製、対応項目は entry ごとに異なる点を確認画面と日本語文書に明示した。修正前の回帰テストは期待どおり失敗し、修正後は decoder 3/3、履歴 13/13 と offline の実 MainWindow test が成功。`outputs/state-v2-order-gui/state-manager-import-preview.png` を目視した。異なる取込み間の正確な時系列は保証しない。

MCP `generation_history` に read-only の一覧と指定 ID の項目取得を追加。プロンプトは詳細でのみ、名前は一覧でも返されるため、名前・項目に個人情報がある場合はクライアントに渡る。画像や取込み原本は返さず、整数と小数は文字列で精度を保つ。`McpToolsSuite` 全 40 件が pass、追加境界を含む focused 1 件も pass。source-built jar と公式 MCP SDK による stdio と HTTP の試験では、合成 import 履歴の名前・詳細を確認し、画像と originalFields の sentinel が返らず、保存元の bytes が不変だった（`outputs/state-history-mcp-stdio-final/receipt.json`、`outputs/state-history-mcp-http-final2/receipt.json`）。HTTP 初回の成功表示が STDIO と誤表示され、修正後に再実行した。stdio 初回は home/python の欠如で engine 起動に失敗、再実行でも読み取り後の終了が非同期 engine 起動を中断した。履歴読み取りは成功したが engine 起動・生成や配布版の検証ではない。

main 統合後も `McpToolsSuite` 40件と `ui/assembly` は exit 0。JAR SHA-256 は `7402592bcc1375b71f72ce61fff774d17482b4422ba68600f64299462768253b`。上記の transport 実測は分離 worktree の source-built jar に対するもので、この JAR 自体ではない。

## 保存・安全性の境界

- 選択ファイルは読み取りのみで、最終パスに対する symlink を追わず、圧縮後と展開後をそれぞれ最大 4,194,304 bytes に制限する。JSON の重複キー・不正 UTF-8・過大な深さ／ノード数や、展開設定の総数超過は拒否する。モデルのダウンロードや読み込み、生成は import 自体では行わない。
- native の history store に確認後まとめて保存する。追加対象は mode ごとに history / favourites を分け、同一 ID は重複追加しない。既存履歴の上限を超えれば途中分を保存せず拒否する。保存は既存の revision 照合と cooperating process 間の history operation lock を使う。非協調ツールが JSON や lock file を変更する場合、ディレクトリ置換の競合は保証外。
- 各 entry の元 JSON とその参照 defaults 全体を `PresetSource.originalFields` に埋め込む。未知項目や元の preview のデータも metadata として残るため、元ファイルに個人情報や画像があれば保存先の `config/generation-history/library-v1.json` にも含まれる。実画像の export、未対応設定の自動再現、source JSON の完全な再出力 UI は提供していない。
- `generation.prompt` など事前に明示した key だけをマッピングし、未対応・型不一致は原本を保持して preview に列挙する。checkpoint / VAE / sampler 名が同じ意味であることは推測せず、復元時に現 catalog の preflight を通す。対応されない設定は適用されない。マッピングされた値でも個別のモデル・拡張での挙動は未検証。
- 代表的な合成 gzip と破損・制限・キャンセル・同一文字列表現の再 import、GUI の部分復元、SD1.5/SDXL の生成を確認した。実 A1111 が生成したファイル、OS chooser の物理操作、複数拡張の値、巨大な履歴を含む実データ、実 IndexedDB、配布 EXE GUI、MCP によるこの import は未検証。
