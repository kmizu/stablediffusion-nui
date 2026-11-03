# #62 Quick Mask 部分実装の方針

作者の公開ガイドは、入力画像の白・黒・任意色から threshold で選択領域を作り、反転して inpaint に使えると説明している。threshold 1 は完全一致、数値を増やすと近い色も含む。色距離の式は公開説明にないため、独立実装では RGB 各成分の最大差が threshold - 1 以下なら白とし、他は黒とする。これは上流の画素単位の完全一致を保証しない。透明画素はマスク対象外とする。

まず生成時のカラー mask と反転を追加する。Default は元の mask と設定を保持し、反転を明示した場合は既存 mask の反転設定を切り替える。None phase でも明示した mask は適用する。元の画像を保護付き access で読み、生成の間だけ temp PNG を保持する。image protection が有効な session では、この派生 mask を保護付き一時画像として書き出し、既存の worker 入力復号を使う。ホストが保護書込みを実装していなければ拒否する。上流の Return mask、FX 限定、FX・overlay は別途未実装として残す。入力と threshold の検証・失敗時の cleanup・以前の既定動作を回帰テストする。MCP、GUI、両モデルと画像を確認するまで統合・完了と扱わない。

## 2026-11-03 の部分検証

- RED は未実装 `colorMask` による期待どおりの compile failure。GREEN は既存5件を含み 10/10 成功し、完全一致・近傍・反転・透明・既定 mask の反転・無効入力・保護 session 拒否・生成失敗後の一時ファイル削除を確認。
- source-built JAR SHA-256 `c8ac8bc3416aa66edb96c5aa8b673a3ad2ca3ffd7a34f85a44eb2a334db4c822`、変更 class 2 件が target と一致。公式 MCP SDK 経由、SD1.5 512×512 と SDXL 768×768 のいずれも白領域 Quick Mask の生成が完了し、PNG に設定が記録され、元画像 bytes は変わらなかった。元画像は白地に青い正方形という人工 fixture。結果を目視し、SDXL の白地はほぼ元のまま、SD1.5 は薄い灰色の変化だった。
- 同条件・同 seed の標準 img2img に、白地と同一の手作り mask を渡した対照と Quick Mask の生成結果は、両モデルとも RGB 完全一致。これは mask 経路の一例を示し、自然画像や他色・閾値・反転での品質、上流との完全互換を証明しない。記録は `outputs/quickmask62-control-sd15/receipt.json` と `outputs/quickmask62-control-sdxl/receipt.json`。
- 実 MainWindow、配布版、自然画像、色 mask の保護 session 対応、Return mask・FX 限定・overlay・各種 effects・設定保存は未検証または未実装。#62 は open のまま。

保護 session の追補: 最初の GREEN は 8-bit grayscale を既存スクランブル書込みが受け付けず失敗。mask を白黒 RGB で保持するよう修正後、plugin suite 11/11、実 Generator の PluginContext で保護一時 PNG の暗号化・復号・削除 1/1 が成功。実モデルでの保護 session のマスク生成と復号はまだ確認していない。

保護 session の実モデル追補: source-class job で SD1.5 512×512／SDXL 768×768 とも、保護済み合成 input と White Quick Mask から生成し、生成結果が保護され正しいパスワードで復号可能・パスワードなしでは拒否されることを確認。一時ディレクトリは生成後に空になった。復号画像を目視し、同 seed の非保護 Quick Mask と両モデルとも全 RGB 一致。`outputs/quickmask62-protected-{sd15,sdxl}` のテスト用復号画像は合成 fixture であり、実写真や個人データではない。これは source-class job の試験で、実 MainWindow や配布版の確認ではない。
