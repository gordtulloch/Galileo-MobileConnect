import Foundation
import GalileoAPI

/// Builds the real HTTPS and WebSocket clients for one Master, sharing one pinned URLSession.
public struct MasterConnection: Sendable {
    public let api: MasterAPI
    public let stream: any StreamTransport
    public let delegate: PinningDelegate?

    public init(api: MasterAPI, stream: any StreamTransport, delegate: PinningDelegate? = nil) {
        self.api = api
        self.stream = stream
        self.delegate = delegate
    }

    public static func live(
        address: MasterAddress, policy: PinPolicy, identity: ClientIdentity, token: String?
    ) -> MasterConnection {
        let delegate = PinningDelegate(policy: policy)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.waitsForConnectivity = false
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        let api = MasterAPI(
            baseURL: address.baseURL,
            transport: URLSessionTransport(configuration: .init(session: session)),
            identity: identity, token: token, identityChanged: { delegate.identityChanged })
        let stream = WebSocketStreamTransport(
            session: session, baseURL: address.baseURL, token: { api.currentToken },
            identityChanged: { delegate.identityChanged })
        return MasterConnection(api: api, stream: stream, delegate: delegate)
    }
}

extension MasterAPI {
    var currentToken: String? { tokenForStream }
}
