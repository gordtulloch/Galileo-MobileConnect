# Galileo MobileConnect

Native **iOS/iPadOS and Android** client apps — plus a **Meta Quest 2/3 edition** of the Android app — for [Galileo](https://github.com/gordtulloch/Galileo), the cross-platform astrophotography imaging application. The apps let you watch and operate a running Galileo observatory from a **tablet** (the primary target) or a phone — connect equipment, find and centre targets, focus, guide, run and author sessions and schedules, and respond to safety events — without going back to the computer. They **operate** an observatory; they never **configure** one.

> **Status: In Design** (a feasibility spike of the Meta Quest VR client on the Quest 2 has succeeded and the VR client is confirmed). The iOS app runs on the simulator against a scripted mock Master, with connection, pairing, the shell, safety and emergency, and the Overview, Equipment, Sky, Imaging, Focus, Solve, Guiding, Flip, Sessions and Safety screens: every MVP screen. It has also been tested against the draft MobileConnect plugin on Galileo's real server code with fake devices, and has paired with a mock Master from a real iPad, but hasn't yet run with real equipment. Android and Quest have not started. The design documents are drafted and under review. See [Status](#status).

---

## What it does

| Area | From the app |
|---|---|
| Overview | Live state and activity timelines for every Pier of the observatory |
| Equipment | Connect devices; mount, camera, filter wheel, rotator, dome, flat panel, switches |
| Imaging | Capture, live preview with stretch, histogram, star statistics |
| Sky | Planetarium view, target search, go-to, framing and mosaics |
| Solving and focus | Plate solve and centre; manual focus and autofocus (through Galileo's focus module); polar alignment |
| Guiding and flips | Start/stop guiding, dither; see and manage a meridian flip |
| Sessions and schedule | Run, pause and stop sessions; **author and edit** sessions and the multi-night schedule |
| Safety | See every safety input; acknowledge an event; one-touch stop and park |
| Library | Browse recent sessions and frames as previews; history and charts |

Not in the app: equipment profiles, Observatories, safety limits, Options and plugin settings. Those stay on the desktop (or reach other nodes through Galileo's NodeConnect).

## How it fits together

```
 iPad / iPhone / Android tablet or phone
        │   (this repository)
        │  encrypted, paired, token-authenticated — Galileo "Connect Link"
        ▼
 Galileo MASTER node ── MobileConnect plugin ── Control Surface ── Galileo core services
        │
        └── relays to paired spoke nodes (opt-in per node)
```

- The app talks only to the **Master** node and operates every paired node through it. A single-node installation is a Master with no spokes.
- **Pairing** is a request from the app, **approved on the Master**, which then issues the app a token. An unpaired app is refused.
- The server side is the **MobileConnect plugin**, drafted in [Galileo-Plugins](https://github.com/gordtulloch/Galileo-Plugins) and not published yet; its scope is in the Galileo repository at `docs/plugins/mobileconnect/PSD.md`. The wire API (`api/`: OpenAPI plus an event schema) is the contract between the two.
- The browser equivalent is [Galileo WebConnect](https://github.com/gordtulloch/Galileo-WebConnect).

## Repository layout

This is one monorepo for both platforms, sharing as much as possible below the UI. Not all of it exists yet (`tools/`, `design/`, `quest/`, `docs/android/` and `docs/quest/` are planned).

```
docs/                PSD, SRS and RTM (common to both apps); SDD.md = shared architecture
docs/ios/SDD.md      iOS/iPadOS design
docs/android/SDD.md  Android design
docs/quest/SDD.md    Meta Quest 2/3 design (immersive planetarium and image viewer)
docs/analysis/       analyses, e.g. QuestVR.md
api/                 the contract: openapi.yaml, events.schema.json, test vectors, mock-Master scenarios
scripts/             sync_api.py (copies the contract to each generator), gen_rtm.py
tools/mock-master/   standalone scripted Master for contract tests (planned)
ios/                 Swift / SwiftUI app; Packages/GalileoAPI (generated), Packages/GalileoCore (hand-written)
android/             Kotlin / Jetpack Compose app; api-client (generated), core (hand-written, pure Kotlin)
quest/               Meta Quest 2/3 edition (reuses android/core and the Android panel screens)
design/              design tokens, icons, strings
```

## Meta Quest edition

An edition for **Meta Quest** headsets (Android-based), with the **Quest 2 as the primary target and initial test device** — inexpensive second-hand headsets make a dedicated astronomy device realistic — and Quest 3-specific support added later according to use. Version 1 runs the operating screens as floating panels and adds two **immersive scenes** — a full-dome **planetarium** around you and a large **image viewer** — operated with controllers or hands, with camera passthrough one gesture away and no dependence on Google services. Its data is streamed from the Master, which must be a PC or Mac (a Raspberry Pi can be a paired spoke behind it). It has its own design document and is distributed through the Meta Horizon Store, with side-loading through SideQuest as a supported fall-back should the store no longer serve the headset. See the PSD, Section 4a, and the [VR opportunities analysis](docs/analysis/QuestVR.md).

## Documents

| Document | Scope | Status |
|---|---|---|
| [PSD](docs/PSD.md) | Both apps | Draft v0.1 — for review |
| [SRS](docs/SRS.md) | All platforms (common) | Draft v0.1 — for review |
| [RTM](docs/RTM.md) | Both apps (common) | Draft v0.1 — generated from the SRS by `scripts/gen_rtm.py`; Android and Quest columns are `GAP` |
| [SDD (shared)](docs/SDD.md) | Shared architecture | Draft v0.1 — for review |
| [SDD (iOS)](docs/ios/SDD.md) | iOS/iPadOS design | Draft v0.1 — for review |
| SDD (Android), SDD (Meta Quest) | One per platform | Not yet written |
| [Work checklist](docs/CHECKLIST.md) | What's done and what's next | Current |
| [Meta Quest VR analysis](docs/analysis/QuestVR.md) | Opportunities for the Quest edition | Draft — for review |

## Status

| | |
|---|---|
| Stage | **In development** (iOS); in design (Android, Quest) |
| Done | Scope document (PSD); SRS, RTM, shared SDD and iOS SDD drafted; iOS: Swift client generated from `api/openapi.yaml`; `GalileoCore` core with shared vectors; mock Master (in-process and standalone); app with the screens above, UI-tested on iPad and iPhone simulators; draft server plugin in [Galileo-Plugins](https://github.com/gordtulloch/Galileo-Plugins), tested end to end with this client (2026-11-06) |
| Next | The plugin with real equipment; what's left of the iOS MVP needs operations in core ([checklist](docs/CHECKLIST.md) section 5a) ([checklist](docs/CHECKLIST.md) section 5a); review of the drafts and the wire-contract differences in `docs/SDD.md` §4.2; run the Kotlin generator (needs a JDK); wireframes (tablet first); Quest SDD recording the VR spike's findings |
| Depends on | Galileo core's *Connect Link* and *Control Surface*, and the operations listed in `docs/SDD.md` §4.2 item 11 |

## Building the iOS app

On a Mac with Xcode 26, Python 3 and Node: open `ios/GalileoMobileConnect.xcodeproj` and run; *Try the demo* needs no observatory. To try the real network code, run the [standalone mock Master](tools/mock-master/README.md) and enter its address.

`scripts/check_ios.sh` runs every check; its header says what it needs. Change project settings in `ios/project.yml` and run `xcodegen` in `ios/` (`brew install xcodegen`). Change the contract in `api/` and run `scripts/sync_api.py`; change colours in `design/tokens.json` and run `scripts/gen_tokens.py`.

## Licence

The apps are **not open source**: proprietary, and free of charge initially. The Galileo MobileConnect plugin and the wire API they use are open (GPL-3.0-or-later and documented), so other clients are possible. `LICENSE` is a draft, to be reviewed before the first release.
