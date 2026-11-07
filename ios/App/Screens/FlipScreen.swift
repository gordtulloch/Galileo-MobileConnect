import GalileoCore
import SwiftUI

/// The meridian flip: its state and time remaining, and running one when the Master allows it (`MAPP-FLP-*`).
struct FlipScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScreenScroll("Flip") {
            ReadOnlyHint()
            Columns {
                Card("Meridian flip") {
                    LiveRow(label: "State", key: "flip.state")
                    LiveRow(label: "Minutes to the flip", key: "flip.minutes", digits: 1)
                    LiveRow(label: "Automatic flips", key: "flip.enabled")
                    LiveRow(label: "Pointing", key: "flip.pointing")
                    if let last = model.value("flip.last.outcome")?.value.stringValue { Note("Last flip: \(last).") }
                }
            } right: {
                Card("Flip now") {
                    if let why = model.value("flip.why_not")?.value.stringValue, !why.isEmpty { Note(why) }
                    OperationButton(title: "Flip now", operation: "flip.now", icon: "arrow.left.arrow.right", kind: .prominent)
                    OperationButton(title: "Defer the flip", operation: "flip.defer", icon: "clock")
                }
            }
        }
    }
}
