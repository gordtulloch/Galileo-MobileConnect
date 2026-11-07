import SwiftUI

/// The user's choice. Night is the factory default: this app is used in the dark (iOS SDD 3.8).
enum ThemeChoice: String, CaseIterable, Identifiable {
    case night, standard

    var id: String { rawValue }
    var label: String { self == .night ? "Night (red)" : "Standard" }
}

extension EnvironmentValues {
    @Entry var theme: ThemeTokens = .night
}

/// Applied at the root of the scene, so every view gets Night without knowing about it (ADR-IOS-006). No view
/// uses a literal or a system colour: `scripts/check_ios.sh` enforces that.
struct ThemeRoot<Content: View>: View {
    let choice: ThemeChoice
    @ViewBuilder var content: Content
    @Environment(\.colorScheme) private var systemScheme

    private var tokens: ThemeTokens {
        switch choice {
        case .night: .night
        case .standard: systemScheme == .dark ? .dark : .light
        }
    }

    var body: some View {
        content
            .environment(\.theme, tokens)
            .tint(tokens.accent)
            .background(tokens.background.ignoresSafeArea())
            .foregroundStyle(tokens.text)
            .preferredColorScheme(choice == .night ? .dark : nil)
            .statusBarHidden(choice == .night)
            .persistentSystemOverlays(choice == .night ? .hidden : .automatic)
    }
}
