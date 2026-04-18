#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["Pillow>=10"]
# ///
"""
Render a TTF pixel font into a Minecraft-format bitmap glyph atlas.

Output:
  - PNG: 16-column × 16-row grid of glyph cells (ASCII 0x00..0xFF)
  - JSON: bitmap font provider definition that MC can consume directly

Usage:
  uv run tools/build_bitmap_font.py <ttf_path> <out_png> <out_json> \
      --cell-w 9 --cell-h 16 --font-size 16 --ascent 13 --char-namespace oc2

The chars[] array spans 16 rows × 16 columns. Position (row, col) maps to
codepoint (row * 16 + col). Codepoints we don't want exposed get a NUL.
"""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


def main():
    p = argparse.ArgumentParser()
    p.add_argument("ttf")
    p.add_argument("out_png")
    p.add_argument("out_json")
    p.add_argument("--cell-w", type=int, default=9)
    p.add_argument("--cell-h", type=int, default=16)
    p.add_argument("--font-size", type=int, default=16,
                   help="TTF render size (should match cell height for pixel fonts)")
    p.add_argument("--ascent", type=int, default=13,
                   help="MC's baseline offset within the cell (top-down)")
    p.add_argument("--char-namespace", default="oc2",
                   help="ResourceLocation namespace for the file ref in JSON")
    p.add_argument("--png-resource-path", default="oc2/terminal_atlas.png",
                   help="Resource path inside assets/<ns>/font/ for the PNG ref")
    args = p.parse_args()

    cw, ch, fs = args.cell_w, args.cell_h, args.font_size
    cols, rows = 16, 16  # standard MC convention

    font = ImageFont.truetype(args.ttf, fs)
    img = Image.new("RGBA", (cols * cw, rows * ch), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    chars_grid: list[str] = []
    for r in range(rows):
        row_chars = []
        for c in range(cols):
            code = r * cols + c
            ch_char = chr(code)
            row_chars.append(ch_char)
            # Skip unprintable / control chars to keep the atlas clean
            if code < 0x20 or code == 0x7F:
                continue
            # Render glyph centered horizontally in cell, baseline-aligned
            x = c * cw
            y = r * ch
            # PIL's text() places top of bounding box at y. Most pixel fonts
            # have built-in cell padding so a simple (x, y) works.
            draw.text((x, y), ch_char, font=font, fill=(255, 255, 255, 255))
        chars_grid.append("".join(row_chars))

    out_png = Path(args.out_png)
    out_png.parent.mkdir(parents=True, exist_ok=True)
    img.save(out_png)
    print(f"wrote {out_png} ({img.size[0]}x{img.size[1]})")

    font_json = {
        "providers": [
            {"type": "reference", "id": "minecraft:include/space"},
            {
                "type": "bitmap",
                "file": f"{args.char_namespace}:{args.png_resource_path}",
                "ascent": args.ascent,
                "height": ch,
                "chars": chars_grid,
            },
        ]
    }
    out_json = Path(args.out_json)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(font_json, indent=2))
    print(f"wrote {out_json}")


if __name__ == "__main__":
    main()
