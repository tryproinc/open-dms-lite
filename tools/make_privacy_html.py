"""docs/store/PRIVACY_POLICY.md から、次の2つを作る。

- Web に置ける単体の HTML(docs/store/privacy_policy.html)。Play Console には「プライバシーポリシーの URL」が必須。
  この HTML を、当社サイトなど誰でも見られる場所に置き、その URL を入力する
- アプリに入れる文章(android/app/src/main/assets/privacy_policy.txt)。「このアプリについて」で全文を出す
  (Google Play はアプリ内でもポリシーを示すことを求める。アプリは通信しないので、本文を入れる)

原稿を直したら必ず実行する。実行: python tools/make_privacy_html.py   (markdown パッケージが必要)
"""
import re
from pathlib import Path

import markdown

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "store" / "PRIVACY_POLICY.md"
DST = ROOT / "docs" / "store" / "privacy_policy.html"
APP_DST = ROOT / "android" / "app" / "src" / "main" / "assets" / "privacy_policy.txt"

CSS = """
body{font-family:"Yu Gothic","Hiragino Sans",Meiryo,sans-serif;line-height:1.8;max-width:760px;margin:0 auto;padding:24px 16px;color:#1a1a1a;background:#fff}
h1{font-size:1.6em;border-bottom:3px solid #0a5c44;padding-bottom:8px}
h2{font-size:1.2em;margin-top:2em;color:#0a5c44}
table{border-collapse:collapse;width:100%;font-size:.95em}
th,td{border:1px solid #bbb;padding:6px 10px;text-align:left;vertical-align:top}
th{background:#eef5f1}
a{color:#0a5c44}
@media (prefers-color-scheme:dark){body{background:#101814;color:#e6efe9}th{background:#1c2a23}th,td{border-color:#445}h1,h2,a{color:#8be0b8}}
"""


def to_app_text(md: str) -> str:
    """アプリで読む平文。見出しは「■」、箇条書きは「・」、表は行ごとに「【1列目】」と「見出し: 値」にする。"""
    md = re.sub(r"\(公開前に[^)]*\)", "", md)  # 原稿の作業メモはアプリに出さない
    md = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", md)
    md = md.replace("**", "")
    out, header = [], None
    for line in md.splitlines():
        if line.startswith("#"):
            out.append("■ " + line.lstrip("#").strip())
        elif line.startswith("|"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            if set("".join(cells)) <= set("-: "):
                continue
            if header is None:
                header = cells
                continue
            out.append(f"【{cells[0]}】")
            out += [f"{h}: {c}" for h, c in zip(header[1:], cells[1:]) if c and c != "—"]
            out.append("")
        else:
            header = None
            out.append("・" + line[2:] if line.startswith("- ") else line)
    return re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip() + "\n"


def main():
    md = SRC.read_text(encoding="utf-8")
    APP_DST.write_text(to_app_text(md), encoding="utf-8")
    print("wrote", APP_DST)
    # 免責事項へのリンクは、単体の HTML では辿れないので文字だけにする
    md = md.replace("[免責事項](../../DISCLAIMER.md)", "免責事項")
    title = md.splitlines()[0].lstrip("#").strip()  # Lite と Full で同じスクリプトを使うため、原稿の見出しから取る
    body = markdown.markdown(md, extensions=["tables"])
    html = (
        '<!doctype html>\n<html lang="ja">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width,initial-scale=1">\n'
        f"<title>{title}</title>\n"
        f"<style>{CSS}</style>\n</head>\n<body>\n{body}\n</body>\n</html>\n"
    )
    DST.write_text(html, encoding="utf-8")
    print("wrote", DST)


if __name__ == "__main__":
    main()
