import GalileoCore
import SwiftUI

enum Area: String, CaseIterable, Identifiable {
    case overview = "Overview"
    case equipment = "Equipment"
    case imaging = "Imaging"
    case focus = "Focus"
    case guiding = "Guiding"
    case safety = "Safety"
    case settings = "Settings"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .overview: "rectangle.3.group"
        case .equipment: "scope"
        case .imaging: "camera.aperture"
        case .focus: "circle.dotted.circle"
        case .guiding: "dot.scope"
        case .safety: "exclamationmark.shield"
        case .settings: "gearshape"
        }
    }
}

/// I-SHELL: what is on every screen is here, so it can't be forgotten on one of them.
struct ShellView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var selection: Area? = .overview
    @State private var columns = NavigationSplitViewVisibility.all

    var body: some View {
        VStack(spacing: 0) {
            TopBar()
            ConnectionBanner()
            ZStack {
                if sizeClass == .compact {
                    // No system navigation bar here: its back button can't be held to Night.
                    VStack(spacing: 0) {
                        AreaStrip(selection: $selection)
                        detail(selection ?? .overview)
                    }
                } else {
                NavigationSplitView(columnVisibility: $columns) {
                    List(Area.allCases, selection: $selection) { area in
                        Label(area.rawValue, systemImage: area.icon)
                            .foregroundStyle(selection == area ? theme.onAccent : theme.text)
                            .frame(minHeight: 44)
                            .listRowBackground(selection == area ? theme.accent : theme.surface)
                            .tag(area)
                            .accessibilityElement(children: .combine)
                            .accessibilityIdentifier("area.\(area.rawValue)")
                    }
                    .scrollContentBackground(.hidden)
                    .background(theme.surface)
                    .toolbar(removing: .sidebarToggle)
                    .toolbarBackground(theme.surface, for: .navigationBar)
                    .navigationBarTitleDisplayMode(.inline)
                } detail: {
                    NavigationStack {
                        detail(selection ?? .overview)
                    }
                }
                .navigationSplitViewStyle(.balanced)
                }

                DialogLayer()
            }
        }
        .overlay {
            if model.emergencyOpen { EmergencyPanel() }
        }
    }

    @ViewBuilder private func detail(_ area: Area) -> some View {
        switch area {
        case .overview: OverviewScreen()
        case .equipment: EquipmentScreen()
        case .imaging: ImagingScreen()
        case .focus: FocusScreen()
        case .guiding: GuidingScreen()
        case .safety: SafetyScreen()
        case .settings: SettingsScreen()
        }
    }
}

/// The areas on a phone: a strip across the top, so that every area is one tap from any other.
struct AreaStrip: View {
    @Binding var selection: Area?
    @Environment(\.theme) private var theme

    var body: some View {
        ScrollViewReader { reader in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Area.allCases) { area in
                        Button {
                            selection = area
                        } label: {
                            Label(area.rawValue, systemImage: area.icon)
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 12)
                                .frame(minHeight: 44)
                                .background(selection == area ? theme.accent : theme.raised, in: RoundedRectangle(cornerRadius: 8))
                                .foregroundStyle(selection == area ? theme.onAccent : theme.text)
                        }
                        .buttonStyle(.plain)
                        .id(area)
                        .accessibilityValue(selection == area ? "selected" : "")
                        .accessibilityIdentifier("area.\(area.rawValue)")
                    }
                }
                .padding(8)
            }
            .background(theme.surface)
            .onChange(of: selection) { _, new in
                withAnimation { reader.scrollTo(new, anchor: .center) }
            }
        }
    }
}

/// The working Pier, connection, role and lease, safety, and the emergency button (iOS SDD 3.1).
struct TopBar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.verticalSizeClass) private var heightClass

    var body: some View {
        Group {
            // A phone on its side has the width for one row and not the height for two.
            if sizeClass == .regular || heightClass == .compact {
                HStack(spacing: 10) {
                    pierPicker
                    connection
                    role
                    Spacer(minLength: 0)
                    lease
                    safety
                    emergency
                }
            } else {
                VStack(spacing: 6) {
                    HStack {
                        pierPicker
                        Spacer(minLength: 0)
                        emergency
                    }
                    HStack(spacing: 8) {
                        connection
                        safety
                        Spacer(minLength: 0)
                        lease
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(theme.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.border).frame(height: 1) }
    }

    private var pierPicker: some View {
        // A dialog rather than a Menu: a system menu can't be held to Night.
        Button {
            model.pierPickerOpen = true
        } label: {
            Label(model.pier?.name ?? "No Pier", systemImage: "chevron.up.chevron.down")
                .font(.headline)
                .foregroundStyle(theme.text)
                .frame(minHeight: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Working Pier: \(model.pier?.name ?? "none")")
        .accessibilityIdentifier("pier.picker")
    }

    private var connection: some View {
        let (icon, text): (String, String) = switch model.snapshot.connection {
        case .connecting: ("antenna.radiowaves.left.and.right", "Connecting")
        case .connected: ("checkmark.circle", "Connected")
        case .reconnecting: ("arrow.triangle.2.circlepath", "Reconnecting")
        case .unreachable: ("wifi.slash", "Unreachable")
        }
        return Label(text, systemImage: icon)
            .font(.subheadline)
            .foregroundStyle(model.snapshot.isConnected ? theme.text : theme.warning)
            // The icon alone would read as "Selected".
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Connection: \(text)")
            .accessibilityIdentifier("connection")
    }

    @ViewBuilder private var role: some View {
        if let role = model.snapshot.role {
            Text(role.rawValue).font(.subheadline).foregroundStyle(theme.textSecondary)
        }
    }

    @ViewBuilder private var lease: some View {
        if model.snapshot.role == .operator, let pier = model.workingPier, model.canOperate {
            switch model.snapshot.lease(pier: pier) {
            case .mine:
                Button { model.releaseLease() } label: {
                    Label("Lease: yours", systemImage: "lock.open").font(.subheadline)
                }
                .buttonStyle(.plain)
                .frame(minHeight: 44)
                .accessibilityHint("Releases the lease")
                .accessibilityIdentifier("lease.release")
            case .free:
                Button { model.takeLease() } label: {
                    Label("Take lease", systemImage: "lock").font(.subheadline)
                }
                .buttonStyle(.plain)
                .frame(minHeight: 44)
                .accessibilityIdentifier("lease.take")
            case .other(let holder):
                Button { model.takeLease() } label: {
                    Label("Lease: \(holder)", systemImage: "lock.fill").font(.subheadline)
                }
                .buttonStyle(.plain)
                .frame(minHeight: 44)
                .accessibilityHint("Asks to take over")
                .accessibilityIdentifier("lease.ask")
            }
        } else if let pier = model.workingPier, case .other(let holder) = model.snapshot.lease(pier: pier) {
            Label("Lease: \(holder)", systemImage: "lock.fill").font(.subheadline)
        }
    }

    private var safety: some View {
        let overall = model.overallSafety
        let unsafe = overall == "unsafe"
        return Label(overall.map { $0.capitalized } ?? "Safety unknown", systemImage: unsafe ? "exclamationmark.octagon.fill" : "checkmark.shield")
            .font(.subheadline.weight(unsafe ? .bold : .regular))
            .padding(.horizontal, unsafe ? 8 : 0)
            .padding(.vertical, unsafe ? 4 : 0)
            .background(unsafe ? theme.danger : .clear, in: RoundedRectangle(cornerRadius: 6))
            .foregroundStyle(unsafe ? theme.onDanger : theme.text)
            .accessibilityLabel("Safety: \(overall ?? "unknown")")
            .accessibilityIdentifier("safety.indicator")
    }

    @ViewBuilder private var emergency: some View {
        if model.snapshot.role == .operator {
            Button {
                model.emergencyOpen = true
            } label: {
                Label("Emergency", systemImage: "exclamationmark.octagon.fill")
                    .font(.subheadline.weight(.bold))
                    .padding(.horizontal, 12)
                    .frame(minHeight: 44)
                    .background(theme.danger, in: RoundedRectangle(cornerRadius: 8))
                    .foregroundStyle(theme.onDanger)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("emergency")
        }
    }
}

/// Reconnecting, unreachable, a demo: always said, never implied (`MAPP-CON-070`, `MAPP-CON-110`).
struct ConnectionBanner: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            if model.isDemo {
                Banner(icon: "theatermasks", text: "DEMO — simulated observatory").padding(.horizontal, 8).padding(.top, 4)
                    .accessibilityIdentifier("demo.banner")
            }
            switch model.snapshot.connection {
            case .reconnecting:
                Banner(icon: "arrow.triangle.2.circlepath", text: "Reconnecting. Values shown are not live.").padding(8)
            case .unreachable:
                Banner(icon: "wifi.slash", text: "The Master can't be reached. Still trying. Values shown are not live.").padding(8)
            default:
                EmptyView()
            }
            if let notice = model.snapshot.notice {
                Banner(icon: "exclamationmark.triangle", text: notice).padding(8)
            }
            if let reason = model.snapshot.live.closingReason, !reason.isEmpty, model.snapshot.isConnected == false {
                Banner(icon: "xmark.octagon", text: "The Master closed the link: \(reason)").padding(8)
            }
        }
    }
}

/// App dialogs are drawn here rather than as system sheets: a system sheet can't be held to Night, and the
/// emergency button above must stay reachable while a question is open (iOS SDD 3.9, risk I5).
struct DialogLayer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        @Bindable var bindable = model
        if let question = model.question {
            Dialog(title: question.title) {
                if !question.details.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(question.details, id: \.self) { Text($0) }
                    }
                }
                if let consequence = question.consequence { Text(consequence) }
                if let pier = model.pier { Note("On \(pier.name).") }
                Button("Cancel") { model.question = nil }
                    .buttonStyle(ActionStyle())
                    .keyboardShortcut(.cancelAction)
                    .accessibilityIdentifier("confirm.cancel")
                Button("Yes, do it") { model.accept(question) }
                    .buttonStyle(ActionStyle(kind: .destructive))
                    .accessibilityIdentifier("confirm.accept")
            }
        } else if model.pierPickerOpen {
            Dialog(title: "Working Pier") {
                ForEach(model.snapshot.piers) { pier in
                    Button("\(pier.name) — \(pier.node)") {
                        model.workingPier = pier.id
                        model.pierPickerOpen = false
                    }
                    .buttonStyle(ActionStyle(kind: pier.id == model.workingPier ? .prominent : .normal))
                    .accessibilityIdentifier("pier.choose.\(pier.id)")
                }
                Button("Close") { model.pierPickerOpen = false }.buttonStyle(ActionStyle())
            }
        } else if let offer = model.leaseOffer {
            Dialog(title: "The lease is held") {
                Text(offer.reason)
                Button("Close") { model.leaseOffer = nil }
                    .buttonStyle(ActionStyle())
                    .accessibilityIdentifier("lease.close")
                if let token = offer.takeOverToken {
                    Note("Taking over stops whatever \(offer.holder ?? "the holder") is doing by hand.")
                    Button("Take over") {
                        model.leaseOffer = nil
                        model.takeLease(token: token)
                    }
                    .buttonStyle(ActionStyle(kind: .destructive))
                    .accessibilityIdentifier("lease.takeover")
                }
            }
        }
    }
}

struct Dialog<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content
    @Environment(\.theme) private var theme

    var body: some View {
        ZStack {
            theme.background.opacity(0.8).ignoresSafeArea().accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Layout.spacing) {
                Text(title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                content
            }
            .padding(20)
            .frame(maxWidth: 480)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(theme.border))
            .padding(24)
        }
        .accessibilityAddTraits(.isModal)
    }
}

/// Two interactions from anywhere: open this, then hold (`MAPP-EMG-010`). The results list each part.
struct EmergencyPanel: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    private let actions = [
        ("Stop everything", "stop.fill", "system.stop_everything"),
        ("Park mount", "parkingsign", "mount.emergency_park"),
        ("Close dome", "house.lodge", "dome.emergency_close"),
    ]

    var body: some View {
        Dialog(title: "Emergency") {
            Note("Press and hold for a second. On \(model.pier?.name ?? "this Pier"). No lease needed.")
            ForEach(actions, id: \.2) { title, icon, operation in
                let info = model.operation(operation)
                HoldToConfirm(title: title, icon: icon, identifier: "emergency.\(operation)") {
                    model.emergency(operation)
                }
                .disabled(info == nil)
                if let record = model.record(of: operation) { outcome(record) }
            }
            Button("Close") { model.emergencyOpen = false }
                .buttonStyle(ActionStyle())
                .accessibilityIdentifier("emergency.close")
        }
    }

    @ViewBuilder private func outcome(_ record: OperationRecord) -> some View {
        if case .done(let value) = record.state, let parts = value?["parts"]?.arrayValue {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(parts.indices, id: \.self) { index in
                    let ok = parts[index]["ok"]?.boolValue == true
                    Label(
                        "\(parts[index]["name"]?.stringValue ?? "Part"): \(ok ? "done" : "FAILED")",
                        systemImage: ok ? "checkmark" : "xmark.octagon.fill"
                    )
                    .font(.footnote.weight(ok ? .regular : .bold))
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("emergency.result")
        } else {
            OutcomeNote(record: record)
        }
    }
}
