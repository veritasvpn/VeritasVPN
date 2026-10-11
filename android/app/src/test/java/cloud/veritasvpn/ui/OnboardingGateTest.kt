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
    fun existingUpgradedUser_skipsOnboarding() {
        // User accepted disclosure before onboarding was added (upgraded from 0.2.91).
        // disclosureAccepted=true, onboardingDone=false -> SKIP
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = false,
            prepared = false,
            lockdownOn = false
        )
        assertEquals(OnboardingGate.Step.SKIP, step)
    }

    @Test
    fun existingUpgradedUser_prepared_skipsOnboarding() {
        // Same as above but VPN is already prepared.
        val step = OnboardingGate.next(
            disclosureAccepted = true,
            onboardingDone = false,
            prepared = true,
            lockdownOn = false
        )
        assertEquals(OnboardingGate.Step.SKIP, step)
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
