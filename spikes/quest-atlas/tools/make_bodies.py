"""Bake the asteroid elements and satellite tracks for the Quest star-atlas spike.

Sun, Moon and planets need no data (ephem.h computes them). This writes the other two:

  asteroids.bin   the brightest numbered asteroids (H < --max-h) from the JPL Small-Body Database. The app
                  propagates the osculating elements as a Kepler orbit, so keep them fresh: positions drift by
                  tens of arcminutes within a year of the epoch.
      char[4] "GAST", uint32 count, then count x 80-byte records:
          char[16] name, float32 H, float32 G, float64 a, e, i, om, w, M0 (AU / degrees), float64 epoch JD

  satellites.bin  CelesTrak stations + the "visual" (brightest) group, propagated with SGP4 into position and
                  velocity samples (TEME, km and km/s) that the app Hermite-interpolates. Precomputing them keeps
                  SGP4 out of the headset code. The window is --back days before to --ahead days after the run
                  time, so rebake every week or two; outside it satellites are hidden.
      char[4] "GSAT", uint32 count, float64 t0 (unix), float32 step_s, uint32 samples, then per satellite:
          char[16] name, float32 nominal magnitude, uint32 0, samples x 6 float32 (x y z vx vy vz)

Run with the Galileo venv (it has sgp4):  python -I tools/make_bodies.py
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import time
import urllib.parse
import urllib.request
from pathlib import Path

import numpy as np
from sgp4.api import Satrec, jday

ASSETS = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets"
SBDB = "https://ssd-api.jpl.nasa.gov/sbdb_query.api"
CELESTRAK = "https://celestrak.org/NORAD/elements/gp.php?GROUP={}&FORMAT=tle"


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "galileo-quest-atlas-spike"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def bake_asteroids(max_h: float, keep: int) -> None:
    q = urllib.parse.urlencode({
        "fields": "full_name,H,G,a,e,i,om,w,ma,epoch", "sb-kind": "a", "sb-ns": "n",
        "sb-cdata": json.dumps({"AND": [f"H|LT|{max_h}"]}), "limit": 5000})
    d = json.loads(get(f"{SBDB}?{q}"))
    f = {name: i for i, name in enumerate(d["fields"])}
    recs = bytearray()
    n = 0
    for row in sorted(d["data"], key=lambda r: float(r[f["H"]]))[:keep]:
        m = re.match(r"\s*\d+\s+([^(]+?)\s*(\(|$)", row[f["full_name"]])
        name = (m.group(1) if m else row[f["full_name"]]).strip()
        try:
            h = float(row[f["H"]])
            g = float(row[f["G"]]) if row[f["G"]] not in (None, "") else 0.15
            el = [float(row[f[k]]) for k in ("a", "e", "i", "om", "w", "ma", "epoch")]
        except (TypeError, ValueError):
            continue
        recs += struct.pack("<16s2f7d", name.encode("ascii", "replace")[:15], h, g, *el)
        n += 1
    (ASSETS / "asteroids.bin").write_bytes(b"GAST" + struct.pack("<I", n) + bytes(recs))
    print(f"asteroids.bin: {n} brightest asteroids (H < {max_h}, {len(d['data'])} matched)")


def parse_tles(text: str) -> list[tuple[str, str, str]]:
    lines = [ln.rstrip() for ln in text.splitlines() if ln.strip()]
    return [(lines[i].strip(), lines[i + 1], lines[i + 2]) for i in range(0, len(lines) - 2, 3)
            if lines[i + 1].startswith("1 ") and lines[i + 2].startswith("2 ")]


def short_name(name: str) -> str:
    name = re.sub(r"\s*\(.*?\)", "", name).strip()
    if name.upper().startswith("CSS"):
        return "Tiangong"
    return name.title() if len(name) > 5 else name


def bake_satellites(count: int, back: float, ahead: float, step: float) -> None:
    seen: dict[str, tuple[str, str, str, float]] = {}
    for group, mag in (("stations", 0.0), ("visual", 3.5)):
        for name, l1, l2 in parse_tles(get(CELESTRAK.format(group)).decode()):
            seen.setdefault(l2.split()[1], (name, l1, l2, mag))
    chosen = list(seen.values())[:count]

    t0 = (int(time.time()) // int(step)) * int(step) - back * 86400.0
    n = int((back + ahead) * 86400.0 / step)
    unix = t0 + step * np.arange(n)
    jd = unix / 86400.0 + 2440587.5
    jd_i, jd_f = np.floor(jd), jd - np.floor(jd)

    body = bytearray()
    kept = 0
    for name, l1, l2, mag in chosen:
        sat = Satrec.twoline2rv(l1, l2)
        err, r, v = sat.sgp4_array(jd_i, jd_f)
        if err.any():
            print(f"  skip {name}: SGP4 error {int(err.max())} within the window")
            continue
        samples = np.hstack([r, v]).astype("<f4")
        body += struct.pack("<16sfI", short_name(name).encode("ascii", "replace")[:15], mag, 0) + samples.tobytes()
        kept += 1
    header = b"GSAT" + struct.pack("<IdfI", kept, t0, step, n)
    (ASSETS / "satellites.bin").write_bytes(header + bytes(body))
    print(f"satellites.bin: {kept} satellites, {n} samples every {step:.0f} s "
          f"({(len(header) + len(body)) / 1e6:.1f} MB), window {back:g} d back to {ahead:g} d ahead")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-h", type=float, default=9.0, help="asteroid absolute-magnitude limit")
    ap.add_argument("--asteroids", type=int, default=250, help="how many of the brightest to keep")
    ap.add_argument("--sats", type=int, default=70)
    ap.add_argument("--back", type=float, default=1.0, help="days of satellite track before now")
    ap.add_argument("--ahead", type=float, default=13.0, help="days of satellite track after now")
    ap.add_argument("--step", type=float, default=300.0, help="seconds between satellite samples")
    args = ap.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    bake_asteroids(args.max_h, args.asteroids)
    bake_satellites(args.sats, args.back, args.ahead, args.step)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
