# Galileo MobileConnect Apps — PSD (Project Scope Document)

| | |
|---|---|
| **Project** | Galileo MobileConnect — iOS/iPadOS and Android apps |
| **Document** | PSD (Project Scope Document), v0.1, draft — **common to both platforms** |
| **Status** | Draft — for review |
| **Date** | 2026-10-27 |
| **Server-side counterpart** | MobileConnect plugin — `docs/plugins/mobileconnect/PSD.md` in the [Galileo](https://github.com/gordtulloch/Galileo) repository (operational scope, proxying, control model) |
| **Downstream documents** | SRS and RTM (common); SDD: shared architecture plus one per platform (Section 10) |

---

## 1. Purpose of This Document

This document defines the scope of the two **Galileo MobileConnect apps** — the iOS/iPadOS app and the Android app — which let a user operate a running Galileo observatory from a tablet or phone. It covers the **client**. What the node offers (the operations, the control model, the proxy through the Master) is specified by the server-side plugin PSD in the Galileo repository and is **consumed by reference here, not restated**; where the two documents touch, the plugin PSD's Section 6 is authoritative for the operational surface, and this one for how the apps present it.

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

## 5. Non-Goals (Explicitly Out of Scope)

- **No configuration**: equipment, Observatories, safety limits and rules, Options, plugin options, notification rules.
- **No background push notifications in v1** (they need an internet relay; alerts arrive while the app is connected).
- **No internet relay or cloud account**; the app reaches the Master over the user's network (LAN or the user's own VPN).
- **No offline operation**; no cached plan executed later.
- **No FITS processing on the device**; previews are rendered on the node; FITS download is an explicit, optional action.
- **No GPL**: the apps are proprietary (the plugin and wire API they use are open).
- **No watch, TV or desktop builds**; no Android-only or iOS-only features that break parity (G3).
- **No analytics or advertising**; the apps talk only to the user's own Master.
- **No private API**: anything the apps do goes through the documented wire API, so any other client could do the same.

## 6. Dependencies

| Dependency | Source | Why |
|---|---|---|
| MobileConnect plugin, Connect Link, Control Surface, control lease, proxying | Galileo repository, `docs/plugins/mobileconnect/PSD.md` and `docs/plugins/nodeconnect/PSD.md` §6 | The server side of every function; **proposed core additions not yet implemented** — the apps cannot be completed before them |
| Wire-API description (OpenAPI plus event-stream schema) | Published by the plugin; pinned copy in `api/` | Generation of both clients; contract tests |
| Platform services | Apple (Keychain, local-network permission, Bonjour), Google (Keystore, NSD/mDNS, foreground rules) | Secure storage, discovery of the Master, background behaviour |
| Store accounts | Apple Developer Program, Google Play Console, held by Gord Tulloch | Distribution |

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

## 10. Documentation Structure

The PSD, SRS and RTM are **common** to both apps: they describe one product and the behavioural parity (G3) it promises. The SDD is split because the platforms genuinely differ in architecture, but not purely per platform, because a shared layer exists:

| Document | Scope |
|---|---|
| `docs/PSD.md`, `docs/SRS.md`, `docs/RTM.md` | Both platforms. The RTM maps each requirement to its design component on **each** platform and to a test, so a gap in either platform shows up |
| `docs/SDD.md` | **Shared architecture**: the shared module (model, state, session/schedule logic, API client), the contract with the wire API, security design, the parity test-vector suite |
| `docs/ios/SDD.md`, `docs/android/SDD.md` | **Per platform**: UI architecture, navigation, secure storage, discovery, background behaviour, packaging and release |

The shared-architecture SDD exists because splitting the design only by platform would duplicate the shared module's design in two places and let them drift.

## 11. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | The server side (Connect Link, Control Surface) does not exist yet and the apps' scope depends on it | Develop against the wire-API description and a mock node; the apps' SRS follows the plugin SRS |
| R2 | The two apps diverge | Shared layer, shared test vectors, a parity checklist per release, RTM entries per platform |
| R3 | Kotlin Multiplatform in an iOS app adds toolchain complexity | Spike before committing (Section 12, Q2); the fall-back is two native clients generated from the same OpenAPI description with a shared test suite |
| R4 | Session/schedule authoring is a large UI task on a touch screen | Tablet-first; stage after the operating screens if needed |
| R5 | Mobile OS restrictions (local network, background execution) hurt connection reliability | Prototype early on real devices; reconnect-and-resync as the normal case (PC4) |
| R6 | A phone command moves hardware unattended | Viewer default, lease, confirmation, deadman; the node enforces everything |
| R7 | Store review: an app that controls astronomical hardware over a local network is unusual | Describe it plainly in review notes; no tracking; supply a demo node or mock mode for reviewers |

## 12. Success Criteria (Scope-Level)

- A new user pairs the app with their Master and sees live state for every Pier in a few seconds, on both platforms.
- The same night's essentials — connect, go to a target, centre by plate solve, focus, guide, run a saved session, author a schedule, respond to a flip or safety event — are done from a tablet alone, on both platforms, with identical results.
- Killing the app or its network mid-session leaves the session untouched; reopening resumes the live view.
- A Viewer cannot issue operations; no screen exists that edits configuration.
- Both apps pass the same shared protocol test-vector suite and the RTM shows no requirement covered on one platform only.

## 13. Open Questions for Review

| # | Question | Recommendation |
|---|---|---|
| Q1 | Which parts of the phone layout may be dropped? | **TBD** from tablet wireframes |
| Q2 | Is Kotlin Multiplatform the shared layer, or a generated client plus shared specifications only? | Spike KMP first; keep the generated-client fall-back (R3) |
| Q3 | Minimum OS versions | Fix in the SRS from store statistics and API needs (PC2) |
| Q4 | Mock node for development and store review | Yes — a scripted fake Master, also used by the test suite |
| Q5 | App name and bundle identifiers | Decide before the first store submission; **Galileo MobileConnect** is the working name |
