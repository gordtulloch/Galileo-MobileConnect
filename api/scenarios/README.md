# Mock Master scenarios

Scripts for `S-MOCK` (`docs/SDD.md` 3.9): data, not code, played by both the in-process player in each app (tests, UI tests and demonstration mode) and the standalone server in `tools/mock-master/`.

| File | What it is |
|---|---|
| `catalogue.json` | The real operation catalogue, written from a Galileo checkout by `tools/mock-master/dump_catalogue.py`. Do not edit. |
| `catalogue.planned.json` | Operations the screens need that Galileo core does not have yet. A proposal, not the server's names. |
| `preview.jpg` | The picture the mock serves, from `tools/mock-master/make_preview.py`. |
| Every other `.json` | A scenario. |

A scenario with `extends` replaces the named top-level fields of its parent. Its fields:

| Field | Meaning |
|---|---|
| `pair_approval_s`, `pairing` | Delay before the simulated person answers, and their answer: `approve`, `reject` or `never` |
| `permissions` | What `whoami` reports |
| `handshake`, `capabilities`, `piers`, `alerts` | Those routes' bodies |
| `state` | Initial state per Pier, as dotted keys. **These keys are the proposal for the plugin's `PierState`** (`docs/SDD.md` 4.1) |
| `lease_holders` | Pier to holder at the start |
| `unavailable` | Pier to operation to the reason it is unavailable |
| `results`, `consequences`, `durations` | Per operation: result value, confirmation wording, running time of a long-running one |
| `effects` | Per operation: state changes `after_s` seconds later. A value may be `{"$arg": "name"}`, or `{"$add": ["state.key", "arg"]}`, where the arg `_one` means 1 |
| `timeline`, `loop_s` | Timed events from the first stream connection: `set` (with `pier`), `alert`, or `do`: `drop`, `resync`, `revoke`, `rotate_certificate`. Repeats every `loop_s` if not 0 |
| `waves` | Keys updated every `every_s`: `fields` of `{base, amp, period_s}` for an object, `base`/`amp`/`period_s` for a number, or `ramp_s` for a 0-1 ramp |
