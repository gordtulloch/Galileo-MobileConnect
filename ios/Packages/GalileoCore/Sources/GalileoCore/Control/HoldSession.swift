import Foundation

/// Why a hold ended. The UI says so when the Master is what will stop the motion.
public enum HoldStop: Sendable, Equatable {
    case released
    case background
    /// Nothing can reach the Master, so its deadman stops the motion (about 0.6 s).
    case connectionLost
    case leaseLost
    case refused(String)
    case beatFailed
}

/// Held motion (`S-CTL`, `MAPP-DEV-030`, `MAPP-CTL-060`): moves only while keep-alives flow. This owns the timing
/// and the stop conditions; the platform only forwards the press, the release and lifecycle changes.
public actor HoldSession {
    public static let beatInterval = 0.25  // HoldManager.DEFAULT_BEAT_S

    public enum State: Sendable, Equatable {
        case idle, starting, holding
        case stopped(HoldStop)
    }

    public typealias Invoke = @Sendable (_ operation: String, _ args: [String: JSONValue]) async -> OpResult

    private let family: String
    private let invoke: Invoke
    private let clock: any LinkClock
    private let onChange: @Sendable (State) async -> Void
    private var holdID: String?
    private var beats: Task<Void, Never>?
    private var finished = false
    public private(set) var state: State = .idle

    /// `family` is the operation prefix, for example `focus` for `focus.hold_start`.
    public init(
        family: String, clock: any LinkClock = SystemClock(), invoke: @escaping Invoke,
        onChange: @escaping @Sendable (State) async -> Void = { _ in }
    ) {
        self.family = family
        self.clock = clock
        self.invoke = invoke
        self.onChange = onChange
    }

    private func set(_ new: State) async {
        state = new
        await onChange(new)
    }

    public func start(args: [String: JSONValue]) async {
        guard state == .idle else { return }
        await set(.starting)
        let result = await invoke("\(family).hold_start", args)
        // Released while the start was in flight: the stop below still needs the id.
        guard case .ok(let value, _) = result, let id = value?.stringValue ?? value?["hold_id"]?.stringValue else {
            if case .refused(let reason, _, _, _) = result {
                await finish(.refused(reason))
            } else if case .unavailable(let reason) = result {
                await finish(.refused(reason))
            } else {
                await finish(.beatFailed)
            }
            return
        }
        holdID = id
        if finished {
            _ = await invoke("\(family).hold_stop", ["hold_id": .string(id)])
            return
        }
        await set(.holding)
        beats = Task { await beatLoop(id: id) }
    }

    private func beatLoop(id: String) async {
        while !Task.isCancelled {
            do { try await clock.sleep(seconds: Self.beatInterval) } catch { return }
            if Task.isCancelled { return }
            let result = await invoke("\(family).hold_beat", ["hold_id": .string(id)])
            if Task.isCancelled { return }
            switch result {
            case .ok(let value, _) where value?.boolValue != false:
                continue
            case .connectionLost:
                await stop(.connectionLost)
                return
            default:
                // The Master no longer knows the hold, or refused the beat: this hold is over either way.
                await stop(.beatFailed)
                return
            }
        }
    }

    /// Called on release, leaving the foreground, connection loss, lease loss, or any beat error.
    public func stop(_ reason: HoldStop) async {
        guard !finished else { return }
        beats?.cancel()
        beats = nil
        if let holdID, reason != .connectionLost {
            await finish(reason)
            _ = await invoke("\(family).hold_stop", ["hold_id": .string(holdID)])
        } else {
            await finish(reason)
        }
    }

    private func finish(_ reason: HoldStop) async {
        finished = true
        await set(.stopped(reason))
    }
}
