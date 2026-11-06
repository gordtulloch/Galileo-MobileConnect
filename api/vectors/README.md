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
  "steps": [
    { "do": "send",    "method": "POST", "path": "/connect/v1/pair", "body": {} },
    { "do": "receive", "status": 202, "body": {} },
    { "do": "stream",  "frame": {} },
    { "do": "drop" },
    { "do": "clock",   "advance_s": 30 },
    { "do": "expect",  "state": {}, "outcome": "..." }
  ]
}
```

- `send` / `receive` — an HTTP exchange the client makes or is given. A client under test is fed the `receive` steps and must produce the `send` steps.
- `stream` — a frame delivered on the WebSocket (validate against `../events.schema.json`).
- `drop` — the connection closes without warning (network loss, suspension).
- `clock` — advance the injected clock; clients have no other source of time in the core.
- `expect` — the observable result: a subset of the client's state, or an `outcome` name from the Shared SDD (`Pending`, `Done`, `OutcomeUnknown`, `Stale`, `Unpaired`, `IdentityChanged`, …).

`status: implemented` vectors were derived from the real Connect Link behaviour (checked against `galileo/connect_link/`); `planned` ones follow the plugin SDD and may change with it.
