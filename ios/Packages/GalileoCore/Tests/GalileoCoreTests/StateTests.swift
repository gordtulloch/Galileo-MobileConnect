import Foundation
import Testing

@testable import GalileoCore

private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

private func reduce(_ messages: [StreamMessage], from start: LiveState = LiveState()) -> LiveState {
    messages.reduce(start, LiveState.reduce)
}

private let snapshotted: [StreamMessage] = [
    .hello(epoch: "e", seq: 0),
    .frame(seq: 1, event: .snapshot(asOf: t0, piers: ["p": ["mount": ["tracking": true], "weather.wind": 3]])),
]

@Suite("Live state (S-STATE)")
struct StateTests {
    @Test("MAPP-OVR-050: snapshot, then deltas, with dotted keys either nested or flat")
    func snapshotThenDelta() {
        let state = reduce(snapshotted + [.frame(seq: 2, event: .delta(pier: "p", key: "mount.tracking", value: false, asOf: t0))])
        #expect(state.phase == .live)
        #expect(state.piers["p"]?.values["mount.tracking"]?.value == false)
        #expect(state.piers["p"]?.values["weather.wind"]?.value == 3)
    }

    @Test("A delta replaces the subtree at its key")
    func deltaReplacesSubtree() {
        let state = reduce([
            .hello(epoch: "e", seq: 0),
            .frame(seq: 1, event: .snapshot(asOf: t0, piers: ["p": ["safety.inputs": ["Rain": "Dry", "Wind": "8"]]])),
            .frame(seq: 2, event: .delta(pier: "p", key: "safety.inputs", value: ["Rain": "Wet"], asOf: t0)),
        ])
        #expect(state.piers["p"]?.values["safety.inputs.Rain"]?.value == "Wet")
        #expect(state.piers["p"]?.values["safety.inputs.Wind"] == nil)
    }

    @Test("A buffered delta older than the snapshot must not overwrite it")
    func oldBufferedDeltaIsDropped() {
        let state = reduce([
            .hello(epoch: "e", seq: 0),
            .frame(seq: 1, event: .delta(pier: "p", key: "a", value: "old", asOf: t0)),
            .frame(seq: 2, event: .snapshot(asOf: t0.addingTimeInterval(1), piers: ["p": ["a": "new"]])),
        ])
        #expect(state.piers["p"]?.values["a"]?.value == "new")
    }

    @Test("A replayed frame is ignored")
    func duplicate() {
        let state = reduce(
            snapshotted + [
                .frame(seq: 2, event: .delta(pier: "p", key: "a", value: 2, asOf: t0)),
                .hello(epoch: "e", seq: 2),
                .frame(seq: 2, event: .delta(pier: "p", key: "a", value: 99, asOf: t0)),
            ])
        #expect(state.piers["p"]?.values["a"]?.value == 2)
        #expect(state.lastSeq == 2)
    }

    @Test("MAPP-OVR-040: resync discards values and keeps stated access")
    func resync() {
        let state = reduce(
            snapshotted + [
                .frame(seq: 2, event: .access(pier: "p", access: "view", reachable: false, reason: "offline")),
                .resync(epoch: "e2", seq: 50),
            ])
        #expect(state.phase == .resyncing)
        #expect(state.piers["p"]?.values.isEmpty == true)
        #expect(state.piers["p"]?.reason == "offline")
        #expect(state.epoch == "e2")
    }

    @Test("MAPP-OVR-040: anything is stale while the link is down; only moving values age while it is up")
    func staleness() throws {
        let state = reduce(snapshotted)
        let later = t0.addingTimeInterval(3600)
        #expect(try #require(state.view(pier: "p", key: "mount.tracking", now: later, connected: true)).stale == false)
        #expect(try #require(state.view(pier: "p", key: "mount.tracking", now: t0, connected: false)).stale == true)
        #expect(try #require(state.view(pier: "p", key: "weather.wind", now: later, connected: true)).stale == true)
        #expect(try #require(state.view(pier: "p", key: "weather.wind", now: t0.addingTimeInterval(5), connected: true)).age == 5)
        // A Master clock ahead of the device must not produce a negative age.
        #expect(try #require(state.view(pier: "p", key: "weather.wind", now: t0.addingTimeInterval(-9), connected: true)).age == 0)
    }

    @Test("MAPP-OVR-020: activity deltas build lanes, and repeats don't add segments")
    func lanes() {
        let state = reduce(
            snapshotted + [
                .frame(seq: 2, event: .delta(pier: "p", key: "activity.mount", value: "Slewing", asOf: t0)),
                .frame(seq: 3, event: .delta(pier: "p", key: "activity.mount", value: "Slewing", asOf: t0.addingTimeInterval(1))),
                .frame(seq: 4, event: .delta(pier: "p", key: "activity.mount", value: "Tracking", asOf: t0.addingTimeInterval(2))),
            ])
        #expect(state.piers["p"]?.lanes["mount"]?.map(\.state) == ["Slewing", "Tracking"])
    }

    @Test("MAPP-GUI-020: the guide trace keeps a bounded history")
    func series() {
        let frames = (2...(LiveState.seriesLimit + 20)).map {
            StreamMessage.frame(seq: $0, event: .delta(pier: "p", key: "guiding.error", value: ["ra": 0.1], asOf: t0))
        }
        #expect(reduce(snapshotted + frames).piers["p"]?.series["guiding.error"]?.count == LiveState.seriesLimit)
    }

    @Test("MAPP-OVR-060: a new alert goes first, and a repeated id replaces")
    func alerts() {
        let a = AlertEvent(id: "1", severity: "info", time: t0, message: "first")
        let b = AlertEvent(id: "2", severity: "critical", time: t0, message: "second")
        let state = reduce([.frame(seq: 1, event: .alert(a)), .frame(seq: 2, event: .alert(b)), .frame(seq: 3, event: .alert(a))])
        #expect(state.alerts.map(\.id) == ["1", "2"])
    }

    @Test("Messages decode, and an unknown type is not an error")
    func decoding() throws {
        let unknown = try #require(StreamMessage(data: Data(#"{"seq":3,"data":{"type":"brand_new"}}"#.utf8)))
        #expect(unknown == .frame(seq: 3, event: .unknown))
        #expect(StreamMessage(data: Data("not json".utf8)) == nil)
        let python = try #require(
            StreamMessage(
                data: Data(#"{"seq":1,"data":{"type":"delta","pier":"p","key":"k","value":1,"as_of":"2026-11-06T02:00:00.123456+00:00"}}"#.utf8)))
        guard case .frame(_, .delta) = python else {
            Issue.record("a Python isoformat() timestamp must decode: \(python)")
            return
        }
    }
}
