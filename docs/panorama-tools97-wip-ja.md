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
| 任意視点の画像を球面へ再投影して合成、マスクのぼかし、適用と巻き戻し | 未実装。 |
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
