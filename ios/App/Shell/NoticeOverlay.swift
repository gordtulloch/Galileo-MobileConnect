import GalileoCore
import SwiftUI

/// Event notices over the top-right corner: each exposure start, slew, solve, focus result or connection the Master
/// reports appears for ten seconds, or until its × is tapped (`MAPP-OVR-090`, `MAPP-OVR-100`). Notices already in the
/// state when this first appears are history and are not shown, so reconnecting does not replay old events. Colours
/// come from the theme alone, so Night stays red.
struct NoticeOverlay: View {
    static let seconds: UInt64 = 10
    static let visible = 5

    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var known: Set<String>?
    @State private var dismissed: Set<String> = []

    private var shown: [NoticeEvent] {
        let baseline = known ?? Set(model.snapshot.live.notices.map(\.id))
        return model.snapshot.live.notices
            .filter { !baseline.contains($0.id) && !dismissed.contains($0.id) }
            .prefix(Self.visible).reversed()
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            ForEach(shown) { notice in
                NoticeCard(notice: notice) { dismissed.insert(notice.id) }
                    .task(id: notice.id) {
                        try? await Task.sleep(nanoseconds: Self.seconds * 1_000_000_000)
                        dismissed.insert(notice.id)
                    }
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .frame(maxWidth: 320)
        .padding(8)
        .animation(.easeOut(duration: 0.15), value: shown.map(\.id))
        .onAppear { if known == nil { known = Set(model.snapshot.live.notices.map(\.id)) } }
    }
}

private struct NoticeCard: View {
    @Environment(\.theme) private var theme
    let notice: NoticeEvent
    let close: () -> Void

    private var accent: Color {
        switch notice.severity {
        case "error": theme.danger
        case "warning": theme.warning
        case "success": theme.good
        default: theme.accent
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            Rectangle().fill(accent).frame(width: 5)
            VStack(alignment: .leading, spacing: 1) {
                Text(notice.title).font(.subheadline.weight(.bold)).foregroundStyle(theme.text)
                if !notice.message.isEmpty {
                    Text(notice.message).font(.footnote).foregroundStyle(theme.textSecondary)
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: close) {
                Text("×").font(.title3).foregroundStyle(theme.textSecondary).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Dismiss")
            .accessibilityIdentifier("notice.close")
        }
        .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("notice.\(notice.kind)")
    }
}
