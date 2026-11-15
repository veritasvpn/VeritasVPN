import SwiftUI
import VeritasCore

enum Route: Hashable {
    case account, networkMap, help, stealth, shield, splitTunnel, privacy, diagnostics
}

/// Home screen: header, status copy, hero connect circle, live stats.
@MainActor
struct DashboardView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var path: [Route]
    @State private var showSettings = false

    var body: some View {
        PremiumBackdrop {
            ScrollView {
                VStack(spacing: 0) {
                    header
                    Spacer(minLength: 44)
                    statusCopy
                    HeroConnectControl(phase: model.heroPhase, signInAgain: model.signInAgain, onTap: heroTap)
                    if let message = model.statusMessage {
                        StatusBanner(text: message, tint: Palette.warning) { model.statusMessage = nil }
                            .padding(.top, 20)
                    } else if let billingError = model.billingError, !model.billingReady {
                        StatusBanner(text: billingError, tint: Palette.warning, actionTitle: "Try again") {
                            Task { await model.refreshBilling() }
                        }
                        .padding(.top, 20)
                    }
                    if model.connected {
                        LiveStatsCard()
                            .padding(.top, 20)
                            .transition(reduceMotion ? .identity : .opacity.combined(with: .move(edge: .bottom)))
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.36), value: model.connected)
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsDrawer { route in
                showSettings = false
                path.append(route)
            }
            .environmentObject(model)
        }
        .sheet(isPresented: $model.showDisclosure) {
            VpnDisclosureView()
                .environmentObject(model)
        }
        .sheet(item: Binding(
            get: { model.revealAccountId.map(AccountIdReveal.init) },
            set: { model.revealAccountId = $0?.id }
        )) { reveal in
            AccountIdRevealView(accountId: reveal.id) { model.revealAccountId = nil }
        }
    }

    private var heroTap: (() -> Void)? {
        switch model.heroAction {
        case .plans: return { path.append(.account) }
        case .none: return model.billingError != nil ? { model.performHeroAction() } : nil
        default: return { model.performHeroAction() }
        }
    }

    private var header: some View {
        HStack {
            Image("VeritasLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 44, height: 44)
                .accessibilityLabel(L("VeritasVPN shield"))
            Spacer()
            HStack(spacing: 8) {
                GlassIconButton(systemImage: "questionmark.circle", accessibilityLabel: "Help and support") {
                    path.append(.help)
                }
                GlassIconButton(systemImage: "gearshape", accessibilityLabel: "Open settings") {
                    showSettings = true
                }
            }
        }
    }

    @ViewBuilder
    private var statusCopy: some View {
        if model.connected {
            VStack(spacing: 8) {
                Eyebrow(text: "CONNECTION SECURED", color: Palette.cyan)
                Text(L("Direct UDP"))
                    .font(VFont.labelLarge)
                    .foregroundStyle(Palette.paperMuted)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(Palette.cyan.opacity(0.1)))
                    .overlay(Capsule().stroke(Palette.cyan.opacity(0.28), lineWidth: 1))
            }
            .padding(.bottom, 24)
        } else if model.connecting {
            VStack(spacing: 10) {
                Eyebrow(text: "ESTABLISHING SECURE CONNECTION")
                Text(L("Creating secure keys and validating encrypted internet access."))
                    .font(VFont.bodyMedium)
                    .foregroundStyle(Palette.paperMuted)
                    .multilineTextAlignment(.center)
            }
            .padding(.bottom, 24)
        }
    }
}

private struct AccountIdReveal: Identifiable { let id: String }

/// Inline, dismissible message card. Text is always a descriptive sentence.
@MainActor
struct StatusBanner: View {
    let text: String
    var tint: Color = Palette.warning
    var actionTitle: String = "Dismiss"
    var action: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.circle")
                .foregroundStyle(tint)
            Text(L(text))
                .font(VFont.bodyMedium)
                .foregroundStyle(Palette.paper)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            if let action {
                Button(L(actionTitle), action: action)
                    .font(VFont.body(13, .semibold))
                    .foregroundStyle(Palette.cyanHover)
                    .buttonStyle(.plain)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(tint.opacity(0.08)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(tint.opacity(0.3), lineWidth: 1))
    }
}

/// Android's LiveTransferStats card.
@MainActor
struct LiveStatsCard: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let reconnecting = model.reconnecting || !model.handshakeOK
        let rows = ShieldRow.rows(policy: model.shield, blocks: model.shieldBlocks).filter(\.enabled)
        VStack(alignment: .leading, spacing: 0) {
            Eyebrow(text: reconnecting ? "STATS" : "LIVE STATS", color: Palette.paperDim)
            HStack {
                StatCell(label: "Download", value: Formatters.bytes(model.stats.rxBytes), dimmed: reconnecting)
                Spacer()
                StatCell(label: "Upload", value: Formatters.bytes(model.stats.txBytes), dimmed: reconnecting)
                Spacer()
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    StatCell(label: "Handshake", value: Formatters.handshakeAge(model.stats.lastHandshakeEpoch, now: context.date))
                }
            }
            .padding(.top, 10)
            if model.shieldBlocks != nil, !rows.isEmpty {
                Divider().overlay(Palette.line).padding(.vertical, 10)
                ForEach(rows) { row in
                    HStack {
                        Text(L(row.group.label)).foregroundStyle(Palette.paperMuted)
                        Spacer()
                        Text(L("%lld today · %lld this week", row.today, row.week))
                            .foregroundStyle(Palette.paper)
                            .fontWeight(.bold)
                    }
                    .font(VFont.body(13))
                    .padding(.top, 4)
                }
                Text(L("Today and this week are UTC, for this account."))
                    .font(VFont.body(11))
                    .foregroundStyle(Palette.paperDim)
                    .padding(.top, 4)
            }
            Divider().overlay(Palette.line).padding(.vertical, 10)
            Text(L("Veritas Shield on"))
                .font(VFont.body(13, .bold))
                .foregroundStyle(Palette.paper)
            Text(L("Tunnel gateway · malware/phishing blocks via DoH upstreams. Well-known public DoH resolvers are blocked."))
                .font(VFont.body(12))
                .foregroundStyle(Palette.paperDim)
                .padding(.top, 4)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassSurface()
    }
}

@MainActor
private struct StatCell: View {
    let label: String
    let value: String
    var dimmed = false

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(VFont.body(14, .bold))
                .foregroundStyle(dimmed ? Palette.paperDim : Palette.paper)
                .monospacedDigit()
            Text(L(label))
                .font(VFont.body(11))
                .foregroundStyle(Palette.paperDim)
        }
        .accessibilityElement(children: .combine)
    }
}

enum Formatters {
    static func bytes(_ value: UInt64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .binary
        formatter.allowedUnits = [.useKB, .useMB, .useGB, .useTB]
        return formatter.string(fromByteCount: Int64(clamping: value))
    }

    static func handshakeAge(_ epoch: TimeInterval, now: Date) -> String {
        guard epoch > 0 else { return "—" }
        let age = max(0, Int(now.timeIntervalSince1970 - epoch))
        if age < 60 { return L("%llds ago", Int64(age)) }
        return L("%lldm ago", Int64(age / 60))
    }

    static func date(_ iso: String?) -> String? {
        guard let iso, iso.count >= 10 else { return nil }
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withFullDate]
        guard let day = parser.date(from: String(iso.prefix(10))) else { return nil }
        let out = DateFormatter()
        out.dateStyle = .medium
        out.timeStyle = .none
        out.timeZone = TimeZone(identifier: "UTC")
        return out.string(from: day)
    }

    static func amount(cents: Int64, currency: String?) -> String {
        let sign = cents < 0 ? "-" : ""
        let absolute = cents.magnitude
        let dollars = absolute / 100
        let remainder = absolute % 100
        let value = remainder == 0 ? "\(dollars)" : String(format: "%llu.%02llu", dollars, remainder)
        guard let currency, !currency.isEmpty, currency.lowercased() != "usd" else { return "\(sign)$\(value)" }
        return "\(sign)\(value) \(currency.uppercased())"
    }
}
