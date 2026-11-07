import Foundation

/// Opens the event stream. The URLSession form is below; `S-MOCK` is the other implementation.
public protocol StreamTransport: Sendable {
    /// Frames as they arrive. The sequence ends, with or without an error, when the connection does.
    func connect(epoch: String?, last: Int?) async throws -> AsyncThrowingStream<Data, any Error>
}

public enum ConnectionState: Sendable, Equatable {
    case connecting
    case connected
    /// Lost, and trying again.
    case reconnecting
    /// Still trying, but it has been failing for a while: say so.
    case unreachable
}

public enum LinkEvent: Sendable, Equatable {
    case connection(ConnectionState)
    case message(StreamMessage)
    /// The stream dropped; whatever was in flight is now of unknown outcome.
    case dropped
    /// Terminal: the loop has stopped.
    case stopped(LinkError)
}

/// The reconnect loop (`S-LINK`, `MAPP-CON-070`): exponential back-off with jitter, an immediate attempt when the
/// app returns to the foreground, and the last `(epoch, seq)` passed on every attempt. The client does not poll.
public actor StreamLink {
    public struct Backoff: Sendable {
        var initial: Double = 1
        var maximum: Double = 30
        /// Failed attempts before `reconnecting` is reported as `unreachable`.
        var unreachableAfter: Int = 4
        var jitter: @Sendable () -> Double = { Double.random(in: 0.8...1.2) }

        public init() {}

        func delay(attempt: Int) -> Double {
            min(maximum, initial * pow(2, Double(max(0, attempt)))) * jitter()
        }
    }

    private let transport: any StreamTransport
    private let clock: any LinkClock
    private let backoff: Backoff
    private let prepare: @Sendable () async throws -> Void
    private let onEvent: @Sendable (LinkEvent) async -> Void
    private let onStartStop: @Sendable (ObservatorySession.StartStop) async -> Void

    private var epoch: String?
    private var last: Int?
    private var loop: Task<Void, Never>?
    private var wake: Task<Void, any Error>?

    init(
        transport: any StreamTransport, clock: any LinkClock = SystemClock(), backoff: Backoff = Backoff(),
        prepare: @escaping @Sendable () async throws -> Void = {},
        onStartStop: @escaping @Sendable (ObservatorySession.StartStop) async -> Void = { _ in },
        onEvent: @escaping @Sendable (LinkEvent) async -> Void
    ) {
        self.transport = transport
        self.clock = clock
        self.backoff = backoff
        self.prepare = prepare
        self.onStartStop = onStartStop
        self.onEvent = onEvent
    }

    public func start() {
        guard loop == nil else { return }
        loop = Task { await run() }
    }

    public func stop() {
        loop?.cancel()
        loop = nil
        wake?.cancel()
    }

    /// The app returned to the foreground, or the network came back: don't wait out the back-off.
    public func reconnectNow() {
        wake?.cancel()
    }

    private func run() async {
        var failures = 0
        var everConnected = false
        while !Task.isCancelled {
            await onEvent(.connection(everConnected ? (failures >= backoff.unreachableAfter ? .unreachable : .reconnecting) : .connecting))
            do {
                try await prepare()
                let frames = try await transport.connect(epoch: epoch, last: last)
                var announced = false
                for try await data in frames {
                    if Task.isCancelled { return }
                    guard let message = StreamMessage(data: data) else { continue }
                    if !announced {
                        announced = true
                        everConnected = true
                        failures = 0
                        await onEvent(.connection(.connected))
                    }
                    track(message)
                    await onEvent(.message(message))
                }
            } catch let error as LinkError where error == .unpaired || error == .identityChanged {
                await onEvent(.stopped(error))
                return
            } catch let refusal as ObservatorySession.StartStop {
                await onStartStop(refusal)
                return
            } catch {
                // Any other failure is an ordinary drop, slow-client disconnects included.
            }
            if Task.isCancelled { return }
            await onEvent(.dropped)
            everConnected = true
            await onEvent(.connection(failures >= backoff.unreachableAfter ? .unreachable : .reconnecting))
            let delay = backoff.delay(attempt: failures)
            failures += 1
            let sleeper = Task { [clock] in try await clock.sleep(seconds: delay) }
            wake = sleeper
            _ = try? await sleeper.value
        }
    }

    private func track(_ message: StreamMessage) {
        switch message {
        case .hello(let newEpoch, let seq):
            if epoch != newEpoch || last == nil {
                epoch = newEpoch
                last = seq
            }
        case .resync(let newEpoch, let seq):
            epoch = newEpoch
            last = seq
        case .frame(let seq, _):
            last = max(last ?? 0, seq)
        }
    }
}

/// The real stream: a WebSocket on the same trust configuration as the API client.
public struct WebSocketStreamTransport: StreamTransport {
    private let session: URLSession
    private let baseURL: URL
    private let token: @Sendable () -> String?
    private let identityChanged: @Sendable () -> Bool

    public init(
        session: URLSession, baseURL: URL, token: @escaping @Sendable () -> String?,
        identityChanged: @escaping @Sendable () -> Bool
    ) {
        self.session = session
        self.baseURL = baseURL
        self.token = token
        self.identityChanged = identityChanged
    }

    static func url(baseURL: URL, epoch: String?, last: Int?) -> URL? {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else { return nil }
        components.scheme = "wss"
        components.path = "/connect/v1/stream"
        if let epoch, let last {
            components.queryItems = [.init(name: "epoch", value: epoch), .init(name: "last", value: String(last))]
        }
        return components.url
    }

    public func connect(epoch: String?, last: Int?) async throws -> AsyncThrowingStream<Data, any Error> {
        guard let url = Self.url(baseURL: baseURL, epoch: epoch, last: last) else {
            throw LinkError.unexpected("The Master's address can't form a stream address.")
        }
        var request = URLRequest(url: url)
        if let token = token() { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let task = session.webSocketTask(with: request)
        task.resume()
        let identityChanged = self.identityChanged
        return AsyncThrowingStream { continuation in
            let reader = Task {
                do {
                    while !Task.isCancelled {
                        switch try await task.receive() {
                        case .string(let text): continuation.yield(Data(text.utf8))
                        case .data(let data): continuation.yield(data)
                        @unknown default: break
                        }
                    }
                    continuation.finish()
                } catch {
                    if identityChanged() {
                        continuation.finish(throwing: LinkError.identityChanged)
                    } else if (task.response as? HTTPURLResponse)?.statusCode == 401 {
                        continuation.finish(throwing: LinkError.unpaired)
                    } else {
                        continuation.finish(throwing: error)
                    }
                }
            }
            continuation.onTermination = { _ in
                reader.cancel()
                task.cancel(with: .goingAway, reason: nil)
            }
        }
    }
}
