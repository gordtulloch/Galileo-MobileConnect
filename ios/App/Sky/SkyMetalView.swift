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
}

/// The star field, drawn only when something changes: a telescope's tablet shouldn't run the GPU for a still sky.
struct SkyMetalView: UIViewRepresentable {
    let stars: [SIMD4<Float>]
    let starsVersion: Int
    let camera: SkyCamera
    let site: SkySite
    let time: Date
    @Environment(\.theme) private var theme

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
        view.setNeedsDisplay()
    }

    @MainActor
    final class Coordinator: NSObject, MTKViewDelegate {
        let device = MTLCreateSystemDefaultDevice()
        private lazy var queue = device?.makeCommandQueue()
        private lazy var pipeline: MTLRenderPipelineState? = makePipeline()
        private var buffer: MTLBuffer?
        private var count = 0
        private var uploaded = -1
        private var camera = SkyCamera()
        private var site = SkySite(latitude: 0, longitude: 0)
        private var time = Date()
        private var color = SIMD4<Float>(1, 0, 0, 1)

        private func makePipeline() -> MTLRenderPipelineState? {
            guard let device, let library = device.makeDefaultLibrary() else { return nil }
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = library.makeFunction(name: "starVertex")
            descriptor.fragmentFunction = library.makeFunction(name: "starFragment")
            let attachment = descriptor.colorAttachments[0]
            attachment?.pixelFormat = .bgra8Unorm
            attachment?.isBlendingEnabled = true
            attachment?.sourceRGBBlendFactor = .sourceAlpha
            attachment?.destinationRGBBlendFactor = .oneMinusSourceAlpha
            attachment?.sourceAlphaBlendFactor = .one
            attachment?.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            return try? device.makeRenderPipelineState(descriptor: descriptor)
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
            if let pipeline, let buffer, count > 0, size.width > 0, size.height > 0 {
                var uniforms = StarUniforms(
                    equatorialToHorizon: SkyGeometry.equatorialToHorizon(site: site, at: time),
                    horizonToView: camera.horizonToView,
                    viewport: SIMD2(Float(size.width), Float(size.height)),
                    scale: Float(camera.scale(viewShorterSide: min(size.width, size.height))),
                    limitingMagnitude: SkyModel.limitingMagnitude(level: camera.levelOfDetail),
                    contentScale: Float(view.contentScaleFactor),
                    color: color)
                encoder.setRenderPipelineState(pipeline)
                encoder.setVertexBuffer(buffer, offset: 0, index: 0)
                encoder.setVertexBytes(&uniforms, length: MemoryLayout<StarUniforms>.stride, index: 1)
                encoder.setFragmentBytes(&uniforms, length: MemoryLayout<StarUniforms>.stride, index: 1)
                encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: count)
            }
            encoder.endEncoding()
            commands.present(drawable)
            commands.commit()
        }
    }
}
