package cloud.veritasvpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyEmailResendTest {

    @Test
    fun freshVerificationStartsOnCooldown() {
        assertEquals(30, VerifyEmailResend.COOLDOWN_SECONDS)
        assertFalse(VerifyEmailResend.canResend(loading = false, cooldownSeconds = VerifyEmailResend.COOLDOWN_SECONDS))
        assertEquals(
            VerifyEmailResend.Label.Countdown(30),
            VerifyEmailResend.label(loading = false, cooldownSeconds = 30),
        )
    }

    @Test
    fun countdownReachesReadyAtZero() {
        assertEquals(
            VerifyEmailResend.Label.Countdown(1),
            VerifyEmailResend.label(loading = false, cooldownSeconds = 1),
        )
        assertFalse(VerifyEmailResend.canResend(loading = false, cooldownSeconds = 1))
        assertEquals(
            VerifyEmailResend.Label.Ready,
            VerifyEmailResend.label(loading = false, cooldownSeconds = 0),
        )
        assertTrue(VerifyEmailResend.canResend(loading = false, cooldownSeconds = 0))
    }

    @Test
    fun loadingBlocksResendAndReplacesCountdownLabel() {
        assertFalse(VerifyEmailResend.canResend(loading = true, cooldownSeconds = 0))
        assertEquals(
            VerifyEmailResend.Label.Sending,
            VerifyEmailResend.label(loading = true, cooldownSeconds = 12),
        )
    }

    @Test
    fun displayEmailKeepsTheAddressAndCanWrap() {
        val shown = displayEmail("jgv9710@gmail.com")
        assertEquals("jgv9710@gmail.com", shown.replace("\u200B", ""))
        assertTrue(shown.contains("\u200B@"))
        assertTrue(shown.contains(".\u200B"))
    }
}
