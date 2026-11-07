# Changelog

All notable changes to this repository are recorded here, in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [Unreleased]

### Added

- **iOS app, first working version.** A universal iPhone/iPad app that finds or is given a Master's address, pairs (showing the code, with the certificate pinned and the token in the Keychain), reconnects by itself, and shows the Overview, Equipment, Imaging, Focus, Guiding and Safety screens plus Settings. Every control is built from the Master's operation catalogue, shows the Master's own answer, and marks a stale value as such. An Operator gets the lease, confirmations and held motion; a Viewer gets no controls. Sky, Solve, Flip, Sessions, Schedule and Library are not built: `docs/CHECKLIST.md` section 5a lists what the MVP still needs.
- **Demonstration mode** ("Try the demo"): the real client code against a scripted Master inside the app, labelled on every screen, so the app can be tried with no observatory and App Review has something to look at.
- **Emergency actions on every screen**: stop everything, park and close dome, in two interactions, with a one-second press-and-hold, needing no lease, and listing each part's result.
- **Night theme that is provably red.** Colours come from `design/tokens.json` (contrast and red-only are checked by `scripts/gen_tokens.py`), and every Night screenshot from the UI tests is checked pixel by pixel by `scripts/check_night.py`. Night is the default on first launch.
- **`GalileoCore`**: the connection, API, live-state and control-safety logic, in plain Swift with no UI, specified in `docs/SDD.md` for the Kotlin side to implement too.
- **Shared test vectors** in `api/vectors/`, now 15, with explicit `act` steps so that any platform's runner can replay them with no knowledge of individual files. They cover pairing, stream replay and resynchronisation, outcome-unknown, confirmation, typed refusals, revocation, Viewers, emergency actions and held motion.
- **Mock Master scenarios** in `api/scenarios/` (normal night, safety event, meridian flip, lease contention, dropped stream, revoked pairing, refused pairing, Viewer, Pi-class Master, older and newer Master, certificate change), played by the in-process player and by a **standalone server**, `tools/mock-master/mock_master.py`, which speaks real HTTPS and WebSocket. The catalogue they use is dumped from a real Galileo checkout.
- **`tools/mock-master/real_link_server.py`** runs Galileo's own Connect Link server against a throwaway database, so the client is tested on real pairing, handshake and stream code.
- `scripts/check_ios.sh` runs all of the above, and refuses literal colours and system controls that can't be held to Night in `ios/App`.
- **iOS tooling.** The Swift client generates from `api/openapi.yaml` and builds with its dependencies pinned. The Xcode project is generated from `ios/project.yml`. The bundle identifier is a working value, still to be decided (PSD Q5).

### Changed

- **Design documents corrected against what the server really does** (`docs/SDD.md` 4.2, items 8-11). Role comes from `whoami`, because the handshake's `allowed` always includes `config.*` and would have locked the app out of every real Master. Emergency operations use core's real names. A handshake answering `restricted` only because the app reports no database level proceeds without showing its message. Item 11 lists the MVP operations core doesn't have yet.
- A value now ages out only if it moves continuously, because the Master sends a value only when it changes: otherwise "tracking: yes" would turn stale after a few seconds of nothing happening.

### Fixed

- **API description, corrected against the Galileo source.** A 403 is now described as plain text, as the server sends it; described as JSON, the generated client would have thrown a decoding error instead of reporting "forbidden". `transferGetChunk` gains its 404 and 503 (busy, with `Retry-After`) responses, and `transferStatus` its 400. `Operation.class` is no longer an enum, so an unknown class drops one entry rather than failing the whole catalogue.
