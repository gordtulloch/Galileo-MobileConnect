import Foundation
import GalileoAPI

/// A planet's place at one moment.
public struct EphemerisBody: Sendable, Equatable {
    public var name: String
    public var raDeg: Double
    public var decDeg: Double
}

public struct EphemerisSample: Sendable, Equatable {
    public var time: Date
    public var bodies: [EphemerisBody]
}

public struct IdentifiedObject: Sendable, Equatable {
    public var index: Int
    public var isDeepSky: Bool
    public var name: String?
    public var type: String?
    public var raDeg: Double
    public var decDeg: Double
    public var magnitude: Double?
}

/// Constellation figures, from the Master: the app ships none.
public struct SkyConstellations: Sendable, Equatable {
    public struct Name: Sendable, Equatable {
        public var name: String
        public var raDeg: Double
        public var decDeg: Double
    }

    public struct Point: Sendable, Equatable {
        public var raDeg: Double
        public var decDeg: Double
    }

    public var lines: [[Point]] = []
    public var names: [Name] = []

    public init() {}
}

public struct PositionCheck: Sendable, Equatable {
    public var altitude: Double
    public var azimuth: Double
    public var aboveHorizon: Bool
    public var reason: String?
}

/// The Master's answer to a request for a page: objects, or why not now.
public enum SkyPage: Sendable, Equatable {
    case page(objects: [SkyObject], next: String)
    /// Over the rate or the client limit. Back off; this isn't an error (`MAPP-STR-070`).
    case backOff
    case refused(String)
}

extension MasterAPI {
    public func skyPage(ra: Double, dec: Double, radius: Double, level: Int, cursor: String?) async -> SkyPage {
        let output: Operations.getSkyCatalog.Output
        do {
            output = try await callingForSky {
                try await client.getSkyCatalog(query: .init(ra: ra, dec: dec, radius: radius, lod: level, cursor: cursor))
            }
        } catch {
            return .backOff  // the link: the view says so, and the next look asks again
        }
        switch output {
        case .ok(let response):
            guard let data = try? await Data(collecting: response.body.binary, upTo: 16 * 1024 * 1024),
                let objects = try? SkyFrameDecoder.decode(data)
            else { return .refused("The Master's sky data couldn't be read.") }
            return .page(objects: objects, next: response.headers.X_hyphen_Sky_hyphen_Next ?? "")
        case .tooManyRequests, .serviceUnavailable: return .backOff
        case .badRequest: return .refused("The Master refused that part of the sky.")
        case .unauthorized: return .refused(LinkError.unpaired.message)
        case .undocumented(let status, _): return .refused("The Master gave an unexpected answer (\(status)).")
        }
    }

    public func ephemeris(from start: Date, to end: Date, stepSeconds: Double) async -> [EphemerisSample]? {
        let output = try? await callingForSky {
            try await client.getEphemeris(query: .init(from: start, to: end, step: stepSeconds))
        }
        guard case .ok(let response)? = output, let body = try? response.body.json else { return nil }
        return body.samples.map { sample in
            EphemerisSample(
                time: sample.t,
                bodies: sample.bodies.map { EphemerisBody(name: $0.name, raDeg: $0.ra_deg, decDeg: $0.dec_deg) })
        }
    }

    /// A Master saying it has no location is one thing, and not getting an answer is another: only the first is
    /// something the user should go and set on the desktop.
    public enum SiteAnswer: Sendable, Equatable {
        case site(SkySite)
        case noLocation
        case failed
    }

    public func skySite(pier: String) async -> SiteAnswer {
        guard let output = try? await callingForSky({ try await client.getSite(query: .init(pier: pier)) }) else { return .failed }
        switch output {
        case .ok(let response):
            guard let body = try? response.body.json else { return .failed }
            return .site(
                SkySite(
                    name: body.name, latitude: body.latitude_deg, longitude: body.longitude_deg,
                    horizon: body.horizon.map { .init(azimuth: $0.az_deg, altitude: $0.alt_deg) }))
        case .notFound: return .noLocation
        case .unauthorized, .undocumented: return .failed
        }
    }

    public func constellations() async -> SkyConstellations? {
        let output = try? await callingForSky { try await client.getConstellations() }
        guard case .ok(let response)? = output, let body = try? response.body.json else { return nil }
        var result = SkyConstellations()
        result.lines = body.lines.map { line in
            line.compactMap { pair in pair.count >= 2 ? SkyConstellations.Point(raDeg: pair[0], decDeg: pair[1]) : nil }
        }
        result.names = body.names.map { .init(name: $0.name, raDeg: $0.ra_deg, decDeg: $0.dec_deg) }
        return result
    }

    public func checkPosition(pier: String, raDeg: Double, decDeg: Double) async -> PositionCheck? {
        let output = try? await callingForSky { try await client.checkPosition(query: .init(pier: pier, ra: raDeg, dec: decDeg)) }
        guard case .ok(let response)? = output, let body = try? response.body.json else { return nil }
        return PositionCheck(altitude: body.altitude_deg, azimuth: body.azimuth_deg, aboveHorizon: body.above_horizon, reason: body.reason)
    }

    public func identify(index: Int) async -> IdentifiedObject? {
        let output = try? await callingForSky { try await client.identifyObject(query: .init(index: index)) }
        guard case .ok(let response)? = output, let body = try? response.body.json else { return nil }
        return IdentifiedObject(
            index: body.index, isDeepSky: body.kind == "deep_sky", name: body.name, type: body._type, raDeg: body.ra_deg,
            decDeg: body.dec_deg, magnitude: body.magnitude)
    }
}

/// A short-lived, bounded memory of streamed pages (`MAPP-STR-050`, Shared SDD D3). Nothing is written to disk.
struct SkyPageCache {
    struct Key: Hashable {
        let ra: Int
        let dec: Int
        let radius: Int
        let level: Int
    }

    private struct Entry {
        var objects: [SkyObject]
        var complete: Bool
        var stored: Date
        var used: Date
    }

    let maximumObjects: Int
    let lifetime: TimeInterval
    private var entries: [Key: Entry] = [:]
    private(set) var objectCount = 0

    init(maximumObjects: Int = 150_000, lifetime: TimeInterval = 15 * 60) {
        self.maximumObjects = maximumObjects
        self.lifetime = lifetime
    }

    mutating func get(_ key: Key, now: Date) -> (objects: [SkyObject], complete: Bool)? {
        guard let entry = entries[key] else { return nil }
        if now.timeIntervalSince(entry.stored) > lifetime {
            remove(key)
            return nil
        }
        entries[key]?.used = now
        return (entry.objects, entry.complete)
    }

    mutating func put(_ key: Key, objects: [SkyObject], complete: Bool, now: Date) {
        remove(key)
        entries[key] = Entry(objects: objects, complete: complete, stored: now, used: now)
        objectCount += objects.count
        while objectCount > maximumObjects, entries.count > 1 {
            guard let oldest = entries.filter({ $0.key != key }).min(by: { $0.value.used < $1.value.used })?.key else { break }
            remove(oldest)
        }
    }

    private mutating func remove(_ key: Key) {
        if let gone = entries.removeValue(forKey: key) { objectCount -= gone.objects.count }
    }
}

/// `S-SKY`: asks the Master for what the view is looking at, brightest first, and hands over each page as it arrives
/// (`MAPP-STR-010`, `040`). Requests stay under the Master's rate; being told to slow down just slows it.
public actor SkyStore {
    public typealias Update = @Sendable ([SkyObject]) async -> Void

    private let api: MasterAPI
    private let clock: any LinkClock
    private var cache = SkyPageCache()
    private var objects: [Int32: SkyObject] = [:]
    private var loading: Task<Void, Never>?
    private let keepObjects = 80_000
    private let maximumPages = 40
    private let maximumBackOff = 16.0
    /// Seconds between requests, from the Master's stated rate.
    public var minimumInterval = 0.15

    public init(api: MasterAPI, clock: any LinkClock = SystemClock()) {
        self.api = api
        self.clock = clock
    }

    public func setRate(perSecond rate: Double?) {
        if let rate, rate > 0 { minimumInterval = 1 / rate * 1.2 }
    }

    /// The view has settled here. Replaces whatever was still loading. `update` gets the whole current set each time.
    public func look(raDeg: Double, decDeg: Double, radius: Double, level: Int, update: @escaping Update) {
        loading?.cancel()
        // Quantised, so that small moves ask for nothing new.
        let step = max(1, radius / 2)
        let ra = ((raDeg / step).rounded() * step).truncatingRemainder(dividingBy: 360)
        let dec = max(-90, min(90, (decDeg / step).rounded() * step))
        let region = min(180, radius * 1.3)
        let key = SkyPageCache.Key(ra: Int(ra * 10), dec: Int(dec * 10), radius: Int(region * 10), level: level)
        loading = Task { await self.load(key: key, ra: ra < 0 ? ra + 360 : ra, dec: dec, radius: region, level: level, update: update) }
    }

    public func stop() {
        loading?.cancel()
        loading = nil
    }

    private func merge(_ page: [SkyObject]) {
        for object in page { objects[object.index] = object }
        if objects.count > keepObjects {
            // Over the bound: the faintest go first, since they matter least and return with the next look.
            let keep = objects.values.sorted { $0.magnitude < $1.magnitude }.prefix(keepObjects)
            objects = Dictionary(uniqueKeysWithValues: keep.map { ($0.index, $0) })
        }
    }

    private func load(key: SkyPageCache.Key, ra: Double, dec: Double, radius: Double, level: Int, update: @escaping Update) async {
        var collected: [SkyObject] = []
        if let cached = cache.get(key, now: clock.now()) {
            merge(cached.objects)
            await update(Array(objects.values))
            if cached.complete { return }
        }
        var cursor: String?
        var backOff = 1.0
        var pages = 0
        while !Task.isCancelled, pages < maximumPages {
            switch await api.skyPage(ra: ra, dec: dec, radius: radius, level: level, cursor: cursor) {
            case .page(let page, let next):
                pages += 1
                backOff = 1
                collected += page
                merge(page)
                if Task.isCancelled { return }
                await update(Array(objects.values))
                cache.put(key, objects: collected, complete: next.isEmpty, now: clock.now())
                if next.isEmpty { return }
                cursor = next
                try? await clock.sleep(seconds: minimumInterval)
            case .backOff:
                try? await clock.sleep(seconds: backOff)
                backOff = min(maximumBackOff, backOff * 2)
                if backOff >= maximumBackOff { return }  // the link is down, or the Master is busy: the next look asks again
            case .refused:
                return
            }
        }
    }

    public var currentObjects: [SkyObject] { Array(objects.values) }
    var cachedObjectCount: Int { cache.objectCount }
}

/// Interpolates a time series, the only computing done on positions over time: the Master computed each sample.
public enum EphemerisInterpolator {
    public static func bodies(at time: Date, in samples: [EphemerisSample]) -> [EphemerisBody] {
        guard let first = samples.first else { return [] }
        guard let after = samples.firstIndex(where: { $0.time >= time }) else { return samples.last?.bodies ?? [] }
        if after == 0 { return first.bodies }
        let a = samples[after - 1]
        let b = samples[after]
        let span = b.time.timeIntervalSince(a.time)
        let f = span > 0 ? time.timeIntervalSince(a.time) / span : 0
        return a.bodies.compactMap { body in
            guard let other = b.bodies.first(where: { $0.name == body.name }) else { return body }
            var delta = other.raDeg - body.raDeg
            if delta > 180 { delta -= 360 }
            if delta < -180 { delta += 360 }
            var ra = (body.raDeg + delta * f).truncatingRemainder(dividingBy: 360)
            if ra < 0 { ra += 360 }
            return EphemerisBody(name: body.name, raDeg: ra, decDeg: body.decDeg + (other.decDeg - body.decDeg) * f)
        }
    }
}
