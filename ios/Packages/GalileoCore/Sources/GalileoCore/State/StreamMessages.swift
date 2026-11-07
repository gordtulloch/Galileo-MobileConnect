import Foundation

public struct AlertEvent: Sendable, Equatable, Identifiable, Codable {
    public var id: String
    public var severity: String
    public var time: Date
    public var pier: String?
    public var message: String
    public var important: Bool

    public init(id: String, severity: String, time: Date, pier: String? = nil, message: String, important: Bool = false) {
        self.id = id
        self.severity = severity
        self.time = time
        self.pier = pier
        self.message = message
        self.important = important
    }
}

public struct OperationEvent: Sendable, Equatable {
    public var phase: String  // op.started, op.progress, op.finished
    public var correlationID: String
    public var requestID: String?
    public var pier: String?
    public var operation: String?
    public var progress: Double?
    public var outcome: String?
    public var reason: String?
}

public enum StreamEvent: Sendable, Equatable {
    case snapshot(asOf: Date, piers: [String: JSONValue])
    case delta(pier: String, key: String, value: JSONValue, asOf: Date)
    case operation(OperationEvent)
    case alert(AlertEvent)
    case lease(pier: String, holder: String?)
    case access(pier: String, access: String, reachable: Bool?, reason: String?)
    case closing(reason: String)
    /// A newer Master's message. Ignored, never an error (`events.schema.json`).
    case unknown
}

/// One frame of `GET /connect/v1/stream`.
public enum StreamMessage: Sendable, Equatable {
    case hello(epoch: String, seq: Int)
    case resync(epoch: String, seq: Int)
    case frame(seq: Int, event: StreamEvent)

    public init?(data: Data) {
        guard let json = try? JSONDecoder().decode(JSONValue.self, from: data) else { return nil }
        self.init(json: json)
    }

    public init?(json: JSONValue) {
        guard let seq = json["seq"]?.intValue else { return nil }
        switch json["type"]?.stringValue {
        case "hello":
            guard let epoch = json["epoch"]?.stringValue else { return nil }
            self = .hello(epoch: epoch, seq: seq)
        case "resync":
            guard let epoch = json["epoch"]?.stringValue else { return nil }
            self = .resync(epoch: epoch, seq: seq)
        default:
            guard let data = json["data"] else { return nil }
            self = .frame(seq: seq, event: Self.event(data))
        }
    }

    private static func event(_ json: JSONValue) -> StreamEvent {
        let now = { (key: String) in json[key]?.stringValue.flatMap(WireTime.parse) }
        switch json["type"]?.stringValue {
        case "snapshot":
            guard let asOf = now("as_of"), let piers = json["piers"]?.objectValue else { return .unknown }
            return .snapshot(asOf: asOf, piers: piers)
        case "delta":
            guard let pier = json["pier"]?.stringValue, let key = json["key"]?.stringValue,
                let value = json["value"], let asOf = now("as_of")
            else { return .unknown }
            return .delta(pier: pier, key: key, value: value, asOf: asOf)
        case let phase? where phase.hasPrefix("op."):
            guard let correlation = json["correlation_id"]?.stringValue else { return .unknown }
            return .operation(
                OperationEvent(
                    phase: phase, correlationID: correlation, requestID: json["request_id"]?.stringValue,
                    pier: json["pier"]?.stringValue, operation: json["operation"]?.stringValue,
                    progress: json["progress"]?.doubleValue, outcome: json["outcome"]?.stringValue,
                    reason: json["reason"]?.stringValue))
        case "alert":
            guard let id = json["id"]?.stringValue, let severity = json["severity"]?.stringValue,
                let time = now("time"), let message = json["message"]?.stringValue
            else { return .unknown }
            return .alert(
                AlertEvent(
                    id: id, severity: severity, time: time, pier: json["pier"]?.stringValue, message: message,
                    important: json["important"]?.boolValue ?? false))
        case "lease":
            guard let pier = json["pier"]?.stringValue else { return .unknown }
            return .lease(pier: pier, holder: json["holder"]?.stringValue)
        case "access":
            guard let pier = json["pier"]?.stringValue, let access = json["access"]?.stringValue else { return .unknown }
            return .access(
                pier: pier, access: access, reachable: json["reachable"]?.boolValue, reason: json["reason"]?.stringValue)
        case "closing":
            return .closing(reason: json["reason"]?.stringValue ?? "")
        default:
            return .unknown
        }
    }
}
