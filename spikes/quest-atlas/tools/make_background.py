"""Build the all-sky background texture for the Quest star-atlas spike.

Fetches one equirectangular (CAR, ICRS) all-sky image of a CDS HiPS from the CDS
hips2fits service and writes the raw texture the app loads.

Default: CDS/P/Mellinger/color - Axel Mellinger's Milky Way photo-mosaic. It is
smooth and artifact-free. The previous default, CDS/P/DSS2/color, is a patchwork
of photographic plates with a different background level per tile; it is still
available (--hips CDS/P/DSS2/color --flatten) but looks blocky in a headset.

Orientation (verified against Orion, the Pleiades, M31 and the Magellanic
Clouds): the image centre is RA 0h, RA increases to the LEFT, north is up, so the
shader maps u = 0.5 - ra/(2*pi), v = 0.5 - dec/pi.

Output app/src/main/assets/dssbg.bin (file name kept; the app does not care what survey it is):
    char[4]  "GBKG"
    uint16   width, height
    w*h*3    uint8 RGB, row 0 = north

Credit: Mellinger color optical survey, (c) Axel Mellinger, distributed through CDS HiPS.
It is published for non-commercial use with credit; check the terms before shipping it in
anything public. DSS2 is (c) STScI/ROE/AAO/Caltech, free for research and education.

Run with the Galileo venv:  python -I tools/make_background.py [--keep PREVIEW.jpg]
"""

from __future__ import annotations

import argparse
import struct
import urllib.parse
import urllib.request
from io import BytesIO
from pathlib import Path

import numpy as np
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "dssbg.bin"
W, H = 4096, 2048
BASE = "https://alasky.cds.unistra.fr/hips-image-services/hips2fits"


def fetch(hips: str) -> Image.Image:
    q = urllib.parse.urlencode({
        "hips": hips, "width": W, "height": H, "projection": "CAR",
        "coordsys": "icrs", "ra": 0, "dec": 0, "fov": 360, "format": "jpg",
    })
    with urllib.request.urlopen(f"{BASE}?{q}", timeout=300) as r:
        return Image.open(BytesIO(r.read())).convert("RGB")


def flatten(a: np.ndarray, black_pct: float, white_pct: float, gamma: float) -> np.ndarray:
    """Cut a survey's patchy sky-background floor (needed for DSS2 only).

    The floor differs per colour channel (the blue plates sit highest), so the
    black point is taken per channel; the white point is shared to keep colour.
    """
    bp = np.percentile(a.reshape(-1, 3), black_pct, axis=0)
    wp = np.percentile(a.mean(axis=2), white_pct)
    return np.clip((a - bp) / (wp - bp.mean()), 0.0, 1.0) ** gamma


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hips", default="CDS/P/Mellinger/color", help="CDS HiPS id")
    ap.add_argument("--flatten", action="store_true", help="cut the background floor (DSS2)")
    ap.add_argument("--keep", type=Path, help="also save a JPEG preview")
    ap.add_argument("--black", type=float, default=82.0, help="black-point percentile (--flatten)")
    ap.add_argument("--white", type=float, default=99.8, help="white-point percentile (--flatten)")
    ap.add_argument("--gamma", type=float, default=0.8, help="(--flatten)")
    args = ap.parse_args()

    a = np.asarray(fetch(args.hips), dtype=np.float32) / 255.0
    if args.flatten:
        a = flatten(a, args.black, args.white, args.gamma)
    rgb = (a * 255 + 0.5).astype(np.uint8)
    if args.keep:
        Image.fromarray(rgb).resize((2048, 1024)).save(args.keep, quality=90)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("wb") as f:
        f.write(b"GBKG")
        f.write(struct.pack("<HH", W, H))
        f.write(rgb.tobytes())
    print(f"{args.hips}: {W}x{H} -> {OUT} ({OUT.stat().st_size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
