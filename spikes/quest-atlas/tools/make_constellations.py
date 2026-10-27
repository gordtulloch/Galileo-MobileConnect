"""Build constellation assets for the Quest star-atlas spike.

Sources
  * KStars data/clines.dat  - Western constellation stick figures, endpoints are
    Henry Draper (HD) numbers ("M" = move to, "D" = draw to).  GPL-2.
  * KStars data/cnames.dat  - constellation label centres and names.
  * VizieR I/239/hip_main   - Hipparcos positions with an HD cross-reference,
    used to turn each HD endpoint into a J2000 unit vector.

Outputs (app/src/main/assets/)
  constlines.bin   "GCLN", uint32 nseg, nseg x 6 float32 (x0 y0 z0 x1 y1 z1)
  constnames.txt   one line per constellation: "x y z Abbr" (unit vector, centre)

Segments with an endpoint that has no Hipparcos match are dropped and counted.

Run with the Galileo venv:  python -I tools/make_constellations.py [kstars_data_dir]
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

import numpy as np
from astroquery.vizier import Vizier

ASSETS = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets"
DEFAULT_KSTARS = Path(r"C:\Projects\kstars-master\kstars\data")


def unit(ra_deg: float, dec_deg: float) -> np.ndarray:
    ra, dec = np.radians(ra_deg), np.radians(dec_deg)
    return np.array([np.cos(dec) * np.cos(ra), np.cos(dec) * np.sin(ra), np.sin(dec)], dtype="<f4")


def hd_positions() -> dict[int, np.ndarray]:
    v = Vizier(columns=["HD", "RAICRS", "DEICRS", "Vmag"], row_limit=-1)
    t = v.get_catalogs("I/239/hip_main")[0]
    out: dict[int, tuple[float, np.ndarray]] = {}
    for row in t:
        if row["HD"] is None or np.ma.is_masked(row["HD"]):
            continue
        if np.ma.is_masked(row["RAICRS"]) or np.ma.is_masked(row["DEICRS"]):
            continue
        hd = int(row["HD"])
        vm = float(row["Vmag"]) if not np.ma.is_masked(row["Vmag"]) else 99.0
        # a few HD numbers cover close doubles; keep the brighter component
        if hd not in out or vm < out[hd][0]:
            out[hd] = (vm, unit(float(row["RAICRS"]), float(row["DEICRS"])))
    return {k: v[1] for k, v in out.items()}


def western(path: Path) -> list[str]:
    """Lines of the first sky-culture block only ("C Western"); the files hold a dozen cultures."""
    out: list[str] = []
    seen = False
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if raw.startswith("C "):
            if seen:
                break
            seen = True
            continue
        out.append(raw)
    return out


def parse_lines(path: Path, pos: dict[int, np.ndarray]) -> tuple[list[np.ndarray], int]:
    segs: list[np.ndarray] = []
    missing = 0
    prev: np.ndarray | None = None
    for raw in western(path):
        if not raw or raw[0] == "#":
            continue
        op, _, num = raw.partition(" ")
        p = pos.get(int(num)) if num.strip().isdigit() else None
        if op == "M":
            prev = p
        elif op == "D":
            if p is None or prev is None:
                missing += 1
            else:
                segs.append(np.concatenate([prev, p]))
            prev = p
    return segs, missing


def parse_names(path: Path) -> list[str]:
    out = []
    for raw in western(path):
        # "012000+410000And ANDROMEDA": RA hhmmss, Dec +ddmmss, abbrev, name
        if len(raw) < 20 or raw[0] in "#C" or raw[6] not in "+-":
            continue
        ra = int(raw[0:2]) * 15 + int(raw[2:4]) * 15 / 60 + int(raw[4:6]) * 15 / 3600
        sign = -1 if raw[6] == "-" else 1
        dec = sign * (int(raw[7:9]) + int(raw[9:11]) / 60 + int(raw[11:13]) / 3600)
        abbr = raw[13:].split(" ", 1)[0].strip()   # IAU three-letter code, e.g. "And", "CVn"
        x, y, z = unit(ra, dec)
        out.append(f"{x:.6f} {y:.6f} {z:.6f} {abbr}")
    return out


def main() -> int:
    data = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_KSTARS
    pos = hd_positions()
    segs, missing = parse_lines(data / "clines.dat", pos)
    names = parse_names(data / "cnames.dat")
    ASSETS.mkdir(parents=True, exist_ok=True)
    with (ASSETS / "constlines.bin").open("wb") as f:
        f.write(b"GCLN")
        f.write(struct.pack("<I", len(segs)))
        f.write(np.asarray(segs, dtype="<f4").tobytes())
    (ASSETS / "constnames.txt").write_text("\n".join(names) + "\n", encoding="utf-8")
    print(f"{len(pos)} HD stars; {len(segs)} segments ({missing} dropped, no Hipparcos match); "
          f"{len(names)} names -> {ASSETS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
