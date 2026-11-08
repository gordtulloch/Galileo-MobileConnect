import GalileoCore
import MetalKit
import SwiftUI
import simd

/// Must match `Uniforms` in Stars.metal.
private struct StarUniforms {
    var equatorialToHorizon: simd_float3x3
    var horizonToView: simd_float3x3
    var viewport: SIMD2<Float>
    var scale: Float
    var limitingMagnitude: Float
    var contentScale: Float
    var color: SIMD4<Float>
    var backgroundTint: SIMD4<Float>
    var horizonFill: SIMD4<Float>
    var landNear: SIMD4<Float>
    var landFar: SIMD4<Float>
}

/// The survey, the ground and the star field, drawn only when something changes: a telescope's tablet shouldn't run the GPU for a still sky.
struct SkyMetalView: UIViewRepresentable {
    let stars: [SIMD4<Float>]
    let starsVersion: Int
    let camera: SkyCamera
    let site: SkySite
    let time: Date
    /// The tiles to draw behind the stars, if wanted.
    let survey: SkySurveyLayer?
    let showGround: Bool
    @Environment(\.theme) private var theme

    /// Enough to see what the profile blocks, and not so much as to hide what's behind it.
    private static let fillOpacity: Float = 0.28

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: context.coordinator.device)
        view.delegate = context.coordinator
        view.isPaused = true
        view.enableSetNeedsDisplay = true
        view.framebufferOnly = true
        view.isOpaque = true
        view.colorPixelFormat = .bgra8Unorm
        view.isUserInteractionEnabled = false  // gestures belong to the SwiftUI layer above
        return view
    }

    func updateUIView(_ view: MTKView, context: Context) {
        let environment = context.environment
        let star = theme.text.resolve(in: environment)
        let background = theme.background.resolve(in: environment)
        view.clearColor = MTLClearColor(
            red: Double(background.red), green: Double(background.green), blue: Double(background.blue), alpha: 1)
        context.coordinator.update(
            stars: stars, version: starsVersion, camera: camera, site: site, time: time,
            color: SIMD4(star.red, star.green, star.blue, 1))
        let tint = theme.imageTint.resolve(in: environment)
        let fill = theme.horizonFill.resolve(in: environment)
        let near = theme.landNear.resolve(in: environment)
        let far = theme.landFar.resolve(in: environment)
        // A night sky on the Light theme's pale page would be a black hole in it: there the stars stay as they are.
        survey?.view = view
        context.coordinator.update(
            survey: background.red < 0.5 ? survey : nil, tint: SIMD4(tint.red, tint.green, tint.blue, 1),
            fill: SIMD4(fill.red * Self.fillOpacity, fill.green * Self.fillOpacity, fill.blue * Self.fillOpacity, Self.fillOpacity),
            near: SIMD4(near.red, near.green, near.blue, showGround ? 1 : 0), far: SIMD4(far.red, far.green, far.blue, 1))
        view.setNeedsDisplay()
    }

    @MainActor
    final class Coordinator: NSObject, MTKViewDelegate {
        let device = MTLCreateSystemDefaultDevice()
        private lazy var queue = device?.makeCommandQueue()
        private lazy var pipeline: MTLRenderPipelineState? = makePipeline(vertex: "starVertex", fragment: "starFragment", blend: .sourceAlpha)
        private lazy var tilePipeline: MTLRenderPipelineState? = makePipeline(vertex: "tileVertex", fragment: "tileFragment", blend: nil)
        private lazy var groundPipeline: MTLRenderPipelineState? = makePipeline(vertex: "groundVertex", fragment: "groundFragment", blend: .one)
        private var survey: SkySurveyLayer?
        private var tint = SIMD4<Float>(1, 1, 1, 1)
        private var fill = SIMD4<Float>(0, 0, 0, 0)
        private var near = SIMD4<Float>(0, 0, 0, 0)
        private var far = SIMD4<Float>(0, 0, 0, 0)
        private var buffer: MTLBuffer?
        private var count = 0
        private var uploaded = -1
        private var camera = SkyCamera()
        private var site = SkySite(latitude: 0, longitude: 0)
        private var time = Date()
        private var color = SIMD4<Float>(1, 0, 0, 1)

        /// `blend`: what the source's colour is multiplied by, over one minus its alpha of what's there. `nil` replaces.
        private func makePipeline(vertex: String, fragment: String, blend: MTLBlendFactor?) -> MTLRenderPipelineState? {
            guard let device, let library = device.makeDefaultLibrary() else { return nil }
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = library.makeFunction(name: vertex)
            descriptor.fragmentFunction = library.makeFunction(name: fragment)
            let attachment = descriptor.colorAttachments[0]
            attachment?.pixelFormat = .bgra8Unorm
            if let blend {
                attachment?.isBlendingEnabled = true
                attachment?.sourceRGBBlendFactor = blend
                attachment?.destinationRGBBlendFactor = .oneMinusSourceAlpha
                attachment?.sourceAlphaBlendFactor = .one
                attachment?.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            }
            return try? device.makeRenderPipelineState(descriptor: descriptor)
        }

        func update(survey: SkySurveyLayer?, tint: SIMD4<Float>, fill: SIMD4<Float>, near: SIMD4<Float>, far: SIMD4<Float>) {
            self.survey = survey
            self.tint = tint
            self.fill = fill
            self.near = near
            self.far = far
        }

        func update(stars: [SIMD4<Float>], version: Int, camera: SkyCamera, site: SkySite, time: Date, color: SIMD4<Float>) {
            self.camera = camera
            self.site = site
            self.time = time
            self.color = color
            guard version != uploaded else { return }
            uploaded = version
            count = stars.count
            buffer = stars.isEmpty ? nil : device?.makeBuffer(
                bytes: stars, length: stars.count * MemoryLayout<SIMD4<Float>>.stride, options: .storageModeShared)
        }

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
            view.setNeedsDisplay()
        }

        func draw(in view: MTKView) {
            guard let queue, let descriptor = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
                let commands = queue.makeCommandBuffer(), let encoder = commands.makeRenderCommandEncoder(descriptor: descriptor)
            else { return }
            let size = view.bounds.size
            if size.width > 0, size.height > 0 {
                var uniforms = StarUniforms(
                    equatorialToHorizon: SkyGeometry.equatorialToHorizon(site: site, at: time),
                    horizonToView: camera.horizonToView,
                    viewport: SIMD2(Float(size.width), Float(size.height)),
                    scale: Float(camera.scale(viewHeight: size.height)),
                    limitingMagnitude: SkyModel.limitingMagnitude(level: camera.levelOfDetail),
                    contentScale: Float(view.contentScaleFactor),
                    color: color,
                    backgroundTint: tint,
                    horizonFill: fill,
                    landNear: near,
                    landFar: far)
                encoder.setVertexBytes(&uniforms, length: MemoryLayout<StarUniforms>.stride, index: 1)
                encoder.setFragmentBytes(&uniforms, length: MemoryLayout<StarUniforms>.stride, index: 1)
                drawSurvey(encoder, size: size, scale: Double(view.contentScaleFactor))
                if let pipeline, let buffer, count > 0 {
                    encoder.setRenderPipelineState(pipeline)
                    encoder.setVertexBuffer(buffer, offset: 0, index: 0)
                    encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: count)
                }
                if let groundPipeline {
                    var skyline = (0..<72).map { Float(site.skylineAltitude(azimuth: Double($0) * 5) * .pi / 180) }
                    encoder.setRenderPipelineState(groundPipeline)
                    encoder.setFragmentBytes(&skyline, length: skyline.count * MemoryLayout<Float>.stride, index: 2)
                    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
                }
            }
            encoder.endEncoding()
            commands.present(drawable)
            commands.commit()
        }

        private func drawSurvey(_ encoder: MTLRenderCommandEncoder, size: CGSize, scale: Double) {
            guard let survey, let tilePipeline, let indices = survey.indices else { return }
            let wanted = SkySurvey.plan(camera: camera, site: site, at: time, size: size, pixelsPerPoint: scale)
            encoder.setRenderPipelineState(tilePipeline)
            for tile in survey.drawables(for: wanted, fetching: camera.fieldOfView >= SkySurvey.fetchAboveFieldDegrees) {
                encoder.setVertexBuffer(tile.mesh, offset: 0, index: 0)
                encoder.setFragmentTexture(tile.texture, index: 0)
                encoder.drawIndexedPrimitives(
                    type: .triangle, indexCount: SkySurveyLayer.indexCount, indexType: .uint16, indexBuffer: indices, indexBufferOffset: 0)
            }
        }
    }
}
