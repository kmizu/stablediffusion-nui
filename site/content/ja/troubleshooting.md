---
title: トラブルシュートとよくある質問
nav: トラブルシュート
description: Stable Diffusion NUI でエンジンが起動しない、VRAM が足りない、モデルが一覧に出ないといった問題の確認方法と対処法、よくある質問への回答です。
---

## まず確認すること

### エンジンの状態表示

画面上部のバーの右側に、エンジンの状態が表示されます。

| 表示 | 意味 |
|---|---|
| **● Starting…** | Python のエンジンを起動しています |
| **● Ready** | 生成できます。マウスを重ねると、エンジン、Python、torch、diffusers のバージョンが表示されます |
| **● Loading** | モデルを読み込んでいます |
| **● Busy** | エンジンが処理中です |
| **● Engine crashed** | エンジンが異常終了しました。マウスを重ねると、エラーの内容が表示されます |
| **● Stopped** | エンジンが動いていません |

**● Engine crashed** または **● Stopped** のときは、隣に **⟲ Restart** ボタンが出ます。押すとエンジンを再起動します。

### ログを見る

画面右下の **☰ Log** を押すと、エンジンとジョブのログが開きます。エラーの原因はたいていここに出ています。**Copy** でログ全体をコピーできるので、問題を報告するときに使ってください。**Clear** でログを消去します。

### System Info タブ

**System Info** タブでは、実行環境、メモリ、モデルやネットワークの一覧を確認できます。**Refresh data** で最新の情報に更新し、**Export JSON…** でファイルに保存できます。このタブの情報が自動で外部に送られることはありません。

![System Info タブ](shots/system-info.png)

### dry-run で切り分ける

画面の操作に問題があるのか、エンジンやモデルに問題があるのかを切り分けるには、dry-run モードで起動します。dry-run ではモデルや GPU を使わず、プレースホルダー画像を返します。

```
SDNUI.exe --dry-run
```

ソースから実行している場合は `run.bat --dry-run`（Linux / macOS では `./run.sh --dry-run`）です。dry-run で問題が起きなければ、原因は Python 環境、GPU、モデルのいずれかにあると考えられます。

## エンジンが起動しない

1. **☰ Log** でログを確認します。
2. Python のバージョンを確認します。使えるのは 3.10〜3.12 です。3.13 以降は想定していません。
3. Settings の **Engine** タブで **Python** を指定している場合は、そのパスが正しいか確認します。指定した Python が動かないときは、別の Python に切り替えずにエラーになります。空にすると、アプリの `python/.venv` を使います。
4. Python 環境を作り直す場合は、アプリのフォルダの `python/.venv` を削除してから起動します。セットアップ画面が開くので、もう一度 **⤓ Install** を実行します。

セットアップ画面でインストールに失敗した場合は、画面に「Installation failed」と理由が表示され、ボタンが **Retry** に変わります。ネットワークの状態などを確認してから **Retry** を押してください。

## torch のインストールに失敗する / CUDA が使えない

SDNUI は CUDA 12.8 用の torch（`cu128`）をインストールします。NVIDIA のドライバが古いと、GPU が認識されません。

- `nvidia-smi` を実行して、ドライバと GPU を確認します。
- 別の CUDA のビルドが必要な場合は、`python` フォルダで手動でインストールします。

```
.venv/Scripts/pip install -r requirements-torch.txt --index-url <別の index>
```

## VRAM が足りない（out of memory）

次の順に試してください。

- **Width** と **Height**、または **Batch** の **size** を小さくします。size は同時に生成する枚数なので、増やすほど VRAM を使います。
- Settings の **Engine** タブで **VAE tiling** を有効にします。大きい画像を VAE でデコードするときの VRAM を節約できます。
- **VRAM mode** を **Model offload (~8 GB VRAM)** に、それでも足りなければ **Sequential offload (lowest VRAM)** にします。Sequential offload は最も遅くなりますが、VRAM の使用量は最も少なくなります。

VRAM の設定を変えたあとはエンジンの再起動が必要です。**OK** を押したときの確認で **Yes** を選んでください。

エンジンが使っている GPU メモリは、上部バーに「VRAM 使用量 / 全体 GB」の形で表示されます。どのくらいの VRAM が必要かの目安は、**VRAM Estimator** タブで測定できます。生成フォームの AlwaysOn セクション **Never OOM** を有効にすると、エンジンを再起動せずに、そのジョブだけ VRAM 使用量のピークを下げられます。

## モデルが一覧に出ない {#models}

チェックポイントが 1 つも見つからない場合は、プレビュー欄に「No checkpoints found.」と表示されます。

1. 上部バーの **⟳**（Rescan model folders）を押して、モデルフォルダを読み込み直します。読み込み終わると、見つかったチェックポイント、LoRA、VAE の数が表示されます。
2. ファイルが正しい種類のフォルダにあるか確認します。チェックポイントは `models/checkpoints/` に置きます。チェックポイントとして扱うファイルは `.safetensors` と `.ckpt` です。
3. 配布版では、`models` は展開した `SDNUI` フォルダの中にあります。別の場所にあるモデルを使う場合は、Settings の **Model folders** タブでフォルダを追加します。相対パスはアプリのフォルダからの位置として扱います。
4. A1111 / reForge のモデルを使う場合は、**Add A1111/reForge install…** でインストール先を選びます。

## Hugging Face に接続できない / オフラインで使いたい {#offline}

単体ファイルのチェックポイントを初めて読み込むとき、SD1.5 / SDXL のベース設定とトークナイザーを Hugging Face から取得します（`stable-diffusion-v1-5/stable-diffusion-v1-5`、`stabilityai/stable-diffusion-xl-base-1.0`）。

一度オンラインで読み込んでキャッシュしたあとは、Settings の **Engine** タブで **Offline mode** を有効にすると、`HF_HUB_OFFLINE=1` で動きます。Revision・IP-Adapter の画像エンコーダ、BLIP、LDSR、Marigold も初回の利用時に取得するので、オフラインで使う前に一度使っておいてください。

## MCP クライアントで JSON のパースエラーが出る

stdio の MCP では、標準出力に MCP のメッセージ以外が混ざるとクライアントが読み取れません。

- `SDNUI.exe --mcp` や `run.bat --mcp` 自体は、標準出力に余計なものを出しません。
- 独自の起動スクリプトを使う場合は、`echo` や `pause` を入れないでください。
- JVM の警告を抑えるには、`-Xlog:disable -Xlog:all=warning:stderr -Djava.awt.headless=true` を付けて Java を起動します。

## ソースから実行するときの問題

- **Java が見つからない / 古い**: `run.bat` が理由を表示して終了します。JDK 17 以上を入れて、`JAVA_HOME` を設定してください。
- **起動時に `Unsupported JavaFX configuration` と警告が出る**: fat jar をクラスパスから起動しているための警告です。動作には影響しません。

## よくある質問

### A1111 や reForge のインストールは必要ですか？

必要ありません。SDNUI はそれだけで動きます。すでに A1111 / reForge を使っている場合は、そのモデルフォルダを読み取り専用で参照できます。

### インターネット接続は必要ですか？

Python 環境のセットアップと、モデルを初めて読み込むときに必要です。必要なファイルを一度取得したあとは、**Offline mode** を有効にしてオフラインで使えます。

### GPU がなくても使えますか？

実際の生成には NVIDIA GPU を推奨しています。GPU がない環境でも、dry-run モードで画面の操作を試せます。

### 生成した画像のパラメータを確認するには？

画像の下のテキスト欄に表示されます。保存済みの PNG は **PNG Info** タブに読み込むと確認でき、そこから各生成タブに送れます。

### アンインストールするには？

配布版はインストーラーを使わないので、展開した `SDNUI` フォルダを削除するだけです。フォルダ内の設定、生成した画像、Python 環境も一緒に削除されます。残したい画像は先に別の場所へコピーしてください。
