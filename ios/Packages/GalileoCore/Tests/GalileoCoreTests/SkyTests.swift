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
        let centre = try #require(camera.project(altitude: 40, azimuth: 180, viewHeight: 800))
        #expect(abs(centre.x) < 0.01 && abs(centre.y) < 0.01)
        let above = try #require(camera.project(altitude: 50, azimuth: 180, viewHeight: 800))
        #expect(above.y > 0 && abs(above.x) < 0.01)
        // Facing south, east (azimuth 90) is on the left.
        let eastward = try #require(camera.project(altitude: 40, azimuth: 170, viewHeight: 800))
        #expect(eastward.x < 0)
        let place = camera.unproject(x: Double(eastward.x), y: Double(eastward.y), viewHeight: 800)
        #expect(abs(place.altitude - 40) < 0.01 && abs(place.azimuth - 170) < 0.01)
        // The field of view is what it says: half of it is at the edge of the shorter side.
        let edge = try #require(camera.project(altitude: 70, azimuth: 180, viewHeight: 800))
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
        camera.pan(deltaX: 0, deltaY: 100_000, viewHeight: 800)
        #expect(camera.altitude == 90)
        camera.altitude = 45
        camera.pan(deltaX: 400, deltaY: 0, viewHeight: 800)
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
        if ProcessInfo.processInfo.environment["GALILEO_TEST_SURVEY_DIR"] == nil {
            #expect(await api.surveyTile(SkyTile(order: 0, npix: 0)) == nil, "the demo ships no sky pictures")
        }
        let figures = try #require(await api.constellations())
        #expect(figures.names.contains { $0.name == "Orion" })
        #expect(figures.lines.first?.count == 6)
        // The figures' stars are real and named, after the objects.
        let vega = try #require(await api.identify(index: 2500 + 6 + 7 + 7 + 5 + 5))
        #expect(vega.name == "Vega" && !vega.isDeepSky)
        let samples = try #require(await api.ephemeris(from: Date(), to: Date().addingTimeInterval(3600), stepSeconds: 600))
        #expect(samples.count == 7 && samples[0].bodies.contains { $0.name == "Moon" })
    }

    /// The horizon fill undoes the stars' turn with a transpose, and the survey's plan both: only right for pure rotations.
    @Test func theSkyTurnsAreRotationsSoTheyCanBeUndone() {
        var camera = SkyCamera()
        camera.altitude = 37
        camera.azimuth = 251
        let site = SkySite(latitude: 49.9, longitude: -97.1)
        for matrix in [camera.horizonToView, SkyGeometry.equatorialToHorizon(site: site, at: Date(timeIntervalSince1970: 1_800_000_000))] {
            let product = matrix * matrix.transpose
            for column in 0..<3 {
                for row in 0..<3 {
                    #expect(abs(product[column][row] - (column == row ? 1 : 0)) < 1e-5)
                }
            }
        }
    }

    /// `MAPP-SKY-070`: the same answers as astropy_healpix, which `galileo.planning.sky_survey` is held to as well.
    @Test func surveyTilesAreTheSameCellsAsCore() throws {
        let data = try Data(contentsOf: Paths.vectors.appending(path: "sky/healpix.json"))
        let cases = try #require(try JSONDecoder().decode(JSONValue.self, from: data)["cases"]?.arrayValue)
        #expect(cases.count > 300)
        for item in cases {
            let ra = try #require(item["ra_deg"]?.doubleValue)
            let dec = try #require(item["dec_deg"]?.doubleValue)
            let order = try #require(item["order"]?.doubleValue)
            let npix = try #require(item["npix"]?.doubleValue)
            #expect(SkySurvey.tile(raDeg: ra, decDeg: dec, order: Int(order)).tile == SkyTile(order: Int(order), npix: Int(npix)), "\(ra) \(dec) \(order)")
        }
    }

    /// The mesh is built with this inverse, so a tile lies where its name says.
    @Test func aPlaceInATileGoesBackToTheSameDirection() {
        for (ra, dec) in [(83.8, -5.4), (10.68, 41.27), (200.0, 80.0), (300.0, -75.0), (0.5, 0.2), (359.0, 44.0), (135.0, 89.5)] {
            for order in [0, 3, 7] {
                let found = SkySurvey.tile(raDeg: ra, decDeg: dec, order: order)
                let back = SkySurvey.direction(of: found.tile, fx: found.fx, fy: found.fy)
                let want = SkyGeometry.unitVector(raDeg: Float(ra), decDeg: Float(dec))
                #expect(simd_distance(SIMD3<Float>(Float(back.x), Float(back.y), Float(back.z)), want) < 1e-4, "\(ra) \(dec) \(order)")
            }
        }
    }

    /// `MAPP-SKY-070`: everything on screen is asked for, finer as the view zooms, and never more than a screen can hold.
    @Test func aViewAsksForItsTilesAndFinerAsItZooms() {
        let site = SkySite(latitude: 49.9, longitude: -97.1)
        let moment = Date(timeIntervalSince1970: 1_800_000_000)
        let size = CGSize(width: 1000, height: 700)
        var camera = SkyCamera()
        camera.altitude = 40
        camera.azimuth = 200
        var lastOrder = -1
        for field in [100.0, 20.0, 2.0] {
            camera.fieldOfView = field
            let wanted = SkySurvey.plan(camera: camera, site: site, at: moment, size: size, pixelsPerPoint: 2)
            #expect(!wanted.isEmpty && wanted.count <= SkySurvey.maxViewTiles)
            let order = wanted[0].order
            #expect(order > lastOrder)
            lastOrder = order
            // Screen corners and middle: each lands in a tile that was asked for.
            let shorter = min(size.width, size.height)
            let scale = camera.scale(viewHeight: shorter)
            let toSky = SkyGeometry.equatorialToHorizon(site: site, at: moment).transpose * camera.horizonToView.transpose
            for (x, y) in [(0.0, 0.0), (500.0, 350.0), (-500.0, -350.0), (500.0, -350.0), (-500.0, 350.0)] {
                let p = SIMD2(x / scale, y / scale)
                let r = simd_dot(p, p) / 4
                let view = SIMD3<Float>(Float(p.x / (1 + r)), Float(p.y / (1 + r)), Float((1 - r) / (1 + r)))
                let sky = toSky * view
                let ra = atan2(Double(sky.y), Double(sky.x)) * 180 / .pi
                let dec = asin(min(1, max(-1, Double(sky.z)))) * 180 / .pi
                #expect(wanted.contains(SkySurvey.tile(raDeg: ra < 0 ? ra + 360 : ra, decDeg: dec, order: order).tile), "\(field) \(x) \(y)")
            }
        }
    }

    @Test func theSkylineInterpolatesRoundTheCircle() {
        var site = SkySite(latitude: 0, longitude: 0)
        #expect(site.skylineAltitude(azimuth: 123) == 0, "no profile: the true horizon")
        site.horizon = [.init(azimuth: 0, altitude: 10), .init(azimuth: 180, altitude: 30)]
        #expect(abs(site.skylineAltitude(azimuth: 90) - 20) < 1e-9)
        #expect(abs(site.skylineAltitude(azimuth: 270) - 20) < 1e-9)
    }

    /// `MAPP-SKY-090`: what the app draws is where Galileo's Star Atlas draws it, from values its own code wrote: the stars
    /// on the GPU's path, and everything else on the CPU's.
    @Test func theProjectionIsTheStarAtlases() throws {
        let data = try Data(contentsOf: Paths.vectors.appending(path: "sky/projection.json"))
        let cases = try #require(try JSONDecoder().decode(JSONValue.self, from: data)["cases"]?.arrayValue)
        #expect(cases.count == 120)
        func number(_ item: JSONValue, _ key: String) throws -> Double { try #require(item[key]?.doubleValue) }
        for item in cases {
            let site = SkySite(latitude: try number(item, "latitude"), longitude: try number(item, "longitude"))
            let moment = Date(timeIntervalSince1970: try number(item, "unix"))
            let ra = try number(item, "ra_deg")
            let dec = try number(item, "dec_deg")
            let camera = SkyCamera(
                altitude: try number(item, "view_altitude"), azimuth: try number(item, "view_azimuth"), fieldOfView: try number(item, "field"))
            let width = try number(item, "width")
            let height = try number(item, "height")

            let place = SkyGeometry.horizontal(raDeg: ra, decDeg: dec, site: site, at: moment)
            #expect(abs(place.altitude - (try number(item, "altitude"))) < 1e-6)
            let azimuthError = abs(place.azimuth - (try number(item, "azimuth")))
            #expect(min(azimuthError, 360 - azimuthError) < 1e-5)

            let point = camera.project(altitude: place.altitude, azimuth: place.azimuth, viewHeight: height)
            // Viewport's own limit is -0.85 too, so only a hair's breadth of float difference can disagree.
            guard let point else { continue }
            let x = try number(item, "x")
            let y = try number(item, "y")
            guard abs(x - width / 2) < 4 * width, abs(y - height / 2) < 4 * height else { continue }
            #expect(abs(width / 2 + point.x - x) < 0.5, "\(item)")
            #expect(abs(height / 2 - point.y - y) < 0.5, "\(item)")

            let view = camera.horizonToView * (SkyGeometry.equatorialToHorizon(site: site, at: moment)
                * SkyGeometry.unitVector(raDeg: Float(ra), decDeg: Float(dec)))
            let k = Double(2 / (1 + view.z)) * camera.scale(viewHeight: height)
            #expect(abs(width / 2 + Double(view.x) * k - x) < 1.5, "the stars' path: \(item)")
            #expect(abs(height / 2 - Double(view.y) * k - y) < 1.5, "the stars' path: \(item)")
        }
    }

    /// `MAPP-SKY-110`: what's in which catalog is the Master's to say.
    @Test func theMasterSaysWhatIsInWhichCatalog() async throws {
        let master = try MockMaster(scenarioNamed: "normal-night")
        let api = MasterConnection.demo(master, identity: testIdentity, token: MockMaster.token).api
        let catalogs = await api.skyCatalogs()
        #expect(catalogs["Messier"]?.contains(2500) == true, "M 42")
        #expect(catalogs["NGC"] != nil && catalogs["Caldwell"] != nil)
    }
}
