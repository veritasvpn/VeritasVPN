package cloud.veritasvpn.ui

/**
 * Pure logic for the first-run onboarding VPN consent flow.
 *
 * The onboarding shows the disclosure and system consent dialog right after
 * sign-in, so the first connect tap is truly one-tap. This object decides
 * what step to show based on the current state.
 */
object OnboardingGate {
    enum class Step {
        /** Onboarding already done or not needed; go straight to dashboard. */
        SKIP,
        /** Show the VPN disclosure screen. */
        SHOW_DISCLOSURE,
        /** Show the system VPN consent dialog (prepare() returned an intent). */
        SHOW_SYSTEM_DIALOG,
        /** Disclosure accepted, system consent granted; onboarding complete. */
        DONE
    }

    /**
     * Should onboarding start? Returns true for new users who haven't completed onboarding
     * and haven't accepted the disclosure yet.
     */
    fun shouldStart(disclosureAccepted: Boolean, onboardingDone: Boolean): Boolean {
        return !onboardingDone && !disclosureAccepted
    }

    /**
     * After accepting the disclosure, what's the next step?
     * If VPN is already prepared, we're done. Otherwise, show the system dialog.
     */
    fun afterAccept(prepared: Boolean): Step {
        return if (prepared) Step.DONE else Step.SHOW_SYSTEM_DIALOG
    }

    /**
     * Decide the next onboarding step.
     *
     * @param disclosureAccepted Whether the user accepted the VPN disclosure.
     * @param onboardingDone Whether the onboarding flow completed (accept or decline).
     * @param prepared Whether VpnService.prepare() returned null (already prepared).
     */
    fun next(
        disclosureAccepted: Boolean,
        onboardingDone: Boolean,
        prepared: Boolean
    ): Step {
        if (onboardingDone) return Step.SKIP
        if (!disclosureAccepted) return Step.SHOW_DISCLOSURE
        // Disclosure accepted but onboarding not done: show system dialog or done
        return afterAccept(prepared)
    }
}
