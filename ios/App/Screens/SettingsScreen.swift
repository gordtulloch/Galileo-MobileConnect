import GalileoCore
import SwiftUI

/// The app's own preferences and pairings. The observatory is configured on the desktop, and nothing here can
/// change it (`MAPP-CFG-010`); what's shown of it is read-only (`MAPP-CFG-020`).
struct SettingsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        @Bindable var bindable = model
        ScreenScroll("Settings") {
            Columns {
                Card("Theme") {
                    HStack(spacing: 8) {
                        ForEach(ThemeChoice.allCases) { choice in
                            Button(choice.label) { model.theme = choice }
                                .buttonStyle(ActionStyle(kind: model.theme == choice ? .prominent : .normal))
                                .accessibilityIdentifier("theme.\(choice.rawValue)")
                                .accessibilityValue(model.theme == choice ? "selected" : "")
                        }
                    }
                    if model.theme == .night {
                        Note("Lower the screen brightness as well. System keyboards can't be made red: iOS's Color Filters (Settings › Accessibility › Display & Text Size) can turn the whole screen red.")
                    }
                }
                Card("This Master") {
                    Row("Name", model.masterName)
                    Row("Galileo", model.snapshot.galileoVersion ?? "—")
                    Row("This device", model.snapshot.role?.rawValue ?? "—")
                    if model.isDemo {
                        Button("Leave the demo") { model.backToWelcome() }
                            .buttonStyle(ActionStyle(kind: .prominent))
                            .accessibilityIdentifier("demo.leave")
                    } else {
                        UnpairButton()
                    }
                }
            } right: {
                Card("Piers") {
                    ForEach(model.snapshot.piers) { pier in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(pier.name).font(.subheadline.weight(.semibold))
                            Text("\(pier.node)\(pier.observatory.map { " · \($0)" } ?? "")").font(.footnote).foregroundStyle(theme.textSecondary)
                        }
                    }
                    Note("Equipment, Piers and limits are set on the desktop.")
                }
                Card("About") {
                    Row("App version", AppModel.appVersion)
                    Note("This app collects nothing. It talks only to your own Master.")
                }
            }
        }
    }
}

private struct Row: View {
    let label: String
    let value: String
    @Environment(\.theme) private var theme

    init(_ label: String, _ value: String) {
        self.label = label
        self.value = value
    }

    var body: some View {
        HStack {
            Text(label).foregroundStyle(theme.textSecondary)
            Spacer()
            Text(value).fontWeight(.semibold)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct UnpairButton: View {
    @Environment(AppModel.self) private var model
    @State private var asking = false

    var body: some View {
        if asking {
            Note("This device will forget the Master. Remove the pairing on the Master as well.")
            HStack(spacing: 8) {
                Button("Keep") { asking = false }.buttonStyle(ActionStyle())
                Button("Unpair") { model.unpairCurrent() }.buttonStyle(ActionStyle(kind: .destructive)).accessibilityIdentifier("unpair.confirm")
            }
        } else {
            Button("Unpair") { asking = true }.buttonStyle(ActionStyle()).accessibilityIdentifier("unpair")
        }
    }
}
