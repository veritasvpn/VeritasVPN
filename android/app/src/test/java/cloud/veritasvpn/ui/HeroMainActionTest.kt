package cloud.veritasvpn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroMainActionTest {

    @Test
    fun signedOutTapOpensSignInAndDoesNotDisconnect() {
        assertEquals(
            HeroMainAction.SignIn,
            heroMainAction(
                connecting = false,
                billingReady = true,
                connected = true,
                signInAgain = true,
                isPremium = true,
            ),
        )
        assertEquals(
            HeroMainAction.SignIn,
            heroMainAction(
                connecting = false,
                billingReady = false,
                connected = true,
                signInAgain = true,
                isPremium = false,
            ),
        )
    }

    @Test
    fun connectingAndReconnectingDisconnectImmediately() {
        assertEquals(
            HeroMainAction.Disconnect,
            heroMainAction(
                connecting = true,
                billingReady = false,
                connected = false,
                signInAgain = false,
                isPremium = true,
                reconnecting = false,
            ),
        )
        assertEquals(
            HeroMainAction.Disconnect,
            heroMainAction(
                connecting = false,
                billingReady = true,
                connected = true,
                signInAgain = false,
                isPremium = true,
                reconnecting = true,
            ),
        )
        assertEquals("Disconnect", heroActionName(HeroPhase.Connecting, signInAgain = false))
        assertEquals("Disconnect", heroActionName(HeroPhase.Reconnecting, signInAgain = false))
        assertEquals("Sign in", heroActionName(HeroPhase.Reconnecting, signInAgain = true))
        assertEquals(
            HeroMainAction.SignIn,
            heroMainAction(
                connecting = true,
                billingReady = true,
                connected = false,
                signInAgain = true,
                isPremium = true,
                reconnecting = true,
            ),
        )
    }

    @Test
    fun connectedTapStillDisconnectsOnceSignedIn() {
        assertEquals(
            HeroMainAction.Disconnect,
            heroMainAction(
                connecting = false,
                billingReady = true,
                connected = true,
                signInAgain = false,
                isPremium = true,
            ),
        )
    }
}
