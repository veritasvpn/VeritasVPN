package cloud.veritasvpn.ui

/** What the main hero button does. Signed out opens sign-in and leaves the tunnel up. */
enum class HeroMainAction { Connect, Disconnect, SignIn, Plans, None }

fun heroMainAction(
    connecting: Boolean,
    billingReady: Boolean,
    connected: Boolean,
    signInAgain: Boolean,
    isPremium: Boolean,
    reconnecting: Boolean = false,
): HeroMainAction = when {
    signInAgain -> HeroMainAction.SignIn
    connecting || reconnecting || connected -> HeroMainAction.Disconnect
    !billingReady -> HeroMainAction.None
    isPremium -> HeroMainAction.Connect
    else -> HeroMainAction.Plans
}

/** Spoken name for the circle. Connecting and Reconnecting stay tappable so a stuck session can be cancelled. */
fun heroActionName(phase: HeroPhase, signInAgain: Boolean): String? = when {
    signInAgain -> "Sign in"
    phase == HeroPhase.Ready -> "Connect"
    phase == HeroPhase.Upsell -> "Get Premium"
    phase == HeroPhase.Connecting ||
        phase == HeroPhase.Reconnecting ||
        phase == HeroPhase.Protected -> "Disconnect"
    else -> null
}
