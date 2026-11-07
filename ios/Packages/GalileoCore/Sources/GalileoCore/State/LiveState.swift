import Foundation

public struct Stamped: Sendable, Equatable {
    public var value: JSONValue
    public var asOf: Date

    public init(value: JSONValue, asOf: Date) {
        self.value = value
        self.asOf = asOf
    }
}

public struct SeriesPoint: Sendable, Equatable {
    public var value: JSONValue
    public var asOf: Date
}

/// One span of a Dashboard activity lane: `state` from `start` until the next segment.
public struct LaneSegment: Sendable, Equatable {
    public var state: String
    public var start: Date
}

public struct PierState: Sendable, Equatable {
    /// Leaves by dotted key, for example `mount.tracking`. The Master publishes such fragments
    /// (`galileo.control_surface.state`), so a key is the natural unit of state and of staleness.
    public var values: [String: Stamped] = [:]
    public var series: [String: [SeriesPoint]] = [:]
    public var lanes: [String: [LaneSegment]] = [:]
    public var leaseHolder: String?
    public var access: String?
    public var reachable: Bool?
    public var reason: String?

    public init() {}
}

public enum LivePhase: String, Sendable, Equatable {
    /// Connected, or connecting, with no snapshot yet.
    case loading
    case live
    /// The Master could not replay the gap; state was discarded and a new snapshot is on its way.
    case resyncing
}

/// Everything the Master has said, and nothing else (ADR-APP-002). Changed only by `reduce`.
public struct LiveState: Sendable, Equatable {
    public var phase: LivePhase = .loading
    public var epoch: String?
    public var lastSeq: Int?
    public var piers: [String: PierState] = [:]
    public var alerts: [AlertEvent] = []
    public var closingReason: String?
    var buffered: [StreamEvent] = []

    public init() {}

    static let seriesKeys: Set<String> = ["guiding.error", "focus.vcurve"]
    static let seriesLimit = 300
    static let laneLimit = 200
    static let alertLimit = 200
    static let lanePrefix = "activity."

    /// A pure function of its inputs, with no clock or I/O: what makes the shared vectors possible (`S-STATE`).
    public static func reduce(_ state: LiveState, _ message: StreamMessage) -> LiveState {
        var next = state
        switch message {
        case .hello(let epoch, let seq):
            // A resuming hello reports the head of the stream and replay follows, so the position only moves on
            // the frames themselves.
            if next.epoch != epoch || next.lastSeq == nil {
                next.epoch = epoch
                next.lastSeq = seq
            }
        case .resync(let epoch, let seq):
            next.epoch = epoch
            next.lastSeq = seq
            next.phase = .resyncing
            next.piers = next.piers.mapValues { old in
                var kept = PierState()
                (kept.access, kept.reachable, kept.reason) = (old.access, old.reachable, old.reason)
                return kept
            }
            next.buffered = []
        case .frame(let seq, let event):
            if let last = next.lastSeq, seq <= last { return next }  // a replayed duplicate
            next.lastSeq = seq
            next.apply(event)
        }
        return next
    }

    private mutating func apply(_ event: StreamEvent) {
        switch event {
        case .snapshot(let asOf, let snapshot):
            closingReason = nil
            var piers: [String: PierState] = [:]
            for (id, content) in snapshot {
                var pier = self.piers[id] ?? PierState()
                pier.values = [:]
                pier.series = self.piers[id]?.series ?? [:]
                pier.lanes = self.piers[id]?.lanes ?? [:]
                Self.flatten(content, prefix: "", into: &pier.values, asOf: asOf)
                if let holder = pier.values["lease.holder"] { pier.leaseHolder = holder.value.stringValue }
                for key in pier.values.keys.filter({ $0.hasPrefix(Self.lanePrefix) }) {
                    Self.addLaneSegment(to: &pier, key: key, value: pier.values[key]!.value, at: asOf)
                }
                piers[id] = pier
            }
            self.piers = piers
            phase = .live
            let waiting = buffered
            buffered = []
            for case .delta(let id, let key, let value, let at) in waiting where at >= asOf {
                applyDelta(pier: id, key: key, value: value, asOf: at)
            }
        case .delta(let pier, let key, let value, let asOf):
            if phase == .live {
                applyDelta(pier: pier, key: key, value: value, asOf: asOf)
            } else {
                buffered.append(event)
            }
        case .alert(let alert):
            alerts.removeAll { $0.id == alert.id }
            alerts.insert(alert, at: 0)
            if alerts.count > Self.alertLimit { alerts.removeLast(alerts.count - Self.alertLimit) }
        case .lease(let pier, let holder):
            piers[pier, default: PierState()].leaseHolder = holder
        case .access(let pier, let access, let reachable, let reason):
            piers[pier, default: PierState()].access = access
            piers[pier, default: PierState()].reachable = reachable
            piers[pier, default: PierState()].reason = reason
        case .closing(let reason):
            closingReason = reason
        case .operation, .unknown:
            break  // operation events are the ledger's (S-CTL)
        }
    }

    private mutating func applyDelta(pier id: String, key: String, value: JSONValue, asOf: Date) {
        var pier = piers[id] ?? PierState()
        // A delta replaces the whole subtree at its key.
        for existing in pier.values.keys where existing == key || existing.hasPrefix(key + ".") {
            pier.values.removeValue(forKey: existing)
        }
        Self.flatten(value, prefix: key, into: &pier.values, asOf: asOf)
        if Self.seriesKeys.contains(key) {
            pier.series[key, default: []].append(SeriesPoint(value: value, asOf: asOf))
            if pier.series[key]!.count > Self.seriesLimit {
                pier.series[key]!.removeFirst(pier.series[key]!.count - Self.seriesLimit)
            }
        }
        if key.hasPrefix(Self.lanePrefix) { Self.addLaneSegment(to: &pier, key: key, value: value, at: asOf) }
        piers[id] = pier
    }

    private static func addLaneSegment(to pier: inout PierState, key: String, value: JSONValue, at time: Date) {
        let lane = String(key.dropFirst(lanePrefix.count))
        let state = value.displayText
        if pier.lanes[lane]?.last?.state == state { return }
        pier.lanes[lane, default: []].append(LaneSegment(state: state, start: time))
        if pier.lanes[lane]!.count > laneLimit { pier.lanes[lane]!.removeFirst(pier.lanes[lane]!.count - laneLimit) }
    }

    private static func flatten(_ value: JSONValue, prefix: String, into values: inout [String: Stamped], asOf: Date) {
        if case .object(let object) = value, !object.isEmpty {
            for (key, child) in object {
                flatten(child, prefix: prefix.isEmpty ? key : "\(prefix).\(key)", into: &values, asOf: asOf)
            }
        } else if !prefix.isEmpty {
            values[prefix] = Stamped(value: value, asOf: asOf)
        }
    }
}

/// A value as the UI may show it: never stale as if live (`MAPP-OVR-040`).
public struct ValueView: Sendable, Equatable {
    public var value: JSONValue
    public var age: TimeInterval
    public var stale: Bool
}

/// The Master publishes a value only when it changes (`ObservableState.publish`), so most keys must not age out
/// while the stream is up: silence means "unchanged". Only values that move continuously have a limit.
public enum FreshnessLimits {
    static let prefixes: [(prefix: String, seconds: TimeInterval)] = [
        ("mount.position", 10), ("guiding.error", 15), ("weather.", 300),
    ]

    public static func limit(for key: String) -> TimeInterval? {
        prefixes.first { key.hasPrefix($0.prefix) }?.seconds
    }
}

extension LiveState {
    public func view(pier: String, key: String, now: Date, connected: Bool) -> ValueView? {
        guard let stamped = piers[pier]?.values[key] else { return nil }
        let age = max(0, now.timeIntervalSince(stamped.asOf))
        let overLimit = FreshnessLimits.limit(for: key).map { age > $0 } ?? false
        return ValueView(value: stamped.value, age: age, stale: !connected || overLimit)
    }
}
