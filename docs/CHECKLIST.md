# Mobile Client Work — Checklist

Written 2026-11-06 to resume the work on a **Mac mini**. Tick items as they are done. Sections 0-6 were finished on 2026-11-06, apart from running on a real iPad; `scripts/check_ios.sh` re-runs their checks. **Section 5a lists what the MVP still needs.** Design authority: [PSD](PSD.md) → [SRS](SRS.md) → [Shared SDD](SDD.md) / [iOS SDD](ios/SDD.md) → [RTM](RTM.md). Approach: **two native clients generated from one OpenAPI description** (SDD ADR-APP-001), kept identical by shared test vectors.

## 0. Before leaving the Windows machine

- [x] **Commit and push this repository.** As of this checklist nothing from the 2026-11-06 session is committed: `docs/SDD.md`, `docs/RTM.md`, `docs/ios/`, `docs/CHECKLIST.md`, `api/`, `ios/`, `android/`, `scripts/`, and edits to `README.md`, `.gitignore`, `docs/PSD.md`, `docs/SRS.md`.
- [x] Confirm the Galileo core repo is pushed too if the Mac will run a real Master (the source of truth for the wire is `galileo/connect_link/` and `galileo/control_surface/`).
- [x] Note which Apple Developer account will own the app (PSD Q5 / Section 6: held by Gord Tulloch) — needed for device signing in section 3.

## 1. Mac mini setup

- [x] macOS current enough for the latest stable Xcode (iOS/iPadOS 17 is the app's minimum, `MAPP-PLT-010`).
- [x] Install **Xcode** (App Store or developer.apple.com); open it once, accept the licence, install the iOS simulator runtime; `xcode-select -p` shows the Xcode path.
- [x] Install **Git**, then clone `Galileo-MobileConnect`. Optionally clone `Galileo` (server) and `Galileo-Plugins` alongside.
- [x] Install **Python 3** (for `scripts/*.py`) and **Node** (to lint the description).
- [ ] Optional, for the Android/Kotlin side on the Mac: **JDK 17+** and Android Studio.
- [x] Sign in to Xcode with the Apple ID (Settings → Accounts) when you reach device testing.

## 2. Verify the contract (`api/`)

- [x] `python scripts/sync_api.py --check` → passes (no drift between `api/openapi.yaml` and the Swift package copy).
- [x] Lint the description: `npx @redocly/cli lint api/openapi.yaml` (it has **never been linted**; only a structural check was done: 32 `$ref`s resolve, 19 unique operation IDs). Fix whatever it reports, in `api/openapi.yaml` only, then re-run `sync_api.py`. Clean; the one warning (no 4xx on `pollPairing`) is a false positive, because the route always answers 200, and is recorded in `.redocly.lint-ignore.yaml`.
- [x] Validate `api/events.schema.json` and the two vectors in `api/vectors/` against it (no validator was available on Windows).
- [x] Compare `api/openapi.yaml` with the Galileo source once more for the seven `implemented` operations (`galileo/connect_link/server.py`, `pairing.py`, `handshake.py`, `transfer.py`) — it was checked on 2026-11-06 and may have moved. Re-checked the same day; differences fixed in the description (Shared SDD §4.1).

## 3. Generate and build the Swift client

- [x] `cd ios/Packages/GalileoAPI && swift build`. This is the **first time `swift-openapi-generator` has run on this description**; expect to fix issues (none arose: generator 1.14.4 accepted it unchanged). Common ones: `default` responses, `oneOf`/`additionalProperties`, a parameter named `y`, binary bodies, `default: {}` in `Arg`.
- [x] If the generator rejects part of the description, **change the description** (not the generated code), re-sync, rebuild. Record any workaround as a note in `docs/SDD.md` §4.
- [x] **Pin dependency versions** in `ios/Packages/GalileoAPI/Package.swift` (they are minimums: generator 1.2, runtime 1.4, urlsession 1.0) and commit `Package.resolved`.
- [x] Create the Swift package `ios/Packages/GalileoCore` (plain Swift, no UI imports; depends on `GalileoAPI`) with a `swift test` target.
- [x] Create the Xcode app project in `ios/` (universal iPhone/iPad, iOS 17 minimum, SwiftUI lifecycle), depending on both local packages. Done with XcodeGen (`ios/project.yml`). The bundle identifier `com.gordtulloch.galileo.mobileconnect` is a **working value**; deciding it stays open (PSD Q5, iOS IO5).
- [x] Info.plist: `NSLocalNetworkUsageDescription`, `NSBonjourServices` = `_galileo-link._tcp`, ATS with `NSAllowsLocalNetworking` only (iOS SDD §3.2, §3.3, §3.12).

## 4. Implement `GalileoCore` (Swift) — in this order

Each item is specified in [docs/SDD.md](SDD.md) §3; write the unit tests against `api/vectors/` as you go.

- [x] **Vector runner**: a test harness that loads `api/vectors/*.json`, feeds `receive`/`stream`/`clock`/`drop` steps to the client, and asserts `send` and `expect` steps. The two existing vectors are `pairing-approved` (implemented) and `stream-resync` (planned).
- [x] **`S-SEC`** `SecureStore` protocol, in-memory implementation for tests, Keychain implementation (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, no sync) — `MAPP-CON-040`.
- [x] **`S-LINK` pairing**: request, show code, poll, token once, store — `MAPP-CON-030`; `pairing-approved` vector green.
- [x] **`S-LINK` pinning**: `PinPolicy` in the `URLSession` delegate (pin the fingerprint from the pairing response; mismatch → `IdentityChanged`, never fall back) — `MAPP-CON-050`. Test against a Master with a self-signed certificate.
- [x] **`S-LINK` handshake**, version/capability handling and revocation (401 → unpaired) — `MAPP-CON-060/080/090`.
- [x] **`S-LINK` stream client** (`URLSessionWebSocketTask`): `hello`/`resync`, `epoch`+`last` resume, back-off reconnect, connection-state observable — `MAPP-CON-070`.
- [x] **`S-STATE`**: the pure `reduce(state, message)`, staleness/age, snapshot-then-delta, resync — `MAPP-OVR-*`; `stream-resync` vector green.
- [x] **`S-API` facade** over the generated client: catalogue, `invokeOperation` with typed `OpResult`, `Idempotency-Key`, argument validation from the catalogue schema, identity header, config operations unrepresentable — `MAPP-CTL-080`, `MAPP-CFG-*`.
- [x] **`S-CTL`**: role from the `allowed` set, lease, two-step confirmation, `Pending → Done | OutcomeUnknown` (never auto-retried), `HoldSession` (beat 250 ms, stop on release/background/loss/lease loss), emergency operations — `MAPP-CTL-*`, `MAPP-DEV-030`, `MAPP-EMG-*`.
- [x] **`S-MOCK` player**: in-process transport that plays scenarios (data in `api/scenarios/`, to be created) for tests, UI tests and demo mode — `MAPP-CON-110`.
- [ ] Later (P2/after MVP screens): `S-IMG`, `S-SKY`, `S-SES`, `S-SCH`.

## 5. First screens (SwiftUI) — iPad first

- [x] `I-BRIDGE` `@Observable` view models over `GalileoCore`.
- [x] `I-CONN`: discovery list (`NWBrowser`), manual address, local-network explanation screen, pairing screen with code and countdown, connection banner, demo mode entry.
- [x] `I-SHELL`: `NavigationSplitView`, top bar with Pier switcher, connection state, role/lease, safety indicator, emergency button.
- [x] `I-THEME`: tokens, **Night (red-only) theme at the scene root**, 56 pt minimum targets, Dynamic Type (`design/tokens.json`, compiled by `scripts/gen_tokens.py`; strings are still literals, `MAPP-LAY-060` is P2).
- [x] Overview, then Equipment → Imaging → Focus → Guiding → Safety (MVP set).
- [x] Run in the **simulator against the mock Master**: iPad and iPhone, by UI test, in demo mode and over real TLS with real pairing and the Keychain.
- [x] First run on a **real iPad** (iPad Pro 11-inch M4, iPadOS 26.6.1, 2026-11-06): the demo, the local-network prompt, Bonjour discovery, and pairing over real TLS with the standalone mock Master.
- [ ] On the iPad: held controls and the deadman over Wi-Fi (D2), reconnecting, a relaunch from the Keychain, and Night in the dark with gloves.
- [ ] Discovery took about 30 s on the first try on a mesh Wi-Fi network: find out whether that was the server's restart or the network, and whether a real Master does the same.

## 5a. What the iOS MVP still needs

**Screens not built** (their `S-*` component too, where one is named):

- [ ] `I-SKY`, `S-SKY`: sky view and streamed sky data, with the Metal prototype on the oldest iPad (`MAPP-SKY-010`-`040`, `MAPP-STR-*`).

**Built 2026-11-06:** Solve (`MAPP-SOL-010`, `020`), Flip (`MAPP-FLP-010`, `020`), Sessions run view and job queue (`MAPP-SES-010`-`030`, `MAPP-SCH-010`).

**Built, but short of the requirement until the server offers more** (Shared SDD 4.2, item 11):

- [ ] Connecting devices (`MAPP-DEV-010`), mount jog (`MAPP-DEV-030`), autofocus (`MAPP-FOC-020`): the screens are built and work on the mock's proposed operations, and say "doesn't offer this yet" on a real Master.
- [ ] Find home, tracking rate and pulse (`MAPP-DEV-020`); per-exposure region of interest (`MAPP-DEV-040`): no operation exists yet, and no control either.
- [ ] Live loop (`MAPP-IMG-020`), guide-star cut-out (`MAPP-GUI-020`): the Imaging and Guiding screens show the rest; these need operations first.
- [ ] Tiles for deep zoom (`MAPP-IMG-030`): the route exists now, and the viewer doesn't use it yet.
- [ ] Solve the current or last frame (`MAPP-SOL-010`): `solve.capture` takes a new one. Skip a session step (`MAPP-SES-020`), defer a flip (`MAPP-FLP-020`), nested blocks and time remaining (`MAPP-SES-030`), current filter (`MAPP-DEV-050`): no operation or state yet.

**The plugin** is drafted (`Galileo-Plugins/mobileconnect`) and not yet installed in a running Galileo: see the end of this file.

## 6. Build the mock Master

- [x] Create `tools/mock-master/` (suggested: Python, so it can reuse Galileo's `aiohttp` ideas): HTTPS with a self-signed certificate, pairing with a simulated approval delay, handshake, catalogue, stream with scripted drops and `resync`, `confirmation_required`, lease contention.
- [x] Create `api/scenarios/` (first pairing, revoked pairing, certificate change, a normal night, safety event, meridian flip, dropped stream, Pi-class Master without sky streaming, older/newer Master) — SDD §3.9.
- [x] Run the real Swift client against it from the Mac, then against a real Galileo Master on the LAN (core is built; the MobileConnect plugin is **not**, so only `implemented` routes will work end to end).

## 7. Android / Kotlin side (can be done on the Mac or on Windows)

- [ ] `cd android && gradle wrapper` once (no wrapper is committed); pin the Kotlin (2.0.21), generator plugin (7.10.0), OkHttp (4.12.0), kotlinx versions in `android/api-client/build.gradle.kts`.
- [ ] `./gradlew :api-client:openApiGenerate :api-client:compileKotlin`. Fix issues in the description, not the output.
- [ ] Create `android/core` (pure Kotlin/JVM, no Android dependencies, reused by Quest) and implement the same `S-*` components against the **same vectors**. The vector suite must pass on both platforms in CI (`MAPP-PLT-040`).
- [ ] Android SDD (`docs/android/SDD.md`) and Quest SDD (`docs/quest/SDD.md`) are **not written** — every Android/Quest row in the RTM reads `GAP`.

## 8. Docs to keep honest (project rule: docs before code, and after)

- [ ] Review and approve the drafts: PSD v0.8, SRS v0.1, Shared SDD v0.1, iOS SDD v0.1, RTM v0.1.
- [ ] If SRS changes: `python scripts/gen_rtm.py .` and commit the regenerated `docs/RTM.md`.
- [ ] Resolve the **seven wire-contract differences** in `docs/SDD.md` §4.2 with the Galileo server side (stream URL, resume parameters, hold-beat path, capability payload, roles vs permissions, pairing fields, unregistered sky/preview routes).
- [ ] Decide HTTP statuses for typed operation outcomes (SDD D9) — currently modelled in the response body on a `default` response.
- [ ] When the Galileo MobileConnect plugin publishes `wire/openapi.yaml`, replace/merge into `api/openapi.yaml`, re-run both generators and all vectors.
- [ ] Findings 8-11 in `docs/SDD.md` §4.2 (found while building the client), and the proposed state keys in `api/scenarios/normal-night.json`, go to the server side with the other seven.
- [ ] Open points still to settle: hold beat 250 ms/deadman 600 ms (measure on Wi-Fi, SDD D2); sky cache size/lifetime (D3); sky-stream binary format (D4); block-type catalogue on the wire for session authoring (D5); phone layout reductions (D8); Metal for the sky view (iOS IO2); bundle id (IO5); Meta developer account holder (PSD Q8).
- [x] No changelog exists in this repo yet; create `CHANGELOG.md` when the first code lands.

## 9. Release path (iOS)

- [ ] Privacy manifest and "Data Not Collected" label; review notes pointing at demo mode (PSD R7); licence text before first release (`MAPP-PLT-100`).
- [ ] TestFlight beta, then App Store submission. **Do not dispatch any GitHub build or tag a release without asking** (standing rule).

## Server-side dependencies (Galileo repo — not this repo's work)

The apps cannot be completed end to end until these exist. Until then use the mock Master.

- [x] MobileConnect plugin draft (`Galileo-Plugins/mobileconnect`): routes, event stream, state translation, Options page. Tested with the real Swift client against real Connect Link and Gateway code with fake devices.
- [ ] Try the plugin in a running Galileo with real devices: rename `plugin.toml.draft` locally and *Install from file*. Renaming it in the repository publishes it to the Marketplace on the next push.
- [ ] Spoke Piers through `galileo.connect_link.relay`, sky routes over `skystream.py`, block-type catalogue for session authoring, the operations in `docs/SDD.md` §4.2 item 11.
