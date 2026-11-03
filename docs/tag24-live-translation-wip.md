# #24 タグ翻訳プレビュー（部分対応）

固定 revision `4170882` の [上流 README](https://github.com/DominikDoom/a1111-sd-webui-tagcomplete/blob/4170882f90b47be130a0ff9314f663c230b9153d/README.md#live-preview) と MIT licence を参照し、コードや翻訳データをコピーせず独立実装した。ローカル翻訳 CSV の一致箇所を prompt／negative 欄の下に表示し、クリックで元の範囲を選択する。既定オフで、文字入力とプログラムからの更新に追従。最大3語の長い一致を優先し、16,384文字と100件で打ち切る。制限時は表示する。

これは部分対応。ハイフンや括弧を含むタグは途中の単語だけ一致する場合があり、LoRA・wildcard など構文の意味を解析しない。表示上の訳とタグはそれぞれ 120 Unicode code point で切り詰める。物理 IME と配布 EXE は未検証。辞書と表示テキストは利用者が用意する。

未実装 class で RED、実装後 focused 5/5、source-class 実 MainWindow fake worker で両欄・クリック・設定オン／オフ・読込失敗後の stale 表示消去と復帰が成功。`outputs/tag24-translation-gui/translation-preview.png` を目視した。実 MainWindow・実 engine のタグ選択と生成で SD1.5 と SDXL が各42チェック成功。青い髪の日本語表示と元文字選択をスクショで確認し、両画像を目視した。SD1.5 `animekawa_v10` 512×512、SDXL `waiIllustriousSDXL_v170` 832×1216、Euler a、8 steps、CFG 6、seed 4242、prompt `masterpiece, blue hair, (best quality, highres), a red fox`、negative `blurry`。画像 SHA-256 は SD1.5 `bd1571cf414f2d3230aeeeeac547404fe4d2630d6592c9c13d4446e21e0afe8d`、SDXL `81e3d863e21a7c1f7b809d0c02aff66835780c546dd6130615d0be070e8d4c61`。

SDXL 画像には強い網目状の模様があり、画質上の原因は未特定。同じ SDXL の prompt・seed・サイズを保って preview オン／オフで生成したところ、8 steps と 20 steps の両方で pixel は完全一致した（`outputs/tag24-translation-compare-sdxl/tac-extras-13993927557683309825`）。20 steps にも同様の模様が残った。これは当該条件で preview の切替が pixel を変えなかったという確認であり、網目の原因の特定ではない。

追加確認: 旧設定からの読み込みでは preview がオフになり、有効設定は JSON 往復で維持された。翻訳 CSV parser に 300,000 行と 100万文字の訳を入力した試験では、この環境で読み込み約300ms、短い prompt の辞書 lookup は1ms未満。これは単一実行の計測であり、上限の600,000行や他環境の性能保証ではない。実 MainWindow fake worker で100万文字の訳を120 code pointに省略し、元タグをクリックで選択できた。focused suite と既存 completion suite 計21件が成功した。
