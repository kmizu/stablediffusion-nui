---
title: 拡張機能
nav: 拡張機能
description: Stable Diffusion NUI に同梱された拡張機能(AlwaysOn プラグインと Script)の使い方と、目的別の一覧です。
---

Stable Diffusion NUI は、A1111 / reForge の標準スクリプトと内蔵拡張、および A1111 拡張インデックスの多くの拡張を、プラグインとして書き直して同梱しています。追加のインストールは不要で、生成タブの中から直接使えます。

## AlwaysOn と Script の違い

プラグインには 2 種類あります。

| 種類 | 動き方 | 画面での場所 |
|---|---|---|
| AlwaysOn | 有効にしている間、毎回の生成に適用されます。複数を同時に有効にできます。 | 生成フォームの下部に、プラグインごとの折りたたみセクションとして並びます。 |
| Script | 生成ジョブ全体を引き受けて実行します(グリッドを作る、何度も生成する、など)。同時に選べるのは 1 つだけです。 | 生成フォームの **Script** カードにあるドロップダウンで選びます。 |

![txt2img フォーム全体。下部に Script カードと AlwaysOn プラグインのセクションが並ぶ](shots/txt2img-form.png)

### AlwaysOn プラグインを使う

1. **txt2img**、**img2img**、**Inpaint** のいずれかのタブを開きます。
2. フォームを下までスクロールし、使いたいプラグインのセクションを探します。
3. セクション見出しの左にあるチェックボックスをオンにします。オンのセクションは見出しが強調表示されます。
4. セクションを展開して値を調整し、通常どおり生成します。

セクションの先頭には、そのプラグインの説明が表示されます。見出しにマウスを合わせても同じ説明を確認できます。

### Script を使う

1. 生成フォームの **Script** カードで、ドロップダウンからスクリプトを選びます。既定は **None**(スクリプトなし)です。
2. ドロップダウンの下に、選んだスクリプトの設定項目が表示されます。
3. 通常どおり生成すると、スクリプトがジョブを実行します。

Script と AlwaysOn は併用できます。たとえば **X/Y/Z plot** の各画像にも、有効にしている AlwaysOn プラグインが適用されます。**X/Y/Z plot** の軸には、プラグインが提供する設定値も選べます。

### タブごとの設定

- プラグインの値と選択中の Script は、**txt2img**、**img2img**、**Inpaint** のタブごとに別々に保持され、次回起動時にも復元されます。
- **Inpaint** タブは img2img と同じプラグインを使います。
- プラグインによっては、使えるタブが限られています。対応していないタブでは、そのセクションやドロップダウンの項目が表示されません。下の一覧では、片方のタブ専用のものに注記しています。
- 次のプラグインは、汎用セクションではなく専用の場所に表示されます。
    - **Hires Fix Tweaks**: txt2img の **Hires. fix** セクションの中
    - **Inpaint Difference**: **Inpaint** タブの画像パネルの中
- **Tokenizer** と **VAE Tools** は生成フォームには表示されず、同名のタブとして使います(詳しくは[ツール](tools.html))。

### プラグインを非表示にする

使わないプラグインは、`config/settings.json` の `disabled_plugins` にプラグインの id を書くと、画面から外せます。各コントロールの既定値は同じファイルの `plugin_values` に保存されます。アプリを終了してから編集し、次回起動時に反映させてください。

### MCP から使う

AI エージェントからも同じプラグインを使えます。`list_always_on` で AlwaysOn プラグインとその値を調べ、生成リクエストの `always_on` に指定します。この指定はそのジョブだけに適用され、デスクトップ側の設定は変わりません。Script は `list_scripts` で一覧を取得し、`run_script` で実行します。**Custom code** は MCP からは実行できません。詳しくは [MCP](mcp.html) を参照してください。

## AlwaysOn プラグイン一覧

表の名前は、画面に表示されるセクション名です。

### プロンプト処理

| 名前 | 内容 |
|---|---|
| **Prompt Format** | 生成前に、重複したスペースやカンマ(設定により重複タグも)を取り除きます。 |
| **Prompt Filter** | 禁止語を取り除き、「検索 => 置換」のルールを両方のプロンプトに適用します。 |
| **Negative Prompt Weight** | ネガティブプロンプト全体に 1 つの重みをかけます。 |
| **NovelAI prompt converter** | NovelAI 式の強調 `{tag}` / `[tag]` を、生成前に A1111 式の重みに変換します。 |
| **Style variables** | 保存済みのスタイルを `{style:Name}`(または `$Name`)でプロンプト内に埋め込みます。 |
| **Weight Gradient** | `{words::0-1:e}` のように書いた語の重みを、サンプリング中に変化させます。 |
| **NegPiP** | `(black hair:-1)` のような負の重みで、ポジティブ側では概念を抑え、ネガティブ側では強めます。 |
| **Neutral Prompt** | `AND`、`AND_PERP`、`AND_SALT`、`AND_TOPK` などで、複数のプロンプトを予測の段階で合成します。 |
| **Prompt Fusion** | 曲線や注意のランプ、変数を使って、各画像のサンプリング中にプロンプトの埋め込みを補間します。 |
| **Model Keyword** | チェックポイントや選択した LoRA のキーワードをプロンプトに挿入します(詳しくは[ツール](tools.html))。 |

### 生成設定のランダム化

| 名前 | 内容 |
|---|---|
| **Randomize** | サンプラー、スケジュール、ステップ数、CFG、サイズを生成ごとにランダムに選びます(シードで再現可能)。 |
| **Random Resolutions** | 生成ごとに、リストから(またはピクセル数の上限と縦横比から)サイズをランダムに選びます。txt2img 専用です。 |

### LoRA

| 名前 | 内容 |
|---|---|
| **LoRA Block Weight** | LoRA の U-Net ブロックごとに重みを変え(プリセットまたは個別指定)、適用するステップ範囲を限定します。 |
| **Composable LoRA / LyCORIS** | アダプターを `AND` で区切ったプロンプトごとに適用し、ネガティブ側やステップごとの重みも制御します。 |

LoRA 全般の使い方は [LoRA と Embedding](lora-embeddings.html) を参照してください。

### ガイダンス(CFG)

| 名前 | 内容 |
|---|---|
| **FreeU** | U-Net のバックボーン特徴とスキップ特徴の重みを調整します(FreeU v2)。 |
| **Dynamic Thresholding (CFG-Fix)** | ガイダンスをクランプし、高い CFG でも低い CFG に近い仕上がりにします。 |
| **APG is now your CFG** | Adaptive Projected Guidance。CFG を置き換え、高 CFG での色の飽和を抑えます。 |
| **Mahiro CFG** | CFG と一致する部分で、ポジティブプロンプト寄りに補正します。 |
| **RescaleCFG** | ガイダンス後の予測の標準偏差を、ポジティブ側に合わせて調整します(v-prediction / ZTSNR モデル向け)。 |
| **Perturbed-Attention Guidance** | 自己注意を乱したパスからのガイダンスを加えます(PAG)。 |
| **Semantic Guidance** | 専用のプロンプトに書いた概念(カンマ区切り)を画像に加え、ネガティブプロンプトの概念を取り除きます。Warmup 後のステップでプロンプトの条件付けを編集するので、構図はおおむね保たれます。Edit guidance scale の既定値 1.0 でも効きが強く、特に取り除く方向では崩れることがあるので、0.3〜1.0 で調整してください。 |
| **Characteristic Guidance** | CFG スケールが大きいときの CFG を、U-Net への入力を反復的にずらして補正します。大きなスケールのプロンプト追従性を保ったまま、彩度の飽和や不自然な色、背景、細部を抑えます。1 ステップごとに U-Net を何回も追加で評価するので遅く、まず通常の CFG で生成してから同じシードで試すのがおすすめです。反復が収束しなかったステップは通常の CFG になります(収束したステップ数はログに出ます)。Regularization Strength と Range Over Time を大きくすると収束しやすくなり、結果は CFG に近づきます。SDXL では Regularization Strength 5 から始めてください。CHG End Step を 0.4 前後にすると、結果をあまり変えずに大幅に速くなります。 |
| **Incantations** | 4 つのガイダンス手法をまとめたもので、それぞれに Active があります。Smoothed Energy Guidance は、条件なし予測の中間ブロックの自己注意をぼかします。CFG は 3 前後に固定し、SEG Blur Sigma で効き具合を調整してください(11 が最大、0 で無効)。S-CFG は、プロンプトのトークンが受け持つ領域ごとに CFG の効きをそろえます。メモリを多く使います。Perturbed Attention Guidance は、自己注意を無効にした追加の推論からガイダンスを加えます(PAG Scale を 0 から 3 程度に上げます。Saliency-Adaptive Noise Fusion をオンにすると CFG と混ぜ合わせます)。CFG Scheduler はステップに応じて CFG を変え、ノイズ区間(SDXL ではおよそ 0.28〜5.42)の中だけで CFG を使います。Constant を選ぶと区間は無視されます。Multi-Concept T2I-Zero は概念が混ざるのを抑えます。CTNMS は既定でオンで、CbS は Correction Strength を 0 より大きくすると効きます。ステップはパスごとに 0 から数えます。SEG、S-CFG、PAG、CFG Scheduler には 1 より大きい CFG が必要です。PAG・SAG プラグイン、Reference-only、Neutral Prompt、Composable LoRA とは併用できず、PAG・S-CFG・CFG Scheduler はほかの CFG を変える機能とも併用できません。Seek for Incantations は含まれていません。 |
| **Sonar** | サンプラーの更新にモメンタムを加えます。各ステップが、現在の潜在の差分と、過去の差分を減衰させながら蓄えた履歴の混ぜ合わせに沿って進み、近くにある似た画像へ結果がずれます。有効にしている間は、ここで選ぶ Base Sampler(Euler a、Euler、単純な Naive)がリクエストのサンプラーの代わりになります。Momentum current を 1 にするとモメンタムが切れます。0.85 以上で使い、Momentum sign は pos、履歴の初期値は zero のままにしてください(履歴の初期値 `rand_init` は通常の設定では画像が崩れます)。Naive は、ステップ範囲の中で線形または Euler 法の1歩で参照画像へ引き寄せられます(1 以下の値はステップ数に対する割合、強さはスライダー値の 10 分の 1)。Upscaling は、完成した画像を倍率または指定サイズに拡大し、はみ出す分は中央で切り落とします。元の拡張機能のグリッドサーチには、X/Y/Z の Sonar momentum と Sonar momentum_hist の軸を使ってください。参照画像は PNG に保存されないので、同じ絵を再現するときは選び直してください。 |
| **Batch Face Swap** | 仕上がった画像の顔をすべて検出して描き直します(ADetailer の後に実行し、img2img でも使えます)。顔だけのプロンプトとネガティブプロンプト(`[PROMPT]` はメインのプロンプト、`[SEP]` で左の顔から順に別々のプロンプト)、ステップ数、CFG スケール、サンプラー、顔領域の解像度(描き直す切り抜きの拡大率)、顔のまわりに広げる楕円または矩形のマスク、マスクのぼかし、切り抜きの余白、ノイズ除去強度、色合わせを指定できます。検出器は Bingsu/adetailer の YOLO 顔モデル(初回に取得、`ultralytics` が必要)、`opencv_face`(取得不要、アニメ顔はあまり見つかりません)、または `mediapipe_face` です。`mediapipe_face` は別途 MediaPipe 1.2.0 が必要で、初回に Google の短距離顔検出モデルを取得します。顔が小さい画像では見逃すことがあります。顔の描き直しだけ別のチェックポイントを使う設定と、img2img のフォルダ入力は含まれません。 |
| **LLuL** | Local Latent upscaLer。画像の一部の正方形の領域(幅と高さの 1/2^N、中央または X / Y の位置)を U-Net の中で拡大して処理するので、その領域はより細かく描かれます。結果は Weight(既定 0.15、負の値も使えます)で元に戻して混ぜます。マスク画像を使うと、画素ごとに Weight を変えられます(白が全部、黒がなし)。詳細設定(「I know what I am doing.」にチェック)では、適用する層を選べます。既定は出力層だけで、ほかに Resblock、Transformer、自己・交差注意を元の層名(IN01、M00、OUT03 など)で絞り込めます。拡大・縮小の方式とアンチエイリアス、Lerp か SLerp、開始・終了ステップも選べます。ステップはパスごとに 1 から数えます。ADetailer のパスには適用されません。有効領域を示す追加の出力画像は含まれていません。 |
| **Aesthetic Gradients** | 参照画像から作った埋め込みを使い、生成画像の雰囲気を変えます。**Aesthetic Embeddings** タブで **Add images…** から画像を選び、名前を入力して **Create embedding** を押します。一覧から選んで **Use in generation** を押すと、現在の生成モードに適用されます。ファイルは出力フォルダの `aesthetic_embeddings` に保存されます。既存ファイルは明示しない限り上書きしません。MCP では `aesthetic_embedding` で作成・一覧取得できます。初回は CLIP ViT-L/14（約1.7 GB）をダウンロードします。SD 1.x と SDXL に対応し、SDXL では CLIP-L 側だけに適用します。SD 2.x は対応していません。強い設定では画像が崩れることがあるため、weight は小さめの値から試してください。 |
| **Clip I/O** | CLIP の conditioning を書き出し、または直接サンプラーへ渡します。**Clip Output** は生成中に Positive(任意で Negative)プロンプトの conditioning を conditioning フォルダ(環境変数 `SDFX_CLIP_IO_DIR`、既定は `~/.cache/stablediffusion-nui/clip_io/conditioning`)へ `.csv` または `.pt` で保存します。1 行が 1 トークン、1 列が 1 次元で、**Transpose matrix** で入れ替えます。**Don't add bos / eos / pad tokens**、**Ignore emphasis**(重みをすべて 1 にします。強調構文の解析は続きます)、**Bypass conditioning normalization**(重み付け後の平均補正をしません)、**Add token strings**、**Overwrite** があり、既存ファイルは Overwrite がオンでない限り残します。**Clip Input** は CLIP を通さず、Positive / Negative 欄を conditioning ファイルの一覧として扱います(Simple: 空白区切りの名前、空白を含む名前は引用符で囲みます。拡張子なしの名前は `.csv`、`.pt` の順にも探します。`"""text"""` は CLIP で変換し、すべてをトークン軸で連結します)。**Directive** モードでは `?prompt("""text""", clip_skip=2, no_padding=False)`(リスト可)と、`i`(conditioning)、`o`、`t`(トークン番号)、`d`(次元番号)に対する成分ごとの計算 `?eval(式)` / `?eval_順序(式)` が使えます(`pi`、`e`、`sin`、`cos`、`exp`、`log`、`sqrt`、`abs`、`min`、`max`、`clamp`、`where` など)。`eval` は Python ではなく制限付きの算術インタプリタで、`?exec` は提供しません。入力プロンプトではプロンプト編集、AND、BREAK を解析しません。置き換えるのは生成本体のプロンプトだけです(ADetailer や Hires の上書きプロンプトは対象外)。SDXL の `.pt` は pooled ベクトルを持ち、CSV の場合は空プロンプトの pooled を使います。幅は読み込み中のモデルに合う必要があります(SD1.x は 768、SD2.x は 1024、SDXL は 2048)。設定は infotext の `Clip Input positive` / `Clip Output ...` に残り、PNG と MCP から復元できます。再現には同じ conditioning ファイルが必要です。 |
| **Compile (TensorRT / Inductor)** | 有効にした生成で U-Net をコンパイルします（NVIDIA TensorRT 拡張、index 236）。**Inductor** は `torch.compile` を使います（`triton`、Windows では `triton-windows` と C コンパイラが必要です。`cl.exe` が PATH に通るよう、Visual Studio の x64 Native Tools プロンプトからアプリを起動してください）。モードは `default`、`max-autotune-no-cudagraphs`、`reduce-overhead` です。**TensorRT** は Torch-TensorRT で静的エンジンを作ります（`torch-tensorrt` と対応する `tensorrt` が必要です。`python/requirements.txt` を参照してください。TensorRT 本体は NVIDIA のもので同梱しません）。最小ブロックサイズとビルダー最適化レベルを指定でき、キャッシュにチェックを入れるとエンジンをディスク（`$SDFX_TRT_CACHE`、既定は `~/.cache/stablediffusion-nui/tensorrt`）に保存します。ビルド用の別操作はなく、新しい解像度やバッチサイズでの最初の生成がビルドします（数分かかります。Hires. fix のパスやバッチサイズが違えば別のエンジンです）。以降は再利用します。CUDA GPU と VRAM モード `gpu` が必要です。ControlNet、Composable LoRA、U-Net やサンプラーループを書き換えるフック（FreeU、PAG、HyperTile など）との併用は、理由つきで拒否します。TensorRT では LoRA も使えません（エンジンに重みが焼き込まれるため。Inductor は LoRA と併用できます）。結果は通常の U-Net に近いですが同一ではありません（fp16 カーネルの違い）。高速な GPU では速度向上は小さく、U-Net 単体の実測は Inductor が SD 1.5（512）・SDXL（1024）とも約 1.09 倍、TensorRT が SD 1.5 で 0.78 倍・SDXL で 1.13 倍で、アプリ全体では TensorRT の方が遅くなりました。RTX 3090 では TensorRT は高速化になりません（`docs/compile-verification.md`）。設定は infotext（`Compile backend`、`Compile mode`、`Compile min block size`、`Compile optimization level`）に書かれ、PNG から復元されます。 |
| **Aesthetic Score** | 最終画像(検閲や拡大の後、保存の直前)を採点し、スコアをインフォテキストと、既定ではPNG情報チャンクに書きます。画素は変わりません。**Aesthetic Image Scorer**(既定でオン)は CLIP ViT-L/14 と改良版 aesthetic predictor による 0〜10 のスコアで、小数 1 桁の `aesthetic_score` として書きます。**Aesthetic Scorer** は CLIP ViT-L/14 または ViT-B/16 の simulacra predictor で、0〜10 のスコアを `Aesthetic Scorer ViT-L/14`(B/16)と PNG チャンク `score` に書きます。**cafe-aesthetic** は 3 つの分類器(aesthetic / not_aesthetic、style(anime、real_life、3d、manga_like、other)、waifu / not_waifu)で、インフォテキストには最も確率の高いクラスとその確率(`Cafe waifu: waifu 0.9100`)、PNG チャンク `cafe_*` には全クラスの確率を書きます。ほかに、sampler、cfg_scale、sd_model_hash、seed を PNG チャンクに書くオプションと、実験的な `hash`(生成パラメーターの md5。サンプラー名やモデルハッシュが違うので Web UI の値とは一致しません)があります。モデルは初回使用時にダウンロードし、固定したハッシュで検証します(CLIP ViT-L/14 は 1.7 GB、ViT-B/16 は 0.6 GB、cafeai の分類器は各 0.4 GB)。普段は RAM に置き、採点する間だけ GPU を使います(Force CPU なら GPU は使いません)。スコアは好みのモデルであり、品質の保証ではありません。Windows のファイルタグ・カテゴリと EXIF には対応していません。 |
| **Self Attention Guidance** | ぼかした自己注意の領域でガイダンスします。SD1.x の txt2img 専用で、DDIM / UniPC / DPM++ 系のサンプラーが必要です。 |
| **CFG Schedule** | CFG スケールを、ステップの進行に合わせて開始値から終了値へ変化させます(CFG > 1 が必要)。 |
| **CADS** | プロンプトの条件付けにスケジュールしたノイズを加え、結果の多様性を高めます。 |

#### 参照画像から埋め込みを作る

![Aesthetic Embeddings タブの初期画面](shots/aesthetic-embeddings.png)

画面例はテスト用の dry-run で撮影しています。参照画像は1〜32枚を指定できます。作成後に **Refresh list** で一覧を読み直し、ファイルを選んで **Use in generation** を押します。名前が同じファイルを置き換えるときは **Replace an existing embedding** を選びます。**Cancel** は作成の中断を要求しますが、すでに保存が確定した場合や中断保護が有効な場合は中断できません。

保護画像と保護画像セッション中の画像は使えません。上書きしない作成では保存先のファイルシステムにハードリンクが必要です。exFAT など対応していない保存先では、NTFS など対応する保存先を使ってください。ADetailer の追加処理と、negpip・Embedding Merge の専用テキスト処理には適用されません。

### サンプリングと潜在空間

| 名前 | 内容 |
|---|---|
| **Detail Daemon** | 実際とは異なるノイズ量をスケジュールに沿ってモデルに伝え、ディテールを増やしたり減らしたりします。 |
| **Vectorscope CC** | サンプリング中の潜在ノイズに、明るさ、コントラスト、彩度、RGB のオフセットをかけます。 |
| **CD Tuner** | 描き込み量(Detail 1/2。Hires 用に別の値も指定できます)、コントラスト、明るさ、色調、彩度を調整します。モデルと VAE の一部の重みと、サンプリングの 1 ステップに手を加える仕組みです。Color Map を使うと、最初の数ステップで画面の領域ごとに色を誘導でき、プロンプトの色が反映されやすくなります。値は 5 前後までから試してください。描き込みを増やすとノイズも増えます。プロンプトに `<cdt:d1=2;col1=-3>` や `<cdtc:...>` と書くと設定を上書きでき、X/Y/Z の Prompt S/R でも使えます。 |
| **Latent Mirroring** | 最初のステップで潜在を反転・回転させ、左右対称に近い構図から完全な鏡像まで作れます。 |
| **Asymmetric Tiling** | X 軸と Y 軸を別々に設定して、継ぎ目のないタイル画像を作ります(U-Net と VAE)。 |
| **Anti-burn** | 最後の数ステップを省き、その手前のステップの潜在を平均して、なめらかな結果にします。 |
| **ReSharpen** | 各ステップの変化を強めて(ディテール増)、または弱めて(なめらか)進めます。 |
| **Diffusion Color Grading** | 潜在の色チャンネルを中心に戻し(色かぶりの除去や特定の色への寄せ)、範囲を広げます。 |
| **Latent Modifier** | シャープネス、トーンマッピング、コントラスト、CFG のずれの補正、ノイズ、周波数の調整を行います。 |
| **Advanced Model Sampling** | モデルの予測タイプ(eps / v-prediction)とゼロ終端 SNR を上書きします。 |

### 大きな画像・速度・メモリ

| 名前 | 内容 |
|---|---|
| **HyperTile** | 自己注意をタイル分割して、大きな画像の生成を速くします。 |
| **Kohya HRFix (Deep Shrink)** | 序盤のステップで U-Net の深いブロックを縮小し、高解像度でも構図が崩れにくくします。 |
| **MultiDiffusion (tiled diffusion)** | 重なり合う潜在タイルごとにノイズ除去して合成し、非常に大きな画像を作ります。 |
| **Token Downsampling** | 自己注意のキーと値を間引いて、ディテールと引き換えに速度を上げます。PAG や HyperTile とは併用できません。 |
| **Agent Attention** | U-Net の自己注意で似たトークンをまとめ、少数のエージェントトークンを介して注意を計算します(AgentSD)。速度が上がり、ディテールが良くなることもあります。First Pass の設定は Second Pass Step まで効き、そこで効果が止まります。Use Second Pass をオンにすると、そこから Second Pass の設定に切り替わります。Apply to Hires. Fix Only をオンにすると、Hires パスだけに効きます。SDXL では Max Downsample を 2 以上にしてください(1 では何も変わりません)。PAG、SAG、HyperTile、Token Downsampling、Reference-only、ControlNet-LLLite とは併用できません。 |
| **Never OOM** | 注意と VAE のスライス処理、VAE のタイル処理で、そのジョブの VRAM ピークを下げます。 |
| **GPU temperature guard** | GPU 温度がスリープ温度を超えたらサンプリングを一時停止し、ウェイク温度を下回ったら再開します。 |

### Hires. fix と img2img

| 名前 | 内容 |
|---|---|
| **Hires Fix Tweaks** | 1 回目の結果を、別のシード、プロンプトの組み立て方、スタイルで仕上げ直します。**Hires. fix** セクションの中にあります。 |
| **img2img Hires Fix** | txt2img、img2img、Inpaint で完成した各画像を、ピクセル拡大と VAE 再エンコードで拡大・仕上げします。標準の潜在 Hires とは別の機能です。 |
| **Conditioning Highres Fix** | 9 チャンネルのインペイントモデルの画像条件付けを、ノイズ除去強度に合わせて調整します。 |

Hires. fix 自体の使い方は [画像の生成](generate.html) を参照してください。

### 画像プロンプトとコントロール

| 名前 | 内容 |
|---|---|
| **Reference only** | 自己注意が参照画像にも注目し、追加モデルなしで画風や被写体を引き継ぎます。 |
| **Revision (SDXL image prompt)** | SDXL 用。画像の CLIP-G 埋め込みをプロンプトに加えます(エンコーダーは初回に取得)。 |
| **IP-Adapter** | 画像プロンプトのアダプターです。モデルは ControlNet のフォルダに置きます。 |
| **ControlNet-LLLite** | kohya 氏による SDXL 向けの軽量 ControlNet です。画像を指定しない場合は初期画像を使います。 |
| **PhotoMaker** | SDXL 用。写真から人物の特徴を取り込みます。クラス名の後にトリガーワードを書きます(例 `a man img`)。txt2img 専用です。 |
| **ControlNet Fastload** | ControlNet ユニットを画像や `.cni` から読み込み、生成画像に保存します(詳しくは [ControlNet](controlnet.html))。 |

### インペイントと修正

| 名前 | 内容 |
|---|---|
| **Soft Inpainting** | ぼかしたマスクに沿って、元の内容と生成した内容をなめらかに混ぜます。img2img / Inpaint 専用です。 |
| **Fooocus Inpaint** | Fooocus のパッチを使った SDXL のインペイントです。img2img / Inpaint 専用です。 |
| **Inpaint Difference** | 元画像と変更後の画像の差分からインペイント用マスクを作ります。**Inpaint** タブの画像パネルの中にあります。 |
| **Restore faces** | 検出した顔に GFPGAN または CodeFormer をかけます。 |
| **ADetailer** | 結果画像の顔・手・人物を検出し、その部分を高解像度で描き直します。 |
| **NSFW Checker** | 最終画像を保存する直前(ADetailer や Hires の仕上げの後)に、最大 3 種類のチェッカーで調べます。**NudeNet**(既定でオン)は、同梱の検出器(CPU で動作)で NSFW 領域を検出し、ぼかし・モザイク・塗りつぶしをかけます。ライブプレビューにもかけます。既定では露出した胸・性器・肛門が対象です。18 のカテゴリーごとにしきい値と拡大率を設定できます(しきい値 1 でそのカテゴリーは無効)。検閲した画像は、オフにしない限り検閲前のコピーを `-before-censor` 付きで保存します。マスクも `-censor-mask` 付きで保存できます。**SD safety checker** は、問題があると判定した画像を真っ黒な画像に置き換えます。ライブプレビューは、オプションをオンにしたときだけ調べます。**NSFWJS filter** は、最も確率の高い分類が安全レベルで NSFW とされるものなら、画像全体をぼかします(**safe**: hentai・porn・sexy、**questionable**: hentai・porn、**explicit**: 何もしない)。ライブプレビューと、保存するすべての画像にかけます。この 2 つは初回使用時にモデル(1.2 GB と 10 MB)をダウンロードし、ハッシュを確認します。どのチェッカーも見逃すことがあります。 |

### 出力と記録

| 名前 | 内容 |
|---|---|
| **Save intermediate images** | ノイズ除去の途中経過を、最終画像とは別に PNG の連番や動画として保存します。 |
| **Grid image numbers** | グリッドの各タイルに、保存された画像の番号と X/Y/Z の値を書き込みます。 |
| **PNG resource hashes** | 使ったローカルのモデルなどのハッシュを、PNG の生成情報に記録します。 |

## Script 一覧

### グリッドと比較

| 名前 | 内容 |
|---|---|
| **X/Y/Z plot** | 最大 3 つのパラメーターを変えた画像のグリッドを作ります。 |
| **Prompt matrix** | `|` で区切ったプロンプトの各部分の、すべての組み合わせを生成します。 |
| **Test my prompt!** | 同じシードで、プロンプトの各部分を 1 つずつ外した画像とラベル付きのグリッドを作ります。 |
| **LoRA queue** | 選んだ LoRA ごとに(空ならすべての LoRA で)1 回ずつ生成し、グリッドにまとめます。 |
| **Batch checkpoints and prompts** | `checkpoint | prompt template` の各行ごとに 1 回生成します(`{prompt}` は現在のプロンプト)。 |

### プロンプトの生成と変化

| 名前 | 内容 |
|---|---|
| **Prompts from file or textbox** | 1 行ごとに 1 回生成します。行には `--prompt` や `--steps` などのオプションも書けます。 |
| **Dynamic Prompts / Wildcards** | `{a|b|c}` などのテンプレートと、`wildcards/*.txt` の `__wildcards__` からプロンプトを作ります。 |
| **One Button Prompt** | 被写体、スタイル、場所、照明などを含むプロンプトをランダムに作って生成します。 |
| **Booru prompt** | booru の投稿のタグをプロンプトにします。検索タグに合う投稿をランダムに選ぶ（画像ごとに別の投稿）か、リンク・ID・md5 で投稿を指定します。Danbooru、AIBooru、e621、Gelbooru、Rule34、Safebooru、Xbooru、Konachan、Yande.re に対応し、レーティング、タグの種類、除外・追加・シャッフル・件数制限、タグの混合、カオス・ネガティブモード、投稿画像からの img2img、ランダム LoRA が使えます。Gelbooru と Rule34 は user id と API キーが必要です。フォーム下のボタンで、指定した投稿またはランダムな投稿のタグを今のプロンプトへ追加できます。タグは画像に保存されるので、再現にネットワークは要りません。 |
| **CreaPrompt** | 単語リストからランダムに選んだ語句でプロンプトを作ります。チェックしたカテゴリ（人物、髪、服、動作、場所、画風、アーティスト、カメラ、照明など 42 リスト）から 1 行ずつ選ぶモード、カテゴリごとに選択肢から語句を選ぶ手動モード（`カテゴリ名: 語句` または `カテゴリ名: random` のテキスト入力も可能）、801 件の完成プロンプト集から選ぶモードがあります。カテゴリ選択のプリセット保存、手動選択のプレビュー、接頭辞・接尾辞、バッチの画像ごとに別のプロンプト（シード固定で再現可能）または全画像で共通、プロンプトの置き換え・前・後ろへの追加に対応します。プリセットはランダムモードのカテゴリ選択を保存し、手動の語句選択は保存しません。プレビューの `Random:` は生成時に選ばれる語句の代わりにカテゴリ名を示します。アプリフォルダの `creaprompt/csv` に自分の `.csv` を置くとカテゴリが増えます。フォーム下のボタンで画面のプロンプトにランダムなプロンプトを追加・置換できます。完成したプロンプトは画像に保存されるので、再生成にリストは不要です。 |
| **StylePile** | 画像の種類、アーティスト、芸術運動などを重み付きで組み合わせ、順番またはランダムに変化させます。 |
| **Scramble prompts** | カンマ区切りのタグを並べ替えたり、ランダムに外したりして、プロンプトの変化形を作ります。 |
| **Tweak weights** | パターンに合うタグ(空ならすべてのタグ)の重みをランダムに変えて、変化形を作ります。 |

### CreaPrompt の使い方

1. txt2img または img2img のフォームを下へスクロールし、**Script** で **CreaPrompt** を選びます。選択中は **CreaPrompt: Active** と表示されます。
2. **Mode** で作り方を選びます。**Random from categories** は **Categories (random mode)** でチェックしたカテゴリから各1行を選びます。**Manual (choose per category)** は **Manual picks** の各欄で語句を選び、`(random)` にしたカテゴリは生成時に選びます。**Prompt collection** は完成済みのプロンプトから選びます。
3. 必要なら **Prefix of the prompt** と **Suffix of the prompt** を入力します。**Use the prompt** では、作ったプロンプトで置き換えるか、今のプロンプトの前後に追加するかを選びます。
4. 画像ごとに別のプロンプトを作るには **Different prompt for each image** をオンにします。最後に **Generate** を押します。完成したプロンプトは画像の生成情報に保存されます。

![CreaPrompt の手動選択欄を開いた画面](shots/creaprompt.png)

手動モードの **Prompt preview** は選択した語句を表示し、ランダム指定は `Random: カテゴリ名` と表示します。ランダムな語句は生成時に決まります。ランダムモードでカテゴリの組み合わせを保存するには、**Preset name** に名前を入力し **Save category preset** を押します。保存後は **Category preset** から選べます。保存されるのはカテゴリ名で、手動で選んだ語句は含まれません。同名のプリセットは上書きできません。

付属の一部プリセットには、対応するカテゴリが見つからない名前が含まれています。その名前は無視されます。カテゴリを一つも選べない場合はエラーになるので、別のカテゴリを選んでください。

独自のカテゴリを加えるには、アプリフォルダの `creaprompt/csv` に UTF-8 の `.csv` ファイルを置きます。1 行に 1 つの語句を書き、`9_1MyCategory.csv` のように先頭に並び順の数字を付けます。新しいカテゴリはアプリを再起動すると表示されます。

### アニメーションと動画

| 名前 | 内容 |
|---|---|
| **Seed travel** | シード間のノイズを補間したフレームを作り、フレームとアニメーション GIF を保存します。txt2img 専用です。 |
| **Shift attention** | プロンプトに `(word:0.5~1.5)` と書くと、重みを変化させたフレームと GIF を保存します。 |
| **Steps Animation** | 1 回の生成のノイズ除去の途中経過から動画を作ります。 |
| **AnimateDiff** | AnimateDiff のモーションモジュールで動画を生成します(SD 1.x のチェックポイントには SD 1.x 用、SDXL には SDXL 用のモジュール)。モジュール(`mm_sd15_v3.safetensors` のようなファイル、diffusers 形式のフォルダ、または `guoyww/animatediff-motion-adapter-v1-5-2` のような Hugging Face のリポジトリ ID)は実行時に読み込み済みのチェックポイントへ差し込まれるので、LoRA・Embedding などの設定はそのまま効きます。フレーム数・FPS・ループ回数・保存形式(GIF / MP4 / WEBP / WEBM。PNG は全フレームをギャラリーに保存、TXT は生成情報のテキスト)を指定します。コンテキストのバッチサイズより長い動画は重なり合うウィンドウで処理します(Overlap・Stride・Closed loop N / R-P / R+P / A)。プロンプトトラベルは、先頭の行・`フレーム番号: プロンプト` の行・末尾の行で書きます。img2img タブの Img2Vid は初期画像(と任意の最終フレーム)を開始ノイズに混ぜ、FreeInit は開始ノイズを整えます(txt2img)。最初のフレームを生成情報(キー `AnimateDiff`)付きでギャラリーに保存し、動画ファイルはその隣に保存します。バッチサイズは 1 のみで、Hires. fix・ControlNet V2V・SparseCtrl・Motion LoRA・FILM 補間には対応していません。MP4 / WEBM には ffmpeg(PATH 上)が必要です。 |
| **SD-CN-Animation** | 動画を 1 フレームずつ現在のチェックポイント・プロンプト・LoRA・ControlNet で描き直します(video to video)。各フレームは、直前の描き直し済みフレームを RAFT オプティカルフローで次の元フレームへワープしたものから始まり、フローを信用できない部分(オクルージョンマスク)だけを描き直します。Step 1(処理強度)で描き直し、Step 2(フレーム補正強度)で整え、結果は元フレームにヒストグラムを合わせます。動画ファイル、処理フレーム数(0 で全部)、フレームの間引き、FPS を指定し、最初のフレームの隣に MP4 / GIF / WEBM / WEBP を保存します(ギャラリーに全フレームを入れることも可能)。ControlNet ユニット(canny・depth・softedge など)を追加して画像は空のままにすると、各ユニットに現在の動画フレームが渡されます。詳細設定: オクルージョンのぼかし・残像・フロー/差分の倍率、ブレンドではなく「オクルージョンを inpaint」、ブレンド α、各ステップのシード、RAFT モデル。最初のフレームがインフォテキスト(キー `SD-CN-Animation`)付きでギャラリーに入ります。MP4 と WEBM は PATH 上の ffmpeg が必要で、RAFT の重みは初回に torchvision が取得します。text-to-video(FloweR ネットワークが必要)には対応していません。 |
| **Stable Video Diffusion** | SVD モデルで初期画像を動かし、GIF と最初のフレームを保存します。img2img 専用です。 |
| **Zero123 novel view** | 初期画像の物体を別の角度から描きます。img2img 専用です。 |

### img2img・アップスケール・アウトペイント

| 名前 | 内容 |
|---|---|
| **Loopback** | 出力を次の入力にして、img2img を繰り返します。img2img 専用です。 |
| **SD upscale** | 画像を拡大してから、タイルごとに仕上げ直します。img2img 専用です。 |
| **Ultimate SD upscale** | 拡大してから、マスク付きのインペイントでタイルごとに描き直します。継ぎ目の修正もできます。img2img 専用です。 |
| **Loopback Scaler** | 出力を次の入力にして img2img を繰り返し、そのたびに画像を少しずつ最大サイズ(または倍率指定のサイズ)まで大きくします。大きくなる速さはイージングで選び、ノイズ除去強度は毎回変化率を掛けて変えます。最後の画像にはシャープネス・色・コントラストの調整とフィルターをかけられます。バッチサイズは 1 にしてください。img2img 専用です。 |
| **Old SD firstpasser** | 最初の 1 パスを別のチェックポイント(SDXL のメインモデルなら SD 1.x、その逆も可)で生成し、アップスケールしてから、メインのチェックポイントが img2img で仕上げます。SD 1.x 用の LoRA・Embedding・ControlNet を SDXL で使うためのスクリプトです。使うモデルの系統のチェックポイントを選びます(Auto はメインと別の系統)。ほかに Firstpass steps、アップスケーラー、メインのパスのノイズ除去強度を設定します。プロンプトの LoRA タグ、手動 LoRA、ControlNet は、「LoRAs in the main pass」をオンにしない限り最初のパスだけに効きます。Hires fix はそのあとでメインのチェックポイントが行います。2 つのチェックポイントを順に読み込むので時間がかかります。txt2img 専用です。 |
| **Corridor Crawler Outpainting** | 廊下を後ろへ歩くように描き足します。各ステップで、直前の絵を廊下のプレハブ(内蔵のレンガの廊下、または好きな画像)の窓に縮めてはめ込み、その周りをインペイントします。マスクは窓から自動で作られるので、初期画像は普通のものを使ってください。深度モデルは不要で、インペイントできる SD1.5/SDXL のチェックポイントなら動きます。ノイズ除去強度は 0.8〜0.95 がおすすめです。各ステップのグリッドと、奥へ進むズームインの GIF も作れます。バッチサイズとバッチ数は 1 にしてください。img2img 専用です。 |
| **Latent Upscale** | 拡大前の画像をエンコードし、潜在を補間してから、指定したステップ数どおりにノイズ除去します。img2img 専用です。 |
| **img2img alternative test** | 初期画像のノイズを推定し、プロンプトを変えて画像を編集します(DDIM ノイズ反転)。img2img 専用です。 |
| **Poor man's outpainting** | 指定した方向に画像を広げ、広げた部分をインペイントします。img2img 専用です。 |
| **Outpainting mk2** | 色を合わせたノイズでキャンバスを広げてインペイントします(高めのノイズ除去強度、例 0.8 で使います)。img2img 専用です。 |
| **Depth aware img2img mask** | 深度を推定し、近い・遠いのしきい値の間だけをインペイントします。img2img 専用です。 |
| **Inpaint background** | 人物をマスクして、背景だけをプロンプトから描き直します。img2img 専用です。 |
| **Seamless tile inpainting** | タイルの端を中央に移し、その継ぎ目だけをインペイントします。img2img 専用です。 |

### 画像の加工

これらのスクリプトは、img2img では初期画像を、txt2img では生成した各画像を加工します。

| 名前 | 内容 |
|---|---|
| **Upscale (extras)** | 画像をアップスケーラーで拡大します。 |
| **Waifu2x upscale** | Anime / Photo / CARN V2 のモデルで 2〜16 倍に拡大します。重みは初回に取得します。 |
| **FSR-style upscale (CAS)** | AI を使わない高速な拡大です(Lanczos とシャープ化)。 |
| **Restore faces (extras)** | 画像に GFPGAN / CodeFormer をかけます。 |
| **Caption (BLIP / WD14 tags)** | 画像にキャプションを付け、生成情報と隣の `.txt` に書き込んだコピーを保存します。 |
| **Create flipped copies** | 左右・上下に反転したコピーを保存します。 |
| **Split oversized images** | 極端に横長・縦長の画像を、重なりのある目標サイズの断片に分けます。 |
| **Auto-sized crop** | 目標の面積と画像の縦横比に近い 64 の倍数のサイズを選び、切り抜いて縮小します。 |
| **Auto focal point crop** | 最も細部が多い領域を中心に、目標の幅 x 高さに切り抜きます。 |
| **Remove background** | 被写体を切り抜き、透過 PNG(必要ならマスクも)を保存します。rembg のモデルを使う場合は別途インストールが必要です。 |
| **Person mask** | 画像内の人物の白黒マスクを保存します。 |
| **Pixel art** | 縮小、減色、ディザリング、輪郭線でピクセルアートにします。 |
| **Color enhance** | 明るさと色相を保ったまま、彩度を全範囲に広げます。 |
| **Color correction** | 参照画像の色に合わせます。参照が空なら元の入力を使います。 |
| **NSFW Checker** | **NudeNet** では、NudeNet が検出した領域と、マスク画像の白い部分を検閲します。**Entire image (always)** は画像全体を検閲します。**No censor** と **Output only the mask** を組み合わせると、マスクだけを保存します。**NSFWJS** と **SD safety checker** では、問題があると判定した画像全体をぼかすか、真っ黒にします。**Only report** をオンにすると画像はそのままにし、判定結果をインフォテキストに書きます。 |
| **Aesthetic Score** | 選んだスコアラー(Aesthetic Image Scorer、Aesthetic Scorer、cafe-aesthetic)で画像を採点し、結果をインフォテキストに書きます。画素は変わりません。フォルダーを採点するには、Extras のディレクトリからのバッチを使います。モデルとキーは生成時のプラグインと同じです。 |

単体の画像の拡大と色補正は、**Extras** タブでも行えます。

### モデルツール

| 名前 | 内容 |
|---|---|
| **Checkpoint merger** | 2 つまたは 3 つのチェックポイントを、モデル A の隣に新しい `.safetensors` として合成します。**Generate** を押すと実行されます。 |
| **Checkpoint converter** | 精度、EMA、不要なキー、VAE などを整理したチェックポイントのコピーを書き出します。**Generate** を押すと実行されます。 |

### その他

| 名前 | 内容 |
|---|---|
| **Custom code** | エンジン内で Python を実行します。Settings → **Engine** の **Allow custom code** をオンにしたときだけ動きます。 |

## A1111 拡張インデックスの対応状況

A1111 / reForge の拡張インデックス(353 件)それぞれの対応状況は、GitHub の [docs/EXTENSIONS.md](https://github.com/kmizu/stablediffusion-nui/blob/main/docs/EXTENSIONS.md) にまとめています。状態は `done`(同梱済み)、`todo`(対応予定)、`na`(デスクトップアプリには当てはまらない)の 3 種類です。

拡張の中には、プラグインではなくアプリ本体の機能やタブとして実装したものもあります(ギャラリー、画像ブラウザ、キャラクター検索、パノラマ、Prompt Tools、Interrogator、Styles、Embedding 関連のタブ、表示言語など)。これらは[ツール](tools.html)、[画像ブラウザ](image-browser.html)、[キャラクター検索](character-searcher.html)、[パノラマ](panorama.html)、[プロンプトツール](prompt-tools.html)、[LoRA と Embedding](lora-embeddings.html)、[表示言語](localization.html)(Bilingual Localization 拡張と A1111 の言語パック)で説明しています。

## 外部プラグインを追加する

自分でプラグインを作って追加することもできます。

- `sdfx.core.plugins.api.Plugin` を実装し、`java.util.ServiceLoader` に登録した jar を作ります。
- 種類は AlwaysOn か Script のどちらかです。宣言したコントロールは GUI のフォームとして描画され、MCP にも JSON スキーマとして公開されます。
- jar をアプリフォルダの `plugins-ext/` に置き、アプリを再起動すると読み込まれます。
- 同梱プラグインと同じ id のものは読み込まれません(同梱側が優先されます)。

サンプルコードと手順は、README の[外部プラグインの作り方](https://github.com/kmizu/stablediffusion-nui#外部プラグインの作り方)を参照してください。
