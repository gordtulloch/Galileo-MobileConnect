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
    @State private var problem: String?
    @State private var stretch = Stretch()

    /// A new frame, Pier or stretch means a new picture from the Master.
    private var request: String {
        "\(model.workingPier ?? "")/\(model.value("imaging.frame")?.value.displayText ?? "")/\(stretch.black)/\(stretch.mid)/\(stretch.white)"
    }

    var body: some View {
        Card("Current frame") {
            ZStack {
                theme.background
                if let image {
                    ZoomableImage(image: image)
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

            StretchSlider(label: "Black point", value: $stretch.black, range: 0...0.5)
            StretchSlider(label: "Midtone", value: $stretch.mid, range: 0.05...0.95)
            StretchSlider(label: "White point", value: $stretch.white, range: 0.5...1)
            Button("Reset stretch") { stretch = Stretch() }
                .buttonStyle(ActionStyle())
        }
        .task(id: request) {
            // Give a dragged slider a moment to settle; the current image stays until the new one arrives.
            try? await Task.sleep(for: .milliseconds(180))
            guard !Task.isCancelled else { return }
            if let data = await model.preview(width: Int(1200 * min(scale, 2) / 2), stretch: stretch), let decoded = UIImage(data: data) {
                image = decoded
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
            Text("\(label): \(value, format: .number.precision(.fractionLength(2)))").font(.footnote).foregroundStyle(theme.textSecondary)
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

/// Native scroll and zoom physics (ADR-IOS-005).
private struct ZoomableImage: UIViewRepresentable {
    let image: UIImage

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 8
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
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        context.coordinator.imageView?.image = image
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var imageView: UIImageView?
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
    }
}
