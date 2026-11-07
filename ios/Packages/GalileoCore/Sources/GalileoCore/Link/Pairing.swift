import Foundation

/// Pairing as the user sees it (`MAPP-CON-030`). Nothing else in the app is reachable before `paired`.
public enum PairingStatus: Sendable, Equatable {
    case idle
    case waiting(code: String, expiresIn: Double, fingerprint: String)
    case paired(MasterCredentials)
    case refused
    case timedOut
    case failed(String)

    public var isFinished: Bool {
        switch self {
        case .idle, .waiting: false
        default: true
        }
    }
}

/// `S-LINK` pairing: request, show the code, poll, take the token once, store it. The app displays the code; it
/// never asks for one.
public actor PairingFlow {
    private let api: MasterAPI
    private let store: any SecureStore
    private let address: MasterAddress
    private let masterName: String
    private let clock: any LinkClock
    private let presentedFingerprint: @Sendable () -> String?
    private let pollInterval: Double

    private var requestID: String?
    private var fingerprint: String?
    public private(set) var status: PairingStatus = .idle

    public init(
        api: MasterAPI, store: any SecureStore, address: MasterAddress, masterName: String,
        clock: any LinkClock = SystemClock(), pollInterval: Double = 2,
        presentedFingerprint: @escaping @Sendable () -> String? = { nil }
    ) {
        self.api = api
        self.store = store
        self.address = address
        self.masterName = masterName
        self.clock = clock
        self.pollInterval = pollInterval
        self.presentedFingerprint = presentedFingerprint
    }

    @discardableResult
    public func start() async -> PairingStatus {
        do {
            let started = try await api.requestPairing()
            // The certificate seen on this connection must be the one the Master says it has.
            if let presented = presentedFingerprint(), presented.lowercased() != started.fingerprint.lowercased() {
                status = .failed(LinkError.identityChanged.message)
                return status
            }
            requestID = started.requestID
            fingerprint = started.fingerprint
            status = .waiting(code: started.code, expiresIn: started.expiresIn, fingerprint: started.fingerprint)
        } catch let error as LinkError {
            status = .failed(error.message)
        } catch {
            status = .failed("The Master gave an answer this app can't read.")
        }
        return status
    }

    @discardableResult
    public func poll() async -> PairingStatus {
        guard let requestID, let fingerprint, case .waiting = status else { return status }
        do {
            let (state, token) = try await api.pollPairing(requestID: requestID)
            switch state {
            case "approved":
                guard let token else {
                    status = .failed("The Master approved the request but sent no token. Try again.")
                    return status
                }
                let credentials = MasterCredentials(
                    name: masterName, address: address, token: token, fingerprint: fingerprint)
                do {
                    try store.save(credentials)
                    api.setToken(token)
                    status = .paired(credentials)
                } catch {
                    status = .failed("This device could not store the pairing securely.")
                }
            case "rejected": status = .refused
            case "expired": status = .timedOut
            case "collected":
                status = .failed("This request was already answered and its token is gone. Try again.")
            default: break  // pending
            }
        } catch LinkError.unreachable {
            // A dropped poll is not a refusal: keep waiting, and let the request's own expiry end it.
        } catch let error as LinkError {
            status = .failed(error.message)
        } catch {
            status = .failed("The Master gave an answer this app can't read.")
        }
        return status
    }

    /// Polls until the request is answered or lapses. Cancel the task to stop waiting.
    public func waitForAnswer() async -> PairingStatus {
        while case .waiting = status {
            do { try await clock.sleep(seconds: pollInterval) } catch { return status }
            await poll()
        }
        return status
    }
}
