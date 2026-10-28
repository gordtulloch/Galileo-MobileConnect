"""Bake DSS2 cutouts of the brighter deep-sky objects for the Quest star-atlas spike.

The headset app has no HTTP client or image decoder, so the cutouts are fetched here, from NASA SkyView's DSS2 Red
survey (STScI/POSS-II, research/education use), and stored as raw 8-bit grayscale. Each is a gnomonic (TAN) projection
centred on the object with north up and east to the left, which is what the shader assumes.

Selects every Messier and Caldwell object plus NGC objects brighter than --ngc-mag and larger than --ngc-size arcmin,
reading positions and sizes back out of deepsky.bin (run make_dso.py first).

    app/src/main/assets/dss.bin
        char[4] "GDSS", uint32 count, uint32 side
        count x { char[12] label, float32 fov_deg }       (fov = full width of the square image)
        count x side*side bytes                            (row 0 = north edge, column 0 = east edge)

Downloads are cached in tools/.dsscache so a rerun only fetches what is missing.

Run with the Galileo venv:  python -I tools/make_dss.py
"""

from __future__ import annotations

import argparse
import io
import math
import struct
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "app" / "src" / "main" / "assets"
CACHE = HERE / ".dsscache"
SKYVIEW = "https://skyview.gsfc.nasa.gov/current/cgi/runquery.pl"


def fov_for(size_arcmin: float) -> float:
    """Image width in degrees: the object with room around it, never under 12' (tiny objects) or over 6°."""
    return min(max(size_arcmin * 2.4 / 60.0, 0.2), 6.0)


def read_objects() -> list[tuple[str, float, float, float, float]]:
    raw = (ASSETS / "deepsky.bin").read_bytes()
    n = struct.unpack_from("<I", raw, 4)[0]
    out = []
    for i in range(n):
        x, y, z, mag, size, typ, cat, lab = struct.unpack_from("<3f2fBB10s", raw, 8 + 32 * i)
        ra = math.degrees(math.atan2(y, x)) % 360.0
        dec = math.degrees(math.asin(max(-1.0, min(1.0, z))))
        out.append((lab.split(b"\0")[0].decode(), ra, dec, mag, size) + (cat,))
    return out


def fetch(label: str, ra: float, dec: float, fov: float, side: int) -> np.ndarray | None:
    path = CACHE / (label.replace(" ", "_") + f"_{side}_{fov:.3f}.jpg")
    if not path.exists():
        q = urllib.parse.urlencode({"Survey": "DSS2 Red", "position": f"{ra:.5f},{dec:.5f}", "Size": f"{fov:.4f}",
                                    "Pixels": side, "Projection": "Tan", "Scaling": "Sqrt", "Return": "JPEG"})
        for attempt in range(4):
            try:
                req = urllib.request.Request(f"{SKYVIEW}?{q}", headers={"User-Agent": "galileo-quest-atlas-spike"})
                with urllib.request.urlopen(req, timeout=90) as r:
                    data = r.read()
                Image.open(io.BytesIO(data)).verify()
                path.write_bytes(data)
                break
            except Exception as e:  # noqa: BLE001 - network flakiness; retry then give up on this object
                if attempt == 3:
                    print(f"  {label}: {e}")
                    return None
                time.sleep(2 + 3 * attempt)
    im = Image.open(path).convert("L")
    if im.size != (side, side):
        im = im.resize((side, side), Image.LANCZOS)
    return np.asarray(im, dtype=np.uint8)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--side", type=int, default=256)
    ap.add_argument("--ngc-mag", type=float, default=10.5)
    ap.add_argument("--ngc-size", type=float, default=3.0)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--limit", type=int, default=0, help="only the first N (testing)")
    args = ap.parse_args()
    CACHE.mkdir(exist_ok=True)

    chosen = [o for o in read_objects() if (o[5] & 3) or (o[3] <= args.ngc_mag and o[4] >= args.ngc_size)]
    if args.limit:
        chosen = chosen[: args.limit]
    print(f"{len(chosen)} objects, {args.side}px each")

    def work(o):
        label, ra, dec, _mag, size, _cat = o
        fov = fov_for(size)
        return label, fov, fetch(label, ra, dec, fov, args.side)

    with ThreadPoolExecutor(args.threads) as ex:
        results = list(ex.map(work, chosen))
    ok = [r for r in results if r[2] is not None]

    out = bytearray(b"GDSS" + struct.pack("<II", len(ok), args.side))
    for label, fov, _ in ok:
        out += struct.pack("<12sf", label.encode("ascii"), fov)
    for _, _, px in ok:
        out += px.tobytes()
    (ASSETS / "dss.bin").write_bytes(bytes(out))
    print(f"dss.bin: {len(ok)} images ({len(chosen) - len(ok)} failed), {len(out) / 1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
