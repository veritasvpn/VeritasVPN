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
     * Decide the next onboarding step.
     *
     * @param disclosureAccepted Whether the user accepted the VPN disclosure.
     * @param onboardingDone Whether the onboarding flow completed (accept or decline).
     * @param prepared Whether VpnService.prepare() returned null (already prepared).
     * @param lockdownOn Whether Always-on + Block connections without VPN is enabled.
     */
    fun next(
        disclosureAccepted: Boolean,
        onboardingDone: Boolean,
        prepared: Boolean,
        lockdownOn: Boolean
    ): Step {
        if (onboardingDone) return Step.SKIP
        if (!disclosureAccepted) return Step.SHOW_DISCLOSURE
        // Existing upgraded users: disclosure accepted but onboarding not done yet.
        // Skip onboarding for them since they already accepted disclosure before.
        return Step.SKIP
    }
}
