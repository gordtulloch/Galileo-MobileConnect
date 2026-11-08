# Galileo MobileConnect Apps — RTM (Requirements Traceability Matrix)

| | |
|---|---|
| **Project** | Galileo MobileConnect — iOS/iPadOS, Android and Meta Quest apps |
| **Document** | RTM, v0.1, draft — **common to all platforms** |
| **Status** | Draft — for review |
| **Date** | 2026-11-06 |
| **Upstream** | [SRS](SRS.md) v0.1, [Shared SDD](SDD.md) v0.1, [iOS SDD](ios/SDD.md) v0.1 |
| **Not yet written** | Android SDD, Meta Quest SDD — their columns read `GAP` until they exist |

---

## 1. How to Read This Matrix

One row per requirement in the [SRS](SRS.md), generated from it so the IDs cannot drift. Columns:

- **Platforms / Priority** — copied from the SRS.
- **Shared** — the `S-*` component of the [Shared SDD](SDD.md) that *specifies* it. Each `S-*` component is implemented once per platform (ADR-APP-001), so this column names the specification and the iOS/Android columns name where it is presented. `—` if the requirement is wholly platform-side.
- **iOS / Android / Quest** — the platform component that presents or implements it, per the platform's SDD. `—` means the requirement does not apply to that platform (SRS *Platforms* column). **`GAP`** means it applies but the platform's SDD has not been written yet, so the requirement has no design there (SRS Section 6); a gap is a to-do, not an omission.
- **Test case** — the reserved `TC-` identifier; **Method** — how it is verified.

| Method | Meaning |
|---|---|
| **U** | Unit test or replayed test vector (`S-TEST`, `api/vectors/`) run against each platform's own core implementation |
| **C** | Contract test of the client against the mock Master (and, once available, the Galileo plugin) |
| **UI** | Automated UI test of the platform app against the mock Master |
| **D** | Manual verification on a physical device (iPad, Android tablet, Quest 2) or with a real Master |
| **R** | Review or inspection (build configuration, store metadata, design tokens) |

Test-case identifiers follow `TC-MAPP-<AREA>-<###>`, the requirement's number reused. No test exists yet.

---

## 2. MAPP-CON — Connection and Pairing

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-CON-010 | The app shall discover Masters on the local network (Bonjour on iOS, network service discovery on Android and Quest) and list them by name, so… | All | MVP | S-LINK | I-CONN | GAP | GAP | TC-MAPP-CON-010 | D |
| MAPP-CON-020 | The app shall let the user enter a Master's address manually, shall validate it before any request is made, and shall report an unusable address… | All | MVP | S-LINK | I-CONN | GAP | GAP | TC-MAPP-CON-020 | C, UI |
| MAPP-CON-030 | The app shall pair with a Master by sending a pairing request that shows a short code, then waiting for a person to approve the request on the Master | All | MVP | S-LINK | I-CONN | GAP | GAP | TC-MAPP-CON-030 | C, UI |
| MAPP-CON-040 | The app shall keep its token only in the platform's secure storage (iOS Keychain; Android Keystore, including on Quest) and shall never write it… | All | MVP | S-SEC, S-LINK | I-TRUST | GAP | GAP | TC-MAPP-CON-040 | C, UI |
| MAPP-CON-050 | The app shall pin the certificate the Master presented at pairing | All | MVP | S-LINK, S-SEC | I-TRUST | GAP | GAP | TC-MAPP-CON-050 | C, UI |
| MAPP-CON-060 | When the Master revokes the app's pairing, the app shall detect it on the next contact, discard its token and return to the unpaired state with an… | All | MVP | S-LINK | I-CONN, I-TRUST | GAP | GAP | TC-MAPP-CON-060 | C, UI |
| MAPP-CON-070 | After the operating system suspends the app or the connection drops, the app shall reconnect automatically when it can, resynchronise all… | All | MVP | S-LINK, S-STATE | I-LIFE, I-CONN | GAP | GAP | TC-MAPP-CON-070 | U, D |
| MAPP-CON-080 | The app shall perform a version handshake on connecting, exchanging the app version and wire-API version for the Master's | All | MVP | S-LINK | I-CONN | GAP | GAP | TC-MAPP-CON-080 | C, UI |
| MAPP-CON-090 | The app shall learn from a capability handshake which features the Master offers, and shall present a feature the Master does not offer as… | All | MVP | S-LINK, S-API | I-CONN, I-OPS | GAP | GAP | TC-MAPP-CON-090 | C, UI |
| MAPP-CON-100 | The app shall let the user unpair from the app, discarding the token, and shall tell them that the pairing should also be removed on the Master | All | MVP | S-LINK, S-SEC | I-CONN, I-TRUST | GAP | GAP | TC-MAPP-CON-100 | C, UI |
| MAPP-CON-110 | The app shall offer a demonstration mode that connects to a scripted mock Master, clearly labelled as such, for store reviewers, first-time users… | All | MVP | S-MOCK | I-CONN, I-BRIDGE | GAP | GAP | TC-MAPP-CON-110 | C, UI |
| MAPP-CON-120 | The app shall support being paired with more than one Master (for example a home observatory and a remote one) and shall let the user choose which… | All | P2 | S-LINK, S-SEC | I-CONN | GAP | GAP | TC-MAPP-CON-120 | C, UI |

## 3. MAPP-OVR — Observatory Overview

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-OVR-010 | The app shall list every Observatory and Pier available through the connected Master, including Piers on spoke nodes reached through it, showing… | All | MVP | S-STATE | I-SHELL | GAP | GAP | TC-MAPP-OVR-010 | U, UI |
| MAPP-OVR-020 | The app shall show, for each Pier, an activity timeline equivalent to the Galileo Dashboard (OBS-150): lanes for session, mount, meridian flip,… | All | MVP | S-STATE | I-SHELL | GAP | GAP | TC-MAPP-OVR-020 | U, UI |
| MAPP-OVR-030 | The app shall let the user choose the working Pier, shall show which Pier is working on every screen, and shall direct every Pier-specific screen… | All | MVP | S-STATE | I-SHELL | GAP | GAP | TC-MAPP-OVR-030 | U, UI |
| MAPP-OVR-040 | The app shall present a Pier that is unreachable, detached or not granted for operation as such, with the reason, and shall not show stale state… | All | MVP | S-STATE | I-SHELL | GAP | GAP | TC-MAPP-OVR-040 | U, UI |
| MAPP-OVR-050 | The app shall receive state, progress and alerts as events pushed by the Master and shall not poll for them in a loop | All | MVP | S-LINK, S-STATE | I-SHELL | GAP | GAP | TC-MAPP-OVR-050 | U, UI |
| MAPP-OVR-060 | The app shall show recent alerts with their severity and time, and shall show when a new alert arrives while the app is connected | All | MVP | S-STATE | I-SHELL | GAP | GAP | TC-MAPP-OVR-060 | U, UI |
| MAPP-OVR-070 | The app shall show a state of the observatory within 5 seconds of connecting (value to be confirmed, Section 5) | All | MVP | S-STATE, S-LINK | I-SHELL | GAP | GAP | TC-MAPP-OVR-070 | D |
| MAPP-OVR-080 | The app shall show, on the Overview screen, the health of the computer running the Master (CPU use, memory use, free disk space, temperature where reported, and uptime), taken from the Master's published state rather than polled, and shall offer Restart and Shut down for that computer as confirm-class operations that the Master refuses, with a reason, while a session is running. The Master side is `CSURF-310`. | All | P2 | S-STATE | I-SHELL | `StateTests` (freshness) | GAP | TC-MAPP-OVR-080 | UI |

## 4. MAPP-DEV — Devices

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-DEV-010 | The app shall connect and disconnect each device of the working Pier, and all of them together, and show each device's state (connected, busy, error) | All | MVP | S-API, S-CTL | I-OPS | GAP | GAP | TC-MAPP-DEV-010 | C, UI |
| MAPP-DEV-020 | The app shall operate the mount: slew to coordinates or a selected object, sync, tracking on/off and rate, park, unpark, find home, and pulse moves | All | MVP | S-API, S-CTL | I-OPS | GAP | GAP | TC-MAPP-DEV-020 | C, UI |
| MAPP-DEV-030 | The app shall provide held manual movement of the mount (jog) that moves only while the user holds the control, sends keep-alives to the Master… | All | MVP | S-CTL | I-OPS, I-LIFE | GAP | GAP | TC-MAPP-DEV-030 | U, D |
| MAPP-DEV-040 | The app shall operate the camera: cooling on/off and target temperature, and per-exposure gain, offset, binning and region of interest, start and… | All | MVP | S-API, S-CTL | I-OPS | GAP | GAP | TC-MAPP-DEV-040 | C, UI |
| MAPP-DEV-050 | The app shall operate the filter wheel (select filter by name) and the rotator (move to a position angle) | All | MVP | S-API, S-CTL | I-OPS | GAP | GAP | TC-MAPP-DEV-050 | C, UI |
| MAPP-DEV-060 | The app shall operate the dome (open, close, slew, follow mount), flat panel (on/off, brightness) and switches (toggle), where the Pier has them | All | P2 | S-API, S-CTL | I-OPS | GAP | GAP | TC-MAPP-DEV-060 | C, UI |
| MAPP-DEV-070 | The app shall show weather and environment readings the Master reports for the Pier | All | P2 | S-STATE | I-OPS | GAP | GAP | TC-MAPP-DEV-070 | C, UI |

## 5. MAPP-IMG — Imaging and Images

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-IMG-010 | The app shall start single exposures and exposure series with per-run parameters, show progress, and abort a running exposure or series | All | MVP | S-API | I-OPS | GAP | GAP | TC-MAPP-IMG-010 | C, UI |
| MAPP-IMG-020 | The app shall run a live exposure loop and display each result as it arrives | All | MVP | S-API | I-OPS | GAP | GAP | TC-MAPP-IMG-020 | C, UI |
| MAPP-IMG-030 | The app shall display the current frame as a preview rendered by the Master, scaled to the device, with pinch-to-zoom and pan, loading further… | All | MVP | S-IMG | I-IMG, I-OPS | GAP | GAP | TC-MAPP-IMG-030 | C, UI |
| MAPP-IMG-040 | The app shall let the user adjust the preview stretch interactively and shall show the histogram | All | MVP | S-IMG | I-IMG, I-OPS | GAP | GAP | TC-MAPP-IMG-040 | C, UI |
| MAPP-IMG-050 | The app shall show the image statistics the Master reports for the frame: star count, HFR and FWHM.  | All | MVP | S-IMG | I-IMG, I-OPS | GAP | GAP | TC-MAPP-IMG-050 | C, UI |
| MAPP-IMG-060 | The app shall show the live-stack result and its progress | All | P2 | S-IMG | I-IMG, I-OPS | GAP | GAP | TC-MAPP-IMG-060 | C, UI |
| MAPP-IMG-070 | The app shall let an Operator reject a frame | All | P2 | S-CTL | I-IMG, I-SAFE | GAP | GAP | TC-MAPP-IMG-070 | C, UI |
| MAPP-IMG-080 | The app shall let the user download a full FITS frame as an explicit action, with its size shown first and the transfer cancellable and resumable | All | P2 | S-IMG | I-IMG, I-OPS | GAP | GAP | TC-MAPP-IMG-080 | C, UI |
| MAPP-IMG-090 | The app shall adapt preview size and quality to the quality of the link, and shall offer a data-saver setting | All | P2 | S-IMG | I-IMG, I-OPS | GAP | GAP | TC-MAPP-IMG-090 | C, UI |

## 6. MAPP-SKY — Sky View, Targets and Framing

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-SKY-010 | The app shall draw an interactive sky view for the Observatory's location and the chosen time, showing the horizon profile, the mount's current… | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-SKY-010 | U, UI |
| MAPP-SKY-020 | The app shall let the user choose the date and time shown, including moving through the night, and shall return to the present on request | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-SKY-020 | U, UI |
| MAPP-SKY-030 | The app shall let the user search for and select a target and shall show its details, altitude and visibility as reported by the Master | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-SKY-030 | U, UI |
| MAPP-SKY-040 | The app shall let an Operator who holds the lease slew the mount to a selected target | All | MVP | S-CTL, S-SKY | I-SKY, I-SAFE | GAP | GAP | TC-MAPP-SKY-040 | U, UI |
| MAPP-SKY-050 | The app shall provide the Framing Assistant: the sensor field of view for the Pier's optical train at a chosen rotation, mosaic panel layout, and… | All | P2 | S-SCH, S-SKY | I-SKY | GAP | GAP | TC-MAPP-SKY-050 | U, UI |
| MAPP-SKY-060 | The app shall show the Master's ranked target lists (What's Up Tonight) and altitude curves for a chosen date | All | P2 | S-SKY | I-SKY | GAP | GAP | TC-MAPP-SKY-060 | U, UI |
| MAPP-SKY-070 | The app shall draw real sky behind the stars from the Master's all-sky survey tiles, at a resolution matching the zoom and correctly placed, on dark themes (in Night using red only), with their credit visible and a switch to turn it off; the sky view shall work normally without them. It shall keep them in memory only, and shall fetch no new tiles while the view is narrower than 3°. | All | P2 | S-SKY | I-SKY | `SkyTests`, `LiveMasterTests` | `DemoUITests`, `scripts/check_night.py` | TC-MAPP-SKY-070 | U, UI |
| MAPP-SKY-080 | The app shall hide everything below 0 degrees altitude behind an opaque landscape-style ground, with a switch to turn it off and the selection and mount markers still shown, and shall fill the sky between 0 degrees and the local horizon profile in transparent red. | All | P2 | S-SKY | I-SKY | `SkyTests` | `DemoUITests`, `scripts/check_night.py` | TC-MAPP-SKY-080 | U, UI |
| MAPP-SKY-090 | The app's sky view shall use the same projection as Galileo's Star Atlas. | All | P2 | S-SKY | I-SKY | `SkyTests`, `api/vectors/sky/projection.json` | — | TC-MAPP-SKY-090 | U |
| MAPP-SKY-100 | Selecting an object shall offer what the Star Atlas's context menu offers: Goto, Goto and Centre, Sync and Add to Session. | All | P2 | S-SKY | I-SKY | — | `DemoUITests` | TC-MAPP-SKY-100 | UI |
| MAPP-SKY-110 | The app shall let the user choose which deep-sky catalogs are shown, by the Master's membership. | All | P2 | S-SKY | I-SKY | `SkyTests` | `DemoUITests` | TC-MAPP-SKY-110 | U, UI |

## 7. MAPP-SOL — Plate Solving and Alignment

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-SOL-010 | The app shall solve the current or last frame, show the result, and centre the mount on a target by plate solving | All | MVP | S-API | I-OPS | GAP | GAP | TC-MAPP-SOL-010 | C, UI |
| MAPP-SOL-020 | The app shall sync the mount to a solved position when the user asks | All | MVP | S-API | I-OPS | GAP | GAP | TC-MAPP-SOL-020 | C, UI |
| MAPP-SOL-030 | The app shall run a polar alignment and display its progress and the resulting error | All | P2 | S-API | I-OPS | GAP | GAP | TC-MAPP-SOL-030 | C, UI |

## 8. MAPP-FOC — Focus

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-FOC-010 | The app shall provide manual focus using the operations the Master publishes from its focus module: move in or out by a chosen step, move to an… | All | MVP | S-API | I-OPS | GAP | GAP | TC-MAPP-FOC-010 | C, UI |
| MAPP-FOC-020 | The app shall start and cancel an autofocus run and show its progress, the V-curve and the result, and the state of temperature compensation | All | MVP | S-API | I-OPS | GAP | GAP | TC-MAPP-FOC-020 | C, UI |
| MAPP-FOC-030 | The app shall not move a focuser by any means other than the focus module's operations | All | MVP | S-API | I-OPS | GAP | GAP | TC-MAPP-FOC-030 | C, UI |

## 9. MAPP-GUI — Guiding

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-GUI-010 | The app shall connect and disconnect the guider service, start and stop guiding, dither and recalibrate | All | MVP | S-API | I-OPS | GAP | GAP | TC-MAPP-GUI-010 | C, UI |
| MAPP-GUI-020 | The app shall display the guide error trace and the guide-star cut-out the Master supplies | All | MVP | S-STATE | I-OPS | GAP | GAP | TC-MAPP-GUI-020 | C, UI |
| MAPP-GUI-030 | The app shall show the guide star as the cut-out or as the whole guide frame, and let the user choose the guide star by tapping either | All | MVP | S-API | I-OPS | GAP | GAP | TC-MAPP-GUI-030 | C, UI |

## 10. MAPP-FLP — Meridian Flip

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-FLP-010 | The app shall show the meridian-flip state and the time remaining to the flip for the working Pier | All | MVP | S-STATE | I-OPS | GAP | GAP | TC-MAPP-FLP-010 | C, UI |
| MAPP-FLP-020 | The app shall let an Operator who holds the lease trigger or defer a flip when the Master's flip service allows it, after a confirmation, and… | All | MVP | S-API, S-CTL | I-OPS, I-SAFE | GAP | GAP | TC-MAPP-FLP-020 | C, UI |

## 11. MAPP-SES — Sessions

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-SES-010 | The app shall list the saved sessions of the Master and load one | All | MVP | S-SES | I-SES | GAP | GAP | TC-MAPP-SES-010 | U, UI |
| MAPP-SES-020 | The app shall run, pause, resume, skip a step of, and stop a session, with a confirmation for stop | All | MVP | S-SES, S-CTL | I-SES, I-SAFE | GAP | GAP | TC-MAPP-SES-020 | U, UI |
| MAPP-SES-030 | The app shall show the progress of a running session block by block, including nested blocks, the current exposure and the time remaining | All | MVP | S-STATE, S-SES | I-SES | GAP | GAP | TC-MAPP-SES-030 | U, UI |
| MAPP-SES-040 | The app shall let the user author and edit a session: add, remove, reorder and nest instruction, condition and trigger blocks, and set each… | All | P2 | S-SES | I-SES | GAP | GAP | TC-MAPP-SES-040 | U, UI |
| MAPP-SES-050 | The app shall save an authored session to the Master and shall report the Master's validation messages without discarding the user's work | All | P2 | S-SES | I-SES | GAP | GAP | TC-MAPP-SES-050 | U, UI |
| MAPP-SES-060 | The app shall import and export a session as a .gses interchange file | All | P2 | S-SES | I-SES | GAP | GAP | TC-MAPP-SES-060 | U, UI |
| MAPP-SES-070 | The app shall prevent or clearly constrain editing of a session while it is running, following the rule the Master reports | All | P2 | S-SES | I-SES | GAP | GAP | TC-MAPP-SES-070 | U, UI |
| MAPP-SES-080 | The Meta Quest edition shall offer session authoring through the panel mode, on the tablet layout | Quest | P2 | S-SES | — | — | GAP | TC-MAPP-SES-080 | U, UI |

## 12. MAPP-SCH — Scheduler

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-SCH-010 | The app shall show the Master's job queue and the next job to run | All | MVP | S-SCH | I-SES | GAP | GAP | TC-MAPP-SCH-010 | U, UI |
| MAPP-SCH-020 | The app shall let the user create, edit and delete a scheduler job, including adding a job from a target selected in the sky view | All | P2 | S-SCH | I-SES | GAP | GAP | TC-MAPP-SCH-020 | U, UI |
| MAPP-SCH-030 | The app shall let the user enable, disable, reorder and run-now a job | All | P2 | S-SCH | I-SES | GAP | GAP | TC-MAPP-SCH-030 | U, UI |

## 13. MAPP-CAL — Calibration

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-CAL-010 | The app shall start dark, flat and bias sets and show their progress | All | P2 | S-API | I-OPS | GAP | GAP | TC-MAPP-CAL-010 | C, UI |

## 14. MAPP-SAF — Safety

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-SAF-010 | The app shall show every safety input and the overall safe or unsafe state, and a compact indicator of that state on every screen | All | MVP | S-CTL, S-STATE | I-SAFE | GAP | GAP | TC-MAPP-SAF-010 | U, UI |
| MAPP-SAF-020 | The app shall let an Operator acknowledge a safety event, and shall offer no other action on one | All | MVP | S-CTL, S-STATE | I-SAFE | GAP | GAP | TC-MAPP-SAF-020 | U, UI |
| MAPP-SAF-030 | The app shall show the reason a session stopped | All | MVP | S-STATE | I-OPS, I-SAFE | GAP | GAP | TC-MAPP-SAF-030 | U, UI |
| MAPP-SAF-040 | The app shall provide no control that disables the safety monitor, clears an unsafe state, resumes after a safety stop, or overrides a mount limit | All | MVP | S-CTL, S-STATE | I-SAFE | GAP | GAP | TC-MAPP-SAF-040 | U, UI |

## 15. MAPP-EMG — Emergency Stop

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-EMG-010 | The app shall provide a stop-everything, park and close-dome action reachable from every screen in no more than two interactions, protected… | All | MVP | S-CTL | I-SAFE | GAP | GAP | TC-MAPP-EMG-010 | C, UI |
| MAPP-EMG-020 | The emergency actions shall be available to any paired Operator without taking the lease, and the app shall show the outcome, including any part… | All | MVP | S-CTL | I-SAFE | GAP | GAP | TC-MAPP-EMG-020 | C, UI |

## 16. MAPP-LIB — Library and History

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-LIB-010 | The app shall browse recent sessions and frames as thumbnails with their quality metrics | All | P2 | S-IMG | I-IMG | GAP | GAP | TC-MAPP-LIB-010 | C, UI |
| MAPP-LIB-020 | The app shall show a frame preview and session history charts | All | P2 | S-IMG | I-IMG | GAP | GAP | TC-MAPP-LIB-020 | C, UI |

## 17. MAPP-NOT — Notifications

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-NOT-010 | While the app is connected, including when it is in the background but still running, the app shall present an operating-system notification for… | Mobile | P2 | — | I-LIFE | GAP | — | TC-MAPP-NOT-010 | D |

## 18. MAPP-CTL — Roles, Lease and Control Safety

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-CTL-010 | The app shall show the role (Viewer or Operator) the Master gave it, and as a Viewer shall offer no control that issues an operation | All | MVP | S-CTL | I-SAFE | GAP | GAP | TC-MAPP-CTL-010 | U, C |
| MAPP-CTL-020 | The app shall let an Operator take, hold and release a Pier's control lease, shall show who holds it, and shall offer to request it, with a… | All | MVP | S-CTL | I-SAFE | GAP | GAP | TC-MAPP-CTL-020 | U, C |
| MAPP-CTL-030 | When the Master refuses an operation because another client holds the lease, or for any other reason, the app shall show the Master's reason and… | All | MVP | S-CTL | I-SAFE | GAP | GAP | TC-MAPP-CTL-030 | U, C |
| MAPP-CTL-040 | The app shall ask for confirmation before any destructive or hard-to-reverse operation: abort an exposure series, stop a session, park or unpark,… | All | MVP | S-CTL | I-SAFE | GAP | GAP | TC-MAPP-CTL-040 | U, C |
| MAPP-CTL-050 | The app shall treat the Master's response as the result of an operation and shall not show an operation as done on the strength of having sent it | All | MVP | S-CTL | I-SAFE | GAP | GAP | TC-MAPP-CTL-050 | U, C |
| MAPP-CTL-060 | For any held operation the app shall send keep-alives while the control is held and shall stop the operation, by ceasing them, when the control is… | All | MVP | S-CTL | I-LIFE, I-OPS | GAP | GAP | TC-MAPP-CTL-060 | U, D |
| MAPP-CTL-070 | An operation in flight when the connection is lost shall be shown as outcome-unknown until the app resynchronises, and shall not be repeated… | All | MVP | S-CTL | I-SAFE | GAP | GAP | TC-MAPP-CTL-070 | U, C |
| MAPP-CTL-080 | Every request the app sends shall carry the app's identity so that the Master can record who did what | All | MVP | S-API | I-BRIDGE | GAP | GAP | TC-MAPP-CTL-080 | U, C |

## 19. MAPP-CFG — No Configuration

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-CFG-010 | The app shall contain no screen, control or request that creates, edits or deletes equipment profiles, Piers, optical trains, device definitions… | All | MVP | S-API | I-SHELL | GAP | GAP | TC-MAPP-CFG-010 | U, R |
| MAPP-CFG-020 | The app shall display Pier, optical-train and Observatory information read-only where it helps the user (for example the field-of-view source) | All | MVP | S-API | I-SHELL | GAP | GAP | TC-MAPP-CFG-020 | U, R |
| MAPP-CFG-030 | The app shall use only the documented wire API, with no route outside it (PSD Section 5), and shall hold no config.* permission | All | MVP | S-API | I-SHELL | GAP | GAP | TC-MAPP-CFG-030 | U, R |

## 20. MAPP-STR — Streamed Data

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-STR-010 | The app shall obtain the star and deep-sky catalog from the Master by sky region, limiting magnitude and level of detail as the user looks, and… | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-STR-010 | U, D |
| MAPP-STR-020 | The app shall obtain positions of the Sun, Moon, planets and minor bodies from the Master for the displayed time, and a time range ahead of the… | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-STR-020 | U, D |
| MAPP-STR-030 | The app shall obtain the Observatory location, horizon profile, pointing and field of view from the Master | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-STR-030 | U, D |
| MAPP-STR-040 | The app shall display streamed sky data progressively, brightest objects first, so that a usable view appears before all detail has arrived | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-STR-040 | U, D |
| MAPP-STR-050 | The app shall keep a bounded, short-lived cache of streamed data to ride out dropouts and avoid re-requesting a region the user is looking at, and… | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-STR-050 | U |
| MAPP-STR-060 | When the link to the Master is down, the app shall say so within the view and shall not present cached data as live | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-STR-060 | U, D |
| MAPP-STR-070 | The app shall conform to the Master's compact streaming protocol and shall respond to the Master's per-client rate and region limits by reducing… | All | MVP | S-SKY | I-SKY | GAP | GAP | TC-MAPP-STR-070 | U, D |

## 21. MAPP-LAY — Layouts, Theme and Accessibility

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-LAY-010 | The app shall provide tablet layouts that show several panels side by side (for example the sky view, the image and the session), in landscape and… | All | MVP | S-DSN | I-THEME | GAP | GAP | TC-MAPP-LAY-010 | UI, R |
| MAPP-LAY-020 | The app shall provide phone layouts that drop or condense secondary panels; which panels are dropped is decided from tablet wireframes (PSD Q1)… | All | MVP | S-DSN | I-THEME | GAP | GAP | TC-MAPP-LAY-020 | UI, R |
| MAPP-LAY-030 | The app shall provide a night-vision theme that uses red only, applies to every screen and dialog, and is selectable and remembered; the app's… | All | MVP | S-DSN | I-THEME | GAP | GAP | TC-MAPP-LAY-030 | U, UI |
| MAPP-LAY-040 | The app shall support dynamic type or font scaling, screen-reader labels on every control, and sufficient contrast in every theme | All | MVP | S-DSN | I-THEME | GAP | GAP | TC-MAPP-LAY-040 | UI, R |
| MAPP-LAY-050 | Touch targets shall be sized to be used reliably in cold conditions and with gloves | All | MVP | S-DSN | I-THEME | GAP | GAP | TC-MAPP-LAY-050 | R, D |
| MAPP-LAY-060 | The app shall externalise every user-visible string into a shared resource so that both platforms use the same wording (PSD G3) | All | P2 | S-DSN | I-THEME | GAP | GAP | TC-MAPP-LAY-060 | UI, R |

## 22. MAPP-PLT — Platform, Build and Release

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-PLT-010 | The iOS/iPadOS app shall run on iOS/iPadOS 17 or later, and the Android app on Android 10 (API 29) or later (to be confirmed against the device… | Mobile | MVP | — | I-REL | GAP | — | TC-MAPP-PLT-010 | R |
| MAPP-PLT-020 | The apps shall be built with each platform's native UI toolkit (SwiftUI on iOS, Jetpack Compose on Android), with an API client generated from one… | All | MVP | S-DSN | I-REL, I-BRIDGE | GAP | GAP | TC-MAPP-PLT-020 | R |
| MAPP-PLT-030 | Each feature shall be released on all platforms together, or recorded as staged in the release notes with the reason; a parity checklist shall be… | All | MVP | S-TEST | I-REL | GAP | GAP | TC-MAPP-PLT-030 | R |
| MAPP-PLT-040 | The apps shall pass one shared suite of wire-protocol test vectors on every platform | All | MVP | S-TEST, S-MOCK | I-REL, I-BRIDGE | GAP | GAP | TC-MAPP-PLT-040 | C |
| MAPP-PLT-050 | The apps shall be distributed free of charge through the Apple App Store and Google Play, without advertising or in-app purchases | Mobile | MVP | — | I-REL | GAP | — | TC-MAPP-PLT-050 | R |
| MAPP-PLT-060 | The apps shall collect no analytics or usage data and shall declare in their store privacy information that no data leaves the user's own network | All | MVP | S-SEC | I-REL | GAP | GAP | TC-MAPP-PLT-060 | R |
| MAPP-PLT-070 | The iOS app shall request the local-network permission with an explanation of why, and when it is refused shall explain how to grant it and which… | iOS | MVP | — | I-CONN, I-REL | — | — | TC-MAPP-PLT-070 | D |
| MAPP-PLT-080 | The Android app shall request only the permissions it needs and shall behave within Android's background-execution and foreground-service rules | Android | MVP | — | — | GAP | — | TC-MAPP-PLT-080 | D |
| MAPP-PLT-090 | When the app receives a response showing the Master is newer than it understands, it shall tell the user to update the app and shall link to the… | All | MVP | S-LINK | I-CONN, I-REL | GAP | GAP | TC-MAPP-PLT-090 | R |
| MAPP-PLT-100 | The app shall be proprietary software; its licence shall be added before the first release (PSD Section 5) | All | MVP | — | I-REL | GAP | GAP | TC-MAPP-PLT-100 | R |

## 23. MAPP-QST — Meta Quest Edition

| ID | Req. | Pl. | Pri. | Shared | iOS | Android | Quest | Test case | Method |
|---|---|---|---|---|---|---|---|---|---|
| MAPP-QST-010 | The Quest edition shall install and run on a Meta Quest 2 on a current Meta Horizon OS release, which is the acceptance device for every Quest… | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-010 | D |
| MAPP-QST-020 | The Quest edition shall run, without Quest 3-specific features, on Meta Quest 3, 3S and Pro headsets, which receive no specific support in v1.  | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-020 | D |
| MAPP-QST-030 | The Quest edition shall offer a panel mode in which every operating screen of the tablet layout is available as one or several floating 2D windows… | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-030 | D |
| MAPP-QST-040 | The Quest edition shall offer an immersive mode, entered from the panels, in which the planetarium and image viewer fill the user's view while… | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-040 | D |
| MAPP-QST-050 | The Quest edition shall be operable with the controllers and with hand tracking, shall make every operation reachable without a touch screen,… | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-050 | D |
| MAPP-QST-060 | In the Quest edition a held operation shall require holding a controller button, never a hand gesture, and shall stop when the button is released,… | Quest | MVP | S-CTL | — | — | GAP | TC-MAPP-QST-060 | D |
| MAPP-QST-070 | The Quest edition shall make the headset's camera passthrough available at all times with one gesture or button | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-070 | D |
| MAPP-QST-080 | The Quest edition shall show a first-run safety notice — use indoors or seated, never while walking at the telescope — and shall not open an… | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-080 | D |
| MAPP-QST-090 | When the headset is removed or goes to sleep the Quest edition shall pause its scenes and stop any held operation, and on wearing again shall… | Quest | MVP | S-LINK, S-CTL | — | — | GAP | TC-MAPP-QST-090 | D |
| MAPP-QST-100 | The Quest edition shall depend on no Google Play Services, Firebase or other component absent from Meta Horizon OS.  | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-100 | D |
| MAPP-QST-110 | The immersive planetarium shall draw the sky as a dome around the user for the Observatory's location and chosen time, with stars and deep-sky… | Quest | MVP | S-SKY | — | — | GAP | TC-MAPP-QST-110 | D |
| MAPP-QST-120 | The immersive planetarium shall show the mount's current pointing and the field of view of the Pier's optical train, updated live | Quest | MVP | S-SKY, S-STATE | — | — | GAP | TC-MAPP-QST-120 | D |
| MAPP-QST-130 | The immersive planetarium shall let the user move through time, in which the sky moves around the user and the user does not move | Quest | MVP | S-SKY | — | — | GAP | TC-MAPP-QST-130 | D |
| MAPP-QST-140 | The immersive planetarium shall let the user select an object by pointing, and shall slew the mount to it only as MAPP-SKY-040 requires (Operator,… | Quest | MVP | S-SKY, S-CTL | — | — | GAP | TC-MAPP-QST-140 | D |
| MAPP-QST-150 | The immersive planetarium shall have a mode in which stars stay legible using only the night-vision palette, and shall default to a low brightness | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-150 | D |
| MAPP-QST-160 | The immersive scenes shall hold on a Meta Quest 2 the frame rate recorded in the Quest SDD, with no visible hitching during head movement, using… | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-160 | D |
| MAPP-QST-170 | The immersive image viewer shall show the current exposure, the live-stack result or any Library frame as a large virtual display, with pan, zoom… | Quest | MVP | S-IMG | — | — | GAP | TC-MAPP-QST-170 | D |
| MAPP-QST-180 | The immersive image viewer shall compare two frames by blinking between them and side by side | Quest | P2 | S-IMG | — | — | GAP | TC-MAPP-QST-180 | D |
| MAPP-QST-190 | The Quest edition shall move the user's viewpoint only when the user asks, and shall keep the horizon level | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-190 | D |
| MAPP-QST-200 | When the Master reports that it cannot serve immersive streaming (it is not PC/Mac-class), the Quest edition shall say so, disable the immersive… | Quest | MVP | S-LINK | — | — | GAP | TC-MAPP-QST-200 | D |
| MAPP-QST-210 | The Quest edition shall be distributed through the Meta Horizon Store and also as a self-contained package for side-loading through SideQuest,… | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-210 | D |
| MAPP-QST-220 | The Quest edition shall not update itself; when the Master expects a newer app it shall say so and point to the download page | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-220 | D |
| MAPP-QST-230 | The Quest edition shall be accompanied by a side-loading guide covering developer mode, SideQuest and the package | Quest | MVP | — | — | — | GAP | TC-MAPP-QST-230 | D |
| MAPP-QST-240 | The Quest edition shall place the Framing Assistant on the dome: the sensor rectangle can be grasped, moved, rotated and sized to the optical… | Quest | P2 | S-SKY | — | — | GAP | TC-MAPP-QST-240 | D |
| MAPP-QST-250 | The Quest edition shall show the What's Up Tonight targets and altitude arcs across the dome for a chosen night | Quest | P2 | S-SKY | — | — | GAP | TC-MAPP-QST-250 | D |
| MAPP-QST-260 | The Quest edition shall show a per-Pier activity timeline as floating panels, and an ambient safety indication (a coloured horizon ring) visible… | Quest | P2 | S-STATE | — | — | GAP | TC-MAPP-QST-260 | D |
| MAPP-QST-270 | The Quest edition shall overlay the live all-sky camera image on the dome where the Master has one | Quest | P3 | S-IMG | — | — | GAP | TC-MAPP-QST-270 | D |
| MAPP-QST-280 | The Quest edition shall show a three-dimensional model of the mount, tube and dome following the live pointing and playing out a meridian flip as… | Quest | P3 | — | — | — | GAP | TC-MAPP-QST-280 | D |
| MAPP-QST-290 | The Quest edition shall show the guiding and focus plots, the plate-solve overlay and the polar-alignment error as floating or dome-attached… | Quest | P3 | S-STATE | — | — | GAP | TC-MAPP-QST-290 | D |

---

## 24. Coverage Summary

- Requirements in the SRS: **135** (MVP 106, P2 26, P3 3).
- Applying to **iOS/iPadOS**: 104; all have a design component in the [iOS SDD](ios/SDD.md).
- Applying to **Android**: 104 — all `GAP` until `docs/android/SDD.md` is written.
- Applying to **Meta Quest**: 130 — all `GAP` until `docs/quest/SDD.md` is written; the shared layer already carries the streamed sky, image and control-safety parts (Shared column).
- Requirements with no shared component are platform-only (permissions, packaging, theming, notifications) or Quest-only presentation.
- No test cases exist yet; the `TC-` identifiers are reserved.

*This matrix is regenerated from the SRS when the SRS changes; hand edits belong in the generator's override tables, not in this file.*
