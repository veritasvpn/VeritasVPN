package cloud.veritasvpn.ui

/** What the main hero button does. Signed out opens sign-in and leaves the tunnel up. */
enum class HeroMainAction { Connect, Disconnect, SignIn, Plans, None }

fun heroMainAction(
    connecting: Boolean,
    billingReady: Boolean,
    connected: Boolean,
    signInAgain: Boolean,
    isPremium: Boolean,
): HeroMainAction = when {
    signInAgain -> HeroMainAction.SignIn
    connecting || !billingReady -> HeroMainAction.None
    connected -> HeroMainAction.Disconnect
    isPremium -> HeroMainAction.Connect
    else -> HeroMainAction.Plans
}
