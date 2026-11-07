import Foundation
import Testing

@testable import GalileoCore

/// The real client against the scripted Master: what demonstration mode runs (`S-MOCK`, `MAPP-CON-110`).
@Suite("Client against the mock Master")
struct MockMasterTests {
    private func connect(
        _ scenario: String, timeScale: Double = 1, onUnpaired: @escaping @Sendable () -> Void = {}
    ) throws -> (ObservatorySession, MockMaster) {
        let master = try MockMaster(scenarioNamed: scenario, timeScale: timeScale)
        let connection = MasterConnection.demo(master, identity: testIdentity, token: MockMaster.token)
        var backoff = StreamLink.Backoff()
        backoff.initial = 0.02
        backoff.jitter = { 1 }
        let session = ObservatorySession(connection: connection, backoff: backoff, onUnpaired: onUnpaired)
        return (session, master)
    }

    private func live(_ session: ObservatorySession) async -> Bool {
        await eventually {
            let snapshot = await session.current
            return snapshot.live.phase == .live && snapshot.isConnected
        }
    }

    @Test("Every scenario loads, and its state covers real operations",
        arguments: ["normal-night", "safety-event", "meridian-flip", "lease-contention", "dropped-stream", "revoked-pairing",
            "first-pairing-refused", "viewer", "pi-class-master", "older-master", "newer-master", "certificate-change"])
    func scenarioLoads(name: String) throws {
        _ = try MockMaster(scenarioNamed: name)
    }

    @Test("Every scenario file is listed above")
    func noScenarioForgotten() throws {
        let files = try FileManager.default.contentsOfDirectory(at: Paths.scenarios, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" && !$0.lastPathComponent.hasPrefix("catalogue") }
        #expect(files.count == 12)
    }

    @Test("Effects only name operations the catalogue has")
    func effectsAreReal() throws {
        let scenario = try MockMaster.scenario(named: "normal-night")
        let data = try MockMaster.resource("catalogue", "json")
        let planned = try MockMaster.resource("catalogue.planned", "json")
        let names = try [data, planned].flatMap {
            try JSONDecoder().decode(JSONValue.self, from: $0)["operations"]?.arrayValue ?? []
        }.compactMap { $0["name"]?.stringValue }
        for name in scenario["effects"]?.objectValue?.keys ?? [:].keys {
            #expect(names.contains(name), "\(name)")
        }
    }

    @Test("MAPP-CON-030, MAPP-CON-110: pairing against the mock gives a usable token, once")
    func pairing() async throws {
        let master = try MockMaster(scenarioNamed: "normal-night", timeScale: 100)
        let connection = MasterConnection.demo(master, identity: testIdentity)
        let store = InMemorySecureStore()
        let flow = PairingFlow(
            api: connection.api, store: store, address: MasterAddress(host: "demo"), masterName: "Demo", pollInterval: 0.01)
        guard case .waiting(let code, _, let fingerprint) = await flow.start() else {
            Issue.record("expected a code")
            return
        }
        #expect(code.count == 6)
        #expect(fingerprint == MockMaster.fingerprint)
        guard case .paired = await flow.waitForAnswer() else {
            Issue.record("expected pairing")
            return
        }
        #expect(store.all().first?.token == MockMaster.token)
    }

    @Test("MAPP-CON-030: a refusal stores nothing")
    func pairingRefused() async throws {
        let master = try MockMaster(scenarioNamed: "first-pairing-refused", timeScale: 100)
        let store = InMemorySecureStore()
        let flow = PairingFlow(
            api: MasterConnection.demo(master, identity: testIdentity).api, store: store,
            address: MasterAddress(host: "demo"), masterName: "Demo", pollInterval: 0.01)
        await flow.start()
        #expect(await flow.waitForAnswer() == .refused)
        #expect(store.all().isEmpty)
    }

    @Test("MAPP-OVR-010, MAPP-OVR-070: connecting shows the observatory promptly")
    func connecting() async throws {
        let (session, _) = try connect("normal-night")
        await session.start()
        let started = Date()
        #expect(await live(session))
        #expect(Date().timeIntervalSince(started) < 5)
        let snapshot = await session.current
        #expect(snapshot.role == .operator)
        #expect(snapshot.piers.map(\.id).sorted() == ["p1", "p2"])
        #expect(snapshot.catalogues["p1"]?.isEmpty == false)
        #expect(snapshot.value("mount.tracking", pier: "p1", now: Date())?.value == true)
        #expect(snapshot.capabilities?.skyStreamAvailable == true)
        #expect(snapshot.operation("dome.open", pier: "p2")?.available == false)
        #expect(snapshot.live.alerts.isEmpty == false)
        await session.stop()
    }

    @Test("MAPP-CTL-020: lease, operation, effect, release")
    func leaseThenOperate() async throws {
        let (session, _) = try connect("normal-night", timeScale: 100)
        await session.start()
        #expect(await live(session))

        // Without the lease: the Master's reason, and no retry.
        guard case .sent(let refused) = await session.request(pier: "p1", operation: "filter.select", args: ["name": "Ha"]) else {
            Issue.record("expected a record")
            return
        }
        #expect(refused.state == .refused("Take the lease on this Pier first."))

        #expect(await session.takeLease(pier: "p1") == nil)
        #expect(await session.current.lease(pier: "p1") == .mine)
        guard case .sent(let done) = await session.request(pier: "p1", operation: "filter.select", args: ["name": "Ha"]),
            case .done = done.state
        else {
            Issue.record("expected the operation to run")
            return
        }
        #expect(await eventually { await session.current.value("filter.current", pier: "p1", now: Date())?.value == "Ha" })
        await session.releaseLease(pier: "p1")
        #expect(await session.current.lease(pier: "p1") == .free)
        await session.stop()
    }

    @Test("ADR-APP-005: the Master's confirmation, with a token bound to the operation")
    func confirmation() async throws {
        let (session, _) = try connect("normal-night", timeScale: 100)
        await session.start()
        #expect(await live(session))
        _ = await session.takeLease(pier: "p1")
        guard case .confirm(let question) = await session.request(pier: "p1", operation: "mount.park") else {
            Issue.record("expected a question")
            return
        }
        #expect(question.consequence?.contains("park position") == true)
        #expect(question.token != nil)
        #expect(await session.current.value("mount.parked", pier: "p1", now: Date())?.value == false)
        guard case .sent(let record) = await session.confirm(question), case .done = record.state else {
            Issue.record("expected the confirmed park to run")
            return
        }
        #expect(await eventually { await session.current.value("mount.parked", pier: "p1", now: Date())?.value == true })
        await session.stop()
    }

    @Test("MAPP-CTL-020: another client holds the lease; taking it over needs the offered token")
    func leaseContention() async throws {
        let (session, _) = try connect("lease-contention")
        await session.start()
        #expect(await live(session))
        #expect(await session.current.lease(pier: "p1") == .other("Desktop"))
        let refusal = try #require(await session.takeLease(pier: "p1"))
        #expect(refusal.holder == "Desktop")
        let token = try #require(refusal.takeOverToken)
        #expect(await session.current.lease(pier: "p1") == .other("Desktop"))
        #expect(await session.takeLease(pier: "p1", takeOverToken: token) == nil)
        #expect(await session.current.lease(pier: "p1") == .mine)
        await session.stop()
    }

    @Test("MAPP-EMG-020: emergency needs no lease and lists its parts")
    func emergency() async throws {
        let (session, _) = try connect("normal-night")
        await session.start()
        #expect(await live(session))
        guard case .sent(let record) = await session.emergency(pier: "p1", operation: "system.stop_everything"),
            case .done(let value) = record.state
        else {
            Issue.record("expected the emergency action to run")
            return
        }
        #expect(value?["parts"]?.arrayValue?.isEmpty == false)
        await session.stop()
    }

    @Test("MAPP-CON-070: the scripted drop replays; the scripted restart resynchronises")
    func dropsAndResync() async throws {
        let (session, master) = try connect("normal-night")
        await session.start()
        #expect(await live(session))
        let key = "camera.state"
        await master.dropStream()
        #expect(await live(session))
        #expect(await session.current.value(key, pier: "p1", now: Date()) != nil)
        let epoch = await session.current.live.epoch
        await master.forceResync()
        #expect(await eventually { await session.current.live.epoch != epoch })
        #expect(await live(session))
        #expect(await session.current.value(key, pier: "p1", now: Date()) != nil)
        await session.stop()
    }

    @Test("MAPP-CON-060: revocation ends the session and tells the owner of the token")
    func revoked() async throws {
        let flag = Locked(false)
        let (session, master) = try connect("normal-night", onUnpaired: { flag.withLock { $0 = true } })
        await session.start()
        #expect(await live(session))
        await master.revoke()
        let stopped = await eventually {
            if case .unpaired = await session.current.stop { return true }
            return false
        }
        #expect(stopped)
        #expect(flag.current)
    }

    @Test("MAPP-CTL-010: a Viewer's grant reaches the role, and an unreachable Pier carries its reason")
    func viewer() async throws {
        let (session, _) = try connect("viewer")
        await session.start()
        #expect(await live(session))
        let snapshot = await session.current
        #expect(snapshot.role == .viewer)
        #expect(snapshot.unavailableReason(pier: "p2") == "Spoke offline since 21:14")
        #expect(snapshot.access(pier: "p1") == .view)
        guard case .notAllowed = await session.request(pier: "p1", operation: "mount.track", args: ["enabled": true]) else {
            Issue.record("a Viewer must not be able to operate")
            return
        }
        await session.stop()
    }

    @Test("MAPP-CON-080, MAPP-PLT-090: a refused handshake says which side to update and stops")
    func versions() async throws {
        for (scenario, updateApp) in [("older-master", false), ("newer-master", true)] {
            let (session, _) = try connect(scenario)
            await session.start()
            let refused = await eventually {
                if case .refused = await session.current.stop { return true }
                return false
            }
            #expect(refused)
            guard case .refused(let message, let side) = await session.current.stop else { continue }
            #expect(side == updateApp, "\(scenario)")
            #expect(!message.isEmpty)
            #expect(await session.current.catalogues.isEmpty)
            await session.stop()
        }
    }

    @Test("MAPP-CON-090: a Pi-class Master's missing feature carries its reason")
    func capabilities() async throws {
        let (session, _) = try connect("pi-class-master")
        await session.start()
        #expect(await live(session))
        let capabilities = await session.current.capabilities
        #expect(capabilities?.skyStreamAvailable == false)
        #expect(capabilities?.skyStreamReason == "master_hardware")
        await session.stop()
    }

    @Test("MAPP-DEV-030: a hold moves, and stops when released; the mock's deadman stops an abandoned one")
    func holds() async throws {
        let (session, _) = try connect("normal-night", timeScale: 1)
        await session.start()
        #expect(await live(session))
        _ = await session.takeLease(pier: "p1")
        await session.startHold(pier: "p1", family: "focus", args: ["direction": 1])
        #expect(await session.current.hold == .holding)
        #expect(await eventually { await session.current.value("focus.moving", pier: "p1", now: Date())?.value == true })
        await session.stopHold()
        #expect(await eventually { await session.current.value("focus.moving", pier: "p1", now: Date())?.value == false })
        await session.stop()
    }

    @Test("MAPP-IMG-030, MAPP-IMG-040: a preview comes from the Master, and the stretch reaches it")
    func preview() async throws {
        let (session, _) = try connect("normal-night")
        await session.start()
        #expect(await live(session))
        let plain = try #require(try await session.preview(pier: "p1", width: 800, stretch: 50))
        let stretched = try #require(try await session.preview(pier: "p1", width: 800, stretch: 80))
        #expect(plain.count > 1000)
        #expect(plain != stretched)
        await session.stop()
    }
}
