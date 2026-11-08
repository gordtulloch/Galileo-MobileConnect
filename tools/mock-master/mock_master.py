"""A scripted Master that speaks the real wire: see README.md. Same scenario rules as GalileoCore's MockMaster."""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import datetime
import io
import ipaddress
import json
import math
import secrets
import socket
import ssl
import struct
import tempfile
from pathlib import Path

from aiohttp import WSMsgType, web
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from PIL import Image

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


def encode_frame(objects: list[tuple[int, float, float, float, int]]) -> bytes:
    """galileo.skystream.encode_objects, without needing Galileo installed."""
    def varint(out: bytearray, value: int) -> None:
        value = (value << 1) ^ (value >> 63)
        while value > 0x7F:
            out.append((value & 0x7F) | 0x80)
            value >>= 7
        out.append(value)
    out = bytearray(b"GSK" + struct.pack("<BBI", 1, 1, len(objects)))
    previous = [0, 0, 0, 0]
    for index, ra, dec, mag, kind in sorted(objects, key=lambda o: o[1]):
        current = [round(ra * 1000), round(dec * 1000), round(mag * 100), index]
        for value, last in zip(current, previous, strict=True):
            varint(out, value - last)
        out.append(kind)
        previous = current
    return bytes(out)


def horizontal(ra: float, dec: float, site: dict) -> tuple[float, float]:
    days = datetime.datetime.now(datetime.UTC).timestamp() / 86400 + 2440587.5 - 2451545.0
    lst = 280.46061837 + 360.98564736629 * days + site["longitude_deg"]
    ha, d, lat = math.radians(lst - ra), math.radians(dec), math.radians(site["latitude_deg"])
    alt = math.asin(math.sin(d) * math.sin(lat) + math.cos(d) * math.cos(lat) * math.cos(ha))
    az = math.atan2(-math.sin(ha) * math.cos(d), math.sin(d) * math.cos(lat) - math.cos(d) * math.cos(ha) * math.sin(lat))
    return math.degrees(alt), math.degrees(az) % 360


def skyline_height(horizon: list[dict], azimuth: float) -> float:
    points = sorted((p["az_deg"], p["alt_deg"]) for p in horizon)
    if not points:
        return 0.0
    before = next((p for p in reversed(points) if p[0] <= azimuth), (points[-1][0] - 360, points[-1][1]))
    after = next((p for p in points if p[0] > azimuth), (points[0][0] + 360, points[0][1]))
    span = after[0] - before[0]
    return before[1] + (after[1] - before[1]) * (azimuth - before[0]) / span if span > 0 else before[1]


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
        self._sky_objects = None

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
        r.add_get("/mobileconnect/v1/tile", self.tile)
        r.add_get("/mobileconnect/v1/sky/catalog", self.sky_catalog)
        r.add_get("/mobileconnect/v1/sky/ephemeris", self.sky_ephemeris)
        r.add_get("/mobileconnect/v1/sky/site", self.sky_site)
        r.add_get("/mobileconnect/v1/sky/constellations", self.sky_constellations)
        r.add_get("/mobileconnect/v1/sky/catalogs", self.sky_catalogs)
        r.add_get("/mobileconnect/v1/sky/survey/tile", self.sky_survey_tile)
        r.add_get("/mobileconnect/v1/sky/check", self.sky_check)
        r.add_get("/mobileconnect/v1/sky/identify", self.sky_identify)
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
        with Image.open(SCENARIOS / "preview.jpg") as image:
            width, height = image.size
            stretch = int(request.query.get("stretch", 50))
            if stretch == 50:
                body = (SCENARIOS / "preview.jpg").read_bytes()
            else:
                gamma = 2 ** ((50 - stretch) / 25)
                table = [min(255, int(255 * (i / 255) ** gamma)) for i in range(256)]
                out = io.BytesIO()
                image.convert("L").point(table).save(out, "JPEG", quality=80)
                body = out.getvalue()
        return web.Response(body=body, content_type="image/jpeg",
                            headers={"X-Frame-Width": str(width), "X-Frame-Height": str(height)})

    async def tile(self, request):
        q = request.query
        level, x, y = int(q.get("z", 0)), int(q.get("x", 0)), int(q.get("y", 0))
        with Image.open(SCENARIOS / "preview.jpg") as image:
            f = 1 << level
            scaled = image.convert("L").resize((max(1, image.width // f), max(1, image.height // f)))
            left, top = x * 256, y * 256
            if left >= scaled.width or top >= scaled.height:
                return web.json_response({"error": "no such tile"}, status=404)
            out = io.BytesIO()
            scaled.crop((left, top, min(left + 256, scaled.width), min(top + 256, scaled.height))).save(out, "JPEG", quality=80)
        return web.Response(body=out.getvalue(), content_type="image/jpeg")

    # -- sky: made-up stars, in core's real encoding -----------------------------------------------
    def sky_objects(self) -> list[tuple[int, float, float, float, int]]:
        if self._sky_objects is None:
            sky = self.s.get("sky", {})
            state = sky.get("seed", 1)

            def next_value() -> float:
                nonlocal state
                state = (state * 6364136223846793005 + 1442695040888963407) % (1 << 64)
                return (state >> 11) / (1 << 53)
            objects = []
            for i in range(sky.get("stars", 0)):
                ra = next_value() * 360
                dec = math.degrees(math.asin(next_value() * 2 - 1))
                objects.append((i, ra, dec, 13 - 13.5 * next_value() ** 0.33, 0))
            stars = len(objects)
            for i, o in enumerate(sky.get("objects", [])):
                objects.append((stars + i, o["ra_deg"], o["dec_deg"], o.get("magnitude", 99.0), 1))
            # The stars of the demo's constellations are real, so that their figures have something to join.
            for star in self.figure_stars():
                objects.append((len(objects), star[1], star[2], star[3], 0))
            self._sky_objects = objects
        return self._sky_objects

    def figure_stars(self) -> list[list]:
        return [star for figure in self.s.get("sky", {}).get("constellations", []) for star in figure["stars"]]

    async def sky_catalogs(self, request):
        sky = self.s.get("sky", {})
        offset = sky.get("stars", 0)
        names = [o["name"] for o in sky.get("objects", [])]

        def members(prefix):
            return [offset + i for i, n in enumerate(names) if n.startswith(prefix) and n[len(prefix):][:1].isdigit()]
        return web.json_response({"catalogs": [{"name": "Messier", "indexes": members("M ")},
                                               {"name": "Caldwell", "indexes": members("C ")},
                                               {"name": "NGC", "indexes": members("NGC ")}]})

    async def sky_survey_tile(self, request):
        return web.json_response({"error": "no sky survey in the demo"}, status=404)  # they aren't ours to ship

    async def sky_constellations(self, request):
        lines, names = [], []
        for figure in self.s.get("sky", {}).get("constellations", []):
            lines += [[figure["stars"][i][1:3] for i in line] for line in figure["lines"]]
            names.append({"name": figure["name"], "code": figure["code"], "ra_deg": figure["ra_deg"], "dec_deg": figure["dec_deg"]})
        return web.json_response({"lines": lines, "names": names})

    async def sky_catalog(self, request):
        q = request.query
        ra0, dec0 = math.radians(float(q["ra"])), math.radians(float(q["dec"]))
        radius = math.radians(min(180.0, float(q["radius"])))
        limit = (4.0, 7.0, 10.0, 13.0, 99.0)[max(0, min(4, int(q.get("lod", 4))))]
        matched = []
        for obj in self.sky_objects():
            ra, dec = math.radians(obj[1]), math.radians(obj[2])
            cosine = math.sin(dec0) * math.sin(dec) + math.cos(dec0) * math.cos(dec) * math.cos(ra - ra0)
            if obj[3] <= limit and math.acos(max(-1.0, min(1.0, cosine))) <= radius:
                matched.append(obj)
        matched.sort(key=lambda o: o[3])
        offset = int(q.get("cursor") or 0)
        page = matched[offset:offset + 500]
        following = offset + len(page)
        return web.Response(body=encode_frame(page), content_type="application/octet-stream", headers={
            "X-Sky-Next": str(following) if following < len(matched) else "", "X-Sky-Total": str(len(matched))})

    async def sky_ephemeris(self, request):
        q = request.query

        def when(name):
            return datetime.datetime.fromisoformat(q[name].replace("Z", "+00:00")) if q.get(name) else None
        start = when("from") or when("t") or datetime.datetime.now(datetime.UTC)
        end, step = when("to") or start, max(60.0, float(q.get("step", 600)))
        samples, moment = [], start
        while moment <= end and len(samples) < 1440:
            hours = moment.timestamp() / 3600
            samples.append({"t": moment.isoformat(), "bodies": [
                {"name": b["name"], "ra_deg": (b["ra_deg"] + b.get("rate_deg_per_h", 0) * (hours - 493000)) % 360, "dec_deg": b["dec_deg"]}
                for b in self.s.get("sky", {}).get("bodies", [])]})
            moment += datetime.timedelta(seconds=step)
        return web.json_response({"as_of": now_text(), "samples": samples})

    async def sky_site(self, request):
        site = self.s.get("sky", {}).get("site")
        return web.json_response(site) if site else web.json_response({"error": "no location set"}, status=404)

    async def sky_check(self, request):
        site = self.s.get("sky", {}).get("site")
        if not site:
            return web.json_response({"error": "no location set"}, status=404)
        alt, az = horizontal(float(request.query["ra"]), float(request.query["dec"]), site)
        skyline = skyline_height(site.get("horizon", []), az)
        answer = {"altitude_deg": alt, "azimuth_deg": az, "above_horizon": alt > skyline}
        if alt <= skyline:
            answer["reason"] = f"Below the horizon: altitude {alt:.1f}° where the skyline is {skyline:.1f}°."
        return web.json_response(answer)

    async def sky_identify(self, request):
        index = int(request.query.get("index", -1))
        objects = self.sky_objects()
        if not 0 <= index < len(objects):
            return web.json_response({"error": "no such object"}, status=404)
        _, ra, dec, mag, kind = objects[index]
        answer = {"index": index, "kind": "deep_sky" if kind else "star", "ra_deg": ra, "dec_deg": dec, "magnitude": mag}
        sky = self.s.get("sky", {})
        if kind:
            named = sky["objects"][index - sky.get("stars", 0)]
            answer.update(name=named["name"], type=named.get("type"))
        elif index >= sky.get("stars", 0):
            answer["name"] = self.figure_stars()[index - sky.get("stars", 0) - len(sky.get("objects", []))][0]
        return web.json_response(answer)

    def sky_operation(self, name: str, args: dict):
        sky = self.s.get("sky", {})
        objects = sky.get("objects", [])

        def names(o):
            return [o["name"], *o.get("designations", [])]
        if name == "sky.search":
            wanted = str(args.get("query", "")).lower()
            found = [o for o in objects if any(wanted in n.lower() for n in names(o))]
            return {"query": wanted, "objects": found, "total": len(found)}
        if name == "sky.object":
            wanted = str(args.get("name", "")).lower()
            match = next((o for o in objects if wanted in (n.lower() for n in names(o))), None)
            if match is None:
                return None
            return {**match, "site": sky.get("site", {}).get("name"), "rise_transit_set": {"rise": "19:12", "transit": "02:40", "set": "10:08"}}
        if name == "sky.framing":
            return {"ra_deg": args.get("ra_deg", 0), "dec_deg": args.get("dec_deg", 0),
                    "fov_deg": sky.get("framing", {}).get("fov_deg", [1, 1])}
        return None

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
        if name.startswith("sky."):
            answer = self.sky_operation(name, args)
            if answer is None:
                return 409, {"error": "refused", "reason": "There is no catalogue object called that."}
            return 200, {"ok": True, "result": answer}
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
    # The default interface only: binding all of them can fail on a Mac with "address in use", seen on a
    # link-local address.
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
