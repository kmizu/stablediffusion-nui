# #62 Quick Mask 部分実装の方針

作者の公開ガイドは、入力画像の白・黒・任意色から threshold で選択領域を作り、反転して inpaint に使えると説明している。threshold 1 は完全一致、数値を増やすと近い色も含む。色距離の式は公開説明にないため、独立実装では RGB 各成分の最大差が threshold - 1 以下なら白とし、他は黒とする。これは上流の画素単位の完全一致を保証しない。実行時は透明画素を含む入力を拒否する。

まず生成時のカラー mask と反転を追加する。Default は元の mask と設定を保持し、反転を明示した場合は既存 mask の反転設定を切り替える。None phase でも明示した mask は適用する。元の画像を保護付き access で読み、生成の間だけ temp PNG を保持する。image protection が有効な session では、この派生 mask を保護付き一時画像として書き出し、既存の worker 入力復号を使う。ホストが保護書込みを実装していなければ拒否する。カラー mask を画像として返す部分を追加する。既存 mask の出力、FX 限定、FX・overlay は別途未実装として残す。入力と threshold の検証・失敗時の cleanup・以前の既定動作を回帰テストする。MCP、GUI、両モデルと画像を確認するまで統合・完了と扱わない。

## 2026-11-03 の部分検証

- RED は未実装 `colorMask` による期待どおりの compile failure。GREEN は既存5件を含み 10/10 成功し、完全一致・近傍・反転・透明・既定 mask の反転・無効入力・保護 session 拒否・生成失敗後の一時ファイル削除を確認。
- source-built JAR SHA-256 `c8ac8bc3416aa66edb96c5aa8b673a3ad2ca3ffd7a34f85a44eb2a334db4c822`、変更 class 2 件が target と一致。公式 MCP SDK 経由、SD1.5 512×512 と SDXL 768×768 のいずれも白領域 Quick Mask の生成が完了し、PNG に設定が記録され、元画像 bytes は変わらなかった。元画像は白地に青い正方形という人工 fixture。結果を目視し、SDXL の白地はほぼ元のまま、SD1.5 は薄い灰色の変化だった。
- 同条件・同 seed の標準 img2img に、白地と同一の手作り mask を渡した対照と Quick Mask の生成結果は、両モデルとも RGB 完全一致。これは mask 経路の一例を示し、自然画像や他色・閾値・反転での品質、上流との完全互換を証明しない。記録は `outputs/quickmask62-control-sd15/receipt.json` と `outputs/quickmask62-control-sdxl/receipt.json`。
- 実 MainWindow、配布版、自然画像、色 mask の保護 session 対応、Return mask・FX 限定・overlay・各種 effects・設定保存は未検証または未実装。#62 は open のまま。

保護 session の追補: 最初の GREEN は 8-bit grayscale を既存スクランブル書込みが受け付けず失敗。mask を白黒 RGB で保持するよう修正後、plugin suite 11/11、実 Generator の PluginContext で保護一時 PNG の暗号化・復号・削除 1/1 が成功。実モデルでの保護 session のマスク生成と復号はまだ確認していない。

保護 session の実モデル追補: source-class job で SD1.5 512×512／SDXL 768×768 とも、保護済み合成 input と White Quick Mask から生成し、生成結果が保護され正しいパスワードで復号可能・パスワードなしでは拒否されることを確認。一時ディレクトリは生成後に空になった。復号画像を目視し、同 seed の非保護 Quick Mask と両モデルとも全 RGB 一致。`outputs/quickmask62-protected-{sd15,sdxl}` のテスト用復号画像は合成 fixture であり、実写真や個人データではない。これは source-class job の試験で、実 MainWindow や配布版の確認ではない。

非表示 MainWindow で実際の img2img script form を開き White を選択、値の反映と Phase 選択が Quick Mask 内に誤って格納されないことを確認（`WORKFLOW_QUICKMASK_OFFSCREEN_OK`）。`outputs/quickmask62-offscreen3/workflow-quickmask-controls.png` を目視した。これは transparent な subsection を画面の暗色背景上に撮影した UI 要素の画像で、実際に表示した window の操作や生成を示さない。ユーザーの画面・入力は使用していない。

独立レビュー追補: 元 path が mask 作成後に差し替わると異なる画像と mask が組み合わさる RED を再現。source を一度だけ読み、同じ画素から方向・mask・入力一時 PNG を作り、出力の source provenance を元 path へ戻す修正を実装。plugin 12/12 と、意図的差替え・engine provenance・次ジョブへの漏れ／例外後の復帰テストが通過した。再実機・最終 GUI 検証は未了。削除失敗は log に記録するが、OS 等による一時ファイル残留の防止は保証できない。

追加の未解決問題: Java の image reader で読み直し PNG 化するため、既存 Python の `ImageOps.exif_transpose` と JPEG の EXIF 回転、ICC・高 bit 深度・alpha の扱いが一致する保証はない。現在の画像実測は合成 PNG のみで、この差分を解消・検証するまで統合しない。MCP は元画像 path の stage によってユーザー指定 path が provenance に残らない場合がある。

上記の形式問題への暫定対応: colour mask を使う時のみ、元入力を最大 48 MiB の一度の bytes 読込みで snapshot し、その同一 bytes から検査と保護付き復号を行う。各辺 4096 以下の 8-bit opaque RGB PNG に限定し、EXIF・ICC・色 profile・透明度・圧縮 text・未知 chunk、JPEG 等を明示拒否する。Default mask の既存動作は変更しない。保守的な制約であり、上流互換や任意画像対応ではない。plugin 14/14、engine provenance 1/1 は通過。

PNG parser の追補: 重複 IHDR の RED を確認し、chunk CRC、header の先頭・一意、IDAT の必須・連続、IEND の長さ・末尾と形式パラメータを検査。非 interlaced 8-bit opaque RGB PNG 以外を拒否する。更新版 source-class JAR SHA-256 `3942be498e63b3cfd2cf5b7c1fd1c38e2a9db2602c04af6d3b6bff561f38b2f1` と変更 class 24 件を照合。非表示 MainWindow の Quick Mask 選択→dry-run 生成も成功（`outputs/quickmask62-offscreen7`）。

更新版を公式 MCP SDK と source-class 保護 session で SD1.5／SDXL の各 12 steps に再試験し、合成 PNG を入力に生成、暗号化結果と一時ファイル削除を確認。MCP で同一手書き mask の標準 img2img、保護 session での復号結果はいずれも同モデル・同 seed で全 RGB 一致。2 モデルの復号画像も目視した。SDXL での人工 fixture の背景は既存 inpaint の対照と同様ほぼ変化しない。記録は `outputs/quickmask62-snapshot-{sd15,sdxl}/receipt.json`、保護画像は `outputs/quickmask62-snapshot-protected-{sd15,sdxl}`。自然画像、上流との mask 画素比較、実際に表示した GUI、配布 EXE は未確認。MCP のユーザー指定元 path は事前 snapshot に変換されるため、その元 path の provenance は本作業で保証していない。

追加で folder 入力の元ファイル 2 件を script がそれぞれ snapshot したとき、出力の params と独立 PNG chunk が各元ファイルを指すことを fake engine の queue テストで確認（1/1）。初回はテスト側の Scala `toRealPath()` 呼び出し漏れで compile failure、修正後 pass。実 GPU の folder 組合せは未確認。

Return colour mask 追補: 選択時は生成後に mask をもう 1 枚返し、PNG parameters の `Img2Img Workflow output: mask` で判別できる。既存 mask を使う Default では明示拒否。キャンセル時は判定直後に保存が競合し得るため、不出力を保証しない。mask PNG の source path は parameters に残る場合があるが独立 chunk は保証しない。plugin 16/16、実 MainWindow の非表示 dry-run で mask 選択・2 枚出力を確認、フォーム snapshot は `outputs/quickmask62-return-offscreen/workflow-quickmask-controls.png`。

JAR SHA-256 `d6b8f1f80c2b700272bfed7aa7e2de78357848535dc44a890e5bbdf3dfee4a6e`、関連 24 class 一致。SD1.5／SDXL の公式 MCP SDK と保護 session で元の生成画像と返された mask を確認。標準 img2img 対照との生成結果・保護／非保護の生成結果は両モデルとも全 RGB 一致、mask の白／黒部分と保護を確認し、4 枚を目視。合成 PNG のみで、自然画像、可視 GUI、配布版、Default mask の画像出力、FX・overlay 等は未確認／未実装。`outputs/quickmask62-return-{sd15,sdxl}/receipt.json` と `outputs/quickmask62-return-protected-{sd15,sdxl}`。
