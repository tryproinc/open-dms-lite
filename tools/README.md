# tools

## build_manual.py — 取扱説明書(Word・PDF)の生成

[docs/USER_MANUAL.md](../docs/USER_MANUAL.md) から、Word(`docs/Open_DMS_Lite_User_Manual.docx`)と PDF(`.pdf`)を作ります。

```bash
pip install python-docx
python tools/build_manual.py          # docx と pdf
python tools/build_manual.py --no-pdf # docx だけ
```

必要なもの: Python 3.9 以上、[pandoc](https://pandoc.org/)、LibreOffice(PDF を作る場合)。フォントは Yu Gothic(無ければ代替フォントで表示されます)。

- 書式は簡素な実務書式です(黒文字、見出しも黒、罫線は細い灰色)
- 画面図は `docs/manual/img/` の PNG を原稿の `![図…](manual/img/xx.png)` から参照します。ファイルが無い図は枠で出ます

## anonymize_screenshot.py — 画面図の顔の置き換え

実機の画面を撮ったあと、**顔の映像を「白地 + 人型の絵」に置き換えます**。説明書に顔写真を載せないためのツールです。

```bash
pip install opencv-python numpy pillow
python tools/anonymize_screenshot.py video 元画像.png 出力.png --status-from 黒地の画面.png
python tools/anonymize_screenshot.py alert 警告の画面.png 出力.png --status-from 黒地の画面.png
python tools/anonymize_screenshot.py plain 閾値設定の画面.png 出力.png --status-from 黒地の画面.png
```

- `video`: 映像の上に操作パネルがある画面。アプリが描いた顔の点から人型の絵を描き、点と文字は元の画素を戻します。元の文字が読めないときは `--title "Open DMS Lite  v0.1.0-lite  ·  RESULT"` で版表示を描き直せます
- `alert`: 全画面の警告。地色の単色にし、白い文字だけを戻します
- `plain`: 全面を黒い板が覆う画面(閾値設定・注意事項)。状態バーだけ差し替えます
- 状態バー(時刻・電波)は背景の映像が透けるため、黒地の画面から差し替えます

撮影は release ビルドで行い(debug は診断の数値が出ます)、元の画像は処理後に削除してください(顔が写っています)。

---

© 2026 tryproinc  |  お問い合わせ・不具合報告: info@trypro.co.jp / 042-306-2924
