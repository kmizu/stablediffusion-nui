# MCP Fastload inspect の画像・データ入力

`controlnet_fastload` の `inspect` は、許可フォルダの入力を検査した後、同じパスを別プロセスで開くまでに差し替えられる可能性があった。Windows / Linux で実体を検証した handle から最大 48 MiB を読み、バイトと拡張子を保った一時 snapshot を worker に渡す。保護 PNG は拒否する。worker が完了するまで一時ファイルを保持し、呼び出しがタイムアウトしても worker 終了時に片付ける。

同じ OS ユーザーの他プロセスがサーバーの一時ファイルを直接変更できる場合は保護しない。Windows と Linux 以外や必要な verified handle helper を使えない環境では path 入力を拒否する。Fastload inspect は path のみを受け取る。生成時の Fastload AlwaysOn を含む他の path 入力は、この変更の対象外。

2026-11-03 追補：保護 PNG の判定は一時パスの再読込でなく、元 handle から取得した同じ bytes で行い、保護されている場合は一時ファイルを作る前に拒否する。最新 main 基点の MCP suite 53/53 と、公式 SDK・dry-run worker の `.cni` inspect を再確認。JAR SHA-256 は `3f66fdd68187b4a2a5ae742b4f360a5a3c62cdd7459ba66548b03a095eb4e200`、対象 class と build 成果物の hash が一致。追補 JAR でも Linux WSL Ubuntu ext4 の stdio MCP `.cni` inspect が成功した。

2026-11-03、MCP suite 50/50 が通過し、byte 完全一致・タイムアウト後の保持と cleanup・保護 PNG 拒否を確認。Windows の公式 MCP SDK と実 dry-run worker を使い、作成した `.cni` の inspect、source hash 不変と一時ファイル消去を確認した。記録は `outputs/fastload-inspect-mcp/receipt.json`（ローカルの未追跡成果物）。同じ JAR の Linux WSL Ubuntu ext4 上の stdio JSON-RPC・実 dry-run worker でも `.cni` の inspect を確認。JAR SHA-256 `a4cceeba2fbbc174286141673b79a20896691a6d15ccd3097cf810879f3f04db`、対象 class と target class の hash が一致した。
