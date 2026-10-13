package cloud.veritasvpn.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInAgainSignalTest {

    @Test
    fun signInClearsTheFlagAndIgnoresAStaleSignedOutBroadcast() {
        val cleared = SignInAgainSignal.afterLocalSignIn()
        assertFalse(cleared.showing)
        assertTrue(cleared.awaitingClear)

        val stale = SignInAgainSignal.merge(
            showing = cleared.showing,
            awaitingClear = cleared.awaitingClear,
            broadcast = true,
        )
        assertFalse(stale.showing)
        assertTrue(stale.awaitingClear)

        val confirmed = SignInAgainSignal.merge(
            showing = stale.showing,
            awaitingClear = stale.awaitingClear,
            broadcast = false,
        )
        assertFalse(confirmed.showing)
        assertFalse(confirmed.awaitingClear)
    }

    @Test
    fun aLaterSignOutShowsAgainAfterTheClearIsConfirmed() {
        val confirmed = SignInAgainSignal.merge(showing = false, awaitingClear = false, broadcast = false)
        val signedOut = SignInAgainSignal.merge(
            showing = confirmed.showing,
            awaitingClear = confirmed.awaitingClear,
            broadcast = true,
        )
        assertTrue(signedOut.showing)
    }
}
