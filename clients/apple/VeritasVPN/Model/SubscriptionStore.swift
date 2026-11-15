import Foundation
import StoreKit
import VeritasCore

/// StoreKit 2 placeholder for Premium on iPhone, iPad and Mac.
///
/// Uses the same product ids as Google Play (`premium_monthly`,
/// `premium_annual`) so App Store Connect can mirror the catalog.
///
/// TODO(apple-billing): enable purchases once:
///   1. the Apple Developer account exists and the two auto-renewable
///      subscriptions are created in App Store Connect (one group);
///   2. the API has an App Store verification endpoint (for example
///      POST /api/v1/billing/apple/verify with the signed transaction JWS)
///      and App Store Server Notifications v2. No server changes were made
///      for this placeholder.
/// Until then `purchasesEnabled` is false and the Plans screen explains that
/// subscriptions on Apple devices are coming soon. Bitcoin and APK-style
/// flows are intentionally not offered on Apple platforms.
@MainActor
final class SubscriptionStore: ObservableObject {
    struct Offer: Identifiable, Equatable {
        let plan: PlanCatalog.Plan
        let displayPrice: String
        let hasFreeTrial: Bool
        var id: String { plan.id }
    }

    static let purchasesEnabled = false

    @Published private(set) var offers: [Offer] = PlanCatalog.all.map {
        Offer(plan: $0, displayPrice: $0.fallbackPrice, hasFreeTrial: false)
    }
    @Published private(set) var loaded = false

    /// Loads localized prices when products exist; keeps the fallback
    /// prices otherwise (always the case before App Store Connect setup).
    func load() async {
        guard !loaded else { return }
        loaded = true
        guard let products = try? await Product.products(for: PlanCatalog.productIds), !products.isEmpty else { return }
        offers = PlanCatalog.all.map { plan in
            guard let product = products.first(where: { $0.id == plan.productId }) else {
                return Offer(plan: plan, displayPrice: plan.fallbackPrice, hasFreeTrial: false)
            }
            let trial = product.subscription?.introductoryOffer?.paymentMode == .freeTrial
            return Offer(plan: plan, displayPrice: product.displayPrice, hasFreeTrial: trial)
        }
    }

    /// Placeholder: never charges. Returns the sentence to show.
    func purchase(_ plan: PlanCatalog.Plan, accountId: String) async -> String {
        guard Self.purchasesEnabled else {
            return "Subscriptions on iPhone, iPad and Mac are coming soon. Your Premium plan from another device works here when you sign in with the same account."
        }
        // TODO(apple-billing): call product.purchase(options: [.appAccountToken(uuid-for-account)]),
        // send the verified transaction to the API, then finish() it.
        return "Subscriptions on iPhone, iPad and Mac are coming soon. Your Premium plan from another device works here when you sign in with the same account."
    }

    /// Placeholder for "Restore purchases".
    func restore() async -> String {
        // TODO(apple-billing): AppStore.sync() and re-verify with the API.
        "Nothing to restore yet. Subscriptions on Apple devices are coming soon."
    }
}
