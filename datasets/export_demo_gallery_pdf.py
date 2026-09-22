#!/usr/bin/env python3
"""Render the curated sign illustrations as a readable A4 PDF."""

from __future__ import annotations

import json
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "demo_help_vocab"
OUTPUT = ROOT.parent / "output" / "pdf" / "手语求助场景图解画廊.pdf"
FONT = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
PAGE_W, PAGE_H = A4
INK = colors.HexColor("#162332")
MUTED = colors.HexColor("#526273")
LINE = colors.HexColor("#D7E0E8")
ACCENT = colors.HexColor("#176A82")


def write(c: canvas.Canvas, x: float, y: float, text: str, size: float = 10, color=INK) -> None:
    c.setFillColor(color)
    c.setFont("ArialUnicode", size)
    c.drawString(x, y, text)


def wrap(text: str, size: float, max_width: float) -> list[str]:
    lines, current = [], ""
    for char in text:
        if char == "\n":
            lines.append(current)
            current = ""
        elif pdfmetrics.stringWidth(current + char, "ArialUnicode", size) > max_width:
            lines.append(current)
            current = char
        else:
            current += char
    if current:
        lines.append(current)
    return lines


def fitted_image(c: canvas.Canvas, path: Path, x: float, y: float, w: float, h: float) -> None:
    reader = ImageReader(str(path))
    iw, ih = reader.getSize()
    scale = min(w / iw, h / ih)
    dw, dh = iw * scale, ih * scale
    c.drawImage(reader, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, mask="auto")


def draw_card(c: canvas.Canvas, record: dict, bottom: float) -> None:
    x, w, h = 42, PAGE_W - 84, 338
    top = bottom + h
    c.setFillColor(colors.white)
    c.setStrokeColor(LINE)
    c.roundRect(x, bottom, w, h, 10, stroke=1, fill=1)
    write(c, x + 17, top - 30, record["label"], 16)
    label_width = pdfmetrics.stringWidth(record["label"], "ArialUnicode", 16)
    write(c, x + 24 + label_width, top - 27, record["tier"], 8.5, ACCENT)
    if any(d["head_or_face_cue"] for d in record["diagrams"]):
        write(c, x + 17, top - 47, "涉及头脸等位置线索，胸前视角需重点实测", 8, MUTED)
    diagrams = record["diagrams"]
    image_left, image_bottom = x + 17, top - 239
    image_width, image_height = w - 34, 184
    if len(diagrams) == 1:
        fitted_image(c, SOURCE / diagrams[0]["file"], image_left, image_bottom, image_width, image_height)
    else:
        slot_width = (image_width - 8) / len(diagrams)
        for index, diagram in enumerate(diagrams):
            fitted_image(c, SOURCE / diagram["file"], image_left + index * (slot_width + 8), image_bottom, slot_width, image_height)
            write(c, image_left + index * (slot_width + 8), image_bottom - 10, f"变体 {index + 1}", 7.5, MUTED)
    c.setStrokeColor(LINE)
    c.line(x + 17, top - 256, x + w - 17, top - 256)
    descriptions = [f"{i + 1}. {d['description']}" if len(diagrams) > 1 else d["description"] for i, d in enumerate(diagrams)]
    lines = wrap("  ".join(descriptions), 8.3, w - 34)
    if len(lines) > 7:
        raise ValueError(f"Description too long for card: {record['label']} ({len(lines)} lines)")
    for index, line in enumerate(lines):
        write(c, x + 17, top - 274 - index * 11, line, 8.3, MUTED)


def footer(c: canvas.Canvas, page_num: int) -> None:
    c.setStrokeColor(LINE)
    c.line(42, 53, PAGE_W - 42, 53)
    write(c, 42, 38, "来源：Chinese Sign Language Dictionary · 词典图解，不是胸前相机训练帧", 7.5, MUTED)
    write(c, PAGE_W - 68, 38, str(page_num), 8, MUTED)


def main() -> None:
    pdfmetrics.registerFont(TTFont("ArialUnicode", FONT))
    data = json.loads((SOURCE / "manifest.json").read_text(encoding="utf-8"))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(OUTPUT), pagesize=A4, pageCompression=1)
    c.setTitle("求助场景手语图解候选词库")
    c.setAuthor("Insta360 GO 3S 手语识别 Demo 资料整理")

    c.setFillColor(INK)
    c.rect(0, PAGE_H - 290, PAGE_W, 290, fill=1, stroke=0)
    write(c, 50, PAGE_H - 114, "求助场景手语图解", 27, colors.white)
    write(c, 50, PAGE_H - 153, "候选词库 · PDF 画廊", 17, colors.white)
    write(c, 50, PAGE_H - 224, "100 个词条  |  116 张图解  |  5 类场景", 11, colors.white)
    write(c, 50, PAGE_H - 341, "基础沟通  /  求助安全  /  问路  /  寻人  /  购物询价", 12)
    notes = [
        "本资料按场景整理已下载词典中的精确词条，便于团队选词和讨论。",
        "其中 48 个标为首批候选；是否能由 GO 3S 胸前机位识别，仍需真人实测。",
        "相同图解可能对应多个词义，同一词也可能有不同手势变体。",
        "图解是教学线稿，不是相机拍摄的训练数据。动作说明来自原词典。",
        "对外发布、训练或商业使用前，请核实原词典图解的使用授权。",
    ]
    for i, line in enumerate(notes):
        write(c, 50, PAGE_H - 402 - i * 29, line, 10, MUTED)
    write(c, 50, 115, "原始资料：github.com/WishingCat/chinese-sign-language-dictionary", 8.5, ACCENT)
    write(c, 50, 93, "生成依据：datasets/demo_help_vocab/manifest.json", 8.5, MUTED)
    footer(c, 1)
    c.showPage()

    page_num = 1
    scenes = list(dict.fromkeys(r["scene"] for r in data["records"]))
    for scene in scenes:
        records = [r for r in data["records"] if r["scene"] == scene]
        for offset in range(0, len(records), 2):
            page_num += 1
            write(c, 42, PAGE_H - 46, scene.split("_", 1)[1], 17)
            write(c, 42, PAGE_H - 66, f"词条 {offset + 1}-{min(offset + 2, len(records))} / {len(records)}", 8.5, MUTED)
            draw_card(c, records[offset], 427)
            if offset + 1 < len(records):
                draw_card(c, records[offset + 1], 78)
            footer(c, page_num)
            c.showPage()
    c.save()
    print(f"{OUTPUT} ({page_num} pages)")


if __name__ == "__main__":
    main()
