"""Build app/src/main/assets/stars.bin for the Quest star-atlas spike.

Source: Hipparcos main catalogue (VizieR I/239/hip_main), ~118,000 stars, real
positions and magnitudes - the "100,000 stars on a Quest 2" case from
docs/analysis/QuestVR.md section 5.

Binary layout (little-endian), sorted brightest first so the app can draw a
prefix of the buffer for a magnitude cut with no re-upload:

    char[4]  magic "GSTR"
    uint32   count
    count x  float32 x, y, z   unit vector, J2000 equatorial (x->RA 0h, z->NCP)
             float32 vmag
             float32 bv        B-V colour index (0 where unknown)

Run with the Galileo venv:  python -I tools/make_stars.py
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

import numpy as np
from astroquery.vizier import Vizier

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "stars.bin"


def main() -> int:
    v = Vizier(columns=["RAICRS", "DEICRS", "Vmag", "B-V"], row_limit=-1)
    tables = v.get_catalogs("I/239/hip_main")
    if not tables:
        print("VizieR returned no table", file=sys.stderr)
        return 1
    t = tables[0]
    ra = np.radians(np.asarray(t["RAICRS"].filled(np.nan), dtype=np.float64))
    dec = np.radians(np.asarray(t["DEICRS"].filled(np.nan), dtype=np.float64))
    vmag = np.asarray(t["Vmag"].filled(np.nan), dtype=np.float64)
    bv = np.asarray(t["B-V"].filled(0.0), dtype=np.float64)

    ok = np.isfinite(ra) & np.isfinite(dec) & np.isfinite(vmag)
    ra, dec, vmag, bv = ra[ok], dec[ok], vmag[ok], np.nan_to_num(bv[ok])
    order = np.argsort(vmag, kind="stable")
    ra, dec, vmag, bv = ra[order], dec[order], vmag[order], bv[order]

    rec = np.empty((len(ra), 5), dtype="<f4")
    rec[:, 0] = np.cos(dec) * np.cos(ra)
    rec[:, 1] = np.cos(dec) * np.sin(ra)
    rec[:, 2] = np.sin(dec)
    rec[:, 3] = vmag
    rec[:, 4] = bv

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("wb") as f:
        f.write(b"GSTR")
        f.write(struct.pack("<I", len(rec)))
        f.write(rec.tobytes())
    print(f"{len(rec)} stars, V {vmag[0]:.2f}..{vmag[-1]:.2f} -> {OUT} ({OUT.stat().st_size/1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
