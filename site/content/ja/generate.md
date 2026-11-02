---
title: 画像生成の基本
nav: 画像生成
description: Stable Diffusion NUI の txt2img、img2img、Inpaint、Hires. fix、バッチ、シード、サンプラー、スタイル、キュー、名前付き生成設定、PNG からのパラメーター復元の使い方を説明します。
---

SDNUI の生成画面には **txt2img**、**img2img**、**Inpaint** の 3 つのタブがあります。どのタブも左側に設定フォーム、右側にプレビューとギャラリーがあり、画面下部のバーで生成・キュー・中断を操作します。このページでは、3 つのタブに共通する設定と、それぞれのモードに固有の設定を順に説明します。

![txt2img タブ](shots/txt2img.png)

## 生成画面の構成

フォームは上から次の順に並びます。

| セクション | 内容 |
|---|---|
| **Prompt** | プロンプト、ネガティブプロンプト、LoRA・スタイルのツールバー |
| **Init image** / **Inpaint** | img2img と Inpaint のみ。入力画像とマスクの設定 |
| **Sampling** | サンプラー、スケジューラー、ステップ数、CFG Scale |
| **Size** | 幅・高さ、バッチ数とバッチサイズ |
| **Seed** | シードとバリエーションシード |
| **Named generation states…** | 設定一式に名前を付けて保存・復元 |
| **Settings presets** | 設定プリセット(詳しくは[ツール](tools.html)) |
| **Hires. fix** | txt2img のみ。2 段階目のアップスケールと描き込み |
| **ControlNet** | 制御画像による構図の誘導(詳しくは [ControlNet](controlnet.html)) |
| **Script** | X/Y/Z plot などのスクリプト(詳しくは[拡張機能](extensions.html)) |
| 各 AlwaysOn プラグイン | FreeU などの拡張機能。個別に ON/OFF |

入力に問題があると、フォームの先頭に `⚠` 付きのメッセージが表示されます(例: img2img で入力画像が未選択、Inpaint でマスクが未描画)。

![拡張機能のセクションまで含めた txt2img フォーム全体](shots/txt2img-form.png)

## プロンプト

上段がプロンプト、下段がネガティブプロンプトです。A1111 と同じ `(強調:1.2)` 記法と `BREAK` が使えます。

- 右上のカウンターは、おおよその CLIP トークン数と現在の 75 トークン単位のチャンク上限を示します。
- 括弧の対応が崩れていると、右下に警告が出ます。
- 文字列を選択して **Ctrl+↑** / **Ctrl+↓** を押すと、強調の重みを増減できます。選択がなければカーソル位置の単語が対象です。
- **Tags…** でローカルのファイルから候補を表示する設定ができます。タグ CSV（エイリアス付き）、翻訳 CSV（自分の言語でタグを検索）、追加タグ CSV、チャントの JSON ファイルが使えます。Enter か Tab で入力中の語を補完します。`__` でワイルドカードのファイル、`<` で LoRA・Embedding・チャントを補完します（LoRA は `<l:` / `<lora:` / `<lyco:`、Embedding は `<e:`、チャントは `<c:`）。LoRA の候補にはプレビュー画像が出て、その `.json` ファイルにあるトリガーワードも一緒に入ります。ダイアログでは、表示件数、アンダースコアと括弧の扱い、末尾のカンマ、エイリアスの表示も設定できます。

  **Tags…** の「補完の待ち時間」で、入力が止まってから候補を検索するまでの時間をミリ秒単位で指定できます。既定の 0 は即時検索です。ワイルドカードの Tab 補完は「候補の分岐まで」「次のフォルダまで」「常に全体を確定」から選べます。既定は「候補の分岐まで」です。ファイルを選ぶと中の行が候補に表示され、行を選んで直接挿入できます。Escape を押すと `__名前__` が残ります。行が多い場合は前後のボタンか PageUp・PageDown で切り替えます。

  タグやチャントにカテゴリ番号がある場合、候補に番号と色付きの印が表示されます。番号の意味はデータの提供元によって異なり、印の色は番号を見分けるためのものです。一部の番号には色が付きません。**Tags…** のカテゴリ配色 JSON では、CSV の拡張子を除いたファイル名ごとに、カテゴリ番号とダーク・ライトそれぞれの色を指定できます。例えば `{"danbooru":{"4":["lightgreen","darkgreen"]}}` と入力すると、`danbooru` のカテゴリ 4 に色が付き、同じファイルの省略したカテゴリには色が付きません。指定していないファイルには既定の色を使います。タグとチャントはタグ CSV、追加タグは追加タグ CSV の配色を使います。同じファイル名の CSV は同じ配色を共有します。

ツールバーの **✚ LoRA** と **Networks…** については [LoRA と埋め込み](lora-embeddings.html)を参照してください。

## スタイル

スタイルは、よく使うプロンプトとネガティブプロンプトの組み合わせに名前を付けたものです。`config/styles.csv` に保存され、A1111 と互換性があります。

1. プロンプト欄の下にある **Styles** の一覧からスタイルを選びます。
2. **⤓**(Apply the selected style to the prompts)を押すと、選んだスタイルがプロンプトに適用されます。
3. 今のプロンプトをスタイルとして保存するには **✎**(Save the current prompts as a style)を押し、名前を入力します。同じ名前があると、上書きしてよいか確認されます。

**Styles…** を押すと **Styles** タブが開き、スタイルの編集、カテゴリー分け、バックアップなどができます(詳しくは[ツール](tools.html))。

PNG から復元したパラメーターなどに生成時に適用するスタイルが含まれている場合は、**Styles applied at generation:** にその順番が表示されます。**Clear styles** で外せます。

![Styles タブ](shots/styles.png)

## サンプリング

| 項目 | 内容 |
|---|---|
| **Sampler** | サンプリング方式。エンジンが対応しているものだけが一覧に出ます(既定は `DPM++ 2M`) |
| スケジューラー | Sampler の右隣。ノイズのスケジュール。**Automatic** はサンプラーごとの既定値 |
| **Steps** | ノイズ除去のステップ数(1〜150)。20〜30 が一般的です |
| **CFG Scale** | プロンプトにどれだけ従うか(1〜30)。5〜8 が一般的です |

サンプラーは `DPM++ 2M`、`DPM++ SDE`、`DPM++ 2M SDE`、`Euler a`、`Euler`、`DDIM`、`UniPC`、`LCM` など、スケジューラーは `Automatic`、`Karras`、`Exponential`、`SGM Uniform`、`Align Your Steps`、`Beta` などがあります。A1111 の旧名(`DPM++ 2M Karras` など)で書かれたパラメーターも対応する組み合わせとして読み込まれます。

## サイズとバッチ

- **Width** / **Height**: 画像の幅と高さ(8 の倍数)。**⇅** で幅と高さを入れ替えます。**▦** からは 512×512、832×1216、1024×1024 などのサイズプリセットを選べます。SD1.5 は 512〜768、SDXL は約 100 万画素が目安です。
- **Batch** の **count**: バッチを何回続けて実行するか(1〜100)。
- **Batch** の **size**: 1 回のバッチで同時に生成する枚数(1〜16)。増やすと VRAM を多く使います。
- **Total** に「4 at once × 5 runs = 20 images」のように合計枚数が表示されます。

## シードとバリエーションシード

**Seed** に `-1` を入れると毎回ランダムなシードになります。**⚄** で `-1` に戻し、**♻** で直前に生成した画像のシードを再利用します。

**Variation seed** を開くと、元のシードに別のシードを混ぜて、構図を保ったまま少しだけ違う画像を作れます。

| 項目 | 内容 |
|---|---|
| **Enable variation** | バリエーションシードを使う |
| **Var. seed** | 混ぜるシード。**⚄** で `-1`(ランダム) |
| **Strength** | 0 で元のシードのみ、1 でバリエーションシードのみ |

**Enable variation** をオフにしても入力した値は残り、オンに戻すと再び使われます。

## img2img

**img2img** タブでは、入力画像をもとに新しい画像を生成します。

![img2img タブ](shots/img2img.png)

1. **Init image** に画像をドラッグ&ドロップするか、Ctrl+V で貼り付けるか、クリックしてファイルを開きます。プレビューの **⇢ img2img** で生成済みの画像を送ることもできます。
2. **Resize mode** で、入力画像を出力サイズに合わせる方法を選びます。**Just resize**(そのまま拡大縮小)、**Crop and resize**(切り抜いて合わせる)、**Resize and fill**(収めて余白を埋める)があります。
3. **Denoising** で変化の大きさを決めます。0 は入力画像のまま、1 は入力画像を無視します。

**WD v3 batch (partial)** は、フォルダ内の画像ごとに WD v3 でタグを作ってから img2img を実行する部分対応の機能です。モデルとタグ CSV は Settings の **WD v3 (partial)** タブで設定し、再起動が必要です。スクリプト、拡張機能、LoRA、ControlNet とは併用できません。詳しくは [Windows 配布版の説明](https://github.com/kmizu/stablediffusion-nui/blob/main/docs/WINDOWS_RELEASE.md#wd-v3-batch-interrogation-partial)を参照してください。

## Inpaint

**Inpaint** タブでは、画像の一部をマスクで指定して描き直します。

![Inpaint タブ](shots/inpaint.png)

### マスクを描く

1. 画像をドロップ、貼り付け(Ctrl+V)、または **…** から開きます。
2. **✎ Brush** を選び、描き直したい部分を左ドラッグで塗ります。
3. 塗りすぎたら **⌫ Eraser** を使うか、右ボタンでドラッグして消します。

| 操作 | 内容 |
|---|---|
| **↶** / **↷** | 元に戻す(Ctrl+Z)/ やり直す(Ctrl+Y) |
| **◑** | マスクを反転 |
| **Clear** | マスクをすべて消去 |
| **Brush size** | ブラシの直径。画像の上で Ctrl+マウスホイールでも変えられます |
| **Mask opacity** | マスクの表示の濃さ。表示だけで、生成には影響しません |

**Create mask from image difference** をオンにすると、手で塗る代わりに、元画像と加工後の画像の差分からマスクを作れます。

### マスクの設定

| 項目 | 内容 |
|---|---|
| **Mask blur** | マスクの境界をぼかす幅(ピクセル) |
| **Mask mode** | **Inpaint masked**(塗った部分を描き直す)か **Inpaint not masked**(塗った部分以外を描き直す) |
| **Masked content** | マスク部分の初期状態。**original** は元の色を保ち、**latent noise** は新しい内容を作ります。ほかに **fill** と **latent nothing** があります |
| **Inpaint area** | **Whole picture**(画像全体)か **Only masked**(マスク周辺を切り出し、フル解像度で生成して貼り戻す) |
| **Padding** | **Only masked** のときにマスクの周りに含める範囲(ピクセル) |
| **Resize mode** | 画像を出力サイズに合わせる方法(img2img と同じ) |
| **Denoising** | 変化の大きさ |

## Hires. fix

**Hires. fix** は txt2img のみの機能です。まず基本サイズで生成し、それを拡大してからもう一度描き込みます(A1111 の Hires. fix と同じ考え方です)。セクション見出しのチェックボックスで有効にします。進捗は画面下部の 2 本目のバーに表示されます。

| 項目 | 内容 |
|---|---|
| **Checkpoint** | 1 段階目と同じチェックポイントを使います(表示のみ) |
| **Upscaler** | 拡大方法。`Latent` 系は潜在空間のまま拡大し、ESRGAN 系などのモデルはデコードした画像を拡大します |
| **Upscale by** | 拡大率(1〜5)。**Resize W** / **Resize H** を指定すると無視されます |
| **Hires steps** | 2 段階目のステップ数。0 で Sampling と同じ |
| **Denoising** | 2 段階目でどれだけ画像を変えるか |
| **Resize W** / **Resize H** | 出力サイズを直接指定。0 なら **Upscale by** を使います |
| **Target** | Hires. fix 後の最終サイズ(例: `512×768  →  1024×1536`) |

拡大方法には `Latent`、`Latent (antialiased)`、`Latent (bicubic)`、`Latent (bicubic antialiased)`、`Latent (nearest)`、`Latent (nearest-exact)` の潜在空間アップスケーラーと、`Lanczos`、`Nearest`、`LDSR`、`models/upscalers` に置いた ESRGAN 系モデルなどのピクセルアップスケーラーがあります。

### 2 段階目だけの設定

**Advanced** を開くと、2 段階目だけに使う設定を指定できます。

- **Sampler** / **Schedule**: **Use same sampler** / **Use same scheduler** なら 1 段階目と同じです。
- **CFG**: 2 段階目の CFG Scale。0 で 1 段階目と同じです。
- Hires プロンプトと Hires ネガティブプロンプト: 空欄なら 1 段階目と同じプロンプトを使います。

### Hires Fix Tweaks

Hires. fix セクションには **Hires Fix Tweaks** の設定も含まれます。**Enable Hires tweaks** をオンにすると、次のことができます。

- **Hires batches and seed**: 1 枚の 1 段階目の結果から、2 段階目を 1〜16 回描き直します(**Hires batches**)。Hires 専用のシードとバリエーションシードも指定できます。シード欄は `0` で 1 段階目を引き継ぎ、`-1` でランダムです。**Reuse last Hires seed** などのボタンで、直前の Hires 画像のシードを再利用できます。
- **Hires prompt modes**: 2 段階目のプロンプトの組み立て方を **Default**、**Append**、**Prepend**、**Prompt S/R** から選びます。**Remove first-pass network tags from Hires** で、1 段階目の LoRA タグを 2 段階目から外せます。
- **Hires styles**: 2 段階目専用のスタイルを選んだ順に適用します。**Apply styles to Hires prompts** で Hires プロンプト欄に書き出せます。

これらの表示は Settings の **Hires** タブで切り替えられます(**Show Hires CFG**、**Show Hires batches and seed** など)。非表示にしても値は保持されます。同じタブで、Hires のプロンプトテンプレートを画像のメタデータに保存・復元するかどうかも設定できます。Hires の結果を別のフォルダに保存したい場合は、Settings の **General** タブの **Hires output folder** を指定します。

## img2img Hires Fix

**img2img Hires Fix** は AlwaysOn プラグインで、txt2img、img2img、Inpaint のどのタブでも使えます。生成が終わった各画像をピクセルのまま拡大し、VAE でエンコードし直してもう一度描き込みます。txt2img の潜在空間の Hires. fix とは別の処理で、両方を有効にすると Hires. fix の後に実行されます。

| 項目 | 内容 |
|---|---|
| **Pixel upscaler** | 拡大に使うアップスケーラー(既定は `R-ESRGAN 4x+`。対応するモデルが必要です) |
| **Hires steps (0 = inherit)** | ステップ数。0 で元の設定を引き継ぎます |
| **Denoising strength** | 変化の大きさ。0 だとピクセルを拡大するだけです |
| **Upscale by** | 拡大率 |
| **Resize width** / **Resize height** | 両方を指定すると拡大率より優先されます |
| **Sampler** / **Schedule** / **CFG** | None または 0 で元の設定を引き継ぎます |
| **Refinement prompt** / **Refinement negative prompt** | 空欄なら 1 段階目のプロンプトを使います |

## 生成とキュー

画面下部のバーで生成を操作します。

| 操作 | ショートカット | 内容 |
|---|---|---|
| **▶ Generate** | Ctrl+Enter | 今の設定で生成します。プロンプト入力中でも使えます |
| **■ Interrupt** | Esc | 生成中は Generate がこの表示になり、押すと中断します |
| **＋ Queue** | Ctrl+Shift+Enter | 今の設定のジョブをキューに追加します。待ち件数が **＋ Queue (2)** のように表示されます |
| **⨯** | | 待機中のジョブをすべてキューから取り除きます |

生成中に **▶ Generate** は使えないので、続けて別の設定で生成したいときは **＋ Queue** を使います。キューのジョブは追加した時点の設定で順に処理されます。

生成中はプレビューに途中経過(**LIVE PREVIEW**)が表示されます。表示の間隔と方式(`approx` / `taesd`)は Settings で変えられます。生成した画像は `outputs/txt2img/<日付>/` のようにモード別のフォルダに保存され、ギャラリーに並びます。

### 中断を防ぐ

**Prevent interruption** をオンにすると、実行中の作業が Interrupt、API や MCP からのキャンセル、明示的なエンジンの再起動で止められなくなります。長い処理を誤って止めたくないときに使います。

- 状態は横に **On** / **Off** で表示されます。
- 保護中でも、待機中のジョブをキューから取り除くことと、アプリを終了することはできます。
- この設定は保存されません。アプリを起動するたびに **Off** から始まります。

## 名前付き生成設定

**Named generation states…** を使うと、そのモードの設定一式に名前を付けて保存し、あとでまとめて復元できます。**Settings presets** が選んだ項目だけを上書きするのに対し、名前付き生成設定は保存した設定をすべて一緒に戻します。

1. txt2img、img2img、Inpaint のいずれかで設定を整え、**Named generation states…** を押します。ウィンドウの見出しに対象のモードが表示されます。
2. 名前を入力して **Save named state** を押します。同じ名前があると、置き換えてよいか確認されます。名前はモードごとに別々です。
3. 復元するときは一覧から選び、**Restore state** を押して確認します。共有のチェックポイント、VAE、Clip skip も切り替わります。復元しても生成は始まりません。
4. 不要になったものは選んで **Delete** で削除します。

保存されるのは、プロンプト、サンプラー、ステップ数、CFG、サイズ、バッチ数、シード、バリエーション設定、チェックポイント・VAE・Clip skip、Hires や img2img/Inpaint の設定、スクリプトと拡張機能の設定(無効なものも含む)、ControlNet ユニットと参照画像のパスなどです。今の入力画像と描いたマスクはそのまま残り、復元されません。画像やモデルのコピーは保存されません。

復元の前に、モデルや参照ファイルが揃っているかが確認されます。足りないものがあると復元全体が止まり、何が足りないかが表示されます。設定が黙って抜け落ちることはありません。

**History** タブは、Generate / Queue を押すたびにフォームを自動で記録します(モードごとに最新 50 件。最初の画像の 100 px サムネイル付き)。**Record every generation automatically** で記録を止められ、エントリの削除、**Clear history**、**Keep as named state**(名前付き生成設定へコピー)も使えます。保存先は `<アプリのフォルダ>/config/generation-history` です。

タブの下のチェックボックス(Prompts / Sampling, size, seed, hires / Checkpoint・VAE・clip skip / ControlNet / Script and extension controls / WD batch and layout)で、**Restore state** が戻す範囲をカテゴリ単位に絞れます。名前付き設定にも履歴にも使えます。すべてチェックなら全部を戻します。

保存先は `<アプリのフォルダ>/config/generation-states` です。**Reload** は外部での変更を読み直し、**Restore backup** はライブラリ全体を 1 つ前に保存した状態に戻します(選んだ項目やモードだけではありません)。

## PNG からパラメーターを復元する

SDNUI、A1111、reForge で生成した PNG には生成パラメーターが埋め込まれています。これを読み出して、同じ設定で生成し直せます。

![PNG Info タブ](shots/png-info.png)

- **PNG Info** タブに PNG をドロップすると、パラメーターが文字列と表で表示されます。**⇢ txt2img** で txt2img に復元し、**⇢ img2img** / **⇢ Inpaint** で画像とパラメーターをそのタブに送ります。
- プレビューに表示中の画像なら、**↺ Restore** で今のタブにパラメーターを復元できます。**⇢ img2img** / **⇢ Inpaint** は画像とパラメーターをそのタブに送ります。

記録されたチェックポイントが見つかれば自動で切り替わります。チェックポイントや VAE が見つからない場合は、今の選択のまま復元し、その旨が通知されます。

## キーボードショートカット

| キー | 動作 |
|---|---|
| Ctrl+Enter | 生成(プロンプト入力中でも可) |
| Ctrl+Shift+Enter | キューに追加 |
| Esc | 生成中なら中断 |
| Ctrl+V | img2img / Inpaint の画像エリアにクリップボードの画像を貼り付け |
| Ctrl+Z / Ctrl+Y(Ctrl+Shift+Z) | Inpaint のマスクの元に戻す / やり直す |
| Ctrl+↑ / Ctrl+↓ | プロンプトで選択範囲の強調の重みを増減 |
| ← / → | ギャラリーの前後の画像(プレビューにフォーカスがあるとき) |
| Delete | ギャラリーで選択中の画像を削除 |

macOS では Ctrl の代わりに Cmd を使います。
