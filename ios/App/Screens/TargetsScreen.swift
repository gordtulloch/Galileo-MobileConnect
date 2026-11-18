import GalileoCore
import SwiftUI

/// The saved targets the user keeps on the Master (Planning > Targets on the desktop), shown as tiles. Slew To and
/// Goto and Centre are the usual operations, so they need the lease; Remove needs an Operator, not the lease.
struct TargetsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var list: SavedTargets?
    @State private var failure: String?
    @State private var message: String?
    @State private var loading = false

    var body: some View {
        ScreenScroll("Targets") {
            if failure != nil { Note("No Targets found.").accessibilityIdentifier("targets.empty") }
            if let message { Note(message).accessibilityIdentifier("targets.message") }
            if let list {
                if list.targets.isEmpty {
                    Note("No Targets found.")
                        .accessibilityIdentifier("targets.empty")
                }
                ForEach(list.targets) { target in tile(target) }
            } else if loading {
                ProgressView().tint(theme.text).frame(maxWidth: .infinity)
            }
        }
        .refreshable { await load() }
        .task { await load() }
        .accessibilityIdentifier("targets.screen")
    }

    private func tile(_ target: SavedTarget) -> some View {
        Card {
            // The object's picture runs the full height of the tile, on the right.
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(target.name) · \(target.type)").font(.headline).accessibilityIdentifier("target.name")
                    Text(target.summary).font(.subheadline).foregroundStyle(theme.textSecondary)
                    Text(String(format: "RA %.3f°  Dec %.3f°", target.raDeg, target.decDeg))
                        .font(.footnote).foregroundStyle(theme.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                TargetThumbnail(name: target.name)
            }
            HStack(spacing: 8) {
                OperationButton(
                    title: "Slew To", operation: "mount.slew", icon: "scope",
                    args: ["ra_deg": .number(target.raDeg), "dec_deg": .number(target.decDeg)])
                OperationButton(
                    title: "Goto and Centre", operation: "solve.centre", icon: "viewfinder",
                    args: [
                        "ra_deg": .number(target.raDeg), "dec_deg": .number(target.decDeg), "name": .string(target.name),
                    ])
            }
            Button {
                Task { await remove(target) }
            } label: {
                Label("Remove", systemImage: "trash")
            }
            .buttonStyle(ActionStyle(kind: .destructive))
            .disabled(!model.canEditTargets)
            .accessibilityIdentifier("target.remove")
            if !model.canEditTargets { Note("Only an Operator can remove a saved target.") }
        }
        .contextMenu {
            if model.canEditTargets {
                Button("Remove", role: .destructive) { Task { await remove(target) } }
            }
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            list = try await model.loadSavedTargets()
            failure = nil
        } catch {
            failure = "Couldn't read the saved targets from the Master."
        }
    }

    private func remove(_ target: SavedTarget) async {
        message = await model.removeSavedTarget(target.name)
        await load()
    }
}

/// The survey picture of a saved target, square, loaded from the Master; nothing is drawn where there is none.
private struct TargetThumbnail: View {
    let name: String
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var image: UIImage?

    var body: some View {
        // The text beside it sets the tile's height; the picture fills that, never makes it taller.
        Color.clear
            .frame(width: 110)
            .frame(minHeight: 90)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill().accessibilityHidden(true)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
        .task(id: name) {
            if let data = await model.loadTargetThumbnail(name) { image = UIImage(data: data) }
        }
    }
}
