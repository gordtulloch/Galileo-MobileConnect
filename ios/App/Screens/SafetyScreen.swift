import GalileoCore
import SwiftUI

/// Every input and the overall state. The only action on a safety event is to acknowledge it: nothing here can
/// disable the monitor, clear an unsafe state, resume after a stop or override a limit (`MAPP-SAF-*`).
struct SafetyScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScreenScroll("Safety") {
            Columns {
                Card("Overall") {
                    LiveRow(label: "State", key: "safety.overall")
                    if model.value("session.stop_reason") != nil {
                        LiveRow(label: "Session stopped because", key: "session.stop_reason")
                    }
                    if model.value("safety.acknowledged") != nil { LiveRow(label: "Acknowledged", key: "safety.acknowledged") }
                    if model.snapshot.role == .operator {
                        // No lease is needed; the Master classes this as lease-free.
                        AcknowledgeButton()
                    }
                }
            } right: {
                Card("Inputs") {
                    let inputs = model.children(of: "safety.inputs")
                    if inputs.isEmpty { Note("The Master reports no inputs for this Pier.") }
                    ForEach(inputs, id: \.self) { input in
                        LiveRow(label: input, key: "safety.inputs.\(input)")
                    }
                }
                Card("Weather") {
                    LiveRow(label: "Temperature", key: "weather.temperature_c", unit: "°C", digits: 1)
                    LiveRow(label: "Humidity", key: "weather.humidity_pct", unit: "%")
                    LiveRow(label: "Wind", key: "weather.wind_kmh", unit: "km/h")
                }
            }
        }
    }
}

private struct AcknowledgeButton: View {
    @Environment(AppModel.self) private var model
    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button("Acknowledge") {
                Task { message = await model.perform("safety.acknowledge") }
            }
            .buttonStyle(ActionStyle(kind: model.overallSafety == "unsafe" ? .prominent : .normal))
            .disabled(model.operation("safety.acknowledge") == nil)
            .accessibilityIdentifier("safety.acknowledge")
            if let message {
                Note(message)
            } else if let record = model.record(of: "safety.acknowledge") {
                OutcomeNote(record: record)
            }
        }
    }
}
