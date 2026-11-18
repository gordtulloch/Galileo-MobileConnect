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
            LiveRow(label: "State", key: "focus.state")
            LiveRow(label: "Last result", key: "focus.last_result")
            LiveRow(label: "Temperature compensation", key: "focus.compensation")
            AutofocusControls()
            VCurve()
        }
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
        let points = (model.workingPier.flatMap { model.snapshot.live.piers[$0]?.series["focus.vcurve"] } ?? []).suffix(30)
        let samples = points.enumerated().compactMap { index, point -> Sample? in
            guard let position = point.value["position"]?.doubleValue, let hfr = point.value["hfr"]?.doubleValue else { return nil }
            return Sample(id: index, position: position, hfr: hfr)
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
            .accessibilityLabel("V-curve, \(samples.count) points")
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
                    .disabled(run == nil || run?.available == false || running)
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
