import Charts
import GalileoCore
import SwiftUI

/// The guider service and its error trace (`MAPP-GUI-*`).
struct GuidingScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScreenScroll("Guiding") {
            ReadOnlyHint()
            Columns {
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
