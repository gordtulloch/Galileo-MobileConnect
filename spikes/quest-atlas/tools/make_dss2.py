"""Bake DSS2 cutouts of every catalogue object for the Quest star-atlas spike, as JPEGs at a resolution that fits the object.

Fetches NASA SkyView's DSS2 Red survey (STScI/POSS-II, research/education use): a gnomonic (TAN) cutout centred on each
object, north up and east left, which is what the shader assumes. Each object gets a field of 2.4x its size (0.2 to 6
degrees) and a resolution of about 800 pixels per degree, rounded up to a power of two between 256 and 2048: the zoom stops
at 40x and the Quest 2 shows ~20 pixels per degree, so 800 px/deg (4.5"/px) is the most the screen can ever use. Small
objects therefore need only 256 px; the big ones (M31, the Orion Nebula...) get up to 2048.

    app/src/main/assets/dss2/<key>.jpg      the coarse picture (256 px) -- the whole picture when 256 px is all there is
    app/src/main/assets/dss2/<key>_hi.jpg   the fine picture, only where more than 256 px are needed; the app shows the
                                            coarse one at once and swaps this in when it has decoded
    app/src/main/assets/dss2.idx            "label <TAB> field in degrees <TAB> 1 if there is a _hi picture"

<key> is the label with every non-alphanumeric character as "_". Pictures are 8-bit grayscale JPEG (quality 72), which the
app's NDK AImageDecoder decodes straight to an 8-bit texture. Downloads are cached in tools/.dsscache.

Needs deepsky.bin (make_dso.py). Run with the Galileo venv:  python -I tools/make_dss2.py [--threads 6]
"""

from __future__ import annotations

import argparse
import io
import math
import re
import struct
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "app" / "src" / "main" / "assets"
OUT = ASSETS / "dss2"
CACHE = HERE / ".dsscache"
SKYVIEW = "https://skyview.gsfc.nasa.gov/current/cgi/runquery.pl"
QUALITY = 72


def key(label: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "_", label)


def fov_for(size_arcmin: float) -> float:
    return min(max(size_arcmin * 2.4 / 60.0, 0.2), 6.0)


def side_for(fov: float) -> int:
    return min(max(2 ** math.ceil(math.log2(max(fov * 800.0, 1.0))), 256), 2048)


def read_objects() -> list[tuple[str, float, float, float]]:
    raw = (ASSETS / "deepsky.bin").read_bytes()
    n = struct.unpack_from("<I", raw, 4)[0]
    out = []
    for i in range(n):
        x, y, z, _mag, size, _t, _c, lab = struct.unpack_from("<3f2fBB10s", raw, 8 + 32 * i)
        out.append((lab.split(b"\0")[0].decode(), math.degrees(math.atan2(y, x)) % 360.0,
                    math.degrees(math.asin(max(-1.0, min(1.0, z)))), size))
    return out


def fetch(label: str, ra: float, dec: float, fov: float, side: int) -> Image.Image | None:
    path = CACHE / (label.replace(" ", "_") + f"_{side}_{fov:.3f}.jpg")
    if not path.exists():
        q = urllib.parse.urlencode({"Survey": "DSS2 Red", "position": f"{ra:.5f},{dec:.5f}", "Size": f"{fov:.4f}",
                                    "Pixels": side, "Projection": "Tan", "Scaling": "Sqrt", "Return": "JPEG"})
        for attempt in range(5):
            try:
                req = urllib.request.Request(f"{SKYVIEW}?{q}", headers={"User-Agent": "galileo-quest-atlas-spike"})
                with urllib.request.urlopen(req, timeout=240) as r:
                    data = r.read()
                Image.open(io.BytesIO(data)).verify()
                path.write_bytes(data)
                break
            except Exception as e:  # noqa: BLE001 - network flakiness; retry, then give up on this object
                if attempt == 4:
                    print(f"  {label}: {e}", flush=True)
                    return None
                time.sleep(3 + 4 * attempt)
    im = Image.open(path).convert("L")
    return im if im.size == (side, side) else im.resize((side, side), Image.LANCZOS)


def save(im: Image.Image, path: Path) -> None:
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=QUALITY, optimize=True)
    path.write_bytes(buf.getvalue())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--limit", type=int, default=0, help="only the first N (testing)")
    args = ap.parse_args()
    CACHE.mkdir(exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)

    objs = read_objects()
    if args.limit:
        objs = objs[: args.limit]
    print(f"{len(objs)} objects", flush=True)
    done = [0]

    def work(o):
        label, ra, dec, size = o
        fov = fov_for(size)
        side = side_for(fov)
        k = key(label)
        coarse, fine = OUT / f"{k}.jpg", OUT / f"{k}_hi.jpg"
        if coarse.exists() and (side == 256 or fine.exists()):
            return label, fov, side > 256
        im = fetch(label, ra, dec, fov, side)
        if im is None:
            return None
        save(im.resize((256, 256), Image.LANCZOS) if side > 256 else im, coarse)
        if side > 256:
            save(im, fine)
        done[0] += 1
        if done[0] % 250 == 0:
            print(f"  {done[0]} fetched", flush=True)
        return label, fov, side > 256

    with ThreadPoolExecutor(args.threads) as ex:
        results = [r for r in ex.map(work, objs) if r]
    (ASSETS / "dss2.idx").write_text("".join(f"{l}\t{f:.4f}\t{int(h)}\n" for l, f, h in results), "ascii", newline="\n")
    total = sum(p.stat().st_size for p in OUT.glob("*.jpg"))
    print(f"dss2: {len(results)}/{len(objs)} objects, {total / 1e6:.0f} MB in {len(list(OUT.glob('*.jpg')))} files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
