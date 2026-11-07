import GalileoCore
import SwiftUI

@main
struct GalileoMobileConnectApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ThemeRoot(choice: model.theme) {
                RootView()
            }
            .environment(model)
            .task { model.launched() }
            .onChange(of: phase) { _, new in model.sceneChanged(to: new) }
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        switch model.route {
        case .welcome: WelcomeView()
        case .pairing: PairingView()
        case .connected:
            if let stop = model.snapshot.stop {
                SessionStoppedView(stop: stop)
            } else {
                ShellView()
            }
        }
    }
}
