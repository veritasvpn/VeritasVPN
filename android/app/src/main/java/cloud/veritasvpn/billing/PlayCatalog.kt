package cloud.veritasvpn.billing

/**
 * Google Play subscription catalog for the Play build.
 *
 * Keep this in sync with services/billing-svc/internal/model/play_products.go
 * and with the products created in Play Console. Prices match the existing
 * premium plans ($3 / 30 days and $30 / 365 days). Play itself bills calendar
 * periods: monthly is one month, yearly is one year.
 */
object PlayCatalog {
    data class Entry(
        val planId: String,
        val productId: String,
        val basePlanId: String,
        val priceCents: Long,
        val billingPeriod: String,
        val periodDays: Int,
    )

    val monthly = Entry(
        planId = "premium_monthly",
        productId = "premium_monthly",
        basePlanId = "monthly",
        priceCents = 300,
        billingPeriod = "monthly",
        periodDays = 30,
    )

    val annual = Entry(
        planId = "premium_annual",
        productId = "premium_annual",
        basePlanId = "yearly",
        priceCents = 3000,
        billingPeriod = "annual",
        periodDays = 365,
    )

    val all = listOf(monthly, annual)

    fun byPlanId(planId: String): Entry? = all.firstOrNull { it.planId == planId }

    fun byProductId(productId: String): Entry? = all.firstOrNull { it.productId == productId }
}
