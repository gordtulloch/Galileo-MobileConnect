import Charts
import GalileoCore
import SwiftUI
import UIKit

/// Capture, and the current frame as the Master renders it (`MAPP-IMG-*`). The frame gets the whole screen: the
/// settings slide down over it from the top, and progress is drawn along its bottom edge.
struct ImagingScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var panelOpen = false
    @State private var more = false
    @State private var stretch = 50.0
    /// The live stack rather than the latest exposure, when there is one to show.
    @State private var showStack = false

    private var stackFrames: Int? { model.value("imaging.stack.count")?.value.doubleValue.map(Int.init) }

    var body: some View {
        ZStack(alignment: .top) {
            FramePicture(stretch: stretch, stack: showStack && stackFrames != nil, stackFrames: stackFrames ?? 0)
                .contentShape(Rectangle())
                .onTapGesture { if panelOpen { panelOpen = false } }
                .overlay(alignment: .topLeading) { statistics }
                .overlay(alignment: .bottom) { VStack(spacing: 6) { picker; progress } }
            VStack(spacing: 6) {
                if let reason = model.workingPier.flatMap({ model.snapshot.unavailableReason(pier: $0) }) {
                    Banner(icon: "exclamationmark.triangle", text: "\(model.pier?.name ?? "This Pier"): \(reason)")
                }
                if panelOpen { panel.transition(.move(edge: .top)) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .padding(panelOpen ? 0 : 8)
            if !panelOpen {
                Button { panelOpen = true } label: {
                    Image(systemName: "slider.horizontal.3").frame(minWidth: Layout.minimumTarget, minHeight: Layout.minimumTarget)
                }
                .buttonStyle(ActionStyle())
                .fixedSize()
                .padding(8)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                .accessibilityLabel("Capture settings")
                .accessibilityIdentifier("imaging.panel")
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: panelOpen)
        // A stack that has just begun is what the user is waiting to watch; the latest exposure is one tap away.
        .onChange(of: stackFrames == nil) { _, none in showStack = !none }
        .background(theme.background)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("Imaging").font(.headline).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(theme.surface, for: .navigationBar)
    }

    /// Small, and in the top corner where the settings button isn't.
    @ViewBuilder private var statistics: some View {
        let stars = model.value("imaging.stats.stars"), hfr = model.value("imaging.stats.hfr"), fwhm = model.value("imaging.stats.fwhm")
        if stars != nil || hfr != nil || fwhm != nil {
            VStack(alignment: .leading, spacing: 0) {
                if let stars { Text("Stars \(stars.value.displayText)") }
                if let hfr, let value = hfr.value.doubleValue { Text("HFR \(value, format: .number.precision(.fractionLength(2)))") }
                if let fwhm, let value = fwhm.value.doubleValue { Text("FWHM \(value, format: .number.precision(.fractionLength(2)))") }
            }
            .font(.caption2.weight(.semibold))
            .foregroundStyle(theme.text)
            .padding(5)
            .background(theme.background.opacity(0.6), in: RoundedRectangle(cornerRadius: 6))
            .padding(8)
            .allowsHitTesting(false)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("imaging.stats")
        }
    }

    /// Which picture, only while there are two to choose from.
    @ViewBuilder private var picker: some View {
        if stackFrames != nil {
            HStack(spacing: 0) {
                choice("Frame", on: !showStack) { showStack = false }
                choice("Stack", on: showStack) { showStack = true }
            }
            .environment(\.compactControls, true)
            .fixedSize()
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.horizontal, 12)
        }
    }

    private func choice(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .buttonStyle(ActionStyle(kind: on ? .prominent : .normal))
            .accessibilityValue(on ? "Showing" : "")
            .accessibilityIdentifier("imaging.show.\(title)")
    }

    /// Exposure and live stack, as bars along the bottom of the picture and nothing more.
    @ViewBuilder private var progress: some View {
        let exposing = model.value("imaging.exposure_progress")?.value.doubleValue
        let series = model.value("imaging.progress")?.value.doubleValue
        let stacked = model.value("imaging.stack.progress")?.value.doubleValue
        if exposing != nil || series != nil || stacked != nil || model.liveLoopRunning {
            VStack(alignment: .leading, spacing: 4) {
                if let exposing {
                    let seconds = model.value("imaging.exposure_s")?.value.doubleValue
                    bar("Exposure", detail: seconds.map { "\($0.formatted(.number.precision(.fractionLength(0...1)))) s" }, value: exposing)
                }
                if let series {
                    bar("Series", detail: frameText, value: series)
                } else if exposing == nil, model.liveLoopRunning {
                    Text("Live loop").font(.caption2.weight(.semibold)).foregroundStyle(theme.text)
                }
                if let stacked {
                    bar("Live stack", detail: stackText, value: stacked)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(theme.background.opacity(0.6))
            .allowsHitTesting(false)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("imaging.progress")
        }
    }

    private var frameText: String? {
        guard let frame = model.value("imaging.frame")?.value.displayText, let count = model.value("imaging.count")?.value.displayText
        else { return nil }
        return "frame \(frame) of \(count)"
    }

    private var stackText: String? {
        guard let frames = stackFrames else { return nil }
        var text = "\(frames) frame\(frames == 1 ? "" : "s")"
        if let total = model.value("imaging.stack.total_exposure_s")?.value.doubleValue {
            text += ", " + Duration.seconds(total).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .narrow))
        }
        return text
    }

    private func bar(_ label: String, detail: String?, value: Double) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text([label, detail].compactMap { $0 }.joined(separator: " · "))
                .font(.caption2.weight(.semibold)).foregroundStyle(theme.text)
            ThemedProgress(value: value, label: label)
        }
    }

    /// The settings that matter every time, small; the rest one tap further.
    private var panel: some View {
        VStack(alignment: .leading, spacing: 6) {
            ReadOnlyHint()
            OperationForm(
                title: "Start", operation: "imaging.capture", icon: "camera",
                fields: ["seconds", "count", "frame_type", "gain", "offset", "live_stack"], kind: .prominent)
            HStack(spacing: 6) {
                OperationButton(title: "Stop", operation: "imaging.stop", icon: "stop")
                Button(more ? "Less" : "More") { more.toggle() }
                    .buttonStyle(ActionStyle())
                    .accessibilityIdentifier("imaging.more")
                Button("Hide") { panelOpen = false }
                    .buttonStyle(ActionStyle())
                    .accessibilityIdentifier("imaging.panel.close")
            }
            if more {
                Histogram()
                StretchSlider(label: "Stretch", value: $stretch, range: 0...100)
                HStack(spacing: 6) {
                    Button("Reset stretch") { stretch = 50 }.buttonStyle(ActionStyle())
                    OperationButton(title: "Abort exposure", operation: "camera.abort", icon: "xmark")
                }
                OperationForm(title: "Single exposure", operation: "camera.expose", icon: "camera.shutter.button", fields: ["seconds", "binning"])
                LiveLoop()
            }
        }
        .environment(\.compactControls, true)
        .padding(8)
        .frame(maxWidth: .infinity)
        .background(theme.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.border).frame(height: 1) }
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
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    HStack {
                        Text("Loop (s)").font(.footnote).foregroundStyle(theme.textSecondary)
                        TextField("", text: $seconds, prompt: Text("2").foregroundStyle(theme.textSecondary))
                            .font(.footnote)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numbersAndPunctuation)
                            .foregroundStyle(theme.text)
                            .accessibilityLabel("Live loop seconds")
                            .accessibilityIdentifier("liveloop.seconds")
                    }
                    .frame(minHeight: Layout.compactTarget)
                    .padding(.horizontal, 8)
                    .background(theme.raised, in: RoundedRectangle(cornerRadius: 7))
                    Button {
                        if model.liveLoopRunning {
                            model.stopLiveLoop()
                        } else if let value = Double(seconds), value > 0 {
                            message = model.startLiveLoop(seconds: value)
                        } else {
                            message = "Seconds must be a number above zero."
                        }
                    } label: {
                        Label(model.liveLoopRunning ? "Stop loop" : "Live loop", systemImage: model.liveLoopRunning ? "stop" : "repeat")
                    }
                    .buttonStyle(ActionStyle(kind: model.liveLoopRunning ? .destructive : .normal))
                    .accessibilityIdentifier("liveloop")
                }
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

/// The current frame, as large as the space it's given.
private struct FramePicture: View {
    let stretch: Double
    /// The live stack rather than the latest exposure, and how many frames are in it (a new one is a new picture).
    let stack: Bool
    let stackFrames: Int
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?
    @State private var frameSize: CGSize?
    @State private var problem: String?

    /// A new frame, Pier or stretch means a new picture from the Master.
    private var request: String {
        "\(model.workingPier ?? "")/\(model.value("imaging.frame")?.value.displayText ?? "")/\(model.record(of: "imaging.capture")?.id ?? "")/\(model.record(of: "imaging.capture")?.state.isOpen == true)/\(Int(stretch))/\(stack)/\(stackFrames)"
    }

    var body: some View {
        ZStack {
            theme.background
            if let image {
                ZoomableImage(
                    image: image, frameSize: frameSize, stretch: Int(stretch), generation: request,
                    loadTile: { [stretch, stack] level, x, y in
                        await model.tile(level: level, x: x, y: y, stretch: Int(stretch), stack: stack)
                    })
                    .colorMultiply(theme.imageTint)
                    .accessibilityLabel("Current frame. Pinch to zoom.")
                    .accessibilityIdentifier("preview.image")
            } else if let problem {
                Note(problem).padding()
            } else {
                ProgressView().tint(theme.text)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: request) {
            // Give a dragged slider a moment to settle; the current image stays until the new one arrives.
            try? await Task.sleep(for: .milliseconds(180))
            guard !Task.isCancelled else { return }
            if let shot = await model.preview(width: Int(1600 * min(scale, 2) / 2), stretch: Int(stretch), stack: stack), let decoded = UIImage(data: shot.data) {
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
