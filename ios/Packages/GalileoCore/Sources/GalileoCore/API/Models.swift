import Foundation

/// How this client identifies itself, on every request and in the handshake (`MAPP-CTL-080`).
public struct ClientIdentity: Sendable, Equatable {
    public var deviceName: String
    public var appVersion: String
    /// `ios` or `ipados`.
    public var platform: String
    /// `tablet` or `phone`.
    public var clientClass: String

    public init(deviceName: String, appVersion: String, platform: String, clientClass: String) {
        self.deviceName = deviceName
        self.appVersion = appVersion
        self.platform = platform
        self.clientClass = clientClass
    }

    var userAgent: String { "GalileoMobileConnect/\(appVersion) (\(platform))" }
}

/// The Connect Link protocol this client speaks. A Master on another version refuses the handshake
/// (`PROTOCOL_VERSION` in `galileo/connect_link/server.py`).
public enum LinkProtocol {
    public static let version = 1
}

public struct ArgumentInfo: Sendable, Equatable, Identifiable {
    public enum Kind: String, Sendable { case int, float, str, bool, `enum` }

    public var id: String { name }
    public var name: String
    public var kind: Kind
    public var required: Bool
    public var unit: String?
    public var description: String?
    public var defaultValue: JSONValue?
    public var minimum: Double?
    public var maximum: Double?
    public var choices: [String]

    public init(
        name: String, kind: Kind, required: Bool = true, unit: String? = nil, description: String? = nil,
        defaultValue: JSONValue? = nil, minimum: Double? = nil, maximum: Double? = nil, choices: [String] = []
    ) {
        self.name = name
        self.kind = kind
        self.required = required
        self.unit = unit
        self.description = description
        self.defaultValue = defaultValue
        self.minimum = minimum
        self.maximum = maximum
        self.choices = choices
    }

    /// Why `value` is not acceptable, in the words `galileo.control_surface.schema` uses, or nil.
    func problem(with value: JSONValue) -> String? {
        let unitSuffix = unit.map { " \($0)" } ?? ""
        switch kind {
        case .bool:
            return value.boolValue == nil ? "must be true or false" : nil
        case .int, .float:
            guard let number = value.doubleValue else { return "must be a number\(unitSuffix)" }
            if kind == .int, number.rounded() != number { return "must be a whole number" }
            if !number.isFinite { return "must be finite" }
            if let minimum, number < minimum { return "must be at least \(minimum.formatted())\(unitSuffix)" }
            if let maximum, number > maximum { return "must be at most \(maximum.formatted())\(unitSuffix)" }
            return nil
        case .str:
            return value.stringValue == nil ? "must be text" : nil
        case .enum:
            guard let text = value.stringValue else { return "must be text" }
            return choices.contains(text) ? nil : "must be one of \(choices.joined(separator: ", "))"
        }
    }
}

public struct OperationInfo: Sendable, Equatable, Identifiable {
    public enum Class: String, Sendable { case read, operate, confirm, emergency }

    public var id: String { name }
    public var name: String
    public var area: String
    public var operationClass: Class
    public var needsLease: Bool
    public var arguments: [ArgumentInfo]
    public var description: String?
    public var available: Bool
    public var unavailableBecause: String?
    public var longRunning: Bool

    public init(
        name: String, area: String, operationClass: Class, needsLease: Bool, arguments: [ArgumentInfo] = [],
        description: String? = nil, available: Bool = true, unavailableBecause: String? = nil, longRunning: Bool = false
    ) {
        self.name = name
        self.area = area
        self.operationClass = operationClass
        self.needsLease = needsLease
        self.arguments = arguments
        self.description = description
        self.available = available
        self.unavailableBecause = unavailableBecause
        self.longRunning = longRunning
    }

    /// The first fault in `args`, naming the argument, exactly as the Master would (`validate_args`).
    public func validate(_ args: [String: JSONValue]) -> (path: String, reason: String)? {
        let known = Set(arguments.map(\.name))
        if let extra = args.keys.filter({ !known.contains($0) }).sorted().first {
            return (extra, "is not an argument of this operation")
        }
        for argument in arguments {
            if let value = args[argument.name] {
                if let problem = argument.problem(with: value) { return (argument.name, problem) }
            } else if argument.required && argument.defaultValue == nil {
                return (argument.name, "is required")
            }
        }
        return nil
    }
}

/// The catalogue as the app may see it (`S-API`): configuration cannot be represented, and a focuser can only
/// be moved through the focus module (`MAPP-CFG-010`, `MAPP-FOC-030`).
public enum CatalogueFilter {
    static let allowedAreas: Set<String> = [
        "camera", "device", "dome", "filter", "flatpanel", "flip", "focus", "guiding", "history", "imaging", "mount",
        "pier", "rotator", "safety", "scheduler", "session", "sky", "solve", "system", "weather",
    ]

    /// nil when the entry must not reach the app: an unknown class (`config` included) or a name outside the areas.
    static func accept(name: String, className: String) -> OperationInfo.Class? {
        guard let area = name.split(separator: ".").first, allowedAreas.contains(String(area)) else { return nil }
        return OperationInfo.Class(rawValue: className)
    }
}

public struct PierInfo: Sendable, Equatable, Identifiable {
    public enum Access: String, Sendable { case control, view, none }

    public var id: String
    public var name: String
    public var node: String
    public var observatory: String?
    public var access: Access
    public var reachable: Bool
    public var reason: String?

    public init(
        id: String, name: String, node: String, observatory: String? = nil, access: Access = .control,
        reachable: Bool = true, reason: String? = nil
    ) {
        self.id = id
        self.name = name
        self.node = node
        self.observatory = observatory
        self.access = access
        self.reachable = reachable
        self.reason = reason
    }
}

public struct Capabilities: Sendable, Equatable {
    public var areas: [String]
    public var skyStreamAvailable: Bool?
    public var skyStreamReason: String?
    public var skyStreamRate: Double?

    public init(areas: [String] = [], skyStreamAvailable: Bool? = nil, skyStreamReason: String? = nil, skyStreamRate: Double? = nil) {
        self.areas = areas
        self.skyStreamAvailable = skyStreamAvailable
        self.skyStreamReason = skyStreamReason
        self.skyStreamRate = skyStreamRate
    }
}

public struct HandshakeOutcome: Sendable, Equatable {
    public enum Verdict: String, Sendable { case ok, restricted, refused }

    public var verdict: Verdict
    public var message: String
    public var allowed: Set<String>
    public var masterProtocol: Int
    public var galileoVersion: String
}

public struct Identity: Sendable, Equatable {
    public var peerID: String
    public var name: String
    public var permissions: Set<String>
}

public enum Role: String, Sendable, Equatable {
    case viewer = "Viewer"
    case `operator` = "Operator"

    /// The peer's real grant comes from `whoami`. The handshake's `allowed` only says what the exchange permits
    /// and always contains `config.*` from a current Master, so it cannot be the test for a configuration grant
    /// (`docs/SDD.md` 4.2, item 8).
    public static func from(permissions: Set<String>, allowed: Set<String>) -> Role? {
        let effective = permissions.intersection(allowed)
        if effective.contains("control") { return .operator }
        if effective.contains("view") { return .viewer }
        return nil
    }

    /// `MAPP-CFG-030`: the app refuses to run on a grant it must never hold.
    public static func hasConfigurationGrant(_ permissions: Set<String>) -> Bool {
        permissions.contains { $0.hasPrefix("config.") }
    }
}

/// The Master's typed answer to an operation (`S-API`).
public enum OpResult: Sendable, Equatable {
    case ok(value: JSONValue?, correlationID: String?)
    case refused(reason: String, kind: String?, holder: String?, canTake: Bool)
    case invalid(path: String?, reason: String)
    case unavailable(reason: String)
    case busy(reason: String)
    case unreachable(reason: String)
    case confirmationRequired(token: String, reason: String?)
    /// The request may or may not have reached the Master. Never treated as done, never retried on its own.
    case connectionLost
}

public struct LeaseAnswer: Sendable, Equatable {
    public var heldByMe: Bool
    public var holder: String?
}
