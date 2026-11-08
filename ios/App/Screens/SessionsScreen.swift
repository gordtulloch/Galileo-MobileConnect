import GalileoCore
import SwiftUI

/// Running saved sessions, and the job queue (`MAPP-SES-010`-`030`, `MAPP-SCH-010`). Authoring is P2.
struct SessionsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        ScreenScroll("Sessions") {
            ReadOnlyHint()
            Columns {
                running
                saved
            } right: {
                queue
            }
        }
    }

    private var running: some View {
        Card("Running") {
            LiveRow(label: "Session", key: "session.name")
            LiveRow(label: "State", key: "session.state")
            LiveRow(label: "Now", key: "session.block")
            position
            if let message = model.value("session.message")?.value.stringValue { Note(message) }
            ProgressRow(key: "session.progress", label: "Session progress")
            if model.value("session.frames_total")?.value.doubleValue != nil {
                LiveRow(label: "Frames done", key: "session.frames_done")
                LiveRow(label: "Of", key: "session.frames_total")
            }
            if model.value("session.stop_reason")?.value.stringValue != nil {
                LiveRow(label: "Stopped because", key: "session.stop_reason")
            }
            HStack(spacing: 8) {
                OperationButton(title: "Pause", operation: "session.pause", icon: "pause")
                OperationButton(title: "Resume", operation: "session.resume", icon: "play")
            }
            HStack(spacing: 8) {
                OperationButton(title: "Skip step", operation: "session.skip", icon: "forward.end")
                OperationButton(title: "Stop", operation: "session.stop", icon: "stop", kind: .destructive)
            }
        }
    }

    @ViewBuilder private var position: some View {
        if let index = model.value("session.block_index")?.value.intValue,
            let total = model.value("session.block_total")?.value.intValue, total > 0
        {
            HStack {
                Text("Block").foregroundStyle(theme.textSecondary)
                Spacer()
                Text("\(index) of \(total)").fontWeight(.semibold)
            }
            .accessibilityElement(children: .combine)
        }
    }

    private func runArguments(_ template: String) -> [String: JSONValue] {
        var args: [String: JSONValue] = ["template": .string(template)]
        if let target = model.sessionTarget {
            args["target_name"] = .string(target.name)
            args["ra_deg"] = .number(target.raDeg)
            args["dec_deg"] = .number(target.decDeg)
        }
        return args
    }

    private var saved: some View {
        Card("Saved sessions") {
            let names = model.value("session.templates")?.value.arrayValue?.compactMap(\.stringValue) ?? []
            if names.isEmpty { Note("The Master reports no saved sessions.") }
            if let target = model.sessionTarget {
                HStack {
                    Text("Target from the sky: \(target.name)").fontWeight(.semibold).accessibilityIdentifier("session.target")
                    Spacer()
                    Button("Clear") { model.sessionTarget = nil }
                        .buttonStyle(ActionStyle())
                        .fixedSize()
                        .accessibilityIdentifier("session.target.clear")
                }
            }
            ForEach(names, id: \.self) { name in
                if model.canOperate {
                    OperationButton(title: "Run \(name)", operation: "session.run", icon: "play.fill", args: runArguments(name))
                } else {
                    Text(name)
                }
            }
        }
    }

    private var queue: some View {
        Card("Job queue") {
            let jobs = model.value("scheduler.jobs")?.value.arrayValue ?? []
            if jobs.isEmpty { Note("Nothing queued on this Pier.") }
            if let next = model.value("scheduler.next")?.value.stringValue {
                HStack {
                    Text("Next").foregroundStyle(theme.textSecondary)
                    Spacer()
                    Text(next).fontWeight(.semibold)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("schedule.next")
            }
            ForEach(jobs.indices, id: \.self) { index in
                let job = jobs[index]
                VStack(alignment: .leading, spacing: 2) {
                    Text(job["name"]?.stringValue ?? "Job").font(.subheadline.weight(.semibold))
                    Text(detail(job)).font(.footnote).foregroundStyle(theme.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func detail(_ job: JSONValue) -> String {
        var parts: [String] = []
        if let state = job["run_state"]?.stringValue { parts.append(state.capitalized) }
        if let done = job["frames_captured"]?.intValue, let total = job["total_required"]?.intValue, total > 0 {
            parts.append("\(done) of \(total) frames")
        }
        return parts.joined(separator: ", ")
    }
}
