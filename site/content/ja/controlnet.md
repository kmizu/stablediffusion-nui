---
title: ControlNet
description: Stable Diffusion NUI の ControlNet ユニットの設定と、ControlNet Fastload による ControlNet 設定の読み込み・保存・閲覧の方法を説明します。
---

ControlNet は、線画・深度・ポーズなどの制御画像を使って、生成する画像の構図を誘導する機能です。SDNUI では txt2img、img2img、Inpaint の各タブにある **ControlNet** セクションから使います。

後半では、ControlNet の設定を画像や `.cni` ファイルに保存して読み込み直す **ControlNet Fastload** を説明します。

## 準備

ControlNet モデルは `models/controlnet/` に置きます。既存の A1111 / reForge のフォルダを追加した場合は、その `models/ControlNet` も読み込まれます(Settings → **Model folders**)。

プリプロセッサーのうち、`none`、`invert`、`inpaint`、`recolor_luminance`、`recolor_intensity`、`tile_resample`、`tile_colorfix`、`blur_gaussian`、`canny`、`depth`、`depth_marigold`、`normal_marigold` は標準の環境で使えます。`openpose`、`lineart`、`lineart_anime`、`softedge_hed`、`mlsd`、`normal_bae` は、任意パッケージの `controlnet_aux` を入れた場合だけ一覧に出ます(`python/requirements.txt` にコメントとして記載されています)。

## ユニットの設定

**ControlNet** セクションには **Unit 1**、**Unit 2**、**Unit 3** の 3 つのタブがあり、最大 3 ユニットを同時に使えます。有効なユニットのタブには `●` が付き、セクション見出しには有効なユニット数(例: `2 active`)が表示されます。

![ControlNet と拡張機能のセクションを含む txt2img フォーム](shots/txt2img-form.png)

1. 使うユニットのタブを開き、**Enable** をオンにします。
2. 制御画像をドロップするか、貼り付けるか、クリックして開きます。img2img と Inpaint では、空欄にすると入力画像(Init image)が制御画像として使われます。
3. **Model** で ControlNet モデルを選びます。
4. **Preprocessor** で、制御画像を制御マップに変換する方法を選びます。すでに線画や深度マップを用意している場合は `none` にします。
5. 必要に応じて **Weight**、**Start**、**End**、**Control mode** を調整し、生成します。

| 項目 | 内容 |
|---|---|
| **Enable** | このユニットを使う |
| **Model** | ControlNet モデル(`models/controlnet` から) |
| **Preprocessor** | 制御画像を制御マップに変換する方法。`none` は変換しない |
| **Resize mode** | 制御画像を出力サイズに合わせる方法。**Just resize**、**Crop and resize**、**Resize and fill** |
| **ControlNet-compatible processing** | A1111 の ControlNet 拡張と同じ方法で処理する(下記) |
| **Control mode** | プロンプトと ControlNet のどちらを重視するか(下記) |
| **Processor res** | canny の入力解像度(下記) |
| **Pixel perfect** | canny の入力解像度を出力サイズから自動で決める |
| **Weight** | 制御の強さ(0〜2) |
| **Start** | 全ステップのうち、制御を始める位置(0〜1) |
| **End** | 全ステップのうち、制御を終える位置(0〜1) |

有効なユニットにモデルが選ばれていない場合、txt2img で制御画像がない場合、**Start** が **End** より大きい場合は、フォームの先頭に警告が出て生成できません。

### Control mode

| モード | 効果 |
|---|---|
| **Balanced** | 標準。プロンプトと ControlNet を均等に扱います |
| **My prompt is more important** | 制御を弱め、プロンプトが主導するようにします |
| **ControlNet is more important** | 制御をプロンプト側(条件付き)の計算にだけかけ、ControlNet の影響を強めます |

SDXL では、**Balanced** 以外の 2 つのモードはどちらも構図への効き方がかなり弱くなります。これは A1111 の ControlNet 拡張と同じ計算によるものです。

### canny の解像度と Pixel perfect

**Processor res** と **Pixel perfect** は、プリプロセッサーが `canny` のときに効きます。`none` と `invert` は A1111 と同じくこの設定を無視し、それ以外のプリプロセッサーでは欄が無効になります。

- **Processor res** は `-1` か 64〜2048 です。`-1` のときは元のサイズのまま処理します。**ControlNet-compatible processing** がオンなら 512 になります。
- **Pixel perfect** をオンにすると、出力サイズから入力解像度を決めます。

### ControlNet-compatible processing

オンにすると、A1111 の ControlNet 拡張と同じ方法で処理します。具体的には、制御マップの補間とエッジの細線化、**Processor res** での canny 処理を A1111 と同じ方法で行い、Hires. fix の 2 段階目では canny と MLSD の制御を弱めます。オフのときは、従来の SDNUI の処理のままです。ControlNet Fastload で読み込んだユニットは、常にこの処理を使います。

### プリプロセッサーの結果を確認する

**◉ Preview preprocessor** を押すと、今の制御画像にプリプロセッサーをかけた結果が **Preprocessor result** として表示されます。生成する前に、線の拾い方や深度の様子を確かめられます。制御画像やプリプロセッサーを変えると、表示は消えます。

## ControlNet Fastload

ControlNet Fastload は、生成に使った ControlNet ユニットの設定と制御画像を保存しておき、あとで読み込んで同じ設定を再現する機能です。A1111 用の拡張機能 sd-webui-controlnet-fastload の動作を参考に作り直したもので、保存するファイルは元の拡張機能と同じ形式です。

次の 3 か所から使います。

- 生成タブの AlwaysOn プラグイン **ControlNet Fastload**: 生成のたびに自動で読み込み・保存します。
- **ControlNet Fastload** タブ: ファイルの中身を確認し、ユニットを生成タブに送ります。
- **ControlNet Fastload Filter** タブ: フォルダの画像を ControlNet のメタデータで絞り込みます。

!!! note "ファイルの安全性"
    Fastload のデータは pickle 形式ですが、SDNUI は pickle を実行せず、許可した範囲の構造だけを読み取ります。

### 生成時に読み込み・保存する(AlwaysOn プラグイン)

txt2img、img2img、Inpaint の各タブに **ControlNet Fastload** セクションがあります。見出しのチェックボックスで有効にし、次の項目を設定します。

| 項目 | 選択肢 | 内容 |
|---|---|---|
| **Mode** | `load_only` | Load only。生成の前に、指定したファイルからユニットを読み込みます |
| | `save_only` | Save only。生成した画像ごとに、使ったユニットを保存します |
| | `load_save` | Load & Save。読み込みと保存の両方を行います |
| **Priority** | `current_first` | Current first。今のフォームに有効なユニットがあればそちらを使い、ファイルは読みません |
| | `loaded_first` | Loaded first。ファイルから読み込んだユニットを使います |
| **Input image or .cni path** | | 読み込む Fastload 画像か `.cni` ファイルのパス |
| **Save format** | `embedded` | 生成した画像の末尾に埋め込みます |
| | `sidecar` | 画像と同じ名前の `.cni` ファイルを隣に作ります |
| | `both` | 埋め込みと `.cni` の両方 |

読み込むとき、画像に空でない埋め込みデータがあれば、同じ名前の `.cni` よりそちらが優先されます。ファイルに記録されたモデル名に A1111 形式の `[hash]` が付いていても、SDNUI のモデル一覧の該当モデルとして扱われます。

### ControlNet Fastload タブ

**ControlNet Fastload** タブでは、Fastload 画像や `.cni` ファイルの中身を確認し、ユニットを生成タブに送ったり、別のファイルとして保存したりできます。

![ControlNet Fastload タブ](shots/controlnet-fastload.png)

1. 上の欄にファイルのパスを入れるか、**Browse** で選び、**Inspect** を押します。保存されている設定が下の欄に表示されます。適用できない設定があれば、ここで確認できます。
2. ユニット番号を選び、**Preview images** を押すと、そのユニットの制御画像とマスクが表示されます。
3. **Send unit to txt2img** / **Send unit to img2img** を押すと、送り先タブの同じ番号のユニットが置き換わります。今の ControlNet フォームで適用できない設定を含むユニットは送れません。

今の生成タブのユニットを取り込むには、**Capture txt2img** / **Capture img2img** を押します。取り込んだ内容はメモリ上にだけ保持されます。新しいファイルとして保存するには、**New .cni file** の欄に保存先を入れて **Save .cni copy** を押します。既存のファイルは上書きされません。

### ControlNet Fastload Filter タブ

**ControlNet Fastload Filter** タブでは、フォルダ内の画像を ControlNet のメタデータで絞り込み、生成タブに送れます。

1. **Image directory** にフォルダを入れるか **Browse** で選び、**Scan** を押します。
2. **Filter field** と **Filter value** を選んで **Add filter** を押します。条件は複数追加でき、すべてに一致する画像だけが表示されます。条件を外すには一覧で選んで **Remove filter** を押します。
3. 結果は 1 ページ 36 枚のサムネイルで表示されます。**First**、**Previous**、**Next**、**Last**、またはページ番号を入れて **Go** で移動します。
4. 画像を選ぶとプレビューが表示されます。選んだ画像に対して次の操作ができます。

| ボタン | 内容 |
|---|---|
| **Inspect Fastload data** | 画像に埋め込まれたデータ、または同じ名前の `.cni` を ControlNet Fastload タブで開きます |
| **Send to txt2img** / **Send to img2img** | 画像とメタデータを送ります |
| **Send parameters to txt2img** / **Send parameters to img2img** | 入力画像は変えずに、メタデータだけを適用します |
| **Set ControlNet image (txt2img)** / **Set ControlNet image (img2img)** | Unit 1 の制御画像だけを差し替えます。ほかの値は変わりません |

フォルダの中のシンボリックリンクと大きすぎる画像はスキャンの対象外になり、その件数がステータスに表示されます。

!!! note "SDNUI で生成した画像は絞り込めません"
    絞り込みの選択肢は、メタデータに A1111 形式の `ControlNet N:` という記録がある画像(A1111 や Forge の出力)からだけ作られます。SDNUI はこの記録を書かないため、SDNUI で生成した画像は絞り込みの対象になりません。

### 適用できない設定

SDNUI で正しく再現できない設定を含むユニットは、黙って設定を捨てるのではなく、どの設定が原因かを示して拒否します。次のようなものが該当します。

- Inpaint 用や CLIP 用のマスク
- バッチ入力
- 既定値以外の高度な重み付け、領域マスク
- Hires. fix の片方の段階だけで使う設定(`hr_option` が Both 以外)
- `none`、`invert`、`canny` 以外のプリプロセッサーに対する Processor res や Pixel perfect
- 4 つ以上のユニット(SDNUI のユニットは 3 つまで)
- このエンジンにないプリプロセッサー

一方、`low_vram` や `save_detected_map` のように通常の出力に影響しない設定は、そのまま受け付けます。これらの値は、読み込んだユニットを SDNUI が保存し直すときにも残ります。

### 保護されたセッション

**Image Scrambling** タブでセッションのスクランブルを有効にしている間は、Fastload のデータを保存できません。Fastload のファイルには元の制御画像とマスクがそのまま読める形で入るためです。この間も、**Load only** での読み込みはできます。

### 制限事項

- SDNUI は img2img の **Only masked** の範囲に合わせて ControlNet の入力を切り抜きません(A1111 の ControlNet 拡張は切り抜きます)。
- 実際の A1111 や Forge で保存したファイルとの互換性は確認していません。確認しているのは、SDNUI が元の拡張機能の形式で書いたファイルと、元の拡張機能のコードで作ったテスト用ファイルです。

### MCP から使う

MCP サーバーでは、`controlnet_fastload` ツールでファイルの確認とバージョン情報の取得ができます。生成時の読み込み・保存は、生成ツールの `always_on` に `controlnet_fastload` の設定を渡して行います。`list_controlnets` ツールは、使える ControlNet モデル、プリプロセッサー、Control mode を一覧します。詳しくは [MCP](mcp.html) を参照してください。
