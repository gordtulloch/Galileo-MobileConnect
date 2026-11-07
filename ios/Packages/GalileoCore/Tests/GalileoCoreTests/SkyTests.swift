import Foundation
import Testing
import simd

@testable import GalileoCore

@Suite("Sky (S-SKY)")
struct SkyTests {
    @Test("MAPP-STR-070: core's own encoder's bytes decode exactly")
    func decodesCoresFrame() throws {
        let data = try Data(contentsOf: Paths.vectors.appending(path: "sky/frame-v1.bin"))
        let expected = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: Paths.vectors.appending(path: "sky/frame-v1.expected.json")))
        let objects = try SkyFrameDecoder.decode(data)
        let wanted = try #require(expected["objects"]?.arrayValue)
        #expect(objects.count == wanted.count)
        for (object, want) in zip(objects, wanted) {
            #expect(object.index == Int32(want["index"]?.intValue ?? -1))
            #expect(abs(Double(object.raDeg) - (want["ra"]?.doubleValue ?? .nan)) < 0.001)
            #expect(abs(Double(object.decDeg) - (want["dec"]?.doubleValue ?? .nan)) < 0.001)
            #expect(abs(Double(object.magnitude) - (want["mag"]?.doubleValue ?? .nan)) < 0.01)
            #expect(Int(object.kind) == want["kind"]?.intValue)
        }
    }

    @Test("Our encoder matches core's byte for byte, so the mock is honest")
    func encoderMatches() throws {
        let data = try Data(contentsOf: Paths.vectors.appending(path: "sky/frame-v1.bin"))
        #expect(SkyFrameDecoder.encode(try SkyFrameDecoder.decode(data)) == data)
    }

    @Test("A frame that isn't one is refused rather than crashing")
    func badFrames() throws {
        #expect(throws: SkyFrameError.notASkyFrame) { try SkyFrameDecoder.decode(Data("nope".utf8)) }
        #expect(throws: SkyFrameError.unsupportedEncoding(9)) { try SkyFrameDecoder.decode(Data([0x47, 0x53, 0x4B, 9, 1, 0, 0, 0, 0])) }
        #expect(throws: SkyFrameError.truncated) { try SkyFrameDecoder.decode(Data([0x47, 0x53, 0x4B, 1, 1, 5, 0, 0, 0, 0x02])) }
    }

    private let site = SkySite(latitude: 49.9, longitude: -97.1)
    private let night = Date(timeIntervalSince1970: 1_793_930_400)  // 2026-11-06T02:00:00Z

    @Test("Sidereal time against a known value: J2000.0 at Greenwich is 280.46°")
    func sidereal() {
        let j2000 = Date(timeIntervalSince1970: 946_728_000)
        #expect(abs(SkyGeometry.siderealDegrees(at: j2000, longitude: 0) - 280.46) < 0.01)
        #expect(abs(SkyGeometry.siderealDegrees(at: j2000, longitude: -90) - 190.46) < 0.01)
    }

    @Test("Polaris stands at about the site's latitude, due north, whenever you look")
    func polaris() {
        for hours in stride(from: 0.0, to: 24, by: 6) {
            let place = SkyGeometry.horizontal(raDeg: 37.95, decDeg: 89.264, site: site, at: night.addingTimeInterval(hours * 3600))
            #expect(abs(place.altitude - 49.9) < 1)
        }
    }

    @Test("Horizontal and equatorial are inverses")
    func roundTrip() {
        let place = SkyGeometry.horizontal(raDeg: 83.82, decDeg: -5.39, site: site, at: night)
        let back = SkyGeometry.equatorial(altitude: place.altitude, azimuth: place.azimuth, site: site, at: night)
        #expect(abs(back.ra - 83.82) < 0.01 && abs(back.dec + 5.39) < 0.01)
    }

    @Test("The GPU's matrix puts a star where the formula does")
    func matrixAgrees() {
        let m = SkyGeometry.equatorialToHorizon(site: site, at: night)
        for (ra, dec) in [(83.82, -5.39), (250.4, 36.46), (10.7, 41.27)] {
            let v = m * SkyGeometry.unitVector(raDeg: Float(ra), decDeg: Float(dec))
            let place = SkyGeometry.horizontal(raDeg: ra, decDeg: dec, site: site, at: night)
            #expect(abs(asin(Double(v.z)) * 180 / .pi - place.altitude) < 0.01)
            var az = atan2(Double(v.x), Double(v.y)) * 180 / .pi
            if az < 0 { az += 360 }
            #expect(abs(az - place.azimuth) < 0.01)
        }
    }

    @Test("The camera: what's looked at is centred; up is up; east is left when facing south... and it inverts")
    func camera() throws {
        let camera = SkyCamera(altitude: 40, azimuth: 180, fieldOfView: 60)
        let centre = try #require(camera.project(altitude: 40, azimuth: 180, viewShorterSide: 800))
        #expect(abs(centre.x) < 0.01 && abs(centre.y) < 0.01)
        let above = try #require(camera.project(altitude: 50, azimuth: 180, viewShorterSide: 800))
        #expect(above.y > 0 && abs(above.x) < 0.01)
        // Facing south, east (azimuth 90) is on the left.
        let eastward = try #require(camera.project(altitude: 40, azimuth: 170, viewShorterSide: 800))
        #expect(eastward.x < 0)
        let place = camera.unproject(x: Double(eastward.x), y: Double(eastward.y), viewShorterSide: 800)
        #expect(abs(place.altitude - 40) < 0.01 && abs(place.azimuth - 170) < 0.01)
        // The field of view is what it says: half of it is at the edge of the shorter side.
        let edge = try #require(camera.project(altitude: 70, azimuth: 180, viewShorterSide: 800))
        #expect(abs(edge.y - 400) < 1)
    }

    @Test("Zoom and pan stay in range")
    func cameraBounds() {
        var camera = SkyCamera(altitude: 45, azimuth: 10, fieldOfView: 90)
        camera.zoom(by: 10_000)
        #expect(camera.fieldOfView == SkyCamera.fieldRange.lowerBound)
        camera.zoom(by: 0.000_1)
        #expect(camera.fieldOfView == SkyCamera.fieldRange.upperBound)
        camera.fieldOfView = 90
        camera.pan(deltaX: 0, deltaY: 100_000, viewShorterSide: 800)
        #expect(camera.altitude == 90)
        camera.altitude = 45
        camera.pan(deltaX: 400, deltaY: 0, viewShorterSide: 800)
        #expect(camera.azimuth >= 0 && camera.azimuth < 360)
    }

    @Test("MAPP-STR-010: a narrower view asks for fainter stars")
    func levels() {
        #expect(SkyCamera(fieldOfView: 120).levelOfDetail < SkyCamera(fieldOfView: 30).levelOfDetail)
        #expect(SkyCamera(fieldOfView: 30).levelOfDetail < SkyCamera(fieldOfView: 5).levelOfDetail)
    }

    @Test("MAPP-STR-050: the cache is bounded, short-lived and evicts the least used")
    func cache() {
        var cache = SkyPageCache(maximumObjects: 10, lifetime: 60)
        let t = Date(timeIntervalSince1970: 0)
        let five = Array(repeating: SkyObject(index: 0, raDeg: 0, decDeg: 0, magnitude: 0, kind: 0), count: 5)
        let a = SkyPageCache.Key(ra: 1, dec: 0, radius: 1, level: 1)
        let b = SkyPageCache.Key(ra: 2, dec: 0, radius: 1, level: 1)
        let c = SkyPageCache.Key(ra: 3, dec: 0, radius: 1, level: 1)
        cache.put(a, objects: five, complete: true, now: t)
        cache.put(b, objects: five, complete: true, now: t.addingTimeInterval(1))
        _ = cache.get(a, now: t.addingTimeInterval(2))
        cache.put(c, objects: five, complete: true, now: t.addingTimeInterval(3))
        #expect(cache.objectCount == 10)
        #expect(cache.get(b, now: t.addingTimeInterval(4)) == nil)  // least recently used
        #expect(cache.get(a, now: t.addingTimeInterval(4)) != nil)
        #expect(cache.get(a, now: t.addingTimeInterval(100)) == nil)  // outlived
    }

    @Test("MAPP-STR-020: interpolation between the Master's samples, across RA's wrap")
    func interpolation() {
        let t0 = Date(timeIntervalSince1970: 0)
        let t1 = t0.addingTimeInterval(3600)
        let samples = [
            EphemerisSample(time: t0, bodies: [EphemerisBody(name: "Moon", raDeg: 359, decDeg: 10)]),
            EphemerisSample(time: t1, bodies: [EphemerisBody(name: "Moon", raDeg: 1, decDeg: 20)]),
        ]
        let middle = EphemerisInterpolator.bodies(at: t0.addingTimeInterval(1800), in: samples)
        #expect(abs(middle[0].raDeg) < 0.001 || abs(middle[0].raDeg - 360) < 0.001)
        #expect(middle[0].decDeg == 15)
        #expect(EphemerisInterpolator.bodies(at: t0.addingTimeInterval(-10), in: samples)[0].raDeg == 359)
        #expect(EphemerisInterpolator.bodies(at: t1.addingTimeInterval(10), in: samples)[0].raDeg == 1)
        #expect(EphemerisInterpolator.bodies(at: t0, in: []).isEmpty)
    }

    private actor Collected {
        var updates: [[SkyObject]] = []
        func add(_ value: [SkyObject]) { updates.append(value) }
    }

    @Test("MAPP-STR-040: pages arrive progressively, and each only ever adds")
    func progressive() async throws {
        let master = try MockMaster(scenarioNamed: "normal-night")
        let api = MasterConnection.demo(master, identity: testIdentity, token: MockMaster.token).api
        let store = SkyStore(api: api)
        await store.setRate(perSecond: 100)
        let collected = Collected()
        await store.look(raDeg: 83, decDeg: 0, radius: 180, level: 4) { await collected.add($0) }
        #expect(await eventually(timeout: 8) { await collected.updates.count >= 4 })
        let updates = await collected.updates
        #expect(zip(updates, updates.dropFirst()).allSatisfy { $0.count <= $1.count })
        let first = try #require(updates.first)
        #expect((first.map(\.magnitude).max() ?? 99) < (updates.last?.map(\.magnitude).max() ?? 0), "brightest first")
        await store.stop()
    }

    @Test("MAPP-STR-010: a second look at the same place comes from the cache and asks for nothing")
    func cachedLook() async throws {
        let master = try MockMaster(scenarioNamed: "normal-night")
        let api = MasterConnection.demo(master, identity: testIdentity, token: MockMaster.token).api
        let store = SkyStore(api: api)
        await store.setRate(perSecond: 1000)
        let collected = Collected()
        await store.look(raDeg: 83, decDeg: 0, radius: 20, level: 1) { await collected.add($0) }
        let filled = await eventually(timeout: 5) {
            let updates = await collected.updates.count
            let cached = await store.cachedObjectCount
            return updates >= 1 && cached > 0
        }
        #expect(filled)
        try await Task.sleep(nanoseconds: 100_000_000)
        let before = await master.requests.count
        await store.look(raDeg: 83, decDeg: 0, radius: 20, level: 1) { await collected.add($0) }
        try await Task.sleep(nanoseconds: 100_000_000)
        #expect(await master.requests.count == before)
    }

    @Test("MAPP-SKY-030, MAPP-SKY-040: search, details, the Master's check, and a go-to that asks first")
    func goTo() async throws {
        let master = try MockMaster(scenarioNamed: "normal-night")
        let connection = MasterConnection.demo(master, identity: testIdentity, token: MockMaster.token)
        let session = ObservatorySession(connection: connection)
        await session.start()
        #expect(await eventually { await session.current.live.phase == .live })
        let found = await session.read(pier: "p1", operation: "sky.search", args: ["query": "orion", "limit": 10])
        #expect(found?["objects"]?.arrayValue?.first?["name"] == "M 42")
        let details = await session.read(pier: "p1", operation: "sky.object", args: ["name": "M 42"])
        #expect(details?["rise_transit_set"]?["transit"] != nil)

        let requests = await master.requests.count
        guard case .confirm(let question) = await session.goToQuestion(pier: "p1", name: "M 42", raDeg: 83.822, decDeg: -5.391) else {
            Issue.record("expected a question")
            return
        }
        #expect(question.title == "Slew to M 42")
        #expect(question.details.contains { $0.hasPrefix("Altitude now:") })
        #expect(question.consequence?.contains("horizon") == true)
        let sent = await master.requests.dropFirst(requests)
        #expect(!sent.contains { $0.contains("/ops") }, "nothing is sent until accepted")

        _ = await session.takeLease(pier: "p1")
        guard case .sent(let record) = await session.confirm(question) else {
            Issue.record("expected the slew to go")
            return
        }
        #expect(record.state != .pending)
        await session.stop()
    }

    @Test("MAPP-STR-030: the site and tapped objects come from the Master")
    func siteAndIdentify() async throws {
        let master = try MockMaster(scenarioNamed: "normal-night")
        let api = MasterConnection.demo(master, identity: testIdentity, token: MockMaster.token).api
        guard case .site(let site) = await api.skySite(pier: "p1") else {
            Issue.record("expected the site")
            return
        }
        #expect(site.latitude == 49.9 && site.horizon.count == 5)
        let named = try #require(await api.identify(index: 2500))
        #expect(named.isDeepSky && named.name == "M 42")
        let star = try #require(await api.identify(index: 0))
        #expect(!star.isDeepSky && star.name == nil)
        #expect(await api.identify(index: 999_999) == nil)
        let figures = try #require(await api.constellations())
        #expect(figures.names.contains { $0.name == "Orion" })
        #expect(figures.lines.first?.count == 6)
        // The figures' stars are real and named, after the objects.
        let vega = try #require(await api.identify(index: 2500 + 6 + 7 + 7 + 5 + 5))
        #expect(vega.name == "Vega" && !vega.isDeepSky)
        let samples = try #require(await api.ephemeris(from: Date(), to: Date().addingTimeInterval(3600), stepSeconds: 600))
        #expect(samples.count == 7 && samples[0].bodies.contains { $0.name == "Moon" })
    }
}
