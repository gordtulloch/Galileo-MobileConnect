import GalileoCore
import SwiftUI

/// Running saved sessions, and the job queue (`MAPP-SES-010`-`030`, `MAPP-SCH-010`). Authoring is P2.
struct SessionsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var sessions: [SavedSession] = []
    @State private var askedForSaved = false
    @State private var runNote: String?
    @State private var jobs: [QueuedJob] = []
    @State private var queueNote: String?
    @State private var dragging: String?
    @State private var dragOffset: CGFloat = 0

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

    // MARK: Saved sessions

    /// A saved session of the working Pier, as the Master lists it (never a template).
    private struct SavedSession: Identifiable {
        let name: String
        let target: String
        var id: String { name }
    }

    private func loadSaved() async {
        guard model.snapshot.isConnected else { return }
        if let list = await model.read("session.saved")?["sessions"]?.arrayValue {
            sessions = list.compactMap { item in
                item["name"]?.stringValue.map { SavedSession(name: $0, target: item["target"]?.stringValue ?? "") }
            }
        }
        askedForSaved = true
    }

    /// Starts a saved session now, as Run on the desktop does. The Master is asked first whether it can: a job already
    /// running is "Jobs already running", and jobs still to come are put to the user as "Pre-empt any upcoming jobs?"
    /// (`MAPP-SES-015`).
    private func start(_ name: String) async {
        guard let check = await model.read("session.start_check", args: ["name": .string(name)]) else {
            runNote = "This Master can't run saved sessions yet: update Galileo on the Master."
            return
        }
        let reason = check["reason"]?.stringValue ?? ""
        if !reason.isEmpty {
            runNote = reason
        } else if !(check["running"]?.stringValue ?? "").isEmpty {
            runNote = "Jobs already running"
        } else if !(check["upcoming"]?.arrayValue ?? []).isEmpty {
            runNote = nil
            model.preemptFor = name
        } else {
            runNote = await model.perform("session.run_saved", args: ["name": .string(name)])
        }
    }

    private var saved: some View {
        Card("Saved sessions") {
            if sessions.isEmpty {
                Note(askedForSaved
                     ? "No saved sessions on this Pier. Save one in the desktop app's Sessions screen."
                     : "Loading the saved sessions…")
            }
            ForEach(sessions) { session in
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(session.name).fontWeight(.semibold)
                        if !session.target.isEmpty { Note(session.target) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Button("Run") { Task { await start(session.name) } }
                        .buttonStyle(ActionStyle(kind: .prominent))
                        .disabled(!model.canOperate)
                        .frame(width: 96)
                        .accessibilityIdentifier("session.run.\(session.name)")
                }
            }
            if let runNote { Note(runNote) }
            if let record = model.record(of: "session.run_saved") { OutcomeNote(record: record) }
        }
        .task(id: model.workingPier) {
            while !Task.isCancelled {
                await loadSaved()
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }

    // MARK: Queued jobs

    /// One entry of the Pier's queue: the job running (queued or run by hand) first, then the rest in order.
    private struct QueuedJob: Identifiable {
        let name: String
        let running: Bool
        let paused: Bool
        let queued: Bool
        let detail: String
        var id: String { name }
    }

    private func loadJobs() async {
        guard model.snapshot.isConnected, dragging == nil,
              let list = await model.read("scheduler.jobs")?["jobs"]?.arrayValue else { return }
        jobs = list.map { job in
            QueuedJob(
                name: job["name"]?.stringValue ?? "Job", running: job["running"]?.boolValue == true,
                paused: job["paused"]?.boolValue == true, queued: job["queued"]?.boolValue != false, detail: detail(job))
        }
    }

    private func move(_ job: QueuedJob, by steps: Int) {
        guard let index = jobs.firstIndex(where: { $0.id == job.id }) else { return }
        // The running entries are first and fixed; the others move among themselves.
        let fixed = jobs.filter { $0.running || !$0.queued }.count
        let target = min(max(index + steps, fixed), jobs.count - 1)
        guard target != index else { return }
        let position = target - jobs.filter { !$0.queued }.count
        jobs.insert(jobs.remove(at: index), at: target)
        Task {
            queueNote = await model.perform("scheduler.reorder", args: ["name": .string(job.name), "position": .number(Double(position))])
            await loadJobs()
        }
    }

    private func control(_ operation: String) {
        Task {
            queueNote = await model.perform(operation)
            await loadJobs()
        }
    }

    private func remove(_ job: QueuedJob) {
        Task {
            queueNote = await model.perform("scheduler.remove", args: ["name": .string(job.name)])
            await loadJobs()
        }
    }

    /// One queue entry. The running one has Pause (Resume while paused) and Stop. Any other can be dragged (press and
    /// hold, then drag) to a new place, and has an X to take it off the queue (`MAPP-SCH-040`, `MAPP-SCH-050`).
    private func tile(_ job: QueuedJob, canMove: Bool) -> some View {
        let lifted = dragging == job.id
        return HStack(spacing: 8) {
            if canMove {
                Image(systemName: "line.3.horizontal").foregroundStyle(theme.textSecondary)
                    .accessibilityLabel("Drag to reorder")
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(job.name).font(.subheadline.weight(.semibold))
                Text(job.detail).font(.footnote).foregroundStyle(theme.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if job.running {
                if model.canOperate {
                    Button(job.paused ? "Resume" : "Pause") { control(job.paused ? "scheduler.resume" : "scheduler.pause") }
                        .buttonStyle(ActionStyle())
                        .frame(width: 96)
                        .accessibilityIdentifier(job.paused ? "job.resume" : "job.pause")
                    Button("Stop") { control("scheduler.stop") }
                        .buttonStyle(ActionStyle(kind: .destructive))
                        .frame(width: 80)
                        .accessibilityIdentifier("job.stop")
                }
            } else if job.queued {
                Button { remove(job) } label: { Image(systemName: "xmark") }
                    .buttonStyle(ActionStyle())
                    .disabled(!model.canOperate)
                    .frame(width: 48)
                    .accessibilityLabel("Remove \(job.name)")
                    .accessibilityIdentifier("job.remove.\(job.name)")
            }
        }
        .padding(.vertical, 6)
        .background(lifted ? theme.raised : theme.surface, in: RoundedRectangle(cornerRadius: 10))
        .offset(y: lifted ? dragOffset : 0)
        .zIndex(lifted ? 1 : 0)
        .gesture(
            canMove
                ? LongPressGesture(minimumDuration: 0.4).sequenced(before: DragGesture())
                    .onChanged { value in
                        if case .second(true, let drag?) = value {
                            dragging = job.id
                            dragOffset = drag.translation.height
                        }
                    }
                    .onEnded { value in
                        if case .second(true, let drag?) = value {
                            move(job, by: Int((drag.translation.height / 64).rounded()))
                        }
                        dragging = nil
                        dragOffset = 0
                    }
                : nil)
        .accessibilityIdentifier("job.\(job.name)")
    }

    private var queue: some View {
        Card("Queued jobs") {
            if jobs.isEmpty { Note("Nothing queued on this Pier.") }
            let fixed = jobs.filter { $0.running || !$0.queued }.count
            ForEach(jobs) { job in
                tile(job, canMove: !job.running && job.queued && model.canOperate && jobs.count - fixed > 1)
            }
            if let queueNote { Note(queueNote) }
        }
        .task(id: model.workingPier) {
            while !Task.isCancelled {
                await loadJobs()
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }

    private func detail(_ job: JSONValue) -> String {
        var parts: [String] = []
        if job["paused"]?.boolValue == true {
            parts.append("Paused")
        } else if job["running"]?.boolValue == true {
            parts.append("Running")
        } else if let state = job["run_state"]?.stringValue {
            parts.append(state.capitalized)
        }
        if let done = job["frames_captured"]?.intValue, let total = job["total_required"]?.intValue, total > 0 {
            parts.append("\(done) of \(total) frames")
        }
        if let start = job["start"]?.stringValue {
            parts.append("starts \(String(start.replacingOccurrences(of: "T", with: " ").prefix(16))) UTC")
        }
        return parts.joined(separator: ", ")
    }
}
