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
                Card("Guide star") { GuidePicture() }
                Card("Guide error") {
                    LiveRow(label: "State", key: "guiding.state")
                    LiveRow(label: "RMS RA", key: "guiding.rms_ra", unit: "″", digits: 2)
                    LiveRow(label: "RMS Dec", key: "guiding.rms_dec", unit: "″", digits: 2)
                    LiveRow(label: "Star SNR", key: "guiding.star_snr")
                    GuideTrace()
                }
            } right: {
                Card("Control") {
                    HStack(spacing: 8) {
                        OperationButton(title: "Connect", operation: "guiding.connect", icon: "power")
                        OperationButton(title: "Disconnect", operation: "guiding.disconnect", icon: "poweroff")
                    }
                    HStack(spacing: 8) {
                        OperationButton(title: "Start guiding", operation: "guiding.start", icon: "play", kind: .prominent)
                        OperationButton(title: "Stop guiding", operation: "guiding.stop", icon: "stop")
                    }
                    HStack(spacing: 8) {
                        OperationButton(title: "Dither", operation: "guiding.dither", icon: "arrow.up.and.down.and.arrow.left.and.right")
                        OperationButton(title: "Recalibrate", operation: "guiding.recalibrate", icon: "arrow.triangle.2.circlepath")
                    }
                    OperationButton(title: "Find star", operation: "guiding.find_star", icon: "sparkle.magnifyingglass")
                }
            }
        }
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
            .accessibilityLabel("Guide error trace")
            .accessibilityIdentifier("guide.trace")
            Note("Solid: RA. Dashed: Dec. Arc-seconds.")
        }
    }
}

/// The guide star as the Master has it: PHD2's close-up of the star, or the whole guide frame. A tap on either asks
/// PHD2 to choose the star there (`MAPP-GUI-030`); the Master looks for the best star in a small box around the tap, so
/// it need not be exact. Taps are sent as where on the picture they landed, in that picture's own pixels, and the
/// Master works out where that is on the guide frame.
private struct GuidePicture: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.displayScale) private var scale
    @State private var fullFrame = false
    @State private var image: UIImage?
    /// The picture's size in the Master's pixels: the guide frame, or the cut-out.
    @State private var pixelSize: CGSize?
    @State private var problem: String?
    @State private var message: String?

    private var state: String { model.value("guiding.state")?.value.displayText ?? "" }
    /// PHD2 only takes a new star while looping; the Master says no at any other time, and so is only asked then.
    private var canChoose: Bool { ["Looping", "Selected", "LostLock"].contains(state) }
    private var request: String { "\(model.workingPier ?? "")/\(fullFrame)/\(state)/\(model.value("guiding.rms_ra")?.value.displayText ?? "")" }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                choice("Star", on: !fullFrame) { fullFrame = false }
                choice("Full frame", on: fullFrame) { fullFrame = true }
            }
            ZStack {
                theme.background
                if let image, let pixelSize {
                    GeometryReader { box in
                        let fit = Self.fit(pixelSize, in: box.size)
                        Image(uiImage: image)
                            .resizable()
                            .interpolation(fullFrame ? .medium : .none)
                            .frame(width: fit.width, height: fit.height)
                            .colorMultiply(theme.imageTint)
                            .position(x: box.size.width / 2, y: box.size.height / 2)
                            .contentShape(Rectangle())
                            .gesture(SpatialTapGesture().onEnded { tap in
                                guard canChoose else { return }
                                let point = CGPoint(x: tap.location.x / fit.width * pixelSize.width,
                                                    y: tap.location.y / fit.height * pixelSize.height)
                                Task {
                                    message = await model.perform("guiding.select_star", args: [
                                        "x": .number(point.x), "y": .number(point.y),
                                        "space": .string(fullFrame ? "frame" : "cutout")])
                                }
                            })
                            .accessibilityLabel(fullFrame ? "Guide frame" : "Guide star")
                            .accessibilityHint(canChoose ? "Tap a star to guide on it." : "")
                            .accessibilityIdentifier("guide.picture")
                    }
                } else if let problem {
                    Note(problem).padding()
                } else {
                    ProgressView().tint(theme.text)
                }
            }
            .frame(maxWidth: .infinity)
            .aspectRatio(4 / 3, contentMode: .fit)
            if let message { Note(message) }
            Note(canChoose
                 ? "Tap a star to guide on it."
                 : "Loop exposures to choose a star; the star can't be changed while guiding.")
        }
        .task(id: request) { await load() }
        .task(id: fullFrame) {
            // The Master only keeps fetching the picture (for the frame, a file write each) while someone is looking.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                if !Task.isCancelled { await load() }
            }
        }
    }

    private func choice(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .buttonStyle(ActionStyle(kind: on ? .prominent : .normal))
            .accessibilityValue(on ? "Showing" : "")
            .accessibilityIdentifier("guide.show.\(title)")
    }

    private func load() async {
        if fullFrame {
            if let shot = await model.preview(width: Int(1200 * min(scale, 2) / 2), stretch: 50, guide: true),
               let decoded = UIImage(data: shot.data), let width = shot.frameWidth, let height = shot.frameHeight {
                image = decoded
                pixelSize = CGSize(width: width, height: height)
                problem = nil
            } else if image == nil || pixelSize == nil {
                problem = "No guide frame yet. The Master can only show it when PHD2 runs on the Master's computer."
            }
        } else if let star = await model.read("guiding.star"), star["available"]?.boolValue != false,
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
