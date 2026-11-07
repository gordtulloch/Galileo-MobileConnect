import Foundation
import GalileoAPI
import HTTPTypes
import Testing

@testable import GalileoCore

/// A clock only the test moves.
final class TestClock: LinkClock, @unchecked Sendable {
    private struct Sleeper {
        let id: UUID
        let deadline: Date
        let continuation: CheckedContinuation<Void, any Error>
    }

    private let state: Locked<(now: Date, sleepers: [Sleeper])>

    init(start: Date = Date(timeIntervalSince1970: 1_800_000_000)) { state = Locked((start, [])) }

    func now() -> Date { state.current.now }

    func sleep(seconds: Double) async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                state.withLock { $0.sleepers.append(Sleeper(id: id, deadline: $0.now.addingTimeInterval(seconds), continuation: continuation)) }
            }
        } onCancel: {
            let sleeper = state.withLock { value -> Sleeper? in
                guard let index = value.sleepers.firstIndex(where: { $0.id == id }) else { return nil }
                return value.sleepers.remove(at: index)
            }
            sleeper?.continuation.resume(throwing: CancellationError())
        }
    }

    func advance(_ seconds: Double) {
        let due = state.withLock { value -> [Sleeper] in
            value.now = value.now.addingTimeInterval(seconds)
            let now = value.now
            let due = value.sleepers.filter { $0.deadline <= now }
            value.sleepers.removeAll { $0.deadline <= now }
            return due
        }
        for sleeper in due { sleeper.continuation.resume() }
    }
}

/// Waits for an asynchronous condition. The client is concurrent, so vectors poll what they expect.
func eventually(
    isolation: isolated (any Actor)? = #isolation, timeout: Double = 3, _ condition: () async -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if await condition() { return true }
        try? await Task.sleep(nanoseconds: 5_000_000)
    }
    return await condition()
}

let testIdentity = ClientIdentity(deviceName: "Vector iPad", appVersion: "0.1.0", platform: "ipados", clientClass: "tablet")

enum Paths {
    /// ios/Packages/GalileoCore/Tests/GalileoCoreTests/Support.swift -> repository root
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    static let vectors = root.appending(path: "api/vectors")
    static let scenarios = root.appending(path: "api/scenarios")
}

func realCatalogue() throws -> [OperationInfo] {
    let data = try Data(contentsOf: Paths.scenarios.appending(path: "catalogue.json"))
    let json = try JSONDecoder().decode(JSONValue.self, from: data)
    return try (json["operations"]?.arrayValue ?? []).compactMap {
        MasterAPI.operationInfo(try $0.converted(to: Components.Schemas.Operation.self))
    }
}
