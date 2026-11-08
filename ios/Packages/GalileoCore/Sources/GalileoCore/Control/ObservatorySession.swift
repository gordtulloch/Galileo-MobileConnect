import Foundation

/// Why a session ended and can't simply be retried.
public enum SessionStop: Sendable, Equatable {
    case unpaired(String)
    case identityChanged
    /// A refused handshake. `updateApp` says which side is behind, when that is known (`MAPP-PLT-090`).
    case refused(message: String, updateApp: Bool?)
    /// The Master granted a permission this app must never hold (`MAPP-CFG-030`).
    case configurationGrant
}

/// Everything a screen may show, as one immutable value.
public struct SessionSnapshot: Sendable, Equatable {
    public var connection: ConnectionState = .connecting
    public var stop: SessionStop?
    public var role: Role?
    public var peerID: String?
    public var galileoVersion: String?
    public var capabilities: Capabilities?
    public var piers: [PierInfo] = []
    public var catalogues: [String: [OperationInfo]] = [:]
    public var live = LiveState()
    public var ledger = OperationLedger()
    public var hold: HoldSession.State = .idle
    /// A problem worth a line on screen, for example a failed start-up step.
    public var notice: String?

    public init() {}

    public var isConnected: Bool { connection == .connected }

    public func operation(_ name: String, pier: String) -> OperationInfo? {
        catalogues[pier]?.first { $0.name == name }
    }

    public enum Lease: Sendable, Equatable {
        case free, mine
        case other(String)
    }

    public func lease(pier: String) -> Lease {
        guard let holder = live.piers[pier]?.leaseHolder, !holder.isEmpty else { return .free }
        return holder == peerID ? .mine : .other(live.piers[pier]?.leaseHolderName ?? holder)
    }

    /// Access as last stated by the stream, falling back on the Pier list.
    public func access(pier: String) -> PierInfo.Access {
        if let text = live.piers[pier]?.access, let access = PierInfo.Access(rawValue: text) { return access }
        return piers.first { $0.id == pier }?.access ?? .none
    }

    public func unavailableReason(pier: String) -> String? {
        if let reachable = live.piers[pier]?.reachable, !reachable {
            return live.piers[pier]?.reason ?? "This Pier can't be reached."
        }
        if let info = piers.first(where: { $0.id == pier }), !info.reachable {
            return info.reason ?? "This Pier can't be reached."
        }
        return nil
    }

    public func value(_ key: String, pier: String, now: Date) -> ValueView? {
        live.view(pier: pier, key: key, now: now, connected: isConnected)
    }
}

/// One connection to one Master: `S-LINK`, `S-STATE` and `S-CTL` together behind a single surface for the UI.
/// There is no function here that disables the safety monitor, clears an unsafe state, resumes after a safety
/// stop, overrides a limit or changes configuration (`MAPP-SAF-040`, `MAPP-CFG-010`).
public actor ObservatorySession {
    private let connection: MasterConnection
    private let clock: any LinkClock
    private let makeID: @Sendable () -> String
    private let backoff: StreamLink.Backoff
    private let onUnpaired: @Sendable () -> Void

    private var link: StreamLink?
    private var hold: HoldSession?
    private var snapshot = SessionSnapshot()
    private var subscribers: [UUID: AsyncStream<SessionSnapshot>.Continuation] = [:]
    private var longRunning: Set<String> = []

    public init(
        connection: MasterConnection, clock: any LinkClock = SystemClock(),
        backoff: StreamLink.Backoff = StreamLink.Backoff(),
        makeID: @escaping @Sendable () -> String = { UUID().uuidString },
        onUnpaired: @escaping @Sendable () -> Void = {}
    ) {
        self.connection = connection
        self.clock = clock
        self.backoff = backoff
        self.makeID = makeID
        self.onUnpaired = onUnpaired
    }

    // MARK: Observation

    public var current: SessionSnapshot { snapshot }

    public func updates() -> AsyncStream<SessionSnapshot> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = UUID()
            subscribers[id] = continuation
            continuation.yield(snapshot)
            continuation.onTermination = { [weak self] _ in
                Task { await self?.unsubscribe(id) }
            }
        }
    }

    private func unsubscribe(_ id: UUID) { subscribers.removeValue(forKey: id) }

    private func publish() {
        for continuation in subscribers.values { continuation.yield(snapshot) }
    }

    // MARK: Lifecycle

    private var retrying: Task<Void, Never>?

    public func start() {
        startLink(prepare: true)
    }

    func startLink(prepare: Bool) {
        guard link == nil else { return }
        let new = StreamLink(
            transport: connection.stream, clock: clock, backoff: backoff,
            prepare: { [weak self] in if prepare { try await self?.prepare() } },
            onStartStop: { [weak self] refusal in await self?.handleStartStop(refusal) },
            onEvent: { [weak self] event in await self?.handle(event) })
        link = new
        Task { await new.start() }
    }

    public func stop() async {
        retrying?.cancel()
        await hold?.stop(.released)
        await link?.stop()
        link = nil
        for continuation in subscribers.values { continuation.finish() }
        subscribers = [:]
    }

    /// Scene left the active phase: stop held motion at once (`MAPP-DEV-030`).
    public func enteredBackground() async {
        await hold?.stop(.background)
    }

    /// Scene is active again: reconnect now rather than waiting out the back-off.
    public func enteredForeground() async {
        await link?.reconnectNow()
    }

    /// Version handshake, identity, and what the Master offers, on every (re)connect (`S-LINK`).
    private func prepare() async throws {
        do {
            try await loadFromMaster()
        } catch LinkError.unexpected(let message) {
            // For example a Master without the MobileConnect plugin. Retried, but not in silence.
            snapshot.notice = message
            publish()
            throw LinkError.unexpected(message)
        }
    }

    private func loadFromMaster() async throws {
        let outcome = try await connection.api.handshake()
        if outcome.verdict == .refused {
            let updateApp: Bool? = outcome.masterProtocol == LinkProtocol.version ? nil : outcome.masterProtocol > LinkProtocol.version
            throw StartStop.stop(.refused(message: outcome.message, updateApp: updateApp))
        }
        let identity = try await connection.api.whoAmI()
        if Role.hasConfigurationGrant(identity.permissions) { throw StartStop.stop(.configurationGrant) }
        let role = Role.from(permissions: identity.permissions, allowed: outcome.allowed)
        let (piers, piersReason) = try await connection.api.piers()
        var catalogues = snapshot.catalogues
        for pier in piers where catalogues[pier.id] == nil {
            catalogues[pier.id] = try await connection.api.catalogue(pier: pier.id)
        }
        longRunning = Set(catalogues.values.flatMap { $0 }.filter(\.longRunning).map(\.name))
        let capabilities = try await connection.api.capabilities()
        let alerts = try await connection.api.alerts()

        snapshot.role = role
        snapshot.peerID = identity.peerID
        snapshot.galileoVersion = outcome.galileoVersion
        snapshot.piers = piers
        snapshot.catalogues = catalogues
        snapshot.capabilities = capabilities
        if snapshot.live.alerts.isEmpty { snapshot.live.alerts = alerts }
        snapshot.notice = piersReason
        publish()
        if piersReason != nil { retryUntilPiersAppear() }
    }

    /// The Master was up but Galileo's control surface was not (Galileo is still starting, or was restarted while the
    /// phone stayed connected). The stream does not drop for that, so nothing would reconnect and ask again: the Piers
    /// and the red notice would stay as they were. Ask again until there are Piers, then ask for the live state.
    private func retryUntilPiersAppear() {
        guard retrying == nil else { return }
        retrying = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard let self, !Task.isCancelled else { return }
                if await self.retryPiersOnce() { return }
            }
        }
    }

    /// True when there is nothing more to retry: the Piers are there, or the session is over.
    private func retryPiersOnce() async -> Bool {
        guard link != nil else { retrying = nil; return true }
        do {
            try await loadFromMaster()
            if snapshot.notice == nil {
                try? await connection.api.requestSnapshot()
                retrying = nil
                return true
            }
        } catch {
            if (error as? LinkError) == .unpaired || error is StartStop { retrying = nil; return true }
        }
        return false
    }

    enum StartStop: Error { case stop(SessionStop) }

    private func handleStartStop(_ refusal: StartStop) {
        if case .stop(let stop) = refusal {
            finish(stop)
            publish()
        }
    }

    private func handle(_ event: LinkEvent) async {
        switch event {
        case .connection(let state):
            snapshot.connection = state
        case .message(let message):
            let wasMine = snapshot.live.piers.filter { snapshot.lease(pier: $0.key) == .mine }.keys
            snapshot.live = LiveState.reduce(snapshot.live, message)
            askForSnapshotIfNeeded(after: message)
            if case .frame(_, .operation(let operation)) = message { snapshot.ledger.apply(operation) }
            if wasMine.contains(where: { snapshot.lease(pier: $0) != .mine }) {
                await hold?.stop(.leaseLost)
            }
        case .dropped:
            snapshot.ledger.connectionLost()
            await hold?.stop(.connectionLost)
        case .stopped(let error):
            finish(error == .identityChanged ? .identityChanged : .unpaired(error.message))
        }
        publish()
    }

    /// `hello` with no state yet, or `resync`: the snapshot comes on the stream, but only when asked for.
    private func askForSnapshotIfNeeded(after message: StreamMessage) {
        switch message {
        case .hello where snapshot.live.phase == .live: return
        case .hello, .resync: break
        case .frame: return
        }
        let api = connection.api
        Task { [weak self] in
            do {
                try await api.requestSnapshot()
            } catch LinkError.unpaired {
                await self?.handle(.stopped(.unpaired))
            } catch {
                // The stream going with it is what happens next, and reconnecting asks again.
            }
        }
    }

    private func finish(_ stop: SessionStop) {
        snapshot.stop = stop
        snapshot.ledger.connectionLost()
        if case .unpaired = stop { onUnpaired() }
        Task { await link?.stop() }
    }

    // MARK: Operations (S-CTL)

    /// Asks for an operation. Answers with a question for the user, or with the record of what was sent.
    /// Declining a confirmation is simply not calling `confirm`: nothing is sent.
    public func request(pier: String, operation name: String, args: [String: JSONValue] = [:]) async -> RequestAnswer {
        let info = snapshot.operation(name, pier: pier)
        if let refusal = refusal(for: name, info: info, pier: pier) { return .notAllowed(refusal) }
        if let fault = info?.validate(args) { return .invalid(path: fault.path, reason: fault.reason) }
        // A `confirm` operation gets its wording and token from the Master, so that goes first.
        if ControlPolicy.alwaysConfirm.contains(name), info?.operationClass != .confirm {
            return .confirm(makeConfirmation(pier: pier, name: name, args: args, info: info, consequence: nil, token: nil))
        }
        return await send(pier: pier, name: name, args: args, token: nil, info: info)
    }

    /// The user accepted a confirmation.
    public func confirm(_ request: ConfirmationRequest) async -> RequestAnswer {
        let info = snapshot.operation(request.operation, pier: request.pier)
        if let refusal = refusal(for: request.operation, info: info, pier: request.pier) { return .notAllowed(refusal) }
        return await send(pier: request.pier, name: request.operation, args: request.args, token: request.token, info: info)
    }

    /// The emergency actions: no lease, no second round trip, confirmed by the deliberate gesture
    /// (`MAPP-EMG-010`, `MAPP-EMG-020`). Viewers are refused here and by the Master.
    public func emergency(pier: String, operation name: String) async -> RequestAnswer {
        guard snapshot.role == .operator else { return .notAllowed("Emergency actions need an Operator.") }
        return await send(pier: pier, name: name, args: [:], token: nil, info: snapshot.operation(name, pier: pier), emergency: true)
    }

    private func refusal(for name: String, info: OperationInfo?, pier: String) -> String? {
        if snapshot.role != .operator, info?.operationClass != .read { return "This device is a Viewer: it can watch but not operate." }
        if let info, !info.available { return info.unavailableBecause ?? "The Master says this isn't available now." }
        return nil
    }

    private func makeConfirmation(
        pier: String, name: String, args: [String: JSONValue], info: OperationInfo?, consequence: String?, token: String?
    ) -> ConfirmationRequest {
        ConfirmationRequest(
            pier: pier, operation: name, args: args, title: ControlPolicy.title(for: info, name: name),
            details: ControlPolicy.details(args, operation: info), consequence: consequence, token: token)
    }

    private func send(
        pier: String, name: String, args: [String: JSONValue], token: String?, info: OperationInfo?, emergency: Bool = false
    ) async -> RequestAnswer {
        let id = makeID()
        snapshot.ledger.sent(id: id, pier: pier, operation: name, at: clock.now())
        publish()
        let result = await connection.api.invoke(
            pier: pier, name: name, args: args, idempotencyKey: id, confirmToken: token, emergencyConfirmed: emergency)
        if case .refused(_, "unpaired", _, _) = result {
            finish(.unpaired(LinkError.unpaired.message))
        }
        if case .confirmationRequired(let newToken, let reason) = result {
            snapshot.ledger.forget(id)
            publish()
            return .confirm(makeConfirmation(pier: pier, name: name, args: args, info: info, consequence: reason, token: newToken))
        }
        snapshot.ledger.answered(id, result, longRunning: longRunning.contains(name))
        publish()
        if let record = snapshot.ledger.record(id) { return .sent(record) }
        return .notAllowed("The operation's record was lost.")
    }

    // MARK: Lease (S-CTL)

    public func takeLease(pier: String, takeOverToken: String? = nil) async -> LeaseRefusal? {
        switch await connection.api.acquireLease(pier: pier, takeOverToken: takeOverToken) {
        case .success(let answer):
            if answer.heldByMe, let peer = snapshot.peerID {
                // The Master's own answer: the stream's `lease` event says the same thing.
                snapshot.live.piers[pier, default: PierState()].leaseHolder = peer
                publish()
            }
            return nil
        case .failure(let refusal):
            return refusal
        }
    }

    public func releaseLease(pier: String) async {
        await hold?.stop(.leaseLost)
        if await connection.api.releaseLease(pier: pier) {
            snapshot.live.piers[pier]?.leaseHolder = nil
            publish()
        }
    }

    // MARK: Held motion

    /// Press: begins a hold if the catalogue offers one for `family` (`focus`, `mount`).
    public func startHold(pier: String, family: String, args: [String: JSONValue]) async {
        guard snapshot.role == .operator, snapshot.operation("\(family).hold_start", pier: pier) != nil else { return }
        await hold?.stop(.released)
        let api = connection.api
        let makeID = self.makeID
        let session = HoldSession(
            family: family, clock: clock,
            invoke: { operation, arguments in
                await api.invoke(pier: pier, name: operation, args: arguments, idempotencyKey: makeID())
            },
            onChange: { [weak self] state in await self?.holdChanged(state) })
        hold = session
        await session.start(args: args)
    }

    /// Release.
    public func stopHold() async { await hold?.stop(.released) }

    private func holdChanged(_ state: HoldSession.State) {
        snapshot.hold = state
        publish()
    }

    // MARK: Images

    public func preview(pier: String, width: Int, quality: Int = 80, stretch: Int, stack: Bool = false, guide: Bool = false, solve: Bool = false) async throws -> MasterAPI.PreviewImage? {
        try await connection.api.preview(pier: pier, width: width, quality: quality, stretch: stretch, stack: stack, guide: guide, solve: solve)
    }

    public func tile(pier: String, level: Int, x: Int, y: Int, stretch: Int, stack: Bool = false, solve: Bool = false) async throws -> Data? {
        try await connection.api.tile(pier: pier, level: level, x: x, y: y, stretch: stretch, stack: stack, solve: solve)
    }

    // MARK: Sky (S-SKY)

    /// The sky's own connection: its requests are read-only and shouldn't sit in the operations ledger.
    public nonisolated var api: MasterAPI { connection.api }

    /// One read-class operation, for something a screen wants to know. Not in the ledger: nothing is being done.
    public func read(pier: String, operation: String, args: [String: JSONValue] = [:]) async -> JSONValue? {
        await connection.api.read(pier: pier, name: operation, args: args, key: makeID())
    }

    /// The go-to question (`MAPP-SKY-040`): the target, its altitude now and the Master's horizon check. Nothing is
    /// sent unless the user accepts, and the Master still enforces its limits when it is.
    public func goToQuestion(pier: String, name: String, raDeg: Double, decDeg: Double) async -> RequestAnswer {
        let operation = "mount.slew"
        let info = snapshot.operation(operation, pier: pier)
        if let refusal = refusal(for: operation, info: info, pier: pier) { return .notAllowed(refusal) }
        let args: [String: JSONValue] = ["ra_deg": .number(raDeg), "dec_deg": .number(decDeg)]
        if let fault = info?.validate(args) { return .invalid(path: fault.path, reason: fault.reason) }
        let check = await connection.api.checkPosition(pier: pier, raDeg: raDeg, decDeg: decDeg)
        var details = [String(format: "RA %.3f°, Dec %.3f°", raDeg, decDeg)]
        var consequence: String
        if let check {
            details.append(String(format: "Altitude now: %.1f°", check.altitude))
            consequence = check.aboveHorizon ? "Above this Observatory's horizon." : check.reason ?? "Below this Observatory's horizon."
        } else {
            consequence = "The Master couldn't check this position against the horizon. It still enforces its limits."
        }
        consequence += " The mount will slew now."
        return .confirm(
            ConfirmationRequest(
                pier: pier, operation: operation, args: args, title: "Slew to \(name)", details: details,
                consequence: consequence, token: nil))
    }

    // MARK: Test seams

    func installForTesting(role: Role, peerID: String, catalogues: [String: [OperationInfo]]) {
        snapshot.role = role
        snapshot.peerID = peerID
        snapshot.catalogues = catalogues
        longRunning = Set(catalogues.values.flatMap { $0 }.filter(\.longRunning).map(\.name))
    }
}
