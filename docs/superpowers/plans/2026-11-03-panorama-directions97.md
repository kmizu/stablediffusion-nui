# Panorama Tools の方向ボタン

対象は `docs/panorama-tools97-wip-ja.md` の六方向のみ。Front=(0,0)、Back=(180,0)、Left=(-90,0)、Right=(90,0)、Up=(0,90)、Down=(0,-90) を既存 `PanoramaModel.setView` に渡し、現在の FOV を維持する。原典 README は六方向ボタンを記すが角度を数値指定していない。極での yaw=0 は NUI の選択。画像・動画で同じ model を用い、未読込時は無効にする。

1. `RealPanoramaCheck` に全六ボタンの存在・有効状態・値・FOV 維持と実画像の投影、共有 model による全画面同期の回帰を加える。実装前に RED を確認する。
2. `PanoramaTab` の独立 row にボタンを追加し、操作のたび共通 view を更新する。静止画・動画で使い、変換中の無効化は既存 controls に合わせる。
3. 既存 SD1.5 / SDXL 静止画で実 MainWindow check とスクリーンショット目視、既存動画への影響を test harness で検証する。共有 GPU / sbt は事前に調整。
4. 日本語ガイドと調査表を更新する。上流の合成・極補正・sketch などは引き続き未実装で #97 は open。

実 GUI RED は未実装ボタンで失敗、実装後 `RealPanoramaCheck` 22 groups と `RealPanoramaVideoCheck` は exit 0。画面を目視した。詳しい条件と残りは `docs/panorama-tools97-wip-ja.md`。
