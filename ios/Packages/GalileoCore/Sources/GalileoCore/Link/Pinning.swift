import CryptoKit
import Foundation
import Security

/// Which certificate a connection accepts (`S-LINK` certificate trust, `MAPP-CON-050`).
public enum PinPolicy: Sendable, Equatable {
    /// Pairing only: accept what is shown, for the one exchange, and compare it with the fingerprint in the
    /// pairing response before anything is stored. The human check of the code is what makes this safe.
    case pairing
    /// Accept only this fingerprint. There is no fall-back to an unverified connection.
    case pinned(String)

    public func accepts(presented fingerprint: String) -> Bool {
        switch self {
        case .pairing: true
        case .pinned(let pin): pin.lowercased() == fingerprint.lowercased()
        }
    }

    /// Lower-case hex SHA-256 of a certificate's DER form, as `galileo.connect_link.certs` computes it.
    public static func fingerprint(ofDER der: Data) -> String {
        SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }
}

/// The TLS callback for one URLSession. App Transport Security stays on (iOS SDD 3.3): this replaces chain
/// validation for the Master's self-signed certificate with the pin, and nothing else.
public final class PinningDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    private struct State {
        var presented: String?
        var mismatch = false
    }

    private let policy: PinPolicy
    private let state = Locked(State())

    public init(policy: PinPolicy) { self.policy = policy }

    /// The fingerprint of the certificate most recently shown by the Master.
    public var presentedFingerprint: String? { state.current.presented }
    public var identityChanged: Bool { state.current.mismatch }

    public func urlSession(
        _ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
            let trust = challenge.protectionSpace.serverTrust,
            let leaf = (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first
        else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let fingerprint = PinPolicy.fingerprint(ofDER: SecCertificateCopyData(leaf) as Data)
        let accepted = policy.accepts(presented: fingerprint)
        state.withLock {
            $0.presented = fingerprint
            if !accepted { $0.mismatch = true }
        }
        if accepted {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
