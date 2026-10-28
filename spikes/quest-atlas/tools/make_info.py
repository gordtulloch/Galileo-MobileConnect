"""Bake the text for the object panel of the Quest star-atlas spike: common names, other designations, the
constellation, and a short description for the brighter objects.

Reads Galileo's cached OpenNGC-derived catalogue (same selection and labels as make_dso.py) and the object list in
dss.bin (the objects that get a description are the ones that also get a picture). Descriptions are the opening
sentences of the matching Wikipedia article (CC BY-SA 4.0), fetched through the REST summary endpoint.

    app/src/main/assets/deepinfo.txt     one object per line, tab separated:
        label  common name(s)  other designations  constellation  description

Descriptions are cached in tools/.infocache.json so a rerun only fetches what is missing.

Run with the Galileo venv:  python -I tools/make_info.py [--no-wiki]
"""

from __future__ import annotations

import argparse
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
CACHE = HERE / ".infocache.json"
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


def imaged_labels() -> set[str]:
    raw = (ASSETS / "dss.bin").read_bytes()
    n = struct.unpack_from("<I", raw, 4)[0]
    return {struct.unpack_from("<12s", raw, 12 + 16 * i)[0].split(b"\0")[0].decode() for i in range(n)}


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


def summary(label: str) -> str | None:
    """The article's opening sentences, "" when there is no usable article, None on a transient failure."""
    url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + urllib.parse.quote(wiki_title(label))
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
    if d.get("type") != "standard":
        return ""
    text = ascii_text(re.sub(r"\s*\([^()]*\)", "", d.get("extract", "")))  # parentheticals: pronunciations, alternates
    text = re.sub(r"\s+", " ", text).strip()
    out = ""
    for sent in re.split(r"(?<=[.!?])\s+(?=[A-Z])", text)[:4]:
        if re.search(r"coordinates|RA \d|dec [+-]?\d", sent):
            continue  # a coordinates sentence is noise on a panel that already shows them
        if len(out) + len(sent) > 330 and out:
            break
        out = (out + " " + sent).strip()
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-wiki", action="store_true")
    args = ap.parse_args()

    rows = json.loads(CATALOG.read_text("utf-8"))
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
        picked.append((label, o["r"], o["c"], list(dict.fromkeys(common))[:2], list(dict.fromkeys(other))[:3]))
    sc = SkyCoord([p[1] for p in picked] * u.deg, [p[2] for p in picked] * u.deg)
    cons = sc.get_constellation()

    want = imaged_labels()
    cache = json.loads(CACHE.read_text("utf-8")) if CACHE.exists() else {}
    todo = [p[0] for p in picked if p[0] in want and p[0] not in cache]
    if todo and not args.no_wiki:
        print(f"fetching {len(todo)} descriptions")
        with ThreadPoolExecutor(3) as ex:
            for lab, text in zip(todo, ex.map(summary, todo)):
                if text is not None:
                    cache[lab] = text
        CACHE.write_text(json.dumps(cache, indent=0), "utf-8")

    lines = []
    for (label, _ra, _dec, common, other, ), con in zip(picked, cons):
        clean = lambda s: s.replace("\t", " ").replace("\n", " ")  # noqa: E731
        lines.append("\t".join([label, clean(ascii_text(" / ".join(common))), clean(", ".join(other)), str(con),
                                clean(ascii_text(cache.get(label, "")))]))
    lines += [f"{name}				{text}" for name, text in BODIES.items()]
    (ASSETS / "deepinfo.txt").write_text("\n".join(lines) + "\n", "utf-8", newline="\n")
    described = sum(1 for p in picked if cache.get(p[0]))
    print(f"deepinfo.txt: {len(lines)} objects, {described} with a description")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
