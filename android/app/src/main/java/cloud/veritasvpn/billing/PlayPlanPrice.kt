package cloud.veritasvpn.billing

/**
 * Localized price for one Play subscription, taken from the base plan's
 * regular pricing phase. [formattedPrice] is Play's own string, for example
 * "PYG 20,000".
 */
data class PlayPlanPrice(
    val planId: String,
    val formattedPrice: String,
    val billingPeriod: String,
)

/** One pricing phase flattened from ProductDetails, so tests do not need Play. */
internal data class StorePricePhase(
    val basePlanId: String,
    val offerId: String?,
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val billingPeriod: String,
    val infiniteRecurring: Boolean,
)

/**
 * The base plan's regular (infinite, paid) phase. Trial and intro phases are
 * skipped. A missing catalog returns null so the screen can hide the price
 * instead of inventing a USD amount.
 */
internal fun regularBasePlanPrice(
    planId: String,
    basePlanId: String,
    phases: List<StorePricePhase>,
): PlayPlanPrice? {
    val paid = phases.filter {
        it.basePlanId == basePlanId &&
            it.formattedPrice.isNotBlank() &&
            it.priceAmountMicros > 0
    }
    if (paid.isEmpty()) return null
    val baseOffer = paid.filter { it.offerId.isNullOrBlank() }
    val pool = if (baseOffer.isNotEmpty()) baseOffer else paid
    val regular = pool.firstOrNull { it.infiniteRecurring } ?: pool.last()
    return PlayPlanPrice(
        planId = planId,
        formattedPrice = regular.formattedPrice,
        billingPeriod = regular.billingPeriod,
    )
}

internal fun playPriceSuffix(period: String): String = when (period.uppercase()) {
    "P1M" -> "/month"
    "P1Y" -> "/year"
    "P1W" -> "/week"
    "P1D" -> "/day"
    else -> ""
}

/**
 * Play prices are fetched only after BillingClient is connected. While it is
 * not, the screen keeps the loading line. An empty plan list has nothing to ask.
 */
internal fun playCatalogReadyToQuery(planIds: List<String>, clientReady: Boolean): Boolean =
    planIds.isNotEmpty() && clientReady

internal fun playPlanPriceLabel(price: PlayPlanPrice): String {
    val suffix = playPriceSuffix(price.billingPeriod)
    return if (suffix.isEmpty()) price.formattedPrice else "${price.formattedPrice} $suffix"
}

/**
 * Choice-button detail. The direct build keeps its USD copy. Play shows the
 * localized price, a loading line, or nothing. It never falls back to "$3".
 */
internal fun planChoiceDetail(
    playBilling: Boolean,
    pricesLoaded: Boolean,
    price: PlayPlanPrice?,
    directDetail: String,
): String = when {
    !playBilling -> directDetail
    !pricesLoaded -> "Loading price…"
    price == null -> ""
    else -> playPlanPriceLabel(price)
}

internal data class ShownPlanPrice(
    val price: String?,
    val suffix: String,
    val loading: Boolean,
)

internal fun shownPlanPrice(
    playBilling: Boolean,
    pricesLoaded: Boolean,
    price: PlayPlanPrice?,
    directPrice: String,
    directSuffix: String,
): ShownPlanPrice = when {
    !playBilling -> ShownPlanPrice(directPrice, directSuffix, loading = false)
    !pricesLoaded -> ShownPlanPrice(null, "", loading = true)
    price == null -> ShownPlanPrice(null, "", loading = false)
    else -> ShownPlanPrice(price.formattedPrice, playPriceSuffix(price.billingPeriod), loading = false)
}
