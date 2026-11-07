# #24 タグ Wiki リンクの作業途中

これは #24 の部分実装と検証の記録。`codex/tag24-wiki` は main `131d9a8` が基点で、変更を main に取り込んだ。issue #24 は open のまま。

上流 MIT の `4170882f90b47be130a0ff9314f663c230b9153d` の README と `javascript/tagAutocomplete.js` を参照。既定 off、対応 CSV 名は danbooru/e621 の先頭一致、merged はカテゴリ 6 以上で e621、それ以外は Danbooru。実装は固定 HTTPS ホストに正規タグ名をエンコードし、リンククリックと F1 で開く構成。成人向け画像がリンク先にあることを設定・日本語ガイドに明示。別名や翻訳の表示を使っても正規タグ名を参照する。

RED: 実装前の `ui/testOnly sdfx.ui.TagWikiSuite` は追加シンボルが存在せずコンパイル失敗。F1 と dot segment 拒否追加後の `ui/testOnly sdfx.ui.TagWikiSuite sdfx.ui.CompletionEngineSuite` は 18 件成功（2026-11-03）。

`NativeTagWikiCheck` は最初の実行で popup 表示前の assertion に失敗。後の実行は exit 0 と報告されたが成功マーカー・画像ともに存在せず、合格ではない。診断ファイルは `outputs/tag24-wiki-ui3/phase.txt` が `first callback`、ui4 が `queued second callback` で止まっていた。callback の例外捕捉と更なる phase を追加した ui6 では `NATIVE_TAG_WIKI_OK` と exit 0 を得て、`outputs/tag24-wiki-ui6/tag-wiki-popup.png` を目視した。それ以前の終了理由は未特定で、独立の出力先で再現性を確認すること。対照の既存 `NativeTagCategoryCheck` は同じ環境で成功。

`NativeTagWikiMouseCheck` では JavaFX Robot により実マウス位置で押下・解放して `NATIVE_TAG_WIKI_MOUSE_OK`、exit 0。リンクの URL をブラウザ起動の代わりに記録する callback に差し替え、正規 URL、入力欄の内容・フォーカス、popup の継続を確認した。これは OS ブラウザ起動の実測ではない。複数行やスクロール後の操作と browser の起動失敗表示は未確認。Wiki が無効のとき、非対応候補、別名、キーボード F1、既存候補の通常選択もさらに検証する。写真の入った wiki サイトにアクセスする作業は実際にリンクを押す場合だけ行う。

実 MainWindow において Tags ダイアログから Wiki を有効化して候補を表示し、その後通常の補完と生成を行う `RealTagAutocompleteExtrasCheck` を、実 CUDA engine と SD1.5 `animekawa_v10`、SDXL `waiIllustriousSDXL_v170` で実行した。各 40 項目通過し、PNG のプロンプトも照合した。実行物はそれぞれ `outputs/tag24-wiki-real-sd15/tac-extras-385851684106103612` と `outputs/tag24-wiki-real-sdxl/tac-extras-6144676955427463864`。両方の生成画像と Wiki の `?` を含む popup を目視した。SD1.5 は青髪の人物、SDXL は黒赤の強い網目が人物を覆っている。これは 8 step の既存テストで、Wiki 有効化による画質比較ではない。ポップアップの実クリックは wildcard 行について行ったが、Wiki の `?` 自体をクリックして外部ブラウザを開く試験ではない。

main 統合時に JDK 21 の `ui/testOnly sdfx.ui.TagWikiSuite sdfx.ui.CompletionEngineSuite` 18件、`ui/Test/runMain sdfx.ui.NativeTagWikiCheck outputs/tag24-wiki-main`、`ui/Test/runMain sdfx.ui.NativeTagWikiMouseCheck outputs/tag24-wiki-mouse-main`、`ui/assembly` が exit 0。両 GUI 成功マーカーを確認。JAR SHA-256 は `ffb3102bc5d5d4204b807cfd2defc25f7126c07ebdd3bf3bfa148f8b4502f3b8`。実モデル画像は分離 worktree の実測で、main の JAR での実モデル再検証ではない。実ブラウザ、スクロール後セルと配布版 GUI は未検証。GPU 使用時は共有ロックを取得して調整する。
