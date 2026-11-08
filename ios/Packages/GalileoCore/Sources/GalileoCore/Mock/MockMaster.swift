import CoreImage
import Foundation
import GalileoAPI
import HTTPTypes
import simd

/// The Master's side of Connect Link and the Control Surface, played from a scenario in `api/scenarios/`
/// (`S-MOCK`, ADR-APP-006). It stands behind the same transports as a real Master, so all the client code runs
/// against it, in tests, in UI tests and in demonstration mode (`MAPP-CON-110`).
public actor MockMaster: ClientTransport, StreamTransport {
    public static let token = "mock-token"
    public static let peerID = "mock-peer"
    public static let fingerprint = String(repeating: "ab", count: 32)

    private let scenario: JSONValue
    private let clock: any LinkClock
    private let timeScale: Double
    private let catalogue: [String: Components.Schemas.Operation]
    private let catalogueBodies: [JSONValue]
    private let preview: Data?

    private var values: [String: [String: JSONValue]] = [:]
    private var pairingRequests: [String: (created: Date, collected: Bool)] = [:]
    private var revoked = false
    private var leases: [String: String] = [:]
    private var alerts: [JSONValue] = []
    private var idempotent: [String: (HTTPResponse.Status, JSONValue)] = [:]
    private var confirmTokens: [String: (operation: String, args: JSONValue)] = [:]
    private var holds: [String: (pier: String, family: String, lastBeat: Date)] = [:]
    private var counter = 0
    private var cachedSkyObjects: [SkyObject]?

    private var epoch = "mock-epoch-1"
    private var seq = 0
    private var ring: [(seq: Int, data: JSONValue)] = []
    private let ringLimit = 256
    private var stream: AsyncThrowingStream<Data, any Error>.Continuation?
    private var started: Date?
    private var tasks: [Task<Void, Never>] = []

    /// Requests seen, for tests: `METHOD /path`.
    public private(set) var requests: [String] = []

    // MARK: Loading

    public enum LoadError: Error { case missing(String) }

    static func resource(_ name: String, _ ext: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: name, withExtension: ext, subdirectory: "scenarios") else {
            throw LoadError.missing("\(name).\(ext)")
        }
        return try Data(contentsOf: url)
    }

    /// A scenario with `extends` resolved.
    public static func scenario(named name: String) throws -> JSONValue {
        let own = try JSONDecoder().decode(JSONValue.self, from: resource(name, "json"))
        guard let parent = own["extends"]?.stringValue, case .object(var merged) = try scenario(named: parent),
            case .object(let fields) = own
        else { return own }
        merged.merge(fields) { _, new in new }
        return .object(merged)
    }

    /// `timeScale` above 1 runs the script faster; tests use it.
    public init(scenarioNamed name: String, clock: any LinkClock = SystemClock(), timeScale: Double = 1) throws {
        scenario = try Self.scenario(named: name)
        self.clock = clock
        self.timeScale = timeScale
        var bodies: [JSONValue] = []
        for file in ["catalogue", "catalogue.planned"] {
            let json = try JSONDecoder().decode(JSONValue.self, from: Self.resource(file, "json"))
            bodies += json["operations"]?.arrayValue ?? []
        }
        catalogueBodies = bodies
        catalogue = Dictionary(
            uniqueKeysWithValues: try bodies.map { body in
                let operation = try body.converted(to: Components.Schemas.Operation.self)
                return (operation.name, operation)
            })
        preview = try? Self.resource("preview", "jpg")
        var values: [String: [String: JSONValue]] = [:]
        for (pier, content) in scenario["state"]?.objectValue ?? [:] { values[pier] = content.objectValue ?? [:] }
        self.values = values
        var leases: [String: String] = [:]
        for (pier, holder) in scenario["lease_holders"]?.objectValue ?? [:] { leases[pier] = holder.stringValue }
        self.leases = leases
        alerts = scenario["alerts"]?.arrayValue ?? []
    }

    deinit { for task in tasks { task.cancel() } }

    /// Ends the script's timers; demonstration mode calls this when it is left.
    public func shutDown() {
        for task in tasks { task.cancel() }
        tasks = []
        stream?.finish()
        stream = nil
    }

    // MARK: HTTP

    public func send(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String
    ) async throws -> (HTTPResponse, HTTPBody?) {
        let bytes: Data
        if let body { bytes = try await Data(collecting: body, upTo: 1024 * 1024) } else { bytes = Data() }
        let json = (try? JSONDecoder().decode(JSONValue.self, from: bytes)) ?? .object([:])
        let target = request.path ?? "/"
        let parts = target.split(separator: "?", maxSplits: 1)
        let path = String(parts.first ?? "")
        let query = parts.count > 1 ? Self.parseQuery(String(parts[1])) : [:]
        let method = request.method.rawValue
        requests.append("\(method) \(path)")
        let segments = path.split(separator: "/").map(String.init)

        @Sendable func reply(_ status: Int, _ value: JSONValue) throws -> (HTTPResponse, HTTPBody?) {
            let data = try JSONEncoder().encode(value)
            return (
                HTTPResponse(status: .init(code: status), headerFields: [.contentType: "application/json"]),
                HTTPBody(data)
            )
        }

        // Unauthenticated: pairing.
        if method == "POST", path == "/connect/v1/pair" {
            guard json["plugin"]?.stringValue != nil, let name = json["name"]?.stringValue, !name.isEmpty else {
                return try reply(400, ["error": "bad request"])
            }
            counter += 1
            let id = "req-\(counter)"
            pairingRequests[id] = (clock.now(), false)
            let code = String(format: "%06d", (counter * 271_828) % 1_000_000)
            return try reply(
                202,
                [
                    "request_id": .string(id), "code": .string(code), "expires_in": 300,
                    "fingerprint": .string(Self.fingerprint),
                    "protocol": scenario["handshake"]?["node"]?["protocol"] ?? 1,
                ])
        }
        if method == "GET", segments.count == 4, segments[2] == "pair" {
            return try reply(200, pollPairing(segments[3]))
        }

        // Everything else needs the token.
        guard !revoked, request.headerFields[.authorization] == "Bearer \(Self.token)" else {
            return try reply(401, ["error": "unauthorised"])
        }

        switch (method, path) {
        case ("POST", "/connect/v1/handshake"):
            var result = scenario["handshake"]?.objectValue ?? [:]
            if result["allowed"] == nil {
                result["allowed"] = result["verdict"]?.stringValue == "refused"
                    ? [] : ["config.read", "config.write", "data.push", "data.pull", "control", "view"]
            }
            return try reply(200, .object(result))
        case ("GET", "/connect/v1/whoami"):
            return try reply(
                200,
                [
                    "peer_id": .string(Self.peerID), "name": "Mock device", "plugin": "mobileconnect",
                    "permissions": scenario["permissions"] ?? ["control", "view"],
                ])
        case ("GET", "/mobileconnect/v1/capabilities"):
            return try reply(200, scenario["capabilities"] ?? ["plugin_api": "1.0", "areas": []])
        case ("GET", "/mobileconnect/v1/piers"):
            return try reply(200, ["piers": scenario["piers"] ?? []])
        case ("GET", "/mobileconnect/v1/catalogue"):
            return try reply(200, ["operations": .array(catalogueBody(pier: query["pier"]))])
        case ("GET", "/mobileconnect/v1/alerts"):
            return try reply(200, ["alerts": .array(alerts.map(stampedAlert).reversed())])
        default: break
        }

        let pier = query["pier"] ?? ""
        switch (method, path) {
        case ("POST", "/mobileconnect/v1/snapshot"):
            let open = stream != nil
            if open { emitSnapshot() }
            return try reply(200, ["ok": .bool(open)])
        case ("POST", "/mobileconnect/v1/lease"):
            let (status, value) = leaseAcquire(pier: pier, token: request.headerFields[.confirmToken])
            return try reply(status, value)
        case ("DELETE", "/mobileconnect/v1/lease"):
            let (status, value) = leaseRelease(pier: pier)
            return try reply(status, value)
        case ("POST", "/mobileconnect/v1/ops"):
            let (status, value) = invoke(
                pier: pier, name: query["name"] ?? "", body: json, key: request.headerFields[.idempotencyKey],
                confirmToken: request.headerFields[.confirmToken])
            return try reply(Int(status.code), value)
        case ("GET", "/mobileconnect/v1/preview"):
            guard let image = rendered(query: query) else { return try reply(404, ["error": "no frame yet"]) }
            var fields: HTTPFields = [.contentType: "image/jpeg"]
            if let extent = previewImage?.extent {
                fields[HTTPField.Name("X-Frame-Width")!] = String(Int(extent.width))
                fields[HTTPField.Name("X-Frame-Height")!] = String(Int(extent.height))
            }
            return (HTTPResponse(status: .ok, headerFields: fields), HTTPBody(image))
        case ("GET", "/mobileconnect/v1/tile"):
            guard let image = renderedTile(query: query) else { return try reply(404, ["error": "no such tile"]) }
            return (HTTPResponse(status: .ok, headerFields: [.contentType: "image/jpeg"]), HTTPBody(image))
        case ("GET", "/mobileconnect/v1/sky/catalog"):
            return skyCatalog(query: query)
        case ("GET", "/mobileconnect/v1/sky/ephemeris"):
            return try reply(200, skyEphemeris(query: query))
        case ("GET", "/mobileconnect/v1/sky/site"):
            guard let site = scenario["sky"]?["site"] else { return try reply(404, ["error": "no location set"]) }
            return try reply(200, site)
        case ("GET", "/mobileconnect/v1/sky/constellations"):
            return try reply(200, skyConstellations())
        case ("GET", "/mobileconnect/v1/sky/catalogs"):
            return try reply(200, skyCatalogs())
        case ("GET", "/mobileconnect/v1/sky/survey/tile"):
            // The demo ships no sky pictures, as they aren't ours to. Tests can point it at a folder of real tiles.
            guard let image = Self.testSurveyTile(query: query) else { return try reply(404, ["error": "no sky survey in the demo"]) }
            let fields: HTTPFields = [.contentType: "image/jpeg", HTTPField.Name("X-Image-Credit")!: "Test tiles"]
            return (HTTPResponse(status: .ok, headerFields: fields), HTTPBody(image))
        case ("GET", "/mobileconnect/v1/sky/check"):
            guard let check = skyCheck(query: query) else { return try reply(404, ["error": "no location set"]) }
            return try reply(200, check)
        case ("GET", "/mobileconnect/v1/sky/identify"):
            guard let found = skyIdentify(query: query) else { return try reply(404, ["error": "no such object"]) }
            return try reply(200, found)
        default:
            return try reply(404, ["error": "no such route"])
        }
    }

    private static func parseQuery(_ text: String) -> [String: String] {
        var result: [String: String] = [:]
        for pair in text.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2 { result[kv[0]] = kv[1].removingPercentEncoding }
        }
        return result
    }

    private func pollPairing(_ id: String) -> JSONValue {
        guard let request = pairingRequests[id] else { return ["state": "expired"] }
        let waited = clock.now().timeIntervalSince(request.created) * timeScale
        guard waited >= (scenario["pair_approval_s"]?.doubleValue ?? 0) else { return ["state": "pending"] }
        switch scenario["pairing"]?.stringValue {
        case "reject": return ["state": "rejected"]
        case "never": return ["state": "pending"]
        default:
            if request.collected { return ["state": "collected"] }
            pairingRequests[id]?.collected = true
            return ["state": "approved", "token": .string(Self.token)]
        }
    }

    private func catalogueBody(pier: String?) -> [JSONValue] {
        let unavailable = pier.flatMap { scenario["unavailable"]?[$0]?.objectValue } ?? [:]
        return catalogueBodies.map { body in
            guard let name = body["name"]?.stringValue, let reason = unavailable[name],
                case .object(var fields) = body
            else { return body }
            fields["available"] = false
            fields["unavailable_because"] = reason
            return .object(fields)
        }
    }

    private func stampedAlert(_ alert: JSONValue) -> JSONValue {
        guard case .object(var fields) = alert else { return alert }
        if fields["time"] == nil { fields["time"] = .string(WireTime.string(clock.now())) }
        return .object(fields)
    }

    // MARK: Lease

    private func leaseAcquire(pier: String, token: String?) -> (Int, JSONValue) {
        guard hasControl(pier) else {
            return (403, ["error": "refused", "reason": "This device can only view this Pier."])
        }
        if let holder = leases[pier], holder != Self.peerID {
            let taking = token.flatMap { confirmTokens.removeValue(forKey: $0)?.operation } == "lease"
            if !taking {
                return (
                    409,
                    [
                        "error": "refused", "reason": .string("\(holder) has control of this Pier."),
                        "detail": ["kind": "lease_held", "holder": .string(holder), "can_take": true],
                        "confirm_token": .string(makeConfirmToken(operation: "lease", args: nil)), "expires_in": 60,
                    ]
                )
            }
        }
        leases[pier] = Self.peerID
        emit(["type": "lease", "pier": .string(pier), "holder": .string(Self.peerID), "holder_name": "This device"])
        return (200, ["pier": .string(pier), "held_by_me": true, "holder": .string(Self.peerID)])
    }

    private func leaseRelease(pier: String) -> (Int, JSONValue) {
        if leases[pier] == Self.peerID {
            leases[pier] = nil
            emit(["type": "lease", "pier": .string(pier), "holder": nil, "holder_name": nil])
        }
        return (200, ["pier": .string(pier), "held_by_me": false])
    }

    private func makeConfirmToken(operation: String, args: JSONValue?) -> String {
        counter += 1
        let token = "confirm-\(counter)"
        confirmTokens[token] = (operation, args ?? .null)
        return token
    }

    private func hasControl(_ pier: String) -> Bool {
        let info = scenario["piers"]?.arrayValue?.first { $0["id"]?.stringValue == pier }
        let permitted = scenario["permissions"]?.arrayValue?.contains("control") ?? true
        return permitted && info?["access"]?.stringValue == "control" && info?["reachable"]?.boolValue != false
    }

    // MARK: Operations

    private func invoke(
        pier: String, name: String, body: JSONValue, key: String?, confirmToken: String?
    ) -> (HTTPResponse.Status, JSONValue) {
        let storeKey = key.map { "\(pier)/\($0)" }
        if let storeKey, let stored = idempotent[storeKey] { return stored }
        let answer = run(pier: pier, name: name, body: body, confirmToken: confirmToken, key: key)
        if let storeKey { idempotent[storeKey] = answer }
        return answer
    }

    private func refuse(
        _ kind: String, _ reason: String, status: HTTPResponse.Status = .conflict, detail: JSONValue? = nil
    ) -> (HTTPResponse.Status, JSONValue) {
        var fields: [String: JSONValue] = ["error": .string(kind), "reason": .string(reason)]
        if let detail { fields["detail"] = detail }
        return (status, .object(fields))
    }

    private func run(
        pier: String, name: String, body: JSONValue, confirmToken: String?, key: String?
    ) -> (HTTPResponse.Status, JSONValue) {
        guard let entry = catalogue[name], let info = MasterAPI.operationInfo(entry) else {
            return refuse("unavailable", "This Master does not offer \(name).", status: .notFound)
        }
        let args = body["args"]?.objectValue ?? [:]
        if let fault = info.validate(args) {
            return refuse(
                "invalid", "\(fault.path) \(fault.reason)", status: .badRequest, detail: ["path": .string(fault.path)])
        }
        if let reason = scenario["unavailable"]?[pier]?[name]?.stringValue {
            return refuse("unavailable", reason)
        }
        if info.operationClass != .read, !hasControl(pier) {
            return refuse("refused", "This device can only view this Pier.", status: .forbidden)
        }
        if info.operationClass == .emergency, body["confirmed"]?.boolValue != true {
            return refuse("refused", "An emergency action needs a deliberate confirmation.", status: .forbidden)
        }
        if info.needsLease, leases[pier] != Self.peerID {
            if let holder = leases[pier] {
                return refuse(
                    "refused", "\(holder) has control of this Pier.",
                    detail: ["kind": "lease_held", "holder": .string(holder), "can_take": true])
            }
            return refuse("refused", "Take control of this Pier first.", detail: ["kind": "no_lease"])
        }
        if info.operationClass == .confirm {
            guard let confirmToken, let stored = confirmTokens.removeValue(forKey: confirmToken),
                stored.operation == name, stored.args == .object(args)
            else {
                let token = makeConfirmToken(operation: name, args: .object(args))
                let reason = scenario["consequences"]?[name]?.stringValue ?? info.description ?? name
                return (
                    .conflict,
                    [
                        "error": "confirmation_required", "reason": .string(reason), "confirm_token": .string(token),
                        "expires_in": 60,
                    ]
                )
            }
        }

        var result: JSONValue? = scenario["results"]?[name]
        if name.hasPrefix("sky.") {
            guard let answer = skyOperation(name, args: args) else {
                return refuse("refused", "There is no catalogue object called that.")
            }
            return (.ok, ["ok": true, "result": answer])
        }
        if name.hasSuffix(".hold_start") {
            counter += 1
            let id = "hold-\(counter)"
            let family = String(name.dropLast(".hold_start".count))
            holds[id] = (pier, family, clock.now())
            schedule(after: 0, scaled: false) { master in await master.deadmanLoop(id: id) }
            result = ["hold_id": .string(id)]
        } else if name.hasSuffix(".hold_beat") {
            let id = args["hold_id"]?.stringValue ?? ""
            let alive = holds[id] != nil
            holds[id]?.lastBeat = clock.now()
            return (.ok, ["ok": true, "result": .bool(alive)])
        } else if name.hasSuffix(".hold_stop") {
            let id = args["hold_id"]?.stringValue ?? ""
            result = .bool(holds.removeValue(forKey: id) != nil)
        } else if name.hasSuffix(".status") || name.hasSuffix(".names") {
            result = statusBody(pier: pier, area: info.area)
        }

        runEffects(of: name, pier: pier, args: args)

        counter += 1
        let correlation = "op-\(counter)"
        if info.longRunning {
            let requestID = key.map(JSONValue.string) ?? .null
            emit([
                "type": "op.started", "correlation_id": .string(correlation), "request_id": requestID,
                "pier": .string(pier), "operation": .string(name),
            ])
            schedule(after: scenario["durations"]?[name]?.doubleValue ?? 3) { master in
                await master.emit([
                    "type": "op.finished", "correlation_id": .string(correlation), "request_id": requestID,
                    "pier": .string(pier), "operation": .string(name), "outcome": "ok",
                ])
            }
        }
        var fields: [String: JSONValue] = ["ok": true, "correlation_id": .string(correlation)]
        if let result { fields["result"] = result }
        return (.ok, .object(fields))
    }

    private func statusBody(pier: String, area: String) -> JSONValue {
        var result: [String: JSONValue] = [:]
        for (key, value) in values[pier] ?? [:] where key.hasPrefix(area + ".") {
            result[String(key.dropFirst(area.count + 1))] = value
        }
        return .object(result)
    }

    /// As the real Master: a hold whose keep-alives stop ends by itself (`HoldManager.check`).
    private func deadmanLoop(id: String) async {
        while let hold = holds[id] {
            try? await clock.sleep(seconds: 0.1)
            if Task.isCancelled { return }
            guard let current = holds[id] else { return }
            if clock.now().timeIntervalSince(current.lastBeat) >= 0.6 {
                holds[id] = nil
                runEffects(of: "\(hold.family).hold_stop", pier: hold.pier, args: [:])
                return
            }
        }
    }

    private func runEffects(of name: String, pier: String, args: [String: JSONValue]) {
        for step in scenario["effects"]?[name]?.arrayValue ?? [] {
            let delay = step["after_s"]?.doubleValue ?? 0
            let changes = step["set"]?.objectValue ?? [:]
            if delay == 0 {
                apply(changes, pier: pier, args: args)
            } else {
                schedule(after: delay) { master in await master.apply(changes, pier: pier, args: args) }
            }
        }
    }

    private func apply(_ changes: [String: JSONValue], pier: String, args: [String: JSONValue]) {
        for key in changes.keys.sorted() {
            var value = changes[key]!
            if let name = value["$arg"]?.stringValue {
                value = args[name] ?? .null
            } else if let add = value["$add"]?.arrayValue, add.count == 2, let stateKey = add[0].stringValue,
                let argName = add[1].stringValue
            {
                let step = argName == "_one" ? 1 : args[argName]?.doubleValue ?? 0
                value = .number((values[pier]?[stateKey]?.doubleValue ?? 0) + step)
            }
            set(key, to: value, pier: pier)
        }
    }

    private func set(_ key: String, to value: JSONValue, pier: String) {
        values[pier, default: [:]][key] = value
        emit([
            "type": "delta", "pier": .string(pier), "key": .string(key), "value": value,
            "as_of": .string(WireTime.string(clock.now())),
        ])
    }

    /// The mock's stand-in for the Master rendering a preview: it applies the requested stretch so the control
    /// visibly works. A real Master does this in `galileo.control_surface.frames`.
    private func rendered(query: [String: String]) -> Data? {
        guard let preview else { return nil }
        let stretch = query["stretch"].flatMap(Double.init) ?? 50
        if stretch == 50 { return preview }
        guard let image = CIImage(data: preview) else { return preview }
        let output = image.applyingFilter("CIGammaAdjust", parameters: ["inputPower": pow(2, (50 - stretch) / 25)])
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        return CIContext().jpegRepresentation(of: output, colorSpace: colorSpace) ?? preview
    }

    // MARK: Sky

    /// Deterministic made-up stars: this is a demonstration, and the app ships no catalog (`MAPP-STR-010`).
    private var skyObjects: [SkyObject] {
        if let cachedSkyObjects { return cachedSkyObjects }
        let built = buildSkyObjects()
        cachedSkyObjects = built
        return built
    }

    private func buildSkyObjects() -> [SkyObject] {
        var state = UInt64(scenario["sky"]?["seed"]?.intValue ?? 1)
        func next() -> Double {
            state = (state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407)
            return Double(state >> 11) / Double(1 << 53)
        }
        var list: [SkyObject] = []
        let stars = scenario["sky"]?["stars"]?.intValue ?? 0
        for i in 0..<stars {
            let ra = next() * 360
            let dec = asin(next() * 2 - 1) * 180 / .pi
            let mag = 13 - 13.5 * pow(next(), 0.33)  // many faint, few bright
            list.append(SkyObject(index: Int32(i), raDeg: Float(ra), decDeg: Float(dec), magnitude: Float(mag), kind: 0))
        }
        for (i, object) in (scenario["sky"]?["objects"]?.arrayValue ?? []).enumerated() {
            list.append(
                SkyObject(
                    index: Int32(stars + i), raDeg: Float(object["ra_deg"]?.doubleValue ?? 0),
                    decDeg: Float(object["dec_deg"]?.doubleValue ?? 0), magnitude: Float(object["magnitude"]?.doubleValue ?? 99), kind: 1))
        }
        // The stars of the demo's constellations are real, so that their figures have something to join.
        for star in figureStars {
            list.append(SkyObject(index: Int32(list.count), raDeg: Float(star.ra), decDeg: Float(star.dec), magnitude: Float(star.magnitude), kind: 0))
        }
        return list
    }

    private var figureStars: [(name: String, ra: Double, dec: Double, magnitude: Double)] {
        (scenario["sky"]?["constellations"]?.arrayValue ?? []).flatMap { figure in
            (figure["stars"]?.arrayValue ?? []).compactMap { star -> (String, Double, Double, Double)? in
                guard let row = star.arrayValue, row.count == 4, let name = row[0].stringValue, let ra = row[1].doubleValue,
                    let dec = row[2].doubleValue, let magnitude = row[3].doubleValue
                else { return nil }
                return (name, ra, dec, magnitude)
            }
        }
    }

    private func skyConstellations() -> JSONValue {
        var lines: [JSONValue] = []
        var names: [JSONValue] = []
        for figure in scenario["sky"]?["constellations"]?.arrayValue ?? [] {
            let stars = figure["stars"]?.arrayValue ?? []
            for line in figure["lines"]?.arrayValue ?? [] {
                lines.append(.array((line.arrayValue ?? []).compactMap { $0.intValue }.map { index -> JSONValue in
                    let star = stars[index].arrayValue ?? []
                    return [star[1], star[2]]
                }))
            }
            names.append(["name": figure["name"] ?? "", "code": figure["code"] ?? "", "ra_deg": figure["ra_deg"] ?? 0, "dec_deg": figure["dec_deg"] ?? 0])
        }
        return ["lines": .array(lines), "names": .array(names)]
    }

    private func skyCatalog(query: [String: String]) -> (HTTPResponse, HTTPBody?) {
        let ra = query["ra"].flatMap(Double.init) ?? 0
        let dec = query["dec"].flatMap(Double.init) ?? 0
        let radius = query["radius"].flatMap(Double.init) ?? 180
        let level = query["lod"].flatMap(Int.init) ?? 4
        let limit = Float([4.0, 7.0, 10.0, 13.0, 99.0][max(0, min(4, level))])
        let center = SkyGeometry.unitVector(raDeg: Float(ra), decDeg: Float(dec))
        let cosRadius = Float(cos(min(180, radius) * .pi / 180))
        let matched = skyObjects
            .filter { $0.magnitude <= limit && simd_dot(SkyGeometry.unitVector(raDeg: $0.raDeg, decDeg: $0.decDeg), center) >= cosRadius }
            .sorted { $0.magnitude < $1.magnitude }
        let offset = query["cursor"].flatMap(Int.init) ?? 0
        let page = Array(matched.dropFirst(offset).prefix(500))
        let next = offset + page.count
        var fields: HTTPFields = [.contentType: "application/octet-stream"]
        fields[HTTPField.Name("X-Sky-Next")!] = next < matched.count ? String(next) : ""
        fields[HTTPField.Name("X-Sky-Total")!] = String(matched.count)
        return (HTTPResponse(status: .ok, headerFields: fields), HTTPBody(SkyFrameDecoder.encode(page)))
    }

    private func skyEphemeris(query: [String: String]) -> JSONValue {
        let start = query["from"].flatMap(WireTime.parse) ?? query["t"].flatMap(WireTime.parse) ?? clock.now()
        let end = query["to"].flatMap(WireTime.parse) ?? start
        let step = max(60, query["step"].flatMap(Double.init) ?? 600)
        var samples: [JSONValue] = []
        var time = start
        while time <= end, samples.count < 1440 {
            let hours = time.timeIntervalSince1970 / 3600
            let bodies: [JSONValue] = (scenario["sky"]?["bodies"]?.arrayValue ?? []).map { body in
                let moved = ((body["ra_deg"]?.doubleValue ?? 0) + (body["rate_deg_per_h"]?.doubleValue ?? 0) * (hours - 493_000))
                    .truncatingRemainder(dividingBy: 360)
                return ["name": body["name"] ?? "?", "ra_deg": .number(moved < 0 ? moved + 360 : moved), "dec_deg": body["dec_deg"] ?? 0]
            }
            samples.append(["t": .string(WireTime.string(time)), "bodies": .array(bodies)])
            time = time.addingTimeInterval(step)
        }
        return ["as_of": .string(WireTime.string(clock.now())), "samples": .array(samples)]
    }

    /// By name, as `catalogs_of` in core has it.
    private func skyCatalogs() -> JSONValue {
        let starCount = scenario["sky"]?["stars"]?.doubleValue.map(Int.init) ?? 0
        let objects = scenario["sky"]?["objects"]?.arrayValue ?? []
        func members(_ test: (String) -> Bool) -> JSONValue {
            .array(objects.indices.filter { test(objects[$0]["name"]?.stringValue ?? "") }.map { .number(Double(starCount + $0)) })
        }
        func catalog(_ name: String, _ prefix: String) -> JSONValue {
            .object(["name": .string(name), "indexes": members { $0.hasPrefix(prefix) && $0.dropFirst(prefix.count).first?.isNumber == true }])
        }
        return .object(["catalogs": .array([catalog("Messier", "M "), catalog("Caldwell", "C "), catalog("NGC", "NGC ")])])
    }

    private static func testSurveyTile(query: [String: String]) -> Data? {
        guard let folder = ProcessInfo.processInfo.environment["GALILEO_TEST_SURVEY_DIR"],
            let order = query["order"].flatMap(Int.init), let npix = query["npix"].flatMap(Int.init)
        else { return nil }
        return try? Data(contentsOf: URL(fileURLWithPath: "\(folder)/Norder\(order)/Dir\(npix / 10000 * 10000)/Npix\(npix).jpg"))
    }

    private func skyCheck(query: [String: String]) -> JSONValue? {
        guard let site = scenario["sky"]?["site"], let latitude = site["latitude_deg"]?.doubleValue,
            let longitude = site["longitude_deg"]?.doubleValue, let ra = query["ra"].flatMap(Double.init),
            let dec = query["dec"].flatMap(Double.init)
        else { return nil }
        let place = SkyGeometry.horizontal(
            raDeg: ra, decDeg: dec, site: SkySite(latitude: latitude, longitude: longitude), at: clock.now())
        let skyline = skylineHeight(site["horizon"]?.arrayValue ?? [], azimuth: place.azimuth)
        var answer: [String: JSONValue] = [
            "altitude_deg": .number(place.altitude), "azimuth_deg": .number(place.azimuth), "above_horizon": .bool(place.altitude > skyline),
        ]
        if place.altitude <= skyline {
            answer["reason"] = .string(String(format: "Below the horizon: altitude %.1f° where the skyline is %.1f°.", place.altitude, skyline))
        }
        return .object(answer)
    }

    private func skylineHeight(_ horizon: [JSONValue], azimuth: Double) -> Double {
        let points = horizon.compactMap { point -> (Double, Double)? in
            guard let az = point["az_deg"]?.doubleValue, let alt = point["alt_deg"]?.doubleValue else { return nil }
            return (az, alt)
        }.sorted { $0.0 < $1.0 }
        guard let first = points.first, let last = points.last else { return 0 }
        let before = points.last { $0.0 <= azimuth } ?? (last.0 - 360, last.1)
        let after = points.first { $0.0 > azimuth } ?? (first.0 + 360, first.1)
        let span = after.0 - before.0
        return span > 0 ? before.1 + (after.1 - before.1) * (azimuth - before.0) / span : before.1
    }

    private func skyIdentify(query: [String: String]) -> JSONValue? {
        guard let index = query["index"].flatMap(Int.init), let found = skyObjects.first(where: { $0.index == Int32(index) }) else { return nil }
        var answer: [String: JSONValue] = [
            "index": .number(Double(index)), "kind": found.isDeepSky ? "deep_sky" : "star", "ra_deg": .number(Double(found.raDeg)),
            "dec_deg": .number(Double(found.decDeg)), "magnitude": .number(Double(found.magnitude)),
        ]
        let stars = scenario["sky"]?["stars"]?.intValue ?? 0
        if found.isDeepSky, let object = scenario["sky"]?["objects"]?.arrayValue?[index - stars] {
            answer["name"] = object["name"]
            answer["type"] = object["type"]
        } else if !found.isDeepSky, index >= stars {
            let offset = index - stars - (scenario["sky"]?["objects"]?.arrayValue?.count ?? 0)
            if figureStars.indices.contains(offset) { answer["name"] = .string(figureStars[offset].name) }
        }
        return .object(answer)
    }

    private func skyOperation(_ name: String, args: [String: JSONValue]) -> JSONValue? {
        let objects = scenario["sky"]?["objects"]?.arrayValue ?? []
        func names(_ object: JSONValue) -> [String] {
            [object["name"]?.stringValue ?? ""] + (object["designations"]?.arrayValue?.compactMap(\.stringValue) ?? [])
        }
        switch name {
        case "sky.search":
            let query = args["query"]?.stringValue?.lowercased() ?? ""
            let found = objects.filter { object in names(object).contains { $0.lowercased().contains(query) } }
            return ["query": .string(query), "objects": .array(found), "total": .number(Double(found.count))]
        case "sky.object":
            let wanted = args["name"]?.stringValue?.lowercased() ?? ""
            guard case .object(var fields)? = objects.first(where: { object in names(object).contains { $0.lowercased() == wanted } }) else {
                return nil
            }
            fields["site"] = scenario["sky"]?["site"]?["name"]
            fields["rise_transit_set"] = ["rise": "19:12", "transit": "02:40", "set": "10:08"]
            return .object(fields)
        case "sky.framing":
            return ["ra_deg": args["ra_deg"] ?? 0, "dec_deg": args["dec_deg"] ?? 0, "fov_deg": scenario["sky"]?["framing"]?["fov_deg"] ?? [1, 1]]
        default:
            return nil
        }
    }

    private func renderedTile(query: [String: String]) -> Data? {
        guard let image = previewImage else { return nil }
        let level = query["z"].flatMap(Int.init) ?? 0
        let x = query["x"].flatMap(Int.init) ?? 0
        let y = query["y"].flatMap(Int.init) ?? 0
        let scale = 1 / CGFloat(1 << max(0, level))
        let width = image.extent.width * scale
        let height = image.extent.height * scale
        let left = CGFloat(x * 256)
        let top = CGFloat(y * 256)
        guard left < width, top < height else { return nil }
        // Core Image's origin is at the bottom left, the wire's at the top left.
        let crop = CGRect(x: left, y: max(0, height - top - 256), width: min(256, width - left), height: min(256, height - top))
        let output = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale)).cropped(to: crop)
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        return CIContext().jpegRepresentation(of: output, colorSpace: colorSpace)
    }

    private var previewImage: CIImage? { preview.flatMap { CIImage(data: $0) } }

    // MARK: Stream

    public func connect(epoch clientEpoch: String?, last: Int?) async throws -> AsyncThrowingStream<Data, any Error> {
        if revoked { throw LinkError.unpaired }
        stream?.finish()
        let (frames, continuation) = AsyncThrowingStream<Data, any Error>.makeStream()
        stream = continuation

        if let last, let clientEpoch {
            let oldest = ring.first?.seq ?? (seq + 1)
            if clientEpoch == epoch, last >= oldest - 1 {
                yield(["type": "hello", "epoch": .string(epoch), "seq": .number(Double(seq))])
                for entry in ring where entry.seq > last {
                    yield(["seq": .number(Double(entry.seq)), "data": entry.data])
                }
            } else {
                yield(["type": "resync", "epoch": .string(epoch), "seq": .number(Double(seq))])
            }
        } else {
            yield(["type": "hello", "epoch": .string(epoch), "seq": .number(Double(seq))])
        }
        startScriptOnce()
        return frames
    }

    private func emitSnapshot() {
        var piers: [String: JSONValue] = [:]
        for (pier, content) in values {
            var merged = content
            if let holder = leases[pier] {
                merged["lease.holder"] = .string(holder)
                merged["lease.holder_name"] = holder == Self.peerID ? "This device" : .string(holder)
            }
            piers[pier] = .object(merged)
        }
        emit(["type": "snapshot", "as_of": .string(WireTime.string(clock.now())), "piers": .object(piers)])
    }

    private func emit(_ data: JSONValue) {
        seq += 1
        ring.append((seq, data))
        if ring.count > ringLimit { ring.removeFirst(ring.count - ringLimit) }
        yield(["seq": .number(Double(seq)), "data": data])
    }

    private func yield(_ frame: JSONValue) {
        if let data = try? JSONEncoder().encode(frame) { stream?.yield(data) }
    }

    /// Ends the connection without warning.
    public func dropStream() {
        stream?.finish(throwing: URLError(.networkConnectionLost))
        stream = nil
    }

    /// The Master restarts its stream, so nothing can be replayed.
    public func forceResync() {
        counter += 1
        epoch = "mock-epoch-\(counter + 1)"
        ring = []
        dropStream()
    }

    public func revoke() {
        revoked = true
        dropStream()
    }

    // MARK: Script

    private func schedule(
        after seconds: Double, scaled: Bool = true, _ body: @escaping @Sendable (MockMaster) async -> Void
    ) {
        let scale = scaled ? timeScale : 1
        let clock = self.clock
        tasks.removeAll { $0.isCancelled }
        tasks.append(
            Task { [weak self] in
                if seconds > 0 {
                    do { try await clock.sleep(seconds: seconds / scale) } catch { return }
                }
                guard let self, !Task.isCancelled else { return }
                await body(self)
            })
    }

    private func startScriptOnce() {
        guard started == nil else { return }
        started = clock.now()
        runTimeline()
        for wave in scenario["waves"]?.arrayValue ?? [] { startWave(wave) }
    }

    private func runTimeline() {
        let events = scenario["timeline"]?.arrayValue ?? []
        for event in events {
            schedule(after: event["at_s"]?.doubleValue ?? 0) { master in await master.play(event) }
        }
        if let loop = scenario["loop_s"]?.doubleValue, loop > 0, !events.isEmpty {
            schedule(after: loop) { master in await master.runTimeline() }
        }
    }

    private func play(_ event: JSONValue) {
        if let changes = event["set"]?.objectValue, let pier = event["pier"]?.stringValue {
            apply(changes, pier: pier, args: [:])
        }
        if case .object(var alert)? = event["alert"] {
            counter += 1
            alert["id"] = .string("a\(counter)")
            alert["time"] = .string(WireTime.string(clock.now()))
            alert["type"] = "alert"
            alerts.append(.object(alert))
            if alerts.count > 100 { alerts.removeFirst() }
            emit(.object(alert))
        }
        switch event["do"]?.stringValue {
        case "drop": dropStream()
        case "resync": forceResync()
        case "revoke": revoke()
        default: break
        }
    }

    private func startWave(_ wave: JSONValue) {
        guard let every = wave["every_s"]?.doubleValue else { return }
        schedule(after: every) { master in await master.waveTick(wave: wave, every: every) }
    }

    private func waveTick(wave: JSONValue, every: Double) {
        guard let pier = wave["pier"]?.stringValue, let key = wave["key"]?.stringValue else { return }
        let elapsed = clock.now().timeIntervalSince(started ?? clock.now()) * timeScale
        func sine(_ spec: JSONValue) -> Double {
            let amplitude = spec["amp"]?.doubleValue ?? 0
            let period = max(0.001, spec["period_s"]?.doubleValue ?? 1)
            return (spec["base"]?.doubleValue ?? 0) + amplitude * sin(2 * .pi * elapsed / period)
        }
        let value: JSONValue
        if let fields = wave["fields"]?.objectValue {
            value = .object(fields.mapValues { .number(sine($0)) })
        } else if let ramp = wave["ramp_s"]?.doubleValue {
            value = .number(elapsed.truncatingRemainder(dividingBy: ramp) / ramp)
        } else {
            value = .number(sine(wave))
        }
        set(key, to: value, pier: pier)
        schedule(after: every) { master in await master.waveTick(wave: wave, every: every) }
    }
}

extension HTTPField.Name {
    fileprivate static let confirmToken = HTTPField.Name("X-Confirm-Token")!
    fileprivate static let idempotencyKey = HTTPField.Name("Idempotency-Key")!
}

extension MasterConnection {
    /// The connection demonstration mode uses: the real client code, in front of the mock.
    public static func demo(_ master: MockMaster, identity: ClientIdentity, token: String? = nil) -> MasterConnection {
        let api = MasterAPI(baseURL: URL(fileURLWithPath: "/"), transport: master, identity: identity, token: token)
        return MasterConnection(api: api, stream: master)
    }
}
