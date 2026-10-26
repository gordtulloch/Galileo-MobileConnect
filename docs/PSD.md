# Galileo MobileConnect Apps — PSD (Project Scope Document)

| | |
|---|---|
| **Project** | Galileo MobileConnect — iOS/iPadOS and Android apps |
| **Document** | PSD (Project Scope Document), v0.7, draft — **common to both platforms**, plus the Meta Quest edition (Section 4a) |
| **Status** | Draft — for review |
| **Date** | 2026-10-27 |
| **Server-side counterpart** | MobileConnect plugin — `docs/plugins/mobileconnect/PSD.md` in the [Galileo](https://github.com/gordtulloch/Galileo) repository (operational scope, proxying, control model) |
| **Downstream documents** | SRS and RTM (common); SDD: shared architecture plus one per platform (Section 10) |

---

## 1. Purpose of This Document

This document defines the scope of the **Galileo MobileConnect apps** — the iOS/iPadOS app, the Android app, and a **Meta Quest edition** of the Android app for Meta Quest 2 and Meta Quest 3 headsets — which let a user operate a running Galileo observatory from a tablet, a phone or a VR headset. It covers the **client**. What the node offers (the operations, the control model, the proxy through the Master) is specified by the server-side plugin PSD in the Galileo repository and is **consumed by reference here, not restated**; where the two documents touch, the plugin PSD's Section 6 is authoritative for the operational surface, and this one for how the apps present it.

## 2. Background and Motivation

Galileo's desktop application runs the imaging session, usually at or near the telescope; its owner is often elsewhere — in the house, at another instrument, in bed. A tablet shows the state of the night at a glance, and lets the user correct it with touch, over little data, without remote-desktop tools that push a whole desktop's pixels over a weak signal. The owner has stated a preference for a **tablet**, with the phone supported in a reduced form. The apps operate an observatory that is already configured and running; they do not configure it.

## 3. Vision Statement

> A Galileo user installs the app on their tablet, asks to pair it with their Master node, approves the request on the Master, and from then on sees every Pier of their observatory — including nodes at other sites reached through the Master — and can do anything they could do at the computer during a night's imaging, except change the configuration. The same experience is available on iPad and on Android tablets, with the same behaviour, and a reduced layout on the phone.

## 4. Goals and Objectives

| # | Goal |
|---|---|
| G1 | **Two native apps from one repository**: iOS/iPadOS (Swift, SwiftUI) and Android (Kotlin, Jetpack Compose), each using its platform's native UI and conventions |
| G2 | **Maximum sharing below the UI**: one generated API client, one shared model and logic layer (Kotlin Multiplatform is the candidate), one set of design tokens, icons, strings and wire-protocol test vectors. Only the screens and platform integration differ |
| G3 | **Behavioural parity**: the same feature set, wording and results on both platforms at each release; a feature ships on both or is explicitly staged |
| G4 | **Tablet-first design**: layouts designed for a tablet's size (several panels side by side) first; the phone layout is a reduction — the choice of what is dropped is TBD from wireframes |
| G5 | **Full operational coverage** of the areas listed in the plugin PSD §6.4: devices, imaging, sky and framing, plate solving, focus (manual through the focus module, and automatic), guiding, meridian flip, **session authoring and running**, **schedule authoring and running**, calibration, safety, Library/history, emergency stop. No configuration screen exists |
| G6 | **Pairing with approval**: the app requests pairing with the Master, shows the code the user checks on the Master, receives its token on approval, and stores it in the platform's secure storage (iOS Keychain, Android Keystore); the certificate seen at pairing is pinned |
| G7 | **One connection, whole observatory**: the app connects only to the Master and operates spoke nodes through it |
| G8 | **Live by push**: state, progress and alerts arrive by the Master's event stream; no polling loops |
| G9 | **Safe by design**: Viewer/Operator roles, the per-Pier control lease, confirmation for destructive actions, an always-reachable stop/park, and deadman behaviour for held motion are all honoured in the UI, with the node as the authority |
| G10 | **Built for the dark**: a night-vision (red-only) theme and low-brightness defaults, since the apps are used at the telescope in the dark |
| G11 | **Free to users initially**, distributed through the Apple App Store and Google Play under accounts held by Gord Tulloch |
| G12 | **Meta Quest edition** (Section 4a): a version of the Android app that runs on Meta Quest 2 and Meta Quest 3 headsets, which are Android-based, with the same operating functions as the Android app presented for a headset |

## 4a. Meta Quest Edition (Meta Quest 2 and Meta Quest 3)

**Meta Quest 2 is the primary target and the initial test environment**; Quest 3-specific support follows, based on how the Quest 2 edition is used (Q-12). Both run Meta's Android-based operating system (Meta Horizon OS), so an Android app can run on them as a **floating 2D panel** in the headset. The Quest edition starts there, but it is **required to go further**: an **immersive planetarium** and an **immersive image viewer** are part of v1, which makes it a distinct product with its own rendering stack and its own SDD. The wider opportunities are analysed in [analysis/QuestVR.md](analysis/QuestVR.md).

| # | Scope |
|---|---|
| Q-1 | **Shares code with the Android app, but is a separate design.** The Quest edition is built from the same repository and reuses the shared layer (model, state, API client, session/schedule logic) and the Android app's panel screens, but it has its own module, its own rendering stack for immersive scenes, and **its own SDD** (`docs/quest/SDD.md`, Section 10) |
| Q-2 | **Two modes in one app**: **panel mode** — the tablet layout as one or several floating 2D windows the user places around the room (all operating screens); and **immersive mode** — the scenes in Q-9 and Q-10, entered from the panels, with panels still available alongside |
| Q-3 | **Input**: pointing with the controllers or hand tracking and pinch/click selection; a virtual keyboard for text; every operation reachable without a touch screen. Controls are sized for pointer selection at a distance, not for a fingertip |
| Q-4 | **Dark by design**: the night-vision (red-only) theme and dim defaults apply; the headset blocks stray light, which suits dark adaptation, but a fully black world is not offered as a default (see Q-6) |
| Q-5 | **Same function and same safety model as the Android app**: Viewer/Operator, lease, confirmation, stop/park, deadman for held motion (held motion needs a controller button hold, not a hand-tracking gesture, so that releasing it is unambiguous) |
| Q-6 | **Safety of the wearer**: the headset hides the real surroundings, and the app is used at night near a telescope. Camera passthrough (the headset's view of the room) is available at one gesture or button, and the first-run screen and documentation say the headset is for use indoors or in a safe seated position, never while walking at the telescope |
| Q-7 | **No dependence on Google services**: Meta Quest has no Google Play Services; the Quest flavour uses none (no Firebase, no Play Services APIs), and the Android app avoids them wherever practicable so the variant stays small |
| Q-8 | **Distribution: Meta Horizon Store, with SideQuest as the standing fall-back.** The primary channel is the Meta Horizon Store under a Meta developer account, subject to Meta's policies and review; it is not distributed through Google Play. The headset also allows **side-loading** of applications through **SideQuest**, so if the store becomes unavailable for the Quest 2 (the primary target, Q-12) or the app is no longer accepted there, users can still install and update it from a downloadable package. Side-loading is therefore a supported channel from the first release, not an afterthought: the app is a self-contained package with no dependence on store services for installation, licensing or updates, and **during development side-loading is the working install route** (debug builds pushed to the Quest 2 over USB or Wi-Fi with `adb` or SideQuest, no store review in the loop; a store test channel is used later for beta testers). **Side-loaded users get the package from the Galileo website** (galileo-imaging.com, alongside the other downloads), published with a checksum and a version number; to update, they download the newer package and install it over the old one. The app does not update itself: when the wire-API handshake shows the Master expects a newer app, it says so and points to the download page. The package must be signed with a stable release key so a newer package installs over an older one; whether the website package and the store build can share a key (and so update each other) is an SDD decision. The app ships with a short side-loading guide (the headset in developer mode, SideQuest, the package) |
| Q-9 | **Immersive planetarium (required, v1)**: the sky rendered as a full dome around the user for the Observatory's location and the chosen time, with the horizon from the Observatory's horizon profile, the live mount pointing and the optical train's field of view shown on it, time scrubbing, and select-to-go-to (an Operator action, with confirmation). Rendered on the headset from catalog and ephemeris data **streamed from the Master** (Q-13) |
| Q-10 | **Immersive image viewer (required, v1)**: the current exposure, live-stack result and any Library frame shown as a large virtual display, with pan, zoom, stretch and compare (blink or side-by-side), from node-rendered previews delivered as tiles |
| Q-11 | **Further VR adaptations** — the framing assistant on the dome, a three-dimensional view of the mount and dome, an all-sky-camera overlay, the session/schedule as a spatial view, and others — are analysed in [analysis/QuestVR.md](analysis/QuestVR.md) and are chosen and ordered in the SRS |
| Q-12 | **Quest 2 first; Quest 3 support by demand.** Every Quest requirement is met on a Quest 2 before anything else: it is the development and test device, the performance baseline and the primary release target. Quest 2 headsets are inexpensive and widely available second-hand, so a user can dedicate one to astronomy — kept at the telescope or in the warm room, set up once and used only for Galileo — which is a usage the edition is designed for (a simple, fast start; no dependence on other apps or accounts beyond what Meta requires). Quest 3-specific work (higher resolution and GPU headroom, colour passthrough, mixed-reality features) is scheduled only once utilisation of the Quest 2 edition justifies it; utilisation is judged from the store's own install and usage figures and from user feedback, **not from in-app analytics** (Section 5) |
| Q-13 | **Stream as much as possible from the Master.** The Master is the source of truth for everything the app displays that is not purely presentation: the star and deep-sky catalog (requested by sky region, limiting magnitude and level of detail as the user looks around), ephemeris (positions of the Sun, Moon, planets and minor bodies for the displayed time, including a time range for scrubbing), the Observatory location and horizon profile, pointing and field-of-view, ranked targets and altitude curves, image tiles and previews, the all-sky image, and live state. The headset **renders** this data locally; it does not receive rendered video (which would add latency and cause discomfort in a headset). It ships no catalog and computes no ephemeris of its own, so it can never disagree with the Master. Applies to the immersive scenes first, and to the tablet and phone planetarium and sky views in the same way. Consequences accepted: the scenes need the link to the Master (a short-lived cache bridges dropouts, Section 13, Q9), and the Master carries the load. **Serving the Meta Quest edition requires a Master running on at least a PC or Mac** (Windows, macOS or Linux desktop/laptop-class hardware), not a Raspberry Pi-class computer; this is an accepted minimum for VR. The streaming service is still compact (binary, delta-coded, bounded per client) and yields to the running session |
| Q-14 | **Master hardware tiers.** *Tablet, phone and web clients* work with any Master Galileo supports, including a Raspberry Pi 5-class computer, using lightweight streaming limits. *The Meta Quest edition* (immersive scenes, Q-9/Q-10) is supported only against a Master on PC/Mac-class hardware. A Pi-class Master reports to the headset that VR streaming is unavailable (via the version/capability handshake) rather than attempting it. Raspberry Pi nodes remain fully usable as **spokes**, reached through such a Master |

Quest 3, Quest 3S, Quest Pro and later headsets on the same system are expected to run the Quest 2 build but receive no specific support in v1 (Q-12, Section 13, Q7).

## 5. Non-Goals (Explicitly Out of Scope)

- **No configuration**: equipment, Observatories, safety limits and rules, Options, plugin options, notification rules.
- **No background push notifications in v1** (they need an internet relay; alerts arrive while the app is connected).
- **No internet relay or cloud account**; the app reaches the Master over the user's network (LAN or the user's own VPN).
- **No offline operation**; no cached plan executed later.
- **No FITS processing on the device**; previews are rendered on the node; FITS download is an explicit, optional action.
- **No GPL**: the apps are proprietary (the plugin and wire API they use are open).
- **No watch, TV or desktop builds**; no Android-only or iOS-only features that break parity (G3). The Meta Quest edition differs from the Android app only in presentation and input, not in function.
- **No mixed-reality scene anchoring in v1** (passthrough is for wearer safety only), no Apple Vision Pro build, and no headset other than Meta Quest 2/3 as a commitment.
- **No analytics or advertising**; the apps talk only to the user's own Master. (Decisions that depend on usage, such as Quest 3 support, use the stores' own install figures and user feedback.)
- **No private API**: anything the apps do goes through the documented wire API, so any other client could do the same.

## 6. Dependencies

| Dependency | Source | Why |
|---|---|---|
| MobileConnect plugin, Connect Link, Control Surface, control lease, proxying | Galileo repository, `docs/plugins/mobileconnect/PSD.md` and `docs/plugins/nodeconnect/PSD.md` §6 | The server side of every function; **proposed core additions not yet implemented** — the apps cannot be completed before them |
| Wire-API description (OpenAPI plus event-stream schema) | Published by the plugin; pinned copy in `api/` | Generation of both clients; contract tests |
| Platform services | Apple (Keychain, local-network permission, Bonjour), Google (Keystore, NSD/mDNS, foreground rules) | Secure storage, discovery of the Master, background behaviour |
| Store accounts | Apple Developer Program, Google Play Console, held by Gord Tulloch; **Meta developer account and Meta Horizon Store listing for the Quest edition** (holder to be confirmed, Section 13, Q8) | Distribution |
| Meta Quest platform | Meta Horizon OS (Android-based), Quest 2 as the primary target and test device, Quest 3 later; Meta's developer tools and store policies; SideQuest for side-loading | Running and distributing the Quest edition |

## 7. Requirement Domain

One domain, **`MAPP`** (Mobile App), specified in the common SRS. Provisional sub-areas:

| Area | Covers |
|---|---|
| Connection and pairing | Discovery of the Master, manual address, pairing request and code, token storage, certificate pinning, revocation handling, reconnection |
| Observatory overview | Pier list across nodes, activity timelines, alerts |
| Operating screens | One sub-area per operational area (G5), including focus and the sky view |
| Sessions and schedule | Authoring, editing, running; `.gses` import/export |
| Images | Preview display, pan/zoom, stretch, histogram, download |
| Safety and control | Roles, lease, confirmation, emergency, deadman, behaviour on disconnect |
| Layouts and theming | Tablet layouts, phone reductions, night theme, accessibility, localisation |
| Platform, build and release | Minimum OS versions, store packaging, privacy declarations, update behaviour, API-version handshake |
| Meta Quest edition | Own module, panel layouts, immersive planetarium and image viewer, pointer/controller/hand input, virtual keyboard, passthrough and wearer-safety screens, no-Google-services constraint, Meta Horizon Store packaging |

## 8. Stakeholders and Primary Personas

- **The backyard / remote-site imager** with a tablet, indoors, watching and nudging the night.
- **The multi-site operator** who sees several nodes in one app through the Master.
- **A guest** in the Viewer role who may look and not touch.

## 9. Project Constraints

| # | Constraint |
|---|---|
| PC1 | **Native tooling per platform** with shared logic below the UI (G1, G2); no cross-platform UI toolkit that replaces native screens |
| PC2 | Minimum platform versions are an SRS decision (candidates: iOS/iPadOS 17, Android 10/API 29, to be confirmed against the device base and the store policies) |
| PC3 | All traffic is encrypted and authenticated; the token lives only in secure storage; there is no unauthenticated route and the app never logs or displays secrets |
| PC4 | The app must keep working when the OS suspends it: it holds no state the node depends on, and reconnects and resynchronises on resume |
| PC5 | Imaging on the node never depends on the app; the app issues commands and displays state only |
| PC6 | The wire API is versioned; on a version mismatch the app says whether to update the app or the Galileo node, and does not attempt operations it cannot be sure of |
| PC7 | Data budget: previews are node-rendered and scaled; the app is usable over a weak link |
| PC8 | Store policy compliance: local-network permission prompts on iOS, foreground-service and permission rules on Android, privacy declarations stating that no data leaves the user's own network |
| PC9 | Accessibility: dynamic type / font scaling, screen-reader labels, sufficient contrast in the night theme; touch targets sized for use in the cold with gloves on |
| PC10 | The Meta Quest edition uses no Google Play Services or other components absent from Meta Horizon OS, and **its performance budget is set by the Quest 2** (the Quest 3 runs the same build faster; nothing is designed that needs it in v1); 2D panel text must be legible at a normal virtual viewing distance |

## 10. Documentation Structure

The PSD, SRS and RTM are **common** to both apps: they describe one product and the behavioural parity (G3) it promises. The SDD is split because the platforms genuinely differ in architecture, but not purely per platform, because a shared layer exists:

| Document | Scope |
|---|---|
| `docs/PSD.md`, `docs/SRS.md`, `docs/RTM.md` | Both platforms. The RTM maps each requirement to its design component on **each** platform and to a test, so a gap in either platform shows up |
| `docs/SDD.md` | **Shared architecture**: the shared module (model, state, session/schedule logic, API client), the contract with the wire API, security design, the parity test-vector suite |
| `docs/ios/SDD.md`, `docs/android/SDD.md` | **Per platform**: UI architecture, navigation, secure storage, discovery, background behaviour, packaging and release. |
| `docs/quest/SDD.md` | **Meta Quest edition**, its own SDD because of the immersive scenes (Q-9, Q-10): rendering stack, scene graph, catalog/ephemeris and tile data handling on the headset, input, comfort and performance budgets, Horizon Store packaging. It builds on the shared SDD and on the Android SDD for the panel screens |

The shared-architecture SDD exists because splitting the design only by platform would duplicate the shared module's design in two places and let them drift.

## 11. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | The server side (Connect Link, Control Surface) does not exist yet and the apps' scope depends on it | Develop against the wire-API description and a mock node; the apps' SRS follows the plugin SRS |
| R2 | The two apps diverge | Shared layer, shared test vectors, a parity checklist per release, RTM entries per platform |
| R3 | Kotlin Multiplatform in an iOS app adds toolchain complexity | Spike before committing (Section 12, Q2); the fall-back is two native clients generated from the same OpenAPI description with a shared test suite |
| R4 | Session/schedule authoring is a large UI task on a touch screen | Tablet-first; stage after the operating screens if needed |
| R12 | Streaming moves load and a dependency onto the Master: several headsets and tablets looking at the sky, or a weak link, could starve the session or leave a scene empty | VR requires a PC/Mac-class Master (Q-14), which removes the Raspberry Pi case; even so, compact binary protocol with delta updates, per-client rate and region limits, streaming at lower priority than the session, progressive detail (bright stars first), a short-lived on-device cache for dropouts, and a clear "link down" state in the scenes |
| R13 | A user with only a Raspberry Pi Master cannot use the Meta Quest edition | Stated up front in the store listing, first-run screen and documentation; the handshake tells the headset why; tablets, phones and the web remain available on a Pi Master |
| R5 | Mobile OS restrictions (local network, background execution) hurt connection reliability | Prototype early on real devices; reconnect-and-resync as the normal case (PC4) |
| R6 | A phone command moves hardware unattended | Viewer default, lease, confirmation, deadman; the node enforces everything |
| R7 | Store review: an app that controls astronomical hardware over a local network is unusual | Describe it plainly in review notes; no tracking; supply a demo node or mock mode for reviewers |
| R8 | A headset wearer cannot see the telescope, cables or surroundings | Q-6: passthrough one gesture away, first-run safety notice, indoor/seated use stated; no held motion by gesture alone |
| R9 | Meta Quest 2 is the primary target, yet it is an ageing platform: Meta's support and store requirements for it may change, and its hardware is the performance floor | Accepted by decision. Keep the dependence on Meta's SDK narrow and the shared layer platform-neutral; track Meta's policy on Quest 2; side-loading through SideQuest is the supported fall-back (Q-8); the headset being a cheap dedicated device limits the user's exposure to its eventual obsolescence |
| R10 | Pointer-and-click on a virtual panel is slower and less precise than touch for fine controls (sliders, planetarium drag) | Larger targets in the Quest layout, step buttons beside sliders, the tablet layout reused rather than shrunk |
| R11 | Quest 2 passthrough is low-resolution and black-and-white, a weaker wearer-safety aid than Quest 3's colour view | Q-6 relies on it only as a way to see the room's layout; the first-run notice and seated/indoor guidance are the real mitigation |

## 12. Success Criteria (Scope-Level)

- A new user pairs the app with their Master and sees live state for every Pier in a few seconds, on both platforms.
- The same night's essentials — connect, go to a target, centre by plate solve, focus, guide, run a saved session, author a schedule, respond to a flip or safety event — are done from a tablet alone, on both platforms, with identical results.
- Killing the app or its network mid-session leaves the session untouched; reopening resumes the live view.
- A Viewer cannot issue operations; no screen exists that edits configuration.
- The Meta Quest edition installs on a Meta Quest 2 (the acceptance device; a Quest 3 running the same build is a bonus, not a gate), pairs with the Master, and offers the Android app's operating functions from controllers or hands, with passthrough one gesture away.
- Both apps pass the same shared protocol test-vector suite and the RTM shows no requirement covered on one platform only.

## 13. Open Questions for Review

| # | Question | Recommendation |
|---|---|---|
| Q1 | Which parts of the phone layout may be dropped? | **TBD** from tablet wireframes |
| Q2 | Is Kotlin Multiplatform the shared layer, or a generated client plus shared specifications only? | Spike KMP first; keep the generated-client fall-back (R3) |
| Q3 | Minimum OS versions | Fix in the SRS from store statistics and API needs (PC2) |
| Q4 | Mock node for development and store review | Yes — a scripted fake Master, also used by the test suite |
| Q5 | App name and bundle identifiers | Decide before the first store submission; **Galileo MobileConnect** is the working name |
| Q6 | Rendering technology for the immersive scenes: Meta's native Android SDK (Kotlin) or a game engine such as Unity? | Decide in the Quest SDD by a spike; a native Android SDK keeps the shared Kotlin layer and the panel screens in one app, an engine eases heavy 3D at the cost of a second runtime |
| Q7 | When is Quest 3-specific support worth building? | When utilisation of the Quest 2 edition shows demand (Q-12); the trigger and the figures to use are set in the SRS |
| Q8 | Who holds the Meta developer account and Meta Horizon Store listing? | To be confirmed (the Apple and Google accounts are held by Gord Tulloch) |
| Q9 | How long and how large should the headset's cache of streamed data be? | Small and short-lived (minutes, bounded memory) to bridge dropouts and avoid re-fetching a region the user keeps looking at; no persistent catalog on the device |
