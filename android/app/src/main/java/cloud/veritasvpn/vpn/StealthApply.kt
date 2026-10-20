package cloud.veritasvpn.vpn

/**
 * Stealth is applied only when the user taps. Recovery, stalls, and this
 * notice never call [modeAfter] with [userTappedSwitch] set.
 */
object StealthApply {
    fun modeAfter(current: StealthMode, userTappedSwitch: Boolean): StealthMode =
        if (userTappedSwitch) StealthMode.STEALTH else current
}
