# Panorama の視点画像を球面へ合成する試作（#97）

上流 `Flyguygx/sd-webui-panorama-tools` の README（revision `49118af2783bf25804155b88faf586d102c1a21c`, MIT）は、視点画像を指定 yaw / pitch / FOV でパノラマへ投影し、境界マスクをぼかして適用する機能を記載する。先に純粋な画素処理を追加し、UI や保護／保存は後段で扱う。既存 `Panorama.render` と同じ水平 FOV・視点方向・ピクセル中心を使い、正距円筒の各画素の方向から逆投影する。画像外と背面は元画素を保持。半透明入力は alpha 合成、境界フェザー幅 0 は硬い境界、正値は画像辺からの距離に基づく線形減衰（上流の blur と一致するとは主張しない）。

1. headless tests を先に作り、正面・左右・裏側の領域、±180°の継ぎ目、上下の極、元画像の変更なし、alpha とフェザー、invalid input を RED として確認。
2. 逆投影と premultiplied alpha sampling を独立に実装し、画素数・有限値・入力制限を確認。参照画像を render→再投影したときの有限誤差も測る。
3. 実 GUI の合成 UI、保存・保護・失敗・並行操作、角度の受渡し、パスと出力の上限を別途設計・検証。SD1.5/SDXL の画像と結果を目視する。sketch・極補正などは未対応のままで、これだけで issue は閉じない。

2026-11-03: 未実装で RED 後、独立 core 試作を追加。前後左右・継ぎ目・極・alpha とフェザー・透明色混入、複数方向の render→再投影→render の画素差、範囲外を確認。新規 6 + 既存 Panorama 14 の計 20 tests GREEN。配列を画素ごとに確保しない sampler としたが、大型画像・性能・メモリの実測、GUI、保護・同時操作・配布版は未検証。README の mask blur と同一の算法・境界ではなく、近似として明記する。

## UI の段階的接続案

静止の正距円筒画像を対象に、現在の yaw/pitch/FOV と境界幅を明示してローカル画像を選び、合成・一意な出力 PNG 保存後にプレビューを置換する。画像は既存の保護対応 ImageCopyReads から読み、操作開始時の元 raster、session revision とカメラを固定する。処理中に別画像を開く・dispose・保護 session を変更する場合は結果の採用を拒否し、その操作が作ったファイルのみ削除。既存の `View →` で送信した視点を自動記録して選ぶ UI、Undo、上流の mask blur と同じ数学、上流の2D preview は未実装。画像内の透明度は alpha 合成に使い、パノラマの情報は source から引き継ぐ。

正常時の画素参照・同一元画素保持・保護 PNG・session 変更・読み取り失敗・保存失敗・discard と cleanup は、実 MainWindow の RED→GREEN と source-class の検証で確認し、別途実 SD1.5 / SDXL の画像とスクリーンショットを確認する。GPU / sbt は順番を調整する。

2026-11-03 追補: base と patch に幅上限 16384、各 16,777,216 pixels の上限を設定し、行単位の取消を追加。base 幅超過の RED 後、新 core 6/6 と UI test compile は成功。Java 21 からコンパイル済み core class を直接呼ぶ CPU probe では 5792×2896（16,773,632 pixels）・1024² patch・feather 32、`-Xmx1g` で 298 ms、exit 0。本機の単回測定であり最大メモリや一般速度の保証ではない。対象 class SHA-256 `61fcad5a0fa4f8b926104c1d616a65e4ff19a85a8964ddf61362a4440ebc7f20`、ソース `5f1b81234efcbf68d0974acc87f5dc20224ad1bfe5dd813e2bb161ae29943738`。GUI の初回操作、保護／session 競合、dispose・取消と各ファイルの後始末は未確認。

実 GUI 初回は test が同じ画像の非同期再読込を待たず camera を設定し、後から reset されたため画素比較に失敗。再読込を除去した次の実行では合成画素・元画像維持・明示 Cancel は通過したが、別画像を開くと共有 ImageReadGate が旧 callback を破棄して busy が戻らず timeout。独立した read Future と session revision 確認へ変更し、再試験で `PANORAMA CHECK PASSED (32 groups)`、元画像切替・session 変更の破棄と保護出力も通過した。以後、合成後に視点が reset され編集箇所が見えなくなる点をスクリーンショットで発見し、現在視点を保持する修正と保存失敗テストを追加、再試験待ち。

最終試作 GUI は `PANORAMA CHECK PASSED (33 groups)`、exit 0。SDXL の既存画像に単色 patch を指定視点から適用し、メモリと保存 PNG の画素一致、元画像非変更、視点保持、明示 Cancel、入力切替・保護 session 変更時の破棄、保護 PNG 出力、保存失敗時の元画像保持を確認。合成後の画面と狭い画面を目視した。合成結果は既存の生成画像に合成色を付けたもので、SDXL を今回再生成していない。dispose 中の合成、保存後・画面採用前の session 変更、chooser・実 inpaint 結果・SD1.5 合成、動画・配布 exe・異常終了は未検証。上流の mask blur と異なる線形 feather で、カメラ自動記録・undo・極補正・sketch 等は未実装。
