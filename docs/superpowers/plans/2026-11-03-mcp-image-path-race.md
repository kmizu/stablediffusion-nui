# MCP 画像入力のパス置換競合（部分修正）

`Images.materialize` は `PathPolicy.resolve` でパスを検査し、画像を検査した後、同じパスを返す。後続の script と生成エンジンはそのパスを改めて開く。許可 root 内の親ディレクトリを検査後に root 外への Windows junction に差し替えると、後続読み込みは root 外のファイルを読む。

2026-11-03 の `mcp/testOnly sdfx.mcp.ImageInputRaceSuite` は、検査した画像の画素 `0x112233` を期待したところ、root 外の `0xaabbcc` を取得して RED。`Img2Img Workflow` の Guess および汎用 img2img 入力を含む、パスを後で再 open する経路が影響範囲。これはスクリプト追加前から存在する問題であり、安全な修正が必要。

`VerifiedFolderInput.copy` を使えば open された元 handle の実体を検査してそこから読める。ただしコピー先の `.tmp` の名前空間を攻撃者が変更できる場合、後続 open の前にそこを差し替えられるため、コピーだけで全競合を防いだとは言えない。元画像と中間画像について、一貫した handle 保持または信頼できる名前空間と、Python・plugin へのバイト受け渡しの安全性を設計する。Windows とその他の OS の両方で、未対応時は明示拒否の挙動を決める。

`Images.materialize` を利用する画像入力については、Windows または Linux の verified handle から読み取った内容を検証し、元バイトのまま一時ファイルにして以後の処理へ渡す部分修正を実装。親の差替えによる漏出の再現テストは修正後 GREEN。Linux は `/usr/bin/python3` の固定 helper が `openat` 相当と `O_NOFOLLOW`・`O_NONBLOCK`・`fstat` を使う。他 OS や helper を利用できない環境はローカル path を明示拒否し base64/data URI は利用可能。ローカル path は 48 MiB 以下。stage の変更を防ぐものではなく、同じユーザーでサーバーの `.tmp` を変更できるプロセスは引き続き対象外。既存 MCP suite 47 件、verified handle suite 8 件も通過。

`get_image`、PNG metadata 検査、VAE、Fastload と folder 等の別経路は未修正で、全 MCP path の解決を意味しない。Workflow flip は保留する。

公式 MCP SDK を使い、path 指定で SD1.5 animekawa_v10 と SDXL waiIllustriousSDXL_v170 の各 2 枚を img2img Workflow 経由で生成した。修正前の同一 source・seed・条件の生成結果と RGB ピクセルを SHA-256 で比較し、4 枚とも一致。`outputs/workflow62-snapshot-sd15/receipt.json` と `outputs/workflow62-snapshot-sdxl/receipt.json` にローカル記録を残した。JAR SHA-256 は `2b769048dfd317e488926d12ada1bf93d1b32411c6b4301f0f40be1d74d354ca`、変更 class とビルド成果物の hash は一致。実モデル確認はこの経路に限り、JPEG の EXIF 向きやアニメーション GIF を使う実モデル試験は未実施。stage の元ファイルパスを新たに provenance として記録する仕組みもない。

Linux は WSL Ubuntu ext4 上で、実際に JAR の `VerifiedFolderInput.copy` と `Images.materialize` を呼び、open 後の親 symlink 差替えに対する元バイト維持、open 前の外部 symlink 拒否、FIFO の非 blocking 拒否、通常 PNG のバイト完全一致を確認。さらに stdio JSON-RPC の img2img を dry-run engine で実行し `LINUX_MCP_SNAPSHOT_OK` を確認。検証プログラムは `scripts/dev/VerifiedLinuxJvmProbe.java`、`scripts/dev/check_verified_unix.py`、`scripts/dev/check_linux_mcp_snapshot.py`。Linux の実モデルは未検証。
