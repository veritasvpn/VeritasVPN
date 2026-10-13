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
