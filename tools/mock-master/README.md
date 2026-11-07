# Standalone mock Master

Speaks the real wire (HTTPS with a self-signed certificate, WebSocket stream) and plays a scenario from `api/scenarios/`, so that a build can be tried, and the real TLS, pinning and WebSocket code tested, with no observatory. It is the other half of `S-MOCK` (`docs/SDD.md` 3.9); the in-process player in `GalileoCore` plays the same scenarios.

```bash
python3 -m venv .venv && .venv/bin/pip install -r tools/mock-master/requirements.txt
.venv/bin/python tools/mock-master/mock_master.py --scenario normal-night --port 8765
```

In the app, enter the address printed at start-up. The simulated person approves pairing after `pair_approval_s`, or `--time-scale` times sooner. `--no-mdns` skips the Bonjour advertisement.

| Option | Meaning |
|---|---|
| `--scenario NAME` | A file in `api/scenarios/` without its extension |
| `--host`, `--port` | Where to listen (default all interfaces, 8765) |
| `--time-scale N` | Run the script N times as fast (for tests) |
| `--no-mdns` | Don't advertise `_galileo-link._tcp` |
| `--print-ready` | Print one line `READY <port> <fingerprint>` once listening (for tests) |

`dump_catalogue.py` and `make_preview.py` regenerate the catalogue and the preview picture. The tool never touches a real Galileo installation; its token and certificate exist only in memory.
