# Img2Img Workflow（段階設定）

img2img のスクリプト「Img2Img Workflow」では、Phase 1〜3 ごとに画像の幅、高さ、枚数、denoising を設定できる。各段階は選択して実行する。自動で連続実行するものではない。

- Phase が `None` の場合は現在の img2img 設定をそのまま使う。
- `Guess` は入力画像の縦横から向きを選ぶ。`Vertical`、`Horizontal`、`Square` も指定できる。正方形の入力は `Square` として扱う。
- `Base` は段階に設定した寸法を使い、`2:1` は高さの半分を幅とする。その後に向きを適用する。幅と高さは 8 の倍数で、64〜4096 の範囲にする。
- `Override phase denoising` を選ぶと、その値が段階ごとの denoising に優先する。枚数は 1〜100、denoising は 0〜1。
- 初期値は Phase 1 が 512×768・4 枚・0.5、Phase 2 が 768×1152・4 枚・0.5、Phase 3 が 1280×1920・1 枚・0.2。大きな画像や多数の生成には十分なメモリと時間が必要になる。

元画像、プロンプト、マスクなどの設定は引き継がれる。適用した段階と寸法、枚数、denoising は生成画像のメタデータにも記録する。

「Quick Mask」では、画像の White（白）・Black（黒）・Custom（任意の `#RRGGBB`）に近い部分を生成用の inpaint マスクにできる。Threshold が 1 なら完全一致し、2 以上では RGB の各成分差が `threshold - 1` 以下の色を含める。Invert で選択範囲を反転する。カラー mask は現在、48 MiB 以下・各辺 4096 以下の不透明な 8-bit RGB PNG に限定し、EXIF、ICC、alpha 等を含む入力や JPEG は明示的に拒否する。これらの形式は、通常の画像入力処理と色・向きが同じになることを確認した後に対応する。Default は既存の inpaint マスクを使い、反転を選ぶと元の反転設定を切り替える。新しく作ったマスクは既存の生成マスクを置き換える。色の距離式は公開資料で確認できないため、上流と画素単位で一致する保証はない。「Return colour mask as image」を選ぶと、カラー mask を生成画像の後に同じ一覧へ追加する。マスク画像の PNG パラメータには `Img2Img Workflow output: mask` と記録する。既存 mask を使う Default ではこの出力をまだ利用できない。生成後のキャンセルと保存が重なると、マスク画像が保存される場合がある。マスク PNG の元画像 path はパラメータ欄に記録される場合があるが、独立した source chunk は保証しない。

生成 PNG には色・閾値などを記録するが、Restore はこのマスクを自動で再作成しないため、再生成時は元画像と Quick Mask を指定し直す。保護セッションでは派生マスクも保護付きの一時画像として書き出す。

これは上流 Workflow の一部の実装や。Default の mask 画像出力、FX 限定、overlay、各種画像効果、設定プリセットはまだ利用できない。入力が正方形のときの `Guess` の扱いは、この実装で定めた動作や。

上流の [README](https://github.com/Inzaniak/sd-webui-workflow/blob/cb68fa94ee232bb33cee7dfcfd586e4c57d77c3b/readme.md) と作者の [Workflow ガイド](https://civitai.com/articles/3716/workflow-the-comprehensive-guide) の公開説明を参照し、コードは独立に実装した。上流には確認した版でライセンスファイルがない。
