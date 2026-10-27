"""Bake the observing site for the Quest star-atlas spike from Galileo's database.

Reads ONLY name, latitude, longitude, elevation and timezone of one Observatory
from the Galileo DB (read-only) and writes app/src/main/assets/site.txt:

    NAME|latitude_deg_N|longitude_deg_E|elevation_m|IANA timezone

Nothing else (address, contacts) is copied. The file is gitignored because it
holds a home location. Or skip the DB entirely:  --name X --lat 1 --lon 2

Run with the Galileo venv:  python -I tools/make_site.py [--observatory SPAO]
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "site.txt"


def from_db(name: str) -> tuple[str, float, float, float, str]:
    sys.path.insert(0, r"C:\Projects\Galileo")
    from galileo.platform import get_db_path  # noqa: PLC0415

    db = get_db_path("galileo.db")
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    row = con.execute(
        "select name, latitude, longitude, coalesce(elevation_m, 0), coalesce(timezone, 'UTC') "
        "from observatories where name = ?", (name,)).fetchone()
    if not row or row[1] is None or row[2] is None:
        raise SystemExit(f"observatory {name!r} not found (or has no coordinates) in {db}")
    return row


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--observatory", default="SPAO")
    ap.add_argument("--name"); ap.add_argument("--lat", type=float); ap.add_argument("--lon", type=float)
    ap.add_argument("--elev", type=float, default=0.0); ap.add_argument("--tz", default="UTC")
    a = ap.parse_args()
    if a.lat is not None and a.lon is not None:
        site = (a.name or "SITE", a.lat, a.lon, a.elev, a.tz)
    else:
        site = from_db(a.observatory)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("|".join(str(x) for x in site) + "\n", encoding="utf-8")
    print(f"site {site[0]}: {site[1]:.4f}, {site[2]:.4f}, {site[3]:.0f} m, {site[4]} -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
