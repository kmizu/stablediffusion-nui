> 最新の作業状況は直下の追記。過去の詳細は `HANDOFF-CODEX.md` と、この追記より下の記録を参照。

## 2026-11-03 作業中の追記

- ユーザーの方針: issue を速く解決し、10件ごとにリリース。MCP から AI が使えることを重視。日本語マニュアルで十分、必要な画面はキャプチャする。次のリリース番号は未決定なので推測しない。
- 2026-11-03 追加指示: **次のリリースを公開し、配布物の確認が終わったら作業を停止する。** 次のバッチを始めない。
- main `1d97f13b72a17f1ea93fdc90130e14b46f80867e` は push 済み（作業時に remote と一致を確認）。この追記以降のコミットは `git log` と remote で確認する。`.claude/` は未追跡の既存 worktrees なので消さない。
- ネガティブ候補 `c484556`: 画質・人体・文字のセットと個別調整、日本語説明。実 GUI と SD1.5/SDXL、展開版 MCP を確認。詳細は `docs/negative-suggestions-verification.md`。抽出版 GUI 自体の実生成は未確認。公開済み v0.1 より後の実装で、次のリリースに含める。
- #2 追加検証: 研究側固定 revision の embedding 8件を安全読込し、うち aivazovsky.pt を実 MCP の SD1.5／SDXL に適用、PNG hash と再生成の RGB 一致を確認。研究側 fixture であり A1111 元拡張の出力互換の証拠ではない。配布条件未確認のため fixture を同梱しない。詳細は `docs/aesthetic-gradients-verification.md`。#2 は open。
- #2 Aesthetic Gradients `c4abab3`: GUI/MCP の embedding 作成、PNG 復元の SHA 照合、日本語マニュアル。実 GUI と展開版 MCP で SD1.5/SDXL を確認。Scala 全体と Python CPU 全体も通過。`674cb79` で SDXL の強い重みについて日英の案内を追加、明示値は変えない。配布 EXE GUI でも Windows chooser から実 embedding 作成・適用・両モデルで生成する一連を確認。元拡張との実物互換性などが未確認のため issue は open、台帳は todo のまま。詳細は `docs/aesthetic-gradients-verification.md` と `docs/aesthetic-embeddings.md`。
- #26 CreaPrompt: main `2284f49` に統合・push し、GitHub issue closed を確認。実 GUI と配布版 MCP で SD1.5・SDXL を検証し画像を目視。main の `sbt test ui/assembly` は exit 0、テストの一部はキャッシュでスキップされた。生成した JAR は配布版実測時の JAR と SHA-256 が一致。抽出版 GUI 自体の実生成などの限界は `docs/creaprompt-verification.md` に記載。新リリースへの issue 件数は 1/10。
- #21 パノラマ動画を main に部分統合。ユーザーが用意する ffmpeg／ffprobe で正距円筒動画の再生・pause・seek・終端・速度変更を行う。分離 worktree の実 GUI で SD1.5／SDXL 由来動画の投影と合成動画の操作を確認、main では関連35件・assembly、JAR 内の関連クラス照合を確認。展開版 exe の dry-run でも chooser・再生・seek・全画面表示を確認。全画面からの Esc 脱出、通常の動く動画、長時間、保護、Infinite Zoom 連携などは未確認／未実装。`docs/panorama-video-wip-ja.md` 参照、#21 は open。
- #22 `prompt_library` MCP を部分統合。Prompt Tools の保存済み名前一覧・名前指定本文取得を読み取り専用で提供し、有界読込と末尾 symlink 拒否を実装。公式 MCP SDK の stdio／HTTP dry-run と分離 worktree の試験を確認。親 junction、同時変更と配布版は未検証。`docs/prompt-library-mcp-ja.md` 参照。#22 は open。
- #22 Prompt All in One（台帳 index 149）は既存検証文書で翻訳・複数選択など未対応と確認できたため、done から todo に訂正。294・307 の代替実装の受け入れ範囲は要再検討。#22 は open。台帳は 175 done / 127 todo / 51 na（issue 件数とは別）。
- #24 タグ翻訳プレビューを main に部分統合。翻訳 CSV を使い positive／negative で範囲を選択可能、既定オフ。source-class 実 GUI・SD1.5／SDXL で確認、SDXL は表示オン／オフで同条件の画像が完全一致したが両方に網目があり原因未確定。長い訳と 30万行辞書、旧設定互換も確認。構文の完全解析・配布版・物理 IME は未検証。`docs/tag24-live-translation-wip.md` 参照。#24 は open。
- #24 タグ補完: main `9876bda` に wildcard の部分 Tab 補完と行選択、`ffcf80e` にカテゴリ番号と色マーカー、`56a9411` にデータ元別のカスタム配色、`87aea1f` に Tab 補完の範囲設定、`1c84470` に補完待ち時間を部分統合。保存失敗・不正なパスの復旧、実 GUI と SD1.5・SDXL を確認したが、上流の未実装機能や配布 GUI の検証が残り issue は open。`docs/tag-autocomplete-acceptance-24.md` と各検証文書を参照。
- #143 VRAM/最適化: main `341dc29` に部分統合、`ffdb2e6` で fp8-auto 拒否後の GUI 表示・生成、`d2aa609` で単一 MainWindow の checkpoint／Style／VAE 操作順序と生成時の待機状態を修正。実 SD1.5・SDXL と展開版 MCP の検証、Python 全体、Scala の回帰を実施。fp32 fallback の NaN と VRAM 制限は試験で再現したもの。物理低 VRAM 端末、物理マウス操作、SDXL ControlNet、MCP／restart／plugin と UI 操作の全体順序は未検証・保証外のため issue は open。`docs/optimization-handoff.md` と `docs/optimization-verification.md` を参照。
- #18 ControlNet 前処理: main `0f9ff4a` に選択ファイル一括処理を部分統合。保護セッション切替時の公開を staging とロックで守り、実 engine の複数選択・失敗・cancel と保護 PNG 入出力も確認した。detector の許諾と実機、OS chooser 手操作などが残るため issue は open。`docs/controlnet-selected-batch-verification.md` を参照。
- 追加調査: #2 の元拡張の固定 revision には正規 `.pt` fixture が見つからず、参照先の研究用 `.pt` が元拡張の出力と同一かは不明。詳細は `docs/aesthetic-gradients-verification.md`。#18 の配布版 chooser は隔離環境で起動したが、ウィンドウの入力座標を取得できず操作・検証できなかった。
- ControlNet `inpaint` 前処理のマスク未反映は `_controlnets` を使う単体再現で確認した。通常の inpaint 自体のマスクとは別の問題。詳細と再現範囲は `docs/controlnet-fastload-verification.md`。修正・実モデル検証は未了。
- ControlNet inpaint の WIP は `C:/Users/Mizushima/.codex/worktrees/controlnet-inpaint-mask/stablediffusion-nui`、branch `codex/controlnet-inpaint-mask`、`eba998b`（先行 commit 含む）。CPU 回帰と実 SD1.5 Engine の通常・only-masked crop・反転を確認し画像目視済み。GUI/MCP、複数 unit、SDXL、配布版、hook の組合せと暫定拒否の解消は未了。詳細は同 worktree の `docs/controlnet-inpaint-prototype.md`。main へ未統合。
- #23 State Manager V2 の img2img 履歴 import は Denoising strength を部分対応。main core 6件と source-class MainWindow の合成 fixture による復元、入力画像を別途指定した SD1.5／SDXL 実 img2img 生成を確認。実 A1111 と配布版は未検証。詳細は `docs/state-manager-v2-import-wip.md`。issue は open のまま。
- #23 の単一設定復元は main に部分統合。独立レビューで見つかった選択外の入力途中値を確定する問題も修正し、実 GUI と SD1.5/SDXL を確認して画像を目視。main 上でも関連10件・実 GUI・assembly を確認。実モデルは分離 worktree の source-class GUI による検証、配布版 GUI は未検証。`docs/state-manager-verification.md` 参照。#23 は open、リリース件数には数えない。
- #24 の Wiki リンクは main に部分統合。選択候補から `?` または F1 で開き、既定オフ。固定 HTTPS host・URL・互換性の単体18件、実 GUI の Robot 押下と候補保持、実 SD1.5/SDXL MainWindow を確認。main でも単体18件・GUI 2件・assembly を再実行。実ブラウザとスクロール後セル、配布 GUI は未検証。`docs/tag24-wiki-wip.md` に証拠と制約。#24 は open、リリース件数には数えない。
- #12 OpenCLIP は main に部分統合。ConvNeXt と BLIP-base で SD1.5/SDXL 由来画像を実 GUI／MCP SDK で解析し、RN50 の実重みも確認。main 上で Python 60件、Scala 候補表示2件、MCP 39件、assembly が成功。安全な重み読込は固定版の内部 API に依存し、配布版・他の多数モデル・offline と重み読込の全体監査は未了。新 backend の disk cache は暫定無効。`docs/clip-interrogator-openclip-wip.md` に限界を記録。#12 は open、リリース件数には数えない。
- #6 フォルダ入力×別顔 checkpoint は main JAR の実 MCP で SD1.5／SDXL とも2入力、PNG hash、2枚目変更時の拒否と回復を確認。先行出力は disk に残るが failed MCP は images=[]、再試行は全件再生成する。最終 ZIP 実モデル、SDXL folder 実 GUI 等は未確認。`docs/batch-face-audit-ja.md` を参照。
- #6 顔用 checkpoint は main に部分統合。別 engine の分離と PNG hash 復元、失敗・キャンセル後の復帰を実 SD1.5／SDXL で確認。配布候補 exe の実操作・生成と、最終案内の dry-run 画面も確認。SDXL モデルによって 512 で破綻し、この試験では顔解像度 1024 で改善。Python 全体 3067件成功・3件 skip。Scala 全体は img2img フォルダのテスト用証跡漏れ 1 件で失敗し、修正後の関連18件は成功、全体の再実行は未了。JAR 変更クラス 31件はコンパイル出力と一致。詳細は `docs/batch-face-audit-ja.md`。#6 は受け入れ判定待ちで open、件数に含めない。
- #6 MediaPipe 顔検出は main に部分統合。モデル公開競合・差替え対策を含む最新版について、分離 worktree の SD1.5/SDXL 実 MCP、SD1.5 実 GUI と replay／cancel 復旧を確認。main でも Python 関連41件成功。SDXL GUI、配布版や #6 の別 checkpoint は未了。`docs/batch-face-mediapipe-wip.md` 参照。#6 は open、リリース件数には数えない。
- #6 Img2Img フォルダ入力は main に部分統合。GUI と MCP の一括処理、元画像 metadata、Windows handle による許可範囲検証、SD1.5/SDXL 実 MCP を分離 worktree で確認。main では engine 44件・MCP 43件・assembly を確認。MCP は非 Windows／network drive を拒否し、多数の画像では検査が遅くなる。配布版・全組合せ・別 checkpoint 等は未了。`docs/img2img-folder-ja.md` と `docs/superpowers/plans/2026-11-03-batch-face-folder.md` を参照。#6 は open。
- #23 お気に入りは main に部分統合。複数登録・解除、履歴消去時の保護、保存とサムネイルの競合対策、共通 PresetStore の保存成功後の誤報修正を実装。SD1.5/SDXL 実 GUI と、main の保存・履歴テスト計36件、実 GUI、assembly を確認。非協調プロセスや配布版 GUI などの限界は `docs/state-manager-verification.md`。上流 storage import 等が残り #23 は open、リリース件数には数えない。
- #23 State Manager V2 gzip の部分 import も main に統合。合成 fixture の preview・未知項目保持・履歴とお気に入りへの保存、SD1.5/SDXL 実 GUI 生成、main の parser 3件・履歴13件・実 GUI・assembly を確認。上流実物ファイル、直接 IndexedDB、配布 GUI と全設定互換は未確認。`docs/state-manager-v2-import-wip.md` を参照。#23 は引き続き open。
- #23 は取り込んだ上流原本の限定 gzip export も main に統合。既存ファイルを上書きせず、NUI 側編集は反映しない。分離 worktree で上流 JS 再読込と SD1.5/SDXL 実 GUI、main で core 5件・実 GUI・assembly を確認。hardlink 非対応 filesystem、実 A1111 と配布版は未検証。詳細は同文書。#23 は open。
- #23 上流保存コードを固定 revision の JS のまま実行し、A1111/UI を模擬して作成した fixture も main に追加。decoder 4件、実 GUI import、公式 SDK stdio/HTTP の読取りを確認。実際に A1111 を動かして保存したデータではない。詳細は同文書。
- #23 履歴・お気に入りの MCP read-only 一覧と ID 指定詳細も main に部分統合。公式 SDK で stdio／HTTP の読み取りと原本非返却・保存 bytes 不変を検証し、main の `McpToolsSuite` 40件と assembly を再実行。詳細は同文書。配布版 transport と生成連携はこの試験では未検証。
- #23 MCP 履歴一覧の query／offset／limit を main に追加。公式 SDK で stdio／HTTP の検索・2ページ・原本非返却・保存 bytes 不変、main で MCP 44件・履歴20件・assembly を確認。JAR は SDK 試験時と hash 一致。名前・一致や件数から私的情報が推測され得る。配布版は未確認。詳細は同文書。
- #23 MCP 履歴から生成引数の部分 draft を作る `prepare` を main に追加。未対応・不正設定と必要な画像を明示し、生成は呼び出し側が別途指示する。公式 SDK の stdio／HTTP および SD1.5／SDXL txt2img、main の MCP 46件・assembly を確認、JAR hash 一致。再現保証・img2img/inpaint 実生成・配布版は未確認。詳細は同文書。
- #23 履歴・お気に入り検索と mode 切替中の旧一覧操作防止を main に部分統合。分離 worktree の SD1.5/SDXL 実 GUI と main の履歴14件・実 GUI・assembly を確認。callback の到着順を人工的に逆転する試験と配布 EXE は未検証。`docs/state-manager-verification.md` を参照。#23 は open。
- #23 履歴・お気に入りの名前変更を main に部分統合。同一 ID の両方を更新し、重複・古い選択を拒否。分離 worktree の SD1.5/SDXL 実 GUI と main の履歴16件・実 GUI・assembly を確認。別 JVM の同時改名と配布 EXE は未検証。詳細は同文書。#23 は open。
- #23 import thumbnail は main に部分統合。大きさを制限した埋め込み PNG を検査して保存、失敗時は履歴を保持して件数を表示。分離 worktree で SD1.5/SDXL 実 GUI、main で履歴18件・実 GUI・assembly を確認。実上流ファイルと配布版は未検証。`docs/state-manager-v2-import-wip.md` 参照。#23 は open。
- #23 現在の設定を生成せずお気に入りへ保存する機能を main に部分統合。分離 worktree の SD1.5/SDXL 実 GUI と main の履歴19件・実 GUI・assembly を確認。配布版と別 JVM 同名競合は未検証。`docs/state-manager-verification.md` 参照。#23 は open。
- #23 Clear history は明示名付き項目と favourites を保護するよう main で修正。旧版で改名済みの項目は記録がないため再度 Rename が必要。分離 worktree の SD1.5/SDXL 実 GUI と main の履歴20件・実 GUI・assembly を確認。詳細は同文書。#23 は open。
- 継続中の未統合 worktree: #21 panorama video は `C:/Users/Mizushima/.codex/worktrees/panorama21-completion/stablediffusion-nui` の `676440e` 以降を確認。実験 GUI と動画 decoder は未受入、過去に timeout・decoder error があり、media 同梱ライセンスも未確認。#6 顔 pass 専用 checkpoint も別 worktree で設計中。いずれも未 commit 差分・稼働中プロセスを確認し、無断破棄しない。
- 共有 GPU は `outputs/.gpu.lock` を原子的に取得して使い、自分が取得したロックだけを解放する。外部プロセスには触れない。sbt も同時実行を避ける。
- sbt 2 の `ui/assembly` がコード変更後にも古い JAR を up-to-date と誤判定した事例あり。variation の main 統合では JAR 内 class と compiled class の SHA-256 不一致を確認し、古い JAR を `outputs/variation-stale-assembly.jar` に退避して出力を削除、再 assembly で一致を確認。リリース前は JAR に最新の全対象 class が入っているか検証する。
- 後続作業は新しいコミット・worktree・GPU 状態を `git status`、`git log` と検証結果で確認してから再開する。上記は記録時点のスナップショット。

# Stable Diffusion NUI 引継ぎ — 2026-10-31（2026-11-01 Claude追記）

## 最新状態（2026-11-01 夜、Claude。これが最優先）

### いまのゴールと件数
- ユーザーのゴール: **次のリリース v0.05 を HANDSOFF の流れで用意する**（issue を 10 件解決 → 配布物を作って検証 → publish → ダウンロードした ZIP の SHA256 照合）。以前の MCP ゴールも継続。
- **v0.05 バッチ: 1/10 受入済み**（#115 Semantic Guidance, main `2b76bb6`, closed）。
- v0.04 は publish 済み（main `cda253d`、ZIP SHA256 `b58c4a8d…`）。

### main の状態（origin/main と一致）
- main HEAD `2e05ea0`「Build with sbt 2.0.12」。製品の未 commit 変更なし。未 commit は引継ぎ・進捗文書（`HANDSOFF.md`, `docs/HANDOFF.md`, `docs/ten-issue-release-progress.md`）だけ。
- **ビルドは sbt 2.0.12 + sbt-assembly 2.5.0**（ユーザー指示「高速化のため sbt 2.x」）。
  - コマンドは同じ: `%LOCALAPPDATA%\Coursier\datain\sbt.bat -batch test` / `ui/assembly`（出力は従来通り `target/stablediffusion-nui.jar`）。
  - クラス出力が `target/out/jvm/scala-3.3.6/<sdfx-module>/{classes,test-classes}` に変わった（dev スクリプト更新済み）。
  - **テスト結果がキャッシュされる**: 入力が変わらない suite は `Total 0` でスキップ（全体 26 秒）。強制再実行したいときは対象を `testOnly` で指定する。
  - `exportJars := false`（fixture を file として読むため）。forked テストの `java.class.path` は sbt の worker jar だけになるので、子プロセスの classpath はテストの ClassLoader の URL から作る（`TransportSuite` 参照）。
  - 移行前の worktree（下記）はまだ sbt 1.10 の設定。main へ取り込むと sbt 2 になる。
- GitHub Pages マニュアル公開済み: <https://kmizu.github.io/stablediffusion-nui/>（`site/content/{ja,en}`、`python site/build.py`、`site/**` の push で自動デプロイ）。機能を足したら `site/content/*/extensions.md` 等も更新する。

### 実装済み・未統合の worktree（各 agent が CPU テストまで済ませて commit 済み、push していない）
| issue | worktree / branch | commit | 状態 |
|---|---|---|---|
| #76 Loopback Scaler（Elldreth, MIT, `184a53e`） | `.claude/worktrees/agent-ac04536ef19849115` / `worktree-agent-ac04536ef19849115` | `867f740` + 未 commit `scripts/dev/check_loopback_scaler_real.py` | **実 SD1.5/SDXL MCP check 合格**（`outputs/loopback-real-{sd15,sdxl}`、画像目視済み）。SD1.5 は loop ごとに彩度が落ちる（初回 img2img から起きる model/VAE 由来、SDXL では起きない）。上流の横長画像の高さ計算バグは修正して実装（doc に記載）。残り: main へ squash 統合、ledger/notice（MIT）、full 回帰、commit/push/close。 |
| #4 Agent Attention（v0xie, GPL-3.0, `8c2fd24`） | `.claude/worktrees/agent-a4cc6b76c60335c18` / `worktree-agent-a4cc6b76c60335c18` | `514318c` + 未 commit: `python/sdfx/agent_attention.py`（merge の平均を決定的な one-hot bmm に変更）、`scripts/dev/check_agent_attention_real.py` | 1 回目の実 SD1.5 check で **PNG replay が一致しなかった**（CUDA の scatter_reduce が非決定的）。修正後の SD1.5 再 check を実行中（`outputs/agat-real-sd15`）。次: 結果確認・画像目視 → SDXL（`--sdxl --side 1024 --config md2 '{"max_downsample":"2"}' --config md4sp '{"max_downsample":"4","use_sp":true,"sp_step":10}' --config md1 '{"max_downsample":"1"}'`）。 |
| #9 CD Tuner（hako-mikan, AGPL-3.0, `aafdbdd`） | `.claude/worktrees/agent-a5e1295c8bcb0080a` / `worktree-agent-a5e1295c8bcb0080a` | `1d6714d` | CPU テスト合格（Torch 2,363 pass）。**実モデル check 未実施**。hook 名 `cd_tuner`、キー `d1,d2,con1,con2,bri,col1,col2,col3,hd1,hd2,st1,st2,dis,sat,sat2,hrs,apply_once,split_ratio,split_by,colors,map_stop_step,map_strength`。prompt タグ `<cdt:…>` 対応。check script は未作成（semantic guidance / agent attention の script を雛形に）。 |

統合の手順（#115 と同じ）: worktree の差分を main に squash で取り込み（衝突は StandardPlugins / PluginValuesVM / InspectGenerationMetadata / hooks.py / engine.py features / dryrun.py / extensions.py / site の extensions.md で、両方残せばよい）→ `scripts/extensions_matrix.py` を done に（index は `grep <target> scripts/extensions_matrix.py`）＋ SCOPE_NOTES → `scripts/third_party.py` に上流と LICENSE の SHA256 を追加 → `python scripts/extensions_matrix.py` と `python scripts/third_party.py`（licenses/ の CRLF だけの差分は `git checkout -- licenses/` で戻す）→ full Torch CPU + Scala → 1 issue 1 commit（`Closes #N`）→ push → close 確認。

### 再開後の状態（2026-11-01 21:30、Claude）
- **v0.05: 10/10 公開済み**（https://github.com/kmizu/stablediffusion-nui/releases/tag/v0.05, SHA256 `fbdb3ba8…0523` 再ダウンロード照合済み）。#118 Sonar `b372e74`、#94 `016ec74`、#75 LLuL `9a721af`。
- **NudeNet NSFW Censor（#91 の index 238）は main `73b3fe1` に統合済み**（real SD1.5/SDXL 合格・目視済み、`Refs #91`）。#91 は index 83（A1111 nsfw-censor、safety checker で黒塗り）と 231（NSFWJS blurrer）が残るので open のまま → 件数にはまだ数えない。83/231 も実装すれば #91 を close できる。
- 実装中の worktree（agent、CPU テストまで）: #11 Characteristic Guidance、#63 Incantations。各 agent の報告後に real check（`scripts/dev/check_*_real.py`）→ 目視 → main へ squash 統合 → ledger/notices → full 回帰 → commit/push。
- Kimi の会話ログ `~/.kimi-code/sessions/wd_stablediffusion-nui_615a26c3f92a/session_e6b3f0d9-…`: ユーザー指示「follow HANDSOFF. At first, nudenet-nsfw-censor should be ported」。Kimi が計画を作って承認を得た直後に利用上限で停止（コードは未着手）。計画: `…/agents/main/plans/sunspot-animal-man-forager.md`。
- その計画に沿って **NudeNet NSFW Censor（#91 の index 238）を agent が worktree で実装中**。#91 は 83/231 も含むので commit は `Refs #91`（close しない）。統合時に real check（`scripts/dev/check_nsfw_checker_real.py`）を実行。
- #4 Agent Attention: 決定的 merge 修正を worktree に commit（`03c530c`）。SD1.5 real check 合格・画像目視済み。SDXL check 実行中（`outputs/agat-real-sdxl`）。
- 下の「中断時点」の #76 の項は完了済み。

### 中断時点（2026-11-01 夜、利用上限で停止）
- **main の作業ツリーに #76 Loopback Scaler の squash merge が staged のまま（未 commit）**。`git status` で確認。残り: ledger（index は `grep loopback_scaler scripts/extensions_matrix.py`）・SCOPE_NOTES・`scripts/third_party.py` に Elldreth/loopback_scaler `184a53e`（MIT）追加 → 再生成 → full 回帰（sbt 2 なので速い）→ `Closes #76` で commit/push。
- #4 Agent Attention: 決定的 merge 修正後、SD1.5 で PNG replay 一致を確認。hires_fix_only の停止は **GPU の VRAM 不足（Ollama llama-server が約 20GB 使用）による sysmem fallback の遅延**と判断（CUDA_LAUNCH_BLOCKING 時や再実行では 7〜12 秒で完了）。full SD1.5 check 再実行は **success**（`outputs/agat-real-sd15/receipt.json`、画像の目視はまだ）。次は SDXL check。worktree の未 commit 修正（agent_attention.py, check script）を commit してから統合。
- ユーザー依頼: NudeNet NSFW Censor は #91 にコメント済み（新規 issue は作っていない）。

### 次にやること（順番）
1. #4 の SD1.5 再 check 結果を見る（`outputs/agat-real-sd15/receipt.json`, 画像目視）。通れば SDXL。
2. #76 → #4 → #9 の順に main へ統合・検証・commit。#9 は実 SD1.5/SDXL check を作って実行。
3. 残り 6 件を選んで進める。候補: **#91 nsfw_checker（NudeNet NSFW Censor 相当を優先、ユーザー依頼。2026-11-01 に issue へコメント済み。上流 w-e-w `1fb21cf`, AGPL-3.0, `nudenet/best.onnx` 12MB のライセンス要確認）**、#11 characteristic_guidance（GPL-3.0）、#63 incantations（GPL-3.0）、#75 llul、#29 custom_hires_fix（ライセンスなし → 挙動から再実装）、#72 lcm（専用タブが大きい）。
4. 10 件そろったら v0.05: final main で `ui/assembly` → `pwsh scripts/package_windows.ps1 -JdkHome "C:/Program Files/Java/jdk-21" -Jar target/stablediffusion-nui.jar -Version 0.05 -OutputRoot outputs/<dir>` → 展開して payload 照合 → 配布 exe で実モデル check → `gh release create v0.05 …` → 再ダウンロードして SHA256 照合 → リリースノートに照合結果を追記。

### 実モデル check の共通事項
- MCP client venv: `C:/Users/Mizushima/.codex/worktrees/affc/stablediffusion-nui/outputs/mcp-client-venv/Scripts/python.exe`（mcp==2.3.0, Pillow）。
- エンジン Python: `python/.venv-smoke/Scripts/python.exe`、`PYTHONPATH=<対象 tree>/python`。モデル: `D:/stable-diffusion-webui-reForge/models/Stable-diffusion`（SD1.5 `animekawa_v10`、SDXL `waiIllustriousSDXL_v170`）。
- 各 `scripts/dev/check_*_real.py` は receipt.json を出す。**assert が通っても画像は必ず目視**（Semantic Guidance では scale 3 で全破綻していたのを目視で発見した）。
- 既存のテスト漏れ: pytest 実行ごとに repo 直下へ `iteration-0-image-0-seed-1.mp4` ができる（`python/tests/test_save_intermediate.py:318`、未修正）。消してから commit する。

このファイルが最新の引継ぎ。過去の長い記録は `docs/HANDOFF.md`、件数台帳は `docs/ten-issue-release-progress.md`。未完成の worktree を main と混同しないこと。

## ユーザーの現在のゴール

> Stable Diffusion NUI を AI エージェントから MCP 経由で実用的に使えるようにする。AI が利用可能なモデルや機能を調べ、モデルの選択、画像生成・編集、進捗確認、結果の取得、中断、対応する拡張機能の操作を行えるようにする。
> MCP の接続方法と各ツールの入力・出力を整備し、stdio と HTTP の両方について、実際の MCP クライアントから SD1.5・SDXL を使った一連の操作を検証する。GUI で使える機能のうち MCP から使えないものは明確にし、必要な対応を進める。
> 引き続き issue をできるだけ速く解決し、10件解決するごとにリリースする。各リリースでは、修正・検証・配布物の確認まで行う。

- 今回の区切りは **9/10件受入済み、残りは #17**。10件になったらリリース。その後も10件ごと。
- 以前の20件目標の実績を今回の10件に足さない。新しいMCP目標に更新したことを理由に現在の9件をゼロに戻さない。
- ユーザーは速度重視、agentsの使用を明示許可。重複検証を減らすが、元拡張の範囲を黙って削ったり、未検証を完了に数えたりしない。
- 将来の多言語対応にも関心あり。まだ対象言語・範囲は未確定、今回のリリース条件へ勝手に追加しない。
- 会話は敬語なし、落ち着いた柔らかい北摂弁。確認の繰返しより実行を優先。

## main とリリース

作業ディレクトリ: `C:/Users/Mizushima/dev/stablediffusion-nui`。
GitHub: private repo `kmizu/stablediffusion-nui`、push先 `origin/main`。

main HEADとremote HEADを実確認（2026-11-01、Claude）:
`56c1ade0fcc9dd748eab4285ffd2b109bb625982`（MCP統合。issue件数には数えない）。
mainには製品の未commit変更なし。引継ぎ・進捗文書は未commit。このファイルも未commit。

## 2026-11-01 追記: MCP共通はmain統合済み

Kimi K3.1がaffcで最終review→修正（5766dac）→再review PASS→main 440a119をmerge→fresh package照合（276file、jar `e85e15c1…`）→公式SDK `--require-extensions` の packaged `SDNUI.exe` 経由 SD1.5/SDXL×stdio/HTTP 4runすべて成功（progress100、PNG image block、replay 2組RGB完全一致、inpaint、cancel後復帰、画像目視）まで実施し、Scala全回帰中に利用上限で停止した。
Claudeが引継ぎ: Scala全回帰の唯一の失敗 `PixelScrambleSuite` はaffcに `python/.venv-test` が無い環境起因。junction作成後13/13 pass（他suiteは1,512 pass/1 skip）。Torch CPU 2,152 pass/1 skip。receipt 4件のRGB一致を再確認。検証tree `3417ca5` と同一treeのsquash commit `56c1ade` としてmainへff、push、local/remote一致確認。
#17 進捗（Claude, 2026-11-01）: worktree 2252 を branch `wip/fastload17` にcommit保全（4d2a123）→ main 56c1ade をmerge（2d8fe5f、衝突4件は両方保持）→ control_mode/processor_res/pixel_perfect をnative ControlNetへ追加、上流互換canny・residual hook・mask選択を実装（505561a）→ `list_controlnets` MCP、実モデル check `scripts/dev/check_fastload_real.py`（d73fb5b）。実SD1.5/SDXL MCP stdio check 両方success・画像目視済み（`outputs/claude-fastload-real-*`）。残り: full Scala/Torch CPU回帰、独立review、package検証、ledger更新、main統合・push・#17 close、v0.04 release。
既知の既存テスト漏れ: `python/tests/test_save_intermediate.py:318` のfake Popenが `cwd` を無視し、pytest実行時にrepo直下へ `iteration-0-image-0-seed-1.mp4`（5byte）を書く。#48由来、未修正。

| 件数 | issue | mainの受入commit |
|---|---|---|
| 1 | #48 image scrambling | b8a623fdad1d78317a10b37149192a59ccff3499 |
| 2 | #61 img2img Hires Fix | 65b4ddcc11e8c2438c30cdd57b3d7cfc3732e019 |
| 3 | #82 Metadata Utils | 777d128cd02762d4b8749ddefe3d7d70c83fc353 |
| 4 | #46 Embedding Inspector | 09202c7447bbe77f4a1866f6efed008f84fc9382 |
| 5 | #47 Embedding Merge | 3607a8324fc849fc50e4eec2df0c386ccb27ac9d |
| 6 | #16 Conditioning Highres Fix | 4e0b32c307ad3ec23f4a1889449986acd3955b68 |
| 7 | #87 Model Presets | 5934d8183bb838839e31e0af331c120f8387113f |
| 8 | #85 Model Keyword | d8375052e07df4295bc593938b63cb36844bdd47 |
| 9 | #45 Embedding Editor | 440a119c1a7126c59d23405c34f161450e9a86f7 |

全9件はpush後にlocal/remote一致とclean treeを確認してissueをcloseした。
#85のScalaは初回全体で4失敗、テスト/fixture/環境修正後に該当3 suiteが成功。修正後の全体再実行成功とは書いていない。
#45はfull Torch CPU exit0、Scala1493pass/1skip、ledger25、SD1.5 model_offload/SDXL sequential_offloadのfresh packageと独立reviewを通して受入。

現行公開releaseは `v0.03`（2026-10-27）、v0.01/v0.02も存在。次は通常v0.04を検討し、実際のタグ状態を再確認する。作業用ZIPはv0.03名を再利用しているが、新releaseではない。
10件受入後、final mainでbuild/package/実機検証→publish→remote tagとダウンロードしたZIPのSHA256を検証する。

## 引継ぎ時の停止状態と最新バックアップ

引継ぎのためagentsの新規編集・起動を止めた。全agentsは完了/待機状態、自分たちのGPU・sbt・workerは終了済み。rootの最終MCP buildもexit 0。
プロセス一覧で該当worktreeのjava/python/SDNUI、およびsbt-launchが残っていないことを確認した。他人のプロセスを終了しないこと。
goal自体は **active** のまま。ユーザーからのgoal pause指示ではない。

今回、全dirty sourceとstaged/unstaged binary patchを新規ZIPへ退避し、ZIP内の全entryを元bytesのSHAで照合した:

`C:/Users/Mizushima/.codex/backups/handoff-20261031-160b8f7a36a2/`

| ZIP | 内容 | SHA256 |
|---|---|---|
| main.zip | 進捗文書2件（本HANDSOFF作成前） | 6f46f1267932ec65bfddc8d8fa3681b462f17fe91d94d13ae833f1476ab89ddb |
| mcp.zip | root MCP worktree未commit15ファイル | 39bb4104f7a5bb9d22733583aaf5606029c423df4bdff35806fde54d2317d662 |
| editor45.zip | #45未commit49ファイル | 4a91f1f6257fbe8bd94ad580bf62643cd38f7a56d824075c2cd69d94ded4c245 |
| fastload17.zip | #17未commit86ファイル | cbfaee7510aede2511cd2f5e55e493f46677307ec7032b945f8842ecc6603db8 |

各ZIPにHEAD/status/manifest/staged.patch/unstaged.patchあり。**ignored outputs、モデル、venv本体は含まれず、元worktreeに保持**。ZIPは差分保全であり、Git履歴とworktreeを削除してよいという意味ではない。

## 3つの作業worktreeと担当

| 作業 | worktree | HEAD | agent |
|---|---|---|---|
| MCP共通・外部SDK検証 | `C:/Users/Mizushima/.codex/worktrees/affc/stablediffusion-nui` | b92b0bc8f9541cddef8c90db6512ed9c951ee8bd | root |
| #45 Embedding Editor | `C:/Users/Mizushima/.codex/worktrees/embedding-editor45/stablediffusion-nui` | d8375052e07df4295bc593938b63cb36844bdd47 | hires_restore_review |
| #17 ControlNet Fastload | `C:/Users/Mizushima/.codex/worktrees/2252/stablediffusion-nui` | d8375052e07df4295bc593938b63cb36844bdd47 | custom_code_policy |

`integration_review` は現在待機。独立レビュー、MCP補助、ControlNet policy helperを担当した。手元worktreeは `C:/Users/Mizushima/.codex/worktrees/d919/stablediffusion-nui`。
完了agentは `send_message` だけでは動かない。再開は `followup_task` を使う。

## MCP共通実装（root affc、main未統合）

worktree HEADに入った機能commit（mainにはない）:

- 53ccf70: PNG inspect/replay候補。元agent commit 457d74e。
- 2234d54: variation、独立Hires、manual_loras、ControlNet型付き生成引数。元 b674519。
- d03e51b: FreeU v2 / HyperTile / PAGのPNG復元候補。元 cdd7962。
- b92b0bc: wildcard traversal対策・画像書込失敗時cleanup。元 f1c805d。

上記に加え **rootの主要AlwaysOn/SDK/接続例/packaging変更は未commit**。`git diff`と未追跡ファイルも必ず見る。

主な未commitファイル:

- `mcp/src/main/scala/sdfx/mcp/AlwaysOnInputs.scala`
- `McpTools.scala`、`Images.scala`、AlwaysOn関連2 test suite
- `python/sdfx/mcp_model_refs.py`、`adapters.py`、対応Pythonテスト
- `scripts/dev/check_mcp_client.py` と `requirements-mcp-client.txt`
- `.mcp.json.example`、`mcp/README.md`、`scripts/package_windows.ps1`
- `docs/mcp-agent-verification.md`、実装計画

実装内容:

- `list_always_on`でtyped controlsを発見し、`always_on: {pluginId: {control: value}}`をsync/async/scriptの全jobへ渡す。
- GUIの保存画像pathやscript値を暗黙継承せず、宣言default＋明示値を検証。model_keywordの既存default/別bool aliasは互換維持、二重指定は拒否。
- 画像は既存PathPolicy/base64制限。head/custom modelはbare filenameもpath検査。HF encoder指定は`hf://owner/repo`へ明示化し、Hub snapshotへ解決してローカル同名dir衝突を防ぐ。
- wildcard_dirsは許可dirのみ。wildcard名の`..`/絶対/UNC/静的symlink escapeも拒否。親junctionの悪意ある並行交換まで原子防御する実装ではない（`docs/mcp-input-safety.md`）。
- Fastloadの`controlnet_fastload.path`は非画像regular file扱い。save_only / current_firstで現在active ControlNetがある場合は使わないsourceを空にし、不要な存在確認をしない。
- PNG inspectは画像を再保存せず、圧縮textの展開量も制限。生成はしない。候補とunappliedを返す。
- 接続例の歴史的reForge内パスを修正し、配布物へMCP説明/例を含める。
- Custom Code、model merger/converterの既存MCP制限は無断解除しない。

最新確認:

- `outputs/mcp-agent-reviewed-final.log`: **MCP 78/78、WildcardPathSafetySuite 1/1、assembly exit0**。
- 現jar SHA256: `5ae625935c20f857e0dab896d44beca824570b937d37dc7c7ed55b0fc09083fb`。
- `outputs/mcp-model-ref-neutral-green.log`: Python33 passed。
- wildcard/cleanup元agent focused19 pass、HF参照RED→GREENあり。

### 外部MCPの確認済み範囲と未完

公式Python SDK **mcp==2.3.0、Pillow==13.0.0**、Python3.12の独立venv:
`affc/.../outputs/mcp-client-venv/Scripts/python.exe`。

**受入済み#85相当jarの基礎API**を、別serverプロセスに対して実SD1.5/SDXL×stdio/HTTPの4通りすべてexit0で確認した:

`affc/outputs/mcp-sdk-real-{stdio,http}-{sd15,sdxl}/receipt.json`

各回でtools/list、model/sampler/script列挙、set_model、txt2img、get_image、img2img、async進捗/完了、cancel_job、cancel後生成を確認。代表画像を目視済み。
既存配布 `SDNUI.exe --mcp` + bundled JREも公式SDK stdio **dry-run**で確認済み:
`outputs/mcp-sdk-packaged-launcher-stdio/receipt.json`。

**新AlwaysOn・新PNG復元を含む最終外部実機確認はまだ**。旧4runを新機能の証拠にしない。
`check_mcp_client.py --require-extensions` を追加済みだがまだ実行していない。このflagはFreeU生成→PNGからalways_on候補ごと復元→RGB一致、通常PNG replay、mask inpaintも確認する。
直近のserver_info/protocol記録・Pillow比較などscript変更も未smoke。

次の手順:

1. 未commit差分を確認、必要なら上記backupから比較。最終実装の独立再レビュー。
2. packageは `git ls-files` でPythonを列挙するため **`git add -N python/sdfx/mcp_model_refs.py` が必要**（stashでintentが外れて現在未追跡）。
3. 現jarからfresh packageを生成・展開し、source/JAR/hashを照合。以前のroot ZIP `31946267...` は修正前でstale。
4. 下記の公式SDKハーネスを最初にdry-run、その後SD1.5/SDXL×stdio/HTTPで `--require-extensions` 付き実行。`--launcher`で本物の配布exeを使う。モデルは下記read-onlyフォルダ。
5. receipt、復元RGB、画像目視、取消後復帰、所有process終了を確認。docsのpendingを実測で更新。
6. mainへ統合し#45/#17との重複（McpTools登録、Generator等）を保存。MCP機能追加だけでissue件数を増やさない。

```powershell
# affc cwd。実際の展開先に置き換える。stdio→http、SD15→SDXLの順など一つずつ実行。
outputs/mcp-client-venv/Scripts/python.exe scripts/dev/check_mcp_client.py `
  --transport stdio --require-extensions --side 512 --steps 12 `
  --checkpoint animekawa_v10 --models D:/stable-diffusion-webui-reForge/models/Stable-diffusion `
  --launcher <fresh-package>/SDNUI.exe --jar <fresh-package>/app/stablediffusion-nui.jar `
  --source <fresh-package> `
  --python C:/Users/Mizushima/dev/stablediffusion-nui/python/.venv-smoke/Scripts/python.exe `
  --output outputs/mcp-sdk-final-stdio-sd15
# SDXL: checkpoint waiIllustriousSDXL_v170、side768。dry-runは --dry-run と side64、.venv-testを使う。
```

## #45 Embedding Editor — 受入済み 9/10

main `440a119c1a7126c59d23405c34f161450e9a86f7` へ統合・pushし、local/remote一致を確認して2026-10-31T03:35:25Zにclose済み。ledgerはindex16 `done:core:embedding-editor`、124 done / 178 todo / 51 na。

- VAE offloadの入力を`engine.device`へ配置し、upcastを`finally`で復元。HEAD baselineで5 RED、現行focused87 GREEN。
- PT/BINはreader対応のraw tensor root・任意single-key dictも保存可能。外部上書き競合はsourceの原子退避・実bytes検証・new-only hardlinkで防ぐ。hardlink直後中断もreload-errorを残す。
- 最終full Torch CPU exit0、Scala1493pass/1skip、ledger25。独立reviewは最終的にCritical/Importantなし。
- 最終ZIP SHA256 `97315ab1e2724b54143f6601a70517ee0a66f98b2607d4d1606a9e323d4b02af`。fresh extracted packageでSD1.5 model_offload・SDXL sequential_offloadともexit0、inversion preview/recovery/save後RGB・画像目視を確認。

詳細と残存制限はmainの `docs/embedding-editor-verification.md`。worktree `embedding-editor45` のcommitは `bae88c2`（mainへcherry-pickした同一patch）。

## #17 ControlNet Fastload — 実装途中、scopeは大きい

worktree2252、HEADはmain85。多数未commit、最新状態は今回fastload17.zipで保全済み。
個別古いbackupに最新Generator wiringはないので、上記最新ZIPを優先。

既存成果:

- bounded symbolic pickle parser（未信頼pickleを実行しない）、marker+gzip、原writer/readerとの限定相互検証、旧/新unit・NumPy1/2。
- privacy-aware inspect/capture/transfer/gallery、36 thumbnail、filters/pages、session変化/競合/取消のCPU・実JavaFXテスト。
- 元ControlNet resize18合成参照をpixel-exactに再実装。既存native resizeは維持。
- 固有MCP toolは別ファイルで **version/inspectのみ**。PathPolicy・protected session拒否・owner取消。focused26pass。ただしmountedはPYTHONPATH不足で失敗し未受入。
- control policy helper `5fc23477bcc8985eeb7ab586fd8697a42fe9cc97` の2ファイルのみ移植済み。CPU13pass、engine未配線。共有cache modelを複数unitが使うためmodel固定hookにしない。

最新の未検証変更:

- `FastloadJobPlan` / `FastloadGeneration` / `FastloadOutput` とGeneratorへauto Load/Save初回配線。
- 生unit/画像をGenerationParams.hooksやdiagnosticsへ入れずjob-private保持、生成ごとtemporary入力/メモリ掃除。
- loaded-firstで不要な現行画像を読まない、保護中のreadable保存を入力処理前に拒否。
- outputはnew-only hardlink公開。bothでimage成功後sidecar失敗は部分成功（画像を残してエラー）。
- plugin classは **registry未登録**。最新Generator wiringは **未compile/未GREEN**。
- 以前のGenerator REDは期待の未配線1fail＋新testが共有homeに画像を残す汚染1fail。後者はisolated homeへ修正済み、再実行待ち。

次の短い検証（2252 cwd）:

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
$env:CUDA_VISIBLE_DEVICES='-1'
$env:PYTHONPATH=(Resolve-Path python).Path
& "$env:LOCALAPPDATA/Coursier/data/bin/sbt.bat" -batch `
 'engine/testOnly sdfx.engine.FastloadJobPlanSuite sdfx.engine.FastloadOutputSuite sdfx.engine.GeneratorSuite' `
 'plugins/testOnly sdfx.plugins.ControlNetFastloadPluginSuite' `
 'ui/testOnly sdfx.ui.FastloadCaptureSuite sdfx.ui.FastloadFormSuite sdfx.ui.FastloadInputStoreSuite'
```

残件: plugin登録/MCP全操作、自動load/saveの実配線と公開、source control_modeのunit別soft/CFG/hires/advanced、processor_res（-1にも作用あり）、save_detected_map（既定trueも出力追加）、inpaint/CLIP mask・batch等の元scope、commit/cancel/衝突/partial、real SD15/SDXL ControlNet・画像目視、native/MCP、freshpackage/全回帰。
単なる設定値保持を「適用済み」と呼ばない。未対応を拒否/保持している段階でdoneにしない。
MCP共通入力のplugin id `controlnet_fastload`、非画像source key `path`。root affc側にPathPolicy/未使用path省略対応を追加済み、統合時に保持する。
検証モデルは2252の`outputs/fastload-models`に固定revision・SHA付きで取得済み（SD15約723MB、SDXL約2.5GB）。本体を配布しない。詳細 `docs/controlnet-fastload-verification.md`。

## 共通環境・運用

- PowerShell / Windows。LFで書く。
- JDK21: `C:/Program Files/Java/jdk-21`、sbt: `$env:LOCALAPPDATA/Coursier/data/bin/sbt.bat`。
- GPU Python: main `python/.venv-smoke/Scripts/python.exe`（Python3.10、torch）。no-torch: main `.venv-test`。
- CPU回帰は **CUDA_VISIBLE_DEVICES=-1**。空文字はWindowsで環境変数削除になりCUDAが有効になる。必要ならtorch.cuda.is_available()==Falseを確認。
- 子Python import用に **PYTHONPATH=<対象worktree>/python** を明示。pytestのsys.pathは子プロセスに継承されない。
- Scalaの一部testが `<worktree>/python/.venv-test` を直指定する。必要な場合mainの実在venvへのjunctionを作る（環境本体を変更しない）。
- SD15: `D:/stable-diffusion-webui-reForge/models/Stable-diffusion/animekawa_v10.safetensors`。
- SDXL: 同フォルダ`waiIllustriousSDXL_v170.safetensors`。
- **親reForgeは参照とモデル読み取りだけ。import/launchしない。**
- GPUは原則1件ずつ。sbtも相談して順番に回す。独立worktreeの短いfocusedを長い既存bulk scanと並列にした実績はある。
- `ModelCardScannerNativeBulkSuite` の4097file Windows probeは2ケース合計7分前後かかる。ログ停止だけでハングと判断せずowned JVMのjstack/CPUと進行を確認。timeoutを黙って延長しない。
- packageはtracked Pythonをコピーする。新moduleはindexへ含め、展開した全payload/JAR/hashを照合。作業用v0.03 ZIPと実releaseを区別する。
- main統合時は進捗文書を一時stashして製品commitをcherry-pick→push→HEAD/remote/clean確認→issue close確認→進捗文書復元、という手順を使ってきた。stashを取り違えない。
- #82 native ledgerの過去誤分類に注意。登録Pluginなら`done:<plugin_id>`、native専用tabなら`done:core:<name>`。generatorsとBuiltins/NativePresetsCoverageを確認する。

## 再開の最短順

1. このファイル→各worktreeの現在状態/未commit/実プロセスを再確認。上書きresetや古いstash復元をしない。
2. root MCPの新package/SDK `--require-extensions` の最終4runを行い、mainへ統合。
3. #17の元scopeを分担して完成・実機/MCP/配布版検証し、10/10へ。
4. 10件のcloseと最終mainを再確認し、次releaseをpublish、ダウンロードSHA検証。

引継ぎ文書の作成は完了しているが、製品ゴールは未達。goalをcomplete/blocked/pausedへ変更しない。

## Kimi K3.1 ログ引き継ぎ記録（2026-11-02、両 wire.jsonl を全行読み）

対象: `~/.kimi-code/sessions/wd_stablediffusion-nui_615a26c3f92a/`。`notify/state.json` は両方 `enabled:false`。

### session_b6d8a71f（2026-10-31, 3113 行, goal「@HANDSOFF.md を引き継いで」）
1. #45 Embedding Editor の VAE offload バグ: `RuntimeError: Input type (torch.FloatTensor) and weight type (torch.cuda.FloatTensor)` → VAE 入力を `engine.device` へ。RED→GREEN、packaged で SD1.5 `model_offload` / SDXL `sequential_offload` を画像確認。独立 review の指摘（PT/BIN raw-tensor 保存、save/publish 競合、hardlink 中断時の reload）を修正。main へ `440a119`、#45 close（その時点 9/10）。
2. 1 回目の 429 → 手動 compaction（wire 1929 行）。
3. MCP worktree `.codex/worktrees/affc`: review「WITH FIXES」→ `5766dac` → 再 review PASS → main `440a119` を merge（`b17b621`）→ MCP test 85 pass・assembly OK → 旧 package で payload・SDK dry-run・SD1.5/SDXL × stdio/HTTP 実モデル 4 run 成功 → 証跡 `3417ca5`（276 payload 一致）。
4. 統合 tree で Torch CPU 2152 pass / 1 skip。Scala 全回帰 `bash-848uxnq4` が 456 秒で failed。通知を受けたターンが 429 で死に、Kimi は一度も読まないまま停止（最後の行は 10-31T14:11:56Z、`resume` が 429）。
5. #17 の計画を読み `explore` subagent を投げたが即 429。

**Scala 失敗の原因（`affc/outputs/mcp-full-scala.log` で確認）**: `PixelScrambleSuite` の 1 件 `Python and JVM exchange 16-bit RGB and RGBA PNGs…` が `Cannot run program ".../affc/.../python/.venv-test/Scripts/python.exe" CreateProcess error=2`。worktree に git-ignored の `.venv-test` が無かっただけの **環境問題**（Total 602, Failed 1）。他は全 pass。製品バグではない。worktree には `.venv-test` の junction/symlink が必要（本ファイルの手順どおり）。

**この session の成果の行き先**: main に `56c1ade Add MCP agent workflows with AlwaysOn control and PNG replay`（+ #17 `cda253d`）として再統合済み（`3417ca5` 自体は main の祖先ではなく、squash 相当）。v0.04 公開済み。

### session_e6b3f0d9（2026-11-01, 322 行, goal「nudenet-nsfw-censor を移植」）
superpowers の brainstorming → plan mode → HANDSOFF/docs 読み、upstream 固定 `1fb21cf9…`、`explore` subagent の推奨（Face Restore 型: `NsfwCheckerPlugin` + `NsfwCheckerExtrasPlugin` + 共有 `nsfw_checker.py`）→ plan `plans/sunspot-animal-man-forager.md`（8,494 B）→ ユーザー承認 → 「隔離 worktree で実装」と宣言し `using-git-worktrees` を読んだところで 429。worktree もリポジトリ変更も無し。成果物は plan のみ。
→ Claude 側で #91 として実装・main 統合（`73b3fe1`, 追加で safety checker/NSFWJS `908a22d`）。v0.05 に収録。

### 未完了の約束
どちらの session にも Claude 側で未消化の項目は残っていない（MCP の push、#17、v0.04、NudeNet 実装、v0.05 公開はすべて完了）。Kimi の `affc` worktree（detached `3417ca5`）と `.codex/worktrees/2252`（`wip/fastload17`）は参照用に残してあるだけで、main へは再統合済みのため使わない。

### 引き継ぎ受入の追加確認（2026-11-02）
Kimi の受入マトリクス（SD1.5/SDXL × stdio/HTTP、公式 SDK、`--require-extensions`）を v0.05 配布 `SDNUI.exe` で再実行し、4 run すべて success（`outputs/pkg-v005-*`, `outputs/pkg-v005-http-*`）。Kimi の `3417ca5` 単体は main の祖先ではないが、その内容は `56c1ade`/`cda253d` として main に入り、配布物で同じ受入が通ることを確認済み。

## v0.06 バッチ（ユーザー指示 `issues.take(20).map(_.resolve)`、2026-11-02 開始）
解釈: open issue を番号昇順で先頭 20 件（#2,3,5,6,8,10,12,13,14,18,19,20,21,22,23,24,25,26,27,28）を解決する。agent を worktree で並行実行（GPU は `outputs/.gpu.lock`）、私が 1 件ずつ merge --squash→テスト→push。実装不能（モデル/依存不足など）の issue は実装済みと数えず理由を記録する。進捗はこの節に追記。
