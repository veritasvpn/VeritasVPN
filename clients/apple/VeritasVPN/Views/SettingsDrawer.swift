import SwiftUI
import VeritasCore

/// Android's settings drawer: Account & tools, Connection, Session.
@MainActor
struct SettingsDrawer: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let open: (Route) -> Void

    @State private var confirmThisDevice = false
    @State private var confirmAllDevices = false
    @State private var signOutError: String?
    @State private var working = false

    var body: some View {
        PremiumBackdrop {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    HStack {
                        Text(L("Settings"))
                            .font(VFont.headlineMedium)
                            .foregroundStyle(Palette.paper)
                            .accessibilityAddTraits(.isHeader)
                        Spacer()
                        GlassIconButton(systemImage: "xmark", accessibilityLabel: "Close settings") { dismiss() }
                    }
                    SettingsSection(title: "Account & tools") {
                        DrawerRow(icon: "person.crop.circle", label: "Account") { open(.account) }
                        RowDivider()
                        DrawerRow(icon: "map", label: "Network map") { open(.networkMap) }
                        RowDivider()
                        DrawerRow(icon: "questionmark.circle", label: "Help", note: "Support and diagnostics") { open(.help) }
                    }
                    SettingsSection(title: "Connection") {
                        DrawerRow(icon: "eye.slash", label: "Stealth", note: "Auto · UDP only · Stealth always") { open(.stealth) }
                        RowDivider()
                        DrawerRow(icon: "shield.lefthalf.filled", label: "Veritas Shield", note: "Ads · malicious · adult") { open(.shield) }
                        RowDivider()
                        DrawerRow(icon: "arrow.triangle.branch", label: "Split tunnel", note: "Exclude LAN") { open(.splitTunnel) }
                    }
                    SettingsSection(title: "Session") {
                        DrawerRow(icon: "rectangle.portrait.and.arrow.right", label: "Sign out from all devices", tint: Palette.error) {
                            confirmAllDevices = true
                        }
                        RowDivider()
                        DrawerRow(icon: "iphone.and.arrow.forward", label: "Sign out from this device", tint: Palette.warning) {
                            confirmThisDevice = true
                        }
                    }
                    if let signOutError {
                        StatusBanner(text: signOutError) { self.signOutError = nil }
                    }
                    Text(L("Version %@", Platform.appVersion))
                        .font(VFont.bodySmall)
                        .foregroundStyle(Palette.paperDim)
                        .frame(maxWidth: .infinity)
                }
                .padding(20)
            }
        }
        .alert(L("Sign out from this device?"), isPresented: $confirmThisDevice) {
            Button(L("Sign out from this device"), role: .destructive) {
                dismiss()
                model.signOutThisDevice()
            }
            Button(L("Cancel"), role: .cancel) {}
        } message: {
            Text(L("Signing out will disconnect your VPN. Continue?"))
        }
        .alert(L("Sign out from all devices?"), isPresented: $confirmAllDevices) {
            Button(L("Sign out from all devices"), role: .destructive) {
                working = true
                Task {
                    let error = await model.signOutAllDevices()
                    working = false
                    if let error { signOutError = error } else { dismiss() }
                }
            }
            Button(L("Cancel"), role: .cancel) {}
        } message: {
            Text(L("This revokes all sessions on every device, disconnects VPN on this device, and signs you out locally."))
        }
        .disabled(working)
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 560)
        #endif
    }
}

@MainActor
struct DrawerRow: View {
    let icon: String
    let label: String
    var note: String?
    var tint: Color = Palette.cyanHover
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(tint)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L(label)).font(VFont.body(15, .semibold)).foregroundStyle(Palette.paper)
                    if let note {
                        Text(L(note)).font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.paperDim)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

@MainActor
struct RowDivider: View {
    var body: some View {
        Rectangle().fill(Palette.line).frame(height: 1).padding(.leading, 56)
    }
}

/// Toggle row used in Shield and Split tunnel.
@MainActor
struct ToggleRow: View {
    let title: String
    let detail: String
    @Binding var isOn: Bool
    var enabled = true

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 3) {
                Text(L(title)).font(VFont.body(15, .semibold)).foregroundStyle(Palette.paper)
                Text(L(detail)).font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .toggleStyle(.switch)
        .tint(Palette.cyan)
        .disabled(!enabled)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }
}

/// Shared layout for the settings sub-screens.
@MainActor
struct SettingsScreen<Content: View>: View {
    let eyebrow: String
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        PremiumBackdrop {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    ScreenHeader(eyebrow: eyebrow, title: title)
                    content
                }
                .padding(20)
                .frame(maxWidth: 620, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("")
        .inlineNavigationTitle()
    }
}

@MainActor
struct ShieldSettingsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        SettingsScreen(eyebrow: "ADVANCED VPN PROTECTION", title: "Veritas Shield") {
            Text(L("Blocks known domains in DNS while you are connected. Ads or trackers loaded from the site itself can still run."))
                .font(VFont.bodyMedium).foregroundStyle(Palette.paperMuted)
            if model.billingReady && !model.isPremium {
                StatusBanner(text: "A free account cannot connect, so Shield is not filtering. Premium connects with malicious sites and trackers on. Ads and adult sites stay off until you turn them on.", tint: Palette.cyan)
            }
            SettingsSection(title: "BLOCKED") {
                ToggleRow(title: "Block malicious sites", detail: "Malware, phishing, scam, and cryptomining domains.", isOn: $model.shield.blockMalicious)
                RowDivider()
                ToggleRow(title: "Block trackers", detail: "Stop known tracker domains from resolving. A tracker served from the site itself can still run.", isOn: $model.shield.blockTrackers)
                RowDivider()
                ToggleRow(title: "Block ads", detail: "Stop known ad domains from resolving. A page can still show an ad that is not a separate domain.", isOn: $model.shield.blockAds)
                RowDivider()
                ToggleRow(title: "Block adult sites", detail: "Known adult domains. Shield does not scan page content.", isOn: $model.shield.blockAdult)
            }
            Text(L(model.connected ? "Changes apply to this connection immediately." : "Saved on this device. Applied the next time you connect."))
                .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
            if let blocks = model.shieldBlocks {
                SettingsSection(title: "CONNECTION") {
                    ForEach(ShieldRow.rows(policy: model.shield, blocks: blocks)) { row in
                        HStack {
                            Text(L(row.group.label)).foregroundStyle(Palette.paperMuted)
                            Spacer()
                            Text(L("%lld today · %lld this week", row.today, row.week)).foregroundStyle(Palette.paper)
                        }
                        .font(VFont.body(13))
                        .padding(.horizontal, 16).padding(.vertical, 10)
                    }
                }
                Text(L("Counts are for this account, in UTC. A category that is off is not shown as blocked."))
                    .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
            }
        }
    }
}

@MainActor
struct StealthSettingsView: View {
    @EnvironmentObject private var model: AppModel

    private func detail(_ mode: StealthMode) -> String {
        switch mode {
        case .auto: return "Uses UDP. VeritasVPN never switches transport on its own."
        case .udpOnly: return "Plain WireGuard over UDP. Never fall back to Stealth."
        case .stealthAlways: return "Start with WireGuard inside TLS. Use this on networks that block UDP. Coming soon on iPhone, iPad and Mac."
        }
    }

    private func title(_ mode: StealthMode) -> String {
        switch mode {
        case .auto: return "Auto"
        case .udpOnly: return "UDP only"
        case .stealthAlways: return "Stealth always"
        }
    }

    var body: some View {
        SettingsScreen(eyebrow: "CONNECTION", title: "Stealth") {
            Text(L("Choose how VeritasVPN carries WireGuard. Auto is the default. Reconnect from Home to apply a change."))
                .font(VFont.bodyMedium).foregroundStyle(Palette.paperMuted)
            SettingsSection(title: "TRANSPORT") {
                ForEach(Array(StealthMode.allCases.enumerated()), id: \.element) { index, mode in
                    if index > 0 { RowDivider() }
                    Button { model.stealthMode = mode } label: {
                        HStack(spacing: 14) {
                            Image(systemName: model.stealthMode == mode ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(model.stealthMode == mode ? Palette.cyan : Palette.paperDim)
                                .frame(width: 26)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(L(title(mode))).font(VFont.body(15, .semibold)).foregroundStyle(Palette.paper)
                                Text(L(detail(mode))).font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 16).padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(model.stealthMode == mode ? .isSelected : [])
                }
            }
            if model.stealthMode == .stealthAlways {
                StatusBanner(text: Messages.stealthComingSoon, tint: Palette.warning, actionTitle: "Use Auto") {
                    model.stealthMode = .auto
                }
            }
        }
    }
}

@MainActor
struct SplitTunnelView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        SettingsScreen(eyebrow: "CONNECTION", title: "Split tunnel") {
            SettingsSection(title: "ROUTING") {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("Route your internet through VeritasVPN")).font(VFont.body(15, .semibold)).foregroundStyle(Palette.paper)
                    Text(L("Your browser, apps, and DNS use the encrypted WireGuard tunnel unless you choose a local-network exception below."))
                        .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(16)
                RowDivider()
                ToggleRow(
                    title: "Allow local network access",
                    detail: "Keep devices on your home, office, or hotel network reachable. Internet traffic still uses VeritasVPN.",
                    isOn: $model.excludeLAN
                )
            }
            Text(L(model.connected ? "Reconnect from Home to apply a change." : "Saved on this device. Applied the next time you connect."))
                .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
            SettingsSection(title: "PER-APP BYPASS") {
                Text(L("Per-app bypass isn't available on iPhone, iPad and Mac. Apple only allows it for managed devices."))
                    .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
                    .padding(16)
            }
        }
    }
}
