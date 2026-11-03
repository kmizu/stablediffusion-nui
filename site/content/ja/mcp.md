---
title: AI エージェントから使う（MCP）
nav: MCP
description: Claude Code などの MCP クライアントを stdio または Streamable HTTP で Stable Diffusion NUI に接続し、モデルの確認、画像の生成・編集、ジョブ管理、PNG メタデータからの再生成を行う方法です。
---

Stable Diffusion NUI には MCP サーバーが入っています。Claude Code などの AI エージェントから、インストール済みのモデルや機能を調べ、チェックポイントを選び、画像を生成・編集し、進捗を確認し、結果を受け取り、ジョブを中断できます。

## 接続方式を選ぶ

接続方式は 2 つあり、どちらでも同じツールを使えます。

| | stdio（ヘッドレス） | Streamable HTTP（GUI 内蔵） |
|---|---|---|
| 起動のしかた | 専用エンジンを持つ別プロセスとして、ウィンドウなしで起動 | 起動中のデスクトップアプリの中で動作 |
| ジョブ | サーバー自身のキューで実行 | GUI のキューを通るので、GUI のプレビューとギャラリーに表示 |
| 接続先 | クライアントがプロセスを起動 | `http://127.0.0.1:7861/mcp` |
| トークン | 使わない | 任意の Bearer トークン |

サーバー設定 1 つごとにモデルエンジンが 1 つ起動します。同じモデルを二重に読み込まないよう、GPU 1 枚につきサーバー設定は 1 つにしてください。

## ヘッドレスサーバー（stdio）

クライアントが SDNUI を子プロセスとして起動し、stdin/stdout でやり取りします。`initialize` にはすぐ応答し、各ツールはエンジンの起動を待ってから動きます。`*_async` ツールは待たずに `waiting_for_engine` という状態で返ります。

### 起動コマンド

インストール方法に合わせて、次のどれかを使います。

- Windows パッケージ: `C:/apps/SDNUI/SDNUI.exe --mcp --home C:/apps/SDNUI`。同梱の Java ランタイムを使います。先にこのホームで Python 環境とモデルフォルダを設定しておいてください。
- ソースのチェックアウト: `run.bat --mcp`。ランチャーは stdout に何も出さず、JavaFX を初期化せずに `sdfx.mcp.McpMain` を起動します。
- jar: `java -cp target/stablediffusion-nui.jar sdfx.mcp.McpMain --home <dir>`

`C:/apps/SDNUI` はインストール先のフォルダに置き換えてください。

### オプション

| オプション | 意味 |
|---|---|
| `--home DIR` | アプリのルート（`python/`、`models/`、`config/` があるフォルダ）を指定します。 |
| `--dry-run` | torch/diffusers を読み込まずにエンジンを起動します。生成はプレースホルダー画像を返すので、GPU やモデルがなくてもツールを試せます。 |
| `--http [PORT]` | stdio の代わりに Streamable HTTP で待ち受けます。ポートを省略すると設定の `mcp_http_port`（既定 7861）を使います。 |
| `--token TOKEN` | `--http` 用の Bearer トークンです。環境変数 `SDFX_MCP_TOKEN` でも指定でき、プロセス一覧に表示されないのでこちらを推奨します。どちらもなければ設定の `mcp_token` を使います。 |
| `--allow-dir DIR` | 画像パスで指定できるフォルダを追加します。複数指定できます。 |
| `--drain-seconds N` | stdin が閉じたあと、実行中の呼び出しが終わるまで待つ秒数です。既定は 2 秒です。 |

ランチャーが渡す `--mcp` など、知らないオプションは無視されます。

!!! note "stdout に余計な出力を混ぜない"
    stdio モードでは stdout に JSON-RPC メッセージだけを出し、ログはすべて stderr に出します。自分でランチャーを書く場合は何も出力しないようにし（`@echo off`、バナーなし、`pause` なし）、JVM を `-Xlog:disable -Xlog:all=warning:stderr -Djava.awt.headless=true` 付きで起動して JVM の警告が stdout に出ないようにしてください。MCP クライアントで JSON のパースエラーが出たら、まず stdout に余計な出力がないか確認します。

## GUI 内蔵の HTTP サーバー

すでに開いているアプリをエージェントから操作したいときに使います。

1. **Settings** を開き、**MCP** タブを選びます。
2. **Enable MCP HTTP server** をオンにします。
3. **Port** は 7861 のままにするか、別のポート（1024〜65535）を指定します。
4. 必要なら **Token** を入力します。入力するとクライアントはこれを Bearer トークンとして送る必要があります。
5. **OK** を押します。設定はすぐに反映されます。

![Settings の MCP タブ](shots/settings-mcp.png)

サーバーが動いている間は、トップバーに **⇄ MCP :7861** という表示が出ます。エンドポイントは `http://127.0.0.1:7861/mcp`（ポート番号は設定値）です。設定は `settings.json` の `mcp_http_enabled`、`mcp_http_port`、`mcp_token` に保存されます。

サーバーは 127.0.0.1 だけで待ち受けます。`Origin` や `Host` ヘッダーがループバック以外のリクエストは拒否するので、Web ページからは呼び出せません。トークンを設定した場合、すべてのリクエストに `Authorization: Bearer <token>` が必要です。

!!! warning "共有マシンではトークンを設定する"
    トークンがないと、同じマシン上の他のプロセスや他のユーザーが、画像の生成、モデルの切り替え、GUI キューの中断や削除、許可フォルダ内の画像の読み出しをできてしまいます。

## Claude Code に登録する

ヘッドレス（Windows パッケージ）:

```sh
claude mcp add stablediffusion-nui -- C:/apps/SDNUI/SDNUI.exe --mcp --home C:/apps/SDNUI
```

ヘッドレス（ソースのチェックアウト、または jar）:

```sh
claude mcp add stablediffusion-nui -- C:/path/to/stablediffusion-nui/run.bat --mcp
claude mcp add stablediffusion-nui -- java -cp C:/path/to/stablediffusion-nui/target/stablediffusion-nui.jar sdfx.mcp.McpMain --home C:/path/to/stablediffusion-nui
```

GPU なしで試す:

```sh
claude mcp add stablediffusion-nui-dry -- C:/path/to/stablediffusion-nui/run.bat --mcp --dry-run
```

起動中の GUI に HTTP で接続する（トークンなし／あり）:

```sh
claude mcp add --transport http stablediffusion-nui-gui http://127.0.0.1:7861/mcp
claude mcp add --transport http stablediffusion-nui-gui http://127.0.0.1:7861/mcp --header "Authorization: Bearer <token>"
```

### プロジェクトの `.mcp.json`

プロジェクト単位で設定を共有する場合は、リポジトリの `.mcp.json.example` をプロジェクトの `.mcp.json` にコピーし、インストール先のパスを書き換えます。

```json
{
  "mcpServers": {
    "stablediffusion-nui": {
      "command": "C:/apps/SDNUI/SDNUI.exe",
      "args": ["--mcp", "--home", "C:/apps/SDNUI"]
    }
  }
}
```

ほかの MCP ホストでも、同じ実行ファイルと引数、または HTTP エンドポイントを指定すれば使えます。

## ツール

どのツールも、知らない引数や型の違う値を受け取るとエラーを返し、使える引数の一覧を示します。各ツールの完全な JSON スキーマは、クライアントの `tools/list` の応答で確認できます。

### 調べる

| ツール | 内容 |
|---|---|
| `get_status` | エンジンの状態、デバイス、読み込み中のモデル、実行中のジョブ、キューの長さ、出力フォルダ。 |
| `list_models` | チェックポイントの一覧（種類 `sd15`・`sdxl` など、サイズ、読み込み中かどうか）と VAE の一覧。`refresh: true` で先にモデルフォルダを再スキャンします。 |
| `list_samplers` | サンプラー、スケジュールタイプ、Hires 用アップスケーラー、アップスケーラー、保存済みスタイル。 |
| `list_loras` | LoRA の一覧と、そのまま使える `<lora:...>` タグ。`query` で名前を絞り込めます。 |
| `list_controlnets` | `controlnet` ユニットで使える ControlNet モデル、プリプロセッサ、コントロールモード。 |
| `list_always_on` | `tab`（`txt2img` または `img2img`）で有効な AlwaysOn 拡張と、その項目、既定値、`always_on` 用の型付き `values_schema`。 |
| `list_scripts` | `run_script` で使えるスクリプト（X/Y/Z plot、prompt matrix、loopback、SD upscale など）とその項目。 |

### モデル

| ツール | 内容 |
|---|---|
| `set_model` | `name`（`list_models` の名前、タイトル、パス、ハッシュの先頭）でチェックポイントを読み込みます。`vae`（`Automatic`、`None`、VAE 名）も指定できます。読み込めるのは設定済みフォルダ内のチェックポイントだけです。 |
| `model_keyword_lookup` | チェックポイントまたは LoRA（`kind`、`name`）のトリガーキーワード、旧形式のハッシュ、一致したマッピングファイルを返します。`name` を省くと読み込み中のチェックポイントを調べます。 |
| `model_presets` | モデルに紐づくプリセットライブラリを扱います。`action` は `get`、`save`、`share`、`resolve`、`fetch`、`thumbnail` のどれかです。`save` と `fetch` には `get` で得た `expected_revision` が必要です。`fetch` はチェックポイントの SHA256 を Civitai に送ります。生成は始まりません。 |

### 生成とジョブ

| ツール | 内容 |
|---|---|
| `txt2img` | プロンプトから生成し、結果が出るまで待ちます。 |
| `img2img` | `init_image` を変換するか、`mask` でインペイントし、結果が出るまで待ちます。 |
| `txt2img_async` / `img2img_async` | 同じリクエストをキューに入れ、すぐに `{job_id}` を返します。クライアントのツールのタイムアウトが短いときに使います。 |
| `get_job` | 非同期ジョブの状態、進捗（0〜1）、完了後は画像を返します。`wait_seconds`（最大 300）を指定すると、ジョブが終わるかその時間が過ぎるまで待ちます。 |
| `cancel_job` | キューにある MCP ジョブを取り消すか、実行中なら中断します。MCP から投入したジョブにだけ使えます。 |
| `interrupt` | 誰が始めたかに関係なく、実行中の生成を止めます。`clear_queue: true` で待機中のジョブもすべて削除します。 |
| `interruption_protection` | このエンジンプロセスの中断防止を取得、または `enabled` で設定します。実行中だけ有効で、既定はオフです。デスクトップ側の切り替えと共通です。 |
| `run_script` | `script_id` でスクリプトを実行します。項目の値は `values`、生成パラメータは `params` で渡します。`async: true` なら `job_id` を返します。 |

### 画像とそのほか

| ツール | 内容 |
|---|---|
| `list_recent` | 出力フォルダの新しい画像と infotext。`limit`（既定 10、最大 200）と `query` で絞り込みます。 |
| `get_image` | 出力フォルダまたは許可フォルダの画像 1 枚を infotext 付きで返します。`max_side`（既定 1024、0 は元のサイズ）でプレビューを縮小します。`.png`、`.jpg`、`.webp` を読めます。 |
| `inspect_generation_metadata` | PNG に埋め込まれたパラメータを読み、`txt2img` の引数、AlwaysOn の再現候補、適用できなかった項目を返します。何も実行しません。 |
| `aesthetic_embedding` | `operation: "create"` に `name` と `images`（1〜32枚）を渡して Aesthetic Gradients 用の埋め込みを作ります。`operation: "list"` は保存先の一覧を返します。画像と任意の `output_dir` は許可フォルダ内に指定します。既定の保存先は出力フォルダの `aesthetic_embeddings`、`overwrite` の既定値は `false` です。初回は CLIP ViT-L/14 をダウンロードします。 |
| `controlnet_fastload` | `operation: "inspect"` で Fastload 画像や `.cni` ファイルに保存された ControlNet 設定を読み、`operation: "version"` で形式のバージョンを返します。 |
| `clip_interrogate` | CLIP Interrogator で画像からプロンプトを推定します。`operation` は `prompt`（`mode` は best・fast・classic・negative・caption）、`analyze`（媒体・作家・芸術運動・流行・フレーバーの上位 5 件）、`batch`（許可フォルダ `folder` に `.txt`、`batch.txt`、`batch.csv` を書き出します。`overwrite: true` でない限り上書きしません）、`models`、`unload` です。`keep_loaded: true` でない限り、呼び出しごとにモデルを解放します。初回は Hugging Face からモデルをダウンロードします。 |
| `controlnet_preprocess` | 画像生成なしで、画像またはフォルダに ControlNet プリプロセッサを実行します（拡張機能 ControlNet Preprocessor の Extras 相当）。`preprocessor` は `operation=list` で確認できるモジュール（canny、invert、recolor_*、tile_*、blur_gaussian、depth、Marigold、検出系）、`processor_res`（64〜2048）または `pixel_perfect` は canny と検出系に効き、`args` でモジュールのスライダーを指定します。結果は `<名前>-<preprocessor>.png` として、パラメータに `ControlNet Preprocessor: "{...}"` を付けて保存され、上書きされません。 |
| `vae_tools` | 読み込み中の VAE で、ローカルファイル（`input_paths`、最大 64 個）をエンコード、デコード、または往復変換します。TAESD や Approx NN の重みは `allow_download: true` のときだけダウンロードします。 |

### 生成履歴

`generation_history` はデスクトップの履歴やお気に入りを読み取ります。`action: "list"`、`mode: "txt2img"`（または `img2img`、`inpaint`）、`collection: "history"`（または `favourites`）で名前と ID を一覧し、`action: "get"` と選んだ `id` で保存済み項目を取得できます。整数と小数の値は精度を保つため文字列として返り、各項目に型が付きます。履歴の名前や項目には個人情報が含まれ得ます。取り込んだ元データや画像は返さず、設定の復元・生成・履歴変更も行いません。

`action: "prepare"` と `mode`、`id` を指定すると、保存値の一部を `txt2img` または `img2img` の引数候補 `params` に変換します。これは候補の作成だけで、画像生成や履歴の変更は行いません。`invalid_fields` は値が不正な項目、`omitted_fields` は変換しなかった保存項目、`missing_fields` は必要な未指定項目を示します。img2img は入力画像、inpaint は入力画像とマスクが必要です。上流から取り込んだデータは、表示された項目以外にも未対応の設定を含み得ます。

variation の有効状態が履歴にある場合は、有効時の seed・strength を候補に含め、無効時は strength を 0 にします。有効状態が不明な場合は変換しません。

候補を確認・補完してから生成ツールを明示的に呼び出してください。モデルの存在、画像の総量などは生成ツール側で検証します。未変換の設定や現在の既定値、拡張機能、入力画像などによって結果は変わるため、元の画像の再現は保証されません。

一覧では `query` にカンマ区切りで語を指定すると、名前・prompt・negative prompt・checkpoint・sampler を大文字小文字を区別せず検索し、すべての語に一致する項目を返します。`limit`（1〜1000、既定1000）と `offset` で分割でき、`total` と次ページの `next_offset`（最後は `null`）を返します。ページ取得の間に履歴が変わると項目がずれることがあります。名前以外の値は一覧に出ませんが、検索結果や件数からその内容を推測できるため、検索も履歴を読む権限のあるクライアントで使ってください。

## 生成の引数

`txt2img` には `prompt` が、`img2img` にはさらに `init_image` が必要です。ほかの引数は省略できます。

- 基本: `negative_prompt`、`width`・`height`（64〜4096、8 の倍数）、`steps`（1〜150、既定 20）、`cfg_scale`（既定 7）、`sampler_name`（既定 `DPM++ 2M`）、`scheduler`（既定 `Automatic`）、`seed`（`-1` でランダム）、`batch_size`、`n_iter`、`clip_skip`、`eta`
- モデル: `checkpoint` を指定すると、現在のモデルと違う場合は先に読み込みます。`vae` で VAE を指定します。
- プロンプト補助: `styles`（保存済みスタイル名）、`loras`（`[{name, weight}]`。`<lora:name:weight>` タグとしてプロンプトに追加）、`model_keyword`（このリクエストだけ Model Keyword をオン／オフ）
- バリエーション: `subseed`、`subseed_strength`
- img2img のみ: `denoising_strength`（既定 0.75）、`resize_mode`、`mask`、`inpainting_fill`、`inpaint_full_res`、`inpaint_full_res_padding`、`inpainting_mask_invert`、`mask_blur`
- 結果: `return_images`（既定 true）と `max_image_side`（既定 1024）でインラインプレビューを調整します。保存されるファイルは常に元のサイズです。

`init_image` と `mask` には、許可フォルダ内のファイルパス、base64 の PNG/JPEG、`data:` URI を指定できます。

### `hires`（txt2img のみ）

`enabled`、`scale`（1〜4、既定 2）、`upscaler`（既定 `Latent`）、`steps`（0 は 1 回目と同じ）、`denoising_strength`（既定 0.55）、`resize_x`、`resize_y` を持つオブジェクトです。2 回目のパス専用の `prompt`、`negative_prompt`、`cfg_scale`、`sampler_name`、`scheduler` も指定できます。

```json
{
  "prompt": "a lighthouse at dusk",
  "width": 512,
  "height": 512,
  "hires": { "enabled": true, "scale": 2, "upscaler": "Latent", "denoising_strength": 0.5 }
}
```

### `manual_loras`

構造化した LoRA 指定を最大 32 個まで渡せます。項目は `name`（必須）、`weight`、`unet`、`lbw`、`start`・`stop`（0〜1）、`dyn` です。名前は現在のカタログと照合されます。単純なプロンプトタグには、従来の `loras` も引き続き使えます。

### `controlnet`

最大 8 ユニットです。各ユニットは `enabled`、`model`、`image`、`preprocessor`、`weight`（0〜2）、`guidance_start`、`guidance_end`、`preprocessor_args`（数値のみ）、`control_mode`、`processor_res`（-1 または 64〜2048）、`pixel_perfect` を持ちます。使えるモデル、プリプロセッサ、コントロールモードは `list_controlnets` で確認します。`txt2img` では有効なユニットごとに `image` が必要です。`img2img` では省略すると `init_image` を使えます。

### `always_on`

AlwaysOn 拡張のジョブ単位の設定で、プラグイン ID をキーにします。どの ID があり、それぞれどの型の値を受け付けるかは `list_always_on` で確認します。指定しなかった値にはデスクトップの設定ではなくプラグインの既定値が使われ、デスクトップの設定は変わりません。

```json
{
  "prompt": "a ceramic teapot on a wooden table",
  "seed": 173,
  "steps": 20,
  "always_on": {
    "freeu": { "enabled": true, "b1": 1.15, "b2": 1.2 }
  }
}
```

`model_keyword` の真偽値と `always_on.model_keyword` は、どちらか一方だけを使ってください。生成時の ControlNet Fastload の読み込み・保存も `always_on` で指定します（例: `{"controlnet_fastload": {"enabled": true, "mode": "load_only", "path": "..."}}`）。

## 生成結果の形

生成結果には、画像ごとの image ブロック（`return_images: false` でなければ `max_image_side` に縮小）と、保存先のパスと infotext を並べた text ブロックが入ります。構造化された結果は `{job_id, images: [{path, seed, infotext, grid}]}` です。

クライアントが `_meta.progressToken` を送ると、サーバーは `notifications/progress` で進捗を通知します。`notifications/cancelled` を送るとジョブが中断されます。

`get_job` の `status` は `waiting_for_engine`、`queued`、`running`、`done`、`failed`、`cancelled` のどれかです。取り消されたジョブは `isError: true` で返ります。

## 操作の例

エージェントが実行する短い流れです。各ブロックは 1 回のツール呼び出しの引数です。モデル名は、手元の `list_models` が返す名前を使ってください。

モデルの一覧を取得します。

```json
{ "name": "list_models", "arguments": {} }
```

一覧から SD1.5 のチェックポイントを読み込みます。`animekawa_v10` は例です。`list_models` の結果に表示された、手元のチェックポイント名に置き換えてください。

```json
{ "name": "set_model", "arguments": { "name": "animekawa_v10" } }
```

画像を生成し、完了を待ちます。

```json
{
  "name": "txt2img",
  "arguments": {
    "prompt": "a red fox sitting in fresh snow, morning light",
    "negative_prompt": "blurry, low quality",
    "width": 512,
    "height": 512,
    "steps": 20,
    "sampler_name": "DPM++ 2M",
    "seed": 42
  }
}
```

結果の `images[0].path` に保存先が入っています。小さいサイズで取得します。

```json
{ "name": "get_image", "arguments": { "path": "<txt2img が返した images[0].path>", "max_side": 512 } }
```

時間のかかるジョブでは、同じ引数で `txt2img_async` を呼び、返った `job_id` と `wait_seconds` を付けて、状態が `done`、`failed`、`cancelled` のどれかになるまで `get_job` を呼びます。そのジョブを止めるには `cancel_job` を使います。`interrupt` はキュー全体に作用します。

## PNG のメタデータから再生成する

1. `inspect_generation_metadata` を呼び、`image` に許可フォルダ内の PNG のパス、base64 の PNG、または PNG の `data:` URI を指定します。
2. 応答の `generation_args`（型付きの `txt2img` 引数）、`always_on`（対応する拡張の再現候補）、`unapplied_fields`、`warnings` を確認します。
3. チェックポイントと LoRA を `list_models` と `list_loras` で確認します。このツールはモデルを選んだり読み込んだりしません。
4. 確認した引数と `always_on` の値で `txt2img` を呼びます。img2img やインペイントでは、元画像とマスクを自分で指定してください。PNG のテキストからは復元できません。

再現候補を返すのは、標準の Hires 設定、バリエーション、eta、manual LoRA、Conditioning Highres Fix、img2img Hires Fix、Token Downsampling、Model Keyword、Hires seed の調整、Composable LoRA、グリッド番号、FreeU、HyperTile、PAG です。それ以外の項目は `unapplied_fields` に入り、実行されることはありません。モデルファイル、embedding、アダプタ、実行環境のバージョン、入力が違うことがあるため、ピクセル単位で同じ画像になるとは限りません。

## セキュリティと制限

### 受け付ける画像パス

`init_image`、`mask`、`get_image`、`run_script` の `params.init_image` などのパス入力で受け付けるのは、出力フォルダ（その中の `.tmp` を含む）と、許可したフォルダの中のファイルだけです。

- ヘッドレス: `--allow-dir <dir>`（複数指定可）
- 両方の方式: 環境変数 `SDFX_MCP_ALLOWED_DIRS`（Windows は `;`、それ以外は `:` 区切り）

パスは実パスに解決して判定するので、シンボリックリンクやジャンクションで外に出ることはできません。相対パスは出力フォルダを基準に解決します。UNC やネットワークパス（`\\host\share`、`//host`）は常に拒否します。ファイルがない場合と許可されていない場合は、同じエラーになります。base64 と `data:` URI は常に使えます。

### MCP からできないこと

- 保護（スクランブル）された PNG は入力として拒否します。MCP には画像パスワードのセッションがありません。
- `custom_code` スクリプトは MCP からは実行できません。`model_merge` と `model_converter` はモデルファイルを書き出すため GUI 専用です。
- MCP のリクエストは自分の設定を持ちます。GUI の未保存の項目は使いません。
- 次のデスクトップ機能には MCP の対応ツールがありません: Model Keyword のマッピング編集、Embedding Inspector と Embedding Merge、Image Comparison、Styles エディター、Metadata Utils、Temporal Lab、Extras、Tokenizer、System Info、VRAM Estimator。

### 上限

| 項目 | 上限 |
|---|---|
| リクエスト本体、stdio の 1 行 | 64 MB |
| 同時に実行するツール呼び出し | 32（超えるとエラー -32000 "server busy"） |
| 入力画像 | 4096 x 4096 |
| 幅、高さ、Hires の出力サイズ | 1 辺 4096 |
| `width * height * batch_size` | 16.7 メガピクセル |
| 1 ジョブの出力合計 | 64 メガピクセル |
| 1 回の呼び出しの画像数 | 16 |
| 待機中の MCP ジョブ | 32 |
| 1 回の応答のインラインプレビュー | base64 で 20 MB。超えた分はパスで返すので `get_image` で取得します |
| HTTP セッション | 30 分操作がないと失効。64 セッションに達すると、アイドル中のものだけを削除 |

## 検証済みの内容

公式の `mcp` Python SDK（バージョン 2.3.0、プロトコル `2025-06-18`）を別プロセスのクライアントとして使い、パッケージ版の `SDNUI.exe` と同梱ランタイムに対してテストしました。実モデルでは SD1.5（`animekawa_v10`、512 px）と SDXL（`waiIllustriousSDXL_v170`、768 px）を 12 ステップで、stdio と HTTP の両方で実行し、4 回とも成功しました。各回で次を確認しています。

- ツール、モデル、サンプラー、スクリプト、AlwaysOn 拡張の取得
- モデルの選択と、進捗通知が 100 まで届く `txt2img`
- 応答に含まれる PNG 画像
- 通常の PNG と FreeU 付き PNG からの再生成（RGB が完全に一致することを確認）
- `img2img` とマスクによるインペイント
- 非同期ジョブのポーリングと完了
- 取り消しと、その後の生成の成功

HTTP の検証は、GUI 内蔵のサーバーではなく、別に起動したヘッドレスの HTTP サーバーで行いました。テストに通っても、すべての拡張の組み合わせが動くとは限りません。モデルとの相性やプロンプトの出来はモデルに左右されます。
