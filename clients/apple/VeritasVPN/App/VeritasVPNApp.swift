import SwiftUI

@main
struct VeritasVPNApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.store)
                .preferredColorScheme(.dark)
                .tint(Palette.cyan)
                #if os(macOS)
                .frame(minWidth: 420, idealWidth: 460, minHeight: 640, idealHeight: 760)
                #endif
        }
        #if os(macOS)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
        #endif
    }
}

@MainActor
struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var path: [Route] = []

    var body: some View {
        Group {
            switch model.screen {
            case .launching:
                PremiumBackdrop {
                    Image("VeritasLogo")
                        .resizable().scaledToFit()
                        .frame(width: 96, height: 96)
                        .accessibilityLabel(L("VeritasVPN shield"))
                }
            case .auth:
                AuthView()
            case .home:
                NavigationStack(path: $path) {
                    DashboardView(path: $path)
                        .navigationDestination(for: Route.self) { route in
                            destination(route)
                        }
                        .navigationTitle("")
                        .inlineNavigationTitle()
                        #if os(iOS)
                        .toolbar(.hidden, for: .navigationBar)
                        #endif
                }
            }
        }
        .task { await model.bootstrap() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { Task { await model.becameActive() } }
        }
        .onChange(of: model.screen) { screen in
            if screen != .home { path = [] }
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .account: AccountView()
        case .networkMap: NetworkMapView()
        case .help: HelpView()
        case .stealth: StealthSettingsView()
        case .shield: ShieldSettingsView()
        case .splitTunnel: SplitTunnelView()
        case .privacy: PrivacyView()
        case .diagnostics: DiagnosticsView()
        }
    }
}
