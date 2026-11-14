import Foundation
import Testing

@testable import GalileoCore

@Suite("Saved targets (MCON-1340)")
struct TargetsTests {
    private func api(_ scenario: String = "normal-night") throws -> MasterAPI {
        let master = try MockMaster(scenarioNamed: scenario)
        return MasterConnection.demo(master, identity: testIdentity, token: MockMaster.token).api
    }

    @Test("The list arrives with every field and a version")
    func listsTheTargets() async throws {
        let list = try await api().savedTargets()
        #expect(list.targets.map(\.name) == ["M42", "M31"])
        let m31 = try #require(list.targets.last)
        #expect(m31.designations == ["NGC 224"] && m31.type == "Galaxy")
        #expect(m31.raDeg == 10.685 && m31.decDeg == 41.269 && m31.magnitude == 3.4 && m31.sizeArcmin == 178)
        #expect(!list.version.isEmpty)
    }

    @Test("Removing an object takes it off and changes the version")
    func removes() async throws {
        let api = try api()
        let before = try await api.savedTargets()
        let result = await api.removeTarget(name: "M42")
        #expect(result.removed)
        let after = try await api.savedTargets()
        #expect(after.targets.map(\.name) == ["M31"] && after.version != before.version)
    }

    @Test("A Viewer is refused, and the list is unchanged")
    func viewerCannotRemove() async throws {
        let api = try api("viewer")
        let result = await api.removeTarget(name: "M42")
        #expect(!result.removed && result.reason?.contains("Operator") == true)
        #expect(try await api.savedTargets().targets.count == 2)
    }

    @Test("The tile says what the catalog doesn't know")
    func summary() {
        #expect(SavedTarget(name: "M31", raDeg: 0, decDeg: 0, magnitude: 3.4, sizeArcmin: 178).summary == "mag 3.4  ·  178.0′")
        #expect(SavedTarget(name: "X", raDeg: 0, decDeg: 0).summary == "mag unknown")
    }
}
