import CoreGraphics
import Foundation
import simd

/// One tile of the all-sky survey the Master serves: a HEALPix cell at a HiPS order, in J2000 (`MAPP-SKY-070`).
public struct SkyTile: Hashable, Sendable {
    public var order: Int
    public var npix: Int

    public init(order: Int, npix: Int) {
        self.order = order
        self.npix = npix
    }

    public var parent: SkyTile? { order > 0 ? SkyTile(order: order - 1, npix: npix / 4) : nil }
}

/// A tile's picture and whose picture it is. Not ours to keep: memory only, and its credit goes where it goes.
public struct SkyTileImage: Sendable {
    public var jpeg: Data
    public var credit: String

    public init(jpeg: Data, credit: String) {
        self.jpeg = jpeg
        self.credit = credit
    }
}

/// HEALPix, nested: the same maths as `galileo.planning.sky_survey`, against the same vectors (`api/vectors/sky/healpix.json`).
public enum SkySurvey {
    public static let maxOrder = 4          // Mellinger's mosaic has nothing finer
    /// Enough for a screen at its order; a view needing more is drawn one order coarser.
    public static let maxViewTiles = 48
    private static let order0DegreesPerPixel = 58.63 / 512
    /// A view narrower than this fetches no new tiles: it draws what it holds, so panning deep in never waits on the network.
    public static let fetchAboveFieldDegrees = 3.0

    /// Which tile a J2000 position is in, and where in it, each 0-1. In the tile's image, the column is `fy` and the row `fx`.
    public static func tile(raDeg: Double, decDeg: Double, order: Int) -> (tile: SkyTile, fx: Double, fy: Double) {
        let (face, x, y) = faceXY(raDeg: raDeg, decDeg: decDeg)
        let side = 1 << order
        let tx = min(Int(x * Double(side)), side - 1)
        let ty = min(Int(y * Double(side)), side - 1)
        var npix = 0
        for bit in 0..<order {
            npix |= ((tx >> bit) & 1) << (2 * bit)
            npix |= ((ty >> bit) & 1) << (2 * bit + 1)
        }
        return (SkyTile(order: order, npix: face << (2 * order) | npix), x * Double(side) - Double(tx), y * Double(side) - Double(ty))
    }

    private static func faceXY(raDeg: Double, decDeg: Double) -> (face: Int, x: Double, y: Double) {
        let z = sin(decDeg * .pi / 180)
        var tt = (raDeg * .pi / 180 / (.pi / 2)).truncatingRemainder(dividingBy: 4)
        if tt < 0 { tt += 4 }
        if abs(z) <= 2.0 / 3 {
            let jp = 0.5 + tt - 0.75 * z
            let jm = 0.5 + tt + 0.75 * z
            let ifp = floor(jp)
            let ifm = floor(jm)
            let face: Int
            if ifp == ifm {
                face = Int(ifp) % 4 + 4
            } else if ifp < ifm {
                face = Int(ifp) % 4
            } else {
                face = Int(ifm) % 4 + 8
            }
            return (face, jm - ifm, 1 - (jp - ifp))
        }
        let ntt = min(floor(tt), 3)
        let tp = tt - ntt
        let tmp = (3 * (1 - abs(z))).squareRoot()
        let a = min(tp * tmp, 1)
        let b = min((1 - tp) * tmp, 1)
        return z > 0 ? (Int(ntt), 1 - b, 1 - a) : (Int(ntt) + 8, a, b)
    }

    /// The J2000 unit vector at a place in a tile.
    public static func direction(of tile: SkyTile, fx: Double, fy: Double) -> SIMD3<Double> {
        let cells = 1 << (2 * tile.order)
        let face = tile.npix / cells
        var inFace = tile.npix % cells
        var tx = 0
        var ty = 0
        var bit = 0
        while inFace > 0 {
            tx |= (inFace & 1) << bit
            ty |= ((inFace >> 1) & 1) << bit
            inFace >>= 2
            bit += 1
        }
        let side = Double(1 << tile.order)
        let x = (Double(tx) + fx) / side
        let y = (Double(ty) + fy) / side
        let jr = [2.0, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4][face] - x - y
        let z: Double
        let nr: Double
        if jr < 1 {
            nr = jr
            z = 1 - nr * nr / 3
        } else if jr > 3 {
            nr = 4 - jr
            z = nr * nr / 3 - 1
        } else {
            nr = 1
            z = (2 - jr) * 2 / 3
        }
        let t = [1.0, 3, 5, 7, 0, 2, 4, 6, 1, 3, 5, 7][face] * nr + x - y
        let phi = nr > 1e-12 ? .pi / 4 * t / nr : 0
        let s = max(0, 1 - z * z).squareRoot()
        return SIMD3(s * cos(phi), s * sin(phi), z)
    }

    /// One finer than just matching the screen, because a tile's JPEG blocks show otherwise.
    public static func order(degreesPerPixel: Double) -> Int {
        max(0, min(maxOrder, 1 + Int(log2(order0DegreesPerPixel / max(degreesPerPixel, 1e-6)).rounded(.up))))
    }

    /// The tiles a cone of the sky touches, by descending from the 12 base cells. May include a few just outside it.
    public static func tiles(around centre: SIMD3<Double>, radiusDegrees: Double, order: Int) -> [SkyTile] {
        var found: [SkyTile] = []
        func visit(_ tile: SkyTile) {
            let middle = direction(of: tile, fx: 0.5, fy: 0.5)
            let distance = acos(min(1, max(-1, simd_dot(middle, centre)))) * 180 / .pi
            // A cell's corners are at most this far from its middle: the base cells' ~48 degrees, halving each order.
            guard distance <= radiusDegrees + 55 / Double(1 << tile.order) else { return }
            if tile.order == order {
                found.append(tile)
            } else {
                for child in 0..<4 { visit(SkyTile(order: tile.order + 1, npix: tile.npix * 4 + child)) }
            }
        }
        for face in 0..<12 { visit(SkyTile(order: 0, npix: face)) }
        // Nearest first: what the user is looking at comes before the corners.
        return found.sorted {
            simd_dot(direction(of: $0, fx: 0.5, fy: 0.5), centre) > simd_dot(direction(of: $1, fx: 0.5, fy: 0.5), centre)
        }
    }

    /// What a view needs: the finest order that doesn't take too many tiles.
    public static func plan(
        camera: SkyCamera, site: SkySite, at moment: Date, size: CGSize, pixelsPerPoint: Double
    ) -> [SkyTile] {
        let scale = camera.scale(viewHeight: size.height)
        guard scale > 0 else { return [] }
        let toHorizon = SkyGeometry.equatorialToHorizon(site: site, at: moment).transpose
        let toView = camera.horizonToView.transpose
        let forward = toHorizon * (toView * SIMD3<Float>(0, 0, 1))
        let centre = simd_normalize(SIMD3<Double>(Double(forward.x), Double(forward.y), Double(forward.z)))
        let radius = 2 * atan(hypot(size.width, size.height) / 2 / scale / 2) * 180 / .pi
        var level = order(degreesPerPixel: 180 / .pi / scale / pixelsPerPoint)
        var found = tiles(around: centre, radiusDegrees: radius, order: level)
        while found.count > maxViewTiles, level > 0 {
            level -= 1
            found = tiles(around: centre, radiusDegrees: radius, order: level)
        }
        return found
    }
}

extension SkySite {
    /// The local horizon's altitude at an azimuth, interpolated round the circle: the true horizon where none is set.
    public func skylineAltitude(azimuth: Double) -> Double {
        guard !horizon.isEmpty else { return 0 }
        let points = horizon.sorted { $0.azimuth < $1.azimuth }
        let before = points.last { $0.azimuth <= azimuth } ?? HorizonPoint(azimuth: points[points.count - 1].azimuth - 360, altitude: points[points.count - 1].altitude)
        let after = points.first { $0.azimuth > azimuth } ?? HorizonPoint(azimuth: points[0].azimuth + 360, altitude: points[0].altitude)
        let span = after.azimuth - before.azimuth
        return span > 0 ? before.altitude + (after.altitude - before.altitude) * (azimuth - before.azimuth) / span : before.altitude
    }
}
