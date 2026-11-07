# #6 顔 pass 専用 checkpoint（着手中）

上流の無許諾リポジトリは README のみを参照。初回と顔 pass に異なるモデルを使えることを明記し、同系統への限定は書かれていない。既存モデルの `_free()` を直接呼んで切り替えると pipe・adapter・embedding 等を失うため、独立した顔用 Engine を一時的に使用する設計を検討する。

1. `BatchFaceSwapPlugin.scala` に checkpoint 選択を追加し、従来デフォルトは空（現在のモデル）とする。PNG と MCP はモデル名を記録し、engine への送信時のみカタログでパスを解決。存在しない選択肢を生成開始前に拒否し、ローカル任意パスを許可しない。
2. Python の face pass に独立 Engine の所有境界を設ける。必要に応じ主モデルを park し、同じ job の中断状態を使い、顔 Engine の load・後始末に関わらず元モデルの状態を守る。共有グローバルキャッシュを副 Engine が破棄しない。モデルの種類、VAE、LoRA、embedding、hooks、VRAM mode の違いを検討し、不正な組合せは先に明示拒否する。
3. TDD で override 未指定・同じモデル・別モデル、複数顔と複数画像、初期化失敗・中断・復帰、元 pipeline／LoRA／状態、独立後処理、設定と PNG replay を検証。無検出で不必要なモデルをロードしないことも検討する。
4. 公式 MCP SDK と実 GUI で SD1.5／SDXL、可能な異系統の組合せ、画像比較、失敗・中断後の既存モデルによる生成を確認し目視する。配布版と日本語説明を確認し、未完了条件を残したまま issue を閉じない。

実装途中。`batch_face_swap` の `checkpoint` は空が従来の挙動で、GUI/MCP はカタログ選択、worker 送信時は登録済み path に解決し、PNG には名前を残す。実際に顔を処理した画像にはロードされたモデルの AutoV2 10 桁 `Face checkpoint hash` を記録。ロード中にファイルが変わった場合は失敗させる。モデルが同じなら二重ロードを避け、無検出なら副 Engine を作らない。主 Engine と副 Engine はキャッシュ所有権を分け、前者の pipe を park した後で顔側の LoRA/embedding を準備し、成功・失敗・中断時も cleanup と復帰を試みる。cleanup/復帰の失敗が元の例外を覆わないことを単体試験した。

確認: Python `test_batch_face_swap.py` 36 件、Scala plugin 9 件、catalog 名→パスと未登録拒否 1 件、PNG→MCP inspect 名と hash 1 件が通過。下記に書いた範囲以外の実機・hook 組合せや配布版は未検証。現時点で完全対応や issue 完了としない。

実機の途中結果: Java 21 assembly jar SHA-256 `326cba087e15ee5ee06cd2f790c25a5efab6e0b05ccca19d6f7a682bcc464ca0` と worktree の Python engine を公式 MCP SDK から使用。SD1.5 主／SDXL 顔で顔画像が変化し、元モデルの前後画像は完全一致、PNG の名前・hash と MCP inspect を確認。さらに同じ組合せで PNG inspect の候補から再生成した画像が完全一致し、顔 pass `adetailer step 5/65` で中断後の元モデル画像も baseline と完全一致（`outputs/bfs-checkpoint-sd15-cancel/receipt.json`）。逆の SDXL 主／SD1.5 顔も画像差、元モデル復帰、PNG→MCP 再生成一致が通り、両組合せの画像を目視した（`outputs/bfs-checkpoint-sd15-sdxl/receipt.json`、`outputs/bfs-checkpoint-sdxl-sd15/receipt.json`）。この段落の試験時点では SDXL 主側の中断、ロード失敗後の実機回復、実 GUI は未確認で、次段落で追加検証した。`Face checkpoint hash` は info 欄に記録し inspect の `unapplied_fields` に残るが、replay 時の hash 一致検証はまだ実装していない。

その後、SDXL 主／SD1.5 顔でも `adetailer step 7/65` で中断し、破損した登録済み顔 checkpoint の load 失敗も確認した。両方とも主モデルの後続画像が baseline と完全一致（`outputs/bfs-checkpoint-sdxl-cancel-failure/receipt.json`）。実 MainWindow の img2img タブで `animekawa_v10` を主モデル、`waiIllustriousSDXL_v170` を顔モデルとして combo から選び、生成後の PNG 名・hash を確認して `FACE_CHECKPOINT_GUI_OK`。読み込み済み結果画面と選択欄の screenshot を目視した（`outputs/face-checkpoint-gui3/face-checkpoint-gui-4520041562328026966/`）。初回の GUI harness は別タブの同じ ID を拾って assertion に失敗し、対象タブ内で検索する修正後に通過した。GUI の実マウス／配布版、主 GPU mode と各 offload mode の両モデル、LoRA・その他 hook は残る。
