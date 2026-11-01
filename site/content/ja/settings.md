---
title: 設定とフォルダ
nav: 設定
description: Stable Diffusion NUI の設定画面、設定ファイルのキー、アプリのフォルダと出力先、VRAM モード、キーボードショートカット、環境変数の一覧です。
---

## 設定画面

画面右上の **⚙** を押すと、Settings が開きます。変更は **OK** を押したときに保存され、**Cancel** で破棄されます。

![Settings 画面](shots/settings.png)

| タブ | 主な項目 |
|---|---|
| **Model folders** | モデルの種類ごとの検索フォルダ、**Add A1111/reForge install…** |
| **General** | 出力フォルダ、テーマ、ライブプレビュー |
| **Engine** | エンジン用の Python、精度、VRAM モード、VAE タイル化、オフラインモード |
| **MCP** | GUI に内蔵された MCP HTTP サーバー |
| **WD v3 (partial)** | img2img の WD v3 バッチで使うモデルとタグのファイル |
| **Hires** | Hires. fix のセクションに表示する項目 |

### Model folders

左の一覧でモデルの種類を選び、右の一覧でその種類の検索フォルダを追加・削除します。種類は **Checkpoints**、**VAE**、**LoRA**、**ControlNet**、**Embeddings**、**Upscalers**、**Face restore**、**SVD**、**Zero123**、**PhotoMaker** の 10 種類です。

- **＋ Add folder…**: 選んだ種類にフォルダを追加します。
- **－ Remove**: 選んだフォルダを一覧から外します。
- フォルダはダブルクリックで直接編集できます。相対パスはアプリのフォルダからの位置として扱います。
- **Add A1111/reForge install…**: A1111 / reForge / Forge のインストール先を選ぶと、そのモデルフォルダを読み取り専用でまとめて追加します。

フォルダを変えて **OK** を押すと、モデルの一覧が読み込み直されます。

### General

| 項目 | 内容 |
|---|---|
| **Output folder** | 生成した画像の保存先です。相対パスはアプリのフォルダの中になります |
| **Hires output folder** | Hires. fix の結果を別のフォルダに保存する場合に指定します。空なら **Output folder** を使います。**Output folder** を初期値から変えている場合は、そちらが優先されます |
| **Named NSFW previews default** | モデルのプレビュー画像の既定の表示方法（**Blur**、**Hide**、**Show**）です。次に起動したときから使われます |
| **Theme** | `dark` または `light` |
| **Live preview** | 何ステップごとにライブプレビューを送るかです。0 で無効になります |
| **Preview method** | `approx`（すぐ表示、色はおおまか）または `taesd`（高画質、少し処理が増える） |

### Engine

| 項目 | 内容 |
|---|---|
| **Python** | エンジンに使う Python です。空なら、アプリの venv（`python/.venv`）、なければシステムの Python を使います |
| **Precision** | モデルの重みの精度です。`fp16`、`bf16`、`fp32` から選びます。たいていの GPU では `fp16` を使います |
| **VRAM mode** | 下の [VRAM モード](#vram) を参照してください |
| **VAE tiling** | 大きい画像の VAE デコードをタイルに分けて行い、VRAM を節約します |
| **Offline mode** | Hugging Face からダウンロードしません |
| **Allow custom code** | Custom code スクリプトでエンジン内の Python を実行できるようにします。A1111 の `--allow-code` に相当します |

**Python** を指定した場合は、その Python だけを使います。動かなくても別の Python に切り替えず、エラーになります。

Python、精度、VRAM モード、VAE タイル化、オフラインモードを変えて **OK** を押すと、「Engine settings changed. Restart the engine now?」と確認が出ます。**Yes** を押すとエンジンが再起動し、変更が反映されます。

### MCP

| 項目 | 内容 |
|---|---|
| **Enable MCP HTTP server** | AI エージェントが HTTP の MCP でこのアプリを操作できるようにします |
| **Port** | MCP HTTP サーバーのポートです。初期値は 7861 です |
| **Token** | クライアントに送らせるベアラートークンです（任意ですが、設定を推奨します） |

この設定は **OK** を押すとすぐに反映されます。サーバーが動いている間は、上部バーに **⇄ MCP :7861** のような表示が出ます。接続方法は [MCP のページ](mcp.html)を参照してください。

![Settings の MCP タブ](shots/settings-mcp.png)

### WD v3 (partial) と Hires

**WD v3 (partial)** タブでは、ローカルに置いた WD v3 のモデル（**Model (.onnx)**）とタグ（**Tags (.csv)**）のファイルを選びます。モデルは自動ではダウンロードされません。選んだあとはエンジンを再起動してください。

**Hires** タブでは、Hires. fix のセクションに表示する項目を切り替えます。非表示にした項目も値は保持されます。

## VRAM モード {#vram}

**Engine** タブの **VRAM mode** で、モデルを GPU にどう載せるかを選びます。

| 表示 | 設定値 | 内容 |
|---|---|---|
| **GPU (fastest)** | `gpu` | すべてを GPU に置きます。最も速い設定です |
| **Model offload (~8 GB VRAM)** | `model_offload` | モデルを使うときだけ、モデル単位で GPU に移します |
| **Sequential offload (lowest VRAM)** | `sequential_offload` | 層ごとに GPU に移します。最も遅いですが、VRAM の使用量が最も少なくなります |

古い設定値 `medvram` は `model_offload` に、`lowvram` と `cpu` は `sequential_offload` に、読み込み時に自動で変換されます。

## 設定ファイル

設定は `config/settings.json` に保存されます。初回起動時に作られ、Settings 画面から編集できます。

| キー | 初期値 | 内容 |
|---|---|---|
| `model_dirs` | `models/*` の各フォルダ | 種類ごとの検索フォルダ |
| `output_dir` | `outputs` | 出力先（相対パスはアプリのフォルダが基準） |
| `python_exe` | `null` | エンジン用の Python。空なら `python/.venv`、なければシステムの Python |
| `dtype` | `fp16` | `fp16` / `bf16` / `fp32` |
| `vram_mode` | `gpu` | VRAM モード |
| `vae_tiling` | `false` | 大きい画像の VAE デコードをタイル化 |
| `preview_every` / `preview_method` | `2` / `approx` | ライブプレビューの間隔（0 で無効）/ `approx` または `taesd` |
| `last_model` / `last_vae` | `null` | 前回使ったモデルと VAE |
| `mcp_http_enabled` / `mcp_http_port` / `mcp_token` | `false` / `7861` / `null` | GUI 内蔵の MCP HTTP サーバー |
| `disabled_plugins` / `plugin_values` | `[]` / `{}` | 無効にしたプラグインと、各プラグインの入力値 |
| `hf_offline` | `false` | Hugging Face にアクセスしない（`HF_HUB_OFFLINE=1`） |
| `theme` | `dark` | `dark` / `light` |
| `allow_custom_code` | `false` | Custom code スクリプトを許可する。MCP からは常に使えません |

## アプリのフォルダ

SDNUI は、`python/`、`models/`、`config/` があるフォルダを「アプリのフォルダ」として使います。

- **配布版**: 展開した `SDNUI` フォルダを使います。カレントディレクトリや環境変数 `SDFX_HOME` が別の場所を指していても、このフォルダが優先されます。`--home DIR` を指定した場合だけ、そのフォルダを使います。
- **ソースから実行する場合**: `-Dsdfx.home`、環境変数 `SDFX_HOME`、カレントディレクトリ、jar の場所（`python/sdfx` があるフォルダ）の順に探します。

アプリのフォルダには、次のファイルやフォルダが作られます。

| 場所 | 内容 |
|---|---|
| `config/settings.json` | 設定 |
| `config/ui-state.json` | 画面の状態 |
| `config/styles.csv` | Styles（A1111 互換） |
| `config/lbwpresets.txt` | LoRA Block Weight のユーザープリセット（任意。1 行に 1 つ `名前:w1,w2,...`） |
| `python/.venv` | エンジン用の Python 環境 |
| `outputs/` | 生成した画像 |

### 出力フォルダの構成

| 場所 | 内容 |
|---|---|
| `outputs/txt2img/<日付>/` など | 種類と日付ごとの生成画像（ファイル名は `<通し番号>-<seed>.png`） |
| `outputs/grids/` | グリッド画像 |
| `outputs/intermediates/` | **Save intermediate images** などで記録した途中経過 |
| `outputs/.tmp` | 一時ファイル |

## キーボードショートカット

| キー | 動作 |
|---|---|
| Ctrl+Enter | 生成（プロンプトの入力中でも使えます） |
| Ctrl+Shift+Enter | キューに追加 |
| Esc | 生成中なら中断 |
| Ctrl+V | img2img / Inpaint の画像欄に、クリップボードの画像を貼り付け |
| Ctrl+Z / Ctrl+Y（Ctrl+Shift+Z） | Inpaint のマスクを元に戻す / やり直す |
| Ctrl+↑ / Ctrl+↓ | プロンプトで選択した語句の強調の重みを増やす / 減らす |
| ← / → | ギャラリーの前後の画像に移動（プレビューにフォーカスがあるとき） |
| Delete | ギャラリーで選択中の画像を削除 |

プレビュー欄では、Ctrl + マウスホイールで拡大・縮小、ドラッグで移動、ダブルクリックで全体表示と等倍表示の切り替えができます。macOS では Ctrl の代わりに Cmd を使います。

## 環境変数

| 変数 | 内容 |
|---|---|
| `SDFX_HOME` | アプリのフォルダを指定します（ソースから実行する場合。配布版では展開したフォルダが優先されます） |
| `SDFX_MCP_TOKEN` | ヘッドレスの MCP サーバーのトークン。`--token` で指定するとプロセス一覧に見えてしまうので、こちらを推奨します |
| `SDFX_MCP_ALLOWED_DIRS` | MCP で読み取りを許可するフォルダ。Windows では `;`、それ以外では `:` で区切ります。GUI の HTTP サーバーにも効きます |

**Offline mode** を有効にすると、エンジンは `HF_HUB_OFFLINE=1` で動きます。
