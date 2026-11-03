# Panorama Tools の対応調査（#97）

対象は [Flyguygx/sd-webui-panorama-tools](https://github.com/Flyguygx/sd-webui-panorama-tools/tree/49118af2783bf25804155b88faf586d102c1a21c)、revision `49118af2783bf25804155b88faf586d102c1a21c`、MIT。README の機能を基準に、現行 Panorama タブとの差を確認した。以下は実装調査であり、互換性の検証や issue 完了を意味しない。

| 原典の機能 | 現状 |
| --- | --- |
| 正距円筒画像の読み込み、3D 表示、yaw/pitch/FOV、ドラッグ・ホイール | Panorama タブにあり、#21 の静止画・実画像の既存検証あり。 |
| 全画面ビュー | Panorama タブにあり、静止画は検証済み。動画では表示まで確認、Escape 自動操作は未確認。 |
| 六方向 Front/Back/Left/Right/Up/Down | FOV を維持する6ボタンを作業ブランチに追加。実 GUI で全方向と全画面共有、動画中の Up を確認。 |
| txt2img / img2img / Extras から入力 | ギャラリー・画像ブラウザの panorama 送信とファイル読込はある。各タブからの同等経路は要確認。 |
| 3D preview を img2img / inpaint / Extras へ送る | 作業ブランチで元画像と視点画像の操作を分け、投影 PNG の保存後に3送信先へ渡す。下記の範囲で検証。 |
| 2D panorama を各タブへ送る | 元ファイルの送信はある。調整・合成済みの 2D preview はない。 |
| 任意視点の画像を球面へ再投影して合成、マスクのぼかし、適用と巻き戻し | 独立 worktree に静止正距円筒への現視点合成を試作。辺の線形 feather で、上流 mask blur の再現ではない。undo と保存した視点の自動復元は未実装。 |
| panorama の pitch/yaw 再配置、上下 pole offset | 未実装。 |
| preview と panorama の解像度を独立指定・入力から導出 | 未実装。 |
| cubemap face 別出力 | 4:3 の展開図への変換はあるが、6 枚別々の出力とは異なる。 |
| 球面上の sketch 描画・消しゴム・undo・ControlNet Unit 0 へ送信 | 未実装。 |

まず既存のカメラ状態を使う六方向の操作を追加する候補とする。正距円筒／cubemap と動画で同じ視点を使い、現在の FOV を維持し、全画面と同期することを検証する。その他の主要な編集・sketch 機能はこの作業の完了対象として残るため、六方向のみで #97 を閉じない。GPU と sbt の使用は他担当と事前調整し、必要な実 GUI・実画像・スクリーンショットを確認する。

## 六方向の試験（2026-11-03）

方向ボタンのない版では実 MainWindow テストが最初のボタンを見つけられず RED。追加後は同じ `RealPanoramaCheck` が `PANORAMA CHECK PASSED (22 groups)`、exit 0。実 SD1.5／SDXL の生成済み静止画で六方向と FOV 67° の保持、全画面共有と Esc、投影・変換・保護を確認した。`outputs/panorama97/gui/pano-00-directions.png` と狭い画面の画像を目視。生成モデルの再実行ではない。`RealPanoramaVideoCheck` も `REAL_PANORAMA_VIDEO_OK`、exit 0。赤／青動画の pause 中に Up と FOV 70° 保持を確認し、従来の動画操作・SD1.5／SDXL 静止画から作った反復動画も通過。動きのある一般動画や配布 exe は未検証。

## 視点画像の送信（2026-11-03）

`Source →` は従来の元画像、`View →` は押下時の raster・方向・FOV・表示縦横比を固定して 1600 px 幅の PNG を作り、保存完了後に img2img / Inpaint / Extras へ渡す。成功時は output/panorama の一意な PNG を残す。保存失敗なら対象画像は変えず、作成途中の当該ファイルを削除する。処理中にタブを dispose すると送信せずファイルを削除する。削除失敗は標準エラーへ記録され残存しうる。送り先の処理が例外を返した場合は変更済みの可能性を考慮しファイルを残して位置を表示する。異常終了・非協調な外部操作に対する cleanup は保証しない。

実 MainWindow（dry-run worker）の `RealPanoramaCheck` は 25 groups pass、元の3送信を維持し、SDXL 画像の3送信先で生成 PNG の画素を参照投影と比較、保存先がディレクトリでない際の非送信、保護セッション時の暗号化、dispose 中の非送信と当該ファイル削除を確認した。`RealPanoramaVideoCheck` は pause 中の合成青フレームを img2img へ送って画素を確認し `REAL_PANORAMA_VIDEO_OK`、exit 0。狭い画面のスクリーンショットも目視。配布 exe、一般動画、フレームの時間的正確さや強制終了は未検証。画像の生成自体は今回行っていない。

極端な viewport 寸法により過大な画像ができないよう、出力の幅と高さは最大 4096、総画素は最大 6,553,600 とし、未 layout・非有限・範囲外は拒否する。境界の unit test は GREEN。送信時は保護 session の revision を保存前と受渡し直前に検査し、変化していれば送信せず作成済みファイルを削除する。実 MainWindow 27 groups pass で保存前の変更と保存開始後・FX callback 前の変更をそれぞれ同期して再現し、非送信と後者のファイル削除を確認。動画試験も最終変更後 `REAL_PANORAMA_VIDEO_OK`。session の同期は PNG 書込中と受渡し中に保持するため、設定切替が同時に来た場合はその終了まで待つ。強制終了時や削除失敗時の残存、複数 process の非協調操作、ファイルやディレクトリの敵対的置換は保証しない。

## 視点画像の合成（試作、未統合）

静止した正距円筒の画像に、現在の yaw / pitch / FOV で外部画像を逆投影し、新規 PNG に保存する。元画像は変更せず、適用後は視点を保つ。透過 alpha と線形の辺 feather を扱うが、原典の mask blur と同じ結果ではない。入力は幅 16384、画素 16,777,216 まで。実 MainWindow（dry-run）の 33 groups では SDXL 既存画像と合成用単色画像、視点保持、Cancel、入力切替と保護 session 変更時の破棄、保護出力と保存失敗を確認した。条件・CPU 上限の単回試験と未検証範囲は `superpowers/plans/2026-11-03-panorama-compose97.md`。


## 2026-11-03 保護セッションの追加検証（WIP）

保護画像を表示した後にセッションをロックし、表示済みの復号データから視点合成を始めると平文保存され得る問題を、実 GUI 回帰で修正前に再現した。読み込み開始時の revision を表示画像に紐付け、異なる revision では合成、視点保存・送信、変換を拒否する試作を追加した。修正後 `RealPanoramaCheck` は 34 groups 成功し、ロック後の 4 操作の拒否と、視点描画中のセッション変更による保存拒否を確認した。変換の保存中・表示反映前のセッション変更は未検証。表示済み画像そのものをロック時に消去することは保証していない。

**当時の未解決事項（下記の追補で対応）**: 合成用画像の寸法チェックが完全 decode 後であり、巨大画像のメモリ確保を防がなかった。読み込み完了と session 切替の競合、他操作の終了時の厳密な検証、SD1.5/SDXL 実生成画像を使った合成と配布版での操作も未確認。issue #97 は未完了。


## 2026-11-03 合成画像の読込上限（WIP 追補）

合成画像を最大 64 MiB のバイト列として一度だけ読み、ImageIO が解釈できる画像について、デコード前に幅・高さ各 16384 以下、合計 16,777,216 画素以下と確認する。同じバイト列から保護情報を確認して復号するため、検査後に元のパスが差し替わっても別の内容を decode しない。ImageIO がヘッダーを読めない形式はエラーとするため、JavaFX で開けた形式の一部は合成入力として使えない可能性がある。元画像の通常表示の読込には、この新しい制限は適用していない。

core の新規 5 件と既存の scramble 13 件、合計 18 件が通過し、UI compile も成功。GUI の 35 groups で、大きすぎるヘッダーの拒否後、通常画像の合成と保存を確認した。ファイル差替えは snapshot 取得後に元ファイルを上書きする試験で確認した。実モデル生成・配布版での検証ではない。

PNG の圧縮テキスト等のメタデータ展開は、この時点では画素数とファイルサイズの制限だけでは展開後のメモリ量を保証できなかった（下記追補で対応）。保護表示とセッション切替の厳密な競合・変換途中の切替、SD1.5/SDXL の実生成画像を使った合成、配布版は引き続き未検証。issue #97 の他機能も未実装であり、完了にはしない。


## 2026-11-03 合成入力 PNG のメタデータ上限

PNG の全 chunk を ImageIO より先に走査し、非画像メタデータと zTXt・iTXt・iCCP の展開量の合計を 1 MiB までに制限する。展開は少量ずつ数え、超過または壊れた圧縮データを拒否する。PNG 以外のメタデータ展開は対象外。圧縮 text/profile の 2 MiB 展開を拒否する試験、通常圧縮 text、保護 PNG を含む core 19 件と UI compile が成功した。


## 2026-11-03 実モデルと配布候補の追加確認

`a4b7276` を含む候補 JAR の SHA-256 は `f0708d5c1f3e0a5cb5b283d0ea2a19c26dd6ed233993be5393155d3a6b47a603`。独立出力 ZIP の SHA-256 は `a0b54d476ece381ff41e821a41e31ef5bab354b589c1c95f80d1819d895400a9`、展開後の JAR は同一 SHA-256、変更対象の class 7 件はコンパイル出力と byte 一致。これは非公開の候補で、リリースではない。

展開 EXE の MCP stdio から実 SD1.5（animekawa_v10、1024×512）と SDXL（waiIllustriousSDXL_v170、1536×768）で 8 steps の新規画像を生成し、両画像を目視した。生成画像 SHA-256 はそれぞれ `997ec81e677c59388849430e0dc99142e0c085e7e8d62a95d9a5ebd58b740a61`、`234cf78868a508dfce092f3c696dfc108f595986f00e2c0edc1615ed180b12ac`。source-class の MainWindow GUI でこの 2 枚を各々 patch として SDXL panorama に適用し、両方 35 groups pass、出力画素と原画像保持を確認し、スクリーンショットを目視した（`outputs/panorama97/fresh-gui-sd15/pano-compose.png`、`fresh-gui-sdxl/pano-compose.png`）。画像は 2:1 の生成結果であり、真の全周対応パノラマの品質を保証しない。

候補 ZIP を展開した EXE の GUI でも SDXL 生成画像を Panorama に送り、Windows の chooser から SD1.5 生成画像を適用し保存できた。原画像と合成画像はどちらも 1536×768、RGB の差分 bbox は `(576,271,960,497)`、画面は `outputs/panorama97/package-gui-compose.png`。この回は隔離 home の python/sdfx 未配置により GUI が engine crashed と表示されており、既存画像を使う offline 合成のみの検証。配布物の python/sdfx を隔離 home に配置して再起動すると同 EXE の GUI が CUDA engine ready、合成済み画像を再表示できた。ユーザー操作が検出されたため、その後の GUI 操作は停止し、試験で起動した process と所有 GPU lock を整理した。engine ready 状態での GUI 合成は未再試験、生成実行は上記 MCP のみ。

PNG 以外のメタデータ展開の制限、セッション切替の残る競合、上流の pole correction・sketch・undo などは未完了。issue #97 は open のまま。
