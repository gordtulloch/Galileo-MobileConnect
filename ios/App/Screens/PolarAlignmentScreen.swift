import GalileoCore
import SwiftUI

/// Polar alignment (`MAPP-SOL-030`, `CSURF-145`): the error in numbers at the top, the Start and Stop controls, and the
/// latest frame last, small, since the picture is not what the person looks at. The routine measures with three solves and
/// then keeps solving while the altitude and azimuth knobs are turned, so it runs until it is stopped.
struct PolarAlignmentScreen: View {
    @Environment(AppModel.self) private var model

    private var version: Int? { model.value("polar.state.frame.version")?.value.doubleValue.map(Int.init) }
    private var solveVersion: Int? { model.value("solve.frame.version")?.value.doubleValue.map(Int.init) }

    var body: some View {
        ScreenScroll("Polar Alignment") {
            ReadOnlyHint()
            PolarAlignmentReadout()
            PolarAlignmentControls()
            Card("Latest frame") {
                FramePicture(stretch: 50, stack: false, stackFrames: 0,
                             refresh: "\(version ?? 0)/\(solveVersion ?? 0)", solve: version != nil || solveVersion != nil)
                    .frame(height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
    }
}

/// Starting and stopping polar alignment.
struct PolarAlignmentControls: View {
    var body: some View {
        Card("Polar alignment") {
            Note("Turns the mount to three positions and solves a frame at each, then follows your adjustments live until you stop it.")
            OperationForm(title: "Start polar alignment", operation: "polar.start", icon: "scope",
                          fields: ["exposure_s", "step_deg", "direction", "refraction", "slew_from_zenith"], kind: .prominent,
                          choicesOnOneLine: true)
                .environment(\.compactControls, true)
            OperationButton(title: "Stop polar alignment", operation: "polar.stop", icon: "stop")
        }
    }
}

/// The live error as figures: the total, the north-south (altitude) and east-west (azimuth) deviations, and in words
/// what to turn. All of it is the Master's `polar.state`, refreshed on every solve.
struct PolarAlignmentReadout: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    private func number(_ key: String) -> Double? { model.value(key)?.value.doubleValue }
    private func text(_ key: String) -> String? {
        let shown = model.value(key)?.value.stringValue
        return (shown?.isEmpty ?? true) ? nil : shown
    }

    private var bandColour: Color {
        switch text("polar.state.error.quality") {
        case "good": theme.good
        case "fair": theme.warning
        default: theme.danger
        }
    }

    var body: some View {
        Card("Polar error") {
            if text("polar.state.phase") != nil {
                LiveRow(label: "Phase", key: "polar.state.phase")
                if let message = text("polar.state.message") { Note(message) }
            } else {
                Note("Polar alignment hasn't been run.")
            }
            if let total = number("polar.state.error.total_arcsec") {
                row("Total", Self.angle(total), id: "polar.total", colour: bandColour)
                if let altitude = number("polar.state.error.altitude_arcsec") {
                    row("N–S (altitude)", Self.signed(altitude, positive: "high", negative: "low"), id: "polar.altitude")
                }
                if let azimuth = number("polar.state.error.azimuth_arcsec") {
                    row("E–W (azimuth)", Self.signed(azimuth, positive: "east", negative: "west"), id: "polar.azimuth")
                }
                if let advice = text("polar.state.error.altitude_advice") { Text(advice).font(.body) }
                if let advice = text("polar.state.error.azimuth_advice") { Text(advice).font(.body) }
            }
        }
    }

    private func row(_ label: String, _ value: String, id: String, colour: Color? = nil) -> some View {
        HStack {
            Text(label).foregroundStyle(theme.textSecondary)
            Spacer()
            Text(value)
                .font(.title2.weight(.bold).monospacedDigit())
                .foregroundStyle(colour ?? theme.text)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(id)
    }

    /// An angle as a polar-alignment screen reads it: 45″, 3′12″ or 1°02′05″.
    static func angle(_ arcsec: Double) -> String {
        let total = Int(abs(arcsec).rounded())
        let (degrees, minutes, seconds) = (total / 3600, total % 3600 / 60, total % 60)
        if degrees > 0 { return String(format: "%d°%02d′%02d″", degrees, minutes, seconds) }
        if minutes > 0 { return String(format: "%d′%02d″", minutes, seconds) }
        return "\(seconds)″"
    }

    /// A deviation with its direction: the Master's sign is positive when the axis is too high / east of the pole.
    static func signed(_ arcsec: Double, positive: String, negative: String) -> String {
        "\(angle(arcsec)) \(arcsec >= 0 ? positive : negative)"
    }
}
