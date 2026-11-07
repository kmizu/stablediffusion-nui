# 顔だけ別モデルで描き直す（開発中）

「Batch Face Swap」を有効にし、「Overrides for the face pass」の「Face checkpoint」でモデルを選ぶ。上部で選んだモデルは元画像の生成に使い、ここで選んだモデルは検出された顔の描き直しに使う。空欄または None なら従来どおり上部のモデルを使う。モデルは設定済みの checkpoint フォルダに登録されているものから選ぶ。

顔用モデルは顔が検出されたときに読み込まれ、処理後に解放される。最初の読み込みには時間とメモリが必要。異なるモデル系統では、同じプロンプトや LoRA でも結果が変わる。画像の PNG には顔用モデル名と、実際に読み込んだファイルの SHA-256 先頭 10 桁を `Face checkpoint hash` として記録する。PNG 復元や MCP の `inspect_generation_metadata` はこの hash を期待値として引き継ぎ、顔の描き直しに使うモデルの hash が異なる場合はエラーにする。顔モデルを意図して変更する場合は「Expected face checkpoint hash」を空にする。古い PNG のように hash がない場合は照合しない。顔が検出されなければ顔用モデルの読み込みや照合は行わず、未使用モデルの hash は出力画像に記録しない。

顔用 checkpoint の読み込み失敗やキャンセル時には、もとのモデルへ戻す。指定した LoRA が顔用モデルに適用できない場合は、顔 pass を続けずエラーにする。実モデルでは SD1.5 と SDXL を両方向に組み合わせ、元モデルでの再生成、PNG の設定からの再生成、顔 pass 中のキャンセル後復帰を確認した。配布候補の staging image にある exe で実マウス操作と実生成を確認した。最終配布版での実モデル検証や、すべての LoRA・拡張の組み合わせは完了していない。

## 実測記録

2026-11-03、分離 worktree の候補 JAR（SHA-256 `326cba087e15ee5ee06cd2f790c25a5efab6e0b05ccca19d6f7a682bcc464ca0`）と Python を公式 MCP SDK から使用。SD1.5 と SDXL をそれぞれ元モデルとし、顔用にはもう一方を指定して `gpu`、`model_offload`、`sequential_offload` の 6 組を確認した。各試験は顔の変更、元モデルへの復帰、PNG 設定を使った再生成の RGB 一致を確認し、両系統の顔画像を目視した。計測は RTX 3090 の device 全体を 1 秒間隔で取得した値で、瞬間ピークの保証ではない。新規計測の `gpu` は SD1.5 7,500 MiB、SDXL 18,009 MiB、SD1.5 `sequential_offload` 7,482 MiB、SDXL `model_offload` 7,953 MiB。結果は `outputs/bfs-checkpoint-*` の各 `receipt.json` に保存。

顔 pass 中の実キャンセルと壊れた顔用モデルの読み込み後も元モデルでの画像が元と RGB 一致。別系統の SDXL LoRA を SD1.5 顔モデルに渡した場合は、実 MCP で LoRA 名を含むエラーを受け取り、以後の元モデル生成が RGB 一致した。実 GUI では SD1.5 元モデルと SDXL 顔モデルの選択、生成結果、PNG の記録を確認し、画面を保存・目視した。ただし controls はプログラムから操作しており、実マウス操作と配布版は未確認。

同じ SDXL 系の異なる checkpoint に LoRA と PAG を併用した実 MCP 試験でも顔の変更・replay・元モデルの復帰は成功したが、画像は顔の色と境界が大きく崩れた。処理と再現の検証であり、この組み合わせの画質を保証するものではない。関連 Python テスト 52 件は CUDA 無効で成功。

hash 照合追加後の候補 JAR SHA-256 は `d63605b4240eff901aeb4e67e390266996f746ce61bc092a256df250433bf8f2`。実 MCP で SD1.5 元モデル／SDXL 顔モデルと SDXL 元モデル／SD1.5 顔モデルの両方について、記録した hash による replay が RGB 一致し、不一致 hash の拒否後に元モデルでの出力が復帰した。後者はクリーン venv の MediaPipe 検出を使用した。`outputs/bfs-checkpoint-hash-sd15/receipt.json` と `outputs/bfs-checkpoint-hash-sdxl-clean/receipt.json`、両方の結果画像を目視。実 GUI では顔モデル変更後も古い hash を残す操作を再現し、エラーを確認した。画面は `outputs/face-checkpoint-gui-hash/face-checkpoint-gui-14522632786679199604/`。操作はプログラムから行った。

「Face area resolution」の 0 は元画像のサイズを使う。SDXL など高解像度を前提とする顔用モデルで顔が崩れるときは、1024 を試せる。使用メモリと処理時間は増えるため、環境に合わせて調整する。配布候補 exe の実マウス操作では 512×512・12 steps など同一条件で animagine-xl-3.1 を顔モデルに使うとブロック状の破綻が出たが、顔の解像度を 1024 にした実 MCP の再試験では崩れが消えた。すべてのモデルや設定に適用できるとは限らない。条件と比較画像の所在は `docs/batch-face-audit-ja.md` に記載。

画面の説明追加後、plugin 10 件と assembly は成功。新 JAR SHA-256 は `93f2b1d69a0de01bd3e51dc720d285bc60ad8cb08a8e667b467153e3c7c28dcf` で、実モデル試験に使った上記 JAR とは UI ラベルが異なる。この新 JAR での実モデル再試験は行っていない。
