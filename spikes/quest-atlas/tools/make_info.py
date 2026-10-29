"""Bake the text for the object panel of the Quest star-atlas spike, for every catalogue object: common names, other
designations, the constellation, a one-sentence summary built from the full OpenNGC table (galaxy type, distance from
redshift or parallax, physical size) and, where there is one, the opening of the matching Wikipedia article (CC BY-SA 4.0,
REST summary endpoint; tried under the NGC/Messier/Caldwell title and then under the common name).

    app/src/main/assets/deepinfo.txt     one object per line, tab separated:
        label  common name(s)  other designations  constellation  description  facts

The full OpenNGC tables (CC BY-SA 4.0) are expected in tools/.openngc_NGC.csv and .openngc_addendum.csv, downloaded from
github.com/mattiaverga/OpenNGC (database_files/). Descriptions are cached in tools/.infocache2.json so a rerun only
fetches what is missing.

Run with the Galileo venv:  python -I tools/make_info.py [--no-wiki] [--threads 4]
"""

from __future__ import annotations

import argparse
import csv
import math
import json
import os
import re
import struct
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import astropy.units as u
from astropy.coordinates import SkyCoord

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "app" / "src" / "main" / "assets"
CACHE = HERE / ".infocache2.json"  # v2: every object, with common-name fallbacks
CATALOG = Path(os.environ.get("APPDATA", "")) / "Galileo" / "cache" / "galileo_dso_catalog_v2.json"
M_RE, C_RE, N_RE, I_RE = (re.compile(p) for p in (r"^M\d+$", r"^C \d+$", r"^NGC \d+$", r"^IC \d+$"))
SKIP = {"Star", "DoubleStar"}
UA = "galileo-quest-atlas-spike/1.0 (gord.tulloch@gmail.com)"


# The Sun, Moon and planets are drawn by the app from ephemerides, so their panel text lives here rather than in the catalogue.
BODIES = {
    "Sun": "Our star, a ball of hot plasma about 1.4 million kilometres across that holds the solar system together. Never look at it through optics without a proper solar filter.",
    "Moon": "Earth's only natural satellite, about 3,474 kilometres across and 384,000 kilometres away on average. Its phase shows how much of its sunlit half faces us.",
    "Mercury": "The innermost and smallest planet, only slightly larger than our Moon. It orbits the Sun every 88 days and never strays far from it, so it is seen low in the twilight.",
    "Venus": "The brightest planet, wrapped in thick clouds of carbon dioxide and sulphuric acid. Its surface is hot enough to melt lead, and it shows phases like the Moon.",
    "Mars": "The red planet, a cold desert world with the tallest volcano in the solar system, Olympus Mons. It has two small moons, Phobos and Deimos.",
    "Jupiter": "The largest planet, a gas giant with a banded atmosphere and the Great Red Spot, a storm wider than Earth. Its four large moons can be seen in binoculars.",
    "Saturn": "The ringed planet, a gas giant less dense than water. Its rings are made of ice and rock, and even a small telescope shows them clearly.",
    "Uranus": "An ice giant tipped on its side, with a faint ring system. It is barely visible to the naked eye under very dark skies and shows as a small blue-green disc in a telescope.",
    "Neptune": "The most distant planet, a deep blue ice giant with the fastest winds in the solar system. It needs binoculars or a telescope to see.",
}


def wiki_title(label: str) -> str:
    if label.startswith("M"):
        return "Messier_" + label[1:]
    return label.replace(" ", "_").replace("C_", "Caldwell_") if label.startswith("C") else label.replace(" ", "_")


def ascii_text(text: str) -> str:
    """The panel font is ASCII: spell out the symbols Wikipedia uses for coordinates and drop accents."""
    for k, v in {"°": " deg", "′": "'", "″": '"', "–": "-", "—": "-", "−": "-",
                 "‘": "'", "’": "'", "“": '"', "”": '"', " ": " ", " ": " ",
                 "×": "x", "…": "..."}.items():
        text = text.replace(k, v)
    return unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()


def fetch_summary(title: str) -> str | None:
    """The opening sentences of a Wikipedia article: "" when there is no usable article, None on a transient failure."""
    url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + urllib.parse.quote(title)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=30) as r:
                d = json.loads(r.read())
            break
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return ""
            time.sleep(2 + 3 * attempt)
        except (urllib.error.URLError, TimeoutError, ValueError):
            time.sleep(2 + 3 * attempt)
    else:
        return None
    if d.get("type") != "standard" or re.match(r"(List|Index) of", d.get("title", "")):
        return ""
    text = ascii_text(re.sub(r"\s*\([^()]*\)", "", d.get("extract", "")))  # parentheticals: pronunciations, alternates
    text = re.sub(r"\s+", " ", text).strip()
    if re.search(r"\bmay refer to\b|\bis a list of\b|\bis the list of\b", text[:160]):
        return ""
    out = ""
    for sent in re.split(r"(?<=[.!?])\s+(?=[A-Z])", text)[:4]:
        if re.search(r"coordinates|RA \d|dec [+-]?\d", sent):
            continue  # a coordinates sentence is noise on a panel that already shows them
        if len(out) + len(sent) > 330 and out:
            break
        out = (out + " " + sent).strip()
    return out


def summary(args: tuple[str, list[str]]) -> str | None:
    """Description for an object: its own article, else the article under a common name."""
    label, commons = args
    for title in [wiki_title(label), *[c.replace(" ", "_") for c in commons]]:
        text = fetch_summary(title)
        if text is None:
            return None
        if text:
            return text
    return ""


# ---- OpenNGC facts (Hubble type, distance from redshift or parallax): the one-sentence summary every object can have
KINDS = {"G": "galaxy", "GPair": "pair of galaxies", "GTrpl": "triplet of galaxies", "GGroup": "group of galaxies",
         "OCl": "open cluster", "GCl": "globular cluster", "Cl+N": "star cluster with nebulosity",
         "HII": "H II region, a glowing cloud of ionized hydrogen", "DrkN": "dark nebula", "EmN": "emission nebula",
         "Neb": "nebula", "RfN": "reflection nebula", "SNR": "supernova remnant", "Nova": "nova",
         "PN": "planetary nebula", "*Ass": "association of stars", "**": "double star", "*": "star", "Other": "object"}


def openngc() -> dict[str, dict[str, str]]:
    rows: dict[str, dict[str, str]] = {}
    for name in (".openngc_NGC.csv", ".openngc_addendum.csv"):
        path = HERE / name
        if not path.exists():
            continue
        for r in csv.DictReader(path.read_text("utf-8").splitlines(), delimiter=";"):
            m = re.match(r"^(NGC|IC)(\d+)(\w*)$", r["Name"])
            if m:
                rows[f"{m.group(1)} {int(m.group(2))}{m.group(3)}"] = r
            if r.get("M"):
                rows[f"M{int(r['M'])}"] = r
    return rows


def morphology(hubble: str) -> str:
    h = hubble.strip()
    if h.startswith("SAB"):
        return "weakly barred spiral"
    if h.startswith("SB"):
        return "barred spiral"
    if h.startswith("S0"):
        return "lenticular"
    if h.startswith("S"):
        return "spiral"
    if h.startswith(("dE", "dSph")):
        return "dwarf elliptical"
    if h.startswith("E"):
        return "elliptical"
    if h.startswith("I"):
        return "irregular"
    return ""


def light_years(row: dict[str, str]) -> float | None:
    pax = row.get("Pax", "")
    if pax:
        try:
            if float(pax) > 0.05:
                return 3261.6 / float(pax)  # pax in mas: parsecs = 1000 / pax, light-years = 3.2616 x parsecs
        except ValueError:
            pass
    z = row.get("Redshift", "")
    if z:
        try:
            if float(z) >= 0.0035:  # nearer than that, peculiar motion swamps the Hubble flow
                return 299792.458 * float(z) / 70.0 * 3.2616e6
        except ValueError:
            pass
    return None


def human(n: float) -> str:
    if n >= 1e9:
        return f"{n / 1e9:.1f} billion"
    if n >= 1e6:
        return f"{n / 1e6:.0f} million" if n >= 1e7 else f"{n / 1e6:.1f} million"
    if n >= 1e3:
        return f"{round(n, -3 if n >= 1e4 else -2):,.0f}"
    return f"{n:.0f}"


def facts(row: dict[str, str] | None, size_arcmin: float) -> str:
    if not row:
        return ""
    kind = KINDS.get(row["Type"], "")
    if not kind:
        return ""
    if row["Type"] == "G":
        mo = morphology(row.get("Hubble", ""))
        kind = (mo + " galaxy") if mo else "galaxy"
    out = ("An " if kind[0] in "aeiou" else "A ") + kind
    if row["Type"] == "G" and row.get("Hubble"):
        out += f", Hubble type {row['Hubble']}"
    d = light_years(row)
    if d:
        out += f", about {human(d)} light-years away"
        if size_arcmin > 0.3:
            out += f" and roughly {human(d * math.radians(size_arcmin / 60.0))} light-years across"
    return out + "."


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-wiki", action="store_true")
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()

    rows = json.loads(CATALOG.read_text("utf-8"))
    ngc_rows = openngc()
    picked = []
    for o in rows:
        names = [s for s in [o.get("n"), *o.get("d", [])] if isinstance(s, str)]
        m = next((s for s in names if M_RE.match(s)), None)
        c = next((s for s in names if C_RE.match(s)), None)
        ngc = next((s for s in names if N_RE.match(s)), None)
        if not (m or c or ngc) or (o["t"] in SKIP and not (m or c)):
            continue
        label = (m or c or ngc)[:9]
        common = [s for s in names if not any(ch.isdigit() for ch in s) and len(s) >= 4]
        other = [s for s in names if (M_RE.match(s) or C_RE.match(s) or N_RE.match(s) or I_RE.match(s)) and s != label]
        row = next((ngc_rows[s] for s in names if s in ngc_rows), None)
        picked.append((label, o["r"], o["c"], list(dict.fromkeys(common))[:2], list(dict.fromkeys(other))[:3],
                       facts(row, float(o.get("s", 0.0)))))
    sc = SkyCoord([p[1] for p in picked] * u.deg, [p[2] for p in picked] * u.deg)
    cons = sc.get_constellation()

    cache = json.loads(CACHE.read_text("utf-8")) if CACHE.exists() else {}
    todo = [p for p in picked if p[0] not in cache]
    if todo and not args.no_wiki:
        print(f"fetching {len(todo)} descriptions", flush=True)
        done = 0
        with ThreadPoolExecutor(args.threads) as ex:
            for p, text in zip(todo, ex.map(summary, [(p[0], p[3]) for p in todo])):
                if text is not None:
                    cache[p[0]] = text
                done += 1
                if done % 500 == 0:
                    print(f"  {done}", flush=True)
                    CACHE.write_text(json.dumps(cache, indent=0), "utf-8")
        CACHE.write_text(json.dumps(cache, indent=0), "utf-8")

    clean = lambda s: s.replace("\t", " ").replace("\n", " ")  # noqa: E731
    lines = []
    for (label, _ra, _dec, common, other, fact), con in zip(picked, cons):
        lines.append("\t".join([label, clean(ascii_text(" / ".join(common))), clean(", ".join(other)), str(con),
                                clean(ascii_text(cache.get(label, ""))), clean(fact)]))
    lines += [f"{name}\t\t\t\t{text}\t" for name, text in BODIES.items()]
    (ASSETS / "deepinfo.txt").write_text("\n".join(lines) + "\n", "utf-8", newline="\n")
    described = sum(1 for p in picked if cache.get(p[0]))
    print(f"deepinfo.txt: {len(lines)} objects, {described} with an article, {sum(1 for p in picked if p[5])} with facts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
