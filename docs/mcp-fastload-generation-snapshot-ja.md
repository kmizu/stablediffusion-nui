# MCP の生成時 Fastload 入力

MCP の `always_on.controlnet_fastload` で読み込みが必要な場合、許可されたローカル入力を検証済み handle から読み、元バイトと拡張子を保った一時ファイルを job に渡す。保護 PNG は一時ファイル作成前に拒否する。生成の完了・失敗・中断後は job に属する一時ファイルを削除する。

Fastload が無効の場合、`save_only` の場合、現在の ControlNet が有効で `current_first` の場合は入力を読まない。入力サイズは 48 MiB 以下。Windows と Linux に対応し、その他の OS や必要な helper を使えない環境ではファイル入力を拒否する。サーバーの一時ディレクトリを同じ OS ユーザーの別プロセスが直接変更できる場合、その変更は防げない。

2026-11-03 の検証：AlwaysOn と MCP の focused/既存テスト 63/63。fake worker の中断で job 一時ファイルが削除されること、読み込み失敗時の cleanup と次の生成への復帰、同期 caller が中断されても worker 終了まで一時ファイルが残ることを確認。公式 MCP SDK で SD1.5 `animekawa_v10` と SDXL `waiIllustriousSDXL_v170` の実生成が成功し、入力 hash 不変と生成後 cleanup を確認した。生成画像を目視したところ、SDXL に大きな破綻はなかったが、SD1.5 は上端に黒帯があり原因は未確定。使用した `.cni` は無効化された ControlNet unit なので、実際の ControlNet 効果や画質の確認ではない。Linux WSL の stdio MCP と dry-run worker でも、同じ形式の `.cni` による生成と cleanup を確認。

最終候補の証拠は `outputs/fastload-generation-final-sd15/receipt.json`、`outputs/fastload-generation-final-sdxl/receipt.json`（ローカルの未追跡成果物）と `scripts/dev/check_fastload_generation_mcp.py`。対象 JAR SHA-256 `f95a0e8eb4b906108d41500bc130ebd650289d091fcd7a617d99ac04812eeaca` の変更 class と target class の hash は一致した。追補前後の生成結果は両モデルとも RGB ピクセルが一致した。
