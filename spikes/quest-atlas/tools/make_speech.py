"""Bake the spoken version of each object's panel text for the Quest star-atlas spike.

The Quest has no text-to-speech engine and the app has no Java, so the speech is made here: the Windows "Microsoft Zira"
(female) voice reads the text, ffmpeg turns it into small mono MP3s, and the app plays the file when the right grip
is pressed while an object's panel is open. Covers every object that has a DSS2 picture (dss.bin) plus the Sun, Moon and
planets. The spoken text is the panel's own: name, common name, type and constellation, other designations, magnitude and
size, then the description. Altitude/azimuth change with the sky and are not read.

    app/src/main/assets/speech/<label with non-alphanumerics as _>.mp3

Needs deepsky.bin, dss.bin and deepinfo.txt (make_dso.py, make_dss.py, make_info.py first), Windows PowerShell with
System.Speech, and ffmpeg on PATH. WAVs are cached in tools/.speechcache so a rerun only speaks what is missing.

Run with the Galileo venv:  python -I tools/make_speech.py [--shards 4]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import struct
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "app" / "src" / "main" / "assets"
OUT = ASSETS / "speech"
CACHE = HERE / ".speechcache"
# Runs inside PowerShell (as a -Command, so no execution-policy change is needed): speaks each {file, text} of the manifest
# named by $env:SPEECH_MANIFEST to a WAV with the Windows "Zira" (female) voice.
SPEAK = """
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice('Microsoft Zira Desktop')
$s.Rate = -1
foreach ($i in (Get-Content -LiteralPath $env:SPEECH_MANIFEST -Raw | ConvertFrom-Json)) {
    if (Test-Path -LiteralPath $i.file) { continue }
    $s.SetOutputToWaveFile($i.file)
    $s.Speak($i.text)
    $s.SetOutputToNull()
}
"""
TYPE_WORDS = ["Galaxy", "Open cluster", "Globular cluster", "Planetary nebula", "Nebula", "Object"]
BODIES = {"Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"}


def key(label: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "_", label)


def speakable(text: str) -> str:
    """Catalogue designations the way they are said."""
    text = re.sub(r"(?<![A-Za-z])C\s(\d+)\b", r"Caldwell \1", text)  # before NGC, whose spelled-out form contains "C 123"
    text = re.sub(r"\bNGC\s*(\d+)", r"N G C \1", text)
    text = re.sub(r"\bIC\s*(\d+)", r"I C \1", text)
    text = re.sub(r"\bM(\d+)\b", r"Messier \1", text)
    return text.replace("&", " and ")


def first_sentences(text: str, limit: int = 260) -> str:
    out = ""
    for sent in re.split(r"(?<=[.!?])\s+", text):
        if out and len(out) + len(sent) > limit:
            break
        out = (out + " " + sent).strip()
    return out


def objects() -> dict[str, tuple[int, float, float]]:
    raw = (ASSETS / "deepsky.bin").read_bytes()
    n = struct.unpack_from("<I", raw, 4)[0]
    out = {}
    for i in range(n):
        _x, _y, _z, mag, size, typ, _cat, lab = struct.unpack_from("<3f2fBB10s", raw, 8 + 32 * i)
        out[lab.split(b"\0")[0].decode()] = (typ, mag, size)
    return out


def imaged() -> list[str]:
    raw = (ASSETS / "dss.bin").read_bytes()
    n = struct.unpack_from("<I", raw, 4)[0]
    return [struct.unpack_from("<12s", raw, 12 + 16 * i)[0].split(b"\0")[0].decode() for i in range(n)]


def build_texts() -> dict[str, str]:
    info = {}
    for line in (ASSETS / "deepinfo.txt").read_text("utf-8").splitlines():
        f = line.split("\t")
        if len(f) == 5:
            info[f[0]] = f[1:]
    objs = objects()
    texts: dict[str, str] = {}
    for label in imaged() + sorted(BODIES):
        common, also, con, desc = info.get(label, ["", "", "", ""])
        if label in BODIES:
            texts[label] = f"{label}. {desc}"
            continue
        typ, mag, size = objs[label]
        parts = [speakable(label) + (f", the {common.split(' / ')[0]}" if common else "") + "."]
        kind = TYPE_WORDS[min(typ, 5)] if typ < 5 else "Located"
        parts.append(kind + (f" in the constellation {con}." if con else "."))
        if also:
            parts.append("Also catalogued as " + speakable(also) + ".")
        facts = []
        if mag < 14.95:
            facts.append(f"it shines at magnitude {mag:.1f}")
        if size >= 1:
            facts.append(f"it spans {size:.0f} arcminutes of sky")
        elif size > 0:
            facts.append(f"it spans {size * 60:.0f} arcseconds of sky")
        if facts:
            parts.append(" and ".join(facts).capitalize() + ".")
        if desc:
            parts.append(speakable(first_sentences(desc)))
        texts[label] = " ".join(parts)
    return texts


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--shards", type=int, default=4)
    args = ap.parse_args()
    CACHE.mkdir(exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)

    texts = build_texts()
    items = [{"file": str(CACHE / (key(k) + ".wav")), "text": t} for k, t in texts.items()]
    print(f"{len(items)} clips, {sum(len(t) for t in texts.values()) / len(texts):.0f} characters on average")

    procs = []
    for s in range(args.shards):
        manifest = CACHE / f"manifest{s}.json"
        manifest.write_text(json.dumps(items[s :: args.shards]), "ascii")
        procs.append(subprocess.Popen(["powershell", "-NoProfile", "-Command", SPEAK],
                                      env={**os.environ, "SPEECH_MANIFEST": str(manifest)}))
    for p in procs:
        p.wait()

    def encode(it) -> bool:
        wav = Path(it["file"])
        mp3 = OUT / (wav.stem + ".mp3")
        if not wav.exists():
            return False
        r = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-ac", "1", "-ar", "22050",
                            "-codec:a", "libmp3lame", "-b:a", "16k", str(mp3)])
        return r.returncode == 0

    with ThreadPoolExecutor(8) as ex:
        ok = sum(ex.map(encode, items))
    total = sum(f.stat().st_size for f in OUT.glob("*.mp3"))
    print(f"speech: {ok}/{len(items)} clips, {total / 1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
