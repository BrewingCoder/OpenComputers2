#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["Pillow>=10"]
# ///
"""Convert a BDF bitmap font to a Minecraft-format glyph atlas PNG.

BDF is the X11 bitmap font format — explicit pixel data per glyph, no
rasterization step needed. We just read the bits and stamp them into the
right cell of our atlas.

Usage:
  uv run tools/bdf_to_atlas.py <bdf> <out_png> --cell-w 5 --cell-h 8
"""
import argparse
from pathlib import Path
from PIL import Image


def parse_bdf(path: Path):
    """Yield (codepoint, bbx_w, bbx_h, bbx_offx, bbx_offy, rows_hex)."""
    text = path.read_text(errors="replace").splitlines()
    i = 0
    while i < len(text):
        line = text[i].strip()
        if line.startswith("STARTCHAR"):
            cp = None; bbx = None; rows = []
            while i < len(text):
                line = text[i].strip()
                if line.startswith("ENCODING"):
                    cp = int(line.split()[1])
                elif line.startswith("BBX"):
                    parts = line.split()
                    bbx = (int(parts[1]), int(parts[2]), int(parts[3]), int(parts[4]))
                elif line == "BITMAP":
                    i += 1
                    while i < len(text) and text[i].strip() != "ENDCHAR":
                        rows.append(text[i].strip())
                        i += 1
                    # i now points at ENDCHAR — fall through without extra increment
                    continue
                elif line == "ENDCHAR":
                    if cp is not None and bbx is not None:
                        yield (cp, bbx, rows)
                    break
                i += 1
        i += 1


def main():
    p = argparse.ArgumentParser()
    p.add_argument("bdf")
    p.add_argument("out_png")
    p.add_argument("--cell-w", type=int, required=True)
    p.add_argument("--cell-h", type=int, required=True)
    args = p.parse_args()

    cw, ch = args.cell_w, args.cell_h
    cols = rows_grid = 16
    img = Image.new("RGBA", (cols * cw, rows_grid * ch), (0, 0, 0, 0))
    pixels = img.load()

    count = 0
    for cp, bbx, hex_rows in parse_bdf(Path(args.bdf)):
        if cp >= cols * rows_grid:  # 256 max
            continue
        bbx_w, bbx_h, bbx_offx, bbx_offy = bbx
        # cell origin (top-left of the cell in atlas coords)
        cell_col = cp % cols
        cell_row = cp // cols
        cell_x = cell_col * cw
        cell_y = cell_row * ch

        # Each hex row represents bbx_w bits, padded out to whole bytes.
        # Top of the bitmap is the FIRST hex row.
        # In MC ascent semantics, we want the glyph's baseline aligned to
        # cell_y + (cell_h - 1) (or thereabouts). For simplicity we offset
        # by bbx_offy from the bottom: glyph_top_in_cell = cell_h - bbx_h - bbx_offy - 1
        # For Spleen (FONTBOUNDINGBOX 5 8 0 -1), bbx_offy is typically -1.
        # Use cell-h based positioning: the first row of bitmap starts at
        # cell_y + (cell_h - bbx_h - (-bbx_offy))
        bytes_per_row = (bbx_w + 7) // 8
        for row_idx, hex_str in enumerate(hex_rows):
            try:
                row_int = int(hex_str, 16)
            except ValueError:
                continue
            # Pad to bytes_per_row*8 bits
            row_bits = bin(row_int)[2:].zfill(bytes_per_row * 8)
            for bit_idx in range(bbx_w):
                if row_bits[bit_idx] == "1":
                    px = cell_x + bbx_offx + bit_idx
                    py = cell_y + (ch - bbx_h - bbx_offy - 1) + row_idx
                    if 0 <= px < img.width and 0 <= py < img.height:
                        pixels[px, py] = (255, 255, 255, 255)
        count += 1

    out = Path(args.out_png)
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print(f"wrote {out} ({img.size[0]}x{img.size[1]}) — {count} glyphs")


if __name__ == "__main__":
    main()
