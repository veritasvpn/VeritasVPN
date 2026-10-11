package cloud.veritasvpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGateTest {
    @Test
    fun newUser_shouldStartOnboarding() {
        assertTrue(OnboardingGate.shouldStart(disclosureAccepted = false, onboardingDone = false))
    }

    @Test
    fun existingUserWithDisclosureAccepted_shouldNotStartOnboarding() {
        assertFalse(OnboardingGate.shouldStart(disclosureAccepted = true, onboardingDone = false))
    }

    @Test
    fun onboardingDone_shouldNotStartOnboarding() {
        assertFalse(OnboardingGate.shouldStart(disclosureAccepted = false, onboardingDone = true))
    }

    @Test
    fun afterAccept_notPrepared_showsSystemDialog() {
        assertEquals(OnboardingGate.Step.SHOW_SYSTEM_DIALOG, OnboardingGate.afterAccept(prepared = false))
    }

    @Test
    fun afterAccept_prepared_isDone() {
        assertEquals(OnboardingGate.Step.DONE, OnboardingGate.afterAccept(prepared = true))
    }

    @Test
    fun newUser_seesDisclosure() {
        val step = OnboardingGate.next(
            disclosureAccepted = false,
            onboardingDone = false,
            prepared = false
        )
        assertEquals(OnboardingGate.Step.SHOW_DISCLOSURE, step)
    }

    @Test
    fun existingAcceptedUser_skipsOnboarding() {
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = true,
            prepared = true
        )
        assertEquals(OnboardingGate.Step.SKIP, step)
    }

    @Test
    fun declinedUser_skipsOnboarding() {
        // User declined the disclosure during onboarding.
        // Onboarding is marked as done, so they skip it next time.
        val step = OnboardingGate.next(
            disclosureAccepted = false,
            onboardingDone = true,
            prepared = false
        )
        assertEquals(OnboardingGate.Step.SKIP, step)
    }

    @Test
    fun acceptedDisclosure_notPrepared_seesSystemDialog() {
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = false,
            prepared = false
        )
        assertEquals(OnboardingGate.Step.SHOW_SYSTEM_DIALOG, step)
    }

    @Test
    fun acceptedDisclosure_prepared_done() {
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = false,
            prepared = true
        )
        assertEquals(OnboardingGate.Step.DONE, step)
    }

    @Test
    fun consentRevoked_seesSystemDialog() {
        // Another VPN app took over, so prepare() returns an intent again.
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = false,
            prepared = false
        )
        assertEquals(OnboardingGate.Step.SHOW_SYSTEM_DIALOG, step)
    }
}
