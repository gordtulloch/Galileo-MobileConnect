import GalileoCore
import SwiftUI

struct Card<Content: View>: View {
    let title: String?
    @ViewBuilder var content: Content
    @Environment(\.theme) private var theme
    @Environment(\.compactControls) private var compact

    init(_ title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 6 : Layout.spacing) {
            if let title {
                Text(title).font(compact ? .subheadline.weight(.semibold) : .headline).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            }
            content
        }
        .padding(compact ? 8 : Layout.spacing + 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: Layout.cornerRadius))
        .overlay(RoundedRectangle(cornerRadius: Layout.cornerRadius).stroke(theme.border))
    }
}

/// Screen scaffolding: the same padding and, on a wide screen, the same columns.
struct ScreenScroll<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content
    @Environment(\.theme) private var theme
    @Environment(AppModel.self) private var model

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Layout.spacing) {
                if let reason = model.workingPier.flatMap({ model.snapshot.unavailableReason(pier: $0) }) {
                    Banner(icon: "exclamationmark.triangle", text: "\(model.pier?.name ?? "This Pier"): \(reason)")
                }
                content
            }
            .padding(Layout.spacing + 4)
        }
        .background(theme.background)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(title).font(.headline).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(theme.surface, for: .navigationBar)
    }
}

/// Two columns where there is room (`MAPP-LAY-010`), one where there is not (`MAPP-LAY-020`).
struct Columns<Left: View, Right: View>: View {
    @ViewBuilder var left: Left
    @ViewBuilder var right: Right
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if sizeClass == .regular {
            HStack(alignment: .top, spacing: Layout.spacing) {
                VStack(spacing: Layout.spacing) { left }.frame(maxWidth: .infinity)
                VStack(spacing: Layout.spacing) { right }.frame(maxWidth: .infinity)
            }
        } else {
            VStack(spacing: Layout.spacing) {
                left
                right
            }
        }
    }
}

struct Banner: View {
    let icon: String
    let text: String
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: icon).accessibilityHidden(true)
            Text(text).frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(theme.onDanger)
        .padding(10)
        .frame(maxWidth: .infinity)
        .background(theme.danger, in: RoundedRectangle(cornerRadius: 8))
        .accessibilityElement(children: .combine)
    }
}

/// A label and the Master's value. A stale value is dimmed, crossed through and shows its age: never as if live.
struct LiveRow: View {
    let label: String
    let key: String
    var unit: String?
    var digits: Int?
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        let view = model.value(key)
        HStack(alignment: .firstTextBaseline) {
            Text(label).foregroundStyle(theme.textSecondary)
            Spacer(minLength: 12)
            if let view {
                VStack(alignment: .trailing, spacing: 0) {
                    Text(text(view.value))
                        .fontWeight(.semibold)
                        .strikethrough(view.stale)
                        .opacity(view.stale ? 0.6 : 1)
                    if view.stale {
                        Text("as of \(Int(view.age)) s ago").font(.caption)
                    }
                }
            } else {
                Text("—").foregroundStyle(theme.textSecondary)
            }
        }
        .frame(minHeight: 28)
        .accessibilityElement(children: .combine)
        .accessibilityValue(view?.stale == true ? "not live" : "")
    }

    private func text(_ value: JSONValue) -> String {
        var result = value.displayText
        if let digits, let number = value.doubleValue { result = String(format: "%.\(digits)f", number) }
        if let unit { result += " \(unit)" }
        return result
    }
}

enum ActionKind { case normal, prominent, destructive }

extension EnvironmentValues {
    /// For a panel whose space is short and which sits beside what it controls: smaller controls, by the user's choice,
    /// over the gloved-hands size used everywhere else.
    @Entry var compactControls = false
}

struct ActionStyle: ButtonStyle {
    var kind: ActionKind = .normal
    @Environment(\.theme) private var theme
    @Environment(\.compactControls) private var compact
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let (background, foreground): (Color, Color) = switch kind {
        case .normal: (theme.raised, theme.text)
        case .prominent: (theme.accent, theme.onAccent)
        case .destructive: (theme.danger, theme.onDanger)
        }
        configuration.label
            .font(compact ? .footnote.weight(.semibold) : .body.weight(.semibold))
            .multilineTextAlignment(.center)
            .lineLimit(2)
            .minimumScaleFactor(0.7)
            .padding(.horizontal, compact ? 6 : 10)
            .padding(.vertical, compact ? 4 : 8)
            .frame(maxWidth: .infinity, minHeight: compact ? Layout.compactTarget : Layout.minimumTarget)
            .background(background, in: RoundedRectangle(cornerRadius: compact ? 7 : 10))
            .foregroundStyle(foreground)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(theme.border))
            .opacity(!isEnabled ? 0.5 : configuration.isPressed ? 0.7 : 1)
    }
}

/// A control bound to a catalogue operation (ADR-APP-004). A Viewer sees nothing (`MAPP-CTL-010`); an absent or
/// unavailable operation is disabled with the reason; the outcome shown is the Master's (`MAPP-CTL-050`).
struct OperationButton: View {
    let title: String
    let operation: String
    var icon: String?
    var args: [String: JSONValue] = [:]
    var kind: ActionKind = .normal
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var message: String?

    var body: some View {
        if model.canOperate {
            let info = model.operation(operation)
            let record = model.record(of: operation)
            VStack(alignment: .leading, spacing: 4) {
                Button {
                    Task { message = await model.perform(operation, args: args) }
                } label: {
                    HStack(spacing: 8) {
                        if record?.state.isOpen == true {
                            ProgressView().tint(theme.text)
                        } else if let icon {
                            Image(systemName: icon).accessibilityHidden(true)
                        }
                        Text(title)
                    }
                }
                .buttonStyle(ActionStyle(kind: kind))
                .disabled(info == nil || info?.available == false || record?.state.isOpen == true)
                .accessibilityIdentifier(operation)
                if info == nil {
                    Note("This Master doesn't offer this yet.")
                } else if let reason = info?.unavailableBecause, info?.available == false, record?.state.isOpen != true {
                    // Not while this very operation is the thing running: "already running" would be our own doing.
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

/// The system progress bar's track is grey, which Night can't have.
struct ThemedProgress: View {
    let value: Double
    var label = "Progress"
    @Environment(\.theme) private var theme

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(theme.raised)
                Capsule().fill(theme.accent).frame(width: geometry.size.width * min(1, max(0, value)))
            }
        }
        .frame(height: 6)
        .accessibilityElement()
        .accessibilityLabel(label)
        .accessibilityValue("\(Int(min(1, max(0, value)) * 100)) percent")
    }
}

/// The system slider's thumb is white and its track grey, which Night can't have.
struct ThemedSlider: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    @Environment(\.theme) private var theme
    @Environment(\.compactControls) private var compact

    var body: some View {
        GeometryReader { geometry in
            let thumb: CGFloat = compact ? 20 : 28
            let span = range.upperBound - range.lowerBound
            let fraction = (value - range.lowerBound) / span
            let x = (geometry.size.width - thumb) * fraction
            ZStack(alignment: .leading) {
                Capsule().fill(theme.raised).frame(height: 6).overlay(Capsule().stroke(theme.border))
                Capsule().fill(theme.accent).frame(width: x + thumb / 2, height: 6)
                Circle().fill(theme.accent).frame(width: thumb, height: thumb).offset(x: x)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0).onChanged { drag in
                    let position = (drag.location.x - thumb / 2) / max(1, geometry.size.width - thumb)
                    value = range.lowerBound + span * min(1, max(0, position))
                }
            )
        }
        .frame(height: compact ? 28 : 44)
        .accessibilityElement()
        .accessibilityLabel(label)
        .accessibilityValue(value.formatted(.number.precision(.fractionLength(2))))
        .accessibilityAdjustableAction { direction in
            let step = (range.upperBound - range.lowerBound) / 20
            switch direction {
            case .increment: value = min(range.upperBound, value + step)
            case .decrement: value = max(range.lowerBound, value - step)
            @unknown default: break
            }
        }
    }
}

struct Note: View {
    let text: String
    @Environment(\.theme) private var theme
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.footnote).foregroundStyle(theme.textSecondary).fixedSize(horizontal: false, vertical: true)
    }
}

/// What became of the last request. Done is only ever the Master's word.
struct OutcomeNote: View {
    let record: OperationRecord
    @Environment(\.theme) private var theme

    var body: some View {
        switch record.state {
        case .pending: Note("Waiting for the Master…")
        case .running(let progress):
            if let progress {
                ThemedProgress(value: progress)
            } else {
                Note("Running…")
            }
        case .done: Note("Done.")
        case .refused(let why), .unavailable(let why), .busy(let why), .unreachable(let why): attention(why)
        case .invalid(let path, let why):
            attention([path, why].compactMap { $0 }.joined(separator: " "))
        case .outcomeUnknown:
            attention("Outcome unknown: the connection dropped. Check the state before trying again.")
        }
    }

    private func attention(_ text: String) -> some View {
        Label(text, systemImage: "exclamationmark.triangle")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(theme.warning)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A form built from a catalogue entry's arguments: ranges and units come from the Master (ADR-APP-004).
struct OperationForm: View {
    let title: String
    let operation: String
    var icon: String?
    /// Arguments to show; the rest keep the Master's defaults.
    var fields: [String]?
    var kind: ActionKind = .normal
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.compactControls) private var compact
    @State private var text: [String: String] = [:]
    @State private var flags: [String: Bool] = [:]
    @State private var message: String?

    var body: some View {
        if model.canOperate {
            let info = model.operation(operation)
            VStack(alignment: .leading, spacing: 8) {
                if compact {
                    // Short boxes side by side, so the form takes a line or two rather than a screenful.
                    let arguments = shown(info)
                    HStack(alignment: .bottom, spacing: 6) {
                        ForEach(arguments.filter(\.isBoxed)) { field($0) }
                    }
                    ForEach(arguments.filter { !$0.isBoxed }) { field($0) }
                } else {
                    ForEach(shown(info)) { argument in
                        field(argument)
                    }
                }
                let record = model.record(of: operation)
                Button {
                    guard let info else { return }
                    Task { message = await send(info) }
                } label: {
                    HStack(spacing: 8) {
                        if record?.state.isOpen == true {
                            ProgressView().tint(theme.text)
                        } else if let icon {
                            Image(systemName: icon).accessibilityHidden(true)
                        }
                        Text(title)
                    }
                }
                .buttonStyle(ActionStyle(kind: kind))
                .disabled(info == nil || info?.available == false || record?.state.isOpen == true)
                .accessibilityIdentifier(operation)
                if info == nil {
                    Note("This Master doesn't offer this yet.")
                } else if let reason = info?.unavailableBecause, info?.available == false {
                    Note(reason)
                } else if let message {
                    Note(message)
                } else if let record {
                    OutcomeNote(record: record)
                }
            }
        }
    }

    private func shown(_ info: OperationInfo?) -> [ArgumentInfo] {
        guard let arguments = info?.arguments else { return [] }
        guard let fields else { return arguments }
        return arguments.filter { fields.contains($0.name) }
    }

    /// `ra_deg` reads as "RA" with its unit beside it, not "Ra Deg (deg)".
    private func title(_ name: String) -> String {
        var words = name.split(separator: "_").map(String.init)
        if let last = words.last, words.count > 1, ["deg", "s", "arcsec", "pct"].contains(last) { words.removeLast() }
        return words.map { word in
            ["ra": "RA", "id": "ID"][word] ?? word.capitalized
        }.joined(separator: " ")
    }

    @ViewBuilder private func field(_ argument: ArgumentInfo) -> some View {
        let label = title(argument.name)
        switch argument.kind {
        case .bool:
            Toggle(label, isOn: Binding(get: { flags[argument.name] ?? false }, set: { flags[argument.name] = $0 }))
                .frame(minHeight: compact ? Layout.compactTarget : Layout.minimumTarget)
                .font(compact ? .footnote : .body)
        case .enum:
            // Buttons rather than a Picker: a system menu can't be held to Night.
            let selected = text[argument.name] ?? argument.defaultValue?.stringValue ?? argument.choices.first ?? ""
            Text(label).font(compact ? .caption2 : .footnote).foregroundStyle(theme.textSecondary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: compact ? 56 : 88))], spacing: compact ? 4 : 8) {
                ForEach(argument.choices, id: \.self) { choice in
                    Button(choice) { text[argument.name] = choice }
                        .buttonStyle(ActionStyle(kind: selected == choice ? .prominent : .normal))
                        .accessibilityValue(selected == choice ? "selected" : "")
                }
            }
        default:
            if compact {
                VStack(alignment: .leading, spacing: 0) {
                    Text(label + (argument.unit.map { " (\($0))" } ?? ""))
                        .font(.caption2).foregroundStyle(theme.textSecondary).lineLimit(1).minimumScaleFactor(0.7)
                    entry(argument, label: label).font(.footnote)
                }
                .frame(minHeight: Layout.compactTarget)
                .padding(.horizontal, 6)
                .background(theme.raised, in: RoundedRectangle(cornerRadius: 7))
            } else {
                HStack {
                    Text(label + (argument.unit.map { " (\($0))" } ?? "")).foregroundStyle(theme.textSecondary)
                    entry(argument, label: label).multilineTextAlignment(.trailing)
                }
                .frame(minHeight: Layout.minimumTarget)
                .padding(.horizontal, 10)
                .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    private func entry(_ argument: ArgumentInfo, label: String) -> some View {
        TextField(
            "", text: Binding(get: { text[argument.name] ?? "" }, set: { text[argument.name] = $0 }),
            prompt: Text(hint(argument)).foregroundStyle(theme.textSecondary)
        )
        .keyboardType(argument.kind == .str ? .default : .numbersAndPunctuation)
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .foregroundStyle(theme.text)
        .accessibilityLabel(label)
        .accessibilityIdentifier("\(operation).\(argument.name)")
    }

    private func hint(_ argument: ArgumentInfo) -> String {
        if let shown = argument.defaultValue?.displayText, argument.defaultValue != nil { return shown }
        if let low = argument.minimum, let high = argument.maximum { return "\(low.formatted()) to \(high.formatted())" }
        return ""
    }

    private func send(_ info: OperationInfo) async -> String? {
        var args: [String: JSONValue] = [:]
        for argument in shown(info) {
            switch argument.kind {
            case .bool:
                if let flag = flags[argument.name] { args[argument.name] = .bool(flag) }
            case .int, .float:
                guard let entered = text[argument.name], !entered.isEmpty else { continue }
                // Left as text if it isn't a number, so the validator names the field.
                args[argument.name] = Double(entered).map(JSONValue.number) ?? .string(entered)
            case .enum, .str:
                if let entered = text[argument.name], !entered.isEmpty { args[argument.name] = .string(entered) }
            }
        }
        return await model.perform(operation, args: args)
    }
}

/// Moves only while pressed (`MAPP-DEV-030`). The core does the timing and the stopping.
struct HoldButton: View {
    let title: String
    let icon: String
    let family: String
    let args: [String: JSONValue]
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var pressed = false

    var body: some View {
        Label(title, systemImage: icon)
            .font(.body.weight(.semibold))
            .frame(maxWidth: .infinity, minHeight: Layout.minimumTarget)
            .background(pressed ? theme.accent : theme.raised, in: RoundedRectangle(cornerRadius: 10))
            .foregroundStyle(pressed ? theme.onAccent : theme.text)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(theme.border))
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        model.holdPressed(family: family, args: args)
                    }
                    .onEnded { _ in release() }
            )
            .onDisappear { release() }
            .accessibilityLabel(title)
            .accessibilityHint("Hold to move. Moves only while held.")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction(named: "Start moving") {
                pressed = true
                model.holdPressed(family: family, args: args)
            }
            .accessibilityAction(named: "Stop moving") { release() }
            .accessibilityIdentifier("hold.\(family).\(title)")
    }

    private func release() {
        guard pressed else { return }
        pressed = false
        model.holdReleased()
    }
}

extension HoldSession.State {
    /// What to say about a hold that ended by itself, or nil if there's nothing to say.
    var explanation: String? {
        guard case .stopped(let reason) = self else { return nil }
        switch reason {
        case .released, .background: return nil
        case .connectionLost: return "Connection lost. The Master stops the motion by itself within a second."
        case .leaseLost: return "Stopped: this device no longer has control."
        case .beatFailed: return "Stopped: the Master ended the motion."
        case .refused(let why): return why
        }
    }
}

/// A press held for about a second: the deliberate gesture of the emergency actions (`MAPP-EMG-010`, iOS IO3).
struct HoldToConfirm: View {
    let title: String
    let icon: String
    let identifier: String
    let action: () -> Void
    @Environment(\.theme) private var theme
    @State private var progress: CGFloat = 0
    @State private var firing: Task<Void, Never>?

    var body: some View {
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 12).fill(theme.raised)
            GeometryReader { geometry in
                RoundedRectangle(cornerRadius: 12).fill(theme.danger).frame(width: geometry.size.width * progress)
            }
            Label(title, systemImage: icon)
                .font(.title3.weight(.bold))
                .foregroundStyle(progress > 0.5 ? theme.onDanger : theme.text)
                .frame(maxWidth: .infinity)
        }
        .frame(height: 72)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.danger, lineWidth: 2))
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in begin() }
                .onEnded { _ in cancel() }
        )
        .accessibilityElement()
        .accessibilityLabel(title)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Press and hold for a second, or use the action.")
        .accessibilityAction { action() }
        .accessibilityIdentifier(identifier)
    }

    private func begin() {
        guard firing == nil else { return }
        withAnimation(.linear(duration: 1)) { progress = 1 }
        firing = Task {
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            action()
            firing = nil
            progress = 0
        }
    }

    private func cancel() {
        firing?.cancel()
        firing = nil
        withAnimation(.easeOut(duration: 0.15)) { progress = 0 }
    }
}

extension ArgumentInfo {
    /// Typed in, rather than switched or chosen.
    fileprivate var isBoxed: Bool { kind != .bool && kind != .enum }
}
