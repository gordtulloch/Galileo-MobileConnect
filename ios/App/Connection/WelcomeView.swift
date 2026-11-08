import GalileoCore
import SwiftUI

/// First screen: find a Master, type its address, reconnect to a paired one, or try the demo (I-CONN).
struct WelcomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @AppStorage("localNetworkExplained") private var explained = false
    @State private var browsing = false
    @State private var found: [DiscoveredMaster] = []
    @State private var denied = false
    @State private var searchProblem: String?
    @State private var manual = ""
    @State private var manualProblem: String?
    @State private var resolving: String?
    @State private var searchIsSlow = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Layout.spacing) {
                Text("Galileo").font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                Text("Watch and operate your Galileo observatory.").foregroundStyle(theme.textSecondary)

                if let message = model.welcomeMessage { Banner(icon: "info.circle", text: message) }

                if !model.paired.isEmpty { pairedCard }
                discoveryCard
                manualCard
                Card("No observatory to hand?") {
                    Button("Try the demo") { model.startDemo() }
                        .buttonStyle(ActionStyle())
                        .accessibilityIdentifier("demo")
                    Note("A simulated observatory. Nothing here reaches any real equipment.")
                }
            }
            .padding(24)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(theme.background)
        .task(id: browsing) { if browsing { await browse() } }
        .task(id: browsing) {
            // A spinner alone looks broken: Bonjour can take a while on some networks.
            guard browsing else { return }
            try? await Task.sleep(for: .seconds(10))
            if !Task.isCancelled { searchIsSlow = true }
        }
        .onAppear {
            model.refreshPaired()
            if explained { browsing = true }
        }
    }

    private var pairedCard: some View {
        Card("Paired") {
            ForEach(model.paired) { master in
                Button {
                    model.connect(master)
                } label: {
                    Label("\(master.name) — \(master.address.host)", systemImage: "antenna.radiowaves.left.and.right")
                }
                .buttonStyle(ActionStyle(kind: .prominent))
            }
        }
    }

    @ViewBuilder private var discoveryCard: some View {
        Card("On this network") {
            if !explained {
                // Our words first, so that the system prompt that follows makes sense (MAPP-PLT-070).
                Text("Galileo needs to find your observatory computer on your Wi-Fi network. iOS will ask for permission to use the local network.")
                Button("Find my observatory") {
                    explained = true
                    browsing = true
                }
                .buttonStyle(ActionStyle(kind: .prominent))
            } else if denied {
                Text("Local network access is off, so this app can't find or reach your observatory.")
                Note("Turn it on in Settings › Privacy & Security › Local Network › Galileo. Discovery, pairing and control all need it; only the demo works without.")
            } else if found.isEmpty {
                HStack(spacing: 10) {
                    ProgressView().tint(theme.text)
                    Text("Looking for Galileo…")
                }
                .frame(minHeight: Layout.minimumTarget)
                if searchIsSlow {
                    Note("Nothing yet. Check that Galileo is running and that this device is on the same network. It can take up to a minute, or you can enter the address below.")
                }
                if let searchProblem { Note(searchProblem) }
            } else {
                ForEach(found) { master in
                    Button {
                        choose(master)
                    } label: {
                        HStack {
                            Label(master.name, systemImage: "desktopcomputer")
                            if resolving == master.id {
                                ProgressView().tint(theme.text)
                            }
                        }
                    }
                    .buttonStyle(ActionStyle(kind: .prominent))
                    .disabled(resolving != nil)
                }
            }
        }
    }

    private var manualCard: some View {
        Card("Enter its address") {
            TextField("", text: $manual, prompt: Text("Computer name or IP address").foregroundStyle(theme.textSecondary))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .foregroundStyle(theme.text)
                .padding(.horizontal, 12)
                .frame(minHeight: Layout.minimumTarget)
                .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
                .accessibilityLabel("Address")
                .accessibilityIdentifier("address")
                .onSubmit(connectManually)
            if let manualProblem { Note(manualProblem) }
            Button("Connect", action: connectManually)
                .buttonStyle(ActionStyle())
                .accessibilityIdentifier("connect")
        }
    }

    private func connectManually() {
        switch MasterAddress.parse(manual) {
        case .success(let address):
            manualProblem = nil
            model.startPairing(address: address, name: address.host)
        case .failure(let problem):
            manualProblem = problem.message
        }
    }

    private func choose(_ master: DiscoveredMaster) {
        resolving = master.id
        Task {
            defer { resolving = nil }
            do {
                model.startPairing(address: try await BonjourDiscovery.resolve(master), name: master.name)
            } catch let error as LinkError {
                searchProblem = error.message
            } catch {
                searchProblem = "Could not reach \(master.name)."
            }
        }
    }

    private func browse() async {
        for await event in BonjourDiscovery().events() {
            switch event {
            case .results(let masters):
                found = masters
                denied = false
                searchProblem = nil
            case .permissionDenied:
                denied = true
            case .failed(let why):
                searchProblem = why
            }
        }
    }
}

/// The code and the state of the request. Nothing else is reachable until it's approved (`MAPP-CON-030`).
struct PairingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var started = Date()

    var body: some View {
        VStack(spacing: 20) {
            Text("Pair with \(model.pairingMaster)").font(.title.bold()).multilineTextAlignment(.center)
            switch model.pairing {
            case .idle:
                ProgressView().tint(theme.text)
                Text("Contacting the Master…")
            case .waiting(let code, let expiresIn, let fingerprint):
                Text("Approve this request on the Master. Check that it shows the same code.")
                    .multilineTextAlignment(.center)
                Text(spaced(code))
                    .font(.system(size: 56, weight: .bold, design: .monospaced))
                    .accessibilityLabel("Code \(code.map(String.init).joined(separator: " "))")
                    .accessibilityIdentifier("pairing.code")
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let left = max(0, Int(expiresIn - context.date.timeIntervalSince(started)))
                    Text("Waiting for approval. \(left / 60):\(String(format: "%02d", left % 60)) left.")
                        .foregroundStyle(theme.textSecondary)
                }
                Text("Certificate \(fingerprint.prefix(16))…")
                    .font(.footnote.monospaced())
                    .foregroundStyle(theme.textSecondary)
                Button("Cancel") { model.cancelPairing() }.buttonStyle(ActionStyle()).frame(maxWidth: 320)
            case .paired:
                ProgressView().tint(theme.text)
                Text("Approved.")
            case .refused:
                outcome("The request was refused on the Master.")
            case .timedOut:
                outcome("Nobody approved the request in time.")
            case .failed(let why):
                outcome(why)
            }
        }
        .padding(24)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.background)
    }

    private func spaced(_ code: String) -> String {
        "\(code.prefix(3)) \(code.suffix(3))"
    }

    @ViewBuilder private func outcome(_ text: String) -> some View {
        Text(text).multilineTextAlignment(.center)
        Button("Back") { model.cancelPairing() }.buttonStyle(ActionStyle(kind: .prominent)).frame(maxWidth: 320)
    }
}

/// A session that ended and can't simply be retried.
struct SessionStoppedView: View {
    let stop: SessionStop
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "exclamationmark.shield").font(.system(size: 48)).accessibilityHidden(true)
            switch stop {
            case .unpaired(let message):
                Text("No longer paired").font(.title.bold())
                Text(message).multilineTextAlignment(.center)
                back
            case .identityChanged:
                Text("The Master's identity has changed").font(.title.bold()).multilineTextAlignment(.center)
                Text(LinkError.identityChanged.message).multilineTextAlignment(.center)
                Button("Pair again") { model.pairAgainAfterIdentityChange() }
                    .buttonStyle(ActionStyle(kind: .destructive)).frame(maxWidth: 320)
                back
            case .refused(let message, let updateApp):
                Text(updateApp == true ? "Update this app" : updateApp == false ? "Update Galileo" : "Versions don't match")
                    .font(.title.bold())
                Text(message).multilineTextAlignment(.center)
                if updateApp == true { Note("Get the latest version from the App Store or TestFlight.") }
                back
            case .configurationGrant:
                Text("This pairing has too much access").font(.title.bold()).multilineTextAlignment(.center)
                Text("The Master granted this device permission to change its configuration. This app must never hold that, so it won't connect. Remove the pairing on the Master and pair again with control or view only.")
                    .multilineTextAlignment(.center)
                back
            }
        }
        .padding(24)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.background)
    }

    private var back: some View {
        Button("Back") { model.backToWelcome() }.buttonStyle(ActionStyle()).frame(maxWidth: 320)
    }
}
