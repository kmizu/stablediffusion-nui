# Codex への引き継ぎ (2026-11-02)

Claude Code の週間上限が近いため、作業を Codex に引き継ぐ。まず `AGENTS.md`、`HANDSOFF.md`(長い履歴)、`docs/port-agent-brief.md`(移植タスクの共通手順)を読む。このファイルが最新の状態。

## 1. ゴール

- ユーザー指示 `issues.take(20).map(_.resolve)` = open issue を番号昇順で先頭 20 件(#2, 3, 5, 6, 8, 10, 12, 13, 14, 18–28)を解決する。
- 追加の依頼: **#143 低 VRAM・高速化の最適化** をアプリ本体に入れる(実測つき)。#142 Image Browser 厳密移植(部分実装済み)。
- 次のリリースは **v0.07**(下の手順)。v0.05 と v0.06 は公開済み。
- ルール(ユーザーの方針): 未検証のものを「完了」と数えない。実装不能な部分は `SCOPE_NOTES` に書いて `Refs #N`(`Closes` にしない)。実モデル(SD1.5 `animekawa_v10`、SDXL `waiIllustriousSDXL_v170`)で確認して画像を目視する。コードはコピーせず再実装する。LF 改行。1 issue 1 commit、`main` に push。

## 2. 現状 (main = origin/main、台帳 180/302 done)

| 状態 | issue |
|---|---|
| close 済み | #3, #5, #8, #10, #13 |
| 部分実装、push 済み、issue は open のまま(コメントで未対応を記載) | #2, #6, #12, #14, #18, #19, #20, #21, #22, #23, #24, #25(最後が `89a0936`) |
| **作業中 (worktree に未コミットの作業)** | #26, #27, #143 |
| 未着手 | #28 |

部分実装の中身は、各 `docs/*-verification.md` と `scripts/extensions_matrix.py` の `SCOPE_NOTES` にある。主な既知の弱点:

- #14 CN Animation: text-to-video なし。SD1.5 は色が灰色にずれる。SDXL で安定化は未実証。
- #19 compile: TensorRT は RTX 3090 で遅い。「組み合わせ可能なフック」の許可リストは実モデル確認の後で狭めた。
- #20 Localization: 組み込みは日本語のみ。上流パックは網羅率 1.8–18.2%。
- #23: 台帳は `todo` のまま(履歴と範囲指定の復元のみ)。

## 3. 作業中の worktree(すべて Claude の agent が使っていた。セッションが止まるので結果は届かない)

場所は `.claude/worktrees/agent-<id>`、ブランチは `worktree-agent-<id>`。各 worktree で `git status` と `git log -1` を見て、残っている作業を確認する。まだコミットされていない。

| issue | id | 状況 |
|---|---|---|
| #26 | `afb99384e42e1188b` | 実装中 |
| #27 | `a40abe8e29871d4c8` | 実装中 |
| #143 | `ab7508137bf41c07e` | 実装済み・計測中。`perf.py`、`vae_memory.py`、`deepcache.py`、`scripts/dev/bench_optimizations.py` など。計測結果から各オプションの既定値を決める段階。DeepCache(interval 3)は SD1.5 で速いが画像差 8.9% なので既定は off の予定。VAE 復号が VRAM のピーク(SDXL で denoise 7.1 GB に対し復号で 11.9 GB)で、VAE slicing と fp16 復号が効く |

終わらせる気がなければ worktree ごと捨ててよい(`git worktree remove`)。issue #26、#27 の中身は `gh issue view` で確認する。

## 4. 移植 1 件の流れと、マージの手順

共通手順は `docs/port-agent-brief.md`。agent の成果を main に入れる手順(これまでずっと使ってきた):

1. `git merge --squash <ブランチ>`。衝突は **両方を残す** が基本(`StandardPlugins.scala`、`hooks.py`、`engine.py`、`dryrun.py`、`extensions.py`、`InspectGenerationMetadata.scala`、`PluginValuesVM.scala`、`MainWindow.scala`、`site/build.py`、`site/content/*/extensions.md`、`scripts/extensions_matrix.py`、`scripts/third_party.py`)。
2. **衝突の後の落とし穴**: `InspectGenerationMetadata.scala` の `represented` 集合で `++` が抜ける、テストファイルで閉じ括弧 `}` が抜ける(どちらもコンパイルエラーで気づく)。`KNOWN_HOOKS` / `engine.py` / `dryrun.py` のフック一覧に新しい名前が入っているか確認する。
3. `python scripts/extensions_matrix.py`(台帳と `docs/EXTENSIONS.md` を再生成)。`python scripts/third_party.py`(`gh api` が不安定。3 回リトライして駄目なら `--use-archived-licenses`)。**既存の `licenses/*.txt` の CRLF だけの差分は `git checkout -- <file>` で戻す。**
4. `JAVA_HOME="C:/Program Files/Java/jdk-21"` で `%LOCALAPPDATA%\Coursier\data\bin\sbt.bat -batch test ui/assembly`。Python は `CUDA_VISIBLE_DEVICES=-1 PYTHONPATH=$PWD/python python/.venv-smoke/Scripts/python.exe -m pytest -q -p no:warnings python/tests`(Python を変えたときだけ全件。変えていなければ `test_third_party*.py` だけでよい)。
5. 負荷が高いと `ModelCardScannerNativeBulkSuite` / `ModelCardCatalogSuite` が時間切れで落ちる。単独で再実行して通れば負荷のせい。
6. 作業ディレクトリ直下に出る `iteration-0-image-0-seed-1.mp4`(pytest が作る)を消してから commit。commit 末尾に `Co-Authored-By` 行。`git push origin main`。issue にコメント(部分実装なら未対応を列挙)。

## 5. v0.07 リリースの手順 (v0.06 と同じ)

1. `main` でフルのテスト。`site/content/{en,ja}/install.md` の ZIP 名を `v0.07` にして push。
2. `sbt ui/assembly` → `pwsh scripts/package_windows.ps1 -JdkHome "C:/Program Files/Java/jdk-21" -Jar target/stablediffusion-nui.jar -Version 0.07 -OutputRoot outputs/release-v007`。
3. **payload 検査**: `python -I scripts/dev/payload_check.py <repo> <zip> <展開先>`。`missing` が 0 であること(v0.06 では CLIP Interrogator のラベルリストが漏れていた。新しい非 `.py` のデータは `scripts/package_windows.ps1` の許可リストに追加する)。
4. **配布版の実モデル確認**(GPU ロックを取る): `scripts/dev/check_mcp_client.py --transport stdio --launcher <展開先>/SDNUI/SDNUI.exe --jar <展開先>/SDNUI/app/stablediffusion-nui.jar --python python/.venv-smoke/Scripts/python.exe --require-extensions --models D:/stable-diffusion-webui-reForge/models/Stable-diffusion --checkpoint animekawa_v10 --side 512 --steps 20 --output outputs/pkg-v007-sd15`。SDXL は `waiIllustriousSDXL_v170 --side 1024`。両方 `success: true`。MCP クライアント用の venv は `C:/Users/Mizushima/.codex/worktrees/affc/stablediffusion-nui/outputs/mcp-client-venv/Scripts/python.exe`。
5. `gh release create v0.07 <zip> --title v0.07 --notes-file <notes> --target main`。ノートは v0.06 のものを手本に、部分実装は「部分」と書く。**再ダウンロードして SHA256 を照合**し、ノートと `site/content/{en,ja}/releases.md` に追記する。

## 6. 環境の注意

- **GPU は 1 枚で共有**。実モデルの実行は `outputs/.gpu.lock`(`mkdir` で取り、`rmdir` で返す)を使い、1 回に 1 つだけ。メモリ(RAM)も足りないので、重い処理は並列にしない。メモリ逼迫で長い処理が止められることがある(再実行する)。
- モデルのフォルダは `D:/stable-diffusion-webui-reForge/models/Stable-diffusion`(ハイフンの位置に注意)。reForge の親ディレクトリは参照専用で、import や起動はしない。
- Hugging Face のログインはこのマシンにあるが、sd-nui では使っていない(別プロジェクト用)。
- 上流のライセンスが無い拡張は、README の挙動だけから再実装する(コードは読まない)。AnimateDiff の上流は CC BY-NC-SA(非商用)だが、コードはコピーしていない。

## 7. ユーザーの好み

- 返信は英語が既定。英語の誤りは、返信の最後に日本語で短く訂正する(自然な英語と理由)。S 式風の命令(`(execute plan)`)や `issues.take(20)…` のようなコード風の指示は意図的に書いているので、訂正せずに命令として実行し、解釈を 1 行で述べる。
- 簡潔に、答えを先に。確認よりも速さ。ただし未検証のものを「完了」と言わない。
