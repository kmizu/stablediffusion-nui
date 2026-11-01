---
title: Stable Diffusion NUI
nav: ホーム
description: Stable Diffusion NUI は、SD1.5 と SDXL のモデルを手元の GPU で動かすデスクトップアプリです。機能の概要と、マニュアルの読み進め方を紹介します。
---

## SDNUI でできること

### 3 つの生成モード

**txt2img**、**img2img**、**Inpaint** の 3 つのタブで画像を作ります。Inpaint ではマスクを直接描けて、undo / redo やクリップボードからの画像の貼り付けにも対応しています。

- **Hires. fix**: 基本サイズで生成してから拡大し、もう一度描き込みます。
- **Styles**: よく使うプロンプトを保存して呼び出せます。A1111 互換の `config/styles.csv` を使います。
- **Variation seed**、バッチ生成、キュー（**＋ Queue**）に対応しています。
- 生成中は途中経過をライブプレビューで確認できます。
- プロンプトは A1111 と同じ `(強調:1.2)` 記法と `BREAK` が使え、75 トークン単位のチャンクに分けて扱います。ローカルの CSV からタグを補完することもできます。

### ControlNet と ControlNet Fastload

各生成モードの **ControlNet** セクションで、ControlNet のモデルとプリプロセッサ（canny、depth など）を選んで構図を指定できます。ユニットは 3 つまで使え、**Control mode** は **Balanced**、**My prompt is more important**、**ControlNet is more important** から選べます。

**ControlNet Fastload** は、ControlNet ユニットの設定を画像と一緒に保存し、あとから読み込み直す機能です。保存先は PNG の末尾への追記、`.cni` ファイル、またはその両方から選べます。保存したファイルは **ControlNet Fastload** タブで中身を確認し、**ControlNet Fastload Filter** タブで一覧から探せます。

### LoRA と埋め込みのツール

- プロンプト欄の **✚ LoRA** から検索して `<lora:name:weight>` タグを挿入できます。**Networks…** ではプレビュー画像付きで一覧できます。
- **Embedding Inspector**、**Embedding Merge**、**Embedding Editor** の各タブで、Textual Inversion の埋め込みを調べたり、組み合わせたり、編集したりできます。
- **Model Keywords** タブではチェックポイントや LoRA にキーワードを結び付け、**Model Presets** タブではモデルごとのプリセットを管理できます。
- **Metadata Utils** タブでは、チェックポイントや LoRA の `.safetensors` メタデータを、重みを読み込まずに確認・編集し、コピーとして保存できます。

### A1111 の拡張機能を標準で同梱

reForge に標準で入っているスクリプトと内蔵拡張は、すべて書き直して同梱しています。さらに、A1111 / reForge の公式拡張機能一覧（353 件）のうち 125 件を標準機能として取り込んでいます。

拡張機能は次の 3 種類に分かれます。

| 種類 | 使い方 | 例 |
|---|---|---|
| Script | **Script** 欄で 1 つだけ選び、ジョブ全体を実行します | X/Y/Z plot、Prompt matrix、Loopback、SD upscale |
| AlwaysOn | 生成フォームのセクションで個別に ON / OFF し、毎回の生成に適用します | FreeU、Dynamic Thresholding、PAG、IP-Adapter、Never OOM |
| Extras | 画像の後処理をします | Upscale、Restore faces、Caption（BLIP / WD14） |

jar ファイルを `plugins-ext/` フォルダに置けば、自作のプラグインも追加できます。

### MCP で AI エージェントから操作

SDNUI には MCP サーバーが入っており、Claude Code などの AI エージェントから画像を生成できます。エージェントはモデルの一覧取得と切り替え、txt2img / img2img の実行、進捗の確認、中断、スクリプトや AlwaysOn 拡張の操作ができます。

GUI を使わずに専用エンジンを起動する stdio 接続と、起動中のアプリに接続する HTTP 接続の 2 通りがあります。HTTP 接続で生成したジョブは GUI のキューを通るので、プレビューとギャラリーにも表示されます。

### 画像のツール

| タブ | できること |
|---|---|
| **PNG Info** | 画像に埋め込まれた生成パラメータを読み出し、各生成タブへ送ります |
| **Extras** | アップスケールと色補正をします |
| **Comparison** | 2 枚の画像をワイプ表示で比べます |
| **VAE Tools** | 選んだチェックポイントの VAE（または TAESD）で画像をエンコード・デコードします |
| **Tokenizer** | 読み込んだモデルの CLIP 語彙でテキストをトークンに分けて調べます |
| **Temporal Lab** | 連番画像の安定化（DFI）、Deflicker、画像列の Fuse、動画からのフレーム抽出をします |
| **Image Scrambling** | 画像のスクランブル化と、まとめての変換をします |
| **System Info** | 実行環境、メモリ、モデルの一覧を表示し、ベンチマークを実行します |
| **VRAM Estimator** | 生成に必要な VRAM の目安を測定して表示します |

![txt2img で SDXL の画像を生成したあとの SDNUI の画面](shots/txt2img.png)

## 次に読むページ

- [インストールと初回起動](install.html): ZIP の入手から、Python 環境のセットアップ、モデルフォルダの設定までを説明します。
- [最初の 1 枚を生成する](first-image.html): txt2img で画像を 1 枚作るまでの手順を順に説明します。
- [MCP で AI エージェントから使う](mcp.html): Claude Code などから SDNUI を操作する方法を説明します。
