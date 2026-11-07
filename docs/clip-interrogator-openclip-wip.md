# #12 OpenCLIP 対応の作業途中

main `67d522f` を基点とする `codex/interrogator-openclip` の変更を main に部分統合した。issue #12 は open、全モデル対応の完了扱いではない。統合時、main で関連 Python 60件、Scala の候補表示2件、MCP 39件、`ui/assembly` が exit 0。JAR SHA-256 は `dd028cbd552c7faefe87e0621a752f3f4d96e1aea59f15b2d6dfd4bba142bf94`。以下の実モデル実測は分離 worktree 等で行ったもので、この main JAR や公開配布物の実測ではない。

既存の transformers モデルを維持し、別途インストールした `open_clip_torch` の pretrained registry を候補に追加して、画像・テキストの正規化済み特徴量を既存 Interrogator に渡す。後方互換のため既存 10 モデルは従来の transformers backend を優先する。optional dependency の import が失敗しても既存候補は利用可能。API 操作 `models` でインストール済み OpenCLIP registry を列挙するが、180 の候補すべてが実行可能と検証したわけではない。

実測候補 `convnext_base_w/laion2b_s13b_b82k`: [LAION model card](https://huggingface.co/laion/CLIP-convnext_base_w-laion2B-s13B-b82K) は MIT、非 gated。固定 revision `43cc4adea32de0ca94f71bfe1162eba29c824d6e` の `open_clip_model.safetensors` は 717.6 MB、SHA-256 `a7528aa0381ddcd2cf5791eb5efd8b695d109b57fbcf9f53fb464ab3950d86b0`。モデルは配布物に含めない。他の重みの利用条件は各モデルで異なるため、それぞれ確認する必要がある。

OpenCLIP 2.32.0（MIT、tag `v2.32.0` commit `bf5d49c112c82c738f7b34bde6e154760a711790`）、timm 1.0.15（Apache-2.0）、ftfy 6.3.1（Apache-2.0）、wcwidth 0.2.13（MIT）を `outputs/openclip-deps` に pip `--target --no-deps` で隔離した。既存 smoke venv に直接インストールしていない。追加の依存について `python/requirements.txt` と `scripts/third_party.py` の候補を追記し、各リリースの固定 commit におけるライセンス原文と SHA-256 を確認。`python scripts/third_party.py` は GitHub API の途中失敗で 2 回終了したが、その後 `--use-archived-licenses` で固定原文のハッシュを検証し notices を再生成した（242 projects、exit 0）。差分レビューが必要。

`test_clip_interrogator_openclip.py` と既存 `test_clip_interrogator.py`、`test_third_party_pins.py`、`test_third_party.py` は計 49 件 pass。新 backend の不正入力、registry、欠損・壊れた optional 依存、offline 拒否、fake features 正規化、token 長、cache 無効化、悪意ある pickle、通常の pt/safetensors、失敗時・並行時・factory の API 変更を対象にした。

実 SD1.5 と SDXL で既に生成して目視済みの 2 画像を入力に、CUDA で `scripts/dev/check_openclip_interrogator_real.py` を実行し、ConvNeXt と BLIP-base を使う analyze / negative / fast が通過。結果 `outputs/openclip-real/receipt.json`、`OPENCLIP_ENGINE_OK`、exit 0。これは実 engine のエントリ関数を同一 process から呼んだ結果で、disk cache 無効化前の試験。

disk cache 無効化後の最新版については `scripts/dev/check_openclip_mcp_real.py` を公式 MCP SDK の stdio transport、実 CUDA worker と 2 画像で実行し、モデル一覧・ConvNeXt + BLIP-base の fast、同一 session 継続と unload が通過（`OPENCLIP_MCP_OK`、exit 0）。結果は `outputs/openclip-mcp/receipt.json`。jar は main checkout の `target/stablediffusion-nui.jar`（SHA-256 `2b5628c228274e26edc126c7dfd3bb3c5b7d5dd3f301a8d994244574faf6bf69`）、engine はこの worktree の Python、optional 依存は `outputs/openclip-deps` を `PYTHONPATH` に指定した。パッケージの EXE、GUI、別の OpenCLIP モデルの実測ではない。2 画像は元画像そのままで、SD1.5 は青髪の人物、SDXL は人物を覆う黒赤メッシュ。

共有 OpenCLIP module を書き換えず private namespace に複製した安全 factory に変更後も、同じ方法で `outputs/openclip-mcp-private/receipt.json` に `OPENCLIP_MCP_OK` と exit 0 を確認。これも safetensors を使った実測で、pt 実モデルの検証ではない。

別の実 checkpoint 候補: [timm/resnet50_clip.yfcc15m](https://huggingface.co/timm/resnet50_clip.yfcc15m) のモデルカードは MIT・非 gated。固定 revision `c94a9e3bde942b58c1cd13bb0a03d3c1ff4cb056` の `open_clip_pytorch_model.bin` 408.4 MB、SHA-256 `5382df9e2fc3db2b22bcec023c24bbb87307e8554f131c5b5d8567b09ccf1e0c` を、`openclip_safe_state_dict` と `weights_only=True` で CPU 読み込みし、489 テンソルと text projection の形状 `(512, 1024)` を確認。最初の GPU 試行は pretrained とモデルの QuickGELU 設定が違うという警告が出たため受入に数えない。pretrained config の指定を渡す修正後、`check_openclip_pt_cpu.py` で CPU 上の実モデルに QuickGELU が入り、SD1.5 画像とテキストの特徴量が有限であることを確認（`OPENCLIP_REAL_PT_CPU_OK`、`outputs/openclip-rn50-cpu/receipt.json`）。続けて `check_openclip_interrogator_real.py` の実 CUDA engine-entry で SD1.5・SDXL 画像の analyze／negative／BLIP-base fast を実行し、QuickGELU 警告なしで `OPENCLIP_ENGINE_OK`・exit 0（`outputs/openclip-real-rn50-quick/receipt.json`）。ローカル checkpoint を選ばせるため、いずれもテスト専用 process 内のダウンロード関数を差し替えた。

`RealOpenClipInterrogatorCheck` は実 MainWindow で ConvNeXt と BLIP-base を選び、2 画像を fast で処理し txt2img に送信。`REAL_OPENCLIP_GUI_OK`、exit 0、`outputs/openclip-gui/receipt.json`。生成結果を送った後の `gui-0.png` / `gui-1.png` を目視し、両方のプロンプトが入力欄に現れたことを確認した。画面は送信先であり Interrogator の選択画面ではない。次回のため送信前の Interrogator 画面も `interrogator-0.png` / `interrogator-1.png` に保存するよう harness を変更したが、この変更後の実行はまだ。実 .bin の GUI 選択や抽出版 GUI は未確認。

重要な残件:

- 既存 label cache は labels だけで検証し、重み fingerprint と特徴量次元を保証しない。異なる重みを誤再利用しないため **OpenCLIP の disk cache は一時的に全無効**（`keep_loaded` は同一 session 内で機能）。各ロード後の重み・構成・tokenizer・前処理・バージョンと次元を結びつける方式を設計・検証するまで cache 再利用は有効にしない。変換版の既存 cache は変更していない。
- OpenCLIP の URL-only 重みと、HF tokenizer 指定のない SigLIP の直 HTTP tokenizer を offline で拒否するが、他の tokenizer や依存の直接通信、URL と HF の両方がある場合、pickle fallback、モデルごとの precision や変換結果などを全 registry について確認していない。必要に応じて安全に限定・明示してから対応範囲を広げる。
- OpenCLIP 2.32.0 の `factory.load_state_dict` は `TypeError` 時に引数なしで `torch.load` にフォールバックする。対策として factory 3 関数を独立した globals で呼び、安全 loader の `weights_only=True` を必須とする。safetensors と通常 pt の小さな fixture、悪意ある pickle と TypeError の拒否、例外・並行時に共有 loader が変更されないことをテストした。OpenCLIP は 2.32.0 に固定し、異なる版、factory 関数や call chain が変わったときは拒否する。これは内部 API に依存し、将来版は別途検証が必要。JIT checkpoint は未対応、実モデルの pt および全モデルを安全に読み込めるとは主張しない。
- 欠けている／動かない依存の明確な UI 表示、モデル個々のライセンスと入手先、一般的な OpenCLIP/SigLIP/RN50/EVA/CoCa などの実モデル互換性、低 VRAM、ダウンロードの中断と cleanup、リアル MCP／GUI と配布版の試験が必要。新モデルを一覧に載せただけで成功としない。
- 最新 main を取り込み、独立レビューと全回帰を実施。`docs/EXTENSIONS.md` と `scripts/extensions_matrix.py` の元文はまだ旧範囲。全範囲を再確認するまで #12 は open。

## 2026-11-03: Hub 通信と候補の情報表示（作業中）

OpenCLIP 2.32.0 の 180 registry entries はすべて HF Hub の参照を持ち、52 件は直接 URL も併記されていた。upstream downloader は Hub 未導入時に直接 URL を使い、不完全ファイルが残り得る。private factory の `download_pretrained` を独自の HF Hub 直結関数に差し替え、直接 URL fallback を通さない。Hub 未導入・参照なしを拒否するが、当該版の 180 entries は参照を持つので名前を除外しない。既存 10 の transformers 候補は別経路のまま。Hub 失敗が伝播し次の試行で回復する unit test、`HF_HUB_OFFLINE=1`・socket connect を禁止した実 Hub の cache hit / miss test（`OPENCLIP_OFFLINE_OK`）を行った。実 HTTP 切断・競合・大きなファイルの部分書き込みを再現した試験ではない。

さらに OpenCLIP 標準の HF wrapper が safetensors の例外をすべて握りつぶして bin に切り替える点を修正。明示的なファイル不存在だけで別形式へ切り替え、online の接続失敗では切り替えず、offline は `local_files_only=True` を両形式に渡す。実 `huggingface_hub` cache 実装に疑似 stream 中断を注入し、部分ファイルが公開されないこと、次回 4 bytes から再開し完成後は cache hit になることを確認。外部 HTTP サーバとの通信切断ではなく、stream 関数を差し替えた試験。

この変更後の Python と root 既存 jar の組合せで公式 MCP SDK の stdio 実測を行い、model card metadata、存在しないモデルの拒否後の回復、ConvNeXt + BLIP-base による SD1.5／SDXL 由来画像 2 枚の解析と unload を確認した（`outputs/openclip-mcp-3c83502/receipt.json`、`OPENCLIP_MCP_OK`）。最初は存在しない Java パスを指定して起動に失敗し、`C:/dev/Java/BellSoft/LibericaJDK-21/bin/java.exe` で再実行した。配布物の試験ではない。

同変更後、実 RN50/yfcc15m `.bin` を指定した CPU 特徴量計算も成功（`outputs/openclip-rn50-cpu-3c83502/receipt.json`、`OPENCLIP_REAL_PT_CPU_OK`）。ダウンロード関数をローカル重みへ差し替えたため、ネットワーク取得の確認ではない。

最新 downloader と日本語切替を含む実 MainWindow テストも SD1.5／SDXL 由来画像 2 枚で成功（`REAL_OPENCLIP_GUI_OK`、notice suite 2/2、`outputs/openclip-gui-53dc152/receipt.json`）。`interrogator-1.png` を目視し、日本語のモデル検証範囲・入手元・ライセンス表記と生成プロンプトを確認した。これはソースから起動した GUI であり、配布版の実行確認ではない。

`audit_openclip_registry.py` で公開モデルカード metadata を取得し、180 entries／152 repo、失敗 0、gated 表記 false 180。カードのライセンスラベルは MIT 103、Apache-2.0 41、CC-BY-NC-4.0 23、apple-amlr 13（2026-11-03、`outputs/openclip-registry-audit2.json`）。最初の取得は metadata 変換コードの誤りで全件 `KeyError` だったため使っていない。モデルごとの repo・revision・ラベルは `python/sdfx/data/clip_interrogator/openclip_registry_licenses.json` に保存し、registry の参照 repo が一致する場合だけ提示する。これは調査時点のカード記載で、重みが将来異なる revision になる可能性があり、使用許可や現時点の条件を保証しない。各 model card のリンク先で最新条件を確認する。

候補を隠さず、追加パッケージ状態、各候補の旧 build での確認範囲／未試験、モデルカードのラベルと日付、HF repo を表示する UI を追加。Python 60 tests、notice unit 2 件、MCP suite 39 件が pass。テスト ZIP `outputs/openclip-payload-check/build-8a1bb8df721440cd874e728dd718f247/SDNUI-v0.1-windows-x64.zip` は SHA-256 `7c3ff6dd9ed93166018e4d2e386680f060f80b706f4286baf339dad246642c6a`、snapshot JSON は ZIP 内と source で byte 一致（SHA-256 `125a57c41ae0142e7cbbce6abc31964bc616598b890cb58aa4fdbd6df205a0dd`）。これは root の既存 jar を使った payload 検査であり、最新の UI jar や配布版の実行を検証したものではない。JIT checkpoint、全 tokenizer／モデルのネットワーク経路、実 HTTP 切断と cache の復旧、依存不足 UI の実動作は未検証。

再開: `PYTHONPATH=<worktree>/python;<worktree>/outputs/openclip-deps` と smoke Python を指定。実 GPU 使用前に共通の `outputs/.gpu.lock` を原子的に取得し、所有者のみ削除。対象 2 画像とコマンドは script の `--help` およびこの worktree のセッション履歴を参照。GitHub への書き込みやモデルの再配布はしていない。
