# Batch Face Swap 対応範囲の確認（#6、作業中）

確認した上流は [README の固定版 942ebae](https://github.com/kex0/batch-face-swap/blob/942ebae6937d9ad7964f285a790e7721f712b9ec/README.md)。ライセンスが見つからないため、上流コードを読まず README で公開された動作を再実装した。README が明示する機能は、顔検出と描き直し、txt2img、img2img の単一画像とフォルダ、顔 pass だけの prompt と checkpoint の上書き。元拡張の内部の全オプションや画素単位の互換性は主張しない。

検出と顔 pass は AlwaysOn プラグイン、フォルダ入力は img2img ジョブに実装した。MediaPipe、YOLO、OpenCV の選択を提供し、顔用モデルを指定しなければ元モデルを使う。フォルダ入力、MediaPipe、顔用 checkpoint それぞれの実測と制約は `docs/superpowers/plans/2026-11-03-batch-face-folder.md`、`docs/batch-face-mediapipe-wip.md`、`docs/batch-face-checkpoint-ja.md` を参照。

フォルダ入力と顔用別 checkpoint の結合を、main の source-built JAR `67f74674f9f15e14f761a18306b6b87f2b57f46a48022c7d4fef9eb4ba64cb3e` と公式 MCP SDK で実測した。SD1.5 `animekawa_v10` の元画像を SDXL `waiIllustriousSDXL_v170` で顔処理、逆の組合せでもそれぞれ異なる2入力を処理し、各画像の顔モデル hash、元モデルへの復帰時に baseline と RGB が一致することを確認。4枚の結果画像を目視した。結果は `outputs/face-folder-checkpoint-sd15-final/receipt.json` と `outputs/face-folder-checkpoint-sdxl-final/receipt.json`。処理中、分離コピーの第2入力を変更すると、両系統とも2枚目は変更を検出して失敗し、1枚目は出力先に残った。MCP の failed job は `images: []` であり、その先行ファイルを結果として返さない。入力を戻して再試行すると2枚を新たに生成するため、利用者は再試行前に出力先を確認する必要がある。

最初の注入試験は manifest 固定前に入力を変更して画像 decode で失敗し、harness も failed job の `isError` を成功と誤認して停止した。1枚目の生成 step を確認した後に変更し、failed を検査するよう修正して両系統を再実施した。SDXL の native folder GUI、最終 ZIP を展開した配布版の実モデル生成、さまざまな途中失敗や大規模フォルダは未確認。

ライセンスについては `scripts/third_party.py` と同梱の notices に記録。MediaPipe Tasks と BlazeFace は Apache-2.0、YOLO 実行用の ultralytics は AGPL-3.0 で任意導入。モデルは初回に別途取得し、再配布しない。ユーザーが用意する生成モデル・LoRA の利用条件は各配布元による。

2026-11-03 に新規 Python 3.10 venv で `requirements-torch.txt` の PyTorch 2.7.1+cu128、`requirements.txt` の固定依存、MediaPipe 1.2.0 を通常インストールし、`pip check` と SDXL 画像の CPU 顔検出が成功。pip 23.0.1 のまま PyTorch 専用 index を使うと `flit_core` の取得に失敗し、PyPI を追加 index にするとインストールできた。アプリの bootstrap は先に pip を更新する。別の新規 venv を pip 26.3 に更新し PyTorch 専用 index だけで dry-run したところ、依存解決は成功したが実インストールはしていない。インストール後は OpenCV headless 4.11.0 と opencv-contrib 5.0.0.93 の両 distribution が入っているため、同じ `cv2` 名前空間のパッケージ共存には注意が必要（実行時の cv2 は 4.11.0）。この環境で SDXL 元モデル・SD1.5 顔モデルを公式 MCP SDK から実生成し、PNG 復元、hash 不一致の拒否と元モデルへの復帰まで確認した。テストした組み合わせ以外の依存環境やモデルでの動作を保証するものではない。

配布候補 `outputs/face-checkpoint-package-final/build-0a7b5949f3b040ba9d71cad396039c7b/SDNUI-v0.1-windows-x64.zip` の SHA-256 は `1d32e4a44b96eee762b6ef905115b1af7e0b45c6901c1ca170d48e284fcab3e0`。同じ staging image の exe を起動し、実マウスで Batch Face Swap を有効化して SD1.5 元モデルと SDXL 顔モデルを選び、txt2img の結果と顔モデル hash を PNG で確認した。起動時の一度目には `Failed to launch JVM` のダイアログが出たが、実行コマンドの `--home` 引数の引用符を誤って別要素に分割していた。長い staging パスでは package root を作業ディレクトリにして正しい引数を指定した場合と引数を省略した場合、短いパスでは package root を作業ディレクトリにして引数を省略した場合の GUI 起動を確認。初回エラーの原因は断定できない。

解像度の説明を追加した次の候補 `outputs/face-checkpoint-package-notice/build-691125e851be44479fdf2ba4f5217357/SDNUI-v0.1-windows-x64.zip` は SHA-256 `93226026f1a1476548d8ad2c914ced50abf603eabd0c3d8d8e21daab16febda0`。長い staging パスで、リポジトリを作業ディレクトリとして正しい `--home` を指定した場合、および省略した場合のどちらも dry-run GUI の起動に成功した。日本語説明と監査文書の同梱を確認し、新しい UI の解像度案内が見切れず表示されることを目視した（`outputs/face-checkpoint-package-notice/face-resolution-notice.jpg`）。この候補の実モデル生成は未実施で、実モデルは前段の JAR で確認した。

配布 exe の SD1.5 `animekawa_v10`、seed 2360088032、512×512、DPM++ 2M / Karras、12 steps、顔 denoise 0.5 では、顔処理なしの元画像と `waiIllustriousSDXL_v170` 顔 pass の画像は目視上正常だった一方、`animagine-xl-3.1` 顔 pass は顔に大きなブロック状の破綻が生じた。画像は staging image の `outputs/txt2img/2026-11-03/00000-2360088032.png`（animagine）、`00001-2360088032.png`（顔処理なし）、`00002-2360088032.png`（wai）にあり、最初の画面は `outputs/face-checkpoint-package-final/package-real-face.jpg`。

同じ元画像を animagine の単独 img2img に入れ、seed・sampler・steps・CFG・denoise を合わせた 512×512 でも画像全体に同様の破綻が生じた。1024×1024 では目視で正常になった。さらに元画像 512×512 の txt2img で animagine の顔 pass 解像度だけ 1024 にすると、顔の破綻は消えた（`outputs/bfs-quality-animagine-standalone-exact/receipt.json`、`outputs/bfs-quality-animagine-1024/receipt.json`、`outputs/bfs-quality-animagine-face-1024/receipt.json`）。animagine の safetensors metadata は 1024×1024 を記載する。少なくともこの条件では解像度を上げることで回避できるが、精度や他の設定が影響しないとまでは証明していない。既定の顔解像度 0 は元画像サイズを使用するため、UI と利用文書に SDXL で 1024 を試す案内を追加した。
