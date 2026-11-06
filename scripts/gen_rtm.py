"""Generate docs/RTM.md from docs/SRS.md (one row per MAPP requirement).

Usage: python scripts/gen_rtm.py .   (run from the repository root)
"""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
srs = (root / "docs" / "SRS.md").read_text(encoding="utf-8")

rows = []
for line in srs.splitlines():
    m = re.match(r"\|\s*(MAPP-([A-Z]+)-(\d+))\s*\|\s*([^|]+?)\s*\|\s*(.+)\s*\|\s*(MVP|P2|P3)\s*\|\s*$", line)
    if m:
        rows.append(dict(id=m[1], area=m[2], num=m[3], plat=m[4], text=m[5], pri=m[6]))

# Shared / iOS design component by area (see SDD.md Section 7 and ios/SDD.md Section 3)
SHARED = {
    "CON": "S-LINK", "OVR": "S-STATE", "DEV": "S-API, S-CTL", "IMG": "S-IMG", "SKY": "S-SKY",
    "SOL": "S-API", "FOC": "S-API", "GUI": "S-API", "FLP": "S-API, S-CTL", "SES": "S-SES",
    "SCH": "S-SCH", "CAL": "S-API", "SAF": "S-CTL, S-STATE", "EMG": "S-CTL", "LIB": "S-IMG",
    "NOT": "—", "CTL": "S-CTL", "CFG": "S-API", "STR": "S-SKY", "LAY": "S-DSN",
    "PLT": "S-TEST", "QST": "—",
}
IOS = {
    "CON": "I-CONN, I-TRUST", "OVR": "I-SHELL", "DEV": "I-OPS", "IMG": "I-IMG, I-OPS", "SKY": "I-SKY",
    "SOL": "I-OPS", "FOC": "I-OPS", "GUI": "I-OPS", "FLP": "I-OPS, I-SAFE", "SES": "I-SES",
    "SCH": "I-SES", "CAL": "I-OPS", "SAF": "I-SAFE", "EMG": "I-SAFE", "LIB": "I-IMG",
    "NOT": "I-LIFE", "CTL": "I-SAFE", "CFG": "I-SHELL", "STR": "I-SKY", "LAY": "I-THEME",
    "PLT": "I-REL",
}
# Per-requirement overrides: (shared, ios)
OVR = {
    "MAPP-CON-010": ("S-LINK", "I-CONN"),
    "MAPP-CON-020": ("S-LINK", "I-CONN"),
    "MAPP-CON-030": ("S-LINK", "I-CONN"),
    "MAPP-CON-080": ("S-LINK", "I-CONN"),
    "MAPP-CON-090": ("S-LINK, S-API", "I-CONN, I-OPS"),
    "MAPP-CON-100": ("S-LINK, S-SEC", "I-CONN, I-TRUST"),
    "MAPP-CON-120": ("S-LINK, S-SEC", "I-CONN"),
    "MAPP-CON-040": ("S-SEC, S-LINK", "I-TRUST"),
    "MAPP-CON-050": ("S-LINK, S-SEC", "I-TRUST"),
    "MAPP-CON-060": ("S-LINK", "I-CONN, I-TRUST"),
    "MAPP-CON-070": ("S-LINK, S-STATE", "I-LIFE, I-CONN"),
    "MAPP-CON-110": ("S-MOCK", "I-CONN, I-BRIDGE"),
    "MAPP-OVR-050": ("S-LINK, S-STATE", "I-SHELL"),
    "MAPP-OVR-070": ("S-STATE, S-LINK", "I-SHELL"),
    "MAPP-DEV-030": ("S-CTL", "I-OPS, I-LIFE"),
    "MAPP-DEV-070": ("S-STATE", "I-OPS"),
    "MAPP-IMG-010": ("S-API", "I-OPS"),
    "MAPP-IMG-020": ("S-API", "I-OPS"),
    "MAPP-IMG-070": ("S-CTL", "I-IMG, I-SAFE"),
    "MAPP-SKY-040": ("S-CTL, S-SKY", "I-SKY, I-SAFE"),
    "MAPP-SKY-050": ("S-SCH, S-SKY", "I-SKY"),
    "MAPP-FOC-030": ("S-API", "I-OPS"),
    "MAPP-GUI-020": ("S-STATE", "I-OPS"),
    "MAPP-FLP-010": ("S-STATE", "I-OPS"),
    "MAPP-SES-020": ("S-SES, S-CTL", "I-SES, I-SAFE"),
    "MAPP-SES-030": ("S-STATE, S-SES", "I-SES"),
    "MAPP-SAF-030": ("S-STATE", "I-OPS, I-SAFE"),
    "MAPP-CTL-060": ("S-CTL", "I-LIFE, I-OPS"),
    "MAPP-CTL-080": ("S-API", "I-BRIDGE"),
    "MAPP-CFG-020": ("S-API", "I-SHELL"),
    "MAPP-PLT-020": ("S-DSN", "I-REL, I-BRIDGE"),
    "MAPP-PLT-030": ("S-TEST", "I-REL"),
    "MAPP-PLT-040": ("S-TEST, S-MOCK", "I-REL, I-BRIDGE"),
    "MAPP-PLT-010": ("—", "I-REL"),
    "MAPP-PLT-050": ("—", "I-REL"),
    "MAPP-PLT-060": ("S-SEC", "I-REL"),
    "MAPP-PLT-070": ("—", "I-CONN, I-REL"),
    "MAPP-PLT-080": ("—", "—"),
    "MAPP-PLT-100": ("—", "I-REL"),
    "MAPP-PLT-090": ("S-LINK", "I-CONN, I-REL"),
    "MAPP-LAY-050": ("S-DSN", "I-THEME"),
    "MAPP-LAY-040": ("S-DSN", "I-THEME"),
}
# Quest: shared components where the shared layer does the work
QST_SHARED = {
    "060": "S-CTL", "090": "S-LINK, S-CTL", "110": "S-SKY", "120": "S-SKY, S-STATE", "130": "S-SKY",
    "140": "S-SKY, S-CTL", "170": "S-IMG", "180": "S-IMG", "200": "S-LINK", "240": "S-SKY",
    "250": "S-SKY", "260": "S-STATE", "270": "S-IMG", "290": "S-STATE",
}
# Verification method: U shared unit/vector, C contract vs mock Master, UI platform UI test, D on-device/manual, R review/inspection
METHOD = {
    "CON": "C, UI", "OVR": "U, UI", "DEV": "C, UI", "IMG": "C, UI", "SKY": "U, UI", "SOL": "C, UI",
    "FOC": "C, UI", "GUI": "C, UI", "FLP": "C, UI", "SES": "U, UI", "SCH": "U, UI", "CAL": "C, UI",
    "SAF": "U, UI", "EMG": "C, UI", "LIB": "C, UI", "NOT": "D", "CTL": "U, C", "CFG": "U, R",
    "STR": "U, D", "LAY": "UI, R", "PLT": "R", "QST": "D",
}
METHOD_OVR = {
    "MAPP-CON-010": "D", "MAPP-CON-070": "U, D", "MAPP-DEV-030": "U, D", "MAPP-CTL-060": "U, D",
    "MAPP-OVR-070": "D", "MAPP-PLT-010": "R", "MAPP-PLT-040": "C", "MAPP-PLT-030": "R",
    "MAPP-PLT-070": "D", "MAPP-PLT-080": "D", "MAPP-LAY-030": "U, UI", "MAPP-LAY-040": "UI, R",
    "MAPP-LAY-050": "R, D", "MAPP-QST-160": "D", "MAPP-STR-050": "U",
}


def applies(plat):
    return {"All": ("ios", "android", "quest"), "Mobile": ("ios", "android"), "iOS": ("ios",),
            "Android": ("android",), "Quest": ("quest",)}[plat]


def short(text):
    t = re.sub(r"`", "", text)
    t = re.split(r"(?<=[a-z\)])\.\s", t, maxsplit=1)[0].rstrip(".")
    return t if len(t) <= 150 else t[:147].rsplit(" ", 1)[0] + "…"


out = []
a = out.append
a("# Galileo MobileConnect Apps — RTM (Requirements Traceability Matrix)\n")
a("| | |\n|---|---|")
a("| **Project** | Galileo MobileConnect — iOS/iPadOS, Android and Meta Quest apps |")
a("| **Document** | RTM, v0.1, draft — **common to all platforms** |")
a("| **Status** | Draft — for review |")
a("| **Date** | 2026-11-06 |")
a("| **Upstream** | [SRS](SRS.md) v0.1, [Shared SDD](SDD.md) v0.1, [iOS SDD](ios/SDD.md) v0.1 |")
a("| **Not yet written** | Android SDD, Meta Quest SDD — their columns read `GAP` until they exist |\n")
a("---\n")
a("## 1. How to Read This Matrix\n")
a("One row per requirement in the [SRS](SRS.md), generated from it so the IDs cannot drift. Columns:\n")
a("- **Platforms / Priority** — copied from the SRS.")
a("- **Shared** — the `S-*` component of the [Shared SDD](SDD.md) that *specifies* it. Each `S-*` component is implemented once per platform (ADR-APP-001), so this column names the specification and the iOS/Android columns name where it is presented. `—` if the requirement is wholly platform-side.")
a("- **iOS / Android / Quest** — the platform component that presents or implements it, per the platform's SDD. `—` means the requirement does not apply to that platform (SRS *Platforms* column). **`GAP`** means it applies but the platform's SDD has not been written yet, so the requirement has no design there (SRS Section 6); a gap is a to-do, not an omission.")
a("- **Test case** — the reserved `TC-` identifier; **Method** — how it is verified.\n")
a("| Method | Meaning |\n|---|---|")
a("| **U** | Unit test or replayed test vector (`S-TEST`, `api/vectors/`) run against each platform's own core implementation |")
a("| **C** | Contract test of the client against the mock Master (and, once available, the Galileo plugin) |")
a("| **UI** | Automated UI test of the platform app against the mock Master |")
a("| **D** | Manual verification on a physical device (iPad, Android tablet, Quest 2) or with a real Master |")
a("| **R** | Review or inspection (build configuration, store metadata, design tokens) |\n")
a("Test-case identifiers follow `TC-MAPP-<AREA>-<###>`, the requirement's number reused. No test exists yet.\n")
a("---\n")

areas = []
for r in rows:
    if r["area"] not in areas:
        areas.append(r["area"])

TITLES = {
    "CON": "Connection and Pairing", "OVR": "Observatory Overview", "DEV": "Devices", "IMG": "Imaging and Images",
    "SKY": "Sky View, Targets and Framing", "SOL": "Plate Solving and Alignment", "FOC": "Focus", "GUI": "Guiding",
    "FLP": "Meridian Flip", "SES": "Sessions", "SCH": "Scheduler", "CAL": "Calibration", "SAF": "Safety",
    "EMG": "Emergency Stop", "LIB": "Library and History", "NOT": "Notifications", "CTL": "Roles, Lease and Control Safety",
    "CFG": "No Configuration", "STR": "Streamed Data", "LAY": "Layouts, Theme and Accessibility",
    "PLT": "Platform, Build and Release", "QST": "Meta Quest Edition",
}

gaps = {"android": 0, "quest": 0}
ios_missing = 0
sn = 2
for area in areas:
    a(f"## {sn}. MAPP-{area} — {TITLES[area]}\n")
    sn += 1
    a("| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |")
    a("|---|---|---|---|---|---|---|---|---|---|")
    for r in [x for x in rows if x["area"] == area]:
        rid = r["id"]
        ap = applies(r["plat"])
        if area == "QST":
            shared = QST_SHARED.get(r["num"], "—")
            ios_c = "—"
        else:
            shared, ios_c = OVR.get(rid, (SHARED[area], IOS[area]))
        cell = {}
        cell["ios"] = ios_c if "ios" in ap else "—"
        if "ios" in ap and ios_c in ("", None):
            ios_missing += 1
        for p in ("android", "quest"):
            if p in ap:
                cell[p] = "GAP"
                gaps[p] += 1
            else:
                cell[p] = "—"
        method = METHOD_OVR.get(rid, METHOD[area])
        a(f"| {rid} | {short(r['text'])} | {r['plat']} | {r['pri']} | {shared} | {cell['ios']} | {cell['android']} | {cell['quest']} | TC-{rid} | {method} |")
    a("")

a("---\n")
a(f"## {sn}. Coverage Summary\n")
by_pri = {}
for r in rows:
    by_pri[r["pri"]] = by_pri.get(r["pri"], 0) + 1
a(f"- Requirements in the SRS: **{len(rows)}** ({', '.join(f'{k} {by_pri[k]}' for k in ('MVP', 'P2', 'P3'))}).")
ios_n = sum(1 for r in rows if "ios" in applies(r["plat"]))
a(f"- Applying to **iOS/iPadOS**: {ios_n}; all have a design component in the [iOS SDD](ios/SDD.md).")
a(f"- Applying to **Android**: {gaps['android']} — all `GAP` until `docs/android/SDD.md` is written.")
a(f"- Applying to **Meta Quest**: {gaps['quest']} — all `GAP` until `docs/quest/SDD.md` is written; the shared layer already carries the streamed sky, image and control-safety parts (Shared column).")
a("- Requirements with no shared component are platform-only (permissions, packaging, theming, notifications) or Quest-only presentation.")
a("- No test cases exist yet; the `TC-` identifiers are reserved.\n")
a("*This matrix is regenerated from the SRS when the SRS changes; hand edits belong in the generator's override tables, not in this file.*")

(root / "docs" / "RTM.md").write_text("\n".join(out) + "\n", encoding="utf-8")
print(len(rows), "rows;", by_pri, "ios", ios_n, "android", gaps["android"], "quest", gaps["quest"])
