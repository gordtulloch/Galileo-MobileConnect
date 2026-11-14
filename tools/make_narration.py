"""Bake the spoken object descriptions for the Quest app.

The Quest has no text-to-speech engine, so the voice is made here (macOS `say`, then ffmpeg to small mono AAC) from
Galileo's own deep-sky catalogue, and shipped as assets. Covers every Messier and Caldwell object plus anything brighter
than --limit (default magnitude 9.5). Run with the Galileo venv so the catalogue and astropy are importable:

    /Users/gordtulloch/Galileo/.venv/bin/python -I tools/make_narration.py

Writes android/quest/src/main/assets/speech/<key>.m4a and index.tsv (key<TAB>text); the app shows the same text in the
object menu that the clip says. <key> is the object's primary name with non-alphanumerics as "_" (see Narration.kt).
"""
from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from astropy.coordinates import SkyCoord, get_constellation
import astropy.units as u

from galileo.planning.sky_atlas import SkyAtlas

OUT = Path(__file__).resolve().parent.parent / "android/quest/src/main/assets/speech"
VOICE = "Samantha"
KINDS = {
    "Galaxy": "a galaxy", "OpenCluster": "an open star cluster", "GlobularCluster": "a globular star cluster",
    "PlanetaryNebula": "a planetary nebula", "Nebula": "a nebula", "EmissionNebula": "an emission nebula",
    "ReflectionNebula": "a reflection nebula", "DarkNebula": "a dark nebula", "SupernovaRemnant": "a supernova remnant",
    "Asterism": "an asterism", "DoubleStar": "a double star", "Star": "a star",
}


def key(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "_", name)


def say(text: str) -> str:
    text = re.sub(r"\bNGC\s*(\d+)", r"N G C \1", text)
    text = re.sub(r"\bIC\s*(\d+)", r"I C \1", text)
    text = re.sub(r"\bM\s?(\d+)\b", r"Messier \1", text)
    return re.sub(r"\bC\s?(\d+)\b", r"Caldwell \1", text)


def label(o) -> str:
    for d in o.designations:
        if re.fullmatch(r"M ?\d+", d):
            return "M" + re.sub(r"\D", "", d)
    return o.primary_name


def describe(o, constellation: str) -> str:
    kind = KINDS.get(getattr(o.object_type, "value", str(o.object_type)), "a deep sky object")
    names = [label(o)]
    for d in o.designations:
        if re.fullmatch(r"(NGC|IC|C|M) ?\d+", d) and re.sub(r" ", "", d) != re.sub(r" ", "", label(o)):
            names.append(d.replace("  ", " "))
    parts = [f"{names[0]}, {kind} in {constellation}."]
    if len(names) > 1:
        parts.append("Also known as " + ", ".join(names[1:4]) + ".")
    if o.magnitude < 90:
        parts.append(f"Magnitude {o.magnitude:.1f}.")
    if o.size_arcmin > 0:
        size = o.size_arcmin
        parts.append(f"About {size:.0f} arcminutes across." if size >= 2 else f"About {size:.1f} arcminutes across.")
    return " ".join(parts)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=float, default=9.5)
    args = ap.parse_args()
    atlas = SkyAtlas()
    chosen = []
    for o in atlas.search(""):
        famous = any(re.fullmatch(r"(M|C) ?\d+", d) for d in o.designations)
        if famous or (o.magnitude <= args.limit and str(getattr(o.object_type, "value", o.object_type)) != "DoubleStar"):
            chosen.append(o)
    coords = SkyCoord([o.ra_deg for o in chosen] * u.deg, [o.dec_deg for o in chosen] * u.deg)
    constellations = get_constellation(coords)
    OUT.mkdir(parents=True, exist_ok=True)
    rows = [(key(o.primary_name), describe(o, str(c))) for o, c in zip(chosen, constellations)]

    def render(row: tuple[str, str]) -> None:
        k, text = row
        target = OUT / f"{k}.m4a"
        if target.exists():
            return
        with tempfile.TemporaryDirectory() as tmp:
            aiff = Path(tmp) / "a.aiff"
            subprocess.run(["say", "-v", VOICE, "-o", str(aiff), say(text)], check=True)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(aiff), "-ac", "1", "-c:a", "aac", "-b:a", "40k", str(target)], check=True)

    with ThreadPoolExecutor(4) as pool:
        list(pool.map(render, rows))
    (OUT / "index.tsv").write_text("".join(f"{k}\t{t}\n" for k, t in rows), encoding="utf-8")
    print(f"{len(rows)} objects")


if __name__ == "__main__":
    main()
