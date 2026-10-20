package cloud.veritasvpn.vpn

/**
 * Home-screen hint that the current network may be blocking the VPN.
 *
 * This object never changes transport, never picks a network, and never
 * starts a reconnect. Those happen only when the user taps a button.
 */
object BlockedNetworkNotice {
    const val NO_PROGRESS_MS = 10_000L

    /** A second rising sample hides the card, so one stray byte does not flash it. */
    const val RX_HIDE_SAMPLES = 2

    const val MESSAGE = "This network may be blocking VPN connections."
    const val OTHER_NETWORK = "Try another network."

    data class Clock(
        val stallSinceMs: Long = 0L,
        val rxAtStall: Long = 0L,
        val visible: Boolean = false,
        val rxRiseSamples: Int = 0,
    )

    data class Sample(
        val nowMs: Long,
        val connecting: Boolean,
        val reconnecting: Boolean,
        val connected: Boolean,
        val handshakeOk: Boolean,
        val handshakeMs: Long,
        val attemptStartedMs: Long,
        val rxBytes: Long,
        val networkValidated: Boolean,
        val alreadyStealth: Boolean,
        val dismissed: Boolean,
        val clock: Clock = Clock(),
    )

    data class Step(
        val visible: Boolean = false,
        val offerStealthSwitch: Boolean = false,
        val suggestOtherNetwork: Boolean = false,
        val clock: Clock = Clock(),
    )

    fun sessionLive(sample: Sample): Boolean =
        sample.connecting || sample.reconnecting || (sample.connected && !sample.handshakeOk)

    /**
     * A handshake from before this connect or reconnect does not count.
     * Outside those episodes, a handshake the service still calls fresh does.
     */
    fun handshakeSucceeded(sample: Sample): Boolean {
        if (sample.connecting || sample.reconnecting) {
            return sample.handshakeMs > 0L &&
                sample.attemptStartedMs > 0L &&
                sample.handshakeMs >= sample.attemptStartedMs
        }
        return sample.handshakeOk
    }

    fun advance(sample: Sample): Step {
        if (!sessionLive(sample) || handshakeSucceeded(sample) || !sample.networkValidated) {
            return Step()
        }
        if (sample.dismissed) {
            return Step(clock = sample.clock.copy(visible = false))
        }
        val clock = sample.clock
        val rxIncreased = clock.stallSinceMs != 0L && sample.rxBytes > clock.rxAtStall
        if (rxIncreased) {
            val rises = clock.rxRiseSamples + 1
            if (!clock.visible || rises >= RX_HIDE_SAMPLES) {
                return Step(
                    clock = Clock(
                        stallSinceMs = sample.nowMs,
                        rxAtStall = sample.rxBytes,
                    ),
                )
            }
            val held = clock.copy(rxRiseSamples = rises, visible = true)
            return presented(sample.alreadyStealth, held)
        }
        val stallSince = if (clock.stallSinceMs == 0L) sample.nowMs else clock.stallSinceMs
        val rxBase = if (clock.stallSinceMs == 0L) sample.rxBytes else clock.rxAtStall
        val visible = sample.nowMs - stallSince >= NO_PROGRESS_MS
        return presented(
            sample.alreadyStealth,
            Clock(
                stallSinceMs = stallSince,
                rxAtStall = rxBase,
                visible = visible,
            ),
        )
    }

    fun reconnectingText(blocked: Boolean): String =
        if (blocked) "Reconnecting… $MESSAGE" else "Reconnecting…"

    private fun presented(alreadyStealth: Boolean, clock: Clock): Step {
        if (!clock.visible) return Step(clock = clock)
        return Step(
            visible = true,
            offerStealthSwitch = !alreadyStealth,
            suggestOtherNetwork = alreadyStealth,
            clock = clock,
        )
    }
}
