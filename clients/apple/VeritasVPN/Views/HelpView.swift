import SwiftUI
import VeritasCore

/// Help and Support, a port of Android's HelpScreen.
@MainActor
struct HelpView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openURL) private var openURL
    @AppStorage("veritas.shareDiagnostics") private var shareDiagnostics = false
    @State private var actionError: String?

    var body: some View {
        SettingsScreen(eyebrow: "HELP", title: "Help and Support") {
            SettingsSection(title: "Contact") {
                DrawerRow(icon: "envelope", label: "Contact us", note: SupportLinks.contactEmail) { contact() }
                RowDivider()
                ToggleRow(
                    title: "Share diagnostics",
                    detail: "Off by default. When on, Contact us includes a redacted report. Keys and tokens stay out.",
                    isOn: $shareDiagnostics
                )
            }
            Text(L("Contact opens your email app. A redacted diagnostic report is included only when sharing is on."))
                .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
            SettingsSection(title: "Guides") {
                DrawerRow(icon: "book", label: "How to use the VPN", note: "Open the support guides") { open(SupportLinks.support, failure: "Could not open the support page.") }
                RowDivider()
                DrawerRow(icon: "wifi.exclamationmark", label: "I can't get connected", note: "Connection and Stealth") { open(SupportLinks.supportConnect, failure: "Could not open the support page.") }
                RowDivider()
                DrawerRow(icon: "stethoscope", label: "I can connect but there is a problem", note: "Still protected, something else is wrong") { open(SupportLinks.support, failure: "Could not open the support page.") }
            }
            SettingsSection(title: "Diagnostics") {
                NavigationLink(value: Route.diagnostics) {
                    DrawerRowLabel(icon: "waveform.path.ecg", label: "Diagnostic information", note: "Connection, version, and handshake")
                }
                .buttonStyle(.plain)
            }
            Text(L("Diagnostic details stay on this device unless you choose to share them."))
                .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
            SettingsSection(title: "Legal") {
                NavigationLink(value: Route.privacy) {
                    DrawerRowLabel(icon: "hand.raised", label: "Privacy Policy", note: "Asunción, Paraguay")
                }
                .buttonStyle(.plain)
                RowDivider()
                DrawerRow(icon: "doc.text", label: "Terms of Service") { open(SupportLinks.terms, failure: "Could not open the terms of service.") }
            }
            AcknowledgementsCard()
            if let actionError {
                StatusBanner(text: actionError) { self.actionError = nil }
            }
            Text(L("Version %@", Platform.appVersion))
                .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
        }
    }

    private func open(_ url: URL, failure: String) {
        openURL(url) { accepted in
            if !accepted { actionError = failure }
        }
    }

    private func contact() {
        let report = shareDiagnostics ? DiagnosticsReport.make(model: model) : nil
        guard let url = SupportLinks.mailto(report: report) else {
            actionError = L("No email app found. Write to %@.", SupportLinks.contactEmail)
            return
        }
        openURL(url) { accepted in
            if !accepted { actionError = L("No email app found. Write to %@.", SupportLinks.contactEmail) }
        }
    }
}

/// Same look as DrawerRow, for NavigationLink labels.
@MainActor
struct DrawerRowLabel: View {
    let icon: String
    let label: String
    var note: String?

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Palette.cyanHover)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(L(label)).font(VFont.body(15, .semibold)).foregroundStyle(Palette.paper)
                if let note { Text(L(note)).font(VFont.bodySmall).foregroundStyle(Palette.paperDim) }
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
}

@MainActor
struct AcknowledgementsCard: View {
    private struct Component: Identifiable {
        let name: String
        let license: String
        var id: String { name }
    }

    private let components = [
        Component(name: "WireGuardKit (wireguard-apple)", license: "MIT License"),
        Component(name: "wireguard-go", license: "MIT License"),
        Component(name: "Inter", license: "SIL Open Font License 1.1"),
        Component(name: "Space Grotesk", license: "SIL Open Font License 1.1"),
    ]

    var body: some View {
        SettingsSection(title: "Acknowledgements") {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("VeritasVPN is licensed under the Business Source License 1.1. This build also includes the open-source components below."))
                    .font(VFont.bodySmall).foregroundStyle(Palette.paperMuted)
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(components) { component in
                    HStack {
                        Text(component.name).font(VFont.body(13, .semibold)).foregroundStyle(Palette.paper)
                        Spacer()
                        Text(L(component.license)).font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
                    }
                }
                Text(L("WireGuard is a registered trademark of Jason A. Donenfeld."))
                    .font(VFont.body(11)).foregroundStyle(Palette.paperDim)
            }
            .padding(16)
        }
    }
}

/// Redacted report for support. No keys, tokens, addresses or endpoints.
enum DiagnosticsReport {
    @MainActor
    static func make(model: AppModel) -> String {
        let phase = model.heroPhase.rawValue
        let handshake = model.stats.hasHandshake
            ? Formatters.handshakeAge(model.stats.lastHandshakeEpoch, now: Date())
            : "none"
        let lines = [
            "VeritasVPN \(Platform.appVersion) on \(Platform.osVersion)",
            "State: \(phase)",
            "Transport: Direct UDP",
            "Stealth setting: \(model.stealthMode.rawValue)",
            "Allow local network: \(model.excludeLAN ? "on" : "off")",
            "Handshake: \(handshake)",
            "Plan check: \(model.billingReady ? "done" : "pending")",
            "Last message: \(model.statusMessage ?? "none")",
        ]
        return Redaction.scrub(lines.joined(separator: "\n"))
    }
}

@MainActor
struct DiagnosticsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var copied = false
    @State private var actionError: String?

    var body: some View {
        let report = DiagnosticsReport.make(model: model)
        SettingsScreen(eyebrow: "DIAGNOSTICS", title: "Diagnostic information") {
            Text(L("Connection state, app version, and handshake age. Private keys, addresses, and auth tokens are not included."))
                .font(VFont.bodyMedium).foregroundStyle(Palette.paperMuted)
            Text(report)
                .font(.system(.footnote, design: .monospaced))
                .foregroundStyle(Palette.paper)
                .textSelection(.enabled)
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .glassSurface(cornerRadius: 16)
            HStack(spacing: 10) {
                Button(L(copied ? "Copied" : "Copy")) {
                    Platform.copyToClipboard(report)
                    copied = true
                }
                .buttonStyle(SecondaryButtonStyle())
                ShareLink(item: report) {
                    Text(L("Share"))
                }
                .buttonStyle(SecondaryButtonStyle())
            }
            Button(L("Email to support")) {
                guard let url = SupportLinks.mailto(report: report) else { return }
                openURL(url) { accepted in
                    if !accepted { actionError = L("No email app found. Write to %@.", SupportLinks.contactEmail) }
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            Text(L("Email from this screen always includes the report, even when sharing is off."))
                .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
            if let actionError {
                StatusBanner(text: actionError) { self.actionError = nil }
            }
        }
    }
}

/// In-app privacy summary. The only location and contact shown are
/// "Asunción, Paraguay" and contact@veritasvpn.cloud.
@MainActor
struct PrivacyView: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        SettingsScreen(eyebrow: "LEGAL", title: "Privacy Policy") {
            privacyBlock("What we collect", "If you use an email account, we store that email address, a password hash, and session tokens. We also store device details needed to run the tunnel, such as the device type and system version. We do not collect your browsing activity, the contents of your DNS queries, the contents of your traffic, or a log of your real public IP when you connect.")
            privacyBlock("Location", "VeritasVPN never asks for your location. The map shows only our server in Paraguay, not your position.")
            privacyBlock("Contact", "VeritasVPN\nAsunción, Paraguay\ncontact@veritasvpn.cloud")
            Button(L("Read the full Privacy Policy")) { openURL(SupportLinks.privacy) }
                .buttonStyle(SecondaryButtonStyle())
        }
    }

    private func privacyBlock(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L(title)).font(VFont.titleMedium).foregroundStyle(Palette.paper)
            Text(L(body)).font(VFont.bodyMedium).foregroundStyle(Palette.paperMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassSurface(cornerRadius: 16)
    }
}

/// Shown before the first connect, like Android's VpnDisclosure.
@MainActor
struct VpnDisclosureView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        PremiumBackdrop {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Eyebrow(text: "BEFORE YOU CONNECT")
                    Text(L("Before you use VeritasVPN"))
                        .font(VFont.headlineMedium).foregroundStyle(Palette.paper)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("VeritasVPN will route your traffic")).font(VFont.titleMedium).foregroundStyle(Palette.paper)
                        Text(L(Platform.isMac
                            ? "This app creates a VPN tunnel. After you accept, macOS will ask you to allow a VPN configuration. If you allow it, VeritasVPN routes your Mac's network traffic through VeritasVPN servers in Paraguay."
                            : "This app creates a VPN tunnel. After you accept, iOS will ask you to allow a VPN configuration. If you allow it, VeritasVPN routes your device's network traffic through VeritasVPN servers in Paraguay."))
                            .font(VFont.bodyMedium).foregroundStyle(Palette.paperMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading).glassSurface(cornerRadius: 16)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("What we collect")).font(VFont.titleMedium).foregroundStyle(Palette.paper)
                        Text(L("If you use an email account, we store that email address, a password hash, and session tokens. We also store device details needed to run the tunnel, such as the device type and system version. We do not collect your browsing activity, the contents of your DNS queries, the contents of your traffic, or a log of your real public IP when you connect. The map shows only our server in Paraguay, not your position."))
                            .font(VFont.bodyMedium).foregroundStyle(Palette.paperMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading).glassSurface(cornerRadius: 16)
                    Button(L("Read the Privacy Policy")) { openURL(SupportLinks.privacy) }
                        .font(VFont.body(14, .semibold))
                        .foregroundStyle(Palette.cyanHover)
                        .buttonStyle(.plain)
                    Button(L("Accept and continue")) { model.acceptDisclosure() }
                        .buttonStyle(PrimaryButtonStyle(fill: Palette.cyan, foreground: Palette.ink))
                    Button(L("Decline")) { dismiss() }
                        .buttonStyle(SecondaryButtonStyle())
                    Text(L("Decline keeps the VPN off. You can read the policy again the next time you tap Connect."))
                        .font(VFont.bodySmall).foregroundStyle(Palette.paperDim)
                }
                .padding(24)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 600)
        #endif
    }
}

/// Shown once after "Continue without email" creates an Account ID.
@MainActor
struct AccountIdRevealView: View {
    let accountId: String
    let onDone: () -> Void
    @State private var copied = false

    var body: some View {
        PremiumBackdrop {
            VStack(alignment: .leading, spacing: 16) {
                Eyebrow(text: "ACCOUNT ID")
                Text(L("Your Account ID"))
                    .font(VFont.headlineMedium).foregroundStyle(Palette.paper)
                Text(L("Save this ID in a password manager or another secure place now. If you lose it, the account and its access cannot be recovered."))
                    .font(VFont.bodyMedium).foregroundStyle(Palette.paperMuted)
                    .fixedSize(horizontal: false, vertical: true)
                Text(accountId)
                    .font(.system(.title3, design: .monospaced).weight(.semibold))
                    .foregroundStyle(Palette.cyanHover)
                    .textSelection(.enabled)
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .glassSurface(cornerRadius: 16)
                Button(L(copied ? "Copied to clipboard" : "Copy Account ID")) {
                    Platform.copyToClipboard(accountId)
                    copied = true
                }
                .buttonStyle(SecondaryButtonStyle())
                Button(L("Continue"), action: onDone)
                    .buttonStyle(PrimaryButtonStyle())
            }
            .padding(24)
            .frame(maxWidth: 520)
        }
        .interactiveDismissDisabled()
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 420)
        #endif
    }
}
