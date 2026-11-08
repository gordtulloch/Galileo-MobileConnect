# Galileo MobileConnect — iOS/iPadOS SDD

| | |
|---|---|
| **Project** | Galileo MobileConnect — iOS/iPadOS app |
| **Document** | SDD (Software Design Description), v0.1 — **iOS/iPadOS platform design** |
| **Status** | Draft — for review |
| **Date** | 2026-11-06 |
| **Upstream documents** | [PSD](../PSD.md), [SRS](../SRS.md), [Shared SDD](../SDD.md) |
| **Downstream document** | [RTM](../RTM.md) |

---

## 1. Introduction

### 1.1 Purpose and scope

How the iPadOS/iOS app puts a native SwiftUI interface on the core specified in the [Shared SDD](../SDD.md). Components are named `I-*`. This document does not repeat that design: wherever the app needs transport, state, control safety, session logic or a mock Master, it implements the `S-*` component in Swift (package `GalileoCore`, on top of the generated `GalileoAPI` package) and says so.

### 1.2 Status

Built (2026-11-06): `GalileoCore` (`S-LINK`, `S-API`, `S-STATE`, `S-CTL`, `S-SEC`, `S-MOCK`), `I-CONN`, `I-TRUST`, `I-SHELL`, `I-SAFE`, `I-THEME`, `I-BRIDGE`, `I-LIFE` without notifications; of `I-OPS`/`I-IMG` the Equipment, Imaging, Focus, Solve, Guiding, Flip and Safety screens; `I-SKY` without the P2 framing and target lists; and of `I-SES` the run view and job queue. Not built, all P2: session authoring, Library, calibration, notifications, polar alignment. `docs/CHECKLIST.md` section 5a has the detail. Choices that depend on a spike or a device measurement are marked **proposed** and listed in Section 5.

---

## 2. Design Considerations

### 2.1 iPad first, one adaptive app (ADR-IOS-001)

**Decision:** a single universal app. The layout is built for a 11"–13" iPad in landscape first (PSD G4) with SwiftUI's adaptive containers: `NavigationSplitView` for the sidebar of areas, and multi-column content that collapses by size class. iPhone is the same screens at compact width, in one column, reached from a strip of areas across the top (`I-SHELL`). Which panels to drop altogether is PSD Q1, decided from wireframes.

**Rationale:** one codebase and one set of screens is what parity (PSD G3) needs; size classes, not device checks, drive layout so Stage Manager, Split View and rotation work with no extra code.

### 2.2 SwiftUI with the Observation framework over a plain Swift core (ADR-IOS-002)

**Decision:** views are SwiftUI. The Swift `GalileoCore` package (the `S-*` components, no UI imports) exposes `AsyncSequence`/actor-based state; `I-BRIDGE` turns it into `@Observable` view models on the main actor, one per screen. Screens never touch the generated types directly.

**Rationale:** the core is testable without a UI and from the command line (`swift test`), the generated and hand-written layers have a clear seam, and `I-BRIDGE` is the single place that deals with main-actor threading.

**Minimum OS:** iOS/iPadOS 17 (`MAPP-PLT-010`), which provides Observation, `NavigationSplitView` and the modern `URLSessionWebSocketTask`.

### 2.3 Network stack (ADR-IOS-003)

**Decision:** the generated client (`swift-openapi-generator` with the `swift-openapi-urlsession` transport) is given a `URLSession` that the app configures. Server-trust evaluation is handled in that session's delegate by applying `PinPolicy` to the presented leaf certificate's SHA-256 fingerprint; the event stream uses `URLSessionWebSocketTask` on the same configuration. Discovery uses `NWBrowser` for `_galileo-link._tcp` (the service type Connect Link advertises, `_galileo-link._tcp` **[verified]**).

**Rationale:** `URLSession` honours the system's Wi-Fi and multipath behaviour, supports WebSocket, and lets the app pin a certificate without disabling App Transport Security for the whole app (Section 3.3).

### 2.4 Rendering the sky with Metal (ADR-IOS-004)

**Decision (built; the frame-rate measurement on the oldest supported iPad is still to do):** the sky view is an `MTKView` hosted in SwiftUI, drawing stars as instanced point sprites from the streamed catalog, with deep-sky objects, solar-system bodies, horizon and overlays as separate passes. SwiftUI `Canvas` is rejected for the star field.

**Rationale:** tens of thousands of points at 60 fps on pan and pinch is a GPU job; `Canvas` redraws on the CPU. The decision is gated on a prototype on the oldest supported iPad.

### 2.5 The image viewer is tile-based (ADR-IOS-005)

**Decision:** the image viewer displays a low-resolution full frame immediately and loads higher-resolution tiles for the visible region when a zoom or pan settles, composited by a `UIScrollView`-backed representable (`UIViewRepresentable`) with a tile layer. Stretch is applied by the Master (Shared SDD `S-IMG`); changing it re-requests tiles, with the current image kept until the new ones arrive.

**Rationale:** a full-resolution frame would be many megabytes over a weak link (PC7). Native scroll/zoom physics are expected by iOS users and difficult to reproduce in pure SwiftUI.

### 2.6 Dark-adapted by construction (ADR-IOS-006)

**Decision:** the app has two themes from the shared tokens: Standard (system light/dark) and Night (red only). The Night theme is applied at the **root of the scene**, through an environment value that every component reads, and also sets `UIUserInterfaceStyle` dark and requests the lowest practical brightness behaviour (Section 3.8). No view uses a literal colour or a system semantic colour that could produce non-red light in Night.

**Rationale:** a single stray white sheet, alert or share sheet destroys dark adaptation. The tokens and a lint rule that forbids literal colours in view code make Night complete by construction.

**System controls.** Some can't be held to red at all, so the app doesn't use them: `Slider` (white thumb), `ProgressView(value:)` (grey track), `Menu` and `Picker` (system menu), `.sheet`, `.alert` and `.confirmationDialog`. The app has themed equivalents, and `scripts/check_ios.sh` refuses these names in `ios/App`. It also checks every Night screenshot from the UI tests pixel by pixel (`scripts/check_night.py`), which is how the slider and the grey track were found. The keyboard remains the one system surface in app flows (risk I5).

---

## 3. Component Design

```
 I-SHELL  navigation, Pier switcher, connection banner, safety indicator, emergency button
   │
   ├─ I-CONN   discovery · pairing · handshake messages · local-network permission
   ├─ I-OPS    operating screens: devices · imaging · solve · focus · guiding · flip · calibration · safety
   ├─ I-SKY    sky view (Metal) · targets · framing
   ├─ I-IMG    image viewer · histogram · Library
   ├─ I-SES    session and schedule authoring and running
   ├─ I-SAFE   lease · confirmations · emergency · roles
   ├─ I-THEME  tokens · layouts · accessibility · strings
   ├─ I-LIFE   scene lifecycle · holds · notifications
   ├─ I-TRUST  Keychain SecureStore · certificate pin
   ├─ I-BRIDGE view models over GalileoCore (main-actor adapters)
   └─ I-REL    packaging · privacy · release
```

### 3.1 `I-SHELL` — App shell and navigation

- **Structure.** On iPad, a menu of areas exactly as wide as its longest label, which slides over the screen from a button in the top bar and hides itself once used, so that screens keep their width; *Keep open* pins it beside the content instead, and is remembered. Content is in two columns (`MAPP-LAY-010`). On iPhone, a themed strip of areas. Neither uses the system's split view or navigation bar: their controls can't be held to Night. Areas built so far: Overview, Equipment, Sky, Imaging, Focus, Solve, Guiding, Flip, Sessions, Safety, Settings; an area appears when it works, not before. A persistent top bar shows: the **working Pier** with a switcher (`MAPP-OVR-030`), the **connection state** (connected / reconnecting / unreachable, with the age of the oldest value on screen), the **role** (Viewer/Operator) and **lease** holder, the **safety indicator** (`MAPP-SAF-010`), and the **emergency** button (`MAPP-EMG-010`). They are in the shell so they appear on every screen.
- **Overview** (`MAPP-OVR-010`–`070`): Pier list with node, state and grant; per-Pier activity timeline drawn from the Dashboard lanes (`S-STATE`); alert list with severity and time; a banner for a new alert while connected.
- **No configuration.** There is no Settings screen for the observatory. The app's own **Settings** screen contains only app preferences (theme, data saver, units display) and the list of paired Masters with *Unpair* (`MAPP-CFG-010`); read-only Pier and optical-train information appears as detail views (`MAPP-CFG-020`).
- **Satisfies:** `MAPP-OVR-*`, `MAPP-CFG-010`, `020`, `MAPP-LAY-010`, `020`.

### 3.2 `I-CONN` — Connection, discovery and pairing

- **Discovery list.** `NWBrowser` results via the shared `Discovery` interface, plus *Enter address* with validation messages from `S-LINK` (`MAPP-CON-010`, `020`).
- **Local-network permission.** iOS shows its permission prompt on first local-network use. The app first shows its own explanatory screen ("Galileo needs to find your observatory computer on your Wi-Fi network"), with `NSLocalNetworkUsageDescription` and `NSBonjourServices` in Info.plist. If access is denied (detected by discovery failing with the policy-denied error) the app explains how to enable it in Settings and which features are unavailable (`MAPP-PLT-070`).
- **Pairing screen.** Shows the six-digit code, the Master's name and certificate fingerprint (short form), a countdown from `expires_in`, and the state: waiting, approved, refused, timed out, with *Cancel* and *Try again*. Nothing else in the app is reachable until approved (`MAPP-CON-030`).
- **Connection banner.** Reconnecting, unreachable, identity changed (with *Pair again*), revoked (with the Master's message), version messages from the handshake saying *update the app* (with a link to the App Store page) or *update Galileo* (`MAPP-CON-060`, `070`, `080`, `PLT-090`).
- **Capabilities.** Controls for features the Master lacks are shown disabled with the Master's reason, never as an error (`MAPP-CON-090`).
- **Demonstration mode.** A "Try the demo" entry on the first screen runs the shared `S-MOCK` with a persistent *DEMO — simulated observatory* banner (`MAPP-CON-110`).
- **Multiple Masters** (P2): a list of paired Masters in Settings with a chooser at launch (`MAPP-CON-120`).
- **Unpair.** Discards the token and pin, and tells the user to remove the pairing on the Master (`MAPP-CON-100`).
- **Satisfies:** `MAPP-CON-*`, `MAPP-PLT-070`, `MAPP-PLT-090`.

### 3.3 `I-TRUST` — Secure storage and certificate pinning

- **Keychain.** Implements `SecureStore`: one generic-password item per Master, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (so the app can reconnect after a reboot without the screen being unlocked, and the token never leaves the device through backup or iCloud), holding the token and fingerprint as a single encrypted blob. No Keychain item is synchronised. The token is never written to `UserDefaults`, files or logs (`MAPP-CON-040`).
- **Pinning.** The `URLSessionDelegate` server-trust handler computes the SHA-256 of the leaf certificate and passes it to `PinPolicy`; it never calls `SecTrustEvaluate` with `.proceed` unconditionally. **App Transport Security** keeps its default (no arbitrary loads); because the Master uses a self-signed certificate on a LAN address, the app declares `NSAllowsLocalNetworking` only, which permits connections to local hosts and leaves ATS enforced for everything else (`MAPP-CON-050`).
- **Satisfies:** `MAPP-CON-040`, `050`, `060`.

### 3.4 `I-OPS` — Operating screens

One SwiftUI screen per area, each built from the catalogue (Shared SDD ADR-APP-004): controls are bound to operation names, ranges and units come from the catalogue, and a missing or unavailable operation shows the Master's reason.

| Screen | Contents | Requirements |
|---|---|---|
| Equipment | Per-device cards with connect/disconnect, state, and the device's controls: mount (slew, sync, tracking, park/unpark, home, pulse, **jog pad**), camera (cooling, parameters), filter wheel, rotator, dome, flat panel, switches, weather | `DEV-*` |
| Imaging | The frame fills the screen (pinch, pan, tiles). Exposure, count, frame type, gain and offset sit in a compact panel that slides down from the top, with stretch, single exposure, abort and live loop one tap further; exposure, series and live-stack progress are bars along the bottom edge, with a Frame/Stack switch for which picture and statistics a small caption, with no panels | `IMG-010`, `020`, `050`, `060` |
| Solve | Solve, centre, sync, polar alignment | `SOL-*` |
| Focus | Manual focus (focus-module operations only: step in/out, absolute move, halt, position and temperature) and autofocus (progress, V-curve chart, result, temperature compensation) | `FOC-*` |
| Guiding | Connect, start/stop, dither, recalibrate, error trace, star cut-out | `GUI-*` |
| Flip | State, time remaining, trigger/defer with confirmation | `FLP-*` |
| Calibration | Start dark/flat/bias sets with progress | `CAL-010` |

- **Jog pad and held controls.** Implemented as a `DragGesture(minimumDistance: 0)` control that calls `HoldSession.start` on touch-down and `.stop` on touch-up, cancellation, or when the view disappears; it also stops when the scene leaves the active phase (Section 3.7) (`MAPP-DEV-030`).
- **Charts.** Swift Charts for the guide trace, V-curve and history; data from `S-STATE`/`S-API`.
- **Controls sized for gloves.** Minimum target 56 pt on iPad (Section 3.8).
- **Satisfies:** `MAPP-DEV-*`, `MAPP-IMG-010`, `020`, `050`, `060`, `MAPP-SOL-*`, `MAPP-FOC-*`, `MAPP-GUI-*`, `MAPP-FLP-*`, `MAPP-CAL-*`, `MAPP-SAF-030`.

### 3.5 `I-SKY` — Sky view

- Hosts the Metal view of ADR-IOS-004 with a SwiftUI overlay for search, time controls (date/time, scrub through the night, *Now*), and the selected object's detail card with altitude/visibility from the Master. Gestures: drag to pan, pinch to zoom, tap to select, double-tap to centre.
- **Layers:** stars and deep-sky objects (streamed, progressive), constellation figures and names (from the Master, with a switch), Sun/Moon/planets/minor bodies, horizon profile, mount pointing marker, optical-train field-of-view rectangle, and the target's marker.
- **Panel:** the sky takes the whole screen. Search, time, *Show* (Sky survey, Constellations) and *Catalog* (Messier, Caldwell, NGC, by the Master's `/sky/catalogs`) are in a panel that slides over it from a button at the top right (from the bottom on a phone); tapping empty sky closes it, and it can be kept open, when it takes its room from the sky. Its controls are the `compactTarget` size, 36 pt, by the user's decision on 2026-11-07 that screen space matters more here than the gloved-hands size of `MAPP-LAY-050`, which everything else keeps. A label on the sky says when the time shown isn't now.
- **Object menu (`MAPP-SKY-100`):** tapping an object, or choosing a search result, opens beside it what the Star Atlas's context menu offers: *Goto* (`mount.slew`, asking first), *Goto and Centre* (`solve.centre`), *Sync* (`mount.sync`) and *Add to Session*. The app has no session editor, so the last takes the target to the Sessions screen, where saved sessions run on it (`session.run`'s target).
- **Projection (`MAPP-SKY-090`):** `SkyCamera` and `SkyGeometry` are `Viewport`, `precess_from_j2000`, `local_sidereal_deg` and `equatorial_to_horizontal` in `galileo.planning.star_atlas`: field of view up the height, J2000 precessed to the date, the same far limit, pan rates and clamps. `api/vectors/sky/projection.json` is written by that code, and the tests hold the app to it.
- **Survey (`MAPP-SKY-070`):** real sky from the Master's HiPS tiles, in `SkySurveyLayer`. `SkySurvey.plan` chooses an order from the screen's pixel scale and descends the HEALPix tree for the tiles in view (at most 48, else one order coarser; the Master's survey stops at order 4). A view narrower than 3° fetches nothing new and draws what is held. Each tile is a texture and a mesh of real sky directions that goes through the stars' own projection, so it can't disagree with them; coarse tiles are drawn first and finer ones replace them, so the picture sharpens rather than having holes. Four at a time, the latest view first, cancelling what the view has left; 96 held, in memory only. `imageTint` makes them red-only in Night; they aren't drawn on the Light theme. The credit comes with the tiles and is shown on the sky. The demo has none, as they aren't ours to ship.
- **Ground and horizon (`MAPP-SKY-080`):** a per-pixel pass after the stars. Below 0°, an opaque made-up landscape, the same idea as `landscape_rgb` in core: noise on a flat ground plane in the `landNear` and `landFar` tokens, so red-only in Night. The canvas overlay leaves out figures, names, deep-sky objects and bodies below 0° too, and they can't be tapped; the selection and mount markers stay. From 0° up to the profile, the `horizonFill` token at 28%.
- **Go to:** the selection offers *Go to*, visible only to an Operator, which hands to `S-CTL`; the confirmation sheet names the target, current altitude and the Master's horizon/limit check, and sends nothing unless confirmed (`MAPP-SKY-040`).
- **Framing assistant (P2):** the FOV rectangle for the chosen rotation, mosaic grid, and *Use in session/job* (`MAPP-SKY-050`). **What's Up Tonight** and altitude curves (P2) as lists and Swift Charts (`MAPP-SKY-060`).
- **Link down:** a banner reads "Not live — showing the last sky received" and the pointing marker and FOV are hidden, never frozen in place (`MAPP-STR-060`).
- **Satisfies:** `MAPP-SKY-*`, `MAPP-STR-*`.

### 3.6 `I-IMG` — Image viewer and Library

- Viewer (ADR-IOS-005) with pinch/pan, a stretch control (the one value `galileo.preview` has), the histogram from the Master, the star count/HFR/FWHM readout, and for Operators *Reject frame* (confirm) (`MAPP-IMG-030`–`050`, `070`).
- Live-stack progress and result (`MAPP-IMG-060`). *Download FITS*: shows size, transfers with progress, can be cancelled and resumed, and hands the file to the Files/share sheet (`MAPP-IMG-080`). Data-saver setting (`MAPP-IMG-090`).
- Library: grid of recent session and frame thumbnails with quality metrics, frame preview, history charts (`MAPP-LIB-010`, `020`).
- **Satisfies:** `MAPP-IMG-030`–`090`, `MAPP-LIB-*`.

### 3.7 `I-LIFE` — Scene lifecycle, holds and notifications

- **Lifecycle.** The app observes `scenePhase`. On `.inactive` or `.background`: stop every active hold immediately (the shared `HoldSession.stop`), pause previews and sky requests, and keep the stream only as long as iOS lets the app run. On returning to `.active`: reconnect, replay or resync, and show state only when fresh (`MAPP-CON-070`, PC4). The app does not claim to stay connected in the background and uses no background mode that would require an unjustified entitlement.
- **Local notifications (P2).** While the app is connected — including for the brief time iOS keeps a backgrounded app alive — alerts the Master marks important become `UNUserNotificationCenter` local notifications, requested with a plain explanation (`MAPP-NOT-010`). No push service, no APNs token, no server (PSD Section 5).
- **Satisfies:** `MAPP-CON-070`, `MAPP-DEV-030` (stop on leaving foreground), `MAPP-CTL-060`, `MAPP-NOT-010`.

### 3.8 `I-THEME` — Layout, theme, accessibility, localisation

- **Layouts.** Tablet layouts show several panels side by side in landscape and portrait (for example sky + image + session) using size classes and `ViewThatFits`; phone layouts are the reduced form decided from wireframes (`MAPP-LAY-010`, `020`).
- **Night theme.** ADR-IOS-006. The status bar is hidden or styled to dark; the app prompts the user to lower screen brightness the first time Night is chosen and never raises it; its default theme on first launch after dusk is the user's last choice, and the factory default is Night with low-brightness advice (`MAPP-LAY-030`).
- **Accessibility.** Dynamic Type everywhere (no fixed font sizes except in charts, whose labels scale with a clamp), VoiceOver labels and hints on every control including custom gestures (the jog pad exposes explicit *Hold to move* actions), contrast verified for both themes by a test over the tokens (`MAPP-LAY-040`). Minimum target 56 pt; spacing from tokens (`MAPP-LAY-050`).
- **Strings.** The shared string table is compiled to `Localizable.xcstrings`; views use keys only (`MAPP-LAY-060`).
- **Satisfies:** `MAPP-LAY-*`.

### 3.9 `I-SAFE` — Roles, lease, confirmations and emergency

- **Roles.** A Viewer sees no control that issues an operation; the controls are not rendered rather than rendered disabled, so there is nothing to tap; read-only equivalents show state (`MAPP-CTL-010`).
- **Lease.** A lease chip in the top bar; tapping opens a sheet to take or release, or to *Ask to take over* when another client holds it, with the Master's wording (`MAPP-CTL-020`, `030`).
- **Confirmation dialogs.** One reusable dialog, drawn by the app in a layer under the top bar, not as a system sheet, so that it holds to Night and the emergency button stays reachable above it. It is fed by `S-CTL`'s summary: title naming the operation, the arguments in words, the Master's consequence text, a destructive-styled confirm button, and *Cancel* as the default action (`MAPP-CTL-040`).
- **Outcome states.** Pending shows an indeterminate indicator on the control that was used; `OutcomeUnknown` shows a "check state" badge and the control stays disabled until resolved; a refusal shows the Master's reason inline (`MAPP-CTL-030`, `050`, `070`).
- **Emergency.** A fixed red-on-dark control in the top bar. One tap opens a sheet with *Stop everything*, *Park*, *Close dome*; each requires a **press-and-hold** (about one second) or a swipe-to-confirm, reachable in two interactions from any screen, including with a dialog open (the top bar is above the dialog layer). The result sheet lists each part and any that failed (`MAPP-EMG-010`, `020`).
- **Safety screen.** Every input with its state and the overall state; *Acknowledge* for an Operator; the reason a session stopped; no control to override, clear or disable (`MAPP-SAF-010`–`040`).
- **Satisfies:** `MAPP-CTL-*`, `MAPP-SAF-*`, `MAPP-EMG-*`, `MAPP-SKY-040`.

### 3.10 `I-SES` — Sessions and scheduler

- **Run view.** Saved sessions list, run/pause/resume/skip/stop (stop confirms), and a live progress tree that expands nested blocks with the current exposure and time remaining (`MAPP-SES-010`–`030`).
- **Editor (P2).** A block outline with drag-to-reorder, swipe/indent controls for nesting, an *Add block* palette built from the Master's block-type catalogue, and an inspector form generated from each block's parameter schema; validation messages are attached to the block they name; undo/redo; the draft is never discarded on a failed save (`MAPP-SES-040`, `050`). Import/export `.gses` through `fileImporter`/`ShareLink` (`MAPP-SES-060`). Editing a running session follows the Master's reported rule (`MAPP-SES-070`).
- **Scheduler.** Job queue with next job highlighted; add job (also from the sky view selection), edit, delete (confirms), enable/disable, reorder, run now (`MAPP-SCH-010`–`030`).
- **Satisfies:** `MAPP-SES-010`–`070`, `MAPP-SCH-*`.

### 3.11 `I-BRIDGE` — View-model layer

`I-BRIDGE` is one `@MainActor @Observable` `AppModel`, holding the latest immutable `SessionSnapshot` from `ObservatorySession`. Screens read it and bind controls to catalogue operations through it; form state stays in the views. This replaces the one-view-model-per-screen first proposed: every screen shows the same snapshot, so per-screen models would only have copied it. Launch arguments (`-GalileoDemo <scenario>`, `-GalileoTheme`) inject the `S-MOCK` player for UI tests. Cancellation propagates through Swift concurrency from the view down to the request.

### 3.12 `I-REL` — Packaging, privacy and release

- **Identifiers.** Bundle id `com.gordtulloch.galileo.mobileconnect` (decided 2026-11-06, PSD Q5). App name is decided before first submission; working name *Galileo MobileConnect*.
- **Info.plist.** `NSLocalNetworkUsageDescription`, `NSBonjourServices`, ATS with `NSAllowsLocalNetworking` only, no background modes, no tracking domains.
- **Privacy manifest** (`PrivacyInfo.xcprivacy`): declares no tracking and no data collection; lists the required-reason APIs actually used. The App Store privacy label states "Data Not Collected" and notes that the app talks only to the user's own Master (`MAPP-PLT-060`).
- **Review.** The App Review notes describe the app plainly, point at demonstration mode (no hardware needed), and explain the local-network permission (PSD R7).
- **Distribution.** Free, no ads, no in-app purchases (`MAPP-PLT-050`); TestFlight for beta; the licence text is added before the first release (`MAPP-PLT-100`).
- **Minimum OS:** iOS/iPadOS 17 (`MAPP-PLT-010`).
- **Build.** Xcode project in `ios/`, generated by XcodeGen from `ios/project.yml` (the source of truth; both are committed, so a clone opens without XcodeGen), depending on the local Swift packages `GalileoAPI` (generated from `api/openapi.yaml` at build time by the SwiftPM plugin) and `GalileoCore`; `swift test` on `GalileoCore` replays the `S-TEST` vectors; UI tests (`ios/UITests`) run against `S-MOCK`. Swift 6 language mode for hand-written code. Dependencies are pinned exactly, and each `Package.resolved` is committed. Unattended `xcodebuild` needs `-skipPackagePluginValidation` for the generator plugin (Xcode's UI asks once instead); `scripts/check_ios.sh` passes it. Building, signing and TestFlight upload require macOS (Shared SDD Section 6).
- **Satisfies:** `MAPP-PLT-010`, `020`, `050`, `060`, `070`, `100`.

---

## 4. Platform Risks

| # | Risk | Mitigation |
|---|---|---|
| I1 | iOS suspends the app soon after it backgrounds, so alerts and a running connection are short-lived | Designed for it (PC4): reconnect and resync on return; local notifications only while connected; the Master is the authority and is unaffected |
| I2 | The local-network permission is denied or its prompt is missed | Explanatory screen before the prompt; denied-state screen; manual address entry still works once permission is granted |
| I3 | Self-signed certificate handling is misread by App Review | Pinning is explained in review notes; ATS is not disabled, only local networking is allowed |
| I4 | The core's logic exists in Swift and Kotlin and could drift | `S-TEST` vectors in CI on both platforms; the specification in the Shared SDD is the single source |
| I5 | A stray non-red surface in Night (system sheets, keyboards, share sheet) | Root-level style override; custom sheets for app flows; documented list of system UI that cannot be themed, with the advice to enable iOS Red filter via Accessibility shortcut |
| I6 | Metal sky view cost on older iPads | Level-of-detail by magnitude, cap on instance count, measured on the oldest supported iPad |

---

## 5. Open Design Points

| # | Point |
|---|---|
| IO1 | ~~Bonjour TXT fields~~ **Closed:** `name`, `node`, `proto`, `role` (`galileo/connect_link/discovery.py`), decoded in `BonjourDiscovery` |
| IO2 | Metal vs an alternative for the sky view: decided by a prototype on the oldest supported iPad |
| IO3 | Emergency control: **press-and-hold (one second) is built**; user-test on a tablet at night, with gloves |
| IO4 | Which iPhone panels fold (PSD Q1): for now nothing is dropped, each area is one column and the sky's panel scrolls under it. Revisit from use |
| IO5 | ~~Bundle identifier~~ **Closed:** `com.gordtulloch.galileo.mobileconnect`. The App Store name remains (PSD Q5) |
| IO6 | Whether the Keychain accessibility class should be stricter once background use is understood |
