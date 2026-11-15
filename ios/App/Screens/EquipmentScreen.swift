import GalileoCore
import SwiftUI

/// Every device of the working Pier, built from the catalogue (`MAPP-DEV-*`).
struct EquipmentScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScreenScroll("Equipment") {
            ReadOnlyHint()
            JogPad()
            Columns {
                mount
                MeridianFlipCards()
            } right: {
                camera
                filter
                rotator
                dome
            }
        }
    }

    private func connection(_ device: String, _ label: String) -> some View {
        HStack(spacing: 8) {
            OperationButton(title: "Connect \(label)", operation: "device.connect", icon: "power", args: ["device": .string(device)])
            OperationButton(title: "Disconnect", operation: "device.disconnect", icon: "poweroff", args: ["device": .string(device)])
        }
    }

    private var mount: some View {
        Card("Mount") {
            LiveRow(label: "Connected", key: "mount.connected")
            LiveRow(label: "Tracking", key: "mount.tracking")
            LiveRow(label: "Slewing", key: "mount.slewing")
            LiveRow(label: "Parked", key: "mount.parked")
            LiveRow(label: "Target", key: "mount.target")
            LiveRow(label: "RA", key: "mount.position.ra_deg", unit: "°", digits: 3)
            LiveRow(label: "Dec", key: "mount.position.dec_deg", unit: "°", digits: 3)
            LiveRow(label: "Altitude", key: "mount.position.alt_deg", unit: "°", digits: 1)
            connection("mount", "mount")
            HStack(spacing: 8) {
                let tracking = model.value("mount.tracking")?.value.boolValue ?? false
                OperationButton(title: tracking ? "Stop tracking" : "Start tracking", operation: "mount.track", args: ["enabled": .bool(!tracking)])
                OperationButton(title: "Abort slew", operation: "mount.abort_slew", icon: "stop")
            }
            HStack(spacing: 8) {
                OperationButton(title: "Park", operation: "mount.park", icon: "parkingsign")
                OperationButton(title: "Unpark", operation: "mount.unpark")
            }
            OperationForm(title: "Slew", operation: "mount.slew", icon: "location", kind: .prominent)
            OperationForm(title: "Sync", operation: "mount.sync")
        }
    }

    private var camera: some View {
        Card("Camera") {
            LiveRow(label: "Connected", key: "camera.connected")
            LiveRow(label: "State", key: "camera.state")
            LiveRow(label: "Temperature", key: "camera.temperature", unit: "°C", digits: 1)
            LiveRow(label: "Cooling", key: "camera.cooling")
            connection("camera", "camera")
            OperationForm(title: "Set temperature", operation: "camera.set_temperature", icon: "thermometer.snowflake")
        }
    }

    private var filter: some View {
        Card("Filter wheel") {
            LiveRow(label: "Current", key: "filter.current")
            if model.canOperate, let names = model.value("filter.names")?.value.arrayValue {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96))], spacing: 8) {
                    ForEach(names.compactMap(\.stringValue), id: \.self) { name in
                        FilterChoice(name: name)
                    }
                }
            }
            if let record = model.record(of: "filter.select") { OutcomeNote(record: record) }
        }
    }

    private var rotator: some View {
        Card("Rotator") {
            LiveRow(label: "Angle", key: "rotator.angle", unit: "°", digits: 1)
            OperationForm(title: "Move", operation: "rotator.move", icon: "rotate.right")
        }
    }

    private var dome: some View {
        Card("Dome") {
            LiveRow(label: "State", key: "dome.state")
            LiveRow(label: "Azimuth", key: "dome.azimuth", unit: "°", digits: 1)
            HStack(spacing: 8) {
                OperationButton(title: "Open", operation: "dome.open")
                OperationButton(title: "Close", operation: "dome.close")
                OperationButton(title: "Stop", operation: "dome.abort", icon: "stop")
            }
        }
    }
}

private struct FilterChoice: View {
    let name: String
    @Environment(AppModel.self) private var model

    var body: some View {
        let current = model.value("filter.current")?.value.stringValue == name
        Button(name) {
            Task { _ = await model.perform("filter.select", args: ["name": .string(name)]) }
        }
        .buttonStyle(ActionStyle(kind: current ? .prominent : .normal))
        .accessibilityValue(current ? "selected" : "")
    }
}

/// The mount's manual movement, at the top of Equipment and as large as the screen allows (on an iPhone, one full
/// screen) so it can be used in the dark: a 3 x 3 pad of the eight directions with a Stop in the middle. Each arrow moves
/// the mount only while it is held (`MAPP-DEV-*`, `CSURF-330`); the diagonals move both axes.
private struct JogPad: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    private static let rows: [[(String, String, String)]] = [
        [("North West", "arrow.up.left", "northwest"), ("North", "arrow.up", "north"), ("North East", "arrow.up.right", "northeast")],
        [("West", "arrow.left", ""), ("", "", ""), ("East", "arrow.right", "")],
        [("South West", "arrow.down.left", "southwest"), ("South", "arrow.down", "south"), ("South East", "arrow.down.right", "southeast")],
    ]

    private func direction(_ cell: (String, String, String)) -> String { cell.2.isEmpty ? cell.0.lowercased() : cell.2 }

    var body: some View {
        if model.canOperate {
            if let info = model.operation("mount.hold_start") {
                VStack(spacing: 8) {
                    ForEach(Self.rows.indices, id: \.self) { row in
                        HStack(spacing: 8) {
                            ForEach(Self.rows[row].indices, id: \.self) { column in
                                let cell = Self.rows[row][column]
                                if cell.0.isEmpty {
                                    stop
                                } else {
                                    HoldButton(title: cell.0, icon: cell.1, family: "mount",
                                               args: ["direction": .string(direction(cell))], large: true)
                                }
                            }
                        }
                    }
                }
                // One full screen on a phone: the visible height less the screen's own padding.
                .containerRelativeFrame(.vertical) { length, _ in max(320, length - 2 * (Layout.spacing + 4)) }
                if let choices = info.arguments.first(where: { $0.name == "direction" })?.choices, !choices.contains("northeast") {
                    Note("This Master doesn't offer diagonal moves yet: update Galileo on the Master.")
                }
                if let explanation = model.snapshot.hold.explanation { Note(explanation) }
            } else {
                Note("Jog: this Master doesn't offer held mount movement yet.")
            }
        }
    }

    /// The middle of the pad: stop whatever the mount is doing.
    private var stop: some View {
        Button {
            Task { _ = await model.perform("mount.abort_slew") }
        } label: {
            Image(systemName: "stop.fill").font(.system(size: 40, weight: .bold))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(theme.danger, in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(theme.onDanger)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Stop")
        .accessibilityIdentifier("mount.abort_slew")
    }
}

/// Says why there are no controls, when there aren't (`MAPP-CTL-010`).
struct ReadOnlyHint: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if !model.canOperate, let pier = model.workingPier, model.snapshot.unavailableReason(pier: pier) == nil {
            Note(model.snapshot.role == .viewer ? "This device is a Viewer: it can watch but not operate." : "This Pier is view only from this device.")
        }
    }
}
