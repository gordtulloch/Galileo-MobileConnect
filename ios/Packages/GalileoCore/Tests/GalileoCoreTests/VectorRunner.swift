import Foundation
import GalileoAPI
import HTTPTypes
import Testing

@testable import GalileoCore

/// A Master whose every answer comes from the vector: a request stays in flight until the `receive` step.
actor VectorTransport: ClientTransport, StreamTransport {
    struct Seen: Sendable {
        let method: String
        let path: String
        let headers: HTTPFields
        let body: JSONValue?
    }

    private var unclaimed: [Seen] = []
    private var inFlight: [CheckedContinuation<(HTTPResponse, HTTPBody?), any Error>] = []
    private(set) var total = 0
    private(set) var keys: [String: Int] = [:]

    private var stream: AsyncThrowingStream<Data, any Error>.Continuation?
    private(set) var connects: [(epoch: String?, last: Int?)] = []

    func send(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var parsed: JSONValue?
        if let body {
            let bytes = try await Data(collecting: body, upTo: 1024 * 1024)
            parsed = try? JSONDecoder().decode(JSONValue.self, from: bytes)
        }
        total += 1
        if let key = request.headerFields[HTTPField.Name("Idempotency-Key")!] { keys[key, default: 0] += 1 }
        unclaimed.append(Seen(method: request.method.rawValue, path: request.path ?? "", headers: request.headerFields, body: parsed))
        return try await withCheckedThrowingContinuation { inFlight.append($0) }
    }

    func claim() -> Seen? { unclaimed.isEmpty ? nil : unclaimed.removeFirst() }

    func answer(status: Int, body: JSONValue) throws {
        guard !inFlight.isEmpty else { throw VectorFailure("a receive step with no request in flight") }
        let continuation = inFlight.removeFirst()
        let data = try JSONEncoder().encode(body)
        continuation.resume(
            returning: (
                HTTPResponse(status: .init(code: status), headerFields: [.contentType: "application/json"]),
                HTTPBody(data)
            ))
    }

    func failAll() {
        let waiting = inFlight
        inFlight = []
        for continuation in waiting { continuation.resume(throwing: URLError(.networkConnectionLost)) }
        stream?.finish()
        stream = nil
    }

    func connect(epoch: String?, last: Int?) async throws -> AsyncThrowingStream<Data, any Error> {
        connects.append((epoch, last))
        let (frames, continuation) = AsyncThrowingStream<Data, any Error>.makeStream()
        stream = continuation
        return frames
    }

    var streamOpen: Bool { stream != nil }

    func deliver(_ frame: JSONValue) throws {
        guard let stream else { throw VectorFailure("a stream step with no connection") }
        stream.yield(try JSONEncoder().encode(frame))
    }

    func drop() {
        stream?.finish(throwing: URLError(.networkConnectionLost))
        stream = nil
    }
}

struct VectorFailure: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

/// Replays one file from `api/vectors/` through the real client code (`S-TEST`, `MAPP-PLT-040`).
actor VectorRunner {
    private let vector: JSONValue
    private let transport = VectorTransport()
    private let clock: TestClock
    private let store = InMemorySecureStore()
    private let api: MasterAPI
    private let session: ObservatorySession
    private let pairing: PairingFlow
    private let address = MasterAddress(host: "master.local")

    private var lastAnswer: RequestAnswer?
    private var pairingStatus: PairingStatus = .idle
    private var outcome: String = "Live"
    private var unpaired = false
    private var tasks: [Task<Void, Never>] = []

    init(vector: JSONValue) throws {
        self.vector = vector
        let start = vector["clock_start"]?.stringValue.flatMap(WireTime.parse) ?? Date(timeIntervalSince1970: 1_800_000_000)
        let clock = TestClock(start: start)
        self.clock = clock
        let setup = vector["setup"]
        let tokenStored = setup?["token_stored"]?.boolValue ?? false
        let store = self.store
        let credentials = MasterCredentials(name: "Master", address: address, token: "tok", fingerprint: "ff")
        if tokenStored { store.save(credentials) }
        let api = MasterAPI(
            baseURL: URL(string: "https://master.local:8765")!, transport: transport, identity: testIdentity,
            token: tokenStored ? "tok" : nil)
        self.api = api
        let counter = Locked(0)
        var backoff = StreamLink.Backoff()
        backoff.jitter = { 1 }
        session = ObservatorySession(
            connection: MasterConnection(api: api, stream: transport), clock: clock, backoff: backoff,
            makeID: {
                counter.withLock {
                    $0 += 1
                    return "k\($0)"
                }
            },
            onUnpaired: { [credentials] in store.delete(id: credentials.id) })
        pairing = PairingFlow(api: api, store: store, address: address, masterName: "Master", clock: clock)
    }

    func run() async throws {
        let role = vector["setup"]?["role"]?.stringValue == "Viewer" ? Role.viewer : .operator
        let catalogue = try realCatalogue()
        await session.installForTesting(role: role, peerID: "me", catalogues: ["p1": catalogue])

        for (index, step) in (vector["steps"]?.arrayValue ?? []).enumerated() {
            let name = vector["name"]?.stringValue ?? "?"
            do {
                try await perform(step)
            } catch {
                await finish()
                throw VectorFailure("\(name) step \(index) \(step["do"]?.stringValue ?? "?"): \(error)")
            }
        }
        await finish()
    }

    private func finish() async {
        await transport.failAll()
        await session.stop()
        for task in tasks { task.cancel() }
    }

    private func perform(_ step: JSONValue) async throws {
        switch step["do"]?.stringValue {
        case "act": act(step)
        case "send": try await expectSend(step)
        case "receive":
            try await transport.answer(status: step["status"]?.intValue ?? 200, body: step["body"] ?? .object([:]))
        case "stream":
            guard await eventually({ await self.transport.streamOpen }) else { throw VectorFailure("no connection to deliver to") }
            try await transport.deliver(step["frame"] ?? .null)
        case "drop": await transport.drop()
        case "expect_connect":
            let done = await eventually {
                let connects = await self.transport.connects.count
                let open = await self.transport.streamOpen
                return connects >= 2 && open
            }
            guard done, let last = await transport.connects.last else { throw VectorFailure("no reconnect") }
            guard last.epoch == step["epoch"]?.stringValue, last.last == step["last"]?.intValue else {
                throw VectorFailure("reconnected from \(String(describing: last)), expected \(step)")
            }
        case "clock":
            // Let the client reach its next sleep first, so the advance is deterministic.
            try await Task.sleep(nanoseconds: 40_000_000)
            clock.advance(step["advance_s"]?.doubleValue ?? 0)
        case "expect": try await expect(step)
        default: throw VectorFailure("unknown step")
        }
    }

    private func arguments(_ step: JSONValue) -> [String: JSONValue] { step["args"]?.objectValue ?? [:] }

    private func setAnswer(_ answer: RequestAnswer) { lastAnswer = answer }
    private func setPairing(_ status: PairingStatus) { pairingStatus = status }

    private func act(_ step: JSONValue) {
        let session = self.session
        let pier = step["pier"]?.stringValue ?? "p1"
        let operation = step["operation"]?.stringValue ?? ""
        let args = arguments(step)
        let family = step["family"]?.stringValue ?? ""
        let pairing = self.pairing
        let api = self.api
        let pending: ConfirmationRequest? = if case .confirm(let question)? = lastAnswer { question } else { nil }

        switch step["action"]?.stringValue {
        case "pair_start": spawn { await self.setPairing(await pairing.start()) }
        case "pair_poll": spawn { await self.setPairing(await pairing.poll()) }
        case "handshake": spawn { _ = try? await api.handshake() }
        case "stream_start": spawn { await session.startLink(prepare: false) }
        case "invoke": spawn { await self.setAnswer(await session.request(pier: pier, operation: operation, args: args)) }
        case "emergency": spawn { await self.setAnswer(await session.emergency(pier: pier, operation: operation)) }
        case "confirm":
            if let pending { spawn { await self.setAnswer(await session.confirm(pending)) } }
        case "decline": lastAnswer = nil
        case "hold_press": spawn { await session.startHold(pier: pier, family: family, args: args) }
        case "hold_release": spawn { await session.stopHold() }
        case "background": spawn { await session.enteredBackground() }
        default: break
        }
    }

    private func spawn(_ body: @escaping @Sendable () async -> Void) {
        tasks.append(Task { await body() })
    }

    private func expectSend(_ step: JSONValue) async throws {
        var seen: VectorTransport.Seen?
        _ = await eventually {
            seen = await self.transport.claim()
            return seen != nil
        }
        guard let seen else { throw VectorFailure("expected a request: \(step)") }
        if let method = step["method"]?.stringValue, method != seen.method { throw VectorFailure("method \(seen.method)") }
        if let path = step["path"]?.stringValue, path != seen.path { throw VectorFailure("path \(seen.path), expected \(path)") }
        if let auth = step["auth"]?.boolValue, auth != (seen.headers[.authorization] != nil) {
            throw VectorFailure("auth header present: \(!auth), expected \(auth)")
        }
        #expect(seen.headers[.userAgent]?.hasPrefix("GalileoMobileConnect/") == true)  // MAPP-CTL-080
        for (name, value) in step["headers"]?.objectValue ?? [:] {
            let got = seen.headers[HTTPField.Name(name)!]
            if got != value.stringValue { throw VectorFailure("header \(name) = \(got ?? "nil"), expected \(value)") }
        }
        if let body = step["body"], !Self.contains(seen.body ?? .null, body) {
            throw VectorFailure("body \(String(describing: seen.body)), expected to contain \(body)")
        }
    }

    /// `expected` is a subset of `actual`.
    static func contains(_ actual: JSONValue, _ expected: JSONValue) -> Bool {
        if case .object(let wanted) = expected {
            if wanted.isEmpty { return actual.objectValue != nil }
            guard let have = actual.objectValue else { return false }
            return wanted.allSatisfy { key, value in have[key].map { contains($0, value) } ?? false }
        }
        return actual == expected
    }

    private func expect(_ step: JSONValue) async throws {
        var last = ""
        let met = await eventually {
            let snapshot = await self.session.current
            last = await self.mismatch(step, snapshot: snapshot) ?? ""
            return last.isEmpty
        }
        if !met { throw VectorFailure(last) }
    }

    private func mismatch(_ step: JSONValue, snapshot: SessionSnapshot) async -> String? {
        if let wanted = step["outcome"]?.stringValue, !holds(outcome: wanted, snapshot: snapshot) {
            return "outcome is not \(wanted); answer \(String(describing: lastAnswer)) pairing \(pairingStatus) phase \(snapshot.live.phase) stop \(String(describing: snapshot.stop))"
        }
        for (key, wanted) in step["state"]?.objectValue ?? [:] {
            let actual = await observe(key, snapshot: snapshot)
            if !Self.contains(actual, wanted) { return "\(key) is \(actual), expected \(wanted)" }
        }
        return nil
    }

    private func holds(outcome wanted: String, snapshot: SessionSnapshot) -> Bool {
        let lastRecord = snapshot.ledger.records.last
        switch wanted {
        case "Waiting": if case .waiting = pairingStatus { return true }; return false
        case "Paired": if case .paired = pairingStatus { return true }; return false
        case "Refused": return pairingStatus == .refused
        case "TimedOut": return pairingStatus == .timedOut
        case "Failed": if case .failed = pairingStatus { return true }; return false
        case "Loading": return snapshot.live.phase == .loading
        case "Live": return snapshot.live.phase == .live && snapshot.connection == .connected
        case "Resyncing": return snapshot.live.phase == .resyncing
        case "Pending": return lastRecord?.state == .pending
        case "OutcomeUnknown": return lastRecord?.state == .outcomeUnknown
        case "Done": if case .done = lastRecord?.state { return true }; return false
        case "ConfirmationRequired": if case .confirm = lastAnswer { return true }; return false
        case "Invalid": if case .invalid = lastAnswer { return true }; return false
        case "NotAllowed": if case .notAllowed = lastAnswer { return true }; return false
        case "Unpaired": if case .unpaired = snapshot.stop { return true }; return false
        case "IdentityChanged": return snapshot.stop == .identityChanged
        default: return false
        }
    }

    private func observe(_ key: String, snapshot: SessionSnapshot) async -> JSONValue {
        switch key {
        case "code":
            if case .waiting(let code, _, _) = pairingStatus { return .string(code) }
            return .null
        case "token_stored": return .bool(!store.all().isEmpty)
        case "operations_offered":
            if case .paired = pairingStatus { return true }
            return false
        case "pinned_fingerprint": return store.all().first.map { .string($0.fingerprint) } ?? .null
        case "requests_sent": return .number(Double(await transport.total))
        case "requests_sent_with_key":
            return .object((await transport.keys).mapValues { .number(Double($0)) })
        case "connection":
            switch snapshot.connection {
            case .connecting: return "Connecting"
            case .connected: return "Connected"
            case .reconnecting: return "Reconnecting"
            case .unreachable: return "Unreachable"
            }
        case "confirmation.consequence":
            if case .confirm(let question)? = lastAnswer { return question.consequence.map(JSONValue.string) ?? .null }
            return .null
        case "confirmation.token":
            if case .confirm(let question)? = lastAnswer { return question.token.map(JSONValue.string) ?? .null }
            return .null
        case "invalid.path":
            if case .invalid(let path, _)? = lastAnswer { return .string(path) }
            return .null
        case "hold": return .string(Self.describe(snapshot.hold))
        default: break
        }
        if key.hasPrefix("operation.") {
            let id = String(key.dropFirst("operation.".count))
            return snapshot.ledger.record(id).map { .string(Self.describe($0.state)) } ?? .null
        }
        // <pier>.<state key>
        let parts = key.split(separator: ".", maxSplits: 1).map(String.init)
        guard parts.count == 2, let view = snapshot.value(parts[1], pier: parts[0], now: clock.now()) else { return .null }
        return ["value": view.value, "stale": .bool(view.stale)]
    }

    static func describe(_ state: HoldSession.State) -> String {
        switch state {
        case .idle: "Idle"
        case .starting: "Starting"
        case .holding: "Holding"
        case .stopped(.released): "Stopped: released"
        case .stopped(.background): "Stopped: background"
        case .stopped(.connectionLost): "Stopped: connection lost"
        case .stopped(.leaseLost): "Stopped: lease lost"
        case .stopped(.beatFailed): "Stopped: beat failed"
        case .stopped(.refused): "Stopped: refused"
        }
    }

    static func describe(_ state: OperationRecord.State) -> String {
        switch state {
        case .pending: "Pending"
        case .running: "Running"
        case .done: "Done"
        case .refused(let why): "Refused: \(why)"
        case .invalid(_, let why): "Invalid: \(why)"
        case .unavailable(let why): "Unavailable: \(why)"
        case .busy(let why): "Busy: \(why)"
        case .unreachable(let why): "Unreachable: \(why)"
        case .outcomeUnknown: "OutcomeUnknown"
        }
    }
}

@Suite("Shared vectors (MAPP-PLT-040)")
struct VectorTests {
    static var files: [URL] {
        ((try? FileManager.default.contentsOfDirectory(at: Paths.vectors, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    @Test("Vector replays", arguments: VectorTests.files)
    func replay(file: URL) async throws {
        let vector = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: file))
        try await VectorRunner(vector: vector).run()
    }

    @Test("There are vectors to replay")
    func vectorsExist() { #expect(Self.files.count >= 10) }
}
