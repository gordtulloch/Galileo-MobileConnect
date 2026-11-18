import GalileoCore
import SwiftUI

/// Plate solving: solve where the mount is or sync it (`MAPP-SOL-010`, `MAPP-SOL-020`); centring on a position is the Sky
/// screen's Goto and Centre. One column, in the order the work goes: the buttons, the bullseye, how far each solution
/// landed from centre, the exposure, the camera, the result, and last the latest frame, which is only there so the person
/// can tell the imaging is working. Polar alignment is its own screen (`PolarAlignmentScreen`).
struct SolveScreen: View {
    @Environment(AppModel.self) private var model

    /// The frame polar alignment last solved. Each refresh is a new frame, so each is a new picture.
    private var polarVersion: Int? { model.value("polar.state.frame.version")?.value.doubleValue.map(Int.init) }
    /// The frame a solve has just captured, announced as it arrives, so it is on screen while it is being solved.
    private var solveFrameVersion: Int? { model.value("solve.frame.version")?.value.doubleValue.map(Int.init) }
    private var solvedCount: Int { model.value("solve.last.solutions")?.value.arrayValue?.count ?? 0 }

    /// The frame being solved, once there is one, rather than the latest exposure: this screen is about the solve's frame.
    private var showsSolveFrame: Bool { polarVersion != nil || solveFrameVersion != nil || solvedCount > 0 }

    /// A new solve or a new polar-alignment refresh means a new picture, fetched at once: the loop is the point.
    private var refresh: String {
        "\(model.value("solve.running")?.value.displayText ?? "")/\(solvedCount)/\(polarVersion ?? 0)/\(solveFrameVersion ?? 0)"
    }

    var body: some View {
        ScreenScroll("Solve") {
            ReadOnlyHint()
            SolverControl()
            SolutionCoordinates()
            Card("Latest frame") {
                FramePicture(stretch: 50, stack: false, stackFrames: 0, refresh: refresh, solve: showsSolveFrame)
                    .frame(height: 240)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
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

/// One of the Pier's cameras, as `solve.cameras` lists it.
struct SolveCamera: Equatable {
    var slot: String
    var name: String
    var connected: Bool
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
    /// The Pier's cameras (`solve.cameras`) and which one the person chose; empty means the Master's own choice.
    @State private var cameras: [SolveCamera] = []
    @AppStorage("solve.camera") private var cameraSlot = ""

    private var action: SolverAction { SolverAction(rawValue: actionRaw) ?? .slew }

    var body: some View {
        Card("Solver Control") {
            let points = (model.value("solve.points")?.value.arrayValue ?? []).compactMap { point -> CGPoint? in
                guard let pair = point.arrayValue?.compactMap(\.doubleValue), pair.count == 2 else { return nil }
                return CGPoint(x: pair[0], y: pair[1])
            }
            if model.canOperate {
                OperationButton(
                    title: "Capture & Solve", operation: "solve.capture", icon: "camera.viewfinder",
                    args: captureArgs, kind: .prominent)
                // How far the frame being taken has got; the operation's own bar keeps moving through the solving.
                ProgressRow(key: "solve.exposure_progress", label: "Exposure")
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
            }
            // Not while a solve is running: the Master's reason is then "a plate solve is already running", which is this one.
            if model.value("solve.running")?.value.boolValue != true,
               let why = model.value("solve.why_not")?.value.stringValue, !why.isEmpty { Note(why) }
            SolveBullseye(points: points, ring: model.value("solve.accuracy_arcsec")?.value.doubleValue ?? 30)
                .frame(height: 220)
                .accessibilityLabel("Where each solution landed against its target, \(points.count) shown")
                .accessibilityIdentifier("solve.bullseye")
            deviation(points.last)
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
                cameraChoice
                VStack(alignment: .leading, spacing: 2) {
                    Text("Solver Action").font(.subheadline.weight(.semibold)).foregroundStyle(theme.text)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(SolverAction.allCases) { choice in radio(choice) }
                    Note(action.detail)
                }
            }
        }
        .task { await loadCameras() }
    }

    /// What `solve.capture` is sent: the exposure, the Solver Action and the chosen camera (the Master's own when none).
    private var captureArgs: [String: JSONValue] {
        var args: [String: JSONValue] = ["exposure_s": exposureValue, "action": .string(action.rawValue)]
        if !cameraSlot.isEmpty, cameras.contains(where: { $0.slot == cameraSlot }) { args["camera_slot"] = .string(cameraSlot) }
        return args
    }

    /// How far the latest solution landed from where it was aimed: dRA, dDec and the total, in arcseconds.
    private func deviation(_ latest: CGPoint?) -> some View {
        let values: [(String, String)] = latest.map { point in
            [("dRA", Self.arcsec(point.x)), ("dDec", Self.arcsec(point.y)), ("Total", Self.arcsec(hypot(point.x, point.y)))]
        } ?? [("dRA", "—"), ("dDec", "—"), ("Total", "—")]
        return HStack(spacing: 8) {
            ForEach(values, id: \.0) { label, value in
                VStack(spacing: 2) {
                    Text(label).font(.caption).foregroundStyle(theme.textSecondary)
                    Text(value).font(.title3.weight(.bold).monospacedDigit()).foregroundStyle(theme.text)
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("solve.dev.\(label)")
            }
        }
        .padding(.vertical, 6)
        .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
    }

    private static func arcsec(_ value: Double) -> String { String(format: "%.1f″", value) }

    /// The Pier's cameras, one tap to choose which takes the frame. Shown once the Master has listed them.
    @ViewBuilder private var cameraChoice: some View {
        if !cameras.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text("Camera").font(.subheadline.weight(.semibold)).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                ForEach(cameras, id: \.slot) { camera in
                    let on = (cameraSlot.isEmpty ? cameras.first?.slot : cameraSlot) == camera.slot
                    Button { cameraSlot = camera.slot } label: {
                        HStack(spacing: 10) {
                            Image(systemName: on ? "largecircle.fill.circle" : "circle").accessibilityHidden(true)
                            Text(camera.connected ? camera.name : "\(camera.name) (not connected)").frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .font(.body.weight(.semibold))
                        .foregroundStyle(theme.text)
                        .frame(minHeight: Layout.minimumTarget)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(.isButton)
                    .accessibilityValue(on ? "selected" : "not selected")
                    .accessibilityIdentifier("solve.camera.\(camera.slot)")
                }
            }
        }
    }

    private func loadCameras() async {
        guard let list = (await model.read("solve.cameras"))?["cameras"]?.arrayValue else { return }
        cameras = list.compactMap { entry in
            guard let slot = entry["slot"]?.stringValue else { return nil }
            return SolveCamera(slot: slot, name: entry["name"]?.stringValue ?? slot, connected: entry["connected"]?.boolValue ?? true)
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
