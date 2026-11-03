# VAE Tools の MCP 入力保護 WIP

`vae_tools` の入力とローカル Approx NN 重みは、許可フォルダを確認した後に別プロセスが親ディレクトリを差し替えると、従来は Python が許可外の別ファイルを開く可能性があった。修正候補は検証済み handle から原本 bytes を一時ファイルに写し、入力と重みの表示用パスを解決済みの元パスに保つ。保護 PNG は同じ bytes を使って拒否する。

制約: 各ファイル 48 MiB 以下。Windows と Linux の検証可能な環境で使用し、対応しない環境では拒否する。一時ファイルを同じ OS ユーザーが直接改変する脅威は対象外。取消や timeout 後も worker 完了まで一時ファイルを保持する。fake worker で caller thread 割込み、timeout、MCP cancel 通知を確認した。

検証: fake engine で入力 bytes、表示 path、割込み・timeout 後の保持と完了後の削除を含め 6/6 成功。Python の元パス表示と Approx NN の重み表示は CPU の小型 VAE で 2/2 成功。Linux 実モデル、配布版、故障時の保持は未検証。統合・完了と扱わない。

2026-11-03: `a263fb7` の Scala class 19 件を assembly JAR の bytes と照合し、JAR SHA-256 `a0f575285d7242116bcadd40b48b62b1c276421a929aa4abced607bd5d534465` を確認。Windows 公式 MCP SDK stdio で SD1.5 `animekawa_v10` 512×512、SDXL `waiIllustriousSDXL_v170` 832×832 を各 12 steps 生成し、VAE Full encode+decode を実行。元画像の SHA-256 と report の `input_sha256`、元 path と永続 report の値が一致し、元画像は変更されなかった。結果画像を両方目視し、雪の森の狐に明らかな破綻はなかった。receipt は `outputs/vae-snapshot-sdk-sd15/receipt.json` と `outputs/vae-snapshot-sdk-sdxl/receipt.json`。これは source-built JAR と現行 smoke venv であり、配布版や race/cancel の実証ではない。

追補: VAE の実際の snapshot helper で、許可親ディレクトリを path 解決後に外部 junction に交換する Windows 試験は明示拒否で 1/1 成功。fake worker が割込みを無視して 5 秒間ファイルを使う試験で、MCP cancel 通知・caller interrupt・2 秒 timeout は各々 worker 終了まで保持し、その後削除された（VAE focused 計 6/6）。

更新 JAR SHA-256 `9c76fde56db1ee5d1deda3f5dcbc7b66580b532db70740d75301d816aa7f15f6` は変更 class 21 件の bytes と照合済み。WSL Ubuntu の Java 21 と raw MCP stdio から byte 観察用 dry-run worker fixture を用い、同じ VAE tool で許可入力の元 bytes・元 label・別 snapshot および終了後の削除を確認（`/tmp/sdfx-linux-mcp-0hsb4chq/receipt.json`）。Linux 実モデル、配布版は未検証。更新 JAR の実モデル再試験は下記。

Linux の Java 21 で更新 JAR の VAE snapshot helper を直接呼び、path 解決直後に許可親を外部 symlink に差し替える試験も `LINUX_VAE_PARENT_SWAP_REJECTED` で拒否。これは helper 単独試験であり、raw MCP 通信と競合を同時に起こした試験ではない。再現 fixture は `scripts/dev/LinuxVaeRaceCheck.java`。

更新 JAR `9c76fde…` を公式 MCP SDK stdio から実行し、SD1.5 と SDXL とも生成→Full encode+decode、元 path と SHA-256、永続 report、元画像不変を再確認した。結果画像を両方目視し明らかな破綻なし。receipt は `outputs/vae-snapshot-sdk-final-sd15/receipt.json` と `outputs/vae-snapshot-sdk-final-sdxl/receipt.json`。source-built JAR であり配布版の確認ではない。
