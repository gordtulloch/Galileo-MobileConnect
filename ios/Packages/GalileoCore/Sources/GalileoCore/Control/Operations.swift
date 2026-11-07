import Foundation

/// One operation as the user sees it. Only the Master's answer moves it past `pending` (`MAPP-CTL-050`).
public struct OperationRecord: Sendable, Equatable, Identifiable {
    public enum State: Sendable, Equatable {
        case pending
        /// Accepted, and still running on the Master.
        case running(progress: Double?)
        case done(JSONValue?)
        case refused(String)
        case invalid(path: String?, reason: String)
        case unavailable(String)
        case busy(String)
        case unreachable(String)
        /// The link went while this was in flight (`MAPP-CTL-070`). Check the state; it is never re-sent.
        case outcomeUnknown

        public var isOpen: Bool {
            switch self {
            case .pending, .running: true
            default: false
            }
        }
    }

    /// The `Idempotency-Key` of the request.
    public var id: String
    public var pier: String
    public var operation: String
    public var state: State
    public var correlationID: String?
    public var startedAt: Date
}

/// What has been asked of the Master and what became of it. A pure value, so its rules are simply testable.
public struct OperationLedger: Sendable, Equatable {
    public private(set) var records: [OperationRecord] = []
    /// How many requests went out with each key: the proof that nothing was re-sent.
    public private(set) var sentCounts: [String: Int] = [:]
    /// A finish that arrived before the HTTP answer that names its correlation id.
    private var earlyFinishes: [String: OperationEvent] = [:]
    static let limit = 100

    public init() {}

    public func record(_ id: String) -> OperationRecord? { records.first { $0.id == id } }

    public func latest(pier: String, operation: String) -> OperationRecord? {
        records.last { $0.pier == pier && $0.operation == operation }
    }

    public mutating func sent(id: String, pier: String, operation: String, at time: Date) {
        records.append(OperationRecord(id: id, pier: pier, operation: operation, state: .pending, startedAt: time))
        sentCounts[id, default: 0] += 1
        if records.count > Self.limit { records.removeFirst(records.count - Self.limit) }
    }

    /// Drops a record that only produced a request for confirmation: nothing happened yet.
    public mutating func forget(_ id: String) { records.removeAll { $0.id == id } }

    public mutating func update(_ id: String, _ change: (inout OperationRecord) -> Void) {
        guard let index = records.firstIndex(where: { $0.id == id }) else { return }
        change(&records[index])
    }

    /// The Master's HTTP answer. It is authoritative even if it arrives after the stream dropped.
    public mutating func answered(_ id: String, _ result: OpResult, longRunning: Bool) {
        var early: OperationEvent?
        if case .ok(_, let correlation?) = result { early = earlyFinishes.removeValue(forKey: correlation) }
        update(id) { record in
            switch result {
            case .ok(let value, let correlation):
                record.correlationID = correlation
                if let early {
                    record.state = Self.finalState(of: early)
                    return
                }
                // Only keep a record open when the stream can close it.
                if longRunning, correlation != nil {
                    if case .done = record.state { return }  // op.finished overtook the HTTP answer
                    record.state = .running(progress: nil)
                } else {
                    record.state = .done(value)
                }
            case .refused(let reason, _, _, _): record.state = .refused(reason)
            case .invalid(let path, let reason): record.state = .invalid(path: path, reason: reason)
            case .unavailable(let reason): record.state = .unavailable(reason)
            case .busy(let reason): record.state = .busy(reason)
            case .unreachable(let reason): record.state = .unreachable(reason)
            case .confirmationRequired: break  // handled before this
            case .connectionLost: record.state = .outcomeUnknown
            }
        }
    }

    /// The stream dropped: everything in flight is now unknown.
    public mutating func connectionLost() {
        for index in records.indices where records[index].state.isOpen {
            records[index].state = .outcomeUnknown
        }
    }

    public mutating func apply(_ event: OperationEvent) {
        guard
            let index = records.firstIndex(where: {
                ($0.correlationID != nil && $0.correlationID == event.correlationID)
                    || (event.requestID != nil && $0.id == event.requestID)
            })
        else {
            if event.phase == "op.finished" {
                if earlyFinishes.count >= Self.limit { earlyFinishes.removeAll() }
                earlyFinishes[event.correlationID] = event
            }
            return
        }
        records[index].correlationID = event.correlationID
        switch event.phase {
        case "op.started":
            if records[index].state.isOpen { records[index].state = .running(progress: nil) }
        case "op.progress":
            records[index].state = .running(progress: event.progress)
        case "op.finished":
            records[index].state = Self.finalState(of: event)
        default: break
        }
    }

    private static func finalState(of event: OperationEvent) -> OperationRecord.State {
        let reason = event.reason ?? "The Master gave no reason."
        switch event.outcome {
        case "refused": return .refused(reason)
        case "invalid": return .invalid(path: nil, reason: reason)
        case "unavailable": return .unavailable(reason)
        case "busy": return .busy(reason)
        case "unreachable": return .unreachable(reason)
        default: return .done(nil)
        }
    }
}

/// A question to put to the user before anything is sent (ADR-APP-005).
public struct ConfirmationRequest: Sendable, Equatable, Identifiable {
    public var id: String { "\(pier)/\(operation)/\(token ?? "local")" }
    public var pier: String
    public var operation: String
    public var args: [String: JSONValue]
    public var title: String
    /// The arguments in words.
    public var details: [String]
    /// What the Master says this will do.
    public var consequence: String?
    /// The Master's single-use token, when it asked; nil when only the client's own list asks.
    public var token: String?
}

public enum RequestAnswer: Sendable, Equatable {
    case confirm(ConfirmationRequest)
    case sent(OperationRecord)
    case notAllowed(String)
    case invalid(path: String, reason: String)
}

public enum ControlPolicy {
    /// Operations that always get a dialog here, whatever the Master's class says (`MAPP-CTL-040`). A minimum:
    /// whatever the Master marks `confirm` also prompts.
    public static let alwaysConfirm: Set<String> = [
        "camera.abort", "imaging.stop", "session.stop", "mount.park", "mount.unpark", "flip.now",
    ]

    static func title(for operation: OperationInfo?, name: String) -> String {
        if let description = operation?.description, !description.isEmpty {
            // "Park the mount (asks for a second confirmation)": the note is for developers, not for this dialog.
            return description.components(separatedBy: " (").first ?? description
        }
        return name.replacingOccurrences(of: ".", with: " ").replacingOccurrences(of: "_", with: " ").capitalized
    }

    static func details(_ args: [String: JSONValue], operation: OperationInfo?) -> [String] {
        args.keys.sorted().map { key in
            let unit = operation?.arguments.first { $0.name == key }?.unit
            return "\(key.replacingOccurrences(of: "_", with: " ")): \(args[key]!.displayText)\(unit.map { " \($0)" } ?? "")"
        }
    }
}
