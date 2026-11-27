import Charts
import GalileoCore
import SwiftUI

/// Manual focus and autofocus, only through the focus module's operations (`MAPP-FOC-*`).
struct FocusScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        ScreenScroll("Focus") {
            ReadOnlyHint()
            FocusPreview()
            Columns {
                autofocus
            } right: {
                manual
            }
        }
    }

    private var manual: some View {
        Card("Manual focus") {
            // Double-sized and first, so they can be found and held in the dark.
            if model.canOperate, model.operation("focus.hold_start") != nil {
                HStack(spacing: 8) {
                    HoldButton(title: "Hold in", icon: "minus.circle", family: "focus", args: ["direction": -1], minHeight: 2 * Layout.minimumTarget)
                    HoldButton(title: "Hold out", icon: "plus.circle", family: "focus", args: ["direction": 1], minHeight: 2 * Layout.minimumTarget)
                }
                if let explanation = model.snapshot.hold.explanation { Note(explanation) }
            }
            LiveRow(label: "Position", key: "focus.position")
            LiveRow(label: "Temperature", key: "focus.temperature", unit: "°C", digits: 1)
            LiveRow(label: "Moving", key: "focus.moving")
            if model.canOperate {
                ForEach([1000, 100, 10], id: \.self) { step in
                    HStack(spacing: 8) {
                        StepButton(steps: -step)
                        StepButton(steps: step)
                    }
                }
                if let record = model.record(of: "focus.move_by") { OutcomeNote(record: record) }
            }
            OperationButton(title: "Halt", operation: "focus.halt", icon: "stop", kind: .destructive)
            OperationForm(title: "Move to position", operation: "focus.move_to", icon: "arrow.right.to.line")
        }
    }

    private var autofocus: some View {
        Card("Autofocus") {
            LiveRow(label: "Temperature compensation", key: "focus.compensation")
            AutofocusControls()
            VCurve()
        }
    }
}

/// The Pier's latest frame (a capture from here, or each autofocus exposure) with an exposure length and Capture,
/// Loop and Stop, so focus can be judged by eye (`MAPP-FOC-040`).
private struct FocusPreview: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @AppStorage("focus.exposure") private var exposure = "2"
    @State private var message: String?

    var body: some View {
        Card("Preview") {
            // Each autofocus point is a new picture.
            FramePicture(stretch: 50, stack: false, stackFrames: 0, refresh: refresh)
                .frame(height: 260)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            if model.canOperate { controls }
        }
    }

    private var refresh: String {
        ["running", "phase", "step"].map { model.value("focus.autofocus.\($0)")?.value.displayText ?? "" }.joined(separator: "/")
    }

    @ViewBuilder private var controls: some View {
        let capture = model.operation("imaging.capture")
        let record = model.record(of: "imaging.capture")
        let open = record?.state.isOpen == true
        // The Master would let a capture and an autofocus run share the camera, so the app doesn't.
        let focusing = model.record(of: "focus.autofocus")?.state.isOpen == true
            || model.value("focus.autofocus.running")?.value.boolValue == true
        let free = capture != nil && capture?.available != false && !open && !focusing && !model.liveLoopRunning
        HStack {
            Text("Exposure (s)").foregroundStyle(theme.textSecondary)
            TextField("", text: $exposure, prompt: Text("2").foregroundStyle(theme.textSecondary))
                .keyboardType(.numbersAndPunctuation)
                .multilineTextAlignment(.trailing)
                .foregroundStyle(theme.text)
                .accessibilityLabel("Exposure in seconds")
                .accessibilityIdentifier("focus.exposure")
        }
        .frame(minHeight: Layout.minimumTarget)
        .padding(.horizontal, 10)
        .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
        HStack(spacing: 8) {
            Button {
                guard let seconds = seconds() else { return }
                Task { message = await model.perform("imaging.capture", args: ["seconds": .number(seconds), "count": 1]) }
            } label: {
                Label("Capture", systemImage: "camera")
            }
            .buttonStyle(ActionStyle(kind: .prominent))
            .disabled(!free)
            .accessibilityIdentifier("focus.capture")
            Button {
                guard let seconds = seconds() else { return }
                message = model.startLiveLoop(seconds: seconds)
            } label: {
                Label("Loop", systemImage: "repeat")
            }
            .buttonStyle(ActionStyle())
            .disabled(!free)
            .accessibilityIdentifier("focus.loop")
            Button {
                model.stopLiveLoop()
                Task { message = await model.perform("imaging.stop") }
            } label: {
                Label("Stop", systemImage: "stop")
            }
            .buttonStyle(ActionStyle())
            .disabled(!model.liveLoopRunning && !open)
            .accessibilityIdentifier("focus.stop")
        }
        if capture == nil {
            Note("This Master doesn't offer capture yet.")
        } else if let reason = capture?.unavailableBecause, capture?.available == false, !open {
            Note(reason)
        } else if let message {
            Note(message)
        } else if let said = model.liveLoopMessage {
            Note(said)
        } else if let record {
            OutcomeNote(record: record)
        }
    }

    private func seconds() -> Double? {
        let value = Double(exposure.trimmingCharacters(in: .whitespaces)).flatMap { $0 > 0 ? $0 : nil }
        message = value == nil ? "Exposure must be a number above zero." : nil
        return value
    }
}

private struct StepButton: View {
    let steps: Int
    @Environment(AppModel.self) private var model

    var body: some View {
        Button(steps > 0 ? "Out +\(steps)" : "In \(steps)") {
            Task { _ = await model.perform("focus.move_by", args: ["steps": .number(Double(steps))]) }
        }
        .buttonStyle(ActionStyle())
        .disabled(model.operation("focus.move_by") == nil)
        .accessibilityIdentifier("focus.step.\(steps)")
    }
}

private struct VCurve: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    private struct Sample: Identifiable {
        let id: Int
        let position: Double
        let hfr: Double
    }

    var body: some View {
        // The Master publishes the whole run as one object, `focus.autofocus`: step/steps, the [position, hfr] points so
        // far, the best position and the outcome. An older Master's `focus.vcurve` series still draws.
        // The app flattens an object into one key per field (`focus.autofocus.points`, `.running`...).
        // A closure, not a nested func: a declaration of that kind is not allowed inside a ViewBuilder body.
        let field: (String) -> JSONValue? = { name in model.value("focus.autofocus.\(name)")?.value }
        let phase = field("phase")?.stringValue
        let measured = (field("points")?.arrayValue ?? []).enumerated().compactMap { index, pair -> Sample? in
            guard let pair = pair.arrayValue, pair.count > 1, let position = pair[0].doubleValue, let hfr = pair[1].doubleValue else { return nil }
            return Sample(id: index, position: position, hfr: hfr)
        }
        let legacy = (model.workingPier.flatMap { model.snapshot.live.piers[$0]?.series["focus.vcurve"] } ?? []).suffix(30)
            .enumerated().compactMap { index, point -> Sample? in
                guard let position = point.value["position"]?.doubleValue, let hfr = point.value["hfr"]?.doubleValue else { return nil }
                return Sample(id: index, position: position, hfr: hfr)
            }
        let samples = measured.isEmpty ? legacy : measured
        let running = field("running")?.boolValue == true
        if let latest = samples.last, let best = samples.min(by: { $0.hfr < $1.hfr }) {
            Note(String(format: "Latest HFR %.2f px at %d. Lowest so far %.2f px at %d.",
                        latest.hfr, Int(latest.position.rounded()), best.hfr, Int(best.position.rounded())))
        }
        if !running, phase == "finished" {
            if field("cancelled")?.boolValue == true {
                Note("Autofocus cancelled.")
            } else if field("success")?.boolValue == true, let best = field("best_position")?.doubleValue {
                Note("Best focus at \(Int(best.rounded())).")
            } else {
                let why = field("failure_reason")?.stringValue ?? ""
                Note("Autofocus failed: \(why.isEmpty ? "no reason given" : why).")
            }
        }
        if samples.isEmpty {
            Note("The V-curve appears here during a run.")
        } else {
            Chart(samples) { sample in
                PointMark(x: .value("Position", sample.position), y: .value("HFR", sample.hfr))
                    .foregroundStyle(theme.accent)
            }
            .chartXScale(domain: .automatic(includesZero: false))
            .themedAxes(theme)
            .frame(height: 180)
            .accessibilityLabel("V-curve, \(samples.count) points (HFR against position)")
            .accessibilityIdentifier("vcurve")
        }
    }
}

extension View {
    /// Chart axes default to system colours, which would break Night.
    func themedAxes(_ theme: ThemeTokens) -> some View {
        chartXAxis {
            AxisMarks { _ in
                AxisGridLine().foregroundStyle(theme.border)
                AxisTick().foregroundStyle(theme.border)
                AxisValueLabel().foregroundStyle(theme.textSecondary)
            }
        }
        .chartYAxis {
            AxisMarks { _ in
                AxisGridLine().foregroundStyle(theme.border)
                AxisTick().foregroundStyle(theme.border)
                AxisValueLabel().foregroundStyle(theme.textSecondary)
            }
        }
    }
}

/// Run and Cancel side by side, with the run's progress under them at the full width (not squeezed into half of it, as
/// an `OperationButton`'s note would be).
private struct AutofocusControls: View {
    @Environment(AppModel.self) private var model
    @State private var message: String?

    var body: some View {
        if model.canOperate {
            let run = model.operation("focus.autofocus")
            let record = model.record(of: "focus.autofocus")
            let running = record?.state.isOpen == true
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Button {
                        Task { message = await model.perform("focus.autofocus") }
                    } label: {
                        HStack(spacing: 8) {
                            if running { ProgressView() } else { Image(systemName: "scope").accessibilityHidden(true) }
                            Text("Run autofocus")
                        }
                    }
                    .buttonStyle(ActionStyle(kind: .prominent))
                    .disabled(run == nil || run?.available == false || running || model.liveLoopRunning)
                    .accessibilityIdentifier("focus.autofocus")
                    Button {
                        Task { message = await model.perform("focus.autofocus_cancel") }
                    } label: {
                        Label("Cancel", systemImage: "xmark")
                    }
                    .buttonStyle(ActionStyle())
                    .disabled(model.operation("focus.autofocus_cancel") == nil)
                    .accessibilityIdentifier("focus.autofocus_cancel")
                }
                if run == nil {
                    Note("This Master doesn't offer autofocus yet.")
                } else if let reason = run?.unavailableBecause, run?.available == false, !running {
                    Note(reason)
                } else if let message {
                    Note(message)
                } else if let record {
                    OutcomeNote(record: record)
                }
            }
        }
    }
}
