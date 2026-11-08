import GalileoCore
import SwiftUI

/// The panes beside the preview on the Solve screen, in the order they stack.
enum SolvePane: String, CaseIterable, Identifiable {
    case control, result, polar, error

    var id: String { rawValue }

    var title: String {
        switch self {
        case .control: "Solver Control"
        case .result: "Latest result"
        case .polar: "Polar alignment"
        case .error: "Polar error"
        }
    }
}

/// Plate solving: solve where the mount is or sync it (`MAPP-SOL-010`, `MAPP-SOL-020`); centring on a position is the Sky screen's Goto and Centre, and
/// polar alignment (`MAPP-SOL-030`). The latest frame is on the left and the panes are stacked on the right, which
/// can be hidden as a whole, so that the picture can have the screen to itself. The menu button stays on the
/// picture while the menu is hidden.
struct SolveScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Whether the menu (the panes beside the picture) is hidden, kept on the device.
    @AppStorage("solve.menuHidden") private var menuHidden = false
    private var shown: [SolvePane] { menuHidden ? [] : SolvePane.allCases }

    /// The frame polar alignment last solved. Each refresh is a new frame, so each is a new picture.
    private var polarVersion: Int? { model.value("polar.state.frame.version")?.value.doubleValue.map(Int.init) }
    private var polarAdjusting: Bool {
        model.value("polar.state.running")?.value.boolValue == true && model.value("polar.state.phase")?.value.stringValue == "adjusting"
    }
    /// The frame a solve has just captured, announced as it arrives, so it is on screen while it is being solved.
    private var solveFrameVersion: Int? { model.value("solve.frame.version")?.value.doubleValue.map(Int.init) }
    private var solvedCount: Int { model.value("solve.last.solutions")?.value.arrayValue?.count ?? 0 }

    /// The frame being solved, once there is one, rather than the latest exposure: this screen is about the solve's frame.
    private var showsSolveFrame: Bool { polarVersion != nil || solveFrameVersion != nil || solvedCount > 0 }

    /// A new solve or a new polar-alignment refresh means a new picture, fetched at once: the loop is the point.
    private var refresh: String {
        "\(model.value("solve.running")?.value.displayText ?? "")/\(solvedCount)/\(polarVersion ?? 0)/\(solveFrameVersion ?? 0)"
    }

    /// The circle, cross and vector for the latest polar-alignment frame, only while adjusting is live.
    private var marks: PolarMarks? {
        guard polarAdjusting, polarVersion != nil,
            let width = model.value("polar.state.frame.width")?.value.doubleValue,
            let height = model.value("polar.state.frame.height")?.value.doubleValue,
            let axis = model.value("polar.state.frame.axis_px")?.value.arrayValue?.compactMap(\.doubleValue), axis.count == 2,
            let pole = model.value("polar.state.frame.pole_px")?.value.arrayValue?.compactMap(\.doubleValue), pole.count == 2,
            let scale = model.value("polar.state.frame.arcmin_per_px")?.value.doubleValue
        else { return nil }
        return PolarMarks(
            refresh: refresh, frame: CGSize(width: width, height: height), axis: CGPoint(x: axis[0], y: axis[1]),
            pole: CGPoint(x: pole[0], y: pole[1]), arcminPerPixel: scale)
    }

    var body: some View {
        VStack(spacing: 0) {
            if let reason = model.workingPier.flatMap({ model.snapshot.unavailableReason(pier: $0) }) {
                Banner(icon: "exclamationmark.triangle", text: "\(model.pier?.name ?? "This Pier"): \(reason)").padding(8)
            }
            if sizeClass == .regular {
                HStack(spacing: 0) {
                    picture
                    if !shown.isEmpty {
                        Rectangle().fill(theme.border).frame(width: 1)
                        column.frame(width: 400)
                    }
                }
            } else {
                VStack(spacing: 0) {
                    picture.frame(maxHeight: shown.isEmpty ? .infinity : 260)
                    if !shown.isEmpty { column }
                }
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: menuHidden)
        .background(theme.background)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("Solve").font(.headline).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(theme.surface, for: .navigationBar)
    }

    /// The latest frame, as large as the space allows, with the button that hides or shows the menu in its corner.
    private var picture: some View {
        FramePicture(stretch: 50, stack: false, stackFrames: 0, refresh: refresh, solve: showsSolveFrame, marks: marks)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
            .overlay(alignment: .topTrailing) {
                Button { menuHidden.toggle() } label: {
                    Image(systemName: menuHidden ? "sidebar.right" : "rectangle.righthalf.inset.filled.arrow.right")
                        .frame(minWidth: Layout.minimumTarget, minHeight: Layout.minimumTarget)
                }
                .buttonStyle(ActionStyle())
                .fixedSize()
                .padding(8)
                .accessibilityLabel(menuHidden ? "Show menu" : "Hide menu")
                .accessibilityIdentifier("solve.menu")
            }
    }

    /// The panes, stacked.
    private var column: some View {
        ScrollView {
            VStack(spacing: Layout.spacing) {
                ReadOnlyHint()
                ForEach(shown) { pane in content(of: pane) }
            }
            .padding(Layout.spacing)
        }
        .background(theme.background)
    }

    @ViewBuilder private func content(of pane: SolvePane) -> some View {
        switch pane {
        case .control: SolverControl()
        case .result: SolutionCoordinates()
        case .polar: PolarAlignmentControls()
        case .error: PolarAlignmentReadout()
        }
    }
}

/// Starting and stopping polar alignment (`MAPP-SOL-030`, `CSURF-145`). The routine measures with three solves and then
/// keeps solving while the altitude and azimuth knobs are turned, so it runs until it is stopped.
struct PolarAlignmentControls: View {
    var body: some View {
        Card("Polar alignment") {
            Note("Turns the mount to three positions and solves a frame at each, then follows your adjustments live until you stop it.")
            OperationForm(title: "Start polar alignment", operation: "polar.start", icon: "scope",
                          fields: ["exposure_s", "step_deg", "direction", "refraction", "slew_from_zenith"], kind: .prominent)
            OperationButton(title: "Stop polar alignment", operation: "polar.stop", icon: "stop")
        }
    }
}

/// The live adjustment display: the error as large figures with a trend, in words what to turn, and where the pole lies
/// against the axis in the latest frame. All of it is the Master's `polar.state`, refreshed on every solve.
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
                Text(Self.angle(total))
                    .font(.system(size: 44, weight: .bold, design: .rounded).monospacedDigit())
                    .foregroundStyle(bandColour)
                    .accessibilityIdentifier("polar.total")
                    .accessibilityLabel("Polar error \(Self.angle(total))")
                if let advice = text("polar.state.error.altitude_advice") { Text(advice).font(.body) }
                if let advice = text("polar.state.error.azimuth_advice") { Text(advice).font(.body) }
                PolarTargetPlot(offset: model.value("polar.state.target_offset_arcmin")?.value.arrayValue?.compactMap(\.doubleValue))
                    .frame(height: 220)
                    .accessibilityHidden(true)
                if let trend = model.value("polar.state.error.trend")?.value.arrayValue?.compactMap(\.doubleValue), trend.count > 1 {
                    PolarTrend(values: trend, colour: bandColour).frame(height: 60).accessibilityHidden(true)
                    Note(trend.last! < trend.first! ? "The error is falling." : "The error is not falling yet.")
                }
            }
        }
    }

    /// An angle as a polar-alignment screen reads it: 45″, 3′12″ or 1°02′05″.
    static func angle(_ arcsec: Double) -> String {
        let total = Int(abs(arcsec).rounded())
        let (degrees, minutes, seconds) = (total / 3600, total % 3600 / 60, total % 60)
        if degrees > 0 { return String(format: "%d°%02d′%02d″", degrees, minutes, seconds) }
        if minutes > 0 { return String(format: "%d′%02d″", minutes, seconds) }
        return "\(seconds)″"
    }
}

/// The pole (a cross) against the point the axis aims at (the circle at the centre): bring the cross into the circle.
struct PolarTargetPlot: View {
    let offset: [Double]?
    @Environment(\.theme) private var theme

    var body: some View {
        Canvas { context, size in
            let centre = CGPoint(x: size.width / 2, y: size.height / 2)
            let half = min(size.width, size.height) / 2 - 12
            var axes = Path()
            axes.move(to: CGPoint(x: 0, y: centre.y)); axes.addLine(to: CGPoint(x: size.width, y: centre.y))
            axes.move(to: CGPoint(x: centre.x, y: 0)); axes.addLine(to: CGPoint(x: centre.x, y: size.height))
            context.stroke(axes, with: .color(theme.border), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
            context.stroke(Path(ellipseIn: CGRect(x: centre.x - 14, y: centre.y - 14, width: 28, height: 28)),
                           with: .color(theme.accent), lineWidth: 2)
            guard let offset, offset.count == 2 else { return }
            let range = max(5.0, abs(offset[0]) * 1.2, abs(offset[1]) * 1.2)       // never zoomed in tighter than 5′
            let at = CGPoint(x: centre.x + offset[0] / range * half, y: centre.y + offset[1] / range * half)
            var cross = Path()
            cross.move(to: CGPoint(x: at.x - 9, y: at.y - 9)); cross.addLine(to: CGPoint(x: at.x + 9, y: at.y + 9))
            cross.move(to: CGPoint(x: at.x - 9, y: at.y + 9)); cross.addLine(to: CGPoint(x: at.x + 9, y: at.y - 9))
            context.stroke(cross, with: .color(theme.danger), style: StrokeStyle(lineWidth: 3, lineCap: .round))
        }
        .background(theme.raised, in: RoundedRectangle(cornerRadius: 10))
    }
}

/// The recent totals as a line, so the person can see whether turning the knob is helping.
struct PolarTrend: View {
    let values: [Double]
    let colour: Color
    @Environment(\.theme) private var theme

    var body: some View {
        Canvas { context, size in
            guard let high = values.max(), high > 0 else { return }
            var line = Path()
            for (index, value) in values.enumerated() {
                let point = CGPoint(x: size.width * CGFloat(index) / CGFloat(values.count - 1),
                                    y: size.height - (size.height - 8) * CGFloat(value / high) - 4)
                if index == 0 { line.move(to: point) } else { line.addLine(to: point) }
            }
            context.stroke(line, with: .color(colour), lineWidth: 2)
        }
        .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
    }
}


/// What to do with the answer, as the desktop's Solver Action: raw values are what `solve.capture` takes.
enum SolverAction: String, CaseIterable, Identifiable {
    case sync, slew, nothing

    var id: String { rawValue }

    var title: String {
        switch self {
        case .sync: "Sync"
        case .slew: "Slew to Target"
        case .nothing: "Nothing"
        }
    }

    var detail: String {
        switch self {
        case .sync: "Tell the mount where it is really pointing."
        case .slew: "Slew to the target, then solve, sync and slew again until within the accuracy."
        case .nothing: "Only solve. The mount is not touched."
        }
    }
}

/// The desktop's Solver Control and Solver Action (`MAPP-SOL-010`, `MAPP-SOL-020`): the bullseye of where each solution
/// landed against its target, the exposure, Capture & Solve, Load & Slew and Stop, and what to do with the answer.
struct SolverControl: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    /// Kept on the device: Slew to Target unless the person chose otherwise.
    @AppStorage("solve.action") private var actionRaw = SolverAction.slew.rawValue
    @AppStorage("solve.exposure") private var exposure = "5"
    @State private var choosing = false
    @State private var frames: [JSONValue]?
    @State private var message: String?

    private var action: SolverAction { SolverAction(rawValue: actionRaw) ?? .slew }

    var body: some View {
        Card("Solver Control") {
            let points = (model.value("solve.points")?.value.arrayValue ?? []).compactMap { point -> CGPoint? in
                guard let pair = point.arrayValue?.compactMap(\.doubleValue), pair.count == 2 else { return nil }
                return CGPoint(x: pair[0], y: pair[1])
            }
            SolveBullseye(points: points, ring: model.value("solve.accuracy_arcsec")?.value.doubleValue ?? 30)
                .frame(height: 220)
                .accessibilityLabel("Where each solution landed against its target, \(points.count) shown")
                .accessibilityIdentifier("solve.bullseye")
            if let why = model.value("solve.why_not")?.value.stringValue, !why.isEmpty { Note(why) }
            if model.canOperate {
                HStack {
                    Text("Exposure (s)").foregroundStyle(theme.textSecondary)
                    TextField("", text: $exposure, prompt: Text("5").foregroundStyle(theme.textSecondary))
                        .keyboardType(.numbersAndPunctuation)
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(theme.text)
                        .accessibilityLabel("Exposure in seconds")
                        .accessibilityIdentifier("solve.exposure")
                }
                .frame(minHeight: Layout.minimumTarget)
                .padding(.horizontal, 10)
                .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
                OperationButton(
                    title: "Capture & Solve", operation: "solve.capture", icon: "camera.viewfinder",
                    args: ["exposure_s": exposureValue, "action": .string(action.rawValue)], kind: .prominent)
                Button {
                    choosing.toggle()
                    if choosing { Task { await loadFrames() } }
                } label: {
                    Label("Load & Slew…", systemImage: "square.and.arrow.down").frame(maxWidth: .infinity)
                }
                .buttonStyle(ActionStyle())
                .disabled(model.operation("solve.load")?.available != true)
                .accessibilityIdentifier("solve.load.choose")
                if model.operation("solve.load") == nil { Note("This Master doesn't offer Load & Slew yet.") }
                if choosing { frameList }
                OperationButton(title: "Stop", operation: "solve.stop", icon: "stop")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Solver Action").font(.subheadline.weight(.semibold)).foregroundStyle(theme.text)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(SolverAction.allCases) { choice in radio(choice) }
                    Note(action.detail)
                }
            }
        }
    }

    /// Whatever was typed, else the default; the Master's range check names a bad value.
    private var exposureValue: JSONValue {
        Double(exposure.trimmingCharacters(in: .whitespaces)).map(JSONValue.number) ?? .string(exposure)
    }

    /// A radio button: not a system picker or toggle, which can't be held to Night.
    private func radio(_ choice: SolverAction) -> some View {
        let on = choice == action
        return Button { actionRaw = choice.rawValue } label: {
            HStack(spacing: 10) {
                Image(systemName: on ? "largecircle.fill.circle" : "circle").accessibilityHidden(true)
                Text(choice.title).frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.body.weight(.semibold))
            .foregroundStyle(theme.text)
            .frame(minHeight: Layout.minimumTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(on ? "selected" : "not selected")
        .accessibilityIdentifier("solve.action.\(choice.rawValue)")
    }

    /// Recent Library frames on the Master, one tap to solve it and slew there.
    @ViewBuilder private var frameList: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Solve a frame from the Library and slew to where it points.").font(.footnote).foregroundStyle(theme.textSecondary)
            if let frames {
                if frames.isEmpty { Note("The Library has no frames to choose from.") }
                ForEach(frames.indices, id: \.self) { index in
                    let frame = frames[index]
                    Button {
                        guard let id = frame["id"]?.doubleValue.map({ String(Int($0)) }) ?? frame["id"]?.stringValue else { return }
                        choosing = false
                        Task { message = await model.perform("solve.load", args: ["frame_id": .string(id)]) }
                    } label: {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(frame["name"]?.stringValue ?? "Frame").font(.subheadline.weight(.semibold))
                            Text([frame["object"]?.stringValue, frame["filter"]?.stringValue, frame["date"]?.stringValue]
                                .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(theme.textSecondary)
                        }
                        .foregroundStyle(theme.text)
                        .frame(maxWidth: .infinity, minHeight: Layout.minimumTarget, alignment: .leading)
                        .padding(.horizontal, 10)
                        .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("solve.load.\(index)")
                }
            } else {
                ProgressView().tint(theme.text)
            }
        }
        if let message { Note(message) }
    }

    private func loadFrames() async {
        frames = nil
        frames = (await model.read("solve.frames"))?["frames"]?.arrayValue ?? []
    }
}

/// The desktop's bullseye: each solution's offset from its target (dRA across, dDE up) in arcseconds, with rings at one,
/// two and three times the accuracy asked for, the newest solution brightest.
struct SolveBullseye: View {
    let points: [CGPoint]
    let ring: Double
    @Environment(\.theme) private var theme

    var body: some View {
        Canvas { context, size in
            let side = min(size.width - 40, size.height - 46)   // room under the plot for the two caption lines
            guard side > 40 else { return }
            let plot = CGRect(x: (size.width - side) / 2 + 8, y: 4, width: side, height: side)
            let centre = CGPoint(x: plot.midX, y: plot.midY)
            let reach = max(ring * 3.3, (points.map { max(abs($0.x), abs($0.y)) }.max() ?? 0) * 1.15)
            let k = side / 2 / reach
            var axes = Path()
            axes.move(to: CGPoint(x: plot.minX, y: centre.y)); axes.addLine(to: CGPoint(x: plot.maxX, y: centre.y))
            axes.move(to: CGPoint(x: centre.x, y: plot.minY)); axes.addLine(to: CGPoint(x: centre.x, y: plot.maxY))
            context.stroke(axes, with: .color(theme.border), lineWidth: 1)
            for (multiple, colour) in [(3.0, theme.danger), (2.0, theme.warning), (1.0, theme.good)] {
                let radius = multiple * ring * k
                let circle = Path(ellipseIn: CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2))
                context.fill(circle, with: .color(colour.opacity(0.14)))
                context.stroke(circle, with: .color(colour), lineWidth: 1.4)
            }
            for (index, point) in points.enumerated() {
                let age = Double(index + 1) / Double(points.count)
                let dot = CGRect(x: centre.x + point.x * k - 3, y: centre.y - point.y * k - 3, width: 6, height: 6)
                context.fill(Path(ellipseIn: dot), with: .color(theme.text.opacity(0.3 + 0.7 * age)))
            }
            let label = Text("dRA (arcsec) →   dDE ↑").font(.caption2).foregroundStyle(theme.textSecondary)
            context.draw(label, at: CGPoint(x: plot.midX, y: plot.maxY + 12))
            let ringLabel = Text("rings \(ring.formatted())″ × 1, 2, 3").font(.caption2).foregroundStyle(theme.textSecondary)
            context.draw(ringLabel, at: CGPoint(x: plot.midX, y: plot.maxY + 27))
        }
        .background(theme.raised, in: RoundedRectangle(cornerRadius: 10))
    }
}

/// The desktop's Solution Coordinates (JNow) for the latest solved frame: where it points now, and how that compares with
/// the target and with the optics set up.
struct SolutionCoordinates: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        Card("Latest result") {
            LiveRow(label: "Solving now", key: "solve.running")
            let list = model.value("solve.last.solutions")?.value.arrayValue ?? []
            if let solved = list.last(where: { $0["success"]?.boolValue == true }) {
                Text("Solution Coordinates (JNow)").font(.subheadline.weight(.semibold)).foregroundStyle(theme.text)
                    .accessibilityAddTraits(.isHeader)
                row("RA", solved["jnow_ra_hours"]?.doubleValue.map(Self.hms))
                row("DE", solved["jnow_dec_deg"]?.doubleValue.map(Self.dms))
                row("Err", error(solved))
                row("Pix", solved["scale_arcsec_px"]?.doubleValue.map { String(format: "%.2f″", $0) })
                row("PA", solved["rotation_deg"]?.doubleValue.map { String(format: "%.2f°", $0) })
                row("FOV", fov(solved))
                row("R", solved["scale_ratio"]?.doubleValue.map { String(format: "%.2fx", $0) })
                row("FL", pair(solved["focal_mm"]?.doubleValue, solved["expected_focal_mm"]?.doubleValue))
                row("F/", pair(solved["focal_ratio"]?.doubleValue, solved["expected_focal_ratio"]?.doubleValue))
                    .accessibilityIdentifier("solve.solution")
            } else if let failed = list.last, failed["success"]?.boolValue == false {
                Label(failed["failure_reason"]?.stringValue ?? "This frame didn't solve.", systemImage: "exclamationmark.triangle")
                    .font(.subheadline)
                    .foregroundStyle(theme.warning)
            } else {
                Note("Nothing solved yet.")
            }
        }
    }

    private func row(_ label: String, _ value: String?) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).foregroundStyle(theme.textSecondary)
            Spacer(minLength: 12)
            Text(value ?? "—").fontWeight(.semibold).multilineTextAlignment(.trailing)
        }
        .frame(minHeight: 28)
        .accessibilityElement(children: .combine)
    }

    private func error(_ solution: JSONValue) -> String? {
        guard let total = solution["error_arcsec"]?.doubleValue else { return nil }
        var text = String(format: "%.1f″", total)
        if let ra = solution["d_ra_arcsec"]?.doubleValue, let dec = solution["d_dec_arcsec"]?.doubleValue {
            text += String(format: "  (dRA %+.1f″, dDE %+.1f″)", ra, dec)
        }
        return text
    }

    private func fov(_ solution: JSONValue) -> String? {
        guard let size = solution["fov_arcmin"]?.arrayValue?.compactMap(\.doubleValue), size.count == 2 else { return nil }
        return String(format: "%.1f′ × %.1f′", size[0], size[1])
    }

    /// The solved value, and in brackets what the optics set up predict, as the desktop does.
    private func pair(_ solved: Double?, _ expected: Double?) -> String? {
        guard let solved else { return nil }
        return String(format: "%.1f", solved) + (expected.map { String(format: " (%.1f)", $0) } ?? "")
    }

    static func hms(_ hours: Double) -> String {
        let total = hours * 3600
        return String(format: "%02dh %02dm %04.1fs", Int(total / 3600) % 24, Int(total / 60) % 60, total.truncatingRemainder(dividingBy: 60))
    }

    static func dms(_ degrees: Double) -> String {
        let total = abs(degrees) * 3600
        return String(format: "%@%02d° %02d′ %02d″", degrees < 0 ? "−" : "+", Int(total / 3600), Int(total / 60) % 60, Int(total.rounded()) % 60)
    }
}
