# #6 MediaPipe 検出器の作業記録（未完成）

> この文書は初期の部分実装時の記録。後に顔用 checkpoint とフォルダ入力も実装されている。最新の範囲と配布候補の制約は [batch-face-audit-ja.md](batch-face-audit-ja.md) を参照。#6 は引き続き open。

main への部分統合時、`python/.venv-smoke/Scripts/python.exe -m pytest -q python/tests/test_mediapipe_face.py python/tests/test_batch_face_swap.py python/tests/test_adetailer.py` は CUDA 無効で41件成功。JDK 21 の `plugins/testOnly sdfx.plugins.BatchFaceSwapSuite` 8件と `ui/assembly` も成功し、JAR SHA-256 は `b7b3c1798315e06d6f5cf05a5364e8dbafb9f76cdbd3f932e4cd30dc11880dac`。以下の実モデル実測は分離 worktree の候補 JAR・Python によるもので、main の JAR や配布版 GUI による実測ではない。#6 は open のまま。

上流 `kex0/batch-face-swap` の README は mediapipe を依存に挙げる。元拡張は無許諾のためコードを読まず、Google が公開する MediaPipe Tasks の顔検出 API から `mediapipe_face` を再実装した。既存の YOLO や OpenCV は変更しない。選択すると BlazeFace short-range v1 の 229,746 バイトのモデルを Google Storage から初回取得し、SHA-256 `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f` を確認する。モデルカードは Apache-2.0、MediaPipe コードも Apache-2.0。モデルは同梱しない。`SDFX_MEDIAPIPE_DIR` でキャッシュ先を変更できる。`HF_HUB_OFFLINE=1` または `SDFX_MEDIAPIPE_OFFLINE=1` では未取得のモデルをダウンロードしない。不正なキャッシュファイルは黙って上書きせず拒否する。

`mediapipe==0.10.21` は numpy<2 を要求し、既存 numpy 2.2.5 の環境とは衝突する。試験は `mediapipe==1.2.0` の wheel を `outputs/mediapipe-deps` に `--no-deps` で隔離展開し、既存 smoke Python の NumPy 2 から import して実施した。本体 venv のパッケージは変更していない。これだけではクリーン環境での依存解決・インストール方法を確認できたことにはならない。公式モデルは小さな顔・アニメ顔では検出できないことがある。

- 単体テスト: `test_mediapipe_face.py`, `test_batch_face_swap.py`, `test_adetailer.py` 合計41件 pass。偽 detector を使う bounds や検出なし、検証済みモデルのキャッシュ、サイズ/SHA 不一致の拒否、offline 時の非取得、同時作成されたキャッシュの非上書き、パッケージがない場合の engine エラーを含む。
- Scala `BatchFaceSwapSuite` 8件 pass。選択・infotext 復元を含む。JAR assembly exit 0、SHA-256 `4942dfcd3b1d3f2126bb4f054d4f5ac47ceeefc9f246a9bdfe0817209b50f4e7`。
- 実 MediaPipe 1.2.0 と実 CPU 推論で SDXL 生成済み画像1枚から2顔候補を検出。SD1.5 生成画像の小さな顔1枚は検出されなかった。この初期試験では生成経路・GUI・MCP は未検証だった（後続試験は下記）。
- `scripts/third_party.py` 初回は GitHub API のエラーで失敗し、対象 URL の単独再試行は成功。全体再実行は exit 0、239 projects。既存 LICENSE の不要な改行・内容変動は戻した。
- 公式 MCP SDK、候補 JAR と分離した依存を使う初期の SD1.5 `animekawa_v10` と SDXL `waiIllustriousSDXL_v170` 実モデル検証で検出・顔の描き直し・PNG 内の detector 復元に成功。`outputs/mediapipe-final-sd15/receipt.json`, `outputs/mediapipe-final-sdxl/receipt.json`、両方の前後画像を目視。その後モデル読込を検証済み bytes に変え、下記のとおり両モデルを再検証した。完全に目が閉じることや全画像での効果は保証しない。更新後は空キャッシュへの実ダウンロード・hardlink 公開とモデル SHA 照合、および実 MediaPipe 1.2.0 での SDXL 画像の CPU 顔検出にも成功。
- キャッシュの公開は同一ディレクトリ内の temporary file から hardlink で行い、同時作成された既存ファイルは上書きせず SHA 検証する。hardlink をサポートしないファイルシステムでは取得できない。検証後の path 差替えを防ぐため MediaPipe には検証済み bytes を渡す。
- 最新コードの SD1.5 MCP 再試験 `outputs/mediapipe-buffer-sd15/receipt.json`: PNG 設定で再生成した画像は RGB 完全一致、80 steps 中のキャンセル後、通常生成は baseline と RGB 完全一致。変更後の顔も目視。キャンセルは通常の生成中に行い、検出器の推論中に行ったわけではない。
- 最新コードの SDXL MCP 再試験 `outputs/mediapipe-buffer-sdxl/receipt.json`: 顔の描き直しと PNG detector 設定の復元に成功し、前後の画像を目視。`closed eyes` の指示では目が細くなったが完全には閉じなかった。RGB 完全一致の replay とキャンセルの検証は SD1.5 のみ。
- 最新コードの実 SD1.5 GUI `outputs/mediapipe-gui-sd15/mediapipe-gui-3399704454153186179/receipt.json`: checkbox と combo の programmatic 操作、Generate、PNG の detector 記録を確認。`controls.png` と生成画像も目視。同条件の GUI baseline との比較はしていないため、GUI 上の顔 pass による差分は未検証。実マウス操作は行っていない。
- 最新コードの SDXL GUI、依存がない場合の実アプリでのエラー、実クリック、配布版 JAR 等はまだ検証が必要。顔だけ別 checkpoint と img2img フォルダ入力が未実装で #6 は open のまま。

Google の[顔検出 API](https://developers.google.com/edge/mediapipe/solutions/vision/face_detector/python)、[モデルカード](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf)。
