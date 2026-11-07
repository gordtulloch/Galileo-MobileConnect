import Charts
import GalileoCore
import SwiftUI
import UIKit

/// Capture, and the current frame as the Master renders it (`MAPP-IMG-*`).
struct ImagingScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScreenScroll("Imaging") {
            ReadOnlyHint()
            Columns {
                PreviewCard()
            } right: {
                status
                capture
            }
        }
    }

    private var status: some View {
        Card("Now") {
            LiveRow(label: "State", key: "imaging.state")
            LiveRow(label: "Camera", key: "camera.state")
            LiveRow(label: "Frame", key: "imaging.frame")
            LiveRow(label: "Of", key: "imaging.count")
            LiveRow(label: "Exposure", key: "imaging.exposure_s", unit: "s")
            ProgressRow(key: "imaging.progress", label: "Exposure progress")
            LiveRow(label: "Stars", key: "imaging.stats.stars")
            LiveRow(label: "HFR", key: "imaging.stats.hfr", digits: 2)
            LiveRow(label: "FWHM", key: "imaging.stats.fwhm", digits: 2)
        }
    }

    private var capture: some View {
        Card("Capture") {
            OperationForm(
                title: "Start series", operation: "imaging.capture", icon: "camera",
                fields: ["seconds", "count", "gain", "offset", "frame_type"], kind: .prominent)
            OperationButton(title: "Stop series", operation: "imaging.stop", icon: "stop")
            OperationForm(title: "Single exposure", operation: "camera.expose", icon: "camera.shutter.button", fields: ["seconds", "binning"])
            OperationButton(title: "Abort exposure", operation: "camera.abort", icon: "xmark")
            LiveLoop()
        }
    }
}

/// `MAPP-IMG-020`: one exposure after another, each shown as it arrives.
private struct LiveLoop: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var seconds = "2"
    @State private var message: String?

    var body: some View {
        if model.canOperate, model.operation("imaging.capture") != nil {
            VStack(alignment: .leading, spacing: 6) {
                Text("Live loop").font(.subheadline.weight(.semibold))
                HStack {
                    Text("Seconds (s)").foregroundStyle(theme.textSecondary)
                    TextField("", text: $seconds, prompt: Text("2").foregroundStyle(theme.textSecondary))
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numbersAndPunctuation)
                        .foregroundStyle(theme.text)
                        .accessibilityLabel("Live loop seconds")
                        .accessibilityIdentifier("liveloop.seconds")
                }
                .frame(minHeight: Layout.minimumTarget)
                .padding(.horizontal, 10)
                .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
                Button {
                    if model.liveLoopRunning {
                        model.stopLiveLoop()
                    } else if let value = Double(seconds), value > 0 {
                        message = model.startLiveLoop(seconds: value)
                    } else {
                        message = "Seconds must be a number above zero."
                    }
                } label: {
                    Label(model.liveLoopRunning ? "Stop live loop" : "Start live loop", systemImage: model.liveLoopRunning ? "stop" : "repeat")
                }
                .buttonStyle(ActionStyle(kind: model.liveLoopRunning ? .destructive : .normal))
                .accessibilityIdentifier("liveloop")
                if let shown = message ?? model.liveLoopMessage { Note(shown) }
            }
        }
    }
}

struct ProgressRow: View {
    let key: String
    let label: String
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        if let view = model.value(key), let value = view.value.doubleValue {
            ThemedProgress(value: value, label: label).opacity(view.stale ? 0.4 : 1)
        }
    }
}

private struct PreviewCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?
    @State private var frameSize: CGSize?
    @State private var problem: String?
    @State private var stretch = 50.0

    /// A new frame, Pier or stretch means a new picture from the Master.
    private var request: String {
        "\(model.workingPier ?? "")/\(model.value("imaging.frame")?.value.displayText ?? "")/\(model.record(of: "imaging.capture")?.id ?? "")/\(model.record(of: "imaging.capture")?.state.isOpen == true)/\(Int(stretch))"
    }

    var body: some View {
        Card("Current frame") {
            ZStack {
                theme.background
                if let image {
                    ZoomableImage(
                        image: image, frameSize: frameSize, stretch: Int(stretch), generation: request,
                        loadTile: { [stretch] level, x, y in
                            await model.tile(level: level, x: x, y: y, stretch: Int(stretch))
                        })
                        .colorMultiply(theme.imageTint)
                        .accessibilityLabel("Current frame. Pinch to zoom.")
                        .accessibilityIdentifier("preview.image")
                } else if let problem {
                    Note(problem)
                } else {
                    ProgressView().tint(theme.text)
                }
            }
            .aspectRatio(1.5, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            Histogram()

            StretchSlider(label: "Stretch", value: $stretch, range: 0...100)
            Button("Reset stretch") { stretch = 50 }
                .buttonStyle(ActionStyle())
        }
        .task(id: request) {
            // Give a dragged slider a moment to settle; the current image stays until the new one arrives.
            try? await Task.sleep(for: .milliseconds(180))
            guard !Task.isCancelled else { return }
            if let shot = await model.preview(width: Int(1200 * min(scale, 2) / 2), stretch: Int(stretch)), let decoded = UIImage(data: shot.data) {
                image = decoded
                if let width = shot.frameWidth, let height = shot.frameHeight {
                    frameSize = CGSize(width: width, height: height)
                }
                problem = nil
            } else if image == nil {
                problem = "No frame yet."
            }
        }
    }
}

private struct StretchSlider: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("\(label): \(Int(value))").font(.footnote).foregroundStyle(theme.textSecondary)
            ThemedSlider(label: label, value: $value, range: range)
        }
    }
}

/// From the Master's numbers, not computed here (`MAPP-IMG-040`).
private struct Histogram: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        if let bins = model.value("imaging.histogram")?.value.arrayValue?.compactMap(\.doubleValue), !bins.isEmpty {
            let highest = max(1, bins.max() ?? 1)
            let accent = theme.accent
            Canvas { context, size in
                let width = size.width / CGFloat(bins.count)
                for (index, bin) in bins.enumerated() {
                    let height = size.height * CGFloat(bin / highest)
                    context.fill(
                        Path(CGRect(x: CGFloat(index) * width, y: size.height - height, width: max(1, width - 1), height: height)),
                        with: .color(accent))
                }
            }
            .frame(height: 56)
            .accessibilityLabel("Histogram")
            .accessibilityIdentifier("histogram")
        }
    }
}

/// Native scroll and zoom physics, with tiles from the Master over the preview as the user zooms (ADR-IOS-005).
private struct ZoomableImage: UIViewRepresentable {
    let image: UIImage
    let frameSize: CGSize?
    let stretch: Int
    let generation: String
    let loadTile: @MainActor (_ level: Int, _ x: Int, _ y: Int) async -> Data?

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 16
        scroll.delegate = context.coordinator
        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        scroll.backgroundColor = .clear
        let view = UIImageView(image: image)
        view.contentMode = .scaleAspectFit
        view.frame = scroll.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.addSubview(view)
        context.coordinator.imageView = view
        context.coordinator.scroll = scroll
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        let coordinator = context.coordinator
        coordinator.imageView?.image = image
        coordinator.frameSize = frameSize
        coordinator.loadTile = loadTile
        if coordinator.generation != generation {
            coordinator.generation = generation
            coordinator.clearTiles()      // the current picture stays until new tiles arrive for the new one
            coordinator.refreshTiles()
        }
    }

    @MainActor
    final class Coordinator: NSObject, UIScrollViewDelegate {
        var imageView: UIImageView?
        weak var scroll: UIScrollView?
        var frameSize: CGSize?
        var generation = ""
        var loadTile: (@MainActor (Int, Int, Int) async -> Data?)?
        private var tiles: [String: UIImageView] = [:]
        private var pending: Set<String> = []
        private let tilePixels: CGFloat = 256
        private let maximumTiles = 24

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) { refreshTiles() }
        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { refreshTiles() }
        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
            if !decelerate { refreshTiles() }
        }

        func clearTiles() {
            tiles.values.forEach { $0.removeFromSuperview() }
            tiles = [:]
            pending = []
        }

        /// Where the frame sits inside the image view, which fits it with bars at the sides or ends.
        private func fitted(in bounds: CGRect, frame: CGSize) -> CGRect {
            let scale = min(bounds.width / frame.width, bounds.height / frame.height)
            let size = CGSize(width: frame.width * scale, height: frame.height * scale)
            return CGRect(x: (bounds.width - size.width) / 2, y: (bounds.height - size.height) / 2, width: size.width, height: size.height)
        }

        func refreshTiles() {
            guard let scroll, let imageView, let frameSize, frameSize.width > 0, let loadTile, scroll.zoomScale > 1.2,
                imageView.bounds.width > 0
            else { return }
            let fit = fitted(in: imageView.bounds, frame: frameSize)
            let pixelsOnScreen = fit.width * scroll.zoomScale * imageView.traitCollection.displayScale
            // Level 0 is full resolution and each level halves it: the coarsest that's still sharp on screen.
            let level = max(0, min(12, Int(floor(log2(frameSize.width / max(1, pixelsOnScreen))))))
            let span = tilePixels * CGFloat(1 << level)         // one tile, in frame pixels
            let perPixel = fit.width / frameSize.width          // image-view points per frame pixel
            let visible = scroll.convert(scroll.bounds, to: imageView).intersection(fit)
            guard !visible.isNull else { return }
            let columns = Int(floor((visible.minX - fit.minX) / perPixel / span))...Int(floor((visible.maxX - fit.minX - 0.01) / perPixel / span))
            let rows = Int(floor((visible.minY - fit.minY) / perPixel / span))...Int(floor((visible.maxY - fit.minY - 0.01) / perPixel / span))
            guard columns.count * rows.count <= maximumTiles else { return }
            for key in tiles.keys where !key.hasPrefix("\(level)/") {
                tiles.removeValue(forKey: key)?.removeFromSuperview()
            }
            let current = generation
            for y in rows {
                for x in columns {
                    let key = "\(level)/\(x)/\(y)"
                    guard tiles[key] == nil, !pending.contains(key) else { continue }
                    pending.insert(key)
                    Task { @MainActor [weak self] in
                        let data = await loadTile(level, x, y)
                        guard let self else { return }
                        self.pending.remove(key)
                        guard self.generation == current, let data, let tile = UIImage(data: data) else { return }
                        let view = UIImageView(image: tile)
                        view.frame = CGRect(
                            x: fit.minX + CGFloat(x) * span * perPixel, y: fit.minY + CGFloat(y) * span * perPixel,
                            width: tile.size.width * CGFloat(1 << level) * perPixel,
                            height: tile.size.height * CGFloat(1 << level) * perPixel)
                        imageView.addSubview(view)
                        self.tiles[key] = view
                    }
                }
            }
        }
    }
}
