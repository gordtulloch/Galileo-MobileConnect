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
    /// Notices already here when the screen opens are history, as for the panels shown on other screens.
    @State private var known: Set<String>?

    private var problems: [NoticeEvent] {
        let baseline = known ?? Set(model.snapshot.live.notices.map(\.id))
        return Array(model.snapshot.live.notices
            .filter { !baseline.contains($0.id) && ($0.severity == "warning" || $0.severity == "error") }
            .prefix(3))
    }

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
        Card("Alignment Results") {
            if let phase = text("polar.state.phase") {
                status(phase)
            } else {
                Note("Polar alignment hasn't been run.")
            }
            ForEach(problems) { notice in
                Text([notice.title, notice.message].filter { !$0.isEmpty }.joined(separator: ": "))
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(notice.severity == "error" ? theme.danger : theme.warning)
                    .accessibilityIdentifier("polar.notice")
            }
            // Poor-pointing warnings for this run, from the Master.
            ForEach(warnings, id: \.self) { warning in
                Text(warning)
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(theme.warning)
                    .accessibilityIdentifier("polar.warning")
            }
            if let total = number("polar.state.error.total_arcsec") {
                row("Total", Self.angle(total) + rating, id: "polar.total", colour: bandColour)
                readingLine
                if let altitude = number("polar.state.error.altitude_arcsec") {
                    row("N–S (altitude)", Self.signed(altitude, positive: "high", negative: "low"), id: "polar.altitude")
                }
                if let azimuth = number("polar.state.error.azimuth_arcsec") {
                    row("E–W (azimuth)", Self.signed(azimuth, positive: "east", negative: "west"), id: "polar.azimuth")
                }
                if let advice = text("polar.state.error.altitude_advice") { Text(advice).font(.body) }
                if let advice = text("polar.state.error.azimuth_advice") { Text(advice).font(.body) }
            }
            if text("polar.state.phase") != nil { activity }
        }
        .onAppear { if known == nil { known = Set(model.snapshot.live.notices.map(\.id)) } }
    }

    private var warnings: [String] { model.value("polar.state.warnings")?.value.arrayValue?.compactMap(\.stringValue) ?? [] }

    /// The rating in words as well as colour: the night theme's colours cannot be told apart.
    private var rating: String {
        switch text("polar.state.error.quality") {
        case "good": " · Good"
        case "fair": " · Fair"
        case "poor": " · Poor"
        default: ""
        }
    }

    /// Which reading the figures are and how old it is, so an unchanged or stale error is never mistaken for a fresh
    /// one. A Master that sends no reading number shows nothing here.
    @ViewBuilder private var readingLine: some View {
        let reading = Int(number("polar.state.reading") ?? 0)
        if reading > 0 {
            let running = model.value("polar.state.running")?.value.boolValue == true
            let stale = model.value("polar.state.stale")?.value.boolValue == true
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let age = number("polar.state.measured_at").map { max(0, Int((context.date.timeIntervalSince1970 - $0).rounded())) }
                let taken = (running && age != nil) ? ", taken \(age ?? 0) s ago" : ""
                let line = stale
                    ? "OUT OF DATE. Reading \(reading)\(taken). \(text("polar.state.problem") ?? "")"
                    : "Reading \(reading)\(taken)." + (running ? "" : " Not running, so this will not update.")
                Text(line)
                    .font(stale ? .callout.weight(.bold) : .callout)
                    .foregroundStyle(stale ? theme.warning : theme.text)
                    .accessibilityIdentifier("polar.reading")
            }
        }
    }

    private var log: [String] { model.value("polar.state.log")?.value.arrayValue?.compactMap(\.stringValue) ?? [] }

    /// Which phase it is in, how far through, what it is doing now and what the last solve found.
    @ViewBuilder private func status(_ phase: String) -> some View {
        let running = model.value("polar.state.running")?.value.boolValue == true
        let step = Int(number("polar.state.step") ?? 0)
        let steps = Int(number("polar.state.steps") ?? 3)
        Text(title(phase, step: step, steps: steps))
            .font(.headline)
            .foregroundStyle(running ? theme.accent : theme.text)
            .accessibilityIdentifier("polar.phase")
        if running, phase == "measuring" {
            ThemedProgress(value: Double(step) / Double(max(steps, 1)), label: "Measuring")
        }
        if let message = text("polar.state.message") { Text(message).font(.body.weight(.semibold)).foregroundStyle(theme.text) }
        if let solve = log.last(where: { $0.hasPrefix("Solution:") || $0.hasPrefix("Solver failed") }) {
            Text("Last solve. \(solve)").font(.callout).foregroundStyle(theme.text).accessibilityIdentifier("polar.solve")
        }
    }

    private func title(_ phase: String, step: Int, steps: Int) -> String {
        switch phase {
        case "measuring": step > 0 ? "Measuring: position \(step) of \(steps)" : "Preparing: checking where the mount points"
        case "adjusting": "Adjusting: turn the altitude and azimuth knobs"
        default: model.value("polar.state.stopped")?.value.boolValue == true ? "Stopped" : "Finished"
        }
    }

    /// The Master's own log, newest last, kept after the run ends so a failure can still be read.
    @ViewBuilder private var activity: some View {
        let all = log
        let earlier = Array((all.last == text("polar.state.message") ? Array(all.dropLast()) : all).suffix(8))
        if !earlier.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text("Activity").font(.subheadline.weight(.semibold)).foregroundStyle(theme.textSecondary)
                ForEach(Array(earlier.enumerated()), id: \.offset) { _, line in Note(line) }
            }
            .accessibilityIdentifier("polar.log")
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
