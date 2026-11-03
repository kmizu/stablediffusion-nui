# #62 Quick Mask 部分実装の方針

作者の公開ガイドは、入力画像の白・黒・任意色から threshold で選択領域を作り、反転して inpaint に使えると説明している。threshold 1 は完全一致、数値を増やすと近い色も含む。色距離の式は公開説明にないため、独立実装では RGB 各成分の最大差が threshold - 1 以下なら白とし、他は黒とする。これは上流の画素単位の完全一致を保証しない。透明画素はマスク対象外とする。

まず生成時のカラー mask と反転を追加する。Default は元の mask と設定を保持し、反転を明示した場合は既存 mask の反転設定を切り替える。None phase でも明示した mask は適用する。元の画像を保護付き access で読み、生成の間だけ temp PNG を保持する。image protection が有効な session では、この派生 mask の平文一時ファイルを作らないよう明示拒否する。上流の Return mask、FX 限定、FX・overlay は別途未実装として残す。入力と threshold の検証・失敗時の cleanup・以前の既定動作を回帰テストする。MCP、GUI、両モデルと画像を確認するまで統合・完了と扱わない。

## 2026-11-03 の部分検証

- RED は未実装 `colorMask` による期待どおりの compile failure。GREEN は既存5件を含み 10/10 成功し、完全一致・近傍・反転・透明・既定 mask の反転・無効入力・保護 session 拒否・生成失敗後の一時ファイル削除を確認。
- source-built JAR SHA-256 `c8ac8bc3416aa66edb96c5aa8b673a3ad2ca3ffd7a34f85a44eb2a334db4c822`、変更 class 2 件が target と一致。公式 MCP SDK 経由、SD1.5 512×512 と SDXL 768×768 のいずれも白領域 Quick Mask の生成が完了し、PNG に設定が記録され、元画像 bytes は変わらなかった。元画像は白地に青い正方形という人工 fixture。結果を目視し、SDXL の白地はほぼ元のまま、SD1.5 は薄い灰色の変化だった。
- 同条件・同 seed の標準 img2img に、白地と同一の手作り mask を渡した対照と Quick Mask の生成結果は、両モデルとも RGB 完全一致。これは mask 経路の一例を示し、自然画像や他色・閾値・反転での品質、上流との完全互換を証明しない。記録は `outputs/quickmask62-control-sd15/receipt.json` と `outputs/quickmask62-control-sdxl/receipt.json`。
- 実 MainWindow、配布版、自然画像、色 mask の保護 session 対応、Return mask・FX 限定・overlay・各種 effects・設定保存は未検証または未実装。#62 は open のまま。
