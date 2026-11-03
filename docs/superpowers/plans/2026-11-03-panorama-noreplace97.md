# #97 Panorama PNG の非上書き公開

- 任意の `uniqueTarget` は候補名を返すだけ。生成は同じディレクトリの staging file に行い、hard link 作成で完成済み bytes を非上書き公開する。候補名を別 process が先に作った場合は失敗して既存 bytes を保持する。
- hard link 不可の filesystem では `CREATE_NEW` で宛先を排他的に作り、staging bytes をコピーする。copy / close の失敗時は未完成ファイルが残る可能性を明示し、第三者がすり替えたファイルを誤って削除しないため宛先を無条件削除しない。この fallback は可視性と crash 時の完全性が atomic ではない。
- conversion と saveView の名前付き出力に適用する。session/source 変更後の callback が採用しない完成ファイルについて、宛先が外部に差替えられた可能性を考え無条件削除せず、ファイルが残る旨を通知する。保護済み画像は元の保護セッションの暗号を維持する。
- `createTempFile` を使用する送信・合成の内部 path と、ディレクトリ置換・staging path の敵対的変更は別途監査する。path 所有権の完全な保証と crash durability は今回の範囲ではない。
- 別 process に候補名を先に作らせる競合、hardlink 不可、既存名、部分書込／close 失敗、保護 PNG、staging cleanup を unit test する。画面はユーザーの操作検知後のため触れず、GUI 回帰は未実行として残す。
