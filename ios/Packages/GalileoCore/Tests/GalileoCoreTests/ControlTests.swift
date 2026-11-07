import Foundation
import Testing

@testable import GalileoCore

@Suite("Catalogue and arguments (S-API)")
struct CatalogueTests {
    private let slew = OperationInfo(
        name: "mount.slew", area: "mount", operationClass: .operate, needsLease: true,
        arguments: [
            ArgumentInfo(name: "ra_deg", kind: .float, minimum: 0, maximum: 360, ),
            ArgumentInfo(name: "count", kind: .int, required: false, minimum: 1, maximum: 5),
            ArgumentInfo(name: "frame", kind: .enum, required: false, choices: ["Light", "Dark"]),
            ArgumentInfo(name: "on", kind: .bool, required: false),
        ])

    @Test func validationMatchesTheMastersRules() {
        #expect(slew.validate(["ra_deg": 10]) == nil)
        #expect(slew.validate([:])?.path == "ra_deg")
        #expect(slew.validate(["ra_deg": 400])?.reason == "must be at most 360")
        #expect(slew.validate(["ra_deg": "ten"])?.reason == "must be a number")
        #expect(slew.validate(["ra_deg": 1, "count": 2.5])?.reason == "must be a whole number")
        #expect(slew.validate(["ra_deg": 1, "frame": "Bias"])?.path == "frame")
        #expect(slew.validate(["ra_deg": 1, "on": 1])?.reason == "must be true or false")
        #expect(slew.validate(["ra_deg": 1, "other": 1])?.reason == "is not an argument of this operation")
    }

    @Test("MAPP-CFG-010, MAPP-FOC-030: configuration and a generic focuser move can't reach the app")
    func filtering() {
        #expect(CatalogueFilter.accept(name: "mount.park", className: "confirm") == .confirm)
        #expect(CatalogueFilter.accept(name: "profile.save", className: "config") == nil)
        #expect(CatalogueFilter.accept(name: "profile.save", className: "operate") == nil)
        #expect(CatalogueFilter.accept(name: "mount.configure", className: "config") == nil)
        #expect(CatalogueFilter.accept(name: "focuser.move", className: "operate") == nil)
        #expect(CatalogueFilter.accept(name: "focus.move_to", className: "operate") == .operate)
        #expect(CatalogueFilter.accept(name: "mount.future", className: "a_new_class") == nil)
    }

    @Test("Every real catalogue entry reaches the app")
    func realCatalogueSurvives() throws {
        #expect(try realCatalogue().count >= 66)
    }

    @Test("MAPP-CTL-010, MAPP-CFG-030: role comes from the grant, never from what the handshake permits")
    func roles() {
        let everythingAllowed: Set = ["config.read", "config.write", "data.push", "data.pull", "control", "view"]
        #expect(Role.from(permissions: ["control", "view"], allowed: everythingAllowed) == .operator)
        #expect(Role.from(permissions: ["view"], allowed: everythingAllowed) == .viewer)
        #expect(Role.from(permissions: ["data.pull"], allowed: everythingAllowed) == nil)
        #expect(Role.hasConfigurationGrant(["control", "config.read"]))
        #expect(!Role.hasConfigurationGrant(["control", "view"]))
    }
}

@Suite("Ledger (MAPP-CTL-050, MAPP-CTL-070)")
struct LedgerTests {
    private let now = Date(timeIntervalSince1970: 0)

    @Test func nothingIsDoneUntilTheMasterSaysSo() {
        var ledger = OperationLedger()
        ledger.sent(id: "k", pier: "p", operation: "o", at: now)
        #expect(ledger.record("k")?.state == .pending)
        ledger.answered("k", .ok(value: 1, correlationID: nil), longRunning: false)
        #expect(ledger.record("k")?.state == .done(1))
    }

    @Test func aDropMakesOpenRecordsUnknownAndNothingElse() {
        var ledger = OperationLedger()
        ledger.sent(id: "a", pier: "p", operation: "o", at: now)
        ledger.sent(id: "b", pier: "p", operation: "o", at: now)
        ledger.answered("b", .ok(value: nil, correlationID: nil), longRunning: false)
        ledger.connectionLost()
        #expect(ledger.record("a")?.state == .outcomeUnknown)
        #expect(ledger.record("b")?.state == .done(nil))
        #expect(ledger.sentCounts["a"] == 1)
    }

    @Test func theMastersFinishResolvesAnUnknownOutcome() {
        var ledger = OperationLedger()
        ledger.sent(id: "a", pier: "p", operation: "o", at: now)
        ledger.connectionLost()
        ledger.apply(OperationEvent(phase: "op.finished", correlationID: "c", requestID: "a", outcome: "ok"))
        #expect(ledger.record("a")?.state == .done(nil))
    }

    @Test func longRunningStaysOpenUntilFinished() {
        var ledger = OperationLedger()
        ledger.sent(id: "a", pier: "p", operation: "o", at: now)
        ledger.answered("a", .ok(value: nil, correlationID: "c"), longRunning: true)
        #expect(ledger.record("a")?.state == .running(progress: nil))
        ledger.apply(OperationEvent(phase: "op.progress", correlationID: "c", progress: 0.5))
        #expect(ledger.record("a")?.state == .running(progress: 0.5))
        ledger.apply(OperationEvent(phase: "op.finished", correlationID: "c", outcome: "refused", reason: "Clouds"))
        #expect(ledger.record("a")?.state == .refused("Clouds"))
    }

    @Test("op.finished can overtake the HTTP answer")
    func finishBeforeAnswer() {
        var ledger = OperationLedger()
        ledger.sent(id: "a", pier: "p", operation: "o", at: now)
        ledger.apply(OperationEvent(phase: "op.finished", correlationID: "c", requestID: "a", outcome: "ok"))
        ledger.answered("a", .ok(value: nil, correlationID: "c"), longRunning: true)
        #expect(ledger.record("a")?.state == .done(nil))
    }

    @Test func aLongRunningAnswerWithNoCorrelationIDCanNotStayOpenForever() {
        var ledger = OperationLedger()
        ledger.sent(id: "a", pier: "p", operation: "o", at: now)
        ledger.answered("a", .ok(value: nil, correlationID: nil), longRunning: true)
        #expect(ledger.record("a")?.state == .done(nil))
    }
}

extension OperationEvent {
    init(phase: String, correlationID: String, requestID: String? = nil, progress: Double? = nil, outcome: String? = nil, reason: String? = nil) {
        self.init(phase: phase, correlationID: correlationID, requestID: requestID, pier: nil, operation: nil, progress: progress, outcome: outcome, reason: reason)
    }
}

@Suite("Held motion (MAPP-DEV-030, MAPP-CTL-060)")
struct HoldTests {
    private actor Calls {
        var names: [String] = []
        func add(_ name: String) { names.append(name) }
    }

    private func make(beat: OpResult = .ok(value: true, correlationID: nil)) -> (HoldSession, Calls, TestClock) {
        let calls = Calls()
        let clock = TestClock()
        let session = HoldSession(family: "focus", clock: clock) { name, _ in
            await calls.add(name)
            if name.hasSuffix("start") { return .ok(value: ["hold_id": "h"], correlationID: nil) }
            if name.hasSuffix("beat") { return beat }
            return .ok(value: true, correlationID: nil)
        }
        return (session, calls, clock)
    }

    /// Advances one beat at a time until `beats` beats were sent.
    private func beat(_ clock: TestClock, _ calls: Calls, until beats: Int) async {
        _ = await eventually {
            clock.advance(HoldSession.beatInterval)
            return await calls.names.filter { $0.hasSuffix("beat") }.count >= beats
        }
    }

    @Test func beatsFlowUntilRelease() async {
        let (session, calls, clock) = make()
        await session.start(args: ["direction": 1])
        #expect(await session.state == .holding)
        await beat(clock, calls, until: 2)
        await session.stop(.released)
        #expect(await calls.names.last == "focus.hold_stop")
        #expect(await session.state == .stopped(.released))
    }

    @Test("A beat the Master no longer recognises ends the hold")
    func beatRefused() async {
        let (session, calls, clock) = make(beat: .ok(value: false, correlationID: nil))
        await session.start(args: [:])
        await beat(clock, calls, until: 1)
        #expect(await eventually { await session.state == .stopped(.beatFailed) })
    }

    @Test("A lost beat stops beating and sends no stop it can't deliver")
    func beatLost() async {
        let (session, calls, clock) = make(beat: .connectionLost)
        await session.start(args: [:])
        await beat(clock, calls, until: 1)
        #expect(await eventually { await session.state == .stopped(.connectionLost) })
        #expect(await !calls.names.contains("focus.hold_stop"))
    }

    @Test("Stopping twice sends one stop")
    func stopIsIdempotent() async {
        let (session, calls, _) = make()
        await session.start(args: [:])
        await session.stop(.background)
        await session.stop(.released)
        #expect(await calls.names.filter { $0 == "focus.hold_stop" }.count == 1)
        #expect(await session.state == .stopped(.background))
    }

    @Test("Released while the start is still in flight: the motion is still stopped")
    func releasedDuringStart() async {
        let clock = TestClock()
        let calls = Calls()
        let gate = AsyncStream<Void>.makeStream()
        let session = HoldSession(family: "focus", clock: clock) { name, _ in
            await calls.add(name)
            if name.hasSuffix("start") {
                for await _ in gate.stream { break }
                return .ok(value: ["hold_id": "h"], correlationID: nil)
            }
            return .ok(value: true, correlationID: nil)
        }
        let starting = Task { await session.start(args: [:]) }
        #expect(await eventually { await calls.names == ["focus.hold_start"] })
        await session.stop(.released)
        gate.continuation.yield()
        await starting.value
        #expect(await calls.names.last == "focus.hold_stop")
        #expect(await session.state == .stopped(.released))
    }

    @Test("Refused at the start")
    func refusedAtStart() async {
        let session = HoldSession(family: "focus", clock: TestClock()) { _, _ in
            .refused(reason: "Take the lease first", kind: nil, holder: nil, canTake: false)
        }
        await session.start(args: [:])
        #expect(await session.state == .stopped(.refused("Take the lease first")))
    }
}
