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

/** What the Play plan cards do while billing setup is in progress. */
internal enum class PlayCatalogRequest { LOAD, WAIT, HIDE }

/**
 * Prices load once Play Billing is connected. Until then the card says
 * "Loading price…". If setup fails or times out, the price line is hidden.
 * An empty plan list has nothing to ask, so it hides too.
 */
internal fun playCatalogRequest(hasPlans: Boolean, clientReady: Boolean, setupGaveUp: Boolean): PlayCatalogRequest =
    when {
        !hasPlans -> PlayCatalogRequest.HIDE
        clientReady -> PlayCatalogRequest.LOAD
        setupGaveUp -> PlayCatalogRequest.HIDE
        else -> PlayCatalogRequest.WAIT
    }

/** A second connect() while BillingClient is already connecting is ignored. */
internal fun shouldStartPlayBillingConnection(closed: Boolean, ready: Boolean, inProgress: Boolean): Boolean =
    !closed && !ready && !inProgress

/** Arm the price timeout on every plan-screen request that is still disconnected. */
internal fun shouldArmPlayCatalogTimeout(hasPlans: Boolean, clientReady: Boolean): Boolean =
    hasPlans && !clientReady

internal const val PLAY_BILLING_RECONNECT_BACKOFF_MS = 2_000L

/** One delayed reconnect. A reconnect that is already scheduled is not queued again. */
internal fun playReconnectBackoffMs(alreadyScheduled: Boolean): Long? =
    if (alreadyScheduled) null else PLAY_BILLING_RECONNECT_BACKOFF_MS

/** Server verify errors that contain these words are not shown to the user. */
internal fun playPurchaseErrorMessage(serverMessage: String?): String {
    val raw = serverMessage?.trim().orEmpty()
    if (raw.isEmpty() ||
        raw.contains("invalid", ignoreCase = true) ||
        raw.contains("required", ignoreCase = true)
    ) {
        return "Couldn't verify this Google Play purchase."
    }
    return raw
}

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
