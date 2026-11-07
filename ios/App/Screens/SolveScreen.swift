import GalileoCore
import SwiftUI

/// Plate solving: solve where the mount is, sync it, or centre it on a position (`MAPP-SOL-010`, `MAPP-SOL-020`).
struct SolveScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        ScreenScroll("Solve") {
            ReadOnlyHint()
            Columns {
                Card("Latest result") {
                    LiveRow(label: "Solving now", key: "solve.running")
                    if let why = model.value("solve.why_not")?.value.stringValue, !why.isEmpty { Note(why) }
                    solutions
                }
                Card("Solve here") {
                    OperationForm(title: "Take a frame and solve", operation: "solve.capture", icon: "camera.viewfinder", fields: ["exposure_s"], kind: .prominent)
                    OperationForm(title: "Solve and sync the mount", operation: "solve.sync", icon: "scope", fields: ["exposure_s"])
                    OperationButton(title: "Stop solving", operation: "solve.stop", icon: "stop")
                }
            } right: {
                Card("Centre on a position") {
                    Note("Slews, then corrects with solved frames until within the accuracy.")
                    OperationForm(title: "Centre", operation: "solve.centre", icon: "location", fields: ["ra_deg", "dec_deg", "accuracy_arcsec"], kind: .prominent)
                }
            }
        }
    }

    @ViewBuilder private var solutions: some View {
        let list = model.value("solve.last.solutions")?.value.arrayValue ?? []
        if list.isEmpty {
            Note("Nothing solved yet.")
        }
        ForEach(list.indices, id: \.self) { index in
            let solution = list[index]
            if solution["success"]?.boolValue == true {
                VStack(alignment: .leading, spacing: 2) {
                    Label("Solved", systemImage: "checkmark.circle").font(.subheadline.weight(.semibold))
                    Text("RA \(number(solution["ra_deg"], 4))°  Dec \(number(solution["dec_deg"], 4))°").font(.subheadline.monospacedDigit())
                    Text("Rotation \(number(solution["rotation_deg"], 1))°  Scale \(number(solution["scale_arcsec_px"], 2))″/px")
                        .font(.footnote).foregroundStyle(theme.textSecondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("solve.solution")
            } else {
                Label(solution["failure_reason"]?.stringValue ?? "This frame didn't solve.", systemImage: "exclamationmark.triangle")
                    .font(.subheadline)
                    .foregroundStyle(theme.warning)
                    .accessibilityElement(children: .combine)
            }
        }
    }

    private func number(_ value: JSONValue?, _ digits: Int) -> String {
        value?.doubleValue.map { String(format: "%.\(digits)f", $0) } ?? "—"
    }
}
