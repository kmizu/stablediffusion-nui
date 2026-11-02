---
title: ツールタブ
nav: ツール
description: PNG Info、Extras、Comparison、Styles、Tokenizer、VAE Tools など、生成以外の作業に使うツールタブの使い方です。
---

メインウィンドウには、txt2img / img2img / Inpaint の生成タブのあとに、画像の確認や加工、モデルの管理に使うツールタブが並んでいます。このページでは、各タブの用途と基本的な使い方を説明します。

画像を受け付ける欄は、どのタブでも同じように使えます。ファイルやギャラリーのサムネイルをドラッグ＆ドロップするか、**Ctrl+V** でクリップボードから貼り付けるか、欄をクリックしてファイルを選びます。**✕** で画像を外せます。

!!! note "ほかのページで説明しているタブ"
    **Embedding Inspector**、**Embedding Editor**、**Embedding Merge** は [LoRA と埋め込み](lora-embeddings.html)、**ControlNet Fastload** と **ControlNet Fastload Filter** は [ControlNet](controlnet.html) のページで説明しています。**Image Browser** は[画像ブラウザ](image-browser.html)、**Character Searcher** は[キャラクター検索](character-searcher.html)、**Panorama** は[パノラマ](panorama.html)のページで説明しています。**Prompt Tools** タブ(保存プロンプト、履歴、タグボタンとカード、タグ編集)は[プロンプトツール](prompt-tools.html)のページで説明しています。


## PNG Info

PNG に埋め込まれた生成パラメータを読み出し、生成タブに戻すためのタブです。A1111、reForge、SDNUI で生成した PNG に対応しています。

![PNG Info タブ](shots/png-info.png)

1. **Image** 欄に PNG を入れます。
2. **Parameters** 欄に埋め込まれたテキストがそのまま表示され、その下の表にプロンプト、ネガティブプロンプト、各設定項目が **Field** / **Value** として並びます。
3. 下のボタンで送り先を選びます。
    - **⇢ txt2img**: パラメータを txt2img タブに復元します。
    - **⇢ img2img**: 画像とパラメータを img2img タブに送ります。
    - **⇢ Inpaint**: 画像とパラメータを Inpaint タブに送ります。

パラメータを持たない PNG では、PNG 内のほかのテキストチャンクが表示されます。テキストチャンクがまったくない場合は `(no text chunks)` と表示されます。

## Extras

画像のアップスケール、色補正、ControlNet プリプロセッサの実行を行うタブです。

![Extras タブ](shots/extras.png)

### アップスケール

1. **Image** 欄に画像を入れます。
2. **Upscaler** でアップスケーラーを選び、**Scale**（1〜8 倍、0.25 刻み）で倍率を決めます。
3. **⤢ Upscale** を押すと、結果が **Result** 欄に表示されます。

**Upscaler** には、Lanczos や Nearest などの組み込みの方法、Waifu2x、モデルフォルダに置いた ESRGAN 系のモデルファイルが並びます。モデルファイルは `models/upscalers` に置きます（`models/ESRGAN`、`models/RealESRGAN`、`models/SwinIR`、`models/ScuNET`、`models/DAT` も検索対象です）。Waifu2x の重みは初回使用時にダウンロードされます。

### 色補正

**Color correction** 欄では、アップスケールの結果（結果がなければ入力画像）の色を参照画像に合わせます。

1. 必要なら参照画像を入れます。空のままにすると、元の入力画像を参照に使います。
2. **Method**（**A1111** / **Wavelet** / **AdaIN**）と **Strength**（0〜1）を選びます。
3. **Apply color correction** を押します。**Also output other methods** をオンにすると、ほかの方法の結果も保存され、**Result** 欄の一覧から切り替えて見られます。

A1111 はヒストグラムと明るさ、AdaIN は画像全体の色の統計、Wavelet は局所的な色を合わせます。Wavelet は、参照画像の構図が違うとハロが出ることがあります。出力は RGB です。

### ControlNet Preprocessor

**ControlNet Preprocessor** 欄では、画像を生成せずに ControlNet のプリプロセッサだけを実行します（拡張機能「ControlNet Preprocessor in extras tab」に相当）。結果は、確認したり保存したり、ControlNet ユニットに渡したりできる制御画像です。

複数の画像を個別に選ぶには、出力フォルダを指定し、**画像を選択…** から画像を選んで **選択した画像を処理** を押します。一覧では不要な項目を外せます。処理順はファイル選択画面から返された順です。失敗した画像は報告し、残りを続けます。**Cancel** は処理中の画像を終えてから停止します。既存の出力は上書きせず、番号を付けて保存します。個別選択では入力と同じフォルダに保存できます。フォルダ一括では入力と出力を別にしてください。

処理中に画像保護セッションを変更すると、その画像の出力を公開せず一括処理を止めます。保存時は出力先に一時ファイルを作ります。保護が無効なとき、この一時ファイルは通常の PNG と同様にフォルダのアクセス権を持ち、コピー中にほかのプロセスから読める場合があります。遅い保存先ではコピー中の保護設定の切り替えが待たされます。一時ファイルを削除できなかった場合は処理結果に警告を残します。複数件あるとき、結果欄に残る詳しいパスと原因は最初の1件です。

1. **Module** を選びます。ControlNet ユニットと同じ一覧から `none` と `inpaint`（マスクが必要）を除いたものです。
2. **Pixel perfect** は画像の短辺を解像度にします。使わないときは **Preprocessor resolution**（64〜2048、既定 512）を指定します。解像度があるのは canny と検出系（openpose、lineart、softedge、mlsd、normal）だけで、ほかのモジュールでは非表示です。
3. スライダーはモジュールごとに変わります。canny は **Low threshold** と **High threshold**、recolor 系は **Gamma correction**、tile_resample は **Down sampling rate**、tile_colorfix は **Variation**、blur_gaussian は **Sigma**、Marigold 系は **Steps** です。
4. **◉ Run preprocessor** を押します。既定では元の画像を使い、**Use the current result as input** をオンにすると、アップスケールや色補正の結果に対して実行します（拡張機能を Extras の最後に動かすのと同じ順序です）。
5. PNG のパラメータには `ControlNet Preprocessor: "{'module': ..., 'processor_res': ..., 'threshold_a': ..., 'threshold_b': ...}", Size: WxH` が記録されます。入力画像のパラメータは `Source parameters` として残ります。

フォルダごと処理するときは、**入力フォルダ**と**出力フォルダ**を選んで **▶ Run on folder** を押します。`.png`・`.jpg`・`.jpeg`・`.webp`・`.bmp` を名前順に処理し、`<名前>-<module>.png` として保存します（上書きせず、再実行すると `-1`、`-2` が付きます）。失敗したファイルは数えて報告し、残りは続行します。**Cancel** は処理中の画像の後で止まります。出力フォルダは入力フォルダと別にしてください。

**⇢ txt2img ControlNet** と **⇢ img2img ControlNet** は、結果を選んだユニット（0〜2）の制御画像に入れ、処理済みなのでそのユニットのプリプロセッサを `none` にします。Depth と Marigold の重みは初回実行時にダウンロードされます。検出系（openpose、lineart など）は `controlnet_aux` が入っているときだけ一覧に出ます。

### 結果の利用

**Result** 欄のボタンで、結果を次の作業に回せます。

- **Send to Comparison**: 入力と出力を **Comparison** タブで比較します。
- **⇢ img2img** / **⇢ Inpaint**: 結果をそのタブの入力画像にします。
- **⧉**: 結果をコピーします。**▤**: 保存先のフォルダを開きます。

!!! note "顔の修復について"
    Extras タブ自体にはアップスケールと色補正だけがあります。顔の修復（Restore faces）、キャプション、反転コピーなどは、生成タブのプラグイン（**Extras** グループ）として生成画像に適用します。

## Comparison

2 枚の画像を重ねて、スライダーで境界を動かしながら比較するタブです。埋め込まれた生成パラメータの違いも確認できます。

![Comparison タブ](shots/comparison.png)

### 画像を読み込む

- 2 枚のファイルを選ぶ場合は、**A · first image** と **B · second image** に画像を入れ、**Compare upload** を押します。
- 直前の作業結果と比べる場合は、次のボタンを使います。
    - **Compare img2img** / **Compare Inpaint**: 入力画像と、表示中の img2img / Inpaint の結果を比べます。
    - **Compare Extras**: Extras の入力と出力を比べます。
    - **Compare previous**: txt2img の現在の結果と、ひとつ前の生成結果を比べます。
- **Selected → A** / **Selected → B** は、ギャラリーやプレビューで選んでいる画像を A / B に送ります。

元のファイルは変更されません。サイズの違う画像は、引き伸ばさずに合わせて表示されます。

### 操作

- 画像の上をドラッグすると境界が動きます。**Shift** を押しながらドラッグすると表示位置を動かせます。
- **Shift** + ホイール、または **+** / **−** で拡大・縮小し、矢印キーで表示位置を動かします。**Backspace** または **Shift+0** で表示をリセットします。
- **Reveal**（境界の位置）、**B opacity**（B の不透明度）、**Zoom** のスライダーと、**Pan** の矢印ボタンでも同じ操作ができます。
- **Horizontal slider (left / right)** で境界の向きを切り替え、**Swap A / B** で A と B を入れ替えます。**Reset view** は拡大率と位置だけを戻し、境界と不透明度はそのままにします。
- **Export PNG…** で、見えている比較画面を新しい PNG として保存します。

**Infotext · changed tags and fields are marked in red** を開くと、A と B のプロンプトと設定が並び、違うタグや項目が赤で表示されます。

### Comparison options

**Comparison options** では、ほかのタブに表示する送信ボタンの形（**Off** / **Text** / **Icon**）と、マウス操作による送信を設定します。**Ctrl+Alt+left/right click an image to send to A/B** をオンにすると、画像を Ctrl+Alt+左クリックで A に、右クリックで B に送れます。**Switch to Comparison after image shortcut** をオンにすると、送ったあと Comparison タブに切り替わります。

## Styles

プロンプトスタイル（`styles.csv`）を表形式で編集するタブです。モデルや画像サイズまで含む拡張プリセットも、同じ画面で扱えます。

![Styles タブ](shots/styles.png)

### 編集する対象を選ぶ

- 一番上の選択欄で **Prompt styles**（プロンプトスタイル）か **Extended presets**（拡張プリセット）を選びます。
- プロンプトスタイルでは、カテゴリ欄で **Master styles** か各カテゴリを選べます。
- 検索欄はすべての列を対象にします。検索方法は **Exact match** / **Case insensitive** / **regex** から選びます。
- 拡張プリセットでは、**Current model + VAE** をオンにすると、今のモデルと VAE に合うものだけを表示します。

### スタイルを編集する

- セルをダブルクリックすると編集できます。編集は少し間をおくか、欄から離れると自動で保存されます。
- 右側の **Selected style** には選んだスタイルの全項目が表示されます。ここで編集したときは **Save / merge changes** で保存します。
- ツールバーのボタン:
    - **New**: 空のスタイルを作ります。
    - **From prompts…**: 今のプロンプトとネガティブプロンプトからスタイルを作ります。
    - **Duplicate** / **Delete**: 選んだスタイルを複製・削除します。
    - **↑** / **↓**: 並び順を動かします。**Autosort** をオンにすると自動で並べ替えます。
    - **Move to…**: 選んだスタイルを別のカテゴリに移します。**New category…** でカテゴリを作ります。
    - **Apply**: 選んだスタイルを生成タブに適用します。
    - **Reload**: ファイルを読み直します。
- **Replace all** は、検索文字列をファイル全体（非表示の行も含む）で置き換えます。

キーボードでは、**D** で複製、**Delete** で削除、**M** でカテゴリ移動ができます。**Ctrl/Cmd+X/C/V** はセル単位で切り取り・コピー・貼り付けをします。プロンプトスタイルで名前が重なると、名前に `x` が付きます。

拡張プリセットはプロンプトスタイルとは別に保存され、適用するとプロンプト、サイズ、モデル、VAE だけを復元します。

### バックアップ

**Backups · prompt styles and categories** を開くと、バックアップを操作できます。変更されたプロンプトスタイルは 10 分ごとにバックアップされ、新しいものから 24 個が残ります。

- **Back up now**: すぐにバックアップします。
- **Restore**: 選んだバックアップ（CSV または `.aes`）から `styles.csv` とカテゴリを復元します。復元の前に、今のファイルがバックアップされます。
- **Export…**: 選んだバックアップ、または今のファイルを書き出します。
- **Encrypt future backups (.aes)** をオンにしてパスワードを入れると、以後のバックアップが暗号化されます。パスワードは保存されません。使用中の CSV は暗号化されません。

## Tokenizer

読み込んでいるモデルの CLIP トークナイザーで、テキストがどのトークンに分かれるかを確認するタブです。画像は生成しません。

![Tokenizer タブ](shots/tokenizer.png)

1. チェックポイントを読み込んでおきます。
2. 入力方法を **Text** か **Token IDs** から選びます。**Token IDs** では、カンマ区切りの整数を入れます。
3. テキストを入力するか、**Use current prompt** で表示中の生成タブのプロンプトをコピーします。
4. **Inspect tokens** を押します。

結果には、トークン数、BOS / EOS / PAD の ID、トークン ID の一覧、トークナイザーによるデコード結果、トークンごとの表（**ID**、**BPE piece**、**Display**、**UTF-8 bytes**、**Special**）が表示されます。色の付いたグループをクリックすると、その ID をコピーできます。**Copy all IDs** ですべての ID をコピーします。SDXL では 2 つのトークナイザーの結果が別々に表示されます。

!!! note "生成時のトークン化との違い"
    このタブは文字どおりにトークン化します。BOS / EOS は自動で付けません。強調記法、スケジュール、`BREAK`、LoRA タグ、埋め込みは展開しません。生成時にはプロンプトが分割や変換をされることがあります。

## VAE Tools

画像を VAE で潜在表現に変換したり、潜在表現から画像に戻したりするタブです。VAE による劣化の確認、潜在表現の編集、別の VAE でのデコードなどに使います。上部バーで選んでいるチェックポイントと VAE を使うので、先にチェックポイントを読み込んでおきます。

![VAE Tools タブ](shots/vae-tools.png)

1. **Operation** で **encode**、**decode**、**encode+decode** を選びます。
2. **Representation** で、潜在表現を保存する形式（**L** / **RGBA** / **CMYK**）を選びます。
3. **Approximation** で、デコードの方法（**Full** / **Approx NN** / **Approx cheap** / **TAESD**）を選びます。
4. **Choose files…** で入力ファイルを選ぶか、入力欄に 1 行に 1 つずつパスを書きます。
5. **Output folder…** で保存先を選び、**Run VAE Tools** を押します。

各形式の違いは次のとおりです。

- **L**: 潜在テンソルをそのまま収めたグレースケール PNG です。
- **RGBA** / **CMYK**: 潜在表現の 4 チャンネルを [-10, 10] の範囲で 8 ビットに量子化します。範囲外の値は切り詰められます。16 チャンネルの場合は 2×2 のグリッドになります。
- CMYK は **CMYK save format** で **TIFF** か **JPEG100** を選べます。TIFF はすべてのチャンネルを残しますが、JPEG100 では劣化します。

decode に使える入力は、手を加えていない L / RGBA の PNG、CMYK の TIFF / JPEG、`tensor.safetensors` です。L や RGBA の PNG は、サイズ変更、アルファの統合、非可逆形式への変換をしないでください。

デコードの方法:

- **Full**: 選択中の VAE を使います。
- **TAESD**: madebyollin/taesd または taesdxl を使います。初回は、キャッシュ済みの重みがあるか、**Allow optional model downloads** をオンにする必要があります。
- **Approx NN**: A1111 公式の重みを使い、潜在サイズの 2 倍でデコードします。重みのパスを欄に指定するか、ダウンロードを許可します。
- **Approx cheap**: 潜在サイズでデコードします。

エンコードできるのは **Full** と **TAESD** だけです。それ以外を選んだときのエンコードには Full が使われます。**Tiled VAE** をオンにすると、この処理だけ diffusers の VAE タイリングを使い、終わると元の設定に戻します。

実行中は **Interrupt** で中断を要求できます。結果と保存先は **Result metadata and saved paths** に表示されます。入力ファイルは変更されません。

!!! warning "画像スクランブルとの関係"
    画像スクランブルが有効なときも、L / CMYK の潜在ファイル、safetensors、レポートは読める状態で保存されます。エンコードするには **Allow readable plaintext latent files for this run** をオンにする必要があります。

## Metadata Utils

チェックポイントや LoRA の safetensors ファイルに入っているメタデータを確認し、編集したコピーを保存するタブです。モデルの重みは読み込みません。

![Metadata Utils タブ](shots/metadata-utils.png)

1. 種類を **Checkpoint** か **LoRA** から選び、ファイルを選びます。一覧が古いときは **Refresh** で読み直します。
2. **Load metadata** を押すと、メタデータが JSON として表示されます。
3. JSON を編集します。
4. 必要なら新しいファイル名（拡張子を除いた部分）を入れます。空のままにすると、元の名前に `_md` を付けた名前になります。
5. **Save copy** を押すと、元のファイルと同じフォルダに新しいファイルが保存されます。

- 既存のファイルは上書きされません。同じ名前のファイルがあると保存は拒否されます。
- テンソルのバイト列は変わりませんが、メタデータを変えるとファイルのハッシュは変わります。
- JSON の値は文字列として保存されます。文字列以外の値やオブジェクトは JSON 文字列として保存されます。空のオブジェクト `{}` を保存すると、コピーのメタデータが空になります。
- **Cancel** で、このメタデータ処理だけを取り消せます。

## Interrogator

手元にある画像から、プロンプトを推定するタブです。CLIP Interrogator と同じ仕組みで、キャプションモデル（BLIP）が画像を説明し、そのあと CLIP が画像との近さで、作家・画材・芸術運動・流行サイト・「フレーバー」の語句の一覧を順位づけします。ページは **Prompt**、**Analyze**、**Batch**、**About** です。

1. **CLIP model** を選びます。**ViT-L-14/openai** は Stable Diffusion 1.x と XL、**ViT-H-14/laion2b_s32b_b79k** は 2.x、**ViT-bigG-14/laion2b_s39b_b160k** は XL にも向きます。**Caption model** も選びます（既定は `blip-large`、VRAM が 12 GB 以下の GPU では `blip-base`。`none` は Analyze と negative モードでだけ意味があります）。
2. **Prompt**: 画像を入れ、モードを選んで **Generate** を押します。
    - **best**: 近い語句をつないでいき、キャプション・classic・fast・つなげた結果のうち画像にいちばん近いものを採ります。最も遅く、たいてい最も近い結果になります。
    - **fast**: キャプションのあとに、上位の語句を並べます。
    - **classic**: キャプション、媒体と作家、流行サイト、芸術運動、フレーバー 3 個の順に書きます。
    - **negative**: 画像に最も似ていない語句をつなぎます。ネガティブプロンプトとして使います。
   ステータス欄に、プロンプトと画像の近さが出ます。**Send to txt2img / img2img / inpaint / Extras** でプロンプト（img2img と inpaint では画像も）を送れます。negative の結果はネガティブプロンプトに入ります。
3. **Analyze**: 媒体・作家・芸術運動・流行・フレーバーの上位 5 件を、スコア付きで表示します。
4. **Batch**: フォルダ内のすべての `.png`、`.jpg`、`.jpeg` にプロンプトを付けます。出力は、画像ごとの `.txt`、1 つの `batch.txt`、`filename,prompt` 列の `batch.csv` から選べます。すでに `.txt` がある画像と、既存の `batch.txt`・`batch.csv` は、**Overwrite existing outputs** をオンにしない限り残ります。

- **Keep models loaded** をオンにすると、CLIP とキャプションモデルを実行間でメモリに置いておきます。オフなら実行のたびに解放します。**Unload** はすぐに解放します。**Cancel** はこの実行だけを取り消します。
- 初回は Hugging Face からモデルをダウンロードし、語句の埋め込みを計算します。Transformer モデルの埋め込みは設定フォルダの `clip-interrogator` に保存して再利用します。追加の OpenCLIP モデルは、重みと前処理に対応したキャッシュの安全性を確認するまで、読み込むたびに埋め込みを再計算します。
- 追加の OpenCLIP モデルを使うには、アプリが使う Python 環境へ `open_clip_torch==2.32.0` と必要な依存を別途インストールします。手順は `python/requirements.txt` にあります。候補が一覧に表示されても、すべてが動作確認済みとは限りません。選択時に、確認済みの範囲とモデルの入手元を表示します。モデルごとにライセンスや必要な環境が異なるため、入手元を確認してください。モデルの重みはアプリに同梱していません。
- 元の拡張機能とは違い、既定では何も上書きしません。従来のモデルは同じ重みの Hugging Face 変換版を使います。

## Model Presets

チェックポイントごとに生成設定のプリセットを保存し、生成タブに送るタブです（画面上の見出しは **Model Preset Manager**）。モデルページの URL、トリガーワード、サムネイルも管理できます。

![Model Presets タブ](shots/model-presets.png)

### モデルを選ぶ

チェックポイントを選んで **Retrieve model info** を押すか、**Use loaded model** で読み込み中のモデルを使います。プリセットはチェックポイントの SHA256 で区別されるので、初回はハッシュの計算に時間がかかります。

!!! note "Civitai との通信"
    **Retrieve model info** と **Use loaded model** は、保存済みの URL がないとき、Civitai にチェックポイントの SHA256 ハッシュ（64 文字）を送って情報を問い合わせます。モデルのファイル自体は送りません。自動の取得で更新されるのは URL、トリガーワード、未設定のサムネイルだけです。Civitai 上の共有プリセットを取り込むときは、**Download and overwrite info** を押します。

### プリセットを作る・使う

1. プリセット名を入れ、**Generation data** 欄に生成パラメータ（infotext）を書きます。**Use generated image parameters** を押すと、生成済みまたは選択中の画像のパラメータが入ります。**Append template** でひな形を追加することもできます。
2. **Save preset** で保存します。**Save as default** で既定のプリセットにし、**Rename preset** / **Delete preset** で名前の変更・削除をします。
3. **Send to txt2img** / **Send to img2img** / **Send to Inpaint** で、設定を生成タブに送ります。

保存していない編集内容はプリセットごとに保持されます。名前の変更は **Save preset** か **Rename preset** を押したときに反映されます。

### モデル情報と共有

- **Model page URL** にモデルページの URL を入れ、**Save URL / trigger words** で保存します。**Open model page** でブラウザで開きます。
- **Trigger words** には 1 行に 1 つずつトリガーワードを書きます。
- **Replace thumbnail…** でサムネイルを差し替えます。チェックポイントの隣に 300px の PNG が保存されます。**Reveal presets folder** で保存先を開きます。
- **Import JSON / sharing text** でプリセットの JSON ファイルや共有テキストを読み込み、**Export JSON** で JSON ファイルに書き出します。書き出しで既存のファイルは上書きされません。
- **Get sharing text** で共有用テキストを作り、**Copy sharing text** でコピーします。

## Model Keywords

チェックポイントや LoRA に対応するキーワード（トリガーワード）を確認・登録するタブです。ここで管理した対応表は、生成タブの **Model Keyword** プラグインがプロンプトにキーワードを入れるときに使います。

![Model Keywords タブ](shots/model-keywords.png)

1. 種類を **Checkpoint** か **LoRA** から選びます。LoRA の場合は、一覧から LoRA を選びます。チェックポイントは、上部バーで選んでいるものが対象になります。
2. **Check** で、登録されているキーワードを確認します。結果にはファイル名、ハッシュ、対応表の出どころが表示されます。
3. 独自のキーワードを登録するときは、キーワードを `|` で区切って入力し、**Save** を押します。**Delete** で独自の登録を削除します。

アップストリーム（mix1009/model-keyword）の対応表が同梱されているため、ネットワークがなくても検索できます。独自の登録は設定フォルダの `model-keyword` 内に保存されます。**Download upstream LoRA catalog…** は、固定版の LoRA カタログ（約 31 MB）を GitHub から設定フォルダにダウンロードします。既存のカタログは置き換えません。

生成タブの **Model Keyword** プラグインでは、キーワードの位置（**Keyword placement**）、チェックポイントのキーワードの選び方（**Checkpoint keywords**）、LoRA と倍率（**LoRA model**、**LoRA multiplier**）、LoRA のキーワード（**LoRA keywords**）、埋め込み（**Textual inversion embedding**）を設定します。

## Image Scrambling

画像のピクセルをパスワードで並べ替えて保存する機能です。アップストリーム拡張 sd-encrypt-image と互換の形式で読み書きします。**Session** と **Batch conversion** の 2 つのページがあります。

![Image Scrambling タブ](shots/image-scrambling.png)

!!! warning "安全な暗号化ではありません"
    これはピクセルの並べ替えで、安全な暗号化ではありません。メタデータは読める状態のまま残り、元画像のサムネイルを含むことがあります。見られては困る画像の保護には使わないでください。

### Session

このセッションで生成する画像をスクランブルして保存します。

1. パスワードを入れ、**Enable for this session** を押します。パスワードは保存されません。
2. 以後に始めたジョブの画像はスクランブルして保存され、アプリ内ではパスワードを使って普通に表示されます。
3. やめるときは **Turn off** を押します。以後のジョブは通常の画像を保存します。

- すでにキューに入っているジョブは、入れたときの設定のままです。
- 動画とその一時フレームはスクランブルされません。動画を書き出すには **Allow plaintext video exports for this session** をオンにします。この設定はセッションをオフにすると戻ります。
- クラッシュしたときは、一時入力ファイルが読める状態で残ることがあります。VAE の復旧用テンソルも読める状態で残ります。Custom Code は読める状態のファイルを保存できます。
- MCP サーバーはこのセッションとは別に動くため、セッションの設定は MCP には適用されません。

### Batch conversion

フォルダ内の画像をまとめてスクランブル、または復元します。

1. **Source folder** と **Output folder** を **Browse…** で選びます。
2. パスワードを入れ、処理を **Scramble images**（スクランブル）か **Restore images**（復元）から選びます。
3. **Start** を押します。途中で止めるときは **Stop after current image** を押します。

- 元のファイルは変更されません。出力先にすでにあるファイルはスキップされます。
- 出力は PNG です。復元してもピクセルが戻るだけで、元の JPEG や WebP のバイト列には戻りません。JPEG や WebP のメタデータの一部が失われることがあります。
- パスワードを検証する情報を持たない古い形式の画像を復元するには、**Restore legacy images even when the password cannot be verified** をオンにします。

## Temporal Lab

連番画像やフレーム列を処理するタブです。生成した連番画像を参照フレームに合わせて安定させる DFI、Deflicker、Fuse、動画からのフレーム抽出があります。Abysz LAB 拡張の画面と処理を参考にしていますが、元の実装との数値の完全な一致は保証していません。

![Temporal Lab タブ](shots/temporal-lab.png)

入力フォルダは読み取りだけで、変更されません。処理のたびに、**Output** 欄の **Output parent** の下に **New folder** で指定した名前の新しいフォルダが作られます。既存のファイルは上書きされません。

### DFI

**Reference frames**（参照フレーム）と **Generated frames**（生成フレーム）のフォルダを選び、**Source denoise**、**Tolerance**、**Expand**、**Mask smoothing**、**Inter denoise**、**Inter size**、**Inter frequency**、**Inter blur**、**Refresh frequency**、**Preserve %** を調整します。**Frame limit** を 0 にするとすべてのフレームを処理します。

- **Preview mask**: **Preview transition** で指定したフレームのマスクを **Mask preview** に表示します。
- **Run DFI**: 処理を実行します。

### Deflicker

**Frames** のフォルダを選び、**Temporal blend**、**Overlay**、**Normalize brightness** を設定して **Run deflicker** を押します。

### Fuse

**Style frames** と **Video frames** のフォルダを選び、**Strength** を設定して **Fuse frames** を押します。スタイルフレームは動画フレームのサイズに合わせてリサイズされます。

### Extract

**Video file** を選び、**FPS** を設定して **Extract frames** を押します。FPS を 0 にすると元の動画の FPS を使います。

### 結果の確認

**Output preview** で出力フレームを確認できます。**Play** / **Pause** / **Stop** で再生し、FPS を変えられます。実行中のジョブは **Cancel** で取り消しを要求できます。

## VRAM Estimator

読み込んでいる CUDA モデルで実際に生成を行い、画像サイズとバッチ数ごとの VRAM 使用量を測るタブです。測った結果は、生成タブの上部に表示される VRAM の見積もりに使われます。

![VRAM Estimator タブ](shots/vram-estimator.png)

1. チェックポイントを読み込んでおきます。
2. **Maximum side**（最大辺、256〜2048）、**Maximum batch**（最大バッチ数、1〜16）、**Steps** を設定します。
3. **Run calibration** を押します。txt2img、Hires（Lanczos）、Hires（Latent）、img2img の 4 種類の処理を、サイズとバッチ数を変えて実行します。
4. 結果は処理ごとのグラフ（横軸がピクセル数、縦軸が追加で確保された VRAM）に表示されます。

- **Stop calibration** で止められます。完了した測定は履歴に残ります。
- あるサイズでメモリ不足などの失敗が起きると、同じ処理・バッチ数のそれより大きいサイズは飛ばします。
- **Reload history** で保存済みの測定を読み直し、**Export profile…** / **Import profile…** で測定結果を書き出し・読み込みます。

生成タブでは、測定結果があると「Vanilla VRAM estimate」として見積もりが表示されます。測定範囲の外では「extrapolated」と付きます。

!!! note "見積もりの範囲"
    測定は拡張機能を使わない基本的な処理だけが対象で、拡張機能の分は含みません。限られた条件で測った結果なので、そのデバイスで生成できる上限を保証するものではありません。

## System Info

エンジンの実行環境、ライブラリ、モデルの一覧、GPU の状態を確認するタブです。ベンチマークも実行できます。情報が自動で外部に送られることはありません。

![System Info タブ](shots/system-info.png)

上部のボタン:

- **Refresh data**: 実行環境、ライブラリ、モデルの一覧を更新します。タブを表示している間は 5 秒ごとに自動で更新されます。
- **Hash inventory**: モデルファイルを読んで SHA256 の先頭部分を計算します。時間がかかります。
- **Export JSON…**: **Info JSON** で表示しているレポートを保存します。
- **Send interrupt**: 実行中のエンジンの処理（ベンチマークを含む）を中断します。

下のページ:

- **Overview**: 項目と値の表と、GPU の使用率とメモリ使用率のグラフです。
- **Models & Networks**: モデルやネットワークの一覧です。
- **Benchmark**: 固定シーンでのベンチマークです。**Level**（quick / normal / extensive / ludicrous）、**Steps**（turbo / default / long）、**Width** / **Height** を選び、**Run benchmark** を押します。**Warm up first (excluded from scores)** で事前のウォームアップを行います。結果はローカルの履歴に保存され、**Export benchmark JSON…** で書き出せます。
- **Info JSON**: レポート全体の JSON です。

**Optional legacy cloud submission** では、ベンチマーク結果を元の拡張の公開集計先に送れます。**Preview submission** で送る内容を確認し、**Submit previewed payload** を押したときだけ送信されます。受信の確認はできません。

### 画像メタデータへの書き込み

- **Hardware image metadata (opt-in)**: **Write hardware and time into future images** をオンにすると、以後に生成する PNG と Extras の PNG に、ハードウェア情報、所要時間、任意のメモ（**Remark**）が書き込まれます。
- **Image metadata (opt-in)**: 選んだシステム情報の項目を、以後に生成する PNG に書き込みます。すべて初期状態ではオフです。
