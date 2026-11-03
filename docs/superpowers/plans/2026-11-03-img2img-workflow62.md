# #62 img2img Workflow の段階設定（部分対応）

上流 [固定 README](https://github.com/Inzaniak/sd-webui-workflow/blob/cb68fa94ee232bb33cee7dfcfd586e4c57d77c3b/readme.md) と [作者の最新ガイド](https://civitai.com/articles/3716) から挙動を再実装する。上流 repository にライセンスは確認できず、コードを読まずコピーしない。記事本文は公式 `https://civitai.com/api/v1/articles/3716` で取得した。

最初の区切りは img2img Script として None、Phase 1/2/3 を提供し、各段階の寸法・枚数・denoise を変更可能とする。既定値は旧 README の 512×768／4／0.5、768×1152／4／0.5、1280×1920／1／0.2。最新記事の Phase 2 例は 1152×1280 と異なるため、値を固定的な最新仕様とは見なさない。入力画像を既存の保護付き画像アクセスで読み、Guess／Vertical／Horizontal／Square と Base／2:1 を適用する。None は生成パラメータを変更しない。Override denoise は明示した場合だけ選択値を使う。多画像はメモリ負荷を抑えるため batch size 1、n_iter に枚数を指定する。

既存の img2img 入力・mask・hooks・prompt・model などは保持し、Script の処理結果と適用値を PNG metadata に残す。無効な寸法・範囲、txt2img 指定は明示拒否し、処理中断は既存 queue を通す。GUI、MCP `run_script`、SD1.5/SDXL で検証する。最新ガイドにある色 mask、overlay、pixel swap、色収差、noise、flip、blur、sharpen と設定保存・復元の完全な互換はこの区切りでは未実装で、#62 を閉じない。

## 2026-11-03 の部分検証

- Scala focused test 5/5 成功。モデルなしのテストで寸法・枚数・マスク等の保持、向き、denoise と異常値を確認。
- 実 MCP SDK の `run_script` から animekawa_v10（SD1.5、512×512）と waiIllustriousSDXL_v170（SDXL、768×768）の各 2 枚を生成し、画像寸法、PNG metadata の段階情報と denoise 0.5、入力画像の SHA-256 不変を確認。4 枚とも目視し、顔の構造と画面全体に大きな破綻は見られなかった。結果は `outputs/workflow62-sd15/receipt.json` と `outputs/workflow62-sdxl/receipt.json`（ローカルの未追跡成果物）。
- JAR SHA-256 `0dcde3ec8d847983af3b761b4ca41d1aae92ef2f3984b7de46aeae322d4bfdf2`。変更した plugin と registry の 3 class は JAR と target の SHA-256 が一致した。
- 実 MainWindow を dry-run で操作し、img2img の画像を入力、スクリプトと Phase 1 を選択して生成。正方形入力の Guess が 768×768・4 枚・denoise 0.5 となり、gallery と metadata を確認した。画面のスクリーンショットは操作記録で確認した。
- 実 MCP の SD1.5／SDXL で進行中の Phase 1 をキャンセルし、同じサーバーで再生成できた。記録は `outputs/workflow62-cancel-sd15/receipt.json` と `outputs/workflow62-cancel-sdxl/receipt.json`（ローカルの未追跡成果物）。
- 実モデル GUI、配布版、他の段階・向き・マスクを含む実モデル経路は未検証。上流全機能も未実装。
- `scripts/third_party.py` は既存の別対象 `Physton/sd-webui-prompt-all-in-one` の pinned LICENSE の gh api 取得エラーで途中終了。今回の unlicensed 通知は生成器と同じ形式で通知ファイルに追加したが、生成器全体の完走は未確認。
