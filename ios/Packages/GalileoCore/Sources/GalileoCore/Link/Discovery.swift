import Foundation
import Network

public struct DiscoveredMaster: Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var protocolVersion: Int?
    public var role: String
    let endpoint: NWEndpoint
}

public enum DiscoveryEvent: Sendable, Equatable {
    case results([DiscoveredMaster])
    /// iOS refused the local-network permission (`MAPP-PLT-070`).
    case permissionDenied
    case failed(String)
}

/// Bonjour discovery of `_galileo-link._tcp`. The advertisement carries only `name`, `node`, `proto` and `role`
/// (`galileo/connect_link/discovery.py`), so the address is resolved when the user chooses a Master.
public final class BonjourDiscovery: @unchecked Sendable {
    public static let serviceType = "_galileo-link._tcp"

    private let browser: NWBrowser
    private let queue = DispatchQueue(label: "galileo.discovery")

    public init() {
        browser = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: .tcp)
    }

    public func events() -> AsyncStream<DiscoveryEvent> {
        AsyncStream { continuation in
            browser.stateUpdateHandler = { state in
                switch state {
                case .waiting(let error), .failed(let error):
                    if case .dns(let code) = error, code == -65570 {  // kDNSServiceErr_PolicyDenied
                        continuation.yield(.permissionDenied)
                    } else {
                        continuation.yield(.failed(error.localizedDescription))
                    }
                default: break
                }
            }
            browser.browseResultsChangedHandler = { results, _ in
                let masters = results.compactMap(Self.master).sorted { $0.name < $1.name }
                continuation.yield(.results(masters))
            }
            continuation.onTermination = { [browser] _ in browser.cancel() }
            browser.start(queue: queue)
        }
    }

    private static func master(from result: NWBrowser.Result) -> DiscoveredMaster? {
        guard case .service(let service, _, _, _) = result.endpoint else { return nil }
        var name = service
        var node = service
        var proto: Int?
        var role = ""
        if case .bonjour(let txt) = result.metadata {
            name = txt["name"].flatMap { $0.isEmpty ? nil : $0 } ?? service
            node = txt["node"] ?? service
            proto = txt["proto"].flatMap(Int.init)
            role = txt["role"] ?? ""
        }
        return DiscoveredMaster(id: node, name: name, protocolVersion: proto, role: role, endpoint: result.endpoint)
    }

    /// Resolves a chosen Master to a host and port by opening, then closing, a connection to it.
    public static func resolve(_ master: DiscoveredMaster, timeout: TimeInterval = 8) async throws -> MasterAddress {
        let connection = NWConnection(to: master.endpoint, using: .tcp)
        let finished = Locked(false)
        return try await withCheckedThrowingContinuation { continuation in
            @Sendable func finish(_ result: Result<MasterAddress, any Error>) {
                let first = finished.withLock { done -> Bool in
                    defer { done = true }
                    return !done
                }
                guard first else { return }
                connection.cancel()
                continuation.resume(with: result)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if case .hostPort(let host, let port) = connection.currentPath?.remoteEndpoint {
                        finish(.success(MasterAddress(host: hostString(host), port: Int(port.rawValue))))
                    } else {
                        finish(.failure(LinkError.unexpected("Could not find the address of \(master.name).")))
                    }
                case .failed(let error), .waiting(let error):
                    finish(.failure(LinkError.unreachable(error.localizedDescription)))
                default: break
                }
            }
            connection.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                finish(.failure(LinkError.unreachable("\(master.name) did not answer.")))
            }
        }
    }

    private static func hostString(_ host: NWEndpoint.Host) -> String {
        var text = "\(host)"
        if let zone = text.firstIndex(of: "%") { text = String(text[..<zone]) }  // URLs can't carry a scope id
        return text
    }
}
