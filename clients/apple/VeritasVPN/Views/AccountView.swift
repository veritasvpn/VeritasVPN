import SwiftUI
import VeritasCore

/// Account and Plans, a port of Android's PlansScreen without Bitcoin or
/// sideload flows. Purchases are a StoreKit 2 placeholder (see SubscriptionStore).
@MainActor
struct AccountView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var store: SubscriptionStore
    @State private var selectedPlan = PlanCatalog.monthly.id
    @State private var refreshing = false
    @State private var notice: String?
    @State private var showDelete = false

    var body: some View {
        SettingsScreen(eyebrow: "ACCOUNT", title: "Plans and account") {
            currentPlanCard
            if let notice {
                StatusBanner(text: notice, tint: Palette.cyan) { self.notice = nil }
            }
            if let billingError = model.billingError {
                Text(L(billingError)).font(VFont.body(13)).foregroundStyle(Palette.warning)
            }
            if let billing = model.billing, billing.isPaymentPending, !model.isPremium {
                pendingCard(billing)
            }
            planCard
            if !model.isPremium && !(model.billing?.isPaused ?? false) {
                trialCard
                planChoices
                Text(L("Subscribe on iPhone, iPad and Mac"))
                    .font(VFont.body(17, .bold)).foregroundStyle(Palette.paper)
                    .padding(.top, 6)
                Text(L("Subscriptions through the App Store are coming soon. A Premium plan bought on another device already works here when you sign in with the same account."))
                    .font(VFont.body(13)).foregroundStyle(Palette.paperMuted)
                    .fixedSize(horizontal: false, vertical: true)
                Button(L("Subscribe")) {
                    Task {
                        guard let plan = PlanCatalog.plan(id: selectedPlan) else { return }
                        notice = await store.purchase(plan, accountId: model.session?.accountId ?? "")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!SubscriptionStore.purchasesEnabled)
                Button(L("Restore purchases")) {
                    Task { notice = await store.restore() }
                }
                .buttonStyle(SecondaryButtonStyle())
            }
            accountDetails
            PurchaseHistorySection(billing: model.billing, loading: refreshing && model.billing == nil)
            DeleteAccountSection(showDelete: $showDelete)
        }
        .task { await store.load() }
    }

    private var currentPlanCard: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("CURRENT PLAN")).font(VFont.body(11)).tracking(1.4).foregroundStyle(Palette.paperDim)
                Text(L(planTitle))
                    .font(VFont.body(20, .bold))
                    .foregroundStyle(model.isPremium ? Palette.cyanHover : Palette.paper)
                if model.billing?.isPaused == true {
                    Text(L("Paused. Manage it where you subscribed."))
                        .font(VFont.body(13)).foregroundStyle(Palette.paperMuted)
                } else if model.isPremium, let end = Formatters.date(model.billing?.currentPeriodEnd) {
                    Text(L("PREMIUM ACCESS EXPIRES")).font(VFont.body(10)).tracking(1.1).foregroundStyle(Palette.paperDim)
                    Text(L("Expires on %@", end)).font(VFont.body(13)).foregroundStyle(Palette.paperMuted)
                }
            }
            Spacer()
            if refreshing {
                ProgressView().tint(Palette.cyan)
            } else {
                Button(L("Refresh")) {
                    refreshing = true
                    Task {
                        await model.refreshBilling()
                        refreshing = false
                    }
                }
                .foregroundStyle(Palette.cyanHover)
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .glassSurface()
    }

    private var planTitle: String {
        guard let billing = model.billing else {
            return model.billingReady ? "No active subscription" : "Checking subscription…"
        }
        if billing.isPaused { return "Paused" }
        if billing.isPremium { return "Premium" }
        if billing.isPaymentPending { return "Payment pending" }
        return "No active subscription"
    }

    private func pendingCard(_ billing: BillingStatus) -> some View {
        HStack(spacing: 12) {
            ProgressView().tint(Palette.cyan)
            VStack(alignment: .leading, spacing: 2) {
                Text(L(billing.paymentState == "awaiting_confirmation" ? "Payment received" : (billing.paymentState == "awaiting_payment" ? "Waiting for payment" : "Checking payment")))
                    .font(VFont.body(16, .bold)).foregroundStyle(Palette.cyanHover)
                Text(L("Premium turns on as soon as the payment is confirmed."))
                    .font(VFont.body(13)).foregroundStyle(Palette.paperMuted)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Palette.cyan.opacity(0.08)))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.cyan.opacity(0.32), lineWidth: 1))
    }

    private var selectedOffer: SubscriptionStore.Offer? {
        store.offers.first { $0.id == selectedPlan }
    }

    private var planCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L("Premium")).font(VFont.titleLarge).foregroundStyle(Palette.paper)
                Spacer()
                if model.isPremium {
                    Text(L("CURRENT")).font(VFont.body(11, .bold)).foregroundStyle(Palette.success)
                }
            }
            Text(selectedOffer?.displayPrice ?? PlanCatalog.monthly.fallbackPrice)
                .font(VFont.display(26, .bold)).foregroundStyle(Palette.cyanHover)
            ForEach(["Paraguay WireGuard egress", "Up to 5 VPN devices", "Veritas Shield DNS protection", "iPhone, iPad, Mac, Android, Chrome, and Linux access"], id: \.self) { feature in
                Label {
                    Text(L(feature)).font(VFont.body(14)).foregroundStyle(Palette.paperMuted)
                } icon: {
                    Image(systemName: "checkmark").foregroundStyle(Palette.cyan)
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassSurface(border: Palette.cyan.opacity(0.45))
    }

    @ViewBuilder
    private var trialCard: some View {
        if model.billing?.trialEligible == true, let offer = selectedOffer, offer.hasFreeTrial {
            VStack(alignment: .leading, spacing: 6) {
                Text(L("Free trial")).font(VFont.body(16, .bold)).foregroundStyle(Palette.cyanHover)
                Text(L("Try Premium free, then %@. Cancel anytime in the App Store.", offer.displayPrice))
                    .font(VFont.body(13)).foregroundStyle(Palette.paperMuted)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(Palette.cyan.opacity(0.08)))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.cyan.opacity(0.3), lineWidth: 1))
        }
    }

    private var planChoices: some View {
        HStack(spacing: 10) {
            ForEach(store.offers) { offer in
                Button { selectedPlan = offer.id } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L(offer.plan.displayName)).font(VFont.body(15, .bold)).foregroundStyle(Palette.paper)
                        Text(offer.displayPrice).font(VFont.body(13)).foregroundStyle(Palette.paperMuted)
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 14).fill(selectedPlan == offer.id ? Palette.royal.opacity(0.22) : Palette.cardBg))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(selectedPlan == offer.id ? Palette.cyan : Palette.line, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selectedPlan == offer.id ? .isSelected : [])
            }
        }
    }

    private var accountDetails: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let email = model.session?.email, !email.isEmpty {
                Text(L("EMAIL")).font(VFont.body(11)).tracking(1.4).foregroundStyle(Palette.paperDim)
                Text(email).font(VFont.body(15, .semibold)).foregroundStyle(Palette.paper).textSelection(.enabled)
            }
            if let id = model.session?.accountId, !id.isEmpty {
                Text(L("ACCOUNT ID")).font(VFont.body(11)).tracking(1.4).foregroundStyle(Palette.paperDim)
                Text(id).font(.system(.body, design: .monospaced)).foregroundStyle(Palette.paper).textSelection(.enabled)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassSurface()
    }
}

@MainActor
struct PurchaseHistorySection: View {
    let billing: BillingStatus?
    let loading: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("Purchase history")).font(VFont.body(17, .bold)).foregroundStyle(Palette.paper)
            if loading {
                Text(L("Loading purchase history…")).font(VFont.body(14)).foregroundStyle(Palette.paperMuted)
            } else if billing == nil {
                Text(L("Purchase history could not be loaded.")).font(VFont.body(14)).foregroundStyle(Palette.paperMuted)
            } else if let payments = billing?.payments, !payments.isEmpty {
                ForEach(Array(payments.enumerated()), id: \.offset) { _, payment in
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Formatters.date(payment.createdAt) ?? "—").font(VFont.body(14, .bold)).foregroundStyle(Palette.paper)
                            Text(summary(payment)).font(VFont.body(13)).foregroundStyle(Palette.paperMuted)
                        }
                        Spacer()
                        Text(L(statusLabel(payment.status)))
                            .font(VFont.body(12, .semibold))
                            .foregroundStyle(statusColor(payment.status))
                    }
                    .padding(14)
                    .glassSurface(cornerRadius: 14)
                }
            } else {
                Text(L("No past payments yet.")).font(VFont.body(14)).foregroundStyle(Palette.paperMuted)
            }
        }
    }

    private func summary(_ payment: PurchaseHistoryItem) -> String {
        let plan: String
        switch payment.plan?.lowercased() {
        case "annual"?: plan = L("Annual")
        case "monthly"?: plan = L("Monthly")
        default: plan = "—"
        }
        let amount = payment.isTrial ? L("Free trial") : Formatters.amount(cents: payment.amountCents, currency: payment.currency)
        var text = "\(plan) · \(amount)"
        if payment.provider == "google_play" { text += " · Google Play" }
        if payment.provider == "app_store" { text += " · App Store" }
        return text
    }

    private func statusLabel(_ status: String?) -> String {
        switch status {
        case "completed": return "Confirmed"
        case "failed": return "Failed"
        case "refunded": return "Refunded"
        default: return "Pending"
        }
    }

    private func statusColor(_ status: String?) -> Color {
        switch status {
        case "completed": return Palette.success
        case "failed", "refunded": return Palette.warning
        default: return Palette.paperMuted
        }
    }
}

@MainActor
struct DeleteAccountSection: View {
    @EnvironmentObject private var model: AppModel
    @Binding var showDelete: Bool
    @State private var password = ""
    @State private var turnstileToken = ""
    @State private var showTurnstile = false
    @State private var deleting = false
    @State private var error: String?

    private var isEmailAccount: Bool { !(model.session?.isAnonymous ?? true) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("Delete account")).font(VFont.body(17, .bold)).foregroundStyle(Palette.paper)
            Text(L("Permanently deletes this account, signs you out, and removes the account, sessions, and VPN peers from the live service. Encrypted backups can keep a copy for about 3 months. This cannot be undone."))
                .font(VFont.body(13)).foregroundStyle(Palette.paperMuted)
                .fixedSize(horizontal: false, vertical: true)
            Button(L("Delete account")) { showDelete = true }
                .buttonStyle(SecondaryButtonStyle(tint: Palette.error))
        }
        .padding(.top, 8)
        .sheet(isPresented: $showDelete) { confirmSheet }
    }

    private var confirmSheet: some View {
        PremiumBackdrop {
            VStack(alignment: .leading, spacing: 14) {
                Text(L("Delete this account?")).font(VFont.body(16, .bold)).foregroundStyle(Palette.paper)
                Text(L(isEmailAccount
                    ? "Enter your password to confirm. You will be signed out of this device."
                    : "Complete the security check to confirm. You will be signed out of this device."))
                    .font(VFont.bodyMedium).foregroundStyle(Palette.paperMuted)
                if isEmailAccount {
                    SecureField(L("Password"), text: $password)
                        .textContentType(.password)
                        .authField()
                }
                if let error {
                    Text(L(error)).font(VFont.body(13)).foregroundStyle(Palette.error)
                }
                Button(L(deleting ? "Deleting…" : "Delete account permanently")) {
                    if isEmailAccount {
                        Task { await delete() }
                    } else {
                        showTurnstile = true
                    }
                }
                .buttonStyle(PrimaryButtonStyle(fill: Palette.error, foreground: Palette.ink))
                .disabled(deleting || (isEmailAccount && password.isEmpty))
                Button(L("Keep my account")) {
                    password = ""
                    turnstileToken = ""
                    error = nil
                    showDelete = false
                }
                .buttonStyle(SecondaryButtonStyle())
            }
            .padding(24)
            .frame(maxWidth: 480)
        }
        .sheet(isPresented: $showTurnstile) {
            TurnstileSheet { token in
                showTurnstile = false
                turnstileToken = token
                Task { await delete() }
            } onCancel: {
                showTurnstile = false
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 360)
        #endif
    }

    private func delete() async {
        deleting = true
        error = nil
        defer { deleting = false }
        do {
            try await model.deleteAccount(password: password, turnstileToken: turnstileToken)
            password = ""
            turnstileToken = ""
            showDelete = false
        } catch AuthFailure.turnstileRequired {
            showTurnstile = true
        } catch {
            self.error = ErrorText.message(for: error)
        }
    }
}
