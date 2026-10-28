"""Build tools/test_ephem.cpp with MSVC and compare ephem.h to astropy (Sun, Moon, planets) and JPL Horizons (Ceres).

Run with the Galileo venv:  python -I tools/test_ephem.py
"""

from __future__ import annotations

import json
import re
import subprocess
import urllib.parse
import urllib.request
from pathlib import Path

import astropy.units as u
import numpy as np
from astropy.coordinates import TETE, SkyCoord, get_body
from astropy.time import Time

HERE = Path(__file__).resolve().parent
VCVARS = r"C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat"
EXE = Path(__import__("tempfile").gettempdir()) / "test_ephem.exe"


def build() -> None:
    cmd = f'"{VCVARS}" >nul && cl /nologo /O2 /EHsc /std:c++17 /Fe:"{EXE}" /Fo:"{EXE.with_suffix(".obj")}" "{HERE / "test_ephem.cpp"}"'
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(r.stdout + r.stderr)


def run(unix: float, *extra: str) -> dict[str, tuple[float, ...]]:
    r = subprocess.run([str(EXE), repr(float(unix)), *extra], capture_output=True, text=True, check=True)
    return {ln.split()[0]: tuple(map(float, ln.split()[1:])) for ln in r.stdout.splitlines()}


def sep(ra1, dec1, ra2, dec2) -> float:
    return SkyCoord(ra1 * u.deg, dec1 * u.deg).separation(SkyCoord(ra2 * u.deg, dec2 * u.deg)).deg


def sbdb_ceres() -> tuple[list[str], float]:
    q = urllib.parse.urlencode({"sstr": "Ceres", "phys-par": "1"})
    d = json.load(urllib.request.urlopen("https://ssd-api.jpl.nasa.gov/sbdb.api?" + q, timeout=60))
    el = {e["name"]: float(e["value"]) for e in d["orbit"]["elements"]}
    ph = {e["name"]: float(e["value"]) for e in d["phys_par"] if e["name"] in ("H", "G")}
    args = [ph["H"], ph.get("G", 0.12), el["a"], el["e"], el["i"], el["om"], el["w"], el["ma"], float(d["orbit"]["epoch"])]
    return [repr(a) for a in args], float(d["orbit"]["epoch"])


def horizons(jd: float) -> tuple[float, float]:
    q = urllib.parse.urlencode({"format": "text", "COMMAND": "'1;'", "OBJ_DATA": "NO", "MAKE_EPHEM": "YES",
                                "EPHEM_TYPE": "OBSERVER", "CENTER": "'500@399'", "TLIST": f"'{jd}'",
                                "QUANTITIES": "'1'", "ANG_FORMAT": "DEG", "CSV_FORMAT": "YES"})
    t = urllib.request.urlopen("https://ssd.jpl.nasa.gov/api/horizons.api?" + q, timeout=60).read().decode()
    body = t.split("$$SOE")[1].split("$$EOE")[0]
    nums = [float(x) for x in re.findall(r"-?\d+\.\d+", body.split(",", 3)[-1])]
    return nums[0], nums[1]


def main() -> None:
    build()
    ast, epoch = sbdb_ceres()
    for iso in ("2026-10-27 03:00:00", "2026-03-05 12:00:00", "2027-08-14 22:30:00"):
        t = Time(iso)
        got = run(t.unix, *ast)
        print(iso)
        for name in ("Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"):
            ra, dec, mag, size = got[name]
            c = get_body(name.lower(), t)
            if name == "Moon":
                c = c.transform_to(TETE(obstime=t))
                ref_ra, ref_dec = c.ra.deg, c.dec.deg
            else:
                ref_ra, ref_dec = c.ra.deg, c.dec.deg  # GCRS: geocentric, axes of the ICRS (not transformed: that would go barycentric)
            print(f"  {name:8s} err {sep(ra, dec, ref_ra, ref_dec) * 60:7.2f} arcmin  mag {mag:6.2f}  size {size:7.2f}'")
        hr, hd = horizons(t.jd)
        ra, dec, mag, _ = got["Asteroid"]
        print(f"  Ceres    err {sep(ra, dec, hr, hd) * 60:7.2f} arcmin  mag {mag:6.2f}")


if __name__ == "__main__":
    main()
