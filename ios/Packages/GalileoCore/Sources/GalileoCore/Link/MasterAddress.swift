import Foundation

/// Where a Master listens. Manual entry is validated here before any request is made (`MAPP-CON-020`).
public struct MasterAddress: Codable, Sendable, Equatable, Hashable {
    public static let defaultPort = 8765

    public var host: String
    public var port: Int

    public init(host: String, port: Int = MasterAddress.defaultPort) {
        self.host = host
        self.port = port
    }

    /// `https://host:port`, bracketing an IPv6 host.
    public var baseURL: URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = host.contains(":") ? "[\(host)]" : host
        components.port = port
        if let url = components.url { return url }
        // A valid MasterAddress always forms a URL; this is the unreachable fall-back, not a default host.
        return URL(fileURLWithPath: "/")
    }

    public enum Problem: Error, Equatable, Sendable {
        case empty, notEncrypted, unsupportedScheme(String), badHost, badPort

        public var message: String {
            switch self {
            case .empty: "Enter the address of the computer running Galileo."
            case .notEncrypted: "Galileo only connects over an encrypted link. Use https, or leave the prefix off."
            case .unsupportedScheme(let scheme): "“\(scheme)” is not a kind of address Galileo can use."
            case .badHost: "That doesn't look like a computer name or IP address."
            case .badPort: "The port must be a number from 1 to 65535."
            }
        }
    }

    public static func parse(_ text: String) -> Result<MasterAddress, Problem> {
        var rest = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if rest.isEmpty { return .failure(.empty) }
        if let range = rest.range(of: "://") {
            let scheme = rest[..<range.lowerBound].lowercased()
            if scheme == "http" { return .failure(.notEncrypted) }
            if scheme != "https" { return .failure(.unsupportedScheme(scheme)) }
            rest = String(rest[range.upperBound...])
        }
        if let slash = rest.firstIndex(of: "/") {
            guard rest[slash...].allSatisfy({ $0 == "/" }) else { return .failure(.badHost) }
            rest = String(rest[..<slash])
        }

        var host = rest
        var portText: String?
        if rest.hasPrefix("[") {  // [IPv6] or [IPv6]:port
            guard let close = rest.firstIndex(of: "]") else { return .failure(.badHost) }
            host = String(rest[rest.index(after: rest.startIndex)..<close])
            let after = rest[rest.index(after: close)...]
            if !after.isEmpty {
                guard after.hasPrefix(":") else { return .failure(.badHost) }
                portText = String(after.dropFirst())
            }
            guard host.contains(":"), host.allSatisfy({ $0.isHexDigit || $0 == ":" || $0 == "." }) else {
                return .failure(.badHost)
            }
        } else if rest.filter({ $0 == ":" }).count == 1, let colon = rest.firstIndex(of: ":") {
            host = String(rest[..<colon])
            portText = String(rest[rest.index(after: colon)...])
            guard isHostName(host) else { return .failure(.badHost) }
        } else if rest.contains(":") {  // a bare IPv6 address has no room for a port
            guard rest.allSatisfy({ $0.isHexDigit || $0 == ":" || $0 == "." }) else { return .failure(.badHost) }
        } else {
            guard isHostName(host) else { return .failure(.badHost) }
        }

        var port = defaultPort
        if let portText {
            guard let value = Int(portText), (1...65535).contains(value) else { return .failure(.badPort) }
            port = value
        }
        return .success(MasterAddress(host: host, port: port))
    }

    private static func isHostName(_ text: String) -> Bool {
        guard !text.isEmpty, text.count <= 253 else { return false }
        return text.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
            !label.isEmpty && label.count <= 63 && !label.hasPrefix("-") && !label.hasSuffix("-")
                && label.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
        }
    }
}
