# Galileo MobileConnect Apps — SDD (Shared Architecture)

| | |
|---|---|
| **Project** | Galileo MobileConnect — iOS/iPadOS, Android and Meta Quest apps |
| **Document** | SDD (Software Design Description), v0.1 — **shared architecture**, common to all platforms |
| **Status** | Draft — for review |
| **Date** | 2026-11-06 |
| **Upstream documents** | [PSD](PSD.md) v0.8, [SRS](SRS.md) v0.1 |
| **Downstream documents** | [RTM](RTM.md); per-platform SDDs: [iOS](ios/SDD.md); Android and Meta Quest — not yet written |
| **Server-side counterpart** | Galileo repository: `docs/plugins/mobileconnect/SDD.md` (plugin) and `galileo/connect_link/`, `galileo/control_surface/` (core, implemented) |

---

## 1. Introduction

### 1.1 Purpose

This document fixes the architecture of everything below the UI: the Connect Link transport, the typed API client, the live-state store, the control-safety logic, session and schedule authoring, the streamed-sky cache, the mock Master, and the design tokens and strings. **Each of these is specified once, here, and implemented once per platform** (Swift for iOS, Kotlin for Android and Quest) on top of a client generated from one OpenAPI description (ADR-APP-001). The per-platform SDDs say how each app puts a native UI on top. Splitting the design this way (PSD Section 10) keeps the design in one place, and the shared test vectors (`S-TEST`) keep the two implementations behaving identically (PSD R2).

### 1.2 Scope

Components named `S-*` below. A requirement in the [SRS](SRS.md) is mapped to its `S-*` component, and to the platform component that presents it, in the [RTM](RTM.md).

### 1.3 Design status

**Nothing in this document is built.** It is a design to be agreed before code (project rule: scope, then SRS/SDD/RTM, then build). Statements about the server that were checked against the Galileo source on 2026-11-06 (Connect Link pairing, handshake, stream and transfer; the Control Surface catalogue and argument schema) are marked **[verified]**; everything else about the server is as promised by the plugin SDD and is marked **[planned]** until it exists. Section 4 lists where the two disagree.

### 1.4 References

[PSD](PSD.md), [SRS](SRS.md); Galileo `docs/plugins/mobileconnect/{PSD,SRS,SDD}.md`; Galileo `docs/plugins/nodeconnect/PSD.md` §6.2 (Connect Link); the Galileo source `galileo/connect_link/{server,pairing,handshake,stream,permissions,routes}.py` and `galileo/control_surface/{catalogue,schema,registry}.py`.

---

## 2. Design Considerations (ADRs)

### 2.1 Two native clients generated from one OpenAPI description (ADR-APP-001)

**Decision (2026-11-06):** the wire API is described once, in `api/openapi.yaml` (requests) and `api/events.schema.json` (event-stream messages). The request layer of each app is **generated** from that file: Swift with Apple's `swift-openapi-generator` (SwiftPM build plugin, `URLSession` transport) for iOS, and Kotlin with OpenAPI Generator (`kotlin` generator, OkHttp) for Android and Quest. Everything above the generated layer — pairing and pinning, the stream client, the state store, control safety, session and schedule logic, the sky cache — is **specified once in this document and written natively in each language**. There is no shared compiled module. This resolves PSD Q2 in favour of the fall-back named in PSD R3, and supersedes the Kotlin Multiplatform proposal this document first carried.

**What is generated and what is not.** OpenAPI describes request/response endpoints, so it generates the pairing, handshake, catalogue, operation, lease, preview, tile, transfer and sky calls and their models. It does **not** describe a WebSocket, so the event stream client (`S-LINK`) is hand-written on each platform and validated against `events.schema.json` and the test vectors. The same goes for certificate pinning, which is configured on the HTTP transport each platform supplies to its generated client.

**Parity is enforced by data, not by shared code.** The logic with the highest cost of divergence — confirmation flow, lease, held-motion stop rules, outcome-unknown handling, stream resynchronisation, staleness — now exists twice. The defence is `S-TEST`: recorded conversations and expected resulting states in `api/vectors/`, replayed through each platform's implementation in CI. A vector that passes on one platform and fails on the other fails the build (`MAPP-PLT-040`). The component specifications in Section 3 are written precisely enough to be implemented independently; where they are not, that is a defect in this document.

**Rationale:** no Kotlin/Native toolchain in the iOS app, no Kotlin–Swift bridge, idiomatic code in each language, and each app builds with its platform's own tools. The price is paying for the logic twice and the discipline of vectors to keep it aligned. It also lets Android and Quest (both Kotlin/JVM) share one Kotlin module between themselves, which they need anyway (PSD Q-1).

**Layout:** `api/` (contract, vectors, scenarios); `ios/Packages/GalileoAPI` (generated client) and `ios/Packages/GalileoCore` (hand-written core, plain Swift package, no UI); `android/api-client` (generated) and `android/core` (hand-written, pure Kotlin/JVM, no Android dependencies, reused by Quest).

**Consequence for Windows development:** the Android/Kotlin side, the contract, the vectors and the docs can all be built on Windows. The iOS side — generation, build, simulator, signing — needs macOS.

### 2.2 The node is the authority; the client never guesses (ADR-APP-002)

**Decision:** the store holds only what the Master has said. A command shows as *pending* from the moment it is sent and as *done* only on the Master's result (`MAPP-CTL-050`). There is no optimistic update of device state, and no client-side computation that substitutes for a node decision (ephemeris, visibility, limits, validation).

**Rationale:** the Master enforces role, lease, limits and interlocks regardless of the client; a client that predicts the outcome will sometimes be wrong, and in an observatory a wrong "done" is a hazard. It also keeps the client small and the iOS and Android apps behaviourally identical.

### 2.3 Snapshot, then deltas; staleness is state (ADR-APP-003)

**Decision:** the live view is a single `PierState` per Pier, rebuilt from the stream: a snapshot on connect, then sequenced deltas. On reconnect the client supplies its last `(epoch, seq)`; if the Master can replay the gap it does, otherwise it says `resync` and the client discards and rebuilds. Each value carries the Master's as-of time and the store derives an *age*. A value older than a per-key threshold, or any value while the stream is down, is rendered as stale, never as current (`MAPP-OVR-040`, `MAPP-STR-060`).

**Rationale:** this is what Connect Link's stream already does **[verified]** (`hello`/`resync`, `epoch`, per-stream `seq`, bounded replay ring, 20 s WebSocket heartbeat). Treating staleness as part of the state, not a UI afterthought, is what makes "never show stale as live" testable with the same vectors on both platforms.

### 2.4 Operations are driven by the catalogue (ADR-APP-004)

**Decision:** the app learns the operations a Pier offers from the Master's catalogue — name, typed arguments with range/unit/choices, class (`read`, `operate`, `confirm`, `emergency`), lease requirement, and *unavailable-because* text **[verified: `galileo.control_surface`; the wire route that publishes it is planned]**. Screens bind controls to operation names; argument limits shown in the UI (slider ranges, units, enum pickers) come from the catalogue, and the client validates against the same schema before sending. A control whose operation is absent or unavailable is shown disabled with the Master's reason (`MAPP-CON-090`).

**Rationale:** new Galileo operations then need no app release to be *visible*, an older node simply lacks entries, and the app's range checks cannot disagree with the node's.

**Limit:** session and schedule authoring needs block types and parameter schemas the same way (Section 3.5) and relies on the Master supplying them.

### 2.5 Two-step confirmation is the Master's; the dialog is the user's (ADR-APP-005)

**Decision:** an operation of class `confirm` is answered by the Master with `confirmation_required` and a single-use token bound to the operation and arguments, valid for a short time **[planned]**. The client shows a dialog that names the operation, its arguments and anything the Master says about consequences (for go-to: the target, altitude, and horizon/limit check, `MAPP-SKY-040`), and on acceptance repeats the request with the token. Declining sends nothing. Emergency operations skip the second round trip and instead carry the client's confirmation flag, accepted only from an Operator.

**Rationale:** confirmation enforced by protocol, not by a dialog, is the plugin's design (ADR-MCON-006); the app's part is to make the dialog informative and the flow identical on every platform.

### 2.6 A scripted mock Master is a first-class component (ADR-APP-006)

**Decision:** `S-MOCK` implements the Master's side of Connect Link and the Control Surface well enough to drive every screen: pairing (with a simulated approval delay), handshake, catalogue, a scripted night (slews, exposures, a flip, a safety event, a guide trace), the stream with deliberate drops and `resync`, lease contention, refusals and `confirmation_required`. It runs in-process for tests and as a labelled **demonstration mode** inside the apps (`MAPP-CON-110`).

**Rationale:** the Master side is only partly built (Section 4), the iOS target is developed away from any telescope, and store review needs something to look at (PSD R7). Because the demo and the tests use the same mock, the demo cannot rot.

### 2.7 Secrets are held by the platform, not by the core (ADR-APP-007)

**Decision:** each core defines a `SecureStore` interface (`get/put/delete` of the token and pinned fingerprint, per Master) and never persists them itself. iOS implements it with the Keychain, Android and Quest with the Keystore (`MAPP-CON-040`). Neither core logs request headers or tokens; `S-API` redacts the `Authorization` header in its logging hook.

**Rationale:** secure storage is inherently platform code; keeping the core behind an interface keeps secrets out of any serialisation path and makes the core testable with an in-memory store.

---

## 3. Component Design

```
 platform UI (SwiftUI / Compose / Quest panels + scenes)
     │ observes StateFlow/Flow;  calls suspend functions
 ┌───▼────────────────────────────────────────────────────────────┐
 │ per-platform core (specified here; Swift / Kotlin)             │
 │  S-CTL control safety ── S-SES sessions ── S-SCH schedule      │
 │  S-SKY streamed sky  ── S-IMG images                           │
 │  S-STATE live store  ◄── events                                │
 │  S-API typed client  (generated from api/openapi.yaml)         │
 │  S-LINK transport (pairing, pinning, handshake, stream)        │
 │  S-SEC SecureStore interface      S-MOCK scripted Master       │
 │  S-DSN tokens/strings (data)                                   │
 └───┬────────────────────────────────────────────────────────────┘
     │ HTTPS + WebSocket, token-authenticated, certificate pinned
   Master: Connect Link  → MobileConnect plugin → Control Surface → Galileo core
```

> **Reading the `S-*` components.** Each is a specification implemented once per platform (ADR-APP-001). Where a component says "the client", it means each platform's implementation, which must produce the same results on the shared test vectors.

### 3.1 `S-LINK` — Connection and transport

- **Discovery.** A `Discovery` interface (platform-implemented: Bonjour on iOS, NSD on Android/Quest) yields `{name, host, port, protocol}`; manual entry is validated here (scheme, host, port; default port 8765 **[verified]**) before any request is made (`MAPP-CON-010`, `020`).
- **Pairing.** `POST /connect/v1/pair {plugin:"mobileconnect", name, kind, platform}` returns `{request_id, code, expires_in, fingerprint, protocol}` (202); the client shows `code` and polls `GET /connect/v1/pair/{request_id}` until `approved` (the token is returned **once**), `rejected` or `expired` **[verified]**. The client sends nothing else before approval and offers no operation or state (`MAPP-CON-030`). The pairing request carries no credentials; its trust comes from the human check of `code` on the Master. Pairing requests are rate-limited (429) and the pending list is bounded (503) by the Master; the client surfaces both as plain-language messages.
- **Certificate trust.** The Master serves a self-signed EC P-256 certificate generated on first use (the owner may replace it with their own pair) **[verified]**; either way the app pins the SHA-256 fingerprint of the certificate it is shown. The `fingerprint` in the pairing response is the pin. Each platform's TLS callback implements `S-LINK`'s `PinPolicy`: accept only a certificate whose SHA-256 fingerprint equals the stored pin; **during pairing only**, accept the presented certificate for the one pairing exchange and compare its fingerprint with the one in the response body, then store it. A pin mismatch afterwards ends the connection and raises `IdentityChanged`, which the UI presents with an offer to pair again; there is no fall-back to an unverified connection (`MAPP-CON-050`).
- **Authentication.** Every request after pairing carries `Authorization: Bearer <token>` **[verified]**. A 401 on any request means revocation or expiry: `S-LINK` discards the token via `S-SEC`, publishes `Unpaired(reason)` and stops reconnecting (`MAPP-CON-060`).
- **Handshake.** After pairing and on every (re)connect, `POST /connect/v1/handshake` with the client's `{protocol, schema, galileo, plugin_api, plugin:{app_version, platform, client_class}}`; the verdict is `ok`, `restricted` or `refused` with a human message and the `allowed` permission set **[verified]**. The plugin's extension payload carries `areas`, `sky_stream` capability and API version **[planned]**. `refused` or an unknown plugin API stops all operations and names which side to update (`MAPP-CON-080`, `PLT-090`). The `allowed` set decides role display (Section 3.4).
- **Event stream.** `GET /connect/v1/stream?epoch=…&last=…` upgraded to WebSocket **[verified]**. Messages: `{"type":"hello"|"resync","epoch","seq"}` then `{"seq","data"}`. `S-LINK` owns the reconnect loop: exponential back-off with jitter, an immediate attempt when the OS reports the network or the app foreground returned, a passed `(epoch,last)` on every attempt, and `Connected | Reconnecting | Unreachable` as an observable (`MAPP-CON-070`). A `resync` makes `S-STATE` discard and re-request state (Section 3.3). The client does not poll.
- **Back-pressure.** The Master disconnects a slow client **[verified]**; the client treats that as an ordinary drop and replays.
- **Transport library.** The platform's own HTTP and WebSocket stack: `URLSession` and `URLSessionWebSocketTask` on iOS, OkHttp on Android and Quest. The generated client is handed the platform's configured session (pinning and the `Authorization` header included); the stream client uses the same trust configuration. `PinPolicy` is the specified behaviour; each platform implements it in its TLS callback.
- **Satisfies:** `MAPP-CON-010`, `020`, `030`, `050`, `060`, `070`, `080`, `090`, `100`, `120`; `MAPP-OVR-050`; `MAPP-PLT-090`.

### 3.2 `S-API` — Typed API client

- **Generated.** The request/response calls and models are generated from `api/openapi.yaml` (ADR-APP-001); nothing in the generated layer is edited by hand. The hand-written part is a thin facade over it that adds the behaviour below. Planned routes in the description are marked `x-galileo-status: planned` and are generated like the rest but cannot be exercised against a real Master yet.
- **Catalogue.** Fetches and caches the catalogue per Pier (`GET …/catalogue`), exposes `Operation(name, area, args[], class, leaseRequired, availableBecause?)`, and rebuilds when the stream signals a catalogue change **[planned route]**.
- **Invocation.** `invoke(pier, name, args, options)` → `OpResult`, a sealed type mirroring the Master's typed outcomes: `Ok(value)`, `Refused(reason, kind)` (including `lease_held{holder, canTake}` and `safety`), `Invalid(path, reason)`, `Unavailable(reason)`, `Busy(reason)`, `Unreachable(reason)`, `ConfirmationRequired(token, summary)`. Arguments are validated first with the catalogue's own schema (types `int/float/str/bool/enum`, min/max, unit) **[verified schema]**. An unexpected HTTP error is mapped to `Unreachable`/`Unavailable`, never shown as success.
- **Idempotency.** Every state-changing request carries a fresh `Idempotency-Key`; a retry after an uncertain outcome reuses the same key (Section 3.4, outcome-unknown) so the Master returns the original result **[planned]**.
- **Identity.** Every request carries `User-Agent: GalileoMobileConnect/<version> (<platform>)` and the platform/app version in the handshake, so the Master can audit who did what (`MAPP-CTL-080`). No other identifying data is sent.
- **Configuration is not representable.** The client has no type or function for a configuration operation. Catalogue entries of class `config`, or any name outside the allowed areas, are dropped on receipt, and the app declares no `config.read`/`config.write` use; the `allowed` set from the handshake is checked and the app **refuses to proceed** (with an explanation) if it contains `config.write` for this client (`MAPP-CFG-010`, `030`). **[verified]** that a MobileConnect peer is meant to hold only `control`/`view` — see Section 4, item 5.
- **Allowed areas.** Operations are grouped by the SRS areas: devices, imaging, solve, focus, guiding, flip, sessions, schedule, calibration, safety (read-only), library, sky. Focus movement is accepted **only** from `focus.*` operations (`focus.move_to`, `focus.move_by`, `focus.halt`, `focus.hold_*`); a generic focuser movement entry, if one ever appeared, is not surfaced (`MAPP-FOC-030`).
- **Satisfies:** `MAPP-DEV-*`, `MAPP-IMG-010`, `020`, `MAPP-SOL-*`, `MAPP-FOC-*`, `MAPP-GUI-*`, `MAPP-FLP-*`, `MAPP-CAL-*`, `MAPP-CFG-*`, `MAPP-CTL-080`.

### 3.3 `S-STATE` — Live state store

- **Model.** `ObservatoryState` → `PierState` per Pier: connection/reachability and grant (`control | view | none`), device status by category, session progress by block, guiding error, focus state, flip state and time to flip, safety inputs and overall state, weather, activity timeline lanes (as the Dashboard of `OBS-150`), lease holder, recent alerts. Each leaf is `Stamped<T>(value, asOf)`.
- **Reduction.** A pure function `reduce(state, message) → state` over stream messages. It is deterministic and has no clock or I/O, so the shared test-vector suite (`S-TEST`, Section 3.12) can replay recorded streams on every platform and require identical state.
- **Snapshot/resync.** On `hello` without prior state, or on `resync`, the client asks for a snapshot (`POST /snapshot`, Section 4.2 item 13), which arrives on the stream, and holds the UI in *Loading* until it arrives, targeting a first usable state in 5 s (`MAPP-OVR-070`). Deltas received before the snapshot are buffered, then applied in order, except any older than the snapshot, which would otherwise overwrite it with an old value.
- **Staleness.** `age(key) = now − asOf`. The Master publishes a value only when it changes (`ObservableState.publish`), so silence means "unchanged", and only keys that move continuously have a *freshness limit* (seconds for position, minutes for weather). Over the limit, or whenever `S-LINK` is not `Connected`, `StateView` marks the value stale and the UI must dim it and show its age; unreachable/detached/not-granted Piers carry an explicit reason from the Master and show no values (`MAPP-OVR-040`).
- **Operation events.** `op.started | progress | finished` tie to the originating request via correlation id; `S-CTL` consumes them (Section 3.4).
- **Satisfies:** `MAPP-OVR-010`–`070`, `MAPP-FLP-010`, `MAPP-SES-030`, `MAPP-SAF-010`, `030`, `MAPP-GUI-020`, `MAPP-DEV-070`.

### 3.4 `S-CTL` — Control safety

- **Role.** Derived from the grant in `whoami` (Section 4.2, item 8): `Viewer` (read-class only) or `Operator`. In Viewer mode `S-CTL` exposes no invocable operation other than reads, so a UI cannot call one by mistake; the Master enforces regardless (`MAPP-CTL-010`).
- **Lease.** Per Pier: `Free | HeldByMe | HeldByOther(who)`; `acquire`, `release`, `request(take)` with the Master's confirmation; every lease-requiring operation checks local state first for a better message but trusts the Master's refusal (`MAPP-CTL-020`, `030`). A refused operation is shown with the Master's reason and **never retried automatically**.
- **Confirmation.** Implements ADR-APP-005 and the list of operations that always require a dialog on the client side (`MAPP-CTL-040`): abort series, stop session, park, unpark, flip, reject frame, delete job/session. This list is a *minimum*; whatever the Master marks `confirm` also prompts.
- **Outcome.** Each in-flight operation is `Pending(requestId)` → `Done(result)` or `OutcomeUnknown`. If the stream drops while an operation is `Pending`, it becomes `OutcomeUnknown`, is shown as such until `S-STATE` has resynchronised and the finish event or the resulting state resolves it, and is **never re-sent automatically** (`MAPP-CTL-070`). The user may re-issue it deliberately (a new idempotency key).
- **Holds (jog, focus nudge).** `HoldSession.start(op, args)` → starts the hold via the operation, then sends beats at a fixed period (initially 250 ms, plugin SDD P4) until `stop()`. `stop()` is called by: release, platform lifecycle callback (background/inactive), connection loss, lease loss, or any error from a beat. On connection loss the client cannot reach the Master to stop it, so it relies on the Master's deadman (600 ms proposed); the UI says so. The shared code owns the timing and stop conditions; the platform only forwards lifecycle events and the press/release of the control (`MAPP-DEV-030`, `MAPP-CTL-060`). Beats are sent as HTTP operations (`*.hold_beat`) **[verified]** — see Section 4, item 3.
- **Emergency.** `system.stop_everything`, `mount.emergency_park` and `dome.emergency_close` are available to any paired Operator without the lease, with the client's confirmation flag after a deliberate gesture; the result lists each part and any that failed (`MAPP-EMG-010`, `020`).
- **Safety.** `S-CTL` has no function that disables the monitor, clears an unsafe state, resumes after a safety stop or overrides a limit. The only safety action is *acknowledge* (`MAPP-SAF-020`, `040`).
- **Satisfies:** `MAPP-CTL-010`–`080`, `MAPP-DEV-030`, `MAPP-SAF-020`, `040`, `MAPP-EMG-*`, `MAPP-SKY-040`, `MAPP-FLP-020`, `MAPP-SES-020`, `MAPP-IMG-070`.

### 3.5 `S-SES` — Session authoring and running

- **Model.** A session is an ordered, nestable tree of blocks (instruction, condition, trigger), each with a type, parameters and children, matching the Master's Sessions model. Block types, parameter schemas, nesting rules and validation come **from the Master** (catalogue entries for block types, including plugin-registered ones, `SES-340`); the client has no hard-coded block list **[planned: needs a block-type catalogue on the wire]**.
- **Edit operations.** Add, remove, move, indent/outdent (nest), duplicate, set parameter. Each is a pure transformation of the tree with undo/redo. Parameter editors are generated from the parameter schema (the same `Arg` types as operations), so the form for a block type needs no per-block UI code on any platform.
- **Validate and save.** The Master validates; the client sends the tree and shows the Master's messages against the block paths they name, **keeping the user's draft in every failure case** (`MAPP-SES-050`). Drafts are held in memory and may be kept on device only as an unsaved-draft convenience; a saved session exists only on the Master.
- **Running.** `run/pause/resume/skip/stop` are operations (stop confirms). Progress is `PierState.session`, block by block. While a session runs, editing is allowed or restricted per the rule the Master reports (`MAPP-SES-070`).
- **`.gses`.** Import and export of the interchange file via a platform document picker/share sheet; parse and serialise in shared code with the same schema as the desktop.
- **Satisfies:** `MAPP-SES-010`–`070`.

### 3.6 `S-SCH` — Scheduler

Job queue as read from the Master; create/edit/delete/enable/reorder/run-now as catalogue operations with the same edit-validate-save pattern as `S-SES`. "Add a job from a target" takes the sky view's selection and the framing result. **Satisfies:** `MAPP-SCH-010`–`030`, `MAPP-SKY-050`.

### 3.7 `S-SKY` — Streamed sky data

- **No local astronomy.** The client ships no catalog and computes no ephemeris (`MAPP-STR-010`, `020`). It requests by `(region, limiting magnitude, level of detail, cursor)` and receives a compact binary delta frame, and an ephemeris time series for an instant or a range, interpolated locally for smooth scrubbing. Turning a position into the view is a change of coordinates and is done here; whether something is above the horizon is the Master's answer (`/sky/check`). The binary format is `galileo.skystream`'s encoding 1; `S-SKY` isolates it behind a `SkyFrameDecoder`, tested against a frame written by core's own encoder (`api/vectors/sky/`), so a change is one component.
- **Progressive rendering.** Requests proceed brightest-first and refine as the view settles; the view requests a region at the level of detail its field of view needs (`MAPP-STR-040`).
- **Cache.** A bounded in-memory LRU (150,000 objects and 15 minutes to begin with, to be checked by device measurement, PSD Q9) keyed by `(region, magLimit, lod, asOf-version)`; no persistence. Cached data is used while the link is down for continuity but is marked **not live** and the view says so (`MAPP-STR-050`, `060`).
- **Rate limits.** The Master reports per-client limits in the capability; `S-SKY` throttles its own requests to them and treats `busy` as back-off, not as an error (`MAPP-STR-070`). On a Master whose `sky_stream` capability is `reduced` or unavailable, the sky view degrades to the reduced mode it offers or states why it is unavailable (`MAPP-CON-090`).
- **Selection and go-to.** Hit-testing is done on what the view holds; the object identity comes from the Master's data. Go-to calls `S-CTL` with the confirmation content (`MAPP-SKY-040`).
- **Satisfies:** `MAPP-SKY-010`–`060`, `MAPP-STR-010`–`070`; shared by the Quest scenes (`MAPP-QST-110`–`140`, `240`, `250`).

### 3.8 `S-IMG` — Images and Library

- **Previews.** `previewUrl(pier|frame, width, quality, stretch)` yields node-rendered JPEG/PNG; the client picks width from the device's pixel width and the link-quality estimate (round-trip time and recent throughput) and exposes a data-saver setting that caps both (`MAPP-IMG-090`). Stretch parameters are sent to the Master so statistics and rendering agree with the desktop; the histogram and star statistics come from the Master (`MAPP-IMG-040`, `050`).
- **Tiles.** `tile(frame, z, x, y, params)` for zoom; the tile pyramid addressing is the Master's. Frames are addressed by id, so two frames can be held at once for blink and side-by-side (`MAPP-QST-180`).
- **FITS download.** An explicit action that first asks for the size, then transfers with Connect Link's chunked, resumable, checksummed transfer (`/connect/v1/transfer/{sha}` **[verified]**: `PUT/GET` chunks with `X-Chunk-SHA256`, `GET …/chunk?offset&length`, resumable by status) (`MAPP-IMG-080`).
- **Library.** Recent sessions, frame lists with metrics, and charts are Master data presented read-only (`MAPP-LIB-*`).
- **Satisfies:** `MAPP-IMG-030`–`090`, `MAPP-LIB-010`, `020`; `MAPP-QST-170`, `180`.

### 3.9 `S-MOCK` — Scripted mock Master

See ADR-APP-006. It is two things driven by the same data: a small **in-process player** on each platform that implements the transport interface `S-LINK` uses (for unit tests, UI tests and the in-app demonstration mode, so the real client code runs against it), and a **standalone mock server** (`tools/mock-master/`) that speaks the real HTTPS/WebSocket protocol for contract tests and for trying a build with no Master. A **scenario** (`api/scenarios/`) is a script (data, not code) of timed state changes and message sequences; scenarios cover: a first pairing, a revoked pairing, a certificate change, a normal night, a safety event, a meridian flip, lease contention, a dropped and resynchronised stream, a Pi-class Master without sky streaming, an older and a newer Master. **Satisfies:** `MAPP-CON-110`, `MAPP-PLT-040`.

### 3.10 `S-DSN` — Design tokens, icons, strings

Data files in `design/` compiled into platform resources by `scripts/gen_tokens.py`: colour tokens for the **standard** and **night** themes (the night theme is red-only, `MAPP-LAY-030`) with contrast checked in a build-time test; type scale; spacing and minimum target size (`MAPP-LAY-050`); icons as SVG; strings as a single key→text table per language with placeholders, so both platforms use the same wording (`MAPP-LAY-060`). **Satisfies:** `MAPP-LAY-030`, `040` (tokens), `050`, `060`, `MAPP-PLT-020`.

### 3.11 `S-SEC` — Security design

| Concern | Design | Requirements |
|---|---|---|
| Token | Only in the platform `SecureStore`, one per Master; removed on unpair, revocation (401) and by the platform when the app is deleted; never logged, never in crash reports, never on screen; HTTP logging redacts `Authorization` | `CON-040`, `CON-060`, `CON-100`, PC3 |
| Server identity | Fingerprint pin from pairing; mismatch is fatal to the connection; no trust-on-every-use | `CON-050` |
| Transport | TLS only; no cleartext fallback; platform ATS/network-security configuration disallows cleartext | PC3 |
| Pairing | Human-verified six-digit code on both screens; the app displays it and never offers the code for entry; polling stops on `rejected`/`expired` | `CON-030` |
| Privilege | The app holds only `control`/`view`; refuses a grant containing `config.*` | `CFG-030` |
| Data | No analytics, no third-party SDK that communicates off-network; the only network peer is the user's Master; the store privacy declaration states this | `PLT-060` |
| Diagnostics | Logs are local, redact secrets, and contain no site location, coordinates of targets or image data unless the user shares them deliberately | PC3 |

### 3.12 `S-TEST` — Parity test-vector suite and release checklist

- **Test vectors.** Recorded conversations (pairing, handshake, a stream with drops, operation results of every typed outcome, confirmation flow, lease contention, hold start/beat/stop, sky frames) as files in `api/vectors/`, produced from the Galileo plugin's tests once they exist and from `S-MOCK` until then. Each file also carries the user's and platform's actions as `act` steps, so a runner needs no knowledge of individual vectors (`api/vectors/README.md`). Each platform's test run replays every vector through its own implementation and compares the resulting state/outcome to the expected file; a vector that passes on one platform and fails on another fails the build (`MAPP-PLT-040`).
- **Parity checklist.** A per-release checklist generated from the RTM: any requirement whose *Platforms* column includes platform X and whose design or test cell for X is missing is listed and must be resolved or recorded as staged with a reason (`MAPP-PLT-030`).
- **Satisfies:** `MAPP-PLT-030`, `040`.

---

## 4. Contract With the Wire API

### 4.1 What is pinned

`api/openapi.yaml` is the contract both clients are generated from, and `api/events.schema.json` describes the stream messages. Both are **authored in this repository**, and the plugin in `Galileo-Plugins/mobileconnect` is written to them, rather than publishing the `wire/` files the plugin SDD promises (§3.16). Operations are tagged by status:

- **`x-galileo-status: implemented`** — tested against the real server code: Connect Link pairing, handshake, whoami, stream and transfer, and the draft plugin's routes (`real_link_server.py`, `LiveMasterTests.swift`).
- Nothing is `planned` any more: the sky routes are drafted too.

Corrections from generating the Swift client and re-reading the source (2026-11-06), all made in the description to match the running code:

- **403 is plain text, not JSON.** The listener raises aiohttp's `HTTPForbidden`, whose body reads `403: needs <permission>`. Described as JSON, the generated client would have thrown a decoding error instead of returning its `forbidden` case.
- **`transferGetChunk` also answers 404 and 503.** 404 means no such object; 503 means busy, with `Retry-After`. **`transferStatus` also answers 400** for a malformed hash.
- `pollPairing` has no error status: an unknown request is a 200 with `state: expired`.
- `Operation.class` is a string, not an enum, so that an entry of an unknown class is dropped on receipt rather than failing the decoding of the whole catalogue.

**The catalogue in `api/scenarios/catalogue.json` is the real one**, dumped from a Galileo checkout by `tools/mock-master/dump_catalogue.py`, so the app and the mock can't drift from the operations core really registers. **The state keys are a proposal:** `api/scenarios/normal-night.json` is what the screens read (`mount.tracking`, `imaging.stats.hfr`, `activity.mount`, and so on), written in the dotted form `galileo.control_surface.state` already publishes, and the plugin needs to agree them.

This file is the executable statement of what the apps expect; `S-MOCK` and the plugin both implement it, and `real_link_server.py` tests the second.

### 4.2 Where the server design and the running code differ

These were found by reading the Galileo source against the plugin SDD. Items 1-3, 6, 7 and 12-14 are settled by the draft plugin (`Galileo-Plugins/mobileconnect`) as the last column says; the rest are open with the server side.

| # | Plugin SDD says | Galileo core does today | Effect on the app | Proposal |
|---|---|---|---|---|
| 1 | Plugin routes under `/mobileconnect/v1/...`, including `WS /stream` | Core stream is at `/connect/v1/stream` (one WebSocket per peer, shared by all plugins of that peer), messages `{seq, data}` with `hello`/`resync` | Two possible stream URLs | **Settled:** the core stream; the plugin publishes its `snapshot`/`delta`/`op.*` messages as the `data` payload |
| 2 | Client resumes with `?last_seq=` | Core uses `?epoch=…&last=…`; the epoch detects a re-created stream | Resume parameters differ | **Settled:** the core's |
| 3 | `hold.beat` is a client→node stream message | Core's stream **ignores** client messages (`_read` discards them); the shipped beat is the operation `focus.hold_beat` over HTTP | The beat path and cadence differ | **Settled:** beat as an HTTP operation, which is built and tested; measure latency on Wi-Fi (plugin SDD P4) |
| 4 | Capability payload with `areas`, `sky_stream`, `outreach` | Core handshake supports a plugin `extension` payload; the plugin does not exist yet | None until built | Pin the extension schema with the plugin |
| 5 | Roles Viewer/Operator kept in peer metadata | Core peers hold *permission sets* (`control`, `view`, `config.*`, `data.*`) | The app derives role from `allowed`; the Master must grant `control` for Operator and `view` for Viewer, and never `config.*` | Confirm the mapping with the server; make the handshake or `whoami` state the role explicitly |
| 6 | Pairing request identity: name, platform | Core `POST /connect/v1/pair` takes `plugin`, `name`, `kind`, `platform` | Fields to fill | **Settled:** `plugin:"mobileconnect"`, `kind:"app"`, `platform:"ios"` or `"ipados"` |
| 12 | Routes `/pier/{pier}/ops/{name}`, `/pier/{pier}/lease`, `/pier/{pier}/preview`, `/frame/{id}/tiles/...` | Connect Link's `RouteRegistry` matches exact paths: no templates | Those routes can't be registered | **Settled:** flat routes with query parameters (`POST /ops?pier=&name=`), as WebConnect does |
| 13 | On connection the client receives a snapshot (ADR-MCON-004) | `StreamHub` has no connection hook, so a plugin can't know | The snapshot can't be pushed | **Settled:** the client calls `POST /snapshot` after `hello` with no state and after `resync`; the snapshot arrives on the stream, in sequence |
| 14 | A stretch of black, mid and white points | `galileo.preview.PreviewParams` has one value, 0-100 | A control the server can't honour | **Settled:** one stretch control |
| 15 | State pushed on change from aggregated bus subscriptions | `ObservableState` publishes only `mount.position`, `mount.tracking`, `guiding.state`, `guiding.rms`, `weather.readings`; the rest is in read-class `*.status` operations | Most of what the screens show has no push source | **Interim:** the plugin samples the status operations every 2 s while an app is connected and sends what changed. Better done by core publishing more |
| 8 | Role comes from the handshake's `allowed` set, and the app refuses a grant containing `config.*` | `allowed` is built from all of `PAIRED_PERMISSIONS`, so it always contains `config.*`, whatever the peer was granted | Reading `allowed` would refuse every real Master | **Implemented:** role and the refusal use `whoami.permissions` (intersected with `allowed` for the role). Confirm with the server |
| 9 | Emergency operations named `emergency.*` (Section 3.4 said so too) | `system.stop_everything`, `mount.emergency_park`, `dome.emergency_close`, class `emergency` | Names differ | **Implemented** with core's names; Section 3.4 corrected |
| 10 | The app reports `schema: 0` in the handshake | A schema level different from the Master's gives verdict `restricted` with a message about databases, and removes `data.*` from `allowed` | Every real Master answers `restricted`, with a message meaningless to an app user, and the handshake would remove `data.pull`, which FITS download needs | **Implemented:** `restricted` with `control` or `view` proceeds and its message isn't shown. The plugin's handshake extension should exempt app peers |
| 11 | The SRS's MVP covers these | Core registers no operation for: connecting and disconnecting devices, held mount movement, find home, tracking rate, pulse, region of interest, autofocus, live loop, skipping a session step, deferring a flip, the current filter. The wire has no nested session blocks. `guiding.star` returns raw pixels with no route for a picture of them | Those controls can't work on a real Master | `api/scenarios/catalogue.planned.json` proposes names for the first few, and the screens show "doesn't offer this yet" without them. The server side decides |
| 16 | `skystream.py` serves regions of positions, and takes the ephemeris and site as inputs | Nothing ran it, it has no names, and nothing supplies those inputs | No sky view on a real Master | **Settled:** the plugin runs it, adds the planets from astropy and the site and horizon from the Observatory record, and adds `/sky/constellations` from core's star atlas, `/sky/identify` for names and `/sky/check` for the horizon check |
| 7 | Sky stream, previews, tiles, frames as MobileConnect routes | Core has `skystream.py` and `preview.py` services; the routes that expose them are not registered (no plugin) | `S-SKY` can't be exercised against a real Master | **Settled:** the plugin's routes over `galileo.preview` and `galileo.skystream` |

---

## 5. Open Design Points

| # | Point | Decision needed |
|---|---|---|
| D1 | ~~KMP vs generated clients (PSD Q2, R3)~~ | **Closed 2026-11-06:** generated native clients (ADR-APP-001). Follow-up: run both generators on the description (Section 6) and fix whatever they reject |
| D2 | Hold beat cadence and the Master's deadman (proposed 250 ms beat / 600 ms deadman) | Measure on a real iPad over Wi-Fi |
| D3 | Cache size and lifetime for streamed sky data (PSD Q9) | Measure; set in this SDD |
| D4 | Format of the sky-stream binary frame | With the core streaming service |
| D5 | Block-type catalogue for session authoring is not yet on the wire | Server to expose block types and parameter schemas (`SES-340`) |
| D6 | Replacing the authored description with the plugin's own (Section 4.1) | When the plugin publishes it |
| D10 | A value's age compares the Master's clock with the device's, so skew larger than a freshness limit shows a moving value as stale (it can't show a stale one as live for long). Negative ages are clamped to 0 | Measure on real devices; correct for skew from the snapshot's `as_of` if it matters |
| D9 | ~~HTTP status codes for typed outcomes~~ | **Closed:** 400 `invalid`, 429 `busy`, 409 the others, never 5xx; the outcome is in the body |
| D7 | Findings 1–7 in Section 4.2 | With the server side |
| D8 | Phone layout reductions (PSD Q1) | After tablet wireframes |

---

## 6. Build, Environments and Constraints

| Work | Where it can be done |
|---|---|
| Docs, `design/`, `api/` (description, vectors, scenarios), `tools/mock-master` | Any machine, including Windows (Node can lint the description) |
| Kotlin client generation, `android/core`, Android app, Quest edition, JVM tests | Windows, macOS or Linux (JDK 17+, Gradle, Android Studio) |
| Swift client generation, `ios/Packages/*`, the iOS app, simulator, device, signing, TestFlight | **macOS with Xcode only** (local Mac, hosted Mac, or macOS CI) |

Consequence: the contract, the Kotlin side and the vectors can all be developed on Windows now; the iOS side needs a Mac. `scripts/sync_api.py` copies the single description into each generator's input location and, in `--check` mode, fails if a copy has drifted, so CI on either side catches an edit made in the wrong place. `scripts/check_ios.sh` runs the whole iOS check on a Mac: sync, tokens, vectors, lint, the colour rule, `swift test` (including over real TLS against `tools/mock-master`, and against the real Connect Link server when a Galileo checkout is alongside), then the app, core and UI tests on a simulator, and the Night pixel check on the UI tests' screenshots.

## 7. Requirement-to-Component Summary

The full per-requirement mapping is the [RTM](RTM.md). By area:

| SRS area | Shared component |
|---|---|
| `CON` | `S-LINK`, `S-SEC`, `S-MOCK` |
| `OVR` | `S-STATE` |
| `DEV`, `SOL`, `FOC`, `GUI`, `FLP`, `CAL` | `S-API` (+ `S-CTL` for confirmation, leases, holds) |
| `IMG`, `LIB` | `S-IMG` |
| `SKY`, `STR` | `S-SKY` (+ `S-CTL` for go-to) |
| `SES`, `SCH` | `S-SES`, `S-SCH` |
| `SAF`, `EMG`, `CTL` | `S-CTL` |
| `CFG` | `S-API` (no configuration representable) |
| `LAY` | `S-DSN` |
| `PLT` | `S-TEST`, `S-DSN`, `S-LINK` |
| `QST` | `S-SKY`, `S-IMG`, `S-CTL` for the shared parts; the rest in the Quest SDD |
