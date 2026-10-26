# Galileo MobileConnect

Native **iOS/iPadOS and Android** client apps for [Galileo](https://github.com/gordtulloch/Galileo), the cross-platform astrophotography imaging application. The apps let you watch and operate a running Galileo observatory from a **tablet** (the primary target) or a phone — connect equipment, find and centre targets, focus, guide, run and author sessions and schedules, and respond to safety events — without going back to the computer. They **operate** an observatory; they never **configure** one.

> **Status: In Design.** There is no code yet. The scope document is written and under review; requirements, design and the first build follow its approval. See [Status](#status).

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
- The server side is the **MobileConnect plugin**, built in [Galileo-Plugins](https://github.com/gordtulloch/Galileo-Plugins); its scope is in the Galileo repository at `docs/plugins/mobileconnect/PSD.md`. The wire API (OpenAPI plus an event schema) is the contract between the two.
- The browser equivalent is [Galileo WebConnect](https://github.com/gordtulloch/Galileo-WebConnect).

## Repository layout (planned)

This is one monorepo for both platforms, sharing as much as possible below the UI.

```
docs/                PSD, SRS and RTM (common to both apps); SDD.md = shared architecture
docs/ios/SDD.md      iOS/iPadOS design
docs/android/SDD.md  Android design
shared/              Kotlin Multiplatform: model, state, API client, session/schedule logic
ios/                 Swift / SwiftUI app
android/             Kotlin / Jetpack Compose app
api/                 pinned copy of the Galileo wire-API description
design/              design tokens, icons, strings
```

## Documents

| Document | Scope | Status |
|---|---|---|
| [PSD](docs/PSD.md) | Both apps | Draft v0.1 — for review |
| SRS | Both apps (common) | Not yet written |
| RTM | Both apps (common) | Not yet written |
| SDD (shared) | Shared architecture | Not yet written |
| SDD (iOS), SDD (Android) | One per platform | Not yet written |

## Status

| | |
|---|---|
| Stage | **In Design** |
| Done | Scope document (PSD) |
| Next | Wire-API and Control Surface definition in Galileo; SRS; wireframes (tablet first); shared-layer spike |
| Depends on | Galileo core additions *Connect Link* and *Control Surface* (proposed; see the Galileo PSDs) |

## Licence

The apps are **not open source**: proprietary, and free of charge initially. The Galileo MobileConnect plugin and the wire API they use are open (GPL-3.0-or-later and documented), so other clients are possible. A `LICENSE` file will be added before the first release.
