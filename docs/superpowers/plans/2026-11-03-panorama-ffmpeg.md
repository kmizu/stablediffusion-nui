# #21 ffmpeg 動画プレイヤーの設計・検証計画（未実装）

## 目的と前提

JavaFX Media 試作と失敗履歴は同じ worktree の履歴と `docs/panorama-video-wip-ja.md` に保持する。製品の動画経路は、ユーザーが選んだローカル ffmpeg / ffprobe を使う候補に切り替える。ffmpeg は同梱・自動取得しない。Windows の通常の利用、missing executable と unsupported video の明示を最低条件とする。

## 構成案

1. `Settings` に ffmpeg の実行ファイル指定を追加し、Panorama タブに選択・変更・未設定時の説明を置く。プローブには選択された配布物の隣にある ffprobe を使い、見つからない場合は隠さず説明する。パス変更を保存し、起動し直しても保持する。PATH に見つかったものを無断で決定しない。
2. `PanoramaVideoDecoder` は動画情報と、指定時刻の単一フレームを取得する。`ProcessBuilder` に引数配列を渡し shell を介さない。ネットワーク URL を拒否、ローカルファイルを指定、子プロセスの標準入力を閉じ、表示上限まで縮小した RGB を固定長で読み、標準エラーを制限する。出力上限・期限を超えたらプロセスを終了し、エラーを返す。コード実行可能なシェル文字列を組み立てない。
3. プレイヤーは単調時計・開始時刻・開始位置・速度・動画長を持つ。再生、停止、seek、速度変更のたびに位置を確定し、新しい epoch を発行する。フレーム取得は実行中 1 件＋最新の待機 1 件に限り、古い epoch の結果は表示しない。pause は時刻を固定し、resume と seek はその位置から再開。終端は Play とし、終端からの seek は選択位置、通常の Play は先頭から再生する。
4. 外部コマンドは実行中のハンドルを所有者が記録し、明示的停止・別動画への切替・画面破棄・期限切れで終了と wait を行う。読み取りが詰まった場合も watchdog で停止できることを確認する。Windows 親プロセスの異常終了時に子が確実に停止する方式はまだ決めていない。Job Object 等の適用を検討し、その保証がなければ残存し得ることを明記する。
5. 上のデコーダを既存動画 UI と bounded frame renderer に接続する。保存時は現行の保護セッションを適用し、動画パスを静止画送信や変換へ渡さない。静止画⇔動画と全画面の切替、読み込み中の再選択・終了を確認する。

## 試験順と受け入れ

- 先に子プロセスの fake executable を用意し、選択パスの保存と再読込、欠損、非動画、出力過大、早期 EOF、非ゼロ終了、読み取り中の停止、期限切れ、古い結果の破棄と同時操作をテストする。実装前に失敗を確認する。
- 単調時計を差し替え、pause 中の位置固定、seek 後の開始、0.5×／1×／2×の経過位置、終端、切替時の epoch を CPU 単体テストで確認する。
- 実 ffmpeg と赤／青動画を使ってフレームと位置、実 SD1.5／SDXL 画像から作った動画の方向付きフレーム、実 MainWindow の操作・スクリーンショットを確認する。短時間の再生では品質・高負荷・長時間安定性を保証しない。
- `RealPanoramaCheck` の静止画・画像保護の回帰、動画利用中の protected session、通常／全画面の負荷、異常終了時の子プロセス、配布 ZIP（ffmpeg は非同梱）を確認する。使用者が導入する ffmpeg のビルド条件とライセンスを説明し、自動的に許諾済みとは扱わない。
- Infinite Zoom は本体で未実装のため直接連携は別途残る。上流との対応表を更新し、未検証を残したまま #21 の完了や台帳の done は主張しない。

## 2026-11-03 ローカル CPU スパイク

ユーザー環境の ffmpeg 8.0.1 `full_build-www.gyan.dev` に 512×256 の SD1.5 動画から 10 回、指定時刻の単一 RGB フレームを新規プロセスで取得させたところ、最小 0.063 秒・平均 0.072 秒・最大 0.082 秒。別の SDXL 画像動画を 1024×512 にした 10 回では最小 0.070 秒・平均 0.078 秒・最大 0.085 秒。これはこの PC・2 種の静止画像反復動画・ウォームキャッシュでの有限回測定であり、一般的な再生速度や実動画の負荷は示さない。ffmpeg を同梱する根拠にもならない。

`PanoramaVideoClockSuite` は未実装で RED の後、pause 中の位置固定、seek、2×速度、終端時の先頭からの再生、終端後の位置選択、不正値について 3 件 GREEN。これは時計単体であり、0.5×／再生中の速度切替／decoder epoch と UI はまだ確認していない。

`PanoramaFrameDecoderSuite` は未実装で RED。初回の GREEN 試行では偽子 JVM の classpath が不足しており、単に子が起動失敗していたため結果を採用せず修正した。その後、子が本当に待機に入った marker を確認するテストを追加して 4 件 GREEN。固定長／過大出力・異常終了、実際に詰まった子の deadline kill と close kill、missing executable と不正引数を確認した。`RealFfmpegFrameCheck` はユーザー環境の ffmpeg 8.0.1 を JVM の decoder から呼び、赤と青のフレームを読み `REAL_FFMPEG_FRAME_OK`、exit 0。終了後の ffmpeg process がないことも確認した。decoder はまだ UI へ接続されておらず、再生制御、親 process 異常終了時の子の処理、強制終了不能なプロセス、実動画とパッケージは未検証。

ffprobe の JSON parser と bounded 子プロセス呼出しを追加。未実装 RED の後、metadata parser／decoder 計 7 件 GREEN。ユーザー環境の実 ffprobe と ffmpeg を JVM から呼び、`PanoramaVideoInfo(320,160,60.0)` および赤・青フレームを `REAL_FFMPEG_FRAME_OK`、exit 0 で確認した。これは合成動画 1 件の検証で、上記の UI、強制終了不能プロセス、実動画、配布版の制限は残る。

seek に向けて decoder の cancel を追加し、実行中の待機子を止めた後の次フレーム取得を確認した。さらに、cancel より前の待機要求が cancel 後に遅れて開始する race を世代番号で拒否し、decoder／probe 計 9 件 GREEN。これは decoder 単体であり、再生制御と UI の接続による旧フレーム非表示はまだ未検証。

`PanoramaVideoPlaybackSuite` の RED 後、再生 controller の古い in-flight frame の破棄、pause・seek・0.5×、不正 seek／速度による再生状態の非変更を 3 件 GREEN。これは fake decoder と単調時計による単体試験で、JavaFX UI に送った後の未処理 callback の破棄や実 ffmpeg を含む連続再生はまだ未検証。

試作としてユーザー選択の ffmpeg 設定を追加し、旧設定の読込みと新しい値の保存を 1 件 GREEN。Panorama タブに実行ファイル選択、隣の ffprobe 確認、再生・停止・seek・速度を接続し、metadata／フレーム callback に世代を渡して古い結果を捨てる。JavaFX Media の WIP は別 branch に保持し、現行 branch の依存と試験コードは外した。`ui/Test/compile` は exit 0。ffmpeg 経由の実 MainWindow テスト、設定 UI、秒数・速度・切替・失敗・プロセス寿命、SD1.5／SDXL 動画の投影、配布 ZIP と外部依存の通知は未検証で、この試作を製品へ統合しない。

ffmpeg 版の最初の `RealPanoramaVideoCheck` は、未設定時の案内、静止画から動画への切替、pause／速度指定、青と赤への seek、終端後の指定位置再生まで進んだが、2 回目の終端表示待ちで timeout。原因診断中。独立した ffmpeg CLI 測定では、60 秒・10 fps の fixture に `-ss 59.9` はフレームを返すが、`-ss 59.95` 以降は exit 0・出力 0 bytes だった。これが UI timeout の原因かは、状態ログを追加した次の実 GUI 試験で確認する。

状態ログ付き実 GUI 再試験では、終端直前に `Cannot decode video: Video frame decoder returned an invalid frame size` で player が停止していた。正常終了・0 frame を区別し、終端近傍では少し前の位置を段階的に試す案の単体テストを準備したが、RED はまだ実行しておらず、対策も未実装。この補完には可変フレームレートで厳密な最終フレームを保証しない限界があるため、取得した位置と表示上の時刻の差を含め検証する必要がある。

正常終了・0 frame を `PanoramaNoVideoFrame` と区別し、終端の 2 秒以内に限り 0.1／0.25／0.5／1／2 秒前を順に試す実装を追加。未実装 RED の後、decoder／playback 計 11 件 GREEN。終端以外の 0 frame は再試行せずエラーとする。これは取得時刻が最大 2 秒前になり得る方式で、VFR における厳密な最終フレームや実 MainWindow の GREEN は未確認。

終端の 0 frame を通常のデコード失敗と区別し、終端 2 秒以内だけ最大 2 秒前まで段階的に再取得する試作を追加した。`PanoramaFrameDecoderSuite` と `PanoramaVideoPlaybackSuite` の計 11 件は GREEN。画面での再試験は共有 GPU の順番待ちで未完了。可変フレームレートや疎なフレームでは表示時刻より古い画像になる可能性があり、正確な最終フレームの保証ではない。

修正後の実 MainWindow 再試験は赤／青動画の終端 2 回、終端からの再生、静止画への切替まで通過したが、実 SD1.5 画像を反復した動画を開くと `Video frame decoder failed (exit -22)` で停止し、frame 0 待ちが timeout した。停止時に frame thread の `InterruptedException` stack trace も出た。終了時の ffmpeg／ffprobe の残存は観測されなかった。同一 fixture に対する単独 ffmpeg CLI の 0 秒フレーム取得は exit 0・393216 bytes。ffmpeg stderr を破棄しているため exit -22 の原因は未特定。取消と非取消の区別、stderr の安全かつ bounded な診断、停止時の割り込み処理を調べ、実 GUI を再試験する。SD1.5／SDXL 動画の投影・スクリーンショットも未確認。

stderr を 8192 bytes まで保持しつつ残りを並行 drain する変更で、200 KB の stderr を先に出す子の timeout を再現し、修正後に関連 12 件 GREEN。停止による cancel を専用例外で区別し、実 GUI 再試験で `Invalid duration for option ss: 1.6E-6` を取得した。開始直後の微小な秒数が指数表記で渡ることが原因。小数表記の RED test を追加済み、まだ実行前で修正・再試験は未完了。

指数表記再現 test は `1.6E-6` と期待する `0.0000016` の差で RED。`BigDecimal.valueOf(seconds).toPlainString` に変更後、decoder suite 8/8 GREEN、playback suite も成功（sbt cache）。実 GUI は再試験待ち。

時刻修正後の実 MainWindow `RealPanoramaVideoCheck` は `REAL_PANORAMA_VIDEO_OK`・exit 0。60 秒赤／青動画で未設定通知・静止画切替・pause・速度・seek・終端と再開を確認し、実 SD1.5／SDXL の生成静止画を反復した 512×256 H.264 動画で両方の投影画素を比較した。`outputs/panorama-video-ui/real-video-0.png` と `real-video-1.png` を目視し窓・室内および本棚の投影を確認。GUI は dry-run worker、生成モデルの再実行ではなく反復静止画動画で、一般動画のコーデック・性能を示さない。終了後 ffmpeg／ffprobe の残存は観測されなかった。全画面動画・保護・不正媒体・設定 chooser・配布版・長時間再生・Infinite Zoom 接続などは未検証または未実装。
