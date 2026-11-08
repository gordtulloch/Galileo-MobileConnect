import GalileoCore
import Metal
import MetalKit
import Observation

/// Must match `TileVertex` in Stars.metal.
struct SkyTileVertex {
    var direction: SIMD3<Float>
    var place: SIMD2<Float>
}

/// The survey tiles held for drawing: textures and their sky-shaped meshes, fetched from the Master as views need them
/// (`MAPP-SKY-070`). In memory only, because the pictures aren't ours to keep.
@MainActor @Observable
final class SkySurveyLayer {
    struct Drawable {
        let texture: MTLTexture
        let mesh: MTLBuffer
    }

    /// Whose pictures these are, once there are some.
    private(set) var credit: String?
    @ObservationIgnored weak var view: MTKView?
    @ObservationIgnored let device = MTLCreateSystemDefaultDevice()
    @ObservationIgnored private(set) lazy var indices: MTLBuffer? = makeIndices()

    @ObservationIgnored private let api: MasterAPI
    @ObservationIgnored private var held: [SkyTile: Drawable] = [:]
    @ObservationIgnored private var lastUsed: [SkyTile: Int] = [:]
    @ObservationIgnored private var clock = 0
    @ObservationIgnored private var fetching: [SkyTile: Task<Void, Never>] = [:]
    @ObservationIgnored private var retryAfter: [SkyTile: Date] = [:]

    private static let meshCells = 8
    private static let simultaneous = 4
    private static let heldLimit = 96         // a megabyte each
    private static let retrySeconds: TimeInterval = 60
    static let indexCount = meshCells * meshCells * 6

    init(api: MasterAPI) { self.api = api }

    func stop() {
        fetching.values.forEach { $0.cancel() }
        fetching = [:]
    }

    /// What to draw for a view, coarsest first, so that a finer tile covers its parent. A tile not here yet is drawn from
    /// the nearest ancestor that is, and asked for.
    func drawables(for wanted: [SkyTile], fetching: Bool = true) -> [Drawable] {
        clock += 1
        var chosen = Set<SkyTile>()
        for tile in wanted {
            var candidate: SkyTile? = tile
            while let current = candidate, held[current] == nil { candidate = current.parent }
            if let candidate {
                chosen.insert(candidate)
                lastUsed[candidate] = clock
            }
        }
        if fetching { request(wanted) }
        return chosen.sorted { $0.order < $1.order }.compactMap { held[$0] }
    }

    private func request(_ wanted: [SkyTile]) {
        // Tiles the view has moved away from aren't worth finishing.
        let current = Set(wanted)
        for (tile, task) in fetching where !current.contains(tile) {
            task.cancel()
            fetching[tile] = nil
        }
        let now = Date()
        for tile in wanted where fetching.count < Self.simultaneous {
            guard held[tile] == nil, fetching[tile] == nil, (retryAfter[tile] ?? .distantPast) <= now else { continue }
            let api = self.api
            fetching[tile] = Task { [weak self] in
                let image = await api.surveyTile(tile)
                guard !Task.isCancelled else { return }
                self?.arrived(tile, image)
            }
        }
    }

    private func arrived(_ tile: SkyTile, _ image: SkyTileImage?) {
        guard let image, let device else {
            finished(tile, failed: true)
            return
        }
        credit = image.credit
        // Decoding is done away from the main thread.
        let options: [MTKTextureLoader.Option: Any] = [
            .SRGB: false, .textureUsage: MTLTextureUsage.shaderRead.rawValue, .textureStorageMode: MTLStorageMode.private.rawValue,
        ]
        MTKTextureLoader(device: device).newTexture(data: image.jpeg, options: options) { [weak self] texture, _ in
            nonisolated(unsafe) let loaded = texture
            Task { @MainActor [weak self] in
                guard let self else { return }
                guard let loaded, let mesh = self.makeMesh(tile) else {
                    self.finished(tile, failed: true)
                    return
                }
                self.held[tile] = Drawable(texture: loaded, mesh: mesh)
                self.lastUsed[tile] = self.clock
                self.trim()
                self.finished(tile, failed: false)
            }
        }
    }

    private func finished(_ tile: SkyTile, failed: Bool) {
        fetching[tile] = nil
        if failed { retryAfter[tile] = Date().addingTimeInterval(Self.retrySeconds) }
        view?.setNeedsDisplay()  // which also asks for the next ones
    }

    private func trim() {
        guard held.count > Self.heldLimit else { return }
        for tile in held.keys.sorted(by: { (lastUsed[$0] ?? 0) < (lastUsed[$1] ?? 0) }).prefix(held.count - Self.heldLimit) {
            held[tile] = nil
            lastUsed[tile] = nil
        }
    }

    /// A grid of real sky directions: each vertex then goes through the same projection as the stars, so a tile can't
    /// disagree with them, and the cell's curved edges come out curved.
    private func makeMesh(_ tile: SkyTile) -> MTLBuffer? {
        let cells = Self.meshCells
        var vertices: [SkyTileVertex] = []
        vertices.reserveCapacity((cells + 1) * (cells + 1))
        for i in 0...cells {
            for j in 0...cells {
                let fx = Double(i) / Double(cells)
                let fy = Double(j) / Double(cells)
                let d = SkySurvey.direction(of: tile, fx: fx, fy: fy)
                // In the image the column is fy and the row fx: measured against catalog stars on the real tiles.
                vertices.append(SkyTileVertex(direction: SIMD3(Float(d.x), Float(d.y), Float(d.z)), place: SIMD2(Float(fy), Float(fx))))
            }
        }
        return device?.makeBuffer(bytes: vertices, length: vertices.count * MemoryLayout<SkyTileVertex>.stride, options: .storageModeShared)
    }

    private func makeIndices() -> MTLBuffer? {
        let cells = Self.meshCells
        var list: [UInt16] = []
        for i in 0..<cells {
            for j in 0..<cells {
                let a = UInt16(i * (cells + 1) + j)
                let b = a + 1
                let c = a + UInt16(cells + 1)
                let d = c + 1
                list += [a, b, c, b, d, c]
            }
        }
        return device?.makeBuffer(bytes: list, length: list.count * MemoryLayout<UInt16>.stride, options: .storageModeShared)
    }
}
