import Charts
import GalileoCore
import SwiftUI
import UIKit

/// The guider service and its error trace (`MAPP-GUI-*`).
struct GuidingScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScreenScroll("Guiding") {
            ReadOnlyHint()
            Columns {
                Card("Guide error") { GuideTrace() }
            } right: {
                Card("Control") {
                    HStack(spacing: 8) {
                        OperationButton(title: "Connect", operation: "guiding.connect", icon: "power")
                        OperationButton(title: "Disconnect", operation: "guiding.disconnect", icon: "poweroff")
                    }
                    HStack(spacing: 8) {
                        OperationButton(title: "Loop", operation: "guiding.loop", icon: "repeat")
                        OperationButton(title: "Start guiding", operation: "guiding.start", icon: "play", kind: .prominent)
                        OperationButton(title: "Stop", operation: "guiding.stop", icon: "stop")
                    }
                    HStack(spacing: 8) {
                        OperationButton(title: "Dither", operation: "guiding.dither", icon: "arrow.up.and.down.and.arrow.left.and.right")
                        OperationButton(title: "Recalibrate", operation: "guiding.recalibrate", icon: "arrow.triangle.2.circlepath")
                    }
                    OperationButton(title: "Find star", operation: "guiding.find_star", icon: "sparkle.magnifyingglass")
                }
            }
            Card("Guide star") { GuidePicture() }
        }
    }
}

/// The state, RMS error and star SNR on one small line, laid over the foot of the trace.
private struct GuideReadout: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    private func number(_ key: String, _ digits: Int) -> String? {
        model.value(key)?.value.doubleValue.map { String(format: "%.\(digits)f", $0) }
    }

    var body: some View {
        let parts = [
            model.value("guiding.state")?.value.displayText,
            number("guiding.rms_ra", 2).map { "RA \($0)″" },
            number("guiding.rms_dec", 2).map { "Dec \($0)″" },
            number("guiding.star_snr", 0).map { "SNR \($0)" },
        ].compactMap { $0 }
        Text(parts.isEmpty ? "No guide data yet." : parts.joined(separator: "   "))
            .font(.caption2).foregroundStyle(theme.textSecondary)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(theme.surface.opacity(0.75))
            .accessibilityIdentifier("guide.readout")
    }
}

private struct GuideTrace: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    private struct Sample: Identifiable {
        let id: String
        let time: Date
        let value: Double
        let axis: String
    }

    var body: some View {
        let points = (model.workingPier.flatMap { model.snapshot.live.piers[$0]?.series["guiding.error"] } ?? []).suffix(120)
        let samples = points.enumerated().flatMap { index, point -> [Sample] in
            var row: [Sample] = []
            if let ra = point.value["ra"]?.doubleValue { row.append(Sample(id: "r\(index)", time: point.asOf, value: ra, axis: "RA")) }
            if let dec = point.value["dec"]?.doubleValue { row.append(Sample(id: "d\(index)", time: point.asOf, value: dec, axis: "Dec")) }
            return row
        }
        if samples.isEmpty {
            GuideReadout()
            Note("The trace appears here while guiding.")
        } else {
            // Told apart by dash as well as colour: Night has only the one hue.
            Chart(samples) { sample in
                LineMark(x: .value("Time", sample.time), y: .value("Error", sample.value), series: .value("Axis", sample.axis))
                    .foregroundStyle(sample.axis == "RA" ? theme.accent : theme.good)
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: sample.axis == "RA" ? [] : [5, 3]))
            }
            .chartYScale(domain: -2...2)
            .themedAxes(theme)
            .frame(height: 220)
            .opacity(model.snapshot.isConnected ? 1 : 0.4)
            .overlay(alignment: .bottom) { GuideReadout().padding(.bottom, 28) }
            .accessibilityLabel("Guide error trace")
            .accessibilityIdentifier("guide.trace")
            Note("Solid: RA. Dashed: Dec. Arc-seconds.")
        }
    }
}

/// The guide star as the Master has it: PHD2's close-up of the star, which is all PHD2 sends a network client. Small,
/// and not tappable: the star is chosen in PHD2 (or by the Master's Find star).
private struct GuidePicture: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?
    /// The picture's size in the Master's pixels: the guide frame, or the cut-out.
    @State private var pixelSize: CGSize?
    @State private var problem: String?

    private var state: String { model.value("guiding.state")?.value.displayText ?? "" }
    private var request: String { "\(model.workingPier ?? "")/\(state)/\(model.value("guiding.rms_ra")?.value.displayText ?? "")" }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack {
                theme.background
                if let image, let pixelSize {
                    GeometryReader { box in
                        let fit = Self.fit(pixelSize, in: box.size)
                        Image(uiImage: image)
                            .resizable()
                            .interpolation(.none)
                            .frame(width: fit.width, height: fit.height)
                            .colorMultiply(theme.imageTint)
                            .position(x: box.size.width / 2, y: box.size.height / 2)
                            .accessibilityLabel("Guide star")
                            .accessibilityIdentifier("guide.picture")
                    }
                } else if let problem {
                    Note(problem).padding()
                } else {
                    ProgressView().tint(theme.text)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 150)
        }
        .task(id: request) { await load() }
        .task {
            // The Master only keeps fetching the picture while someone is looking.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                if !Task.isCancelled { await load() }
            }
        }
    }

    private func load() async {
        if let star = await model.read("guiding.star"), star["available"]?.boolValue != false,
                  let width = star["width"]?.doubleValue.map(Int.init), let height = star["height"]?.doubleValue.map(Int.init),
                  let encoded = star["pixels"]?.stringValue, let data = Data(base64Encoded: encoded),
                  let decoded = Self.render(pixels: data, width: width, height: height) {
            image = decoded
            pixelSize = CGSize(width: width, height: height)
            problem = nil
        } else if image == nil || pixelSize == nil {
            problem = "No guide star yet."
        }
    }

    /// Where an image of `size` lands when it is as large as it can be inside `box`, keeping its shape.
    static func fit(_ size: CGSize, in box: CGSize) -> CGSize {
        guard size.width > 0, size.height > 0 else { return .zero }
        let ratio = min(box.width / size.width, box.height / size.height)
        return CGSize(width: size.width * ratio, height: size.height * ratio)
    }

    /// The cut-out's 16-bit little-endian pixels as a grey picture, stretched from the background to just under the
    /// brightest, with a square root so a faint star isn't lost beside a bright one (the desktop does the same).
    static func render(pixels: Data, width: Int, height: Int) -> UIImage? {
        let count = width * height
        guard width > 0, height > 0, pixels.count == count * 2 else { return nil }
        var values = [Double](repeating: 0, count: count)
        pixels.withUnsafeBytes { raw in
            for index in 0..<count {
                values[index] = Double(UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: index * 2, as: UInt16.self)))
            }
        }
        let sorted = values.sorted()
        let low = sorted[count / 2]
        var high = sorted[min(count - 1, Int(Double(count) * 0.998))]
        if high <= low { high = low + 1 }
        let grey = values.map { UInt8(255 * pow(min(1, max(0, ($0 - low) / (high - low))), 0.5)) }
        guard let provider = CGDataProvider(data: Data(grey) as CFData),
              let cg = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: width,
                               space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: 0), provider: provider,
                               decode: nil, shouldInterpolate: false, intent: .defaultIntent)
        else { return nil }
        return UIImage(cgImage: cg)
    }
}
