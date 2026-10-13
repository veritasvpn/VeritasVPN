package cloud.veritasvpn.ui

data class SignInAgainView(
    val showing: Boolean,
    val awaitingClear: Boolean,
)

/**
 * The activity clears "Sign in again" as soon as sign-in succeeds. A stats
 * broadcast that was already queued can still say the session is signed out.
 * Those are ignored until a broadcast confirms the flag is clear.
 */
object SignInAgainSignal {
    fun afterLocalSignIn(): SignInAgainView = SignInAgainView(showing = false, awaitingClear = true)

    fun merge(showing: Boolean, awaitingClear: Boolean, broadcast: Boolean): SignInAgainView {
        if (broadcast && awaitingClear) return SignInAgainView(showing, awaitingClear)
        return SignInAgainView(
            showing = broadcast,
            awaitingClear = broadcast && awaitingClear,
        )
    }
}
