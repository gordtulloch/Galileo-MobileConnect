"""Check that Night screenshots contain no light that isn't red (MAPP-LAY-030, risk I5).

    python scripts/check_night.py <folder of .png>       only files with "night" in the name are checked

Needs Pillow. A pixel fails if green or blue exceed the tolerance. The system's own home indicator is masked: a
screenshot's pixels are in portrait even in landscape, so that's the bottom strip or the left one. Exit 1 on any failure.
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

# The system's sidebar glass adds a trace of up to about 15 at its edge: nothing visible, but not exactly zero.
TOLERANCE = 16
HOME_INDICATOR = 45  # pixels from the edge: it sits within 38 of the bottom, or of the left in landscape


def check(path: Path) -> tuple[int, int, tuple[int, int] | None]:
    image = Image.open(path).convert("RGB")
    width, height = image.size
    limit = height - HOME_INDICATOR
    left = HOME_INDICATOR
    bad, first = 0, None
    pixels = image.load()
    for y in range(0, limit):
        for x in range(left, width):
            _, green, blue = pixels[x, y]
            if green > TOLERANCE or blue > TOLERANCE:
                bad += 1
                first = first or (x, y)
    return bad, width * limit, first


def main() -> int:
    files = sorted(p for p in Path(sys.argv[1]).rglob("*.png") if "night" in p.name.lower())
    if not files:
        print("no Night screenshots found")
        return 1
    failed = 0
    for path in files:
        bad, total, first = check(path)
        print(f"{'FAIL' if bad else 'ok  '} {path.name}: {bad} non-red of {total}" + (f", first at {first}" if first else ""))
        failed += bool(bad)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
