# MCP の PNG メタデータ入力の検証

`inspect_generation_metadata` の画像パスは、許可フォルダの確認後、実際に開いたファイルのハンドルを検証し、そのハンドルから最大 16 MiB を読み取る。保護画像の判定と PNG テキストの解析には同じ読み取り済みデータを使う。解析用の一時 PNG を作成してパスから開き直す処理は使わない。base64 入力は従来どおり対応し、保護画像のメタデータは返さない。

Windows と Linux の検証済みハンドル実装を使えない環境では、画像パスによる読み取りを拒否する。必要なら PNG の base64 を渡せる。許可ルート自体の非協調な差し替えや、ほかの MCP 読み取り経路まで保証するものではない。

## 検証状態

Windows のテストで、許可済みパスを解決した直後に親フォルダを許可外の junction に付け替えたところ、修正前は拒否されずテストが失敗した（RED）。Java 21 の修正後テストでは、junction 差し替えの拒否、16 MiB 上限、既存メタデータの読み取り、base64 入力、保護画像拒否を含む 35 件が通過した。source-built JAR の関連 class 14 件をコンパイル出力と byte 比較し、一致を確認した。JAR SHA-256 は `d7193e26099f3144e2cf2ade4402c1b115c424b37102c6274e8645276991957d`。公式 MCP SDK の stdio と HTTP で合成 PNG のパス入力／base64 の結果一致と許可外パスの拒否を確認した（`outputs/metadata-verified-stdio.log`、`outputs/metadata-verified-http.log`）。実モデルによる新規生成ではなく、dry-run サーバーでの検証。
