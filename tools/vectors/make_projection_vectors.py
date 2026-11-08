"""Write api/vectors/sky/projection.json from Galileo's own star atlas, so that the app is held to the same projection.

    PYTHONPATH=../Galileo python tools/vectors/make_projection_vectors.py
"""
import datetime as dt
import json
from pathlib import Path

import numpy as np
from galileo.planning import star_atlas as sa

cases = []
rng = np.random.default_rng(5)
for _ in range(120):
    when = dt.datetime(2026, 1, 1, tzinfo=dt.UTC) + dt.timedelta(seconds=float(rng.uniform(0, 3e8)))
    lat, lon = float(rng.uniform(-60, 70)), float(rng.uniform(-180, 180))
    ra, dec = float(rng.uniform(0, 360)), float(np.degrees(np.arcsin(rng.uniform(-1, 1))))
    jd = sa.julian_date(when)
    lst = sa.local_sidereal_deg(jd, lon)
    r, d = sa.precess_from_j2000(np.array([ra]), np.array([dec]), jd)
    alt, az = sa.equatorial_to_horizontal(r, d, lst, lat)
    # Look near the object so that it's on screen, off-centre, in both landscape and portrait views.
    width, height = [(1000, 700), (768, 1024), (390, 700)][int(rng.integers(3))]
    fov = float(rng.choice([150.0, 100.0, 40.0, 5.0]))
    vp = sa.Viewport(az0=(float(az[0]) + float(rng.uniform(-0.3, 0.3)) * fov) % 360,
                     alt0=float(np.clip(alt[0] + rng.uniform(-0.3, 0.3) * fov, -10, 90)),
                     fov_deg=fov, width=width, height=height)
    x, y, visible = vp.project(alt, az)
    cases.append({"unix": when.timestamp(), "latitude": lat, "longitude": lon, "ra_deg": ra, "dec_deg": dec,
                  "view_altitude": vp.alt0, "view_azimuth": vp.az0, "field": fov, "width": width, "height": height,
                  "altitude": float(alt[0]), "azimuth": float(az[0]), "x": float(x[0]), "y": float(y[0]), "visible": bool(visible[0])})
out = Path(__file__).resolve().parents[2] / "api" / "vectors" / "sky" / "projection.json"
out.write_text(json.dumps({"source": "galileo.planning.star_atlas: Viewport.project, precess_from_j2000, equatorial_to_horizontal; "
                                     "x and y are pixels from the top left", "cases": cases}, indent=0))
print(len(cases), sum(c["visible"] for c in cases))
