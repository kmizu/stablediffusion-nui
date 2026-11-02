---
title: 画像ブラウザ
nav: 画像ブラウザ
description: Stable Diffusion NUI の「Image Browser」タブで、生成済みの画像を閲覧・ランク付け・お気に入り登録・送信・削除する方法を説明します。
---

**Image Browser** タブは、これまでに生成した画像を一覧にして、画像に埋め込まれた生成設定を表示し、その設定や画像を別のタブへ送ります。A1111 拡張「Image browser」に相当する機能で、タブ構成、並べ替えキー、ランク、お気に入り、キーボードショートカットを同じ形で備えています。
このタブを開いている間は、メインウィンドウ右側のプレビューとギャラリーを隠し、画面の幅いっぱいを使います。

![Image Browser タブ](shots/image-browser.png)

## タブ

| タブ | 表示するもの |
|---|---|
| **txt2img**、**img2img**、**Extras** | 出力フォルダ内の対応するフォルダ(`txt2img`、`img2img`、`extras`)。 |
| **txt2img-grids**、**img2img-grids** | 共通の `grids` フォルダ(SDNUI はグリッドを両モードで 1 つのフォルダに保存します)。 |
| **Favorites** | お気に入りフォルダ(既定は出力フォルダ内の `favorites`)。 |
| **Others** | 入力または参照で選んだ任意のフォルダ。後述。 |
| **All** | 上のフォルダすべてをまとめた一覧。 |
| **Maintenance** | ブラウザ自身のデータの整備。後述。 |

設定の **Active tabs** にフォルダのパスを書くと、そのフォルダ用のタブが増えます。対象は `.png`、`.jpg`、`.jpeg`、`.webp`、`.gif` です。画像に生成情報がない場合は、同じ名前の `.txt` ファイルを生成情報として読みます。動画(`.mp4`、`.webm` など)は名前・サイズ・日時を一覧に表示し、ダブルクリックか **Open video** でシステムの再生ソフトで開きます。SDNUI 自身では動画のフレームを表示できません。

## 閲覧

- **First Page**、**Prev Page**、**Next Page**、**End Page** とページ番号の欄でページを移動します。1 ページの枚数は設定の「列 × 行」です。**⟳** でフォルダを読み直します。
- サムネイルをクリックすると選択され、右側にプレビュー、ファイル名、ファイル日時、サイズと寸法、生成情報が出ます。**Formatted display** にチェックすると設定した順序の Key / Value 表、外すと生のテキストです。サムネイルにマウスを載せると寸法とファイルサイズが出ます。ダブルクリックでメインのプレビューにも表示します。
- **Sort by** は path name、date、aesthetic_score、random、cfg scale、steps、seed、sampler、size、model、model hash、ranking です。矢印ボタンで昇順・降順を切り替えます。値を持たない画像は末尾に並び、除外されません。
- **Filename keyword search** はパスに、**EXIF keyword search** は生成情報に一致させます。**Search negative prompt** は *No*(ネガティブを対象にしない)、*Yes*(含める)、*Only*(ネガティブのみ)から選びます。**regex** にチェックすると正規表現になり、`^(?!.*Hires).*$` のように書けます。不正な式は警告を出して無視します。
- **Ranking filter** は All、1〜5、None、Min-max(最小・最大指定)です。**Minimum score** と **Maximum score** は、Aesthetic Score プラグインが書く `aesthetic_score` で絞り込みます。

## ランクとお気に入り

画像を選び、**Set ranking to** の 1〜5 か None を選ぶか、キーの `1`〜`5`(`0` で解除)を押します。ランクは `config/image-browser.json` に保存され、ブラウザがファイルを移動したときは追従します。**Save ranking in image's pnginfo** を有効にすると、PNG に `ranking` テキストチャンクとしても書き込みます(画素は変わりません)。**Rebuild ranking from exif info** で、そのようなファイルからランクを復元できます。

**Move to favorites**(`F`)は画像と同名の `.txt` をお気に入りフォルダへ移動します。**Move buttons copy instead of move** を有効にすると **Copy to favorites** になり、元の画像は残ります。**Copy to directory** のパネルは任意のフォルダに対して同じ操作をし、保存済みディレクトリから選べます。既存のファイルは上書きせず、名前に番号を付けます。

## 送信

**Send to txt2img** は生成パラメータを復元します。**Send to img2img** と **Send to inpaint** は画像を入力画像にし、パラメータも復元します。**Send to extras** は Extras の入力にします。**Send to txt2img ControlNet** と **Send to img2img ControlNet** は、**ControlNet number** で選んだユニットの画像だけを設定し、ほかの値は変えません。

## 削除

**delete next** *N* は、選択中の画像と、同じページ上のその後ろ *N−1* 枚を削除します。**also delete off-screen images** にチェックすると次のページ以降にも進みます。削除の前に対象のファイル名を示す確認が出ます。ファイルは `.txt` と一緒に OS のごみ箱へ移動します(使えない場合は出力フォルダ内の `.trash`)。設定の **Use recycle bin when deleting images** を外すと完全に削除され、確認にもそう表示されます。キーボードの `Delete` でも同じ確認が出ます。

## Others

フォルダを入力(または **Browse**)して **Load** を押します。**Sub directory depth** は 0 でそのフォルダのみ、*N* で N 階層下まで、−1 で無制限です。**Add to / replace in saved directories** でフォルダと深さを記憶し、**Saved directories** で呼び出し、**Remove from saved directories** で削除します。**Get sub directories** で直下のフォルダを **Sub directories** に読み込み、1 つに絞れます。

## Maintenance

| ボタン | 動作 |
|---|---|
| Rebuild exif cache | すべての画像を読み直してメタデータのキャッシュを作り直します。 |
| Delete 0-entries from exif cache | 生成情報のない画像のキャッシュ項目を削除します。 |
| Update directory names in database | フォルダ名を変えたとき、保存済みディレクトリ・キャッシュ・ランクのパスを書き換えます(対象を選びます)。 |
| Recreate hash for existing files | 移動したファイルを見つけるための内容ハッシュを再計算します。 |
| Reapply ranking after moving files | 消えたファイルのランクを、同じ内容ハッシュのファイルへ付け直します。 |
| Rebuild ranking from exif info | PNG に埋め込まれたランクを取り込みます。 |

どれもバックグラウンドで動き、結果は **Last message** に出ます。画像そのものは変更しません。

## キーボード

| キー | 動作 |
|---|---|
| `0`〜`5` | ランクを設定(0 は None) |
| `F` | お気に入りへ |
| `R` | 読み直し |
| `Delete` | 削除(確認あり) |
| `Ctrl+←` / `Ctrl+→` | 前 / 次のページ |

テキスト欄にフォーカスがないとき、選択中の画像に対して働きます。設定の **Change CTRL keybindings to SHIFT** と **or to CTRL+SHIFT** でページ送りの修飾キーを変えられます。**Swipe left/right navigates to the next image** でタッチのスワイプ操作が有効になります。

## 設定

タブ上部の **Settings…** で、Active tabs、お気に入りフォルダ、サブフォルダを含めるか、起動時に最初のタブを先読みするか、移動の代わりにコピーするか、削除時のコンソール出力、`.txt` の同時処理、ごみ箱、Exif / .txt の読み取り、修飾キー、スワイプ、ランクの pnginfo 保存、列数、行数、サムネイルのサイズと正方形トリミング、ツールチップ、進捗表示、情報の整形表示と順序、追加の生成情報、非表示にする部品(並べ替え、検索、ランク、削除、送信ボタンなど)を設定します。変更はすぐ反映され、`config/image-browser.json` に保存されます。**Scan Exif-/.txt-data** を切ると一覧表示だけになり、並べ替え・検索・情報パネルにはこの読み取りが必要です。

## 含まれないもの

動画の再生とフレーム表示、openOutpaint ボタン、Web UI 固有のデバッグ・JavaScript ログ・ページ読み込みの設定は提供しません(ネイティブウィンドウでは不要です)。
