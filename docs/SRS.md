# Galileo MobileConnect Apps — SRS (Software Requirements Specification)

| | |
|---|---|
| **Project** | Galileo MobileConnect — iOS/iPadOS, Android and Meta Quest apps |
| **Document** | SRS (Software Requirements Specification), v0.1, draft — **common to all platforms** |
| **Status** | Draft — for review |
| **Date** | 2026-10-28 |
| **Upstream document** | [PSD (Project Scope Document)](PSD.md), v0.8 |
| **Downstream documents** | RTM (common); SDD: shared architecture, iOS, Android, Meta Quest (PSD Section 10) — not yet written |
| **Server-side counterpart** | MobileConnect plugin PSD, `docs/plugins/mobileconnect/PSD.md` in the [Galileo](https://github.com/gordtulloch/Galileo) repository |

---

## 1. Introduction

### 1.1 Purpose

This SRS decomposes the `MAPP` requirement domain of [the PSD](PSD.md) into individually numbered, verifiable requirements for the Galileo MobileConnect apps. It is **common to all platforms**: one set of requirements describes one product, which is what makes the behavioural parity the PSD promises (G3) checkable. The RTM maps each requirement to its design on each platform it applies to.

### 1.2 Scope

The `MAPP` domain only — the **client**. What the Master offers (the operations, the Control Surface, the lease, proxying, streaming, previews) is specified for the server side in the Galileo repository and is cited here, never restated. A requirement that says the app "shall show X the Master reports" depends on the Master reporting it; the Galileo-side work is listed in Section 2.6.

### 1.3 Requirement ID Convention

`MAPP-<AREA>-<###>`, numbered in increments of 10 within each area, so that requirements can be inserted without renumbering. IDs are never reused.

### 1.4 Priority Convention

| Priority | Meaning |
|---|---|
| **MVP** | Required for the first release of the platform it applies to |
| **P2** | Committed scope, delivered after the MVP operating screens (PSD R4); not optional |
| **P3** | Desirable; delivered if the work and demand justify it |

### 1.5 Platform Column

| Value | Applies to |
|---|---|
| **All** | iOS/iPadOS, Android and Meta Quest |
| **Mobile** | iOS/iPadOS and Android (not Quest) |
| **iOS**, **Android** | That platform only |
| **Quest** | The Meta Quest edition only |

The Meta Quest edition is built from the Android app and its panel screens (PSD Q-1), so "All" requirements are met on Quest through the panel mode unless a `MAPP-QST` requirement says otherwise.

### 1.6 References

[PSD](PSD.md); [Meta Quest VR analysis](analysis/QuestVR.md); the Galileo repository's MobileConnect, NodeConnect and WebConnect plugin PSDs (`docs/plugins/`); [Galileo Core SRS](https://github.com/gordtulloch/Galileo/blob/main/docs/SRS.md) (`OBS-150`, `SES-340`, `SKY-*`, `SAFE-*`, `PLUG-*`).

---

## 2. Overall Description

### 2.1 Product Perspective

Native client apps for iOS/iPadOS and Android, and an edition of the Android app for Meta Quest, that operate a Galileo observatory through a **Master** node's MobileConnect plugin over the Connect Link. The app is a second front end to Galileo's domain services; every operation it issues is carried out, and may be refused, by the Master.

### 2.2 Product Functions (Summary)

Pair with a Master; show every Pier of the observatory live; operate equipment, imaging, sky and framing, solving, focus, guiding, flips, sessions and the scheduler; show safety state and give an always-reachable emergency stop; browse the Library; and, on Meta Quest, present the sky and images in immersive form. It never configures.

### 2.3 User Classes and Characteristics

| Class | Characteristics |
|---|---|
| **Operator** | The observatory's owner or a trusted user, who may operate equipment (PSD Section 8) |
| **Viewer** | A guest who may look and not touch |

The role is granted by the Master per pairing; the app reflects it (`MAPP-CTL-010`).

### 2.4 Operating Environment

iOS/iPadOS and Android tablets and phones (minimum versions `MAPP-PLT-010`); Meta Quest 2 as the primary headset, others best-effort (`MAPP-QST-010`, `-020`). The app reaches the Master over the user's local network or their own VPN. Immersive streaming requires a PC/Mac-class Master (PSD Q-14).

### 2.5 Design and Implementation Constraints

The constraints of PSD Section 9 (PC1–PC10) apply: native tooling per platform; encrypted, authenticated traffic only; no state the node depends on; versioned wire API; node-rendered previews; Quest 2 sets the headset performance budget; no Google services on Quest.

### 2.6 Assumptions and Dependencies

The following **Galileo-side** capabilities are assumed and are not yet implemented. The apps' requirements that rely on them cannot be verified end to end until they exist; until then they are verified against the mock Master (`MAPP-CON-110`).

| Capability | Where specified | Needed by |
|---|---|---|
| Connect Link: pairing with approval, token, pinned certificate, event stream, capability and version handshake | NodeConnect PSD §6.2 | `CON`, `OVR`, `PLT` |
| Control Surface: the catalogue of operations and state; the control lease; operation events; deadman for held motion | MobileConnect plugin PSD §6.3 (C1–C3, C5) | `DEV`, `SES`, `SCH`, `CTL`, `EMG` |
| Server-side previews and the tile pyramid | Plugin PSD C4 | `IMG`, `QST-170` |
| Manual focus owned by the focus module | Plugin PSD C6 | `FOC` |
| Sky-data streaming service; PC/Mac-class Master for VR | Plugin PSD C7, PSD Q-13/Q-14 | `STR`, `SKY`, `QST` |
| Proxying of spoke nodes through the Master, with the per-node grant | Plugin PSD §6.6; WebConnect PSD §6.3 | `OVR-010`, `OVR-040` |

---

## 3. Functional Requirements

### 3.1 `MAPP-CON` — Connection and Pairing

How the app finds, pairs with and stays connected to the Master (PSD G6, G7; Connect Link L2). The app only ever talks to the Master.

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-CON-010 | All | The app shall discover Masters on the local network (Bonjour on iOS, network service discovery on Android and Quest) and list them by name, so that the user can choose one without typing an address. | MVP |
| MAPP-CON-020 | All | The app shall let the user enter a Master's address manually, shall validate it before any request is made, and shall report an unusable address in plain words. | MVP |
| MAPP-CON-030 | All | The app shall pair with a Master by sending a pairing request that shows a short code, then waiting for a person to approve the request on the Master. It shall show the request's state (waiting, approved, refused, timed out), shall not offer any operation or state before approval, and shall obtain its token only on approval. | MVP |
| MAPP-CON-040 | All | The app shall keep its token only in the platform's secure storage (iOS Keychain; Android Keystore, including on Quest) and shall never write it to a log, a crash report or the screen. | MVP |
| MAPP-CON-050 | All | The app shall pin the certificate the Master presented at pairing. If a later connection presents a different certificate, the app shall refuse it, say that the Master's identity has changed, and offer to pair again; it shall never fall back to an unverified connection. | MVP |
| MAPP-CON-060 | All | When the Master revokes the app's pairing, the app shall detect it on the next contact, discard its token and return to the unpaired state with an explanatory message. | MVP |
| MAPP-CON-070 | All | After the operating system suspends the app or the connection drops, the app shall reconnect automatically when it can, resynchronise all displayed state from the Master, and show the connection state (connected, reconnecting, unreachable) at all times. | MVP |
| MAPP-CON-080 | All | The app shall perform a version handshake on connecting, exchanging the app version and wire-API version for the Master's. On a mismatch it shall say whether the app or the Galileo node needs updating, and shall not attempt operations it cannot be sure the Master understands. | MVP |
| MAPP-CON-090 | All | The app shall learn from a capability handshake which features the Master offers, and shall present a feature the Master does not offer as unavailable with the Master's reason (for example, immersive streaming on a Master that is not PC/Mac-class), never as an error. | MVP |
| MAPP-CON-100 | All | The app shall let the user unpair from the app, discarding the token, and shall tell them that the pairing should also be removed on the Master. | MVP |
| MAPP-CON-110 | All | The app shall offer a demonstration mode that connects to a scripted mock Master, clearly labelled as such, for store reviewers, first-time users and development (PSD Q4). | MVP |
| MAPP-CON-120 | All | The app shall support being paired with more than one Master (for example a home observatory and a remote one) and shall let the user choose which to connect to. | P2 |

### 3.2 `MAPP-OVR` — Observatory Overview and Live State

What the user sees on opening the app (PSD G7, G8).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-OVR-010 | All | The app shall list every Observatory and Pier available through the connected Master, including Piers on spoke nodes reached through it, showing for each its node, its connection state and whether it can be operated or is view-only. | MVP |
| MAPP-OVR-020 | All | The app shall show, for each Pier, an activity timeline equivalent to the Galileo Dashboard (`OBS-150`): lanes for session, mount, meridian flip, guiding, plate solving, focusing and capture, and the guide error over time, populated from data supplied by the Master. | MVP |
| MAPP-OVR-030 | All | The app shall let the user choose the working Pier, shall show which Pier is working on every screen, and shall direct every Pier-specific screen and operation at it. | MVP |
| MAPP-OVR-040 | All | The app shall present a Pier that is unreachable, detached or not granted for operation as such, with the reason, and shall not show stale state as if it were live. | MVP |
| MAPP-OVR-050 | All | The app shall receive state, progress and alerts as events pushed by the Master and shall not poll for them in a loop. | MVP |
| MAPP-OVR-060 | All | The app shall show recent alerts with their severity and time, and shall show when a new alert arrives while the app is connected. | MVP |
| MAPP-OVR-070 | All | The app shall show a state of the observatory within 5 seconds of connecting (value to be confirmed, Section 5). | MVP |

### 3.3 `MAPP-DEV` — Devices

Operation of connected equipment. The app never edits a device's definition or address (`MAPP-CFG`).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-DEV-010 | All | The app shall connect and disconnect each device of the working Pier, and all of them together, and show each device's state (connected, busy, error). | MVP |
| MAPP-DEV-020 | All | The app shall operate the mount: slew to coordinates or a selected object, sync, tracking on/off and rate, park, unpark, find home, and pulse moves. | MVP |
| MAPP-DEV-030 | All | The app shall provide held manual movement of the mount (jog) that moves only while the user holds the control, sends keep-alives to the Master while held, and stops when the control is released, the app leaves the foreground, or the connection is lost (`MAPP-CTL-060`). | MVP |
| MAPP-DEV-040 | All | The app shall operate the camera: cooling on/off and target temperature, and per-exposure gain, offset, binning and region of interest, start and abort. | MVP |
| MAPP-DEV-050 | All | The app shall operate the filter wheel (select filter by name) and the rotator (move to a position angle). | MVP |
| MAPP-DEV-060 | All | The app shall operate the dome (open, close, slew, follow mount), flat panel (on/off, brightness) and switches (toggle), where the Pier has them. | P2 |
| MAPP-DEV-070 | All | The app shall show weather and environment readings the Master reports for the Pier. | P2 |

### 3.4 `MAPP-IMG` — Imaging and Images

Capture and the display of images (PSD G5, PC7). The app handles node-rendered previews, never FITS processing.

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-IMG-010 | All | The app shall start single exposures and exposure series with per-run parameters, show progress, and abort a running exposure or series. | MVP |
| MAPP-IMG-020 | All | The app shall run a live exposure loop and display each result as it arrives. | MVP |
| MAPP-IMG-030 | All | The app shall display the current frame as a preview rendered by the Master, scaled to the device, with pinch-to-zoom and pan, loading further detail as tiles when the user zooms. | MVP |
| MAPP-IMG-040 | All | The app shall let the user adjust the preview stretch interactively and shall show the histogram. | MVP |
| MAPP-IMG-050 | All | The app shall show the image statistics the Master reports for the frame: star count, HFR and FWHM. | MVP |
| MAPP-IMG-060 | All | The app shall show the live-stack result and its progress. | P2 |
| MAPP-IMG-070 | All | The app shall let an Operator reject a frame. | P2 |
| MAPP-IMG-080 | All | The app shall let the user download a full FITS frame as an explicit action, with its size shown first and the transfer cancellable and resumable. | P2 |
| MAPP-IMG-090 | All | The app shall adapt preview size and quality to the quality of the link, and shall offer a data-saver setting. | P2 |

### 3.5 `MAPP-SKY` — Sky View, Targets and Framing

The sky as drawn on the tablet and phone (the immersive form is `MAPP-QST`). Data is streamed from the Master (`MAPP-STR`).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-SKY-010 | All | The app shall draw an interactive sky view for the Observatory's location and the chosen time, showing the horizon profile, the mount's current position and the field of view of the Pier's optical train, rendered from data streamed from the Master. | MVP |
| MAPP-SKY-020 | All | The app shall let the user choose the date and time shown, including moving through the night, and shall return to the present on request. | MVP |
| MAPP-SKY-030 | All | The app shall let the user search for and select a target and shall show its details, altitude and visibility as reported by the Master. | MVP |
| MAPP-SKY-040 | All | The app shall let an Operator who holds the lease slew the mount to a selected target. Before the request is sent the app shall show a confirmation that names the target, its current altitude and the Master's horizon and limit check, and shall send nothing if the user does not confirm. | MVP |
| MAPP-SKY-050 | All | The app shall provide the Framing Assistant: the sensor field of view for the Pier's optical train at a chosen rotation, mosaic panel layout, and the ability to use the result in a session or schedule job. | P2 |
| MAPP-SKY-060 | All | The app shall show the Master's ranked target lists (What's Up Tonight) and altitude curves for a chosen date. | P2 |

### 3.6 `MAPP-SOL` — Plate Solving and Alignment

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-SOL-010 | All | The app shall solve the current or last frame, show the result, and centre the mount on a target by plate solving. | MVP |
| MAPP-SOL-020 | All | The app shall sync the mount to a solved position when the user asks. | MVP |
| MAPP-SOL-030 | All | The app shall run a polar alignment and display its progress and the resulting error. | P2 |

### 3.7 `MAPP-FOC` — Focus

Manual and automatic focus, both from the Master's focus module (`MAPP` follows the plugin PSD C6).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-FOC-010 | All | The app shall provide manual focus using the operations the Master publishes from its focus module: move in or out by a chosen step, move to an absolute position, and halt, with the current position and temperature shown. | MVP |
| MAPP-FOC-020 | All | The app shall start and cancel an autofocus run and show its progress, the V-curve and the result, and the state of temperature compensation. | MVP |
| MAPP-FOC-030 | All | The app shall not move a focuser by any means other than the focus module's operations. | MVP |

### 3.8 `MAPP-GUI` — Guiding

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-GUI-010 | All | The app shall connect and disconnect the guider service, start and stop guiding, dither and recalibrate. | MVP |
| MAPP-GUI-020 | All | The app shall display the guide error trace and the guide-star cut-out the Master supplies. | MVP |

### 3.9 `MAPP-FLP` — Meridian Flip

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-FLP-010 | All | The app shall show the meridian-flip state and the time remaining to the flip for the working Pier. | MVP |
| MAPP-FLP-020 | All | The app shall let an Operator who holds the lease trigger or defer a flip when the Master's flip service allows it, after a confirmation, and shall show the outcome. | MVP |

### 3.10 `MAPP-SES` — Sessions

Running and authoring Sessions, using the Sessions model of the Master (PSD G5, Q2 of the plugin PSD).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-SES-010 | All | The app shall list the saved sessions of the Master and load one. | MVP |
| MAPP-SES-020 | All | The app shall run, pause, resume, skip a step of, and stop a session, with a confirmation for stop. | MVP |
| MAPP-SES-030 | All | The app shall show the progress of a running session block by block, including nested blocks, the current exposure and the time remaining. | MVP |
| MAPP-SES-040 | All | The app shall let the user author and edit a session: add, remove, reorder and nest instruction, condition and trigger blocks, and set each block's parameters, using the block types and validation the Master supplies, including blocks registered by plugins (`SES-340`). | P2 |
| MAPP-SES-050 | All | The app shall save an authored session to the Master and shall report the Master's validation messages without discarding the user's work. | P2 |
| MAPP-SES-060 | All | The app shall import and export a session as a `.gses` interchange file. | P2 |
| MAPP-SES-070 | All | The app shall prevent or clearly constrain editing of a session while it is running, following the rule the Master reports. | P2 |
| MAPP-SES-080 | Quest | The Meta Quest edition shall offer session authoring through the panel mode, on the tablet layout. | P2 |

### 3.11 `MAPP-SCH` — Scheduler

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-SCH-010 | All | The app shall show the Master's job queue and the next job to run. | MVP |
| MAPP-SCH-020 | All | The app shall let the user create, edit and delete a scheduler job, including adding a job from a target selected in the sky view. | P2 |
| MAPP-SCH-030 | All | The app shall let the user enable, disable, reorder and run-now a job. | P2 |

### 3.12 `MAPP-CAL` — Calibration

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-CAL-010 | All | The app shall start dark, flat and bias sets and show their progress. | P2 |

### 3.13 `MAPP-SAF` — Safety

The node's safety service is the authority (PSD G9).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-SAF-010 | All | The app shall show every safety input and the overall safe or unsafe state, and a compact indicator of that state on every screen. | MVP |
| MAPP-SAF-020 | All | The app shall let an Operator acknowledge a safety event, and shall offer no other action on one. | MVP |
| MAPP-SAF-030 | All | The app shall show the reason a session stopped. | MVP |
| MAPP-SAF-040 | All | The app shall provide no control that disables the safety monitor, clears an unsafe state, resumes after a safety stop, or overrides a mount limit. | MVP |

### 3.14 `MAPP-EMG` — Emergency Stop

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-EMG-010 | All | The app shall provide a stop-everything, park and close-dome action reachable from every screen in no more than two interactions, protected against accidental activation by a deliberate gesture or a single confirmation. | MVP |
| MAPP-EMG-020 | All | The emergency actions shall be available to any paired Operator without taking the lease, and the app shall show the outcome, including any part that failed. | MVP |

### 3.15 `MAPP-LIB` — Library and History

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-LIB-010 | All | The app shall browse recent sessions and frames as thumbnails with their quality metrics. | P2 |
| MAPP-LIB-020 | All | The app shall show a frame preview and session history charts. | P2 |

### 3.16 `MAPP-NOT` — Notifications

No background push in v1 (PSD Section 5).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-NOT-010 | Mobile | While the app is connected, including when it is in the background but still running, the app shall present an operating-system notification for alerts the Master marks as important. | P2 |

### 3.17 `MAPP-CTL` — Roles, Lease and Control Safety

The Master is the authority; the app shows and honours its decisions (PSD G9; plugin PSD §6.5).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-CTL-010 | All | The app shall show the role (Viewer or Operator) the Master gave it, and as a Viewer shall offer no control that issues an operation. | MVP |
| MAPP-CTL-020 | All | The app shall let an Operator take, hold and release a Pier's control lease, shall show who holds it, and shall offer to request it, with a confirmation, when another client holds it. | MVP |
| MAPP-CTL-030 | All | When the Master refuses an operation because another client holds the lease, or for any other reason, the app shall show the Master's reason and shall not retry on its own. | MVP |
| MAPP-CTL-040 | All | The app shall ask for confirmation before any destructive or hard-to-reverse operation: abort an exposure series, stop a session, park or unpark, run a flip, reject a frame, delete a job or session. | MVP |
| MAPP-CTL-050 | All | The app shall treat the Master's response as the result of an operation and shall not show an operation as done on the strength of having sent it. | MVP |
| MAPP-CTL-060 | All | For any held operation the app shall send keep-alives while the control is held and shall stop the operation, by ceasing them, when the control is released, the app leaves the foreground, or the connection is lost. The Master stops the motion within 1 second of the keep-alives ceasing (value to be confirmed, Section 5). | MVP |
| MAPP-CTL-070 | All | An operation in flight when the connection is lost shall be shown as outcome-unknown until the app resynchronises, and shall not be repeated automatically. | MVP |
| MAPP-CTL-080 | All | Every request the app sends shall carry the app's identity so that the Master can record who did what. | MVP |

### 3.18 `MAPP-CFG` — No Configuration

Configuration stays on the desktop or arrives through NodeConnect (PSD Section 5).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-CFG-010 | All | The app shall contain no screen, control or request that creates, edits or deletes equipment profiles, Piers, optical trains, device definitions or addresses, Observatories, site location, horizon, safety rules or limits, Options, notification rules, or plugin settings. | MVP |
| MAPP-CFG-020 | All | The app shall display Pier, optical-train and Observatory information read-only where it helps the user (for example the field-of-view source). | MVP |
| MAPP-CFG-030 | All | The app shall use only the documented wire API, with no route outside it (PSD Section 5), and shall hold no `config.*` permission. | MVP |

### 3.19 `MAPP-STR` — Streamed Data

The Master is the source of truth; the app renders (PSD Q-13).

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-STR-010 | All | The app shall obtain the star and deep-sky catalog from the Master by sky region, limiting magnitude and level of detail as the user looks, and shall ship and store no catalog. | MVP |
| MAPP-STR-020 | All | The app shall obtain positions of the Sun, Moon, planets and minor bodies from the Master for the displayed time, and a time range ahead of the user's movement through time, and shall compute no ephemeris itself. | MVP |
| MAPP-STR-030 | All | The app shall obtain the Observatory location, horizon profile, pointing and field of view from the Master. | MVP |
| MAPP-STR-040 | All | The app shall display streamed sky data progressively, brightest objects first, so that a usable view appears before all detail has arrived. | MVP |
| MAPP-STR-050 | All | The app shall keep a bounded, short-lived cache of streamed data to ride out dropouts and avoid re-requesting a region the user is looking at, and shall hold no persistent catalog; the size and lifetime are set in the SDD (PSD Q9). | MVP |
| MAPP-STR-060 | All | When the link to the Master is down, the app shall say so within the view and shall not present cached data as live. | MVP |
| MAPP-STR-070 | All | The app shall conform to the Master's compact streaming protocol and shall respond to the Master's per-client rate and region limits by reducing its requests, without error. | MVP |

### 3.20 `MAPP-LAY` — Layouts, Theme and Accessibility

PSD G4, G10, PC9.

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-LAY-010 | All | The app shall provide tablet layouts that show several panels side by side (for example the sky view, the image and the session), in landscape and portrait. | MVP |
| MAPP-LAY-020 | All | The app shall provide phone layouts that drop or condense secondary panels; which panels are dropped is decided from tablet wireframes (PSD Q1) and recorded in the SDD. | MVP |
| MAPP-LAY-030 | All | The app shall provide a night-vision theme that uses red only, applies to every screen and dialog, and is selectable and remembered; the app's default brightness shall be low. | MVP |
| MAPP-LAY-040 | All | The app shall support dynamic type or font scaling, screen-reader labels on every control, and sufficient contrast in every theme. | MVP |
| MAPP-LAY-050 | All | Touch targets shall be sized to be used reliably in cold conditions and with gloves. | MVP |
| MAPP-LAY-060 | All | The app shall externalise every user-visible string into a shared resource so that both platforms use the same wording (PSD G3). English is provided first; further languages follow those of the Galileo desktop application. | P2 |

### 3.21 `MAPP-PLT` — Platform, Build and Release

PSD PC1, PC2, PC6, PC8, G3, G11.

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-PLT-010 | Mobile | The iOS/iPadOS app shall run on iOS/iPadOS 17 or later, and the Android app on Android 10 (API 29) or later (to be confirmed against the device base, Section 5). | MVP |
| MAPP-PLT-020 | All | The apps shall be built with each platform's native UI toolkit (SwiftUI on iOS, Jetpack Compose on Android), sharing a model and logic layer, a generated API client, design tokens, icons and strings below the UI (PSD G1, G2). | MVP |
| MAPP-PLT-030 | All | Each feature shall be released on all platforms together, or recorded as staged in the release notes with the reason; a parity checklist shall be completed for each release. | MVP |
| MAPP-PLT-040 | All | The apps shall pass one shared suite of wire-protocol test vectors on every platform. | MVP |
| MAPP-PLT-050 | Mobile | The apps shall be distributed free of charge through the Apple App Store and Google Play, without advertising or in-app purchases. | MVP |
| MAPP-PLT-060 | All | The apps shall collect no analytics or usage data and shall declare in their store privacy information that no data leaves the user's own network. | MVP |
| MAPP-PLT-070 | iOS | The iOS app shall request the local-network permission with an explanation of why, and when it is refused shall explain how to grant it and which features are unavailable. | MVP |
| MAPP-PLT-080 | Android | The Android app shall request only the permissions it needs and shall behave within Android's background-execution and foreground-service rules. | MVP |
| MAPP-PLT-090 | All | When the app receives a response showing the Master is newer than it understands, it shall tell the user to update the app and shall link to the right place for the platform's distribution channel. | MVP |
| MAPP-PLT-100 | All | The app shall be proprietary software; its licence shall be added before the first release (PSD Section 5). | MVP |

### 3.22 `MAPP-QST` — Meta Quest Edition

PSD Section 4a. The Quest 2 is the primary target and acceptance device (Q-12). Requirements marked P2/P3 are committed scope staged after the Quest MVP.

| ID | Platforms | Requirement | Priority |
|---|---|---|---|
| MAPP-QST-010 | Quest | The Quest edition shall install and run on a Meta Quest 2 on a current Meta Horizon OS release, which is the acceptance device for every Quest requirement. | MVP |
| MAPP-QST-020 | Quest | The Quest edition shall run, without Quest 3-specific features, on Meta Quest 3, 3S and Pro headsets, which receive no specific support in v1. | MVP |
| MAPP-QST-030 | Quest | The Quest edition shall offer a panel mode in which every operating screen of the tablet layout is available as one or several floating 2D windows the user can place and resize around the room. | MVP |
| MAPP-QST-040 | Quest | The Quest edition shall offer an immersive mode, entered from the panels, in which the planetarium and image viewer fill the user's view while panels remain available alongside. | MVP |
| MAPP-QST-050 | Quest | The Quest edition shall be operable with the controllers and with hand tracking, shall make every operation reachable without a touch screen, shall size controls for pointing at a distance, and shall provide a virtual keyboard for text. | MVP |
| MAPP-QST-060 | Quest | In the Quest edition a held operation shall require holding a controller button, never a hand gesture, and shall stop when the button is released, tracking is lost or the headset is removed (`MAPP-CTL-060`). | MVP |
| MAPP-QST-070 | Quest | The Quest edition shall make the headset's camera passthrough available at all times with one gesture or button. | MVP |
| MAPP-QST-080 | Quest | The Quest edition shall show a first-run safety notice — use indoors or seated, never while walking at the telescope — and shall not open an immersive scene until the user has acknowledged it. | MVP |
| MAPP-QST-090 | Quest | When the headset is removed or goes to sleep the Quest edition shall pause its scenes and stop any held operation, and on wearing again shall resynchronise from the Master. | MVP |
| MAPP-QST-100 | Quest | The Quest edition shall depend on no Google Play Services, Firebase or other component absent from Meta Horizon OS. | MVP |
| MAPP-QST-110 | Quest | The immersive planetarium shall draw the sky as a dome around the user for the Observatory's location and chosen time, with stars and deep-sky objects, the Sun, Moon, planets and minor bodies, and the horizon profile, from data streamed from the Master (`MAPP-STR`). | MVP |
| MAPP-QST-120 | Quest | The immersive planetarium shall show the mount's current pointing and the field of view of the Pier's optical train, updated live. | MVP |
| MAPP-QST-130 | Quest | The immersive planetarium shall let the user move through time, in which the sky moves around the user and the user does not move. | MVP |
| MAPP-QST-140 | Quest | The immersive planetarium shall let the user select an object by pointing, and shall slew the mount to it only as `MAPP-SKY-040` requires (Operator, lease, confirmation panel with the horizon and limit check). | MVP |
| MAPP-QST-150 | Quest | The immersive planetarium shall have a mode in which stars stay legible using only the night-vision palette, and shall default to a low brightness. | MVP |
| MAPP-QST-160 | Quest | The immersive scenes shall hold on a Meta Quest 2 the frame rate recorded in the Quest SDD, with no visible hitching during head movement, using level-of-detail by magnitude; the frame rate and star budget are to be set from the successful spike's measurements (Section 5). | MVP |
| MAPP-QST-170 | Quest | The immersive image viewer shall show the current exposure, the live-stack result or any Library frame as a large virtual display, with pan, zoom and stretch, loading node-rendered tiles progressively. | MVP |
| MAPP-QST-180 | Quest | The immersive image viewer shall compare two frames by blinking between them and side by side. | P2 |
| MAPP-QST-190 | Quest | The Quest edition shall move the user's viewpoint only when the user asks, and shall keep the horizon level. | MVP |
| MAPP-QST-200 | Quest | When the Master reports that it cannot serve immersive streaming (it is not PC/Mac-class), the Quest edition shall say so, disable the immersive scenes, and keep the panels available (`MAPP-CON-090`). | MVP |
| MAPP-QST-210 | Quest | The Quest edition shall be distributed through the Meta Horizon Store and also as a self-contained package for side-loading through SideQuest, published on the Galileo website with a version number and a checksum, signed with a stable release key, and depending on no store service for installation, licensing or updates. | MVP |
| MAPP-QST-220 | Quest | The Quest edition shall not update itself; when the Master expects a newer app it shall say so and point to the download page. | MVP |
| MAPP-QST-230 | Quest | The Quest edition shall be accompanied by a side-loading guide covering developer mode, SideQuest and the package. | MVP |
| MAPP-QST-240 | Quest | The Quest edition shall place the Framing Assistant on the dome: the sensor rectangle can be grasped, moved, rotated and sized to the optical train, and mosaic panels laid out against the real sky. | P2 |
| MAPP-QST-250 | Quest | The Quest edition shall show the What's Up Tonight targets and altitude arcs across the dome for a chosen night. | P2 |
| MAPP-QST-260 | Quest | The Quest edition shall show a per-Pier activity timeline as floating panels, and an ambient safety indication (a coloured horizon ring) visible from any scene. | P2 |
| MAPP-QST-270 | Quest | The Quest edition shall overlay the live all-sky camera image on the dome where the Master has one. | P3 |
| MAPP-QST-280 | Quest | The Quest edition shall show a three-dimensional model of the mount, tube and dome following the live pointing and playing out a meridian flip as it happens, labelled as a visualisation without collision claims. | P3 |
| MAPP-QST-290 | Quest | The Quest edition shall show the guiding and focus plots, the plate-solve overlay and the polar-alignment error as floating or dome-attached diagnostics. | P3 |

---

## 4. Requirement Summary Counts (for RTM Seeding)

| Area | Requirements | MVP | P2 | P3 |
|---|---|---|---|---|
| MAPP-CON | 12 | 11 | 1 | 0 |
| MAPP-OVR | 7 | 7 | 0 | 0 |
| MAPP-DEV | 7 | 5 | 2 | 0 |
| MAPP-IMG | 9 | 5 | 4 | 0 |
| MAPP-SKY | 6 | 4 | 2 | 0 |
| MAPP-SOL | 3 | 2 | 1 | 0 |
| MAPP-FOC | 3 | 3 | 0 | 0 |
| MAPP-GUI | 2 | 2 | 0 | 0 |
| MAPP-FLP | 2 | 2 | 0 | 0 |
| MAPP-SES | 8 | 3 | 5 | 0 |
| MAPP-SCH | 3 | 1 | 2 | 0 |
| MAPP-CAL | 1 | 0 | 1 | 0 |
| MAPP-SAF | 4 | 4 | 0 | 0 |
| MAPP-EMG | 2 | 2 | 0 | 0 |
| MAPP-LIB | 2 | 0 | 2 | 0 |
| MAPP-NOT | 1 | 0 | 1 | 0 |
| MAPP-CTL | 8 | 8 | 0 | 0 |
| MAPP-CFG | 3 | 3 | 0 | 0 |
| MAPP-STR | 7 | 7 | 0 | 0 |
| MAPP-LAY | 6 | 5 | 1 | 0 |
| MAPP-PLT | 10 | 10 | 0 | 0 |
| MAPP-QST | 29 | 22 | 4 | 3 |
| **Total** | **135** | **106** | **26** | **3** |

---

## 5. Values and Decisions to Confirm

The requirements above contain a few values this SRS could not fix from the PSD. They are listed here so that review settles them rather than finding them buried in the text.

| # | Where | Item | Proposal |
|---|---|---|---|
| 1 | `OVR-070` | Time to first live state after connecting | 5 seconds |
| 2 | `CTL-060` | Time for the Master to stop a held motion after keep-alives cease | 1 second (the PSD's success criterion) |
| 3 | `PLT-010` | Minimum OS versions | iOS/iPadOS 17; Android 10 (API 29) (PSD Q3) |
| 4 | `QST-160` | Quest 2 frame rate and star budget | Set from the measurements of the successful Quest 2 spike (PSD §4a); not yet recorded |
| 5 | `STR-050` | Size and lifetime of the streamed-data cache | Minutes and bounded memory; exact values in the SDD (PSD Q9) |
| 6 | `LAY-020` | Which panels the phone layout drops | TBD from tablet wireframes (PSD Q1) |
| 7 | — | Trigger for Quest 3-specific support (PSD Q7) | Product owner to set a threshold on the stores' own install and usage figures; no in-app analytics |
| 8 | — | Holder of the Meta developer account and Horizon Store listing (PSD Q8) | To be confirmed |
| 9 | — | Whether the website package and the store build of the Quest app share a signing key | SDD decision (PSD Q-8) |

---

## 6. Path to SDD / Traceability Matrix

1. **SDD** — the shared-architecture SDD (`docs/SDD.md`), the iOS and Android SDDs, and the Meta Quest SDD (`docs/quest/SDD.md`), which records the spike's findings and sets the values in items 4 and 5 above.
2. **Traceability Matrix** — `docs/RTM.md`, a row per requirement ID here, mapped to its SDD component **on each platform in its Platforms column** and to a test case. A requirement with no design on a platform it applies to shows as a gap.

---

*This document is a living draft, subordinate to [the PSD](PSD.md) and parallel to the Galileo plugin SRS documents.*
