import Foundation
import Testing

@testable import GalileoCore

/// The real URLSession, TLS, pinning and WebSocket code against a server speaking the real wire.
/// Run by `scripts/check_ios.sh`, which starts `tools/mock-master/mock_master.py` and sets these variables;
/// skipped otherwise, since a unit-test run has no server.
@Suite("Client over real TLS (tools/mock-master)", .enabled(if: LiveMaster.address != nil))
struct LiveMasterTests {
    @Test("MAPP-CON-030, MAPP-CON-050, MAPP-CON-070: pair, pin, connect, operate, reconnect; a wrong pin is refused")
    func endToEnd() async throws {
        let address = try #require(LiveMaster.address)
        let pin = try #require(LiveMaster.fingerprint)
        let store = InMemorySecureStore()

        // Pairing: any certificate is accepted for this one exchange, and it must match the body.
        let pairingLink = MasterConnection.live(address: address, policy: .pairing, identity: testIdentity, token: nil)
        let delegate = try #require(pairingLink.delegate)
        let flow = PairingFlow(
            api: pairingLink.api, store: store, address: address, masterName: "Mock", pollInterval: 0.05,
            presentedFingerprint: { delegate.presentedFingerprint })
        guard case .waiting(_, _, let fingerprint) = await flow.start() else {
            Issue.record("pairing did not start: \(await flow.status)")
            return
        }
        #expect(fingerprint == pin)
        #expect(delegate.presentedFingerprint == pin)
        guard case .paired(let credentials) = await flow.waitForAnswer() else {
            Issue.record("pairing did not finish: \(await flow.status)")
            return
        }
        #expect(credentials.fingerprint == pin)

        // Pinned: the state arrives over the WebSocket, and operations work.
        let link = MasterConnection.live(
            address: address, policy: .pinned(credentials.fingerprint), identity: testIdentity, token: credentials.token)
        var backoff = StreamLink.Backoff()
        backoff.initial = 0.1
        let session = ObservatorySession(connection: link, backoff: backoff)
        await session.start()
        let live = await eventually(timeout: 8) {
            let snapshot = await session.current
            return snapshot.live.phase == .live && snapshot.isConnected
        }
        let snapshot = await session.current
        #expect(live, "state: \(snapshot.connection) \(String(describing: snapshot.stop)) \(String(describing: snapshot.notice))")
        #expect(await session.current.value("mount.tracking", pier: "p1", now: Date())?.value == true)
        #expect(await session.takeLease(pier: "p1") == nil)
        guard case .sent(let record) = await session.request(pier: "p1", operation: "filter.select", args: ["name": "Ha"]),
            case .done = record.state
        else {
            Issue.record("the operation did not run")
            return
        }
        #expect(await eventually { await session.current.value("filter.current", pier: "p1", now: Date())?.value == "Ha" })
        let preview = try await session.preview(pier: "p1", width: 800, stretch: Stretch())
        #expect((preview?.count ?? 0) > 1000)
        await session.stop()

        // A pin that doesn't match: refused, reported as such, and no fall-back.
        let wrong = MasterConnection.live(
            address: address, policy: .pinned(String(repeating: "0", count: 64)), identity: testIdentity, token: credentials.token)
        let refused = ObservatorySession(connection: wrong, backoff: backoff)
        await refused.start()
        let stopped = await eventually(timeout: 8) { await refused.current.stop == .identityChanged }
        let refusedStop = await refused.current.stop
        #expect(stopped, "stop: \(String(describing: refusedStop))")
        await refused.stop()

        // A bad token: unpaired.
        let stale = MasterConnection.live(
            address: address, policy: .pinned(credentials.fingerprint), identity: testIdentity, token: "not-the-token")
        let unpaired = ObservatorySession(connection: stale, backoff: backoff)
        await unpaired.start()
        let ended = await eventually(timeout: 8) {
            if case .unpaired = await unpaired.current.stop { return true }
            return false
        }
        #expect(ended)
        await unpaired.stop()
    }
}

/// The routes that exist on a real Master today, against Galileo's own `LinkServer`
/// (`tools/mock-master/real_link_server.py`), rather than the mock's idea of them.
@Suite("Client against the real Connect Link server", .enabled(if: LiveMaster.realAddress != nil))
struct RealLinkServerTests {
    @Test("MAPP-CON-030, MAPP-CON-050, MAPP-CON-080, MAPP-CTL-010: pair, pin, handshake, whoami and stream on real code")
    func realServer() async throws {
        let address = try #require(LiveMaster.realAddress)
        let store = InMemorySecureStore()
        let pairing = MasterConnection.live(address: address, policy: .pairing, identity: testIdentity, token: nil)
        let delegate = try #require(pairing.delegate)
        let flow = PairingFlow(
            api: pairing.api, store: store, address: address, masterName: "Real", pollInterval: 0.1,
            presentedFingerprint: { delegate.presentedFingerprint })
        guard case .waiting(let code, _, let fingerprint) = await flow.start() else {
            Issue.record("pairing did not start: \(await flow.status)")
            return
        }
        #expect(code.count == 6)
        #expect(fingerprint.count == 64)
        #expect(delegate.presentedFingerprint == fingerprint, "the pin must equal the certificate actually shown")
        guard case .paired(let credentials) = await flow.waitForAnswer() else {
            Issue.record("approval failed: \(await flow.status)")
            return
        }

        let link = MasterConnection.live(
            address: address, policy: .pinned(credentials.fingerprint), identity: testIdentity, token: credentials.token)
        let outcome = try await link.api.handshake()
        #expect(outcome.masterProtocol == LinkProtocol.version)
        #expect(outcome.verdict != .refused, "\(outcome.message)")
        let me = try await link.api.whoAmI()
        #expect(me.permissions == ["control", "view"])
        #expect(!Role.hasConfigurationGrant(me.permissions))
        #expect(Role.from(permissions: me.permissions, allowed: outcome.allowed) == .operator)

        // The real stream's first frame, through the real WebSocket client and reducer.
        let frames = try await link.stream.connect(epoch: nil, last: nil)
        var iterator = frames.makeAsyncIterator()
        let first = try #require(try await iterator.next())
        guard case .hello(let epoch, _) = try #require(StreamMessage(data: first)) else {
            Issue.record("expected hello")
            return
        }
        #expect(!epoch.isEmpty)

        // The plugin doesn't exist yet: its routes must be a visible notice, not a silent retry loop.
        var backoff = StreamLink.Backoff()
        backoff.initial = 5
        let session = ObservatorySession(connection: link, backoff: backoff)
        await session.start()
        #expect(await eventually(timeout: 8) { await session.current.notice != nil })
        await session.stop()

        // A wrong pin against a real TLS listener.
        let wrong = MasterConnection.live(
            address: address, policy: .pinned(String(repeating: "0", count: 64)), identity: testIdentity, token: credentials.token)
        await #expect(throws: LinkError.identityChanged) { _ = try await wrong.api.whoAmI() }

        // A token the real server doesn't know.
        let stale = MasterConnection.live(
            address: address, policy: .pinned(credentials.fingerprint), identity: testIdentity, token: "nope")
        await #expect(throws: LinkError.unpaired) { _ = try await stale.api.whoAmI() }
    }
}

enum LiveMaster {
    private static let environment = ProcessInfo.processInfo.environment

    static let address: MasterAddress? = {
        guard let text = environment["GALILEO_MOCK_ADDRESS"] else { return nil }
        return try? MasterAddress.parse(text).get()
    }()
    static let fingerprint = environment["GALILEO_MOCK_FINGERPRINT"]
    static let realAddress: MasterAddress? = {
        guard let text = environment["GALILEO_REAL_ADDRESS"] else { return nil }
        return try? MasterAddress.parse(text).get()
    }()
}
