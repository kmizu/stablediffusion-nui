---
title: インストールと初回起動
nav: インストール
description: Stable Diffusion NUI の Windows 版をダウンロードして展開し、Python 環境をセットアップしてモデルを使えるようにするまでの手順です。
---

## 必要なもの

| 項目 | 内容 |
|---|---|
| OS | 配布版は Windows x64 向けです。Linux と macOS ではソースからビルドして使います |
| Java | 配布版には Java ランタイムが同梱されているので、別途インストールする必要はありません |
| Python | 3.10〜3.12。3.13 以降は想定していません。Python と torch は配布版に含まれません |
| GPU | NVIDIA GPU と対応するドライバを推奨します。torch は CUDA 12.8 用のビルドを入れます |
| モデル | 同梱されていません。SD1.5 または SDXL のチェックポイントを用意してください |
| ディスク | Python 環境のセットアップで、PyTorch と diffusers を合わせて数 GB ダウンロードします |

GPU がなくても、dry-run モードで画面の操作は試せます（[dry-run モード](#dry-run)を参照）。

## ダウンロードと展開

1. [GitHub Releases](https://github.com/kmizu/stablediffusion-nui/releases/latest) から最新版の `SDNUI-v0.1-windows-x64.zip` をダウンロードします。
2. ZIP を書き込みできるフォルダ（たとえばユーザープロファイルの下）に展開します。
3. 展開した `SDNUI` フォルダの中の `SDNUI.exe` を起動します。

!!! warning "ZIP の中から直接起動しないでください"
    設定、生成した画像、Python 環境は展開先のフォルダに保存されるので、書き込みできる場所に展開する必要があります。

展開したフォルダには、`SDNUI.exe` と並んで `app`、`runtime`、`python`、`models` があります。これらはまとめて置いたままにしてください。SDNUI はインストーラーを使わない形式のアプリなので、展開したフォルダを削除すれば、そのフォルダ内のデータも含めて削除できます。

## 初回起動と Python のセットアップ

dry-run モードでなく、使う Python がまだ決まっていない（`python/.venv` が作られていない）場合は、最初の起動時に **SDNUI — first start** というセットアップ画面が開きます。`python/requirements*.txt` が更新されたときにも開きます。

画面には 3 つのボタンがあります。

| ボタン | 動作 |
|---|---|
| **⤓ Install** | `python/.venv` を作り、PyTorch（CUDA 版）と diffusers をインストールします。PATH に Python 3.10〜3.12 が必要です |
| **Use dry-run** | プレースホルダー画像を返す仮のエンジンで起動します。GPU もダウンロードも不要です |
| **Choose Python…** | torch と diffusers が入っている既存の Python 環境の `python.exe` を選びます |

**⤓ Install** を選ぶと、次の処理が順に行われます。

1. システムの Python（`py -3.12`、`py -3.10`、`python3`、`python` の順に探します）で `python/.venv` を作ります。
2. `python/requirements-torch.txt`（torch / torchvision）を `https://download.pytorch.org/whl/cu128` からインストールします。
3. `python/requirements.txt`（diffusers など）をインストールします。

ダウンロード量が数 GB あるため、5〜20 分ほどかかります。進行状況は画面のログに表示され、終わると画面は自動で閉じます。途中で **Cancel** を押すと中断できます。中断や失敗のあとはボタンが **Retry** に変わるので、押すと再開できます。

**Choose Python…** で選んだ Python は、設定ファイルの `python_exe` に保存されます。あとから変える場合は、Settings の **Engine** タブの **Python** で指定します。

手動でセットアップする場合は、`python` フォルダで次のコマンドを実行します。

```
cd python
python -m venv .venv
.venv/Scripts/pip install -r requirements-torch.txt --index-url https://download.pytorch.org/whl/cu128
.venv/Scripts/pip install -r requirements.txt
```

## 起動の確認

画面上部のバーの右側に、エンジンの状態が表示されます。**● Starting…** から **● Ready** に変われば準備完了です。**● Ready** の後ろには使っているデバイスが表示されます。読み込み中は **● Loading** と表示されます。

エンジンが起動しないときは、[トラブルシュート](troubleshooting.html)を参照してください。

## 画面を日本語にする

画面の表示は初期設定では英語です。日本語に切り替えるには、画面右上の **⚙ Settings** を押し、**Language** タブの **Language** で **日本語 (ja_JP, built in)** を選んで **OK** を押します。再起動せずに画面が切り替わります。

英語に戻す場合は、同じ欄で **None (English)** を選びます。日本語と英語を併記する方法や、翻訳されない箇所については[表示言語](localization.html)を参照してください。

## モデルを置く

モデルは、アプリのフォルダ（配布版では展開した `SDNUI` フォルダ）の `models` の下に、種類ごとに置きます。サブフォルダも検索されます。

| フォルダ | 置くもの |
|---|---|
| `models/checkpoints/` | チェックポイント（`.safetensors`、`.ckpt`） |
| `models/vae/` | VAE |
| `models/lora/` | LoRA |
| `models/controlnet/` | ControlNet。IP-Adapter、ControlNet-LLLite、Fooocus inpaint パッチもここに置きます |
| `models/embeddings/` | Textual Inversion |
| `models/upscalers/` | ESRGAN / SwinIR / SCUNet / DAT 系のアップスケーラー |
| `models/face_restore/` | GFPGAN / CodeFormer（Restore faces 用） |
| `models/svd/` | Stable Video Diffusion |
| `models/zero123/` | Zero123 系（diffusers 形式のフォルダ） |
| `models/photomaker/` | PhotoMaker v1 アダプタ（`photomaker-v1.bin`） |
| `models/taesd/` | TAESD（ライブプレビュー用、任意） |

Civitai などからダウンロードしたモデルは、対応するフォルダにそのまま置けば使えます。ファイルを置いたあとは、上部バーの **⟳**（Rescan model folders）を押すと一覧が更新されます。モデルを配布しているそれぞれのライセンスは守ってください。

次のものは、初めて使うときに Hugging Face から自動で取得します。

- SD1.5 / SDXL の単体ファイルを読み込むためのベース設定とトークナイザー
- Revision・IP-Adapter の画像エンコーダ、BLIP、LDSR、Marigold

一度取得したあとは、オフラインでも使えます（[トラブルシュート](troubleshooting.html#offline)を参照）。

### 既存の A1111 / reForge のモデルを使う

A1111 や reForge をすでに使っている場合は、モデルをコピーせずにそのフォルダを読み取り専用で参照できます。

1. 画面右上の **⚙**（Settings）を押します。
2. **Model folders** タブを開きます。
3. **Add A1111/reForge install…** を押し、A1111 / reForge のインストール先（例: `D:\stable-diffusion-webui-reForge`）を選びます。
4. **OK** を押します。

次のフォルダがまとめて追加されます: `models/Stable-diffusion`、`models/VAE`、`models/Lora` と `models/LyCORIS`、`models/ControlNet`、`embeddings`、`models/ESRGAN` と `models/RealESRGAN`。

種類ごとにフォルダを個別に追加・削除することもできます。左の一覧で種類を選び、**＋ Add folder…** または **－ Remove** を押します。フォルダを変えて **OK** を押すと、モデルの一覧が読み込み直されます。

![Settings の Model folders タブ](shots/settings.png)

## dry-run モード {#dry-run}

dry-run モードでは、torch や diffusers を読み込まず、seed に応じたプレースホルダー画像を返します。モデルや GPU がなくても、生成、進捗表示、プレビュー、ギャラリー、PNG Info まで通して画面を試せます。

配布版では、コマンドプロンプトや PowerShell から次のように起動します。

```
SDNUI.exe --dry-run
```

!!! note "dry-run でも Python は必要です"
    配布版の dry-run も、動く Python インタープリターを使います。

dry-run で起動している間は、上部バーに **DRY RUN** の表示が出ます。セットアップ画面で **Use dry-run** を選んだ場合も同じです。

## ソースから実行する

Linux や macOS で使う場合や、開発する場合は、ソースからビルドします。

- JDK 17 以上（`JAVA_HOME` または `PATH` の `java`）
- sbt（ビルド済みの jar があれば不要）
- Python 3.10〜3.12

```
# ビルド（target/stablediffusion-nui.jar ができます）
scripts\build.bat          # Linux / macOS: scripts/build.sh

# 起動
run.bat                    # Linux / macOS: ./run.sh
```

`run.bat` / `run.sh` は Java 17 以上を探し、jar がなければ sbt でビルドしてから起動します。主なオプションは次のとおりです。

| オプション | 意味 |
|---|---|
| `--dry-run` | dry-run モードで起動します |
| `--home DIR` | アプリのフォルダ（`python/`、`models/`、`config/` があるフォルダ）を指定します。通常は不要です |
| `--mcp` | GUI を起動せず、MCP サーバー（stdio）として動きます |

JavaFX は OS ごとの jar をビルド時に取り込みます。別の OS 用の jar は、その OS でビルドしてください。
