# Corridor Crawler PNG 設定の復元（#25、作業中）

新しく生成した PNG に version 1 の Corridor 設定を記録する。段数・素材種別・窓の割合と位置・貼付方式・マスク縮小・グリッド・GIF 設定を含める。初期画像とカスタム素材のパスや画像本体は保存しない。旧版 PNG の矩形情報だけでは設定を完全に復元できないため推測しない。

GUI の Restore は Img2Img を対象にし、そのタブに残っていた入力画像と素材パスを消して選び直しを促す。Inpaint の入力は変更しない。画像を明示的に Img2Img へ送る操作では送った画像を新たな入力にする（元画像の復元ではない）。MCP の `inspect_generation_metadata` は入力不足の警告付きで typed な script 候補を返し、生成を始めない。カスタム素材も再指定が必要。

検証: plugin 32/32、MCP 34/34、UI VM 2/2 と assembly が通過。JAR SHA-256 `31869066b9b178b78291d263a453c3db558da7dedbd107d9944138c4640a152e` の関連 26 class をコンパイル出力と照合した。公式 SDK の stdio、SD1.5 `animekawa_v10` と SDXL `waiIllustriousSDXL_v170` で PNG の候補を読み、明示的に同じ初期画像と生成パラメータを与えて再実行したところ、2 段とも RGB が一致した。両モデルの画像を目視し、既報どおり矩形の継ぎ目を確認した。ログと receipt は `outputs/corridor-replay-sd15` および `outputs/corridor-replay-sdxl` にある。GPU lock は解放済み。生成済み SD1.5 PNG を隔離 home に複製し、公式 SDK の stdio・HTTP dry-run で path/base64 の結果一致と候補・入力不足警告も確認した（`outputs/corridor-replay-stdio-sdk.log`、`outputs/corridor-replay-http-sdk.log`）。実 GUI と配布候補は未確認。

上流が想定する depth-to-image は未対応、元 PNG の初期画像とカスタム素材は復元できない。#25 全体の完了を意味しない。
