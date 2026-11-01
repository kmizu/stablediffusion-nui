---
title: LoRA とエンベディング
nav: LoRA とエンベディング
description: Stable Diffusion NUI で LoRA と Textual Inversion エンベディングを使う方法と、Embedding Inspector・Embedding Merge・Embedding Editor の各タブの使い方を説明します。
---

このページでは、LoRA と Textual Inversion エンベディング(以下エンベディング)をプロンプトで使う方法と、エンベディングを調べたり作ったりするための専用タブを説明します。

## ファイルの置き場所

| 種類 | フォルダ |
|---|---|
| LoRA | `models/lora/` |
| エンベディング | `models/embeddings/` |

サブフォルダも検索されます。既存の A1111 / reForge を使っている場合は、Settings → **Model folders** → **Add A1111/reForge install...** でインストール先を選ぶと、`models/Lora`、`models/LyCORIS`、`embeddings` などがまとめて読み取り専用で追加されます。コピーは不要です。

## LoRA

### プロンプトに LoRA を入れる

LoRA はプロンプトに `<lora:名前:重み>` タグを書いて使います。A1111 と同じ書き方です。名前はファイル名から拡張子を除いたものです。

```
1girl, garden <lora:myStyle:0.8>
```

タグを手で書く代わりに、プロンプト欄の下にあるツールバーから入れることもできます。

- **✚ LoRA**: 検索できる LoRA の一覧が開きます。選ぶと、カーソル位置に重み 1 の `<lora:名前:1>` タグが入ります。一覧が空のときは「No LoRAs found」と表示されるので、`models/lora` にファイルを置くか、Settings でフォルダを追加してください。
- **Networks…**: プレビュー画像つきのブラウザが開きます(次の節)。

### タグの書き方

タグには重みのほかに追加の引数を書けます。

| 書き方 | 意味 |
|---|---|
| `<lora:名前:0.8>` | 重み 0.8(テキストエンコーダーと U-Net の両方) |
| `<lora:名前:0.8:0.5>` | テキストエンコーダー 0.8、U-Net 0.5 |
| `<lora:名前:te=0.8:unet=0.5>` | 上と同じ指定を名前つき引数で書いたもの |
| `<lora:名前:1:lbw=OUTALL>` | ブロックごとの重み(LoRA Block Weight。下記) |
| `<lora:名前:1:start=5:stop=15>` | 適用するステップの範囲 |
| `<lyco:名前:0.8>` | `<lora:...>` と同じ扱い |

同じ LoRA を 2 回書くと、重みが足し合わされます。

### Networks ブラウザ

**Networks…** を押すと **Models and networks** ウィンドウが開き、モデルをプレビュー画像つきのカードで一覧できます。

- 上の種類の選択(**all**、**checkpoint**、**lora**、**embedding**、**vae**、**controlnet**、**hypernetwork**)と **Search name or path** 欄で絞り込めます。
- LoRA とエンベディングのカードには **Insert** ボタンがあり、ブラウザを開いたプロンプト欄のカーソル位置に挿入します。LoRA は `<lora:名前:1>` タグ、エンベディングは名前が入ります。ブラウザを開いたあとでプロンプトやカーソル位置を変えると挿入できなくなるので、その場合は **Networks…** を開き直してください。
- チェックポイントのカードには **Select** ボタンがあり、そのモデルを読み込みます。
- プレビュー画像は、モデルと同じフォルダにある `名前.preview.png` や `名前.png` などを自動で探します。**Choose preview…** で別の画像を指定でき、**Remove override** で指定を外せます。**Refresh previews** で一覧を読み直します。
- **Named NSFW previews** メニューでは、プレビュー画像のファイル参照に「nsfw」を含むものを表示・ぼかし・非表示のどれにするかを選べます。この切り替えはアプリを終了するまでの一時的なものです。
- **Inspect-only folders…** では、ブラウザで眺めるだけのフォルダを `種類=フォルダ` の形で 1 行に 1 つずつ追加できます。生成に使うモデルフォルダは変わりません。

### Manual LoRAs

PNG から生成パラメータを復元したときなど、プロンプトのタグとは別に LoRA の指定が含まれている場合は、プロンプト欄の下に **Manual LoRAs (kept when Hires removes prompt tags)** として一覧が出ます。名前とテキストエンコーダー・U-Net の重みが表示され、**Remove** で個別に外せます。Hires Fix Tweaks の **Remove first-pass network tags from Hires** をオンにしても、ここにある LoRA は残ります。

### LoRA Block Weight

AlwaysOn プラグインの **LoRA Block Weight** を使うと、LoRA の U-Net ブロックごとに重みを変えたり、LoRA を効かせるステップの範囲を決めたりできます。

1. 生成タブの下のほうにある **LoRA Block Weight** の欄を開き、見出しのチェックボックスをオンにします。
2. **Rules** に、1 行に 1 つずつ `LoRA名 : プリセットまたは重み [: start=N] [: stop=N]` を書きます。LoRA 名に `*` を書くと、すべての LoRA に適用されます。

```
* : OUTALL
myStyle : 1,0,0,0,1,1,1,1,1,1,1,1,0,0,0,0,0 : stop=0.5
```

- 用意されているプリセットは NONE、ALL、ALL0.5、INS、IND、INALL、MIDD、OUTD、OUTS、OUTALL です。独自のプリセットは `config/lbwpresets.txt` に `名前:w1,w2,...` の形で 1 行に 1 つずつ書けます。
- 重みの数は SD1.x が 17 個(BASE IN01 IN02 IN04 IN05 IN07 IN08 MID OUT03 OUT04 OUT05 OUT06 OUT07 OUT08 OUT09 OUT10 OUT11)、SDXL が 12 個(BASE IN04 IN05 IN07 IN08 MID OUT00 OUT01 OUT02 OUT03 OUT04 OUT05)です。BASE はテキストエンコーダーです。
- start / stop にはステップ数か、0〜1 の割合を書きます(stop=0.4 なら全ステップの 40%)。
- プロンプトのタグに自分で `lbw=` を書いた場合は、そちらが優先されます。
- X/Y/Z plot では **LoRA block weight (all LoRAs)** 軸でプリセットを比べられます。

### Composable LoRA / LyCORIS

AlwaysOn プラグインの **Composable LoRA / LyCORIS** を有効にすると、`AND` で区切ったプロンプトの各部分に、その部分に書いた LoRA だけが効くようになります。

```
a red fox <lora:Fox:0.8> AND a white owl <lora:Owl:0.7>
```

- `AND` は画像の予測を組み合わせるもので、画面の領域を割り当てるものではありません。部分が増えるほど、その数だけモデルの評価回数が増えます。
- **Composable LoRA with steps** をオンにすると、`[<lora:A:1>::10]`(10 ステップで終了)、`[<lora:A:1>:10]`(10 ステップから開始)、`[<lora:A:1>|<lora:B:1>]`(交互に切り替え)のように、ステップに応じて LoRA を切り替えられます。
- **Plot effective LoRA weights** をオンにすると、実際に効いた重みのグラフがギャラリーに追加されます。
- このプラグインを使っているときは、LoRA Block Weight の `lbw=` や `start=` / `stop=` の指定は使えず、エラーになります。

!!! note "LoRA ごとに生成を繰り返す"
    Script の **LoRA queue** を使うと、選んだ LoRA ごとにプロンプトを 1 回ずつ生成し、グリッドにまとめます。詳しくは[拡張機能](extensions.html)を参照してください。

## Textual Inversion エンベディング

エンベディングは、ファイル名から拡張子を除いた名前をプロンプト(またはネガティブプロンプト)にそのまま書いて使います。

```
masterpiece, 1girl, myEmbedding
```

- エンベディングは、プロンプトに名前が出てきたときに初めて読み込まれます。
- SD1.5 用のエンベディングを SDXL のモデルで使うと(またはその逆)、読み込まれずに警告が出ます。SDXL 用のエンベディングには `clip_l` と `clip_g` の 2 つのベクトルが必要です。
- Networks ブラウザの **Insert** でも名前を挿入できます。

## Embedding Inspector タブ

![Embedding Inspector タブ](shots/embedding-inspector.png)

**Embedding Inspector** タブでは、読み込み中のモデルのトークンやエンベディングのベクトルを調べたり、複数のベクトルを混ぜて新しいエンベディングを保存したりできます。先にチェックポイントを読み込んでおいてください。

### ベクトルを調べる

1. 上の欄にトークン、`#` つきのトークン ID、またはエンベディング名を入力します。
2. **Inspect** を押します。

結果欄には、ベクトルごとの大きさ(magnitude)、最小値・最大値と、語彙の中で近いトークンとそのスコアが表示されます。グラフには選んだエンコーダーのベクトルが最大 16 本表示され、SDXL では **Graph encoder** で `clip_l` と `clip_g` を切り替えられます。

- **List embeddings**: 読み込み済みのエンベディングと、カタログにあるが未読み込みのものを分けて一覧します。
- **Export vector…**: ベクトルの値を UTF-8 のテキストファイルに書き出します。

### ミニトークナイザー

2 段目の欄に短いプロンプトを入れて **Tokenize** を押すと、特殊トークンを除いてトークンに分割します。**Send token IDs to mixer** を押すと、そのトークン ID がミキサーの行として入ります。

### ミキサーで新しいエンベディングを作る

1. **Mixer** 欄に、1 行に 1 つずつトークンかエンベディング名を書きます。`cat|0.5` のように `|` のあとに重みを付けられます。SDXL では `#clip_l の ID/#clip_g の ID` の形で 2 つのエンコーダーの ID を組にして指定します。
2. 必要に応じて **Concatenate vectors**(ベクトルをつなげる)や **Combine as one vector**(1 本のベクトルにまとめる)をオンにします。
3. **Output file…** で保存先(`.safetensors` か `.pt`)を選びます。**Optional training step** に学習ステップ数を記録することもできます。
4. **Save mixed** を押します。既存のファイルに上書きするときは **Allow overwrite** をオンにします。

保存したエンベディングは読み込み中のモデルにそのまま読み込まれ、モデル一覧も更新されます。設定済みのエンベディングフォルダの外に保存したファイルは、このセッションの間だけ有効です。**Reset mixer** でミキサーの行とオプションを消せます。

### Eval 式

**Eval preset**(Boost、Digitize、Binary、Randomize、Sine、Comb、Crop_high、Crop_low)を選ぶか、Python の式を直接書くと、保存時にベクトルへ式を適用します。**Save all eval presets** をオンにすると、すべてのプリセットで保存します。

!!! warning "Eval は任意の Python を実行します"
    Eval の式は、あなたのユーザー権限で任意の Python コードとして実行されます。使うには Settings の **Allow custom code** をオンにして、エンジンを再起動する必要があります。信頼できない式は実行しないでください。

## Embedding Merge タブ

![Embedding Merge タブ](shots/embedding-merge.png)

**Embedding Merge** タブでは、プロンプトやエンベディングの CLIP ベクトルを式で足し引きして、新しいエンベディングを作れます。

### 式を調べる

1. 入力欄にプロンプト、`'red hair' * 0.7 + 'blue hair' * 0.3` のような式、または PNG の生成情報(infotext)を貼り付けます。**Use current prompt** で現在のポジティブプロンプトをコピーすることもできます。
2. 区切り方を選びます(**none**、**comma**、**parts**、**words**、**tokens**、**vectors**。既定は **parts**)。
3. **Inspect / restore** を押します。

結果はエンコーダーごとのタブに表で表示され、行ごとのテキスト、トークン、最小値・最大値・合計・絶対値の合計・ノルム・標準偏差がわかります。貼り付けたのが PNG の生成情報なら、中の式を復元します。

### 式の書き方

- テキストやエンベディング名は引用符で囲みます。
- `+` / `-` で行を組み合わせ、`*` / `/` で倍率をかけます。
- `:N` で切り詰めまたは埋め、`:+N` / `:-N` で回転します。
- 修飾子の前に `=` を付けると、それまでの結果全体に適用されます。SDXL では末尾に `L` / `G` を付けてエンコーダーを指定します。
- カンマでテキストやトークン ID を追加します(`00` は埋め、`000` は終端、`0000` は開始)。

生成のプロンプトでも `<'式'>` か `{'式'}` の形で式を使えます。**Use in prompt** で、入力した式を現在のポジティブプロンプトに挿入できます。

### 保存する

1. 新しいファイル名を入力します。既存のファイルは上書きされません。
2. 保存先フォルダを確認します。既定ではエンベディングフォルダの中の `embedding_merge` です。
3. **Safetensors + SD1/SDXL conversion** は既定でオンです。オフにすると `.pt` で保存します。変換したコピーは `sd1/` または `sdxl/` に置かれます。
4. **Save new embedding** を押します。

保存後は、現在のモデルに合う主ファイルだけが有効になります。実行中の処理は **Cancel** で止められますが、保存がすでに確定していた場合は取り消せません。

## Embedding Editor タブ

![Embedding Editor タブ](shots/embedding-editor.png)

**Embedding Editor** タブでは、エンベディングのベクトルの各値をスライダーで直接編集できます。

1. 一覧からエンベディングのファイルを選ぶか、読み込み済みのエンベディング名を入力します。一覧が古いときは **Refresh list** を押します。未保存の編集は消えません。
2. **Open / refresh** を押します。まだ読み込まれていないファイルを開くと、現在のモデルに読み込まれます。
3. **Encoder**、**Vector**、**Page** で編集する部分を選びます。1 ページに 24 個の値が並び、スライダーを動かすか数値を直接入力して変更します。
4. **Preview** を押すと、未保存の編集を反映した画像を生成キューに入れます。最後に開いていた生成タブの設定がそのまま使われます。Script や WD v3 バッチを選んでいるときはプレビューできません。
5. 保存するときは **Overwrite the loaded embedding file** をオンにしてから **Save** を押します。元のファイルのバックアップを作ってから上書きし、エンベディングを読み込み直します。保存後に続けて編集するときは開き直してください。

- 編集内容は保存するまでメモリ上にだけあります。**Reset vector** で表示中のベクトルの編集を、**Reset all** ですべての編集を取り消せます。
- **Guidance** 欄に `単語:#RRGGBB, 単語:#RRGGBB` の形で書いて **Update guidance** を押すと、各単語の最初のトークンの位置が色つきの目印としてスライダーの下に表示されます。編集内容は消えません。

## Model Keywords と Model Presets

モデルごとのキーワードやプリセットを扱う機能もあります。ここでは概要だけを説明します。詳しくは[ツール](tools.html)を参照してください。

- **Model Keywords** タブでは、チェックポイントや LoRA に対応するキーワード(`|` 区切り)を確認・保存・削除できます。AlwaysOn プラグインの **Model Keyword** が、生成時にそのキーワードをプロンプトに入れます。このプラグインは既定で有効です。

![Model Keywords タブ](shots/model-keywords.png)

- **Model Presets** タブ(**Model Preset Manager**)では、チェックポイントごとに生成設定のプリセット、トリガーワード、モデルページの URL、サムネイルを管理できます。

![Model Presets タブ](shots/model-presets.png)
