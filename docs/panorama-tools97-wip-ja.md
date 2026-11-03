# Panorama Tools の対応調査（#97）

対象は [Flyguygx/sd-webui-panorama-tools](https://github.com/Flyguygx/sd-webui-panorama-tools/tree/49118af2783bf25804155b88faf586d102c1a21c)、revision `49118af2783bf25804155b88faf586d102c1a21c`、MIT。README の機能を基準に、現行 Panorama タブとの差を確認した。以下は実装調査であり、互換性の検証や issue 完了を意味しない。

| 原典の機能 | 現状 |
| --- | --- |
| 正距円筒画像の読み込み、3D 表示、yaw/pitch/FOV、ドラッグ・ホイール | Panorama タブにあり、#21 の静止画・実画像の既存検証あり。 |
| 全画面ビュー | Panorama タブにあり、静止画は検証済み。動画では表示まで確認、Escape 自動操作は未確認。 |
| 六方向 Front/Back/Left/Right/Up/Down | FOV を維持する6ボタンを作業ブランチに追加。実 GUI で全方向と全画面共有、動画中の Up を確認。 |
| txt2img / img2img / Extras から入力 | ギャラリー・画像ブラウザの panorama 送信とファイル読込はある。各タブからの同等経路は要確認。 |
| 3D preview を img2img / inpaint / Extras へ送る | 現在の送信は元の画像ファイルで、視点を投影した画像ではない。 |
| 2D panorama を各タブへ送る | 元ファイルの送信はある。調整・合成済みの 2D preview はない。 |
| 任意視点の画像を球面へ再投影して合成、マスクのぼかし、適用と巻き戻し | 未実装。 |
| panorama の pitch/yaw 再配置、上下 pole offset | 未実装。 |
| preview と panorama の解像度を独立指定・入力から導出 | 未実装。 |
| cubemap face 別出力 | 4:3 の展開図への変換はあるが、6 枚別々の出力とは異なる。 |
| 球面上の sketch 描画・消しゴム・undo・ControlNet Unit 0 へ送信 | 未実装。 |

まず既存のカメラ状態を使う六方向の操作を追加する候補とする。正距円筒／cubemap と動画で同じ視点を使い、現在の FOV を維持し、全画面と同期することを検証する。その他の主要な編集・sketch 機能はこの作業の完了対象として残るため、六方向のみで #97 を閉じない。GPU と sbt の使用は他担当と事前調整し、必要な実 GUI・実画像・スクリーンショットを確認する。

## 六方向の試験（2026-11-03）

方向ボタンのない版では実 MainWindow テストが最初のボタンを見つけられず RED。追加後は同じ `RealPanoramaCheck` が `PANORAMA CHECK PASSED (22 groups)`、exit 0。実 SD1.5／SDXL の生成済み静止画で六方向と FOV 67° の保持、全画面共有と Esc、投影・変換・保護を確認した。`outputs/panorama97/gui/pano-00-directions.png` と狭い画面の画像を目視。生成モデルの再実行ではない。`RealPanoramaVideoCheck` も `REAL_PANORAMA_VIDEO_OK`、exit 0。赤／青動画の pause 中に Up と FOV 70° 保持を確認し、従来の動画操作・SD1.5／SDXL 静止画から作った反復動画も通過。動きのある一般動画や配布 exe は未検証。
