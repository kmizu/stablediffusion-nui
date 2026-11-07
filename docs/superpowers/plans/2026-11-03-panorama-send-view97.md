# Panorama の視点画像を他タブへ送る案（#97、未実装）

## 元資料と現在

固定 revision `49118af2783bf25804155b88faf586d102c1a21c` の Panorama Tools README は、3D preview の現在の視点を img2img / inpaint / Extras へ送ると説明する。現在の Panorama の同名ボタンは元画像の path を送る。`Save view` は現在のモデルを非同期に投影・保存するが、戻り値は保存完了前に返るので、そのまま送ってはならない。

## 設計候補

既存の元画像送信を維持し、視点画像の送信先を明示する別操作を用意する。押下時に raster・投影・yaw/pitch/FOV・画面縦横比・保護セッションを固定し、背景で投影と一意な PNG 保存を終えてから、FX thread で選択タブへ渡す。動画では押下時のフレームを固定する。新規結果は現行の `PanoramaFiles.savePng` に従い画像保護を継承し、ユーザーに出力先を表示する。失敗時や disposed 後は送信しない。元ファイルの infotext は送信の適否を既存機能に合わせ、視点と画像が後続の球面合成で必要になるため別途記録する方式を検討する。

## 検証計画

先に MainWindow test を作り、非正面の view を指定して送信し、img2img / inpaint / Extras が元の panorama ではなく参照投影に一致する PNG を受け取ること、保存失敗と画面破棄で誤送信しないことを RED で確認する。保護セッションの有無、動画フレーム、急な連続操作、スクリーンショット、実 SD1.5 / SDXL 画像と配布 exe を別々に確認する。球面への再合成や既存上流の保存カメラ UI は未実装で、送信追加だけでは #97 を完了しない。

2026-11-03: 上記の送信と固定状態、保護・失敗・dispose 時処理を実装。実 GUI は静止画 25 groups、動画 `REAL_PANORAMA_VIDEO_OK`。制約は `docs/panorama-tools97-wip-ja.md` に記録。球面合成などは未実装。

2026-11-03 追加: 寸法上限と session revision の二段階確認を追加し、unit と GUI 27 groups、動画 GUI pass。詳細と同期中の待機・未検証範囲は調査文書を参照。
