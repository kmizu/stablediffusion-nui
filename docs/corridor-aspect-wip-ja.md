# Corridor Crawler の縦横比対応（#25、作業中）

上流 `brick2face/corridor-crawler-outpainting` の固定 revision `680aed7e96fd2785387949222acbb25dee16b332` は、元画像を指定サイズ内に縦横比を保って縮小し、拡大せず左上に貼る。既存 NUI は出力サイズへ伸ばした後、窓全体に伸ばしていた。

「Previous image placement」で `Preserve aspect` を選ぶと、元画像の縦横比を維持し、実際の貼付範囲に合わせて各段の保護マスクを作る。余った窓部分は再生成対象。初期画像が小さければ拡大しない。GIF は各段の貼付位置を使い、フレームを固定サイズに収めるため余白を黒で表示する。既存 GIF を上書きしないよう、完成した一時ファイルを hard link で公開する。hard link を使えない場合は新規作成専用のコピーに切り替える。コピー途中の失敗では不完全な GIF が残り得るため、エラーで場所を表示する。既定の `Stretch` は従来どおり。画像の貼付位置と方式は各 PNG の infotext に記録する。

最初の回帰は修正前に 22 成功・1 期待どおり失敗、最初の修正後に 23/23 成功。その後、元画像が出力サイズと異なる場合、複数段、マスク縮小の事前検査、GIF の縦横比に関する追加修正とテストを行っており、**追加試験では GIF の既存ファイル上書きを再現。対策後、縦横比・2段目のマスク・事前拒否・GIF の黒帯・衝突拒否・一時ファイル cleanup を含む 30/30 件が通過した（hard link 非対応とコピー失敗、read と close の同時失敗は注入テスト）。GIF 衝突後も生成画像が残り、元 GIF を変えず中間 composite／mask が削除されることも確認**。GUI と配布版はこの変更について未確認。

#25 全体には、上流が想定する depth-to-image モデルの非対応、実 GUI や配布版の確認などが残る。`docs/corridor-crawler-verification.md` の従来の実モデル結果は、この追加機能の検証ではない。上流の AGPL ライセンスは既存文書と notices を参照し、実装コードのコピーは行っていない。

PNG の infotext に placement と実際の貼付矩形は残るが、既存の PNG Restore は Corridor Crawler のスクリプト設定や初期画像を復元しない。MCP 検査では未適用項目として表示される。

## 実 MCP 検証（2026-11-03）

fallback 追加前の Java 21 assembly の JAR SHA-256 は `d1d692c264e3f34418efda66f5e55f12f439f63c1f64f7b87011ef606ad8fa0f`。`CorridorCrawlerPlugin` 関連 class 7 件をコンパイル出力と照合した。公式 MCP SDK の stdio で SD1.5 `animekawa_v10`（512×512）、SDXL `waiIllustriousSDXL_v170`（832×1216）を使用。各モデルで生成した画像を 160×80 に縮小して初期画像とし、12 steps、seed 4242、denoising 0.9、2 段の `Preserve aspect` と 5 フレーム GIF を確認した。最初の矩形が 160×80、2 段目が生成画像の縦横比に沿うことを infotext で確認。receipt は `outputs/corridor-aspect-sd15/receipt.json` と `outputs/corridor-aspect-sdxl/receipt.json`、ログは同名 `.log`。

2 段の画像を両モデルとも目視した。小さな初期画像が左上寄りの窓として残り、その矩形の境界が明瞭に見える。SDXL の結果は暗いトンネルで、境界も強い。画像の継ぎ目が目立たないことは確認できていない。fallback と read/close 対応後の JAR SHA-256 `98517b176d3bbdda03383fd47680ac7f8db368b790be706db3a9c7939efb1a4a` も 7 class を照合し、同じ両モデルで 2 段生成と GIF を再検証した。ログは `outputs/corridor-aspect-final-sd15.log` と `outputs/corridor-aspect-final-sdxl.log`、receipt は同名ディレクトリにある。出力画像を目視し、上記と同様の目立つ境界を確認した。SDK は stdio のみ、実 GUI・配布版と depth-to-image は未検証。
