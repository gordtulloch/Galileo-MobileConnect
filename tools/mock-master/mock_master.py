"""A scripted Master that speaks the real wire: see README.md. Same scenario rules as GalileoCore's MockMaster."""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import datetime
import ipaddress
import json
import math
import secrets
import socket
import ssl
import tempfile
from pathlib import Path

from aiohttp import WSMsgType, web
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

SCENARIOS = Path(__file__).resolve().parents[2] / "api" / "scenarios"
TOKEN = "mock-token"
PEER = "mock-peer"
RING = 256


def now_text() -> str:
    return datetime.datetime.now(datetime.UTC).isoformat()


def load_scenario(name: str) -> dict:
    own = json.loads((SCENARIOS / f"{name}.json").read_text(encoding="utf-8"))
    parent = own.get("extends")
    return {**load_scenario(parent), **own} if parent else own


def make_certificate() -> tuple[bytes, bytes, str]:
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Mock Master")])
    now = datetime.datetime.now(datetime.UTC)
    cert = (
        x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=30))
        .add_extension(x509.SubjectAlternativeName([
            x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
        .sign(key, hashes.SHA256())
    )
    return (cert.public_bytes(serialization.Encoding.PEM),
            key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()),
            cert.fingerprint(hashes.SHA256()).hex())


def validate(op: dict, args: dict) -> tuple[str, str] | None:
    """As galileo.control_surface.schema.validate_args."""
    known = {a["name"] for a in op["args"]}
    for extra in sorted(set(args) - known):
        return extra, "is not an argument of this operation"
    for a in op["args"]:
        name = a["name"]
        if name not in args:
            if a.get("required") and "default" not in a:
                return name, "is required"
            continue
        value, kind = args[name], a["type"]
        if kind == "bool" and not isinstance(value, bool):
            return name, "must be true or false"
        if kind in ("int", "float"):
            if isinstance(value, bool) or not isinstance(value, int | float):
                return name, "must be a number"
            if kind == "int" and float(value) != int(value):
                return name, "must be a whole number"
            if "min" in a and value < a["min"]:
                return name, f"must be at least {a['min']:g}"
            if "max" in a and value > a["max"]:
                return name, f"must be at most {a['max']:g}"
        if kind in ("str", "enum") and not isinstance(value, str):
            return name, "must be text"
        if kind == "enum" and value not in a.get("choices", []):
            return name, f"must be one of {', '.join(a['choices'])}"
    return None


class MockMaster:
    def __init__(self, scenario: dict, time_scale: float = 1.0) -> None:
        self.s, self.scale = scenario, time_scale
        ops = []
        for file in ("catalogue.json", "catalogue.planned.json"):
            ops += json.loads((SCENARIOS / file).read_text(encoding="utf-8"))["operations"]
        self.ops = {o["name"]: o for o in ops}
        self.values = {pier: dict(state) for pier, state in scenario.get("state", {}).items()}
        self.leases = dict(scenario.get("lease_holders", {}))
        self.alerts = [dict(a, time=a.get("time", now_text())) for a in scenario.get("alerts", [])]
        self.pairings: dict[str, dict] = {}
        self.revoked = False
        self.idempotent: dict[str, tuple[int, dict]] = {}
        self.tokens: dict[str, tuple[str, object]] = {}
        self.holds: dict[str, dict] = {}
        self.epoch, self.seq, self.ring, self.ws = secrets.token_hex(8), 0, [], None
        self.started = None
        self.tasks: set[asyncio.Task] = set()
        self.loop_time = asyncio.get_event_loop().time
        self.on_rotate = None

    # -- tasks ---------------------------------------------------------------------------------
    def later(self, seconds: float, fn, *args, scaled: bool = True) -> None:
        async def run():
            await asyncio.sleep(seconds / (self.scale if scaled else 1))
            result = fn(*args)
            if asyncio.iscoroutine(result):
                await result
        task = asyncio.ensure_future(run())
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    # -- the app -------------------------------------------------------------------------------
    def app(self) -> web.Application:
        app = web.Application(middlewares=[self.gate])
        r = app.router
        r.add_post("/connect/v1/pair", self.pair)
        r.add_get("/connect/v1/pair/{id}", self.poll)
        r.add_get("/connect/v1/whoami", self.whoami)
        r.add_post("/connect/v1/handshake", self.handshake)
        r.add_get("/connect/v1/stream", self.stream)
        r.add_get("/mobileconnect/v1/capabilities", lambda q: web.json_response(self.s.get("capabilities", {})))
        r.add_get("/mobileconnect/v1/piers", lambda q: web.json_response({"piers": self.s.get("piers", [])}))
        r.add_get("/mobileconnect/v1/catalogue", self.catalogue)
        r.add_get("/mobileconnect/v1/alerts", lambda q: web.json_response({"alerts": list(reversed(self.alerts))}))
        r.add_post("/mobileconnect/v1/snapshot", self.request_snapshot)
        r.add_post("/mobileconnect/v1/ops", self.invoke)
        r.add_post("/mobileconnect/v1/lease", self.lease_take)
        r.add_delete("/mobileconnect/v1/lease", self.lease_release)
        r.add_get("/mobileconnect/v1/preview", self.preview)
        return app

    @web.middleware
    async def gate(self, request, handler):
        if request.path.startswith("/connect/v1/pair"):
            return await handler(request)
        if self.revoked or request.headers.get("Authorization") != f"Bearer {TOKEN}":
            return web.json_response({"error": "unauthorised"}, status=401)
        return await handler(request)

    # -- pairing -------------------------------------------------------------------------------
    async def pair(self, request):
        body = await request.json()
        if not body.get("plugin") or not str(body.get("name", "")).strip():
            return web.json_response({"error": "bad request"}, status=400)
        request_id = secrets.token_hex(8)
        self.pairings[request_id] = {"at": self.loop_time(), "collected": False}
        return web.json_response({
            "request_id": request_id, "code": f"{secrets.randbelow(10**6):06d}", "expires_in": 300,
            "fingerprint": request.app["fingerprint"](), "protocol": self.s["handshake"]["node"]["protocol"],
        }, status=202)

    async def poll(self, request):
        p = self.pairings.get(request.match_info["id"])
        if p is None:
            return web.json_response({"state": "expired"})
        waited = (self.loop_time() - p["at"]) * self.scale
        answer = self.s.get("pairing", "approve")
        if waited < self.s.get("pair_approval_s", 0) or answer == "never":
            return web.json_response({"state": "pending"})
        if answer == "reject":
            return web.json_response({"state": "rejected"})
        if p["collected"]:
            return web.json_response({"state": "collected"})
        p["collected"] = True
        return web.json_response({"state": "approved", "token": TOKEN})

    # -- session -------------------------------------------------------------------------------
    async def whoami(self, request):
        return web.json_response({"peer_id": PEER, "name": "Mock device", "plugin": "mobileconnect",
                                  "permissions": self.s.get("permissions", ["control", "view"])})

    async def handshake(self, request):
        result = dict(self.s["handshake"])
        result.setdefault("allowed", [] if result["verdict"] == "refused" else
                          ["config.read", "config.write", "data.push", "data.pull", "control", "view"])
        return web.json_response(result)

    async def catalogue(self, request):
        unavailable = self.s.get("unavailable", {}).get(request.query.get("pier", ""), {})
        return web.json_response({"operations": [
            {**o, "available": False, "unavailable_because": unavailable[o["name"]]} if o["name"] in unavailable else o
            for o in self.ops.values()]})

    async def preview(self, request):
        return web.Response(body=(SCENARIOS / "preview.jpg").read_bytes(), content_type="image/jpeg")

    # -- lease ---------------------------------------------------------------------------------
    def has_control(self, pier: str) -> bool:
        info = next((p for p in self.s.get("piers", []) if p["id"] == pier), {})
        return "control" in self.s.get("permissions", ["control"]) and info.get("access") == "control" and info.get("reachable", True)

    def new_token(self, op: str, args) -> str:
        token = secrets.token_hex(8)
        self.tokens[token] = (op, args)
        return token

    async def lease_take(self, request):
        pier = request.query.get("pier", "")
        if not self.has_control(pier):
            return web.json_response({"error": "refused", "reason": "This device can only view this Pier."}, status=403)
        holder = self.leases.get(pier)
        if holder and holder != PEER:
            token = request.headers.get("X-Confirm-Token", "")
            if self.tokens.pop(token, (None,))[0] != "lease":
                return web.json_response({
                    "error": "refused", "reason": f"{holder} holds the lease on this Pier.",
                    "detail": {"kind": "lease_held", "holder": holder, "can_take": True},
                    "confirm_token": self.new_token("lease", None), "expires_in": 60}, status=409)
        self.leases[pier] = PEER
        await self.emit({"type": "lease", "pier": pier, "holder": PEER, "holder_name": "This device"})
        return web.json_response({"pier": pier, "held_by_me": True, "holder": PEER})

    async def lease_release(self, request):
        pier = request.query.get("pier", "")
        if self.leases.get(pier) == PEER:
            del self.leases[pier]
            await self.emit({"type": "lease", "pier": pier, "holder": None, "holder_name": None})
        return web.json_response({"pier": pier, "held_by_me": False})

    # -- operations ----------------------------------------------------------------------------
    async def invoke(self, request):
        pier, name = request.query.get("pier", ""), request.query.get("name", "")
        key = request.headers.get("Idempotency-Key")
        if key and f"{pier}/{key}" in self.idempotent:
            status, body = self.idempotent[f"{pier}/{key}"]
            return web.json_response(body, status=status)
        status, body = await self.run(pier, name, await request.json(), request.headers.get("X-Confirm-Token"), key)
        if key:
            self.idempotent[f"{pier}/{key}"] = (status, body)
        return web.json_response(body, status=status)

    async def run(self, pier, name, body, token, key):
        op = self.ops.get(name)
        if op is None:
            return 404, {"error": "unavailable", "reason": f"This Master does not offer {name}."}
        args = body.get("args") or {}
        fault = validate(op, args)
        if fault:
            return 400, {"error": "invalid", "reason": f"{fault[0]} {fault[1]}", "detail": {"path": fault[0]}}
        reason = self.s.get("unavailable", {}).get(pier, {}).get(name)
        if reason:
            return 409, {"error": "unavailable", "reason": reason}
        if op["class"] != "read" and not self.has_control(pier):
            return 403, {"error": "refused", "reason": "This device can only view this Pier."}
        if op["class"] == "emergency" and body.get("confirmed") is not True:
            return 403, {"error": "refused", "reason": "An emergency action needs a deliberate confirmation."}
        if op["lease"] and self.leases.get(pier) != PEER:
            holder = self.leases.get(pier)
            if holder:
                return 409, {"error": "refused", "reason": f"{holder} holds the lease on this Pier.",
                             "detail": {"kind": "lease_held", "holder": holder, "can_take": True}}
            return 409, {"error": "refused", "reason": "Take the lease on this Pier first.", "detail": {"kind": "no_lease"}}
        if op["class"] == "confirm":
            stored = self.tokens.pop(token or "", None)
            if stored != (name, args):
                return 409, {"error": "confirmation_required", "expires_in": 60, "confirm_token": self.new_token(name, args),
                             "reason": self.s.get("consequences", {}).get(name, op.get("description", name))}

        result = self.s.get("results", {}).get(name)
        if name.endswith(".hold_start"):
            hold_id = secrets.token_hex(6)
            self.holds[hold_id] = {"pier": pier, "family": name.rsplit(".", 1)[0], "beat": self.loop_time()}
            self.later(0.1, self.deadman, hold_id, scaled=False)
            result = {"hold_id": hold_id}
        elif name.endswith(".hold_beat"):
            hold = self.holds.get(args["hold_id"])
            if hold:
                hold["beat"] = self.loop_time()
            return 200, {"ok": True, "result": hold is not None}
        elif name.endswith(".hold_stop"):
            result = self.holds.pop(args["hold_id"], None) is not None

        await self.effects(name, pier, args)
        correlation = secrets.token_hex(6)
        if op.get("long_running"):
            event = {"correlation_id": correlation, "request_id": key, "pier": pier, "operation": name}
            await self.emit({"type": "op.started", **event})
            self.later(self.s.get("durations", {}).get(name, 3), self.emit, {"type": "op.finished", **event, "outcome": "ok"})
        answer = {"ok": True, "correlation_id": correlation}
        if result is not None:
            answer["result"] = result
        return 200, answer

    def deadman(self, hold_id: str):
        hold = self.holds.get(hold_id)
        if hold is None:
            return None
        if self.loop_time() - hold["beat"] >= 0.6:
            del self.holds[hold_id]
            return self.effects(f"{hold['family']}.hold_stop", hold["pier"], {})
        self.later(0.1, self.deadman, hold_id, scaled=False)
        return None

    async def effects(self, name, pier, args):
        for step in self.s.get("effects", {}).get(name, []):
            if step.get("after_s", 0) == 0:
                await self.apply(step.get("set", {}), pier, args)
            else:
                self.later(step["after_s"], self.apply, step.get("set", {}), pier, args)

    async def apply(self, changes, pier, args):
        for key in sorted(changes):
            value = changes[key]
            if isinstance(value, dict) and "$arg" in value:
                value = args.get(value["$arg"])
            elif isinstance(value, dict) and "$add" in value:
                state_key, arg = value["$add"]
                value = self.values.get(pier, {}).get(state_key, 0) + (1 if arg == "_one" else args.get(arg, 0))
            await self.set(key, value, pier)

    async def set(self, key, value, pier):
        self.values.setdefault(pier, {})[key] = value
        await self.emit({"type": "delta", "pier": pier, "key": key, "value": value, "as_of": now_text()})

    # -- stream --------------------------------------------------------------------------------
    async def send(self, msg) -> None:
        if self.ws is not None and not self.ws.closed:
            with contextlib.suppress(ConnectionError):
                await self.ws.send_json(msg)

    async def emit(self, data) -> None:
        self.seq += 1
        self.ring.append((self.seq, data))
        del self.ring[:-RING]
        await self.send({"seq": self.seq, "data": data})

    async def snapshot(self) -> None:
        piers = {p: {**v, **({"lease.holder": self.leases[p],
                              "lease.holder_name": "This device" if self.leases[p] == PEER else self.leases[p]}
                             if p in self.leases else {})} for p, v in self.values.items()}
        await self.emit({"type": "snapshot", "as_of": now_text(), "piers": piers})

    async def request_snapshot(self, request):
        open_stream = self.ws is not None and not self.ws.closed
        if open_stream:
            await self.snapshot()
        return web.json_response({"ok": open_stream})

    async def stream(self, request):
        ws = web.WebSocketResponse(heartbeat=20.0)
        await ws.prepare(request)
        if self.ws is not None:
            await self.ws.close()
        self.ws = ws
        epoch, last = request.query.get("epoch", ""), request.query.get("last", "")
        if not last:
            await self.send({"type": "hello", "epoch": self.epoch, "seq": self.seq})
        elif epoch != self.epoch or (self.ring and int(last) < self.ring[0][0] - 1):
            await self.send({"type": "resync", "epoch": self.epoch, "seq": self.seq})
        else:
            await self.send({"type": "hello", "epoch": self.epoch, "seq": self.seq})
            for seq, data in list(self.ring):
                if seq > int(last):
                    await self.send({"seq": seq, "data": data})
        if self.started is None:
            self.started = self.loop_time()
            self.run_timeline()
            for wave in self.s.get("waves", []):
                self.later(wave["every_s"], self.wave, wave)
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
        return ws

    def run_timeline(self) -> None:
        events = self.s.get("timeline", [])
        for event in events:
            self.later(event.get("at_s", 0), self.play, event)
        if self.s.get("loop_s") and events:
            self.later(self.s["loop_s"], self.run_timeline)

    async def play(self, event) -> None:
        if "set" in event:
            await self.apply(event["set"], event["pier"], {})
        if "alert" in event:
            alert = {**event["alert"], "id": secrets.token_hex(4), "time": now_text(), "type": "alert"}
            self.alerts = (self.alerts + [alert])[-100:]
            await self.emit(alert)
        action = event.get("do")
        if action == "drop" and self.ws is not None:
            await self.ws.close()
        elif action == "resync":
            self.epoch, self.ring = secrets.token_hex(8), []
            if self.ws is not None:
                await self.ws.close()
        elif action == "revoke":
            self.revoked = True
            if self.ws is not None:
                await self.ws.close()
        elif action == "rotate_certificate" and self.on_rotate:
            await self.on_rotate()

    async def wave(self, wave) -> None:
        elapsed = (self.loop_time() - self.started) * self.scale

        def sine(spec):
            return spec.get("base", 0) + spec.get("amp", 0) * math.sin(2 * math.pi * elapsed / max(spec.get("period_s", 1), 0.001))
        if "fields" in wave:
            value = {k: sine(f) for k, f in wave["fields"].items()}
        elif "ramp_s" in wave:
            value = (elapsed % wave["ramp_s"]) / wave["ramp_s"]
        else:
            value = sine(wave)
        await self.set(wave["key"], value, wave["pier"])
        self.later(wave["every_s"], self.wave, wave)


def ssl_context(cert: bytes, key: bytes) -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    with tempfile.TemporaryDirectory() as folder:
        c, k = Path(folder) / "c.pem", Path(folder) / "k.pem"
        c.write_bytes(cert)
        k.write_bytes(key)
        context.load_cert_chain(c, k)
    return context


async def advertise(port: int):
    from zeroconf import InterfaceChoice, IPVersion
    from zeroconf.asyncio import AsyncServiceInfo, AsyncZeroconf
    # The default interface only: binding all of them fails on a Mac with "address in use" when another program
    # has an mDNS socket open, such as a Chromium browser.
    zc = AsyncZeroconf(ip_version=IPVersion.V4Only, interfaces=InterfaceChoice.Default)
    host = socket.gethostname().split(".")[0]
    info = AsyncServiceInfo("_galileo-link._tcp.local.", f"mock-master.{'_galileo-link._tcp.local.'}", port=port,
                            properties={"name": "Mock Master", "node": "mock-master", "proto": "1", "role": "master"},
                            server=f"{host}.local.", addresses=[socket.inet_aton(a) for a in local_addresses()])
    await zc.async_register_service(info)
    return zc


def local_addresses() -> list[str]:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        with contextlib.suppress(OSError):
            s.connect(("10.255.255.255", 1))
            return [s.getsockname()[0]]
    return ["127.0.0.1"]


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", default="normal-night")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--time-scale", type=float, default=1.0)
    parser.add_argument("--no-mdns", action="store_true")
    parser.add_argument("--print-ready", action="store_true")
    args = parser.parse_args()

    master = MockMaster(load_scenario(args.scenario), args.time_scale)
    cert, key, fingerprint = make_certificate()
    state = {"fingerprint": fingerprint}
    app = master.app()
    app["fingerprint"] = lambda: state["fingerprint"]
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, args.host, args.port, ssl_context=ssl_context(cert, key))
    await site.start()
    port = runner.addresses[0][1]

    async def rotate() -> None:
        """A new certificate on the same address: every pinned client must refuse it."""
        nonlocal site
        new_cert, new_key, state["fingerprint"] = make_certificate()
        if master.ws is not None:
            await master.ws.close()
        await site.stop()
        site = web.TCPSite(runner, args.host, port, ssl_context=ssl_context(new_cert, new_key))
        await site.start()
    master.on_rotate = rotate

    zc = None
    if not args.no_mdns:
        try:
            zc = await advertise(port)
        except Exception as exc:  # the address can still be typed
            print(f"No Bonjour advertisement: {exc}")
    if args.print_ready:
        print(f"READY {port} {fingerprint}", flush=True)
    else:
        print(f"Mock Master: scenario {args.scenario}, https://{local_addresses()[0]}:{port}, fingerprint {fingerprint[:16]}…", flush=True)
    try:
        await asyncio.Event().wait()
    finally:
        if zc is not None:
            await zc.async_close()
        await runner.cleanup()


if __name__ == "__main__":
    with contextlib.suppress(KeyboardInterrupt):
        asyncio.run(main())
