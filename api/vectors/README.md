# Test vectors

Recorded conversations between a client and a Master, with the state or outcome the client must reach. **Both native clients (Swift and Kotlin) replay every file here in CI and must agree** — this suite is what keeps two independent implementations of the same specification identical (`docs/SDD.md`, ADR-APP-001, `S-TEST`; requirement `MAPP-PLT-040`).

## Format

One JSON file per scenario:

```json
{
  "name": "short-kebab-name",
  "description": "What this proves.",
  "status": "implemented | planned",
  "requirements": ["MAPP-CON-030"],
  "clock_start": "2026-11-06T02:00:00Z",
  "setup": { "role": "Operator", "token_stored": false },
  "steps": [
    { "do": "act", "action": "invoke", "pier": "p1", "operation": "mount.park", "args": {} },
    { "do": "send",    "method": "POST", "path": "/connect/v1/pair", "body": {} },
    { "do": "receive", "status": 202, "body": {} },
    { "do": "stream",  "frame": {} },
    { "do": "drop" },
    { "do": "expect_connect", "epoch": "e1", "last": 8 },
    { "do": "clock",   "advance_s": 30 },
    { "do": "expect",  "state": {}, "outcome": "..." }
  ]
}
```

Steps run in order.

- `act` — what the user or the platform does. `action` is one of: `pair_start`, `pair_poll`, `handshake`, `stream_start`, `invoke` (`pier`, `operation`, `args`), `confirm` (accept the question last asked), `decline`, `emergency` (`pier`, `operation`), `hold_press` (`pier`, `family`, `args`), `hold_release`, `background`. Actions may run concurrently with the steps after them, which is how a request is observed in flight. So an `act` that depends on the previous answer needs an `expect` before it.
- `send` — the client must have made this request: `method` and `path` exactly; `auth` for whether a bearer token was present; `headers` and `body` as subsets. The request then stays in flight until the next `receive`.
- `receive` — answers the request in flight.
- `stream` — a frame delivered on the WebSocket (validate against `../events.schema.json`).
- `drop` — the connection closes without warning (network loss, suspension).
- `expect_connect` — the client must have reconnected, resuming from this position.
- `clock` — advance the injected clock; clients have no other source of time in the core.
- `expect` — the observable result, polled briefly because the client is asynchronous.

Idempotency keys are injected as `k1`, `k2`, … in the order the client asks for them; a held control's requests draw from a separate sequence, so they don't disturb that numbering.

### Observables

Setup defaults: `role` Operator, peer id `me`, device name `Vector iPad`, platform `ipados`, the catalogue from `../scenarios/catalogue.json`, and a Keychain-equivalent store with nothing in it (`token_stored: true` puts one credential there).

`outcome` is the latest thing to have happened, one of `Waiting`, `Paired`, `Refused`, `TimedOut`, `Failed` (pairing); `Loading`, `Live`, `Resyncing` (stream); `Pending`, `Done`, `OutcomeUnknown` (last operation); `ConfirmationRequired`, `Invalid`, `NotAllowed` (last request); `Unpaired`, `IdentityChanged`.

`state` keys:

| Key | Value |
|---|---|
| `code`, `pinned_fingerprint`, `token_stored`, `operations_offered` | Pairing |
| `<pier>.<state key>` | `{value, stale}`, either optional |
| `operation.<key>` | `Pending`, `Running`, `Done`, `OutcomeUnknown`, `Refused: <reason>`, `Busy: <reason>`, `Unreachable: <reason>`, `Unavailable: <reason>`, `Invalid: <reason>` |
| `requests_sent` | Requests made so far, of any kind |
| `requests_sent_with_key` | Key to count |
| `confirmation.consequence`, `confirmation.token` | The question being asked |
| `invalid.path` | The argument at fault |
| `connection` | `Connecting`, `Connected`, `Reconnecting`, `Unreachable` |
| `hold` | `Idle`, `Starting`, `Holding`, `Stopped: released` / `background` / `connection lost` / `lease lost` / `beat failed`, or `Stopped: refused` |

`status: implemented` vectors were derived from the real Connect Link and Control Surface behaviour (checked against `galileo/connect_link/` and `galileo/control_surface/`); `planned` ones follow the plugin SDD and may change with it.
