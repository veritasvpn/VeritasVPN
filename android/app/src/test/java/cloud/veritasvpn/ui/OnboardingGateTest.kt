package cloud.veritasvpn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingGateTest {
    @Test
    fun newUser_seesDisclosure() {
        val step = OnboardingGate.next(
            disclosureAccepted = false,
            onboardingDone = false,
            prepared = false,
            lockdownOn = false
        )
        assertEquals(OnboardingGate.Step.SHOW_DISCLOSURE, step)
    }

    @Test
    fun existingAcceptedUser_skipsOnboarding() {
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = true,
            prepared = true,
            lockdownOn = true
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
            prepared = false,
            lockdownOn = false
        )
        assertEquals(OnboardingGate.Step.SKIP, step)
    }

    @Test
    fun acceptedDisclosure_notPrepared_seesSystemDialog() {
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = false,
            prepared = false,
            lockdownOn = false
        )
        assertEquals(OnboardingGate.Step.SHOW_SYSTEM_DIALOG, step)
    }

    @Test
    fun acceptedDisclosure_prepared_done() {
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = false,
            prepared = true,
            lockdownOn = false
        )
        assertEquals(OnboardingGate.Step.DONE, step)
    }

    @Test
    fun consentRevoked_seesSystemDialog() {
        // Another VPN app took over, so prepare() returns an intent again.
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = false,
            prepared = false,
            lockdownOn = false
        )
        assertEquals(OnboardingGate.Step.SHOW_SYSTEM_DIALOG, step)
    }

    @Test
    fun lockdownOff_stillShowsDisclosureFirst() {
        // Lockdown state doesn't affect the disclosure step.
        val step = OnboardingGate.next(
            disclosureAccepted = false,
            onboardingDone = false,
            prepared = false,
            lockdownOn = false
        )
        assertEquals(OnboardingGate.Step.SHOW_DISCLOSURE, step)
    }
}
