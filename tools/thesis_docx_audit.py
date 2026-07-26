#!/usr/bin/env python3
"""Structural/text audit for an existing thesis DOCX.

The script is intentionally read-only. It reports paragraph order, style,
field codes, bookmark targets, and visible Word field errors without loading
and re-saving the document through a high-level editor.
"""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from pathlib import Path

from lxml import etree


W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NS = {"w": W}
QN = lambda name: f"{{{W}}}{name}"


def paragraph_text(p: etree._Element) -> str:
    return "".join(p.xpath(".//w:t/text()", namespaces=NS))


def paragraph_style(p: etree._Element) -> str:
    values = p.xpath("./w:pPr/w:pStyle/@w:val", namespaces=NS)
    return values[0] if values else ""


def paragraph_fields(p: etree._Element) -> list[str]:
    return [
        re.sub(r"\s+", " ", value).strip()
        for value in p.xpath(".//w:instrText/text()", namespaces=NS)
    ]


def load_styles(zf: zipfile.ZipFile) -> dict[str, str]:
    root = etree.fromstring(zf.read("word/styles.xml"))
    styles: dict[str, str] = {}
    for style in root.xpath("./w:style", namespaces=NS):
        style_id = style.get(QN("styleId"), "")
        names = style.xpath("./w:name/@w:val", namespaces=NS)
        styles[style_id] = names[0] if names else style_id
    return styles


def audit(docx: Path) -> dict:
    with zipfile.ZipFile(docx) as zf:
        root = etree.fromstring(zf.read("word/document.xml"))
        styles = load_styles(zf)

    body = root.find(QN("body"))
    paragraphs = []
    tables = []
    p_index = 0
    table_index = 0
    visible_errors = []
    bookmarks = set(root.xpath(".//w:bookmarkStart/@w:name", namespaces=NS))

    for child in body:
        if child.tag == QN("p"):
            text = paragraph_text(child)
            style_id = paragraph_style(child)
            fields = paragraph_fields(child)
            item = {
                "index": p_index,
                "style_id": style_id,
                "style_name": styles.get(style_id, style_id),
                "text": text,
                "fields": fields,
                "has_drawing": bool(
                    child.xpath(".//w:drawing|.//w:pict", namespaces=NS)
                ),
                "bookmarks": child.xpath(
                    ".//w:bookmarkStart/@w:name", namespaces=NS
                ),
            }
            paragraphs.append(item)
            if re.search(
                r"(Error! Reference source not found|錯誤[！!:：].*參照來源)",
                text,
                re.I,
            ):
                visible_errors.append(item)
            p_index += 1
        elif child.tag == QN("tbl"):
            rows = []
            for tr in child.xpath("./w:tr", namespaces=NS):
                cells = []
                for tc in tr.xpath("./w:tc", namespaces=NS):
                    cells.append(
                        "\n".join(
                            paragraph_text(p)
                            for p in tc.xpath("./w:p", namespaces=NS)
                        )
                    )
                rows.append(cells)
            tables.append({"index": table_index, "rows": rows})
            table_index += 1

    return {
        "docx": str(docx),
        "paragraph_count": len(paragraphs),
        "table_count": len(tables),
        "bookmark_count": len(bookmarks),
        "bookmarks": sorted(bookmarks),
        "visible_reference_errors": visible_errors,
        "paragraphs": paragraphs,
        "tables": tables,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("docx", type=Path)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--text", type=Path)
    args = parser.parse_args()

    report = audit(args.docx)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    if args.text:
        args.text.parent.mkdir(parents=True, exist_ok=True)
        lines = []
        for p in report["paragraphs"]:
            fields = " | ".join(p["fields"])
            lines.append(
                f"P{p['index']:04d}\t{p['style_id']}\t{p['style_name']}"
                f"\tFIELDS={fields}\t{p['text']}"
            )
        for table in report["tables"]:
            lines.append(f"\n[TABLE {table['index']}]")
            for row in table["rows"]:
                lines.append("\t".join(row))
        args.text.write_text("\n".join(lines), encoding="utf-8")

    summary = {
        key: report[key]
        for key in (
            "docx",
            "paragraph_count",
            "table_count",
            "bookmark_count",
        )
    }
    summary["visible_reference_error_count"] = len(
        report["visible_reference_errors"]
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
