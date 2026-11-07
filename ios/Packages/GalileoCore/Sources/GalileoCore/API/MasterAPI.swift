import Foundation
import GalileoAPI
import HTTPTypes

/// The typed client (`S-API`): a thin facade over the generated `Client`, adding identity, revocation handling,
/// the catalogue filter and typed operation results. Nothing here can express a configuration operation.
public struct MasterAPI: Sendable {
    private let client: Client
    private let credentials: TokenHolder
    private let identity: ClientIdentity
    private let identityChanged: @Sendable () -> Bool

    public init(
        baseURL: URL, transport: any ClientTransport, identity: ClientIdentity, token: String? = nil,
        identityChanged: @escaping @Sendable () -> Bool = { false }
    ) {
        let holder = TokenHolder(token: token)
        self.credentials = holder
        self.identity = identity
        self.identityChanged = identityChanged
        self.client = Client(
            serverURL: baseURL, configuration: .init(dateTranscoder: WireDates()), transport: transport,
            middlewares: [IdentityMiddleware(userAgent: identity.userAgent, holder: holder)])
    }

    public func setToken(_ token: String?) { credentials.set(token) }
    var tokenForStream: String? { credentials.current }

    // MARK: Pairing (unauthenticated)

    public struct PairingStart: Sendable, Equatable {
        public var requestID: String
        public var code: String
        public var expiresIn: Double
        public var fingerprint: String
        public var masterProtocol: Int
    }

    public func requestPairing() async throws -> PairingStart {
        let output = try await calling {
            try await client.requestPairing(
                body: .json(
                    .init(
                        plugin: "mobileconnect", name: String(identity.deviceName.prefix(80)), kind: "app",
                        platform: identity.platform)))
        }
        switch output {
        case .accepted(let response):
            let body = try response.body.json
            return PairingStart(
                requestID: body.request_id, code: body.code, expiresIn: body.expires_in,
                fingerprint: body.fingerprint, masterProtocol: body._protocol)
        case .tooManyRequests:
            throw LinkError.unexpected("Too many pairing requests from this device. Wait a minute and try again.")
        case .serviceUnavailable:
            throw LinkError.unexpected("Too many pairing requests are waiting on the Master. Deal with those first, then try again.")
        case .badRequest:
            throw LinkError.unexpected("The Master refused the pairing request.")
        case .undocumented(let status, _):
            throw LinkError.unexpected("The Master gave an unexpected answer (\(status)) to the pairing request.")
        }
    }

    public func pollPairing(requestID: String) async throws -> (state: String, token: String?) {
        let output = try await calling { try await client.pollPairing(path: .init(request_id: requestID)) }
        switch output {
        case .ok(let response):
            let body = try response.body.json
            return (body.state.rawValue, body.token)
        case .undocumented(let status, _):
            throw LinkError.unexpected("The Master gave an unexpected answer (\(status)) while pairing.")
        }
    }

    // MARK: Session

    public func handshake() async throws -> HandshakeOutcome {
        let request = Components.Schemas.HandshakeRequest(
            _protocol: LinkProtocol.version, schema: 0,
            plugin: .init(app_version: identity.appVersion, platform: identity.platform, client_class: identity.clientClass))
        let output = try await calling { try await client.handshake(body: .json(request)) }
        switch output {
        case .ok(let response):
            let body = try response.body.json
            return HandshakeOutcome(
                verdict: HandshakeOutcome.Verdict(rawValue: body.verdict.rawValue) ?? .refused,
                message: body.message, allowed: Set(body.allowed),
                masterProtocol: body.node._protocol, galileoVersion: body.node.galileo)
        case .unauthorized: throw LinkError.unpaired
        case .badRequest: throw LinkError.unexpected("The Master did not understand this app's version details.")
        case .undocumented(let status, _): throw Self.unexpected(status)
        }
    }

    public func whoAmI() async throws -> Identity {
        let output = try await calling { try await client.whoami() }
        switch output {
        case .ok(let response):
            let body = try response.body.json
            return Identity(peerID: body.peer_id, name: body.name, permissions: Set(body.permissions))
        case .unauthorized: throw LinkError.unpaired
        case .undocumented(let status, _): throw Self.unexpected(status)
        }
    }

    /// nil when the Master does not offer the route yet: an old node simply lacks it.
    public func capabilities() async throws -> Capabilities? {
        let output = try await calling { try await client.getCapabilities() }
        switch output {
        case .ok(let response):
            let body = try response.body.json
            return Capabilities(
                areas: body.areas, skyStreamAvailable: body.sky_stream?.available,
                skyStreamReason: body.sky_stream?.reason)
        case .unauthorized: throw LinkError.unpaired
        case .undocumented(let status, _):
            if status == 403 || status == 404 { return nil }
            throw Self.unexpected(status)
        }
    }

    public func piers() async throws -> [PierInfo] {
        let output = try await calling { try await client.listPiers() }
        switch output {
        case .ok(let response):
            return try response.body.json.piers.map {
                PierInfo(
                    id: $0.id, name: $0.name, node: $0.node, observatory: $0.observatory,
                    access: PierInfo.Access(rawValue: $0.access.rawValue) ?? .none,
                    reachable: $0.reachable, reason: $0.reason)
            }
        case .unauthorized: throw LinkError.unpaired
        case .undocumented(let status, _): throw Self.unexpected(status)
        }
    }

    public func catalogue(pier: String?) async throws -> [OperationInfo] {
        let output = try await calling { try await client.getCatalogue(query: .init(pier: pier)) }
        switch output {
        case .ok(let response):
            return try response.body.json.operations.compactMap(Self.operationInfo)
        case .unauthorized: throw LinkError.unpaired
        case .undocumented(let status, _): throw Self.unexpected(status)
        }
    }

    static func operationInfo(_ entry: Components.Schemas.Operation) -> OperationInfo? {
        guard let cls = CatalogueFilter.accept(name: entry.name, className: entry._class) else { return nil }
        return OperationInfo(
            name: entry.name, area: entry.area, operationClass: cls, needsLease: entry.lease,
            arguments: entry.args.compactMap { arg in
                guard let kind = ArgumentInfo.Kind(rawValue: arg._type.rawValue) else { return nil }
                return ArgumentInfo(
                    name: arg.name, kind: kind, required: arg.required, unit: arg.unit, description: arg.description,
                    defaultValue: arg._default.flatMap { try? JSONValue(encoding: $0) },
                    minimum: arg.min, maximum: arg.max, choices: arg.choices ?? [])
            },
            description: entry.description, available: entry.available ?? true,
            unavailableBecause: entry.unavailable_because, longRunning: entry.long_running ?? false)
    }

    public func alerts() async throws -> [AlertEvent] {
        let output = try await calling { try await client.listAlerts() }
        switch output {
        case .ok(let response):
            return try response.body.json.alerts.map {
                AlertEvent(
                    id: $0.id, severity: $0.severity.rawValue, time: $0.time, pier: $0.pier, message: $0.message,
                    important: $0.important ?? false)
            }
        case .unauthorized: throw LinkError.unpaired
        case .undocumented(let status, _): throw Self.unexpected(status)
        }
    }

    // MARK: Operations

    public func invoke(
        pier: String, name: String, args: [String: JSONValue], idempotencyKey: String, confirmToken: String? = nil,
        emergencyConfirmed: Bool = false
    ) async -> OpResult {
        do {
            let request = Components.Schemas.OperationRequest(
                args: .init(additionalProperties: try JSONValue.object(args).converted(to: OpenAPIObjectContainer.self)),
                confirmed: emergencyConfirmed ? true : nil)
            let output = try await calling {
                try await client.invokeOperation(
                    path: .init(pier: pier, name: name),
                    headers: .init(Idempotency_hyphen_Key: idempotencyKey, X_hyphen_Confirm_hyphen_Token: confirmToken),
                    body: .json(request))
            }
            switch output {
            case .ok(let response):
                let body = try response.body.json
                return .ok(value: body.result.flatMap { try? JSONValue(encoding: $0) }, correlationID: body.correlation_id)
            case .unauthorized:
                return .refused(reason: LinkError.unpaired.message, kind: "unpaired", holder: nil, canTake: false)
            case .`default`(_, let response):
                return Self.opResult(try response.body.json)
            }
        } catch LinkError.unpaired {
            return .refused(reason: LinkError.unpaired.message, kind: "unpaired", holder: nil, canTake: false)
        } catch is DecodingError {
            return .unavailable(reason: "The Master gave an answer this app can't read.")
        } catch {
            return .connectionLost
        }
    }

    static func opResult(_ error: Components.Schemas.OperationError) -> OpResult {
        let reason = error.reason ?? "The Master gave no reason."
        let detail = error.detail.flatMap { try? JSONValue(encoding: $0.additionalProperties) }
        switch error.error {
        case .refused:
            return .refused(
                reason: reason, kind: detail?["kind"]?.stringValue, holder: detail?["holder"]?.stringValue,
                canTake: detail?["can_take"]?.boolValue ?? false)
        case .invalid: return .invalid(path: detail?["path"]?.stringValue, reason: reason)
        case .unavailable: return .unavailable(reason: reason)
        case .busy: return .busy(reason: reason)
        case .unreachable: return .unreachable(reason: reason)
        case .confirmation_required:
            guard let token = error.confirm_token else {
                return .unavailable(reason: "The Master asked for a confirmation but gave no way to confirm.")
            }
            return .confirmationRequired(token: token, reason: error.reason)
        }
    }

    /// Takes the lease, or takes it over when `takeOverToken` is the token the refusal offered.
    public func acquireLease(pier: String, takeOverToken: String? = nil) async -> Result<LeaseAnswer, LeaseRefusal> {
        do {
            let output = try await calling {
                try await client.acquireLease(
                    path: .init(pier: pier), headers: .init(X_hyphen_Confirm_hyphen_Token: takeOverToken))
            }
            switch output {
            case .ok(let response):
                let body = try response.body.json
                return .success(LeaseAnswer(heldByMe: body.held_by_me, holder: body.holder))
            case .unauthorized:
                return .failure(LeaseRefusal(reason: LinkError.unpaired.message))
            case .`default`(_, let response):
                let error = try response.body.json
                let detail = error.detail.flatMap { try? JSONValue(encoding: $0.additionalProperties) }
                return .failure(
                    LeaseRefusal(
                        reason: error.reason ?? "The Master refused the lease.", holder: detail?["holder"]?.stringValue,
                        takeOverToken: (detail?["can_take"]?.boolValue ?? false) ? error.confirm_token : nil))
            }
        } catch let error as LinkError {
            return .failure(LeaseRefusal(reason: error.message))
        } catch {
            return .failure(LeaseRefusal(reason: "The Master can't be reached."))
        }
    }

    public func releaseLease(pier: String) async -> Bool {
        guard let output = try? await calling({ try await client.releaseLease(path: .init(pier: pier)) }) else {
            return false
        }
        if case .ok = output { return true }
        return false
    }

    /// The Master renders the picture; the stretch travels to it so that it agrees with the desktop (`S-IMG`).
    public func preview(pier: String, width: Int, quality: Int, stretch: Stretch) async throws -> Data? {
        let output = try await calling {
            try await client.getPreview(
                path: .init(pier: pier),
                query: .init(w: width, q: quality, black: stretch.black, mid: stretch.mid, white: stretch.white))
        }
        switch output {
        case .ok(let response):
            return try await Data(collecting: response.body.jpeg, upTo: 64 * 1024 * 1024)
        case .notFound: return nil
        case .unauthorized: throw LinkError.unpaired
        case .undocumented(let status, _): throw Self.unexpected(status)
        }
    }

    // MARK: Errors

    private static func unexpected(_ status: Int) -> LinkError {
        .unexpected("The Master gave an unexpected answer (\(status)).")
    }

    /// Every call passes through here, so a pin mismatch is reported as such and not as a network failure.
    private func calling<T>(_ call: () async throws -> T) async throws -> T {
        do {
            return try await call()
        } catch {
            if identityChanged() { throw LinkError.identityChanged }
            if let client = error as? ClientError {
                let underlying = client.underlyingError
                if underlying is DecodingError { throw underlying }
                if let link = underlying as? LinkError { throw link }
                throw LinkError.unreachable(underlying.localizedDescription)
            }
            throw error
        }
    }
}

public struct LeaseRefusal: Error, Sendable, Equatable {
    public var reason: String
    public var holder: String?
    public var takeOverToken: String?

    public init(reason: String, holder: String? = nil, takeOverToken: String? = nil) {
        self.reason = reason
        self.holder = holder
        self.takeOverToken = takeOverToken
    }
}

public struct Stretch: Sendable, Equatable {
    public var black: Double
    public var mid: Double
    public var white: Double

    public init(black: Double = 0, mid: Double = 0.5, white: Double = 1) {
        self.black = black
        self.mid = mid
        self.white = white
    }
}

/// The generated default rejects fractional seconds, which Python's `isoformat()` produces.
struct WireDates: DateTranscoder {
    func encode(_ date: Date) throws -> String { WireTime.string(date) }

    func decode(_ text: String) throws -> Date {
        guard let date = WireTime.parse(text) else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Not a date: \(text)"))
        }
        return date
    }
}

final class TokenHolder: Sendable {
    private let token: Locked<String?>
    init(token: String?) { self.token = Locked(token) }
    func set(_ value: String?) { token.withLock { $0 = value } }
    var current: String? { token.current }
}

/// Adds the bearer token and the app's identity to every request, and turns a 401 into `LinkError.unpaired`
/// in one place (`MAPP-CON-060`, `MAPP-CTL-080`). Nothing is logged here, so nothing needs redacting.
struct IdentityMiddleware: ClientMiddleware {
    let userAgent: String
    let holder: TokenHolder

    func intercept(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var request = request
        request.headerFields[.userAgent] = userAgent
        if let token = holder.current { request.headerFields[.authorization] = "Bearer \(token)" }
        let (response, responseBody) = try await next(request, body, baseURL)
        if response.status.code == 401 { throw LinkError.unpaired }
        return (response, responseBody)
    }
}
