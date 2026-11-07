import Foundation
import simd

/// Where the Observatory is. From the Master (`MAPP-STR-030`).
public struct SkySite: Sendable, Equatable {
    public struct HorizonPoint: Sendable, Equatable {
        public var azimuth: Double
        public var altitude: Double
        public init(azimuth: Double, altitude: Double) {
            self.azimuth = azimuth
            self.altitude = altitude
        }
    }

    public var name: String?
    public var latitude: Double
    public var longitude: Double
    public var horizon: [HorizonPoint]

    public init(name: String? = nil, latitude: Double, longitude: Double, horizon: [HorizonPoint] = []) {
        self.name = name
        self.latitude = latitude
        self.longitude = longitude
        self.horizon = horizon
    }
}

public struct HorizontalPosition: Sendable, Equatable {
    public var altitude: Double
    public var azimuth: Double
}

/// Turning the Master's positions into the view: a change of coordinates for drawing, not an ephemeris. Decisions about
/// what is above the horizon or within limits are the Master's (`/sky/check`).
public enum SkyGeometry {
    private static let radians = Double.pi / 180

    /// Local sidereal time in degrees (the IAU 1982 mean-sidereal polynomial, plenty for drawing).
    public static func siderealDegrees(at date: Date, longitude: Double) -> Double {
        let days = date.timeIntervalSince1970 / 86400 + 2440587.5 - 2451545.0
        let gmst = 280.46061837 + 360.98564736629 * days
        let lst = (gmst + longitude).truncatingRemainder(dividingBy: 360)
        return lst < 0 ? lst + 360 : lst
    }

    public static func horizontal(raDeg: Double, decDeg: Double, site: SkySite, at date: Date) -> HorizontalPosition {
        let hourAngle = (siderealDegrees(at: date, longitude: site.longitude) - raDeg) * radians
        let dec = decDeg * radians
        let lat = site.latitude * radians
        let altitude = asin(sin(dec) * sin(lat) + cos(dec) * cos(lat) * cos(hourAngle))
        let azimuth = atan2(-sin(hourAngle) * cos(dec), sin(dec) * cos(lat) - cos(dec) * cos(hourAngle) * sin(lat))
        let degrees = azimuth / radians
        return HorizontalPosition(altitude: altitude / radians, azimuth: degrees < 0 ? degrees + 360 : degrees)
    }

    public static func equatorial(altitude: Double, azimuth: Double, site: SkySite, at date: Date) -> (ra: Double, dec: Double) {
        let alt = altitude * radians
        let az = azimuth * radians
        let lat = site.latitude * radians
        let dec = asin(sin(alt) * sin(lat) + cos(alt) * cos(lat) * cos(az))
        let hourAngle = atan2(-sin(az) * cos(alt), sin(alt) * cos(lat) - cos(alt) * cos(az) * sin(lat))
        let ra = (siderealDegrees(at: date, longitude: site.longitude) - hourAngle / radians).truncatingRemainder(dividingBy: 360)
        return (ra < 0 ? ra + 360 : ra, dec / radians)
    }

    /// Unit vector of an equatorial position: what the GPU is given, once, for each star.
    public static func unitVector(raDeg: Float, decDeg: Float) -> SIMD3<Float> {
        let ra = raDeg * Float(radians)
        let dec = decDeg * Float(radians)
        return SIMD3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
    }

    /// Rotates equatorial unit vectors into horizon axes: x east, y north, z up.
    public static func equatorialToHorizon(site: SkySite, at date: Date) -> simd_float3x3 {
        let lst = Float(siderealDegrees(at: date, longitude: site.longitude) * radians)
        let lat = Float(site.latitude * radians)
        let east = SIMD3(-sin(lst), cos(lst), Float(0))
        let up = SIMD3(cos(lat) * cos(lst), cos(lat) * sin(lst), sin(lat))
        let north = SIMD3(-sin(lat) * cos(lst), -sin(lat) * sin(lst), cos(lat))
        // Rows are the new axes; simd takes columns.
        return simd_float3x3(columns: (
            SIMD3(east.x, north.x, up.x), SIMD3(east.y, north.y, up.y), SIMD3(east.z, north.z, up.z)
        ))
    }
}

/// What the sky view is looking at: a stereographic projection about (altitude, azimuth), with east to the left of
/// north as it is on the sky seen from below.
public struct SkyCamera: Sendable, Equatable {
    public var altitude: Double
    public var azimuth: Double
    /// Degrees across the shorter side of the view.
    public var fieldOfView: Double

    public static let fieldRange = 0.5...180.0

    public init(altitude: Double = 45, azimuth: Double = 180, fieldOfView: Double = 90) {
        self.altitude = altitude
        self.azimuth = azimuth
        self.fieldOfView = fieldOfView
    }

    public mutating func pan(deltaX: Double, deltaY: Double, viewShorterSide: Double) {
        let perPoint = fieldOfView / max(1, viewShorterSide)
        azimuth = (azimuth - deltaX * perPoint / max(0.1, cos(altitude * .pi / 180))).truncatingRemainder(dividingBy: 360)
        if azimuth < 0 { azimuth += 360 }
        altitude = min(90, max(-30, altitude + deltaY * perPoint))
    }

    public mutating func zoom(by factor: Double) {
        fieldOfView = min(Self.fieldRange.upperBound, max(Self.fieldRange.lowerBound, fieldOfView / max(1e-6, factor)))
    }

    /// Rotates horizon axes (x east, y north, z up) into view axes (x right, y up, z towards what's looked at).
    public var horizonToView: simd_float3x3 {
        let alt = Float(altitude * .pi / 180)
        let az = Float(azimuth * .pi / 180)
        let forward = SIMD3(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
        var up = SIMD3(-sin(alt) * sin(az), -sin(alt) * cos(az), cos(alt))
        up = simd_normalize(up)
        // Looking up from below, east is to the left of north: right = forward x up.
        let right = simd_cross(forward, up)
        return simd_float3x3(columns: (
            SIMD3(right.x, up.x, forward.x), SIMD3(right.y, up.y, forward.y), SIMD3(right.z, up.z, forward.z)
        ))
    }

    /// Points per unit of the stereographic plane, for a view of this shorter side.
    public func scale(viewShorterSide: Double) -> Double {
        let half = min(179, fieldOfView) * .pi / 360
        return viewShorterSide / 2 / tan(half / 2) / 2
    }

    /// A horizon-frame direction on screen, from the centre of the view, y up; nil when behind the viewer.
    public func project(altitude alt: Double, azimuth az: Double, viewShorterSide: Double) -> CGPoint? {
        let a = alt * .pi / 180
        let z = az * .pi / 180
        let direction = SIMD3(Float(cos(a) * sin(z)), Float(cos(a) * cos(z)), Float(sin(a)))
        let v = horizonToView * direction
        guard v.z > -0.9 else { return nil }
        let k = Double(2 / (1 + v.z)) * scale(viewShorterSide: viewShorterSide)
        return CGPoint(x: Double(v.x) * k, y: Double(v.y) * k)
    }

    /// The inverse of `project`: which direction a point on screen is.
    public func unproject(x: Double, y: Double, viewShorterSide: Double) -> HorizontalPosition {
        let s = scale(viewShorterSide: viewShorterSide)
        let px = x / s
        let py = y / s
        let d = (px * px + py * py) / 4
        let w = (1 - d) / (1 + d)
        let k = (1 + w) / 2
        let view = SIMD3(Float(px * k), Float(py * k), Float(w))
        let direction = horizonToView.transpose * view
        let altitude = asin(Double(max(-1, min(1, direction.z)))) * 180 / .pi
        var azimuth = atan2(Double(direction.x), Double(direction.y)) * 180 / .pi
        if azimuth < 0 { azimuth += 360 }
        return HorizontalPosition(altitude: altitude, azimuth: azimuth)
    }

    /// How deep to ask for, from how much of the sky is in view (`MAPP-STR-010`). Levels are `LOD_MAGNITUDE` in
    /// `galileo.skystream`: magnitude 4, 7, 10, 13, everything.
    public var levelOfDetail: Int {
        switch fieldOfView {
        case 60...: 1
        case 15..<60: 2
        case 3..<15: 3
        default: 4
        }
    }
}
