"""Bake a glyph atlas for the Quest star-atlas spike (constellation labels).

The app is native with no Java text stack, so labels are drawn from a
pre-rasterised atlas. Default font is Arial from the build machine.

Licensing: Arial is Monotype's font, licensed to Microsoft. Baking glyph
bitmaps from it is fine for a personal sideloaded test build, but do not
redistribute the resulting fontarial.bin. Pass --font with a metric-compatible
open font (Liberation Sans / Arimo, SIL OFL) for anything that ships.

Output app/src/main/assets/fontarial.bin (little-endian):
    char[4]  "GFNT"
    uint16   atlasW, atlasH, glyphCount, pixelSize
    glyphCount x { uint32 code; float u0,v0,u1,v1;          // atlas rect, v0 = top
                   float x0,y0,x1,y1;                        // quad in font px, y up, baseline at 0
                   float advance }
    atlasW*atlasH uint8 coverage

Run with the Galileo venv:  python -I tools/make_font.py [--font PATH.ttf]
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "fontarial.bin"
SIZE = 56          # raster size in px; the app scales it down, mipmaps keep it clean
PAD = 4            # transparent border so linear/mip filtering never bleeds a neighbour
COLS = 16
CELL = 72


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", default=r"C:\Windows\Fonts\arial.ttf")
    args = ap.parse_args()

    font = ImageFont.truetype(args.font, SIZE)
    codes = list(range(32, 127))
    rows = (len(codes) + COLS - 1) // COLS
    atlas = Image.new("L", (COLS * CELL, rows * CELL), 0)
    draw = ImageDraw.Draw(atlas)

    entries = []
    for i, code in enumerate(codes):
        ch = chr(code)
        l, t, r, b = font.getbbox(ch, anchor="ls")   # relative to baseline-left, y down
        adv = font.getlength(ch)
        cx, cy = (i % COLS) * CELL, (i // COLS) * CELL
        w, h = r - l, b - t
        assert w + 2 * PAD <= CELL and h + 2 * PAD <= CELL, (ch, w, h)
        if ch != " ":
            draw.text((cx + PAD - l, cy + PAD - t), ch, font=font, fill=255, anchor="ls")
        entries.append((
            code,
            cx / atlas.width, cy / atlas.height,
            (cx + w + 2 * PAD) / atlas.width, (cy + h + 2 * PAD) / atlas.height,
            l - PAD, -(b + PAD), r + PAD, -(t - PAD),   # y up
            adv,
        ))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("wb") as f:
        f.write(b"GFNT")
        f.write(struct.pack("<HHHH", atlas.width, atlas.height, len(entries), SIZE))
        for e in entries:
            f.write(struct.pack("<I4f4ff", *e))
        f.write(atlas.tobytes())
    print(f"{len(entries)} glyphs, atlas {atlas.width}x{atlas.height} from {Path(args.font).name} -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
