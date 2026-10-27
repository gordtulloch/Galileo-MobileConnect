"""Bake the Messier / Caldwell / NGC deep-sky objects for the Quest star-atlas spike.

Reads the OpenNGC-derived catalogue that Galileo's sky atlas caches
(galileo_dso_catalog_v2.json, built from OpenNGC, CC-BY-SA 4.0) and writes the compact
binary the app loads. Keeps every Messier and Caldwell object plus every NGC object
(IC-only objects, stars and non-existent entries are dropped).

Output app/src/main/assets/deepsky.bin:
    char[4]  "GDSO"
    uint32   count
    count x 32-byte records:
        float32  x, y, z      J2000 unit vector
        float32  mag          V (or B) magnitude, 15 when unknown
        float32  size         major axis, arcminutes
        uint8    type         0 galaxy, 1 open cluster, 2 globular, 3 planetary, 4 nebula/remnant, 5 other
        uint8    cat          bit 0 Messier, bit 1 Caldwell, bit 2 NGC
        char[10] label        "M31", "C14", "NGC 7000", NUL padded

Run with the Galileo venv:  python -I tools/make_dso.py [--catalog PATH]
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import struct
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "deepsky.bin"
DEFAULT_CATALOG = Path(os.environ.get("APPDATA", "")) / "Galileo" / "cache" / "galileo_dso_catalog_v2.json"

TYPES = {"Galaxy": 0, "OpenCluster": 1, "Cluster": 1, "GlobularCluster": 2, "PlanetaryNebula": 3,
         "Nebula": 4, "SupernovaRemnant": 4}
M_RE, C_RE, N_RE = re.compile(r"^M\d+$"), re.compile(r"^C \d+$"), re.compile(r"^NGC \d+$")
SKIP = {"Star", "DoubleStar"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    args = ap.parse_args()

    rows = json.loads(args.catalog.read_text("utf-8"))
    recs = bytearray()
    count = {"M": 0, "C": 0, "NGC": 0}
    n = 0
    for o in rows:
        names = [s for s in [o.get("n"), *o.get("d", [])] if isinstance(s, str)]
        m = next((s for s in names if M_RE.match(s)), None)
        c = next((s for s in names if C_RE.match(s)), None)
        ngc = next((s for s in names if N_RE.match(s)), None)
        if not (m or c or ngc):
            continue
        if o["t"] in SKIP and not (m or c):
            continue
        label = m or c or ngc
        cat = (1 if m else 0) | (2 if c else 0) | (4 if ngc else 0)
        ra, dec = math.radians(o["r"]), math.radians(o["c"])
        mag = o["m"] if o["m"] < 90 else 15.0
        t = TYPES.get(o["t"], 5)
        recs += struct.pack("<3f2fBB10s", math.cos(dec) * math.cos(ra), math.cos(dec) * math.sin(ra),
                            math.sin(dec), mag, float(o.get("s", 0.0)), t, cat, label.encode("ascii")[:9])
        n += 1
        count["M" if m else "C" if c else "NGC"] += 1

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("wb") as f:
        f.write(b"GDSO")
        f.write(struct.pack("<I", n))
        f.write(recs)
    print(f"{n} objects (Messier {count['M']}, Caldwell {count['C']}, NGC-only {count['NGC']}) "
          f"-> {OUT} ({OUT.stat().st_size / 1e3:.0f} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
