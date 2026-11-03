# Img2Img Workflow（段階設定）

img2img のスクリプト「Img2Img Workflow」では、Phase 1〜3 ごとに画像の幅、高さ、枚数、denoising を設定できる。各段階は選択して実行する。自動で連続実行するものではない。

- Phase が `None` の場合は現在の img2img 設定をそのまま使う。
- `Guess` は入力画像の縦横から向きを選ぶ。`Vertical`、`Horizontal`、`Square` も指定できる。正方形の入力は `Square` として扱う。
- `Base` は段階に設定した寸法を使い、`2:1` は高さの半分を幅とする。その後に向きを適用する。幅と高さは 8 の倍数で、64〜4096 の範囲にする。
- `Override phase denoising` を選ぶと、その値が段階ごとの denoising に優先する。枚数は 1〜100、denoising は 0〜1。
- 初期値は Phase 1 が 512×768・4 枚・0.5、Phase 2 が 768×1152・4 枚・0.5、Phase 3 が 1280×1920・1 枚・0.2。大きな画像や多数の生成には十分なメモリと時間が必要になる。

元画像、プロンプト、マスクなどの設定は引き継がれる。適用した段階と寸法、枚数、denoising は生成画像のメタデータにも記録する。

これは上流 Workflow の一部の実装や。Quick Mask、overlay、各種画像効果、設定プリセットはまだ利用できない。入力が正方形のときの `Guess` の扱いは、この実装で定めた動作や。

上流の [README](https://github.com/Inzaniak/sd-webui-workflow/blob/cb68fa94ee232bb33cee7dfcfd586e4c57d77c3b/readme.md) と作者の [Workflow ガイド](https://civitai.com/articles/3716/workflow-the-comprehensive-guide) の公開説明を参照し、コードは独立に実装した。上流には確認した版でライセンスファイルがない。
