# State Manager V2 保存データ読み取り（作業中）

main へ部分統合時、JDK 21 の `StateManagerV2Suite` 3件、`GenerationHistorySuite` 13件、`RealNamedGenerationStatesCheck` と `ui/assembly` が exit 0。JAR SHA-256 は `5241ba4fee5dd25119b89662335322bcdb19d9b6e3e237a635724293f0139c35`。実モデルの試験は下記の分離 worktree の source-class GUI によるもので、main JAR や配布 EXE の実測ではない。上流の実物との互換性は未確認で #23 は open のまま。

対象は上流 MIT 版 SenshiSentou/sd-webui-state-manager 固定 revision `2af62ecd5287438bbc309f7e840d34aa5c8e3cb6`。上流 README、`statemanager.ts`、`scripts/api.py` を確認。共有 `history.txt` は gzip バイト列で、展開後は `defaults` と timestamp-keyed `entries` を持つ JSON。IndexedDB 内も圧縮された同じ構造だが、ブラウザからの抽出方法はまだ実装していない。各 entry の `componentSettings` は default との差分なので、参照する defaults の上に差分を重ねる。

`core/src/main/scala/sdfx/core/presets/StateManagerV2.scala` は read-only decoder。gzip および展開後データに上限を設け、既存の JSON parser により不正 UTF-8、重複キーと大きすぎる構造を拒否する。defaults が存在しない entry やバージョン 2.0 以外、未知の generation mode は拒否する。txt2img / img2img の prompt、sampling、寸法、seed、variation、checkpoint/VAE/clip skip の一部を厳密な数値表現で native key に対応させ、型が違うものや対応のないパスは `unmapped` へ記録する。元 entry と参照 defaults の JSON token は保持される。モデル名や sampler 名の同等性は実 catalog で検証する必要がある。

`outputs/state-v2-reader-red.log` は対象 class 不在を検出、`outputs/state-v2-reader-green2.log` は source 説明から作成した合成 gzip fixture の2テスト成功。defaults の上書き、整数精度、未対応パス保持、破損 gzip、欠損 defaults、未知 mode、重複 JSON、サイズ超過を試した。この fixture は実際の上流が出力したファイルではない。その時点では実データ・UI・MCP・実モデルは未検証だった。

## 2026-11-03 追加の試作

未完成ながら、History の chooser・確認画面・native history/favourites への一括保存を接続した。native record の source metadata に entry と参照 defaults を保持し、未知設定を確認画面で表示する。同一 entry と defaults の内容から導く識別子により再 import は重複しない。現在の保存先に追加した結果、履歴の50件上限を超えるときは全件を拒否する。未対応フィールドは保存時に表示するが native UI には適用されない。`outputs/state-v2-import-green.log` は decoder 2件、履歴12件、実 MainWindow で合成 gzip の未知項目表示・インポート・お気に入り prompt 復元が成功した記録。OS chooser はテスト用 callback で差し替えており、物理操作は未検証。

続く `outputs/state-v2-review-green.log` は decoder 3件、履歴12件、実 MainWindow の成功を記録する。キャンセル、同一入力の再取込み、容量上限超過時に既存 JSON の bytes が変わらないことを確認し、スクロール可能なプレビュー `outputs/state-v2-review-ui/state-manager-import-preview.png` を目視した。元 entry に名前がある場合は表示名に使い、衝突を避ける hash を付ける。元の名前はそのまま source metadata に残る。source の JSON 表現が変われば hash も変わるため、意味的に等しいが文字列表現が違うファイルの重複は防がない。

import record の「全復元」も実際には対応済み項目だけの復元である。全編集欄を確定すると未対応欄の不正 draft によって復元が失敗する問題を `outputs/state-v2-full-draft-red.log` で再現し、対象欄以外の draft を触らないように修正。`outputs/state-v2-final-green.log` では decoder 3件・履歴12件と実 MainWindow が成功し、関係のない不正 draft の保持も確認した。


最終コードの実 MainWindow と実エンジンで合成 gzip を読み、未知項目をプレビューして favourites へ保存、prompt・seed・寸法を復元後に画像を生成し、PNG metadata の prompt と seed を照合した。`outputs/state-v2-real.log` は SD1.5 `animekawa_v10` 512×512 と SDXL `waiIllustriousSDXL_v170` 832×832、各8 steps の成功を記録。結果は `outputs/state-v2-real-sd15/history-real-10671212999347841409/report.json` と `outputs/state-v2-real-sdxl/history-real-9949663998952430540/report.json`。画像は両方とも桜と赤い着物の人物と目視し、SHA-256 はそれぞれ `214aeb334ff1a06e3dd3fb85488eb666c0d0063455c1825b7cfa0d1d5f7f42cd` と `ea98780bea283320740110a305c1ca98378347ec95d4ea0fb56481cdb01a6e47`。検証は source-class GUI であり、配布 EXE や実際の上流出力ファイルの検証ではない。

実物 fixture を探し、上流固定 revision の tree と公式 v2.0 リリース ZIP `statemanager-2.0.zip` の内容を確認したが、history.txt や保存データ fixture は含まれていなかった。ZIP は GitHub release の公式 asset から取得し、SHA-256 は `48b5b519166994b78668beffa963a7c6caa11c7fc9290c023748bef1e55bc59a`。上流 issue #28 の [コメント](https://github.com/SenshiSentou/sd-webui-state-manager/issues/28#issuecomment-3030558145) は gzip JSON 形式を確認している。個人の画像や履歴を含むファイルを取得しておらず、現時点の fixture は自作の合成データのみ。実際の上流アプリが出力したファイルとの互換性は未検証。

追加の合成 fixture `core/src/test/resources/fixtures/state-manager-v2-webstreams.gz` は `scripts/dev/make_state_manager_fixture.mjs` が Node 22 の Web Streams `CompressionStream("gzip")` で生成する。上流と同じブラウザ API を使うが、内容は手作りの例で、上流アプリを実行した証拠ではない。SHA-256 `364bed76c185013e2c43d7d7d043a07bc0acd5298f2c4690f10d65313da64d6a`。名前・プロンプトを含め個人データは入れていない。

配布版、合成以外の上流データ、IndexedDB 抽出と import の境界条件・独立レビューがまだ残る。group のない entry と未知 group は拒否し、一括 import は全体が失敗する。未知設定の一覧は先頭40項目を表示し、残数を知らせる。source 原本を埋め込むため native JSON の容量制限によって import が拒否されることがある。source の preview 画像を native thumbnail としては取り込まない。上流との完全互換を主張しない。関連 issue #23 は open のまま。詳細な実施順は `docs/superpowers/plans/2026-11-03-state-import.md`。

独立レビューで、取込み履歴を末尾に追加すると、上限時に既存の新しい履歴が先に削除され得ることを確認。固定 revision の上流 `saveState` は `Date.now()` のミリ秒を作成キーに使うが、native 履歴は比較に使える正確な共通 timestamp を保持しない。新規取込み分は既存履歴の前に置いて古い扱いとし、表示・削除順と原本の metadata 複製、対応項目は entry ごとに異なる点を確認画面と日本語文書に明示した。修正前の回帰テストは期待どおり失敗し、修正後は decoder 3/3、履歴 13/13 と offline の実 MainWindow test が成功。`outputs/state-v2-order-gui/state-manager-import-preview.png` を目視した。異なる取込み間の正確な時系列は保証しない。

MCP `generation_history` に read-only の一覧と指定 ID の項目取得を追加。プロンプトは詳細でのみ、名前は一覧でも返されるため、名前・項目に個人情報がある場合はクライアントに渡る。画像や取込み原本は返さず、整数と小数は文字列で精度を保つ。`McpToolsSuite` 全 40 件が pass、追加境界を含む focused 1 件も pass。source-built jar と公式 MCP SDK による stdio と HTTP の試験では、合成 import 履歴の名前・詳細を確認し、画像と originalFields の sentinel が返らず、保存元の bytes が不変だった（`outputs/state-history-mcp-stdio-final/receipt.json`、`outputs/state-history-mcp-http-final2/receipt.json`）。HTTP 初回の成功表示が STDIO と誤表示され、修正後に再実行した。stdio 初回は home/python の欠如で engine 起動に失敗、再実行でも読み取り後の終了が非同期 engine 起動を中断した。履歴読み取りは成功したが engine 起動・生成や配布版の検証ではない。

main 統合後も `McpToolsSuite` 40件と `ui/assembly` は exit 0。JAR SHA-256 は `7402592bcc1375b71f72ce61fff774d17482b4422ba68600f64299462768253b`。上記の transport 実測は分離 worktree の source-built jar に対するもので、この JAR 自体ではない。

## 保存・安全性の境界

- 選択ファイルは読み取りのみで、最終パスに対する symlink を追わず、圧縮後と展開後をそれぞれ最大 4,194,304 bytes に制限する。JSON の重複キー・不正 UTF-8・過大な深さ／ノード数や、展開設定の総数超過は拒否する。モデルのダウンロードや読み込み、生成は import 自体では行わない。
- native の history store に確認後まとめて保存する。追加対象は mode ごとに history / favourites を分け、同一 ID は重複追加しない。既存履歴の上限を超えれば途中分を保存せず拒否する。保存は既存の revision 照合と cooperating process 間の history operation lock を使う。非協調ツールが JSON や lock file を変更する場合、ディレクトリ置換の競合は保証外。
- 各 entry の元 JSON とその参照 defaults 全体を `PresetSource.originalFields` に埋め込む。未知項目や元の preview のデータも metadata として残るため、元ファイルに個人情報や画像があれば保存先の `config/generation-history/library-v1.json` にも含まれる。実画像の export、未対応設定の自動再現、source JSON の完全な再出力 UI は提供していない。
- `generation.prompt` など事前に明示した key だけをマッピングし、未対応・型不一致は原本を保持して preview に列挙する。checkpoint / VAE / sampler 名が同じ意味であることは推測せず、復元時に現 catalog の preflight を通す。対応されない設定は適用されない。マッピングされた値でも個別のモデル・拡張での挙動は未検証。
- 代表的な合成 gzip と破損・制限・キャンセル・同一文字列表現の再 import、GUI の部分復元、SD1.5/SDXL の生成を確認した。実 A1111 が生成したファイル、OS chooser の物理操作、複数拡張の値、巨大な履歴を含む実データ、実 IndexedDB、配布 EXE GUI、MCP によるこの import は未検証。
