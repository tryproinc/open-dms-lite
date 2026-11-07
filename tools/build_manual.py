"""取扱説明書(docs/USER_MANUAL.md)から Word(.docx)と PDF を作る。

    python tools/build_manual.py          # docx と pdf を docs/ に出力
    python tools/build_manual.py --no-pdf # docx だけ
    python tools/build_manual.py --source docs/USER_MANUAL.md --stem Open_DMS_Lite_User_Manual         --title "Open DMS Lite 取扱説明書"

必要なもの: Python 3.9+、python-docx、pandoc、LibreOffice(PDF を作る場合)。

- 画面図は docs/manual/img/ に置いた PNG を、原稿の `![図…](manual/img/xx.png)` で参照する。
  ファイルが無い図は「【図】…(撮影後に差し替え)」の枠で出す(撮影前でも組版できる)。
- 書式は簡素な実務書式(黒文字・見出しは黒・罫線は細い灰色)。見出し段落の
  「次の段落と分離しない」「段落を分割しない」「改ページ」は無効にする
  (Word で見出しの左に黒い段落マークが出るのを避けるため)。
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
COPYRIGHT = "© 2026 tryproinc"
CONTACT = "お問い合わせ・不具合報告: info@trypro.co.jp / 042-306-2924"
COMPANY = "株式会社トライプロ"

FONT = "Yu Gothic"
BODY_PT = 10.5
TABLE_PT = 9.5
FIGURE_MAX_CM = 6.5
PLACEHOLDER_HEIGHT_CM = 5.0

PANDOC_CANDIDATES = [
    shutil.which("pandoc"),
    str(Path.home() / "AppData/Local/Pandoc/pandoc.exe"),
]
SOFFICE_CANDIDATES = [
    shutil.which("soffice"),
    "C:/Program Files/LibreOffice/program/soffice.exe",
]


def find_tool(candidates: list[str | None], name: str) -> str:
    for c in candidates:
        if c and Path(c).exists():
            return c
    sys.exit(f"{name} が見つかりません")


PAGE_BREAK = (
    "```{=openxml}\n"
    '<w:p><w:r><w:br w:type="page"/></w:r></w:p>\n'
    "```"
)


def prepare_markdown(text: str) -> str:
    """原稿を pandoc に渡せる形にする。

    - 存在しない画像は、枠(custom-style の div)に置き換える
    - 文書内リンク(相対パス)は、紙・PDF では辿れないので文字だけにする
    """

    def image(m: re.Match[str]) -> str:
        alt, path = m.group(1), m.group(2)
        if (DOCS / path).exists():
            return f"![{alt}]({path})"
        return f'\n::: {{custom-style="FigurePlaceholder"}}\n【{alt}】(撮影後に差し替え)\n:::\n'

    text = re.sub(r"!\[([^\]]*)\]\(([^)]+)\)", image, text)
    # [表示](相対パス) → 表示。http(s) のリンクは残す。画像(!)は上で処理済み。
    text = re.sub(r"(?<!!)\[([^\]]+)\]\((?!https?://)[^)]*\)", r"\1", text)
    # 末尾のコピーライト行は、Word/PDF ではフッターに入るので本文から外す
    text = re.sub(r"\n---\n\n© 2026 tryproinc[^\n]*\n*$", "\n", text)
    text = text.replace("<!--pagebreak-->", PAGE_BREAK)
    return set_table_widths(text)


def table_widths(header: str) -> list[int] | None:
    """見出し行から、表の列幅の比(%)を決める。pandoc は区切り行の「-」の数で列幅を決める。"""
    cells = [c.strip() for c in header.strip().strip("|").split("|")]
    if len(cells) == 2 and not any(cells):
        return [18, 82]
    if "実装・理由" in cells:
        return [8, 11, 21, 9, 9, 42]
    if cells[-1] == "説明" and "Full" in cells and "Lite" in cells:
        return [8, 24, 9, 9, 50]
    if cells[:2] == ["理由", "関係する項目"]:
        return [34, 26, 20, 20]
    if len(cells) == 3 and "Lite" in cells and "Full" in cells:
        return [56, 22, 22]
    return None


def set_table_widths(text: str) -> str:
    lines = text.split("\n")
    for i in range(1, len(lines)):
        if re.fullmatch(r"\|(-{3,}\|)+", lines[i]) and lines[i - 1].startswith("|"):
            widths = table_widths(lines[i - 1])
            if widths and len(widths) == lines[i].count("|") - 1:
                lines[i] = "|" + "|".join("-" * w for w in widths) + "|"
    return "\n".join(lines)


def set_run_font(run, size_pt: float | None = None, bold: bool | None = None) -> None:
    run.font.name = FONT
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    for attr in ("w:ascii", "w:hAnsi", "w:eastAsia", "w:cs"):
        rfonts.set(qn(attr), FONT)
    run.font.color.rgb = RGBColor(0, 0, 0)
    if size_pt is not None:
        run.font.size = Pt(size_pt)
    if bold is not None:
        run.font.bold = bold


def style_font(style, size_pt: float, bold: bool | None = None) -> None:
    style.font.name = FONT
    rpr = style.element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    for attr in ("w:ascii", "w:hAnsi", "w:eastAsia", "w:cs"):
        rfonts.set(qn(attr), FONT)
    # テーマフォント指定が残っていると上書きされるので外す
    for attr in ("w:asciiTheme", "w:hAnsiTheme", "w:eastAsiaTheme", "w:cstheme"):
        if rfonts.get(qn(attr)) is not None:
            del rfonts.attrib[qn(attr)]
    style.font.size = Pt(size_pt)
    style.font.color.rgb = RGBColor(0, 0, 0)
    if bold is not None:
        style.font.bold = bold
    style.font.italic = False


def clear_heading_flow(paragraph_format) -> None:
    paragraph_format.keep_with_next = False
    paragraph_format.keep_together = False
    paragraph_format.page_break_before = False
    paragraph_format.widow_control = False


def set_cell_border(cell, color: str = "808080", size: int = 4) -> None:
    tc_pr = cell._element.get_or_add_tcPr()
    borders = tc_pr.find(qn("w:tcBorders"))
    if borders is None:
        borders = OxmlElement("w:tcBorders")
        tc_pr.append(borders)
    for edge in ("top", "left", "bottom", "right"):
        el = borders.find(qn(f"w:{edge}"))
        if el is None:
            el = OxmlElement(f"w:{edge}")
            borders.append(el)
        el.set(qn("w:val"), "single")
        el.set(qn("w:sz"), str(size))
        el.set(qn("w:space"), "0")
        el.set(qn("w:color"), color)


def format_table(table) -> None:
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    for ri, row in enumerate(table.rows):
        # 行をページの途中で分割しない
        trpr = row._tr.get_or_add_trPr()
        if trpr.find(qn("w:cantSplit")) is None:
            trpr.append(OxmlElement("w:cantSplit"))
        for cell in row.cells:
            set_cell_border(cell)
            for p in cell.paragraphs:
                p.paragraph_format.space_after = Pt(2)
                p.paragraph_format.space_before = Pt(2)
                p.paragraph_format.keep_with_next = False
                for r in p.runs:
                    set_run_font(r, TABLE_PT, bold=True if ri == 0 else None)
    # 先頭行は見出し行として繰り返す
    first = table.rows[0]._tr.get_or_add_trPr()
    if first.find(qn("w:tblHeader")) is None:
        first.append(OxmlElement("w:tblHeader"))


def placeholder_to_table(doc: Document, paragraph) -> None:
    """「FigurePlaceholder」段落を、1セルの枠(高さ固定)の表に置き換える。"""
    text = paragraph.text
    table = doc.add_table(rows=1, cols=1)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    row = table.rows[0]
    tr_pr = row._tr.get_or_add_trPr()
    height = OxmlElement("w:trHeight")
    height.set(qn("w:val"), str(int(PLACEHOLDER_HEIGHT_CM * 567)))
    height.set(qn("w:hRule"), "atLeast")
    tr_pr.append(height)
    cell = row.cells[0]
    set_cell_border(cell, "808080", 6)
    # 垂直方向の中央
    v_align = OxmlElement("w:vAlign")
    v_align.set(qn("w:val"), "center")
    cell._element.get_or_add_tcPr().append(v_align)
    p = cell.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    set_run_font(run, TABLE_PT)
    paragraph._p.addnext(table._tbl)
    paragraph._p.getparent().remove(paragraph._p)


def add_footer(section, title: str) -> None:
    """全ページの下部に、著作権表示・資料名・問い合わせ先、ページ番号を入れる。"""
    first = section.footer.paragraphs[0]
    first.alignment = WD_ALIGN_PARAGRAPH.CENTER
    set_run_font(first.add_run(f"{COPYRIGHT}  |  {title}  |  {CONTACT}"), 8)
    p = section.footer.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for kind, instr in (("begin", None), (None, " PAGE "), ("end", None)):
        run = p.add_run()
        set_run_font(run, 9)
        if kind:
            fld = OxmlElement("w:fldChar")
            fld.set(qn("w:fldCharType"), kind)
            run._r.append(fld)
        else:
            it = OxmlElement("w:instrText")
            it.set(qn("xml:space"), "preserve")
            it.text = instr
            run._r.append(it)


def add_cover(doc, product: str, doc_type: str, version: str) -> None:
    """表紙。製品名と会社名を入れる(黒文字・簡素な書式)。"""
    first = doc.paragraphs[0]

    def line(text: str, size: float, bold: bool, before: float) -> None:
        p = first.insert_paragraph_before()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.space_before = Pt(before)
        p.paragraph_format.space_after = Pt(0)
        p.paragraph_format.keep_with_next = False
        set_run_font(p.add_run(text), size, bold=bold)

    line("ドライバーモニタリングシステム", 18, False, 110)
    line(product, 40, True, 14)
    line(doc_type, 26, False, 40)
    line(version, 12, False, 70)
    line(COMPANY, 16, True, 150)
    brk = first.insert_paragraph_before()
    brk.add_run().add_break(WD_BREAK.PAGE)


def postprocess(path: Path, title: str, cover: tuple[str, str, str]) -> None:
    doc = Document(str(path))
    add_cover(doc, *cover)

    for section in doc.sections:
        section.page_width, section.page_height = Cm(21.0), Cm(29.7)
        section.left_margin = section.right_margin = Cm(2.0)
        section.top_margin = section.bottom_margin = Cm(2.0)
        add_footer(section, title)

    styles = doc.styles
    for name in ("Normal", "Body Text", "First Paragraph", "Compact", "Block Text"):
        if name in [s.name for s in styles]:
            style_font(styles[name], BODY_PT)
    sizes = {"Title": 20, "Heading 1": 16, "Heading 2": 13, "Heading 3": 11.5}
    for name, size in sizes.items():
        if name in [s.name for s in styles]:
            style_font(styles[name], size, bold=True)
            clear_heading_flow(styles[name].paragraph_format)
    for name in ("Body Text", "First Paragraph", "Compact"):
        if name in [s.name for s in styles]:
            styles[name].paragraph_format.space_after = Pt(4)
            styles[name].paragraph_format.line_spacing = 1.3

    # 段落ごとの直接書式(見出しのフロー設定と、実行文字の色・フォント)
    for p in doc.paragraphs:
        if p.style.name.startswith("Heading") or p.style.name == "Title":
            clear_heading_flow(p.paragraph_format)
            p.paragraph_format.space_before = Pt(14 if p.style.name != "Heading 3" else 8)
            p.paragraph_format.space_after = Pt(6)
        for r in p.runs:
            set_run_font(r)

    # 画像は幅を抑える
    for shape in doc.inline_shapes:
        if shape.width > Cm(FIGURE_MAX_CM):
            ratio = shape.height / shape.width
            shape.width = Cm(FIGURE_MAX_CM)
            shape.height = int(Cm(FIGURE_MAX_CM) * ratio)
    for p in doc.paragraphs:
        if p._p.xpath(".//w:drawing"):
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            p.paragraph_format.keep_with_next = False

    for p in list(doc.paragraphs):
        if p.style.name == "FigurePlaceholder":
            placeholder_to_table(doc, p)

    for table in doc.tables:
        format_table(table)

    # リンクの文字(青)も黒にする。下線は残す
    for link in doc.element.body.iter(qn("w:hyperlink")):
        for r in link.iter(qn("w:r")):
            rpr = r.get_or_add_rPr()
            for old in rpr.findall(qn("w:color")):
                rpr.remove(old)
            color = OxmlElement("w:color")
            color.set(qn("w:val"), "000000")
            rpr.append(color)
            if rpr.find(qn("w:u")) is None:
                u = OxmlElement("w:u")
                u.set(qn("w:val"), "single")
                rpr.append(u)

    doc.core_properties.title = title
    doc.core_properties.author = "tryproinc"
    doc.save(str(path))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-pdf", action="store_true")
    parser.add_argument("--source", default="docs/USER_MANUAL.md", help="原稿(リポジトリルートからの相対パス)")
    parser.add_argument("--stem", default="Open_DMS_Lite_User_Manual", help="出力ファイル名(拡張子なし。docs/ に出力)")
    parser.add_argument("--title", default="Open DMS Lite 取扱説明書", help="フッターと文書のタイトル")
    parser.add_argument("--cover-product", default="Open DMS Lite", help="表紙の製品名")
    parser.add_argument("--cover-doc", default="取扱説明書", help="表紙の文書の種類")
    parser.add_argument("--cover-version", default="版 0.1.0-lite  |  2026年11月7日", help="表紙の版・日付")
    args = parser.parse_args()
    source = ROOT / args.source
    out_docx = DOCS / f"{args.stem}.docx"
    out_pdf = DOCS / f"{args.stem}.pdf"

    pandoc = find_tool(PANDOC_CANDIDATES, "pandoc")
    with tempfile.TemporaryDirectory() as tmp:
        md = Path(tmp) / "manual.md"
        md.write_text(prepare_markdown(source.read_text(encoding="utf-8")), encoding="utf-8")
        subprocess.run(
            [
                pandoc, str(md), "-f", "markdown-smart-auto_identifiers+fenced_divs",
                "-t", "docx", "-o", str(out_docx), f"--resource-path={DOCS}",
            ],
            check=True,
            cwd=DOCS,
        )
    postprocess(out_docx, args.title, (args.cover_product, args.cover_doc, args.cover_version))
    print(f"docx: {out_docx}")

    if not args.no_pdf:
        soffice = find_tool(SOFFICE_CANDIDATES, "LibreOffice(soffice)")
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(
                [soffice, "--headless", "--convert-to", "pdf", "--outdir", tmp, str(out_docx)],
                check=True,
            )
            shutil.copyfile(Path(tmp) / out_docx.with_suffix(".pdf").name, out_pdf)
        print(f"pdf : {out_pdf}")


if __name__ == "__main__":
    main()
