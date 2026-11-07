# Changelog

All notable changes to this repository are recorded here, in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [Unreleased]

### Added

- **Solve, Flip and Sessions screens.** Plate solve, sync and centre with the result shown; the flip's state and time remaining, and a flip after the Master's confirmation; running, pausing, resuming and stopping a saved session, with block position and progress, and the job queue. Controls for what the server has no operation for yet (skip a step, defer a flip) say so.
- **Tested end to end against the real server code.** `tools/mock-master/real_link_server.py` runs Galileo's real Connect Link listener, Gateway and lease with the draft MobileConnect plugin (in `Galileo-Plugins/mobileconnect`) and fake devices, and the real client pairs, pins, takes the lease, operates, confirms a park, holds a control and uses an emergency stop against it. `scripts/check_ios.sh` runs this when the checkouts are alongside.
- **iOS app, first working version.** A universal iPhone/iPad app that finds or is given a Master's address, pairs (showing the code, with the certificate pinned and the token in the Keychain), reconnects by itself, and shows the Overview, Equipment, Imaging, Focus, Guiding and Safety screens plus Settings. Every control is built from the Master's operation catalogue, shows the Master's own answer, and marks a stale value as such. An Operator gets the lease, confirmations and held motion; a Viewer gets no controls. Sky, Solve, Flip, Sessions, Schedule and Library are not built: `docs/CHECKLIST.md` section 5a lists what the MVP still needs.
- **Demonstration mode** ("Try the demo"): the real client code against a scripted Master inside the app, labelled on every screen, so the app can be tried with no observatory and App Review has something to look at.
- **Emergency actions on every screen**: stop everything, park and close dome, in two interactions, with a one-second press-and-hold, needing no lease, and listing each part's result.
- **Night theme that is provably red.** Colours come from `design/tokens.json` (contrast and red-only are checked by `scripts/gen_tokens.py`), and every Night screenshot from the UI tests is checked pixel by pixel by `scripts/check_night.py`. Night is the default on first launch.
- **`GalileoCore`**: the connection, API, live-state and control-safety logic, in plain Swift with no UI, specified in `docs/SDD.md` for the Kotlin side to implement too.
- **Shared test vectors** in `api/vectors/`, now 15, with explicit `act` steps so that any platform's runner can replay them with no knowledge of individual files. They cover pairing, stream replay and resynchronisation, outcome-unknown, confirmation, typed refusals, revocation, Viewers, emergency actions and held motion.
- **Mock Master scenarios** in `api/scenarios/` (normal night, safety event, meridian flip, lease contention, dropped stream, revoked pairing, refused pairing, Viewer, Pi-class Master, older and newer Master, certificate change), played by the in-process player and by a **standalone server**, `tools/mock-master/mock_master.py`, which speaks real HTTPS and WebSocket. The catalogue they use is dumped from a real Galileo checkout.
- `scripts/check_ios.sh` runs all of the above, and refuses literal colours and system controls that can't be held to Night in `ios/App`.
- **iOS tooling.** The Swift client generates from `api/openapi.yaml` and builds with its dependencies pinned. The Xcode project is generated from `ios/project.yml`. The bundle identifier is `com.gordtulloch.galileo.mobileconnect` (PSD Q5).

### Changed

- The welcome screen says what to try if nothing has been found after 10 seconds, instead of only showing a spinner. Bonjour took about 30 seconds on the first real iPad, on a mesh Wi-Fi network.
- **The wire contract now matches what Connect Link can do**, found by writing the plugin against it (`docs/SDD.md` 4.2, items 12-15). Routes are flat with query parameters, because its route table matches exact paths. The client asks for its snapshot, because the stream has no connection hook. The preview has one stretch value, as `galileo.preview` does, so the app's three sliders became one. A lease's holder has a name to show. Typed outcomes have fixed HTTP statuses, never 5xx.
- **Design documents corrected against what the server really does** (`docs/SDD.md` 4.2, items 8-11). Role comes from `whoami`, because the handshake's `allowed` always includes `config.*` and would have locked the app out of every real Master. Emergency operations use core's real names. A handshake answering `restricted` only because the app reports no database level proceeds without showing its message. Item 11 lists the MVP operations core doesn't have yet.
- A value now ages out only if it moves continuously, because the Master sends a value only when it changes: otherwise "tracking: yes" would turn stale after a few seconds of nothing happening.

### Fixed

- The standalone mock Master advertises on Bonjour on a Mac with another program holding an mDNS socket open: binding every interface failed with "address in use".
- **API description, corrected against the Galileo source.** A 403 is now described as plain text, as the server sends it; described as JSON, the generated client would have thrown a decoding error instead of reporting "forbidden". `transferGetChunk` gains its 404 and 503 (busy, with `Retry-After`) responses, and `transferStatus` its 400. `Operation.class` is no longer an enum, so an unknown class drops one entry rather than failing the whole catalogue.
