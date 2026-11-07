import GalileoCore
import Observation
import SwiftUI
import simd

/// What the sky view is showing. Everything drawn came from the Master; this only remembers it and asks for more as the
/// user looks around (`S-SKY`).
@MainActor @Observable
final class SkyModel {
    struct Selection: Equatable {
        var name: String
        var raDeg: Double
        var decDeg: Double
        var details: [String] = []
        var check: PositionCheck?
    }

    var camera = SkyCamera()
    /// Seconds from now that the view is showing. Zero is live (`MAPP-SKY-020`).
    var timeOffset: TimeInterval = 0
    var query = ""
    private(set) var searching = false
    private(set) var results: [JSONValue] = []
    private(set) var selection: Selection?
    private(set) var site: SkySite?
    private(set) var siteMissing = false
    private(set) var siteFailed = false
    private(set) var stars: [SIMD4<Float>] = []
    /// Moves when `stars` changes, so that the view needn't compare tens of thousands of them to find out.
    private(set) var starsVersion = 0
    private(set) var deepSky: [SkyObject] = []
    private(set) var bright: [SkyObject] = []
    private(set) var samples: [EphemerisSample] = []
    private(set) var fieldOfView: (width: Double, height: Double)?
    private(set) var loading = false
    private(set) var constellations = SkyConstellations()
    var showConstellations = UserDefaults.standard.object(forKey: "sky.constellations") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showConstellations, forKey: "sky.constellations") }
    }

    private let session: ObservatorySession
    private let pier: String
    private let store: SkyStore
    private var settling: Task<Void, Never>?
    private var sampleRange: ClosedRange<Date>?
    private var fetchingSamples = false
    private var arrivals = 0

    init(session: ObservatorySession, pier: String) {
        self.session = session
        self.pier = pier
        store = SkyStore(api: session.api)
    }

    /// `LOD_MAGNITUDE` in `galileo.skystream`, with the last level's "everything" cut off for drawing.
    nonisolated static func limitingMagnitude(level: Int) -> Float {
        [4, 7, 10, 13, 15][max(0, min(4, level))]
    }

    func time(now: Date) -> Date { now.addingTimeInterval(timeOffset) }

    // MARK: Loading

    func start(now: Date, rate: Double?) async {
        await store.setRate(perSecond: rate)
        siteMissing = false
        siteFailed = false
        switch await session.api.skySite(pier: pier) {
        case .site(let found): site = found
        case .noLocation: siteMissing = true
        case .failed: siteFailed = true
        }
        guard site != nil else { return }
        if let pointing = await pointingNow() { lookAt(altitude: pointing.altitude, azimuth: pointing.azimuth) }
        cameraMoved(now: now)
        await fetchSamples(around: time(now: now))
        await fetchFieldOfView()
        if let figures = await session.api.constellations() { constellations = figures }
    }

    private func pointingNow() async -> HorizontalPosition? {
        guard let site, let snapshot = Optional(await session.current),
            let ra = snapshot.value("mount.position.ra_deg", pier: pier, now: Date())?.value.doubleValue,
            let dec = snapshot.value("mount.position.dec_deg", pier: pier, now: Date())?.value.doubleValue
        else { return nil }
        return SkyGeometry.horizontal(raDeg: ra, decDeg: dec, site: site, at: Date())
    }

    func lookAt(altitude: Double, azimuth: Double) {
        camera.altitude = max(5, altitude)
        camera.azimuth = azimuth
    }

    /// The view moved. Asks once it has settled, not on every frame of a drag.
    func cameraMoved(now: Date) {
        settling?.cancel()
        let camera = self.camera
        let moment = time(now: now)
        settling = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled, let self, let site = self.site else { return }
            let centre = SkyGeometry.equatorial(altitude: camera.altitude, azimuth: camera.azimuth, site: site, at: moment)
            self.loading = true
            await self.store.look(
                raDeg: centre.ra, decDeg: centre.dec, radius: min(180, camera.fieldOfView), level: camera.levelOfDetail
            ) { [weak self] objects in
                await self?.received(objects)
            }
        }
    }

    /// A real catalog is tens of thousands of objects a page apart: sort them out away from the main thread.
    private func received(_ objects: [SkyObject]) async {
        arrivals += 1
        let arrival = arrivals
        let sorted = await Task.detached(priority: .userInitiated) { Self.sortOut(objects) }.value
        guard arrival == arrivals else { return }  // a newer page got here first
        stars = sorted.stars
        starsVersion += 1
        deepSky = sorted.deepSky
        bright = sorted.bright
        loading = false
    }

    private nonisolated static func sortOut(_ objects: [SkyObject]) -> (stars: [SIMD4<Float>], deepSky: [SkyObject], bright: [SkyObject]) {
        var stars: [SIMD4<Float>] = []
        var deepSky: [SkyObject] = []
        var bright: [SkyObject] = []
        stars.reserveCapacity(objects.count)
        for object in objects {
            if object.isDeepSky {
                deepSky.append(object)
                continue
            }
            let v = SkyGeometry.unitVector(raDeg: object.raDeg, decDeg: object.decDeg)
            stars.append(SIMD4(v.x, v.y, v.z, object.magnitude))
            if object.magnitude < 4 { bright.append(object) }
        }
        return (stars, deepSky, bright)
    }

    /// The Master's positions for a span around the shown time, so scrubbing needs no round trips (`MAPP-STR-020`).
    func fetchSamples(around moment: Date) async {
        if let sampleRange, sampleRange.contains(moment) { return }
        guard !fetchingSamples else { return }
        fetchingSamples = true
        defer { fetchingSamples = false }
        let from = moment.addingTimeInterval(-6 * 3600)
        let to = moment.addingTimeInterval(18 * 3600)
        if let fresh = await session.api.ephemeris(from: from, to: to, stepSeconds: 1800) {
            samples = fresh
            sampleRange = from...to
        }
    }

    private func fetchFieldOfView() async {
        // The train's field of view doesn't depend on where it's pointing; any position will do.
        guard let framing = await session.read(pier: pier, operation: "sky.framing", args: ["ra_deg": 0, "dec_deg": 0]),
            let size = framing["fov_deg"]?.arrayValue, size.count == 2,
            let width = size[0].doubleValue, let height = size[1].doubleValue
        else { return }
        fieldOfView = (width, height)
    }

    // MARK: Search and selection

    func search() async {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else {
            results = []
            return
        }
        searching = true
        defer { searching = false }
        let found = await session.read(pier: pier, operation: "sky.search", args: ["query": .string(text), "limit": 20])
        results = found?["objects"]?.arrayValue ?? []
    }

    /// A search result, with the Master's details and check.
    func select(_ object: JSONValue, now: Date) async {
        guard let name = object["name"]?.stringValue, let ra = object["ra_deg"]?.doubleValue, let dec = object["dec_deg"]?.doubleValue else { return }
        results = []
        await selectPosition(name: name, raDeg: ra, decDeg: dec, now: now, centre: true)
    }

    /// Something tapped in the view: `index` is the Master's, and only it knows what that is.
    func tapped(_ object: SkyObject, now: Date) async {
        let identified = await session.api.identify(index: Int(object.index))
        let name = identified?.name ?? (object.isDeepSky ? "Deep-sky object" : "Star")
        await selectPosition(name: name, raDeg: Double(object.raDeg), decDeg: Double(object.decDeg), now: now, centre: false)
    }

    private func selectPosition(name: String, raDeg: Double, decDeg: Double, now: Date, centre: Bool) async {
        var chosen = Selection(name: name, raDeg: raDeg, decDeg: decDeg)
        if centre, let site {
            let place = SkyGeometry.horizontal(raDeg: raDeg, decDeg: decDeg, site: site, at: time(now: now))
            lookAt(altitude: place.altitude, azimuth: place.azimuth)
            cameraMoved(now: now)
        }
        selection = chosen
        async let details = session.read(pier: pier, operation: "sky.object", args: ["name": .string(name)])
        async let check = session.api.checkPosition(pier: pier, raDeg: raDeg, decDeg: decDeg)
        let (found, checked) = await (details, check)
        chosen.check = checked
        if let found {
            if let type = found["type"]?.stringValue { chosen.details.append(type) }
            if let magnitude = found["magnitude"]?.doubleValue, magnitude < 90 { chosen.details.append(String(format: "Magnitude %.1f", magnitude)) }
            if let size = found["size_arcmin"]?.doubleValue { chosen.details.append(String(format: "Size %.1f′", size)) }
            if let times = found["rise_transit_set"]?.objectValue {
                for key in ["rise", "transit", "set"] {
                    if let value = times[key], value != .null { chosen.details.append("\(key.capitalized): \(value.displayText)") }
                }
            }
        }
        if selection?.name == name { selection = chosen }
    }

    func clearSelection() { selection = nil }

    func stop() async {
        settling?.cancel()
        await store.stop()
    }
}
