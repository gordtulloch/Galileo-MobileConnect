import GalileoCore
import Observation
import SwiftUI

/// I-BRIDGE: the main-actor face of `GalileoCore`. Screens read `snapshot` and never see the generated types.
@MainActor @Observable
final class AppModel {
    enum Route: Equatable {
        case welcome
        case pairing
        case connected
    }

    private(set) var route: Route = .welcome
    private(set) var snapshot = SessionSnapshot()
    private(set) var pairing: PairingStatus = .idle
    private(set) var pairingMaster = ""
    private(set) var paired: [MasterCredentials] = []
    private(set) var isDemo = false
    private(set) var masterName = ""
    private(set) var welcomeMessage: String?
    /// Moves once a second, so ages on screen keep counting between updates.
    private(set) var now = Date()

    var workingPier: String?
    var question: ConfirmationRequest?
    var leaseOffer: LeaseRefusal?
    var emergencyOpen = false
    var pierPickerOpen = false
    var theme: ThemeChoice {
        didSet { Self.defaults.set(theme.rawValue, forKey: "theme") }
    }

    private var session: ObservatorySession?
    private var master: MockMaster?
    private var updates: Task<Void, Never>?
    private var pairingTask: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var store: any SecureStore = KeychainSecureStore()
    private var liveCredentials: MasterCredentials?
    private let launch = LaunchOptions()

    private static let defaults = UserDefaults.standard

    init() {
        let saved = Self.defaults.string(forKey: "theme").flatMap(ThemeChoice.init(rawValue:))
        theme = launch.theme ?? saved ?? .night
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                self?.now = Date()
            }
        }
    }

    var identity: ClientIdentity {
        let device = UIDevice.current
        let isPad = device.userInterfaceIdiom == .pad
        return ClientIdentity(
            deviceName: device.name, appVersion: Self.appVersion, platform: isPad ? "ipados" : "ios",
            clientClass: isPad ? "tablet" : "phone")
    }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    // MARK: Start-up

    /// One paired Master: go straight to it. Otherwise the welcome screen lists what there is.
    func launched() {
        if let scenario = launch.demoScenario {
            startDemo(scenario: scenario)
            return
        }
        refreshPaired()
        if paired.count == 1, let only = paired.first { connect(only) }
    }

    func refreshPaired() {
        paired = (try? store.all()) ?? []
    }

    // MARK: Pairing

    func startPairing(address: MasterAddress, name: String) {
        isDemo = false
        store = KeychainSecureStore()
        let link = MasterConnection.live(address: address, policy: .pairing, identity: identity, token: nil)
        let delegate = link.delegate
        let flow = PairingFlow(
            api: link.api, store: store, address: address, masterName: name,
            presentedFingerprint: { delegate?.presentedFingerprint })
        run(flow, masterName: name)
    }

    /// Demonstration mode: the real client against the scripted Master, and nothing kept (`MAPP-CON-110`).
    func startDemo(scenario: String = "normal-night") {
        guard let mock = try? MockMaster(scenarioNamed: scenario) else {
            welcomeMessage = "The demonstration could not start."
            return
        }
        isDemo = true
        master = mock
        store = InMemorySecureStore()
        let link = MasterConnection.demo(mock, identity: identity)
        let flow = PairingFlow(api: link.api, store: store, address: MasterAddress(host: "demo"), masterName: "Demo observatory")
        run(flow, masterName: "Demo observatory")
    }

    private func run(_ flow: PairingFlow, masterName: String) {
        pairingTask?.cancel()
        pairingMaster = masterName
        pairing = .idle
        welcomeMessage = nil
        route = .pairing
        pairingTask = Task { [weak self] in
            let first = await flow.start()
            self?.pairing = first
            guard case .waiting = first else { return }
            let last = await flow.waitForAnswer()
            guard let self, !Task.isCancelled else { return }
            self.pairing = last
            if case .paired(let credentials) = last {
                self.connect(credentials)
            }
        }
    }

    func cancelPairing() {
        pairingTask?.cancel()
        pairing = .idle
        isDemo = false
        master = nil
        route = .welcome
        refreshPaired()
    }

    // MARK: Session

    func connect(_ credentials: MasterCredentials) {
        disconnect()
        liveCredentials = credentials
        masterName = credentials.name
        let link: MasterConnection
        if let master, isDemo {
            link = .demo(master, identity: identity, token: credentials.token)
        } else {
            link = .live(address: credentials.address, policy: .pinned(credentials.fingerprint), identity: identity, token: credentials.token)
        }
        let store = self.store
        let id = credentials.id
        let new = ObservatorySession(connection: link, onUnpaired: { try? store.delete(id: id) })
        session = new
        route = .connected
        snapshot = SessionSnapshot()
        updates = Task { [weak self] in
            await new.start()
            for await next in await new.updates() {
                self?.apply(next)
            }
        }
    }

    private func apply(_ next: SessionSnapshot) {
        snapshot = next
        if workingPier == nil || !next.piers.contains(where: { $0.id == workingPier }) {
            workingPier = next.piers.first(where: { $0.access == .control && $0.reachable })?.id ?? next.piers.first?.id
        }
    }

    private func disconnect() {
        stopLiveLoop()
        updates?.cancel()
        let old = session
        session = nil
        Task { await old?.stop() }
    }

    /// The token and pin are discarded. The pairing on the Master is the owner's to remove (`MAPP-CON-100`).
    func unpair(_ credentials: MasterCredentials) {
        try? store.delete(id: credentials.id)
        if liveCredentials?.id == credentials.id { backToWelcome(message: "Unpaired. Remove this device's pairing on the Master as well.") }
        refreshPaired()
    }

    func unpairCurrent() {
        if let liveCredentials { unpair(liveCredentials) }
    }

    func backToWelcome(message: String? = nil) {
        disconnect()
        let mock = master
        Task { await mock?.shutDown() }
        master = nil
        liveCredentials = nil
        isDemo = false
        snapshot = SessionSnapshot()
        workingPier = nil
        question = nil
        leaseOffer = nil
        emergencyOpen = false
        store = KeychainSecureStore()
        welcomeMessage = message
        route = .welcome
        refreshPaired()
    }

    /// `MAPP-CON-050`: the old pin is thrown away only because the user said so.
    func pairAgainAfterIdentityChange() {
        guard let liveCredentials else { return }
        try? store.delete(id: liveCredentials.id)
        let address = liveCredentials.address
        let name = liveCredentials.name
        backToWelcome()
        startPairing(address: address, name: name)
    }

    // MARK: Lifecycle (I-LIFE)

    func sceneChanged(to phase: ScenePhase) {
        if phase != .active { stopLiveLoop() }
        guard let session else { return }
        Task {
            if phase == .active {
                await session.enteredForeground()
            } else {
                await session.enteredBackground()
            }
        }
    }

    // MARK: Operations

    /// Nil when the answer needs nothing more from the screen; a message when it does.
    func perform(_ operation: String, args: [String: JSONValue] = [:]) async -> String? {
        guard let pier = workingPier, let session else { return nil }
        return handle(await session.request(pier: pier, operation: operation, args: args))
    }

    func accept(_ request: ConfirmationRequest) {
        question = nil
        guard let session else { return }
        Task { _ = handle(await session.confirm(request)) }
    }

    private func handle(_ answer: RequestAnswer) -> String? {
        switch answer {
        case .confirm(let request):
            question = request
            return nil
        case .sent:
            return nil
        case .notAllowed(let reason):
            return reason
        case .invalid(let path, let reason):
            return "\(path.replacingOccurrences(of: "_", with: " ")) \(reason)"
        }
    }

    func emergency(_ operation: String) {
        guard let pier = workingPier, let session else { return }
        Task { _ = await session.emergency(pier: pier, operation: operation) }
    }

    func takeLease(token: String? = nil) {
        guard let pier = workingPier, let session else { return }
        Task { leaseOffer = await session.takeLease(pier: pier, takeOverToken: token) }
    }

    func releaseLease() {
        guard let pier = workingPier, let session else { return }
        Task { await session.releaseLease(pier: pier) }
    }

    func holdPressed(family: String, args: [String: JSONValue]) {
        guard let pier = workingPier, let session else { return }
        Task { await session.startHold(pier: pier, family: family, args: args) }
    }

    func holdReleased() {
        guard let session else { return }
        Task { await session.stopHold() }
    }

    func preview(width: Int, stretch: Int) async -> MasterAPI.PreviewImage? {
        guard let pier = workingPier, let session else { return nil }
        return try? await session.preview(pier: pier, width: width, stretch: stretch)
    }

    func tile(level: Int, x: Int, y: Int, stretch: Int) async -> Data? {
        guard let pier = workingPier, let session else { return nil }
        return try? await session.tile(pier: pier, level: level, x: x, y: y, stretch: stretch)
    }

    func makeSkyModel() -> SkyModel? {
        guard let pier = workingPier, let session else { return nil }
        return SkyModel(session: session, pier: pier)
    }

    /// `MAPP-SKY-040`: asks first, always. Nil when the answer needs nothing more from the screen.
    func goTo(name: String, raDeg: Double, decDeg: Double) async -> String? {
        guard let pier = workingPier, let session else { return nil }
        return handle(await session.goToQuestion(pier: pier, name: name, raDeg: raDeg, decDeg: decDeg))
    }

    // MARK: Live loop (`MAPP-IMG-020`)

    private(set) var liveLoopRunning = false
    private var liveLoop: Task<Void, Never>?

    /// One exposure after another, each shown as it arrives. Core has no live-loop operation, so this repeats a
    /// single capture while the app is in front: it asks for nothing while nobody is looking.
    func startLiveLoop(seconds: Double) -> String? {
        guard !liveLoopRunning else { return nil }
        liveLoopRunning = true
        liveLoop = Task { [weak self] in
            while let self, !Task.isCancelled, self.liveLoopRunning {
                let problem = await self.perform("imaging.capture", args: ["seconds": .number(seconds), "count": 1])
                guard problem == nil else {
                    self.liveLoopMessage = problem
                    break
                }
                try? await Task.sleep(for: .milliseconds(200))
                while !Task.isCancelled, self.record(of: "imaging.capture")?.state.isOpen == true {
                    try? await Task.sleep(for: .milliseconds(250))
                }
                switch self.record(of: "imaging.capture")?.state {
                case .done: continue
                default:
                    self.liveLoopMessage = "The live loop stopped: the last exposure didn't finish."
                    self.liveLoopRunning = false
                }
            }
            self?.liveLoopRunning = false
        }
        return nil
    }

    private(set) var liveLoopMessage: String?

    func stopLiveLoop() {
        liveLoopRunning = false
        liveLoop?.cancel()
        liveLoop = nil
    }

    // MARK: Reading state

    var pier: PierInfo? { snapshot.piers.first { $0.id == workingPier } }

    func value(_ key: String) -> ValueView? {
        guard let workingPier else { return nil }
        return snapshot.value(key, pier: workingPier, now: now)
    }

    /// Child keys under a prefix, for example the safety inputs.
    func children(of prefix: String) -> [String] {
        guard let workingPier, let values = snapshot.live.piers[workingPier]?.values else { return [] }
        return values.keys.filter { $0.hasPrefix(prefix + ".") }.map { String($0.dropFirst(prefix.count + 1)) }.sorted()
    }

    var canOperate: Bool {
        guard let workingPier else { return false }
        return snapshot.role == .operator && snapshot.access(pier: workingPier) == .control
            && snapshot.unavailableReason(pier: workingPier) == nil
    }

    func operation(_ name: String) -> OperationInfo? {
        guard let workingPier else { return nil }
        return snapshot.operation(name, pier: workingPier)
    }

    func record(of name: String) -> OperationRecord? {
        guard let workingPier else { return nil }
        return snapshot.ledger.latest(pier: workingPier, operation: name)
    }

    var overallSafety: String? { value("safety.overall")?.value.stringValue }
}

/// Launch arguments for UI tests and screenshots. Nothing a user can reach.
struct LaunchOptions {
    let demoScenario: String?
    let theme: ThemeChoice?

    init() {
        let defaults = UserDefaults.standard
        demoScenario = defaults.string(forKey: "GalileoDemo")
        theme = defaults.string(forKey: "GalileoTheme").flatMap(ThemeChoice.init(rawValue:))
    }
}
