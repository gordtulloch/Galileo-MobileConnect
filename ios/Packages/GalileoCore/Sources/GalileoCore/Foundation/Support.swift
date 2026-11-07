import Foundation

/// The core's only source of time, so that tests and vectors can drive it (`api/vectors/README.md`).
public protocol LinkClock: Sendable {
    func now() -> Date
    func sleep(seconds: Double) async throws
}

public struct SystemClock: LinkClock {
    public init() {}
    public func now() -> Date { Date() }
    public func sleep(seconds: Double) async throws {
        try await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
    }
}

/// A value shared across isolation domains behind a lock. `Mutex` needs iOS 18; the minimum is iOS 17.
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) { self.value = value }

    func withLock<Result>(_ body: (inout Value) -> Result) -> Result {
        lock.lock()
        defer { lock.unlock() }
        return body(&value)
    }

    var current: Value { withLock { $0 } }
}

enum WireTime {
    // ISO8601DateFormatter is documented as thread-safe.
    nonisolated(unsafe) private static let whole: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()
    nonisolated(unsafe) private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    /// The Master's timestamps, with or without fractional seconds, `Z` or an offset.
    static func parse(_ text: String) -> Date? {
        whole.date(from: text) ?? fractional.date(from: text)
    }

    static func string(_ date: Date) -> String { fractional.string(from: date) }
}

/// Anything the core can say went wrong with the link. `message` is written for a person.
public enum LinkError: Error, Sendable, Equatable {
    /// 401: the pairing was revoked or has expired.
    case unpaired
    /// The Master presented a different certificate from the pinned one.
    case identityChanged
    case unreachable(String)
    case unexpected(String)

    public var message: String {
        switch self {
        case .unpaired: "This device is no longer paired with the Master. Pair again to reconnect."
        case .identityChanged:
            "The Master's identity has changed: it presented a different certificate from the one this device trusted. If you did not replace it, do not continue."
        case .unreachable(let why): "The Master can't be reached. \(why)"
        case .unexpected(let why): why
        }
    }
}
