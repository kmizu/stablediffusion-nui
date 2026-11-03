# パノラマ動画の残作業（#21）

この文書は調査メモであり、動画機能の実装や検証の完了を意味しない。静止画については `panorama-viewer-verification.md` を参照する。

JavaFX Media の不安定な試作と当時の診断ソース・legal 原文は branch `codex/panorama21-javafx-wip`（`12a7712`）とそれ以前の Git 履歴に保存した。現在の作業 branch はユーザーが指定する外部 ffmpeg への置換を進めており、JavaFX Media の依存と診断ファイルは現行ツリーから外している。以下の JavaFX 試験は経緯の記録である。

## 現在の試作と残り（2026-11-03）

作業ブランチには、ユーザーが選択した外部 ffmpeg と同じディレクトリの ffprobe を使う動画 UI を接続済み。ローカル動画の再生、一時停止、シーク、速度変更、終端後の再開、静止画への切替を実装した。ffmpeg は同梱・自動取得しない。設定未指定と実行ファイル欠損はエラーとして表示する。動画終端付近でフレームが得られない場合は最大 2 秒前まで探すため、表示時刻と実際のフレームがずれる可能性がある。

赤／青の合成動画と、既存の SD1.5／SDXL 生成静止画を反復した H.264 動画で、実 MainWindow の再生操作と投影を確認した。画面も目視済み。生成モデルを再実行した検証ではなく、動きのある動画や他の codec の品質を保証しない。詳細・コマンドと試行経緯は `superpowers/plans/2026-11-03-panorama-ffmpeg.md` を参照。

実ファイル選択ダイアログ、通常表示と全画面の動画、画像保護、不正媒体・競合、長時間再生、配布版、ffmpeg ビルドごとのライセンス案内は未検証。親 JVM 異常終了時の子プロセス停止も保証していない。Infinite Zoom は未実装（#64）であり、その結果から直接送る機能もないため #21 は未完了のままにする。

以下は試作時点の履歴であり、「未接続」「未確認」などは当時の状態を表す。最新版の状態はこの節と上記 ffmpeg 計画を参照する。

## 原典と当初の状態（履歴）

上流 `GeorgLegato/sd-webui-panorama-viewer` の GPL-3.0、固定 commit `aa2a238136f68d8943b17378d8c01b6e00179e68` を参照した。タブには動画のアップロードがあり、正距円筒の動画を全周ビューアで再生する。ビューアはミュートされ、動画の再生操作とフルスクリーンを備える。Infinite Zoom の結果動画をビューアに送る導線もある。コードはコピーしない。

本体の `PanoramaTab` は静止画のみで、動画を選択・再生できない。Infinite Zoom は本体で未対応（#64）のため、その生成結果から直接送る接続もない。台帳 index 109 は未完了に戻した。

## 当初の実装の検討点（履歴）

- 動画をローカルから選び、正距円筒の各フレームを既存の投影表示に渡す。再生・一時停止・シーク・終了・繰り返しの状態と、静止画に切り替えたときの解放を明示する。音声は上流と同様にミュートを基本とする。
- JavaFX Media は候補で、main の `build.sbt` には含まれず、この worktree で試験用に追加した。ネイティブライブラリ、ライセンス、Windows 配布物および対応コーデックを検証する。既存 OpenCV worker を使う方式なら、再生中に画像生成 worker を占有しないことと終了時の後片付けを検証する。
- 調査用に取得した `javafx-media-21.0.6-win.jar` には `fxplugins.dll`、`glib-lite.dll`、`gstreamer-lite.dll`、`jfxmedia.dll` が含まれる。OpenJFX 21.0.6+3 の [固定 source](https://github.com/openjdk/jfx21u/tree/5ad442749972f73caf4aa320106d9a25e866addc/modules/javafx.media/src/main/legal) には directshow、glib、gstreamer、libffi の legal notice がある。実際に配布する binary に対応する notice と必要な配布条件を検証・反映するまでは、この追加依存を製品に入れない。
- 上記 4 件の原文を調査資料として `licenses/openjfx__media_{directshow,glib,gstreamer,libffi}.txt` に無変更で保存した。SHA-256 は順に `d847da5757a30d093db3f90a0bac9b1699a52965daa3ec5dedf3ebf14c81c698`、`bde0b1cef025ea56f3a1811f7a8dd1d0bb217850468ca1e75b71ae11b93c8aa2`、`1d9bc7fcaf8b30b97b938d2dee813797743041411a3b3b6a7d543dd76b036739`、`8b7ffa48fb4b8aac3ffde9d014a4888f1b325199c4beb710d13898acf3c370bf`。これだけで Maven binary との対応や LGPL 等の配布条件を満たしたとはみなさない。ビルド元の照合と `THIRD_PARTY_NOTICES.md`／配布 ZIP 内の検証が必要。
- 連続フレームは既存の非同期投影処理を無制限にキューへ積まず、描画が間に合わない場合も最新フレームを表示できるようにする。フルスクリーンと通常表示が同一の動画時刻・カメラを共有することを確認する。
- 動画表示中の静止画変換・保存・img2img 送信の意味を決め、誤って動画パスを静止画として渡さない。画像保護セッション中の保存や入力取扱いも確認する。
- Infinite Zoom 自体は未実装なので、独立したローカル動画の閲覧を先に確認し、#64 実装時に結果から送る導線を追加する。これだけで上流の直接連携を完了したことにはしない。

## 当初挙げた検証（履歴）

異なるフレームに方向の分かる色を入れた短い動画で、再生時刻、向きの変更、一時停止、シーク、終端、反復、静止画への切替と dispose をテストする。動画が壊れている・未対応コーデック・巨大寸法・ファイルが途中で消える場合の失敗も確認する。実 GUI、通常表示とフルスクリーン、動画を開いた状態での終了、スクリーンショットの目視、Windows 配布版の起動を確認する。既存の SD1.5／SDXL 静止画の実機結果は投影の証拠であり、動画再生の証拠ではない。

## 2026-11-03 の JavaFX Media 試験（履歴）

JavaFX Media 21.0.6 が未追加の状態では `ui/Test/compile` は `javafx.scene.media` 不在で失敗した。`build.sbt` に依存を追加し、ローカルの ffmpeg で赤から青に切り替わる 320×160、2 秒の H.264 動画を生成。Windows Java 21 の `ui/Test/runMain sdfx.ui.RealPanoramaVideoProbe outputs/panorama-video-probe/red-blue.mp4` は `PANORAMA_VIDEO_DECODE_OK red blue` と exit 0 を返し、MediaView の snapshot から両色のフレームを得た。これは方式の小規模な試験で、pause／seek の結果、GUI への接続、フルスクリーン、配布版 jar、codec の網羅や動画の実時間表示を検証していない。

一時停止後の seek と再描画を確認するよう probe を変更した後の 2 回は 30 秒で timeout。2 回目には seek に入ったことを示す診断行も出なかったため、seek 単体の故障とはまだ断定できない。初回の赤／青取得も再現できていない。開始状態・時刻・画素を毎秒記録する変更を準備し、原因を調査中。`LatestFrameSuite` は error callback が例外を投げた後の停止を再現して修正し 3 件成功したが、まだ動画表示には接続されていない。

開始時から状態を記録する probe の 3 回目は、動画の準備前に JavaFX Media が `ERROR_MEDIA_INVALID` を返した。同一動画は ffprobe で H.264 High / yuv420p・320×160・20 フレームと認識され、ffmpeg による全フレームの読み取りは exit 0。JavaFX 側の原因は未確定であり、JavaFX と他の画像処理が共有する資源の影響も除外できていない。次は共有 GPU lock を確保して単独再試験する。

共有 GPU lock を確保し他の実モデル測定を止めた 4 回目は `PANORAMA_VIDEO_DECODE_OK red blue pause seek`、exit 0。停止したプレイヤーを 0.4 秒へシークし、赤いフレームを snapshot で得た。1 回の成功と前 3 回の結果だけでは、並行処理が失敗の原因だとは判定できない。GUI 本体および配布版は引き続き未検証。

`PanoramaTab` に動画の選択、停止・再開、位置指定と投影用フレームの試験実装を追加し、`ui/Test/compile` は exit 0。実 MainWindow で動画、pause／seek、終了とスクリーンショットを確認する `RealPanoramaVideoCheck` を用意した。機能全体、既存静止画の退行、競合、安全性、媒体の配布条件は検証前であり、リリースに取り込まない。

実 MainWindow に 2 秒の動画を開く試験は赤／青・pause／seek・stop を通過し `REAL_PANORAMA_VIDEO_OK`、exit 0。ただし取得画面を目視すると fullscreen などが無効のままで、追加試験でも同箇所の timeout を再現した。初回動画フレーム時に controls を更新する修正後は fullscreen enabled の確認を通過したが、その後の pause 待ちが timeout した。画面の位置スライダーは終端にあり、短い動画がスクリーンショットを取得する間に終わった可能性がある。テスト順を pause 先行へ変更し、8 秒動画で再試験を準備中。動画終端でもボタンが Pause 表示のままという問題は残っている。

8 秒の赤／青動画で pause、各色へのシーク、初回フレーム後の fullscreen enabled、stop を実 MainWindow で確認し `REAL_PANORAMA_VIDEO_OK`、exit 0。スクリーンショットも目視した。終端テストについては pause 直後の過渡的な Play 表示を成功と誤認し得るため、この成功には含めない。終端付近で実際に再生が進んだ後の表示を確認するよう試験を強化したが未実行。

強化した試験の初回は 8 秒動画が pause 前に終端へ達し、`seconds=8.0 label=Pause` と記録された。終端時に Play 表示と先頭からの再開を実装し、先頭 1 秒が赤、その後 59 秒が青の H.264 動画で再試験した。実 MainWindow で fullscreen enabled、pause、青と赤への seek、59.9 秒以降の Play 表示、先頭からの再開、stop を確認し `REAL_PANORAMA_VIDEO_OK`、exit 0。スクリーンショットは目視した。これは一種類の合成動画についての検証で、全 codec・静止画との切替・保護・エラーと dispose の競合・配布版は未検証。

上記 fixture はローカル ffmpeg の `-f lavfi -i color=c=red:s=320x160:r=10:d=1 -f lavfi -i color=c=blue:s=320x160:r=10:d=59 -filter_complex "[0:v][1:v]concat=n=2:v=1:a=0,format=yuv420p[v]" -map "[v]" -c:v libx264` で生成した。fixture は生成物で Git には含まれない。

既存静止画の回帰として、前回生成済みの SD1.5 1024×512 と SDXL 1536×768 画像を使う `RealPanoramaCheck` を、共有 GPU lock 下で再実行した。`PANORAMA CHECK PASSED (21 groups)`、exit 0。回転、投影変換、全画面、別タブへの送信、保護 PNG の拒否・復号・保護保存を含む。`outputs/panorama-still-regression/pano-01-sd15.png` を目視した。生成自体の再実行ではなく、静止画から動画への切替試験でもない。

静止画を開いてから動画へ切り替える実 GUI 試験では、その切替と再生／pause／seek は通過したが、動画終端後に 5 秒を選んで再生すると選択位置を無視することを `play from chosen position after end` の timeout で再現した。終端後の選択位置を保持する修正は入れたが、まだ GREEN は未確認。この試験は失敗箇所で停止したため、動画から静止画への切替も未確認。

上記修正後に同じ `RealPanoramaVideoCheck` を再実行し `REAL_PANORAMA_VIDEO_OK`、exit 0。静止画→動画→静止画の切替、終端後の 5 秒指定からの再生、終端からの先頭再生が通過した。未対応 codec や画像保護セッション中の動画操作、読み込み・終了の競合、配布版は試していない。

過去の SD1.5／SDXL 実画像をそれぞれ 512×256、60 秒の H.264 動画にした fixture を追加し、yaw 90／pitch 10 の表示と参照投影を比較する試験を用意。最初の SD1.5 動画のフレーム待ちが timeout し、投影比較や SDXL の確認には至っていない。該当動画は ffprobe で H.264 Constrained Baseline、512×256、600 フレームと認識される。状態とフレーム寸法の診断を追加したが、再実行は待機中。

再実行では MainWindow の状態が `Cannot play video: ERROR_MEDIA_INVALID` と判明した。一方、同一 Baseline 動画と別途 High profile で作り直した同画像動画を、別プロセスの JavaFX MediaPlayer で各 1 回だけ開く `RealMediaOpenProbe` では、どちらも `MEDIA_OPEN_OK width=512 height=256`、exit 0。profile 単独が原因とは結論できず、実再生・連続切替を含む条件と単独の open の差を調査する。参照投影の比較は引き続き未達。

`RealMediaSwitchProbe` で最初の動画を再生してから次を開く単独 JavaFX 試験も `ERROR_MEDIA_INVALID` で失敗した。ただし失敗が 1 本目か 2 本目かを識別するログがなく、この結果だけでは切替が原因とは言えない。段階ごとのログを追加し再実行待ち。

段階ログを付けて共有 GPU lock 下で再試行したところ、`MEDIA_SWITCH_FIRST_READY` → `MEDIA_SWITCH_OPEN_SECOND` → `MEDIA_SWITCH_OK`、exit 0 だった。先の失敗は再現せず、連続切替の成功を 1 回確認したにとどまる。MainWindow での実画像由来動画の投影比較はまだ成功していない。

切替先の再生とフレーム読み取りまで条件を延ばした即時切替試験では、`MEDIA_SWITCH_FIRST_READY` → `MEDIA_SWITCH_OPEN_SECOND` → `MEDIA_SWITCH_SECOND_ERROR`、`ERROR_MEDIA_INVALID` を確認。先行 player を dispose して 1 秒待つ診断では `MEDIA_SWITCH_SECOND_READY` → `MEDIA_SWITCH_FRAME_OK`、exit 0 だった。ただし異なる条件を各 1 回しか試しておらず、待機が失敗を防いだとは断定できない。時間待ちだけを製品の修正とせず、反復試験と実 MainWindow での追試が必要。

動画の素材が静止画から繰り返した合成 fixture であり、動きのある実動画に対するフレーム落ち、音声の無効化、長時間再生の CPU・メモリ、通常／全画面同時表示の負荷は未計測。フレーム寸法は MediaPlayer が ready になった後で検査するため、デコーダの初期化以前に大きな入力による資源消費を防げるとは主張できない。

同一 fixture で即時切替と dispose 後 1 秒待機を再度比較したところ、即時条件は最初の player から `ERROR_MEDIA_INVALID`、1 秒待機条件は最初の準備後、2 本目の player が同じエラーを返した。前に 1 秒待機で成功した結果もあるため、固定待機で信頼性を保証できない。

## ffmpeg 代替の初期実験（履歴）

JavaFX Media の失敗試作はこの worktree に保持したまま、別の方式を `scripts/dev/probe_panorama_ffmpeg.py` で実験した。ユーザーが用意した ffmpeg をフルパスで指定し、シェルを使わず 320×160 の RGB フレームを pipe で受ける。赤／青 fixture の先頭の赤と 1.4 秒位置の青を取得し、読み取りを一時停止した後の子プロセスを終了・待機、シークでは次のプロセスを作り、それも終了した。`PANORAMA_FFMPEG_SPIKE_OK red=(253, 0, 0) blue=(0, 0, 254) child_exit=1,1`、スパイク exit 0、終了後に ffmpeg プロセスが残っていないことを確認した。子の exit 1 は途中で明示的に終了させた結果で、自然完了ではない。存在しない実行ファイルの指定は `ffmpeg executable not found` で exit 1。

この実験は product UI に接続していない。連続フレームの時計と pause／resume、読み取り中のキャンセル・seek、再生終端、巨大・破損入力、stderr とメモリの上限、同時切替、子プロセスが応答しない場合、親プロセス異常終了時の子の後片付け、実動画や配布版は未検証。再生時は一度に一部フレームだけを受け、queue を無制限に積まない設計が必要。ffmpeg はユーザーが用意する任意の外部実行ファイルであり、本体に同梱・自動取得しない。設定画面で実行ファイルを選び、未設定・欠損・起動失敗を明示する設計と、ユーザーが選ぶ ffmpeg のビルドに応じたライセンス通知の扱いを確認してから製品化する。
