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
        let preview = try await session.preview(pier: "p1", width: 800, stretch: 50)
        #expect((preview?.data.count ?? 0) > 1000)
        #expect(preview?.frameWidth == 1200)
        #expect(try await session.tile(pier: "p1", level: 0, x: 0, y: 0, stretch: 50) != nil)
        #expect(try await session.tile(pier: "p1", level: 0, x: 99, y: 99, stretch: 50) == nil)

        // The standalone mock's sky: the one a real device talks to. Its encoder is Python's, decoded here.
        #expect(await link.api.skySite(pier: "p1") != .failed)
        let skyStore = SkyStore(api: link.api)
        await skyStore.setRate(perSecond: 100)
        let seen = Locked<[SkyObject]>([])
        await skyStore.look(raDeg: 83.8, decDeg: -5.4, radius: 180, level: 4) { objects in seen.withLock { $0 = objects } }
        #expect(await eventually(timeout: 8) { seen.current.count > 2000 })
        await skyStore.stop()
        #expect(seen.current.contains { $0.isDeepSky })
        #expect(await link.api.surveyTile(SkyTile(order: 0, npix: 0)) == nil, "the mock ships no sky pictures")
        let figures = try #require(await link.api.constellations())
        #expect(figures.names.contains { $0.name == "Orion" } && figures.lines.first?.count == 6)
        #expect(await link.api.identify(index: 2500)?.name == "M 42")
        let vega = try #require(await link.api.identify(index: 2500 + 6 + 7 + 7 + 5 + 5))
        #expect(vega.name == "Vega")
        #expect(await link.api.checkPosition(pier: "p1", raDeg: 83.8, decDeg: -5.4) != nil)
        #expect(await link.api.ephemeris(from: Date(), to: Date().addingTimeInterval(3600), stepSeconds: 600)?.count == 7)
        guard case .confirm(let goTo) = await session.goToQuestion(pier: "p1", name: "M 42", raDeg: 83.8, decDeg: -5.4) else {
            Issue.record("expected the go-to question")
            return
        }
        #expect(goTo.title == "Slew to M 42")
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

/// The whole path on real server code: Galileo's own Connect Link listener, pairing, Gateway and lease, and the real
/// MobileConnect plugin, with only the devices and the approving person faked (`tools/mock-master/real_link_server.py`).
@Suite("Client against the real plugin on real Connect Link", .enabled(if: LiveMaster.realAddress != nil))
struct RealPluginTests {
    @Test("MAPP-CON-030, MAPP-CON-050, MAPP-CON-080, MAPP-CTL-010, MAPP-CTL-020, MAPP-CTL-040, MAPP-EMG-020, MAPP-OVR-050: end to end")
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
        #expect(delegate.presentedFingerprint == fingerprint, "the pin must equal the certificate actually shown")
        guard case .paired(let credentials) = await flow.waitForAnswer() else {
            Issue.record("approval failed: \(await flow.status)")
            return
        }

        let link = MasterConnection.live(
            address: address, policy: .pinned(credentials.fingerprint), identity: testIdentity, token: credentials.token)
        let me = try await link.api.whoAmI()
        #expect(me.permissions == ["control", "view"])
        let handshake = try await link.api.handshake()
        #expect(handshake.verdict != .refused, "\(handshake.message)")

        // Connect, as the app does: the real catalogue, then state over the real stream.
        var backoff = StreamLink.Backoff()
        backoff.initial = 0.2
        let session = ObservatorySession(connection: link, backoff: backoff)
        await session.start()
        let live = await eventually(timeout: 10) {
            let now = await session.current
            return now.live.phase == .live && now.isConnected
        }
        let current = await session.current
        #expect(live, "state: \(current.connection) \(String(describing: current.stop)) \(String(describing: current.notice))")
        #expect(current.role == .operator)
        #expect(current.piers.map(\.name) == ["East Pier"])
        #expect(current.notice == nil)
        let pier = try #require(current.piers.first?.id)
        #expect(current.catalogues[pier]?.contains { $0.name == "mount.park" && $0.operationClass == .confirm } == true)
        #expect(current.value("mount.tracking", pier: pier, now: Date())?.value == true)
        #expect(current.value("mount.position.ra_deg", pier: pier, now: Date())?.value == 82.5)
        #expect(current.value("safety.overall", pier: pier, now: Date()) == nil, "a Pier with no monitor must not claim to be safe")
        #expect(current.operation("dome.open", pier: pier)?.available == false)

        // Without the lease: the Master's reason, and no retry.
        guard case .sent(let refused) = await session.request(pier: pier, operation: "mount.track", args: ["enabled": false]),
            case .refused = refused.state
        else {
            Issue.record("expected a refusal without the lease")
            return
        }

        // With it: the operation, and the changed state comes back on the stream by itself.
        #expect(await session.takeLease(pier: pier) == nil)
        #expect(await eventually { await session.current.lease(pier: pier) == .mine })
        guard case .sent(let done) = await session.request(pier: pier, operation: "mount.track", args: ["enabled": false]),
            case .done = done.state
        else {
            Issue.record("the operation did not run")
            return
        }
        #expect(await eventually(timeout: 6) { await session.current.value("mount.tracking", pier: pier, now: Date())?.value == false })

        // A confirm operation: the Master's wording and token, and nothing until it's accepted.
        guard case .confirm(let question) = await session.request(pier: pier, operation: "mount.park") else {
            Issue.record("expected the Master to ask")
            return
        }
        #expect(question.title == "Park the mount")
        #expect(question.token != nil)
        #expect(await session.current.value("mount.parked", pier: pier, now: Date())?.value == false)
        guard case .sent(let parked) = await session.confirm(question), case .done = parked.state else {
            Issue.record("the confirmed park did not run")
            return
        }
        #expect(await eventually(timeout: 6) { await session.current.value("mount.parked", pier: pier, now: Date())?.value == true })

        // The sky, from core's real streaming service.
        guard case .site(let site) = await link.api.skySite(pier: pier) else {
            Issue.record("expected the site")
            return
        }
        #expect(site.latitude == 49.9 && site.horizon.count == 2)
        #expect(await link.api.skySite(pier: "99") == .noLocation, "only this means go and set one on the desktop")
        let realStore = SkyStore(api: link.api)
        let seen = Locked<[SkyObject]>([])
        await realStore.look(raDeg: 83.8, decDeg: -5.4, radius: 5, level: 4) { objects in seen.withLock { $0 = objects } }
        #expect(await eventually(timeout: 6) { seen.current.count == 3 }, "core's encoding, decoded here")
        await realStore.stop()
        #expect(seen.current.contains { $0.isDeepSky && abs($0.raDeg - 83.82) < 0.001 })
        let named = try #require(await link.api.identify(index: 3))
        #expect(named.name == "M 42" && named.isDeepSky)
        #expect(await link.api.identify(index: 0)?.name == "Rigel")
        #expect(await link.api.constellations()?.names.first?.name == "Orion")
        let check = try #require(await link.api.checkPosition(pier: pier, raDeg: 83.8, decDeg: -5.4))
        #expect(check.aboveHorizon || check.reason != nil)
        let moon = await link.api.ephemeris(from: Date(), to: Date().addingTimeInterval(3600), stepSeconds: 1800)
        #expect(moon?.count == 3 && moon?.first?.bodies.first?.name == "Moon")
        #expect(current.capabilities?.skyStreamAvailable == true)
        guard case .confirm(let goTo) = await session.goToQuestion(pier: pier, name: "M 42", raDeg: 83.8, decDeg: -5.4) else {
            Issue.record("expected the go-to question")
            return
        }
        #expect(goTo.details.contains { $0.hasPrefix("Altitude now:") })

        // A held control, with real keep-alives.
        await session.startHold(pier: pier, family: "focus", args: ["direction": 1])
        #expect(await session.current.hold == .holding)
        try await Task.sleep(nanoseconds: 700_000_000)  // past the deadman, had the beats not been flowing
        #expect(await session.current.hold == .holding)
        await session.stopHold()

        // Emergency: no second round trip, and the lease doesn't matter.
        await session.releaseLease(pier: pier)
        guard case .sent(let stopped) = await session.emergency(pier: pier, operation: "system.stop_everything"),
            case .done = stopped.state
        else {
            Issue.record("the emergency stop did not run")
            return
        }

        await session.stop()

        // A wrong pin against a real TLS listener, and a token the real server doesn't know.
        let wrong = MasterConnection.live(
            address: address, policy: .pinned(String(repeating: "0", count: 64)), identity: testIdentity, token: credentials.token)
        await #expect(throws: LinkError.identityChanged) { _ = try await wrong.api.whoAmI() }
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
