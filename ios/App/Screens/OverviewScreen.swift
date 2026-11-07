import GalileoCore
import SwiftUI

/// Every Pier, the working Pier's activity, and the alerts (`MAPP-OVR-*`).
struct OverviewScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        ScreenScroll("Overview") {
            if model.snapshot.live.phase != .live {
                Card {
                    HStack(spacing: 10) {
                        ProgressView().tint(theme.text)
                        Text(model.snapshot.live.phase == .resyncing ? "Catching up with the Master…" : "Loading the observatory…")
                    }
                }
            }
            Columns {
                piers
                ActivityCard()
            } right: {
                session
                alerts
            }
        }
    }

    private var piers: some View {
        Card("Piers") {
            ForEach(model.snapshot.piers) { pier in
                let selected = pier.id == model.workingPier
                Button {
                    model.workingPier = pier.id
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(pier.name).font(.headline)
                            Text(pier.node).font(.footnote)
                            Text(statusLine(pier)).font(.footnote)
                        }
                        Spacer()
                        if selected {
                            Image(systemName: "checkmark.circle.fill").accessibilityLabel("Working Pier")
                        }
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, minHeight: Layout.minimumTarget, alignment: .leading)
                    .background(selected ? theme.raised : .clear, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(theme.border))
                    .foregroundStyle(theme.text)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("pier.\(pier.id)")
            }
        }
    }

    private func statusLine(_ pier: PierInfo) -> String {
        if let reason = model.snapshot.unavailableReason(pier: pier.id) { return "Not available: \(reason)" }
        switch model.snapshot.access(pier: pier.id) {
        case .control: return "Can be operated"
        case .view: return "View only"
        case .none: return "Not granted to this device"
        }
    }

    private var session: some View {
        Card("Session") {
            LiveRow(label: "Name", key: "session.name")
            LiveRow(label: "State", key: "session.state")
            LiveRow(label: "Now", key: "session.block")
            if let progress = model.value("session.progress")?.value.doubleValue {
                ThemedProgress(value: progress, label: "Session progress")
            }
            if model.value("session.stop_reason") != nil { LiveRow(label: "Stopped because", key: "session.stop_reason") }
            LiveRow(label: "Meridian flip", key: "flip.state")
            LiveRow(label: "Minutes to flip", key: "flip.minutes")
        }
    }

    private var alerts: some View {
        Card("Alerts") {
            let list = model.snapshot.live.alerts.prefix(12)
            if list.isEmpty {
                Note("Nothing to report.")
            }
            ForEach(Array(list)) { alert in
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: icon(alert.severity)).accessibilityLabel(alert.severity)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(alert.message).font(.subheadline.weight(alert.severity == "critical" ? .bold : .regular))
                        Text(alert.time.formatted(date: .omitted, time: .shortened)).font(.caption).foregroundStyle(theme.textSecondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func icon(_ severity: String) -> String {
        switch severity {
        case "critical": "exclamationmark.octagon.fill"
        case "warning": "exclamationmark.triangle"
        default: "info.circle"
        }
    }
}

/// The lanes of the Galileo Dashboard (`MAPP-OVR-020`), from what the Master has said since connecting.
struct ActivityCard: View {
    private static let lanes = [("session", "Session"), ("mount", "Mount"), ("flip", "Flip"), ("guiding", "Guiding"), ("solve", "Solve"), ("focus", "Focus"), ("capture", "Capture")]
    private static let span: TimeInterval = 30 * 60

    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        Card("Activity, last 30 minutes") {
            let state = model.workingPier.flatMap { model.snapshot.live.piers[$0] }
            ForEach(Self.lanes, id: \.0) { lane, title in
                let segments = state?.lanes[lane] ?? []
                HStack(spacing: 8) {
                    Text(title).font(.caption).frame(width: 58, alignment: .leading)
                    bar(segments)
                    Text(segments.last?.state ?? "—").font(.caption.weight(.semibold)).frame(width: 96, alignment: .trailing).lineLimit(1)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(title): \(segments.last?.state ?? "unknown")")
            }
        }
    }

    private func bar(_ segments: [LaneSegment]) -> some View {
        let now = model.now
        let accent = theme.accent
        let border = theme.border
        return Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(border.opacity(0.35)))
            for (index, segment) in segments.enumerated() {
                let end = index + 1 < segments.count ? segments[index + 1].start : now
                let from = max(0, 1 - now.timeIntervalSince(segment.start) / Self.span)
                let to = max(0, 1 - now.timeIntervalSince(end) / Self.span)
                guard to > from || index == segments.count - 1 else { continue }
                let idle = ["Idle", "Stopped", "Parked", "Done"].contains(segment.state)
                let rect = CGRect(x: min(from * size.width, size.width - 2), y: 0, width: max(2, (to - from) * size.width), height: size.height)
                context.fill(Path(rect), with: .color(accent.opacity(idle ? 0.25 : 0.85)))
            }
        }
        .frame(height: 16)
        .clipShape(RoundedRectangle(cornerRadius: 3))
    }
}
