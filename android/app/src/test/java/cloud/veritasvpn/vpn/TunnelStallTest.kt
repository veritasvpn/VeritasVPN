package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStallTest {

    @Test
    fun stalledWhenRxFrozenTxFlowingAndHandshakeAncient() {
        assertTrue(
            TunnelStall.isStalled(
                elapsedMs = 20_000,
                rxDelta = 0,
                txDelta = 500,
                handshakeAgeMs = 200_000,
            ),
        )
    }

    @Test
    fun notStalledWhenWindowNotElapsed() {
        assertFalse(
            TunnelStall.isStalled(
                elapsedMs = 10_000,
                rxDelta = 0,
                txDelta = 500,
                handshakeAgeMs = 200_000,
            ),
        )
    }

    @Test
    fun notStalledWhenRxIncreased() {
        assertFalse(
            TunnelStall.isStalled(
                elapsedMs = 20_000,
                rxDelta = 100,
                txDelta = 500,
                handshakeAgeMs = 200_000,
            ),
        )
    }

    @Test
    fun notStalledWhenTxAlsoFrozen() {
        assertFalse(
            TunnelStall.isStalled(
                elapsedMs = 20_000,
                rxDelta = 0,
                txDelta = 0,
                handshakeAgeMs = 200_000,
            ),
        )
    }

    @Test
    fun notStalledWhenHandshakeIsRecent() {
        assertFalse(
            TunnelStall.isStalled(
                elapsedMs = 20_000,
                rxDelta = 0,
                txDelta = 500,
                handshakeAgeMs = 60_000,
            ),
        )
    }

    @Test
    fun notStalledAtExactlyHandshakeStaleThreshold() {
        assertFalse(
            TunnelStall.isStalled(
                elapsedMs = 20_000,
                rxDelta = 0,
                txDelta = 500,
                handshakeAgeMs = TunnelStall.HANDSHAKE_STALE_MS,
            ),
        )
    }

    @Test
    fun stalledJustPastHandshakeStaleThreshold() {
        assertTrue(
            TunnelStall.isStalled(
                elapsedMs = 20_000,
                rxDelta = 0,
                txDelta = 1,
                handshakeAgeMs = TunnelStall.HANDSHAKE_STALE_MS + 1,
            ),
        )
    }

    @Test
    fun backoffDoublesUpToMax() {
        assertEquals(60_000L, TunnelStall.nextBackoff(30_000L))
        assertEquals(120_000L, TunnelStall.nextBackoff(60_000L))
        assertEquals(120_000L, TunnelStall.nextBackoff(120_000L))
        assertEquals(120_000L, TunnelStall.nextBackoff(200_000L))
    }

    @Test
    fun backoffResetWhenTrafficResumes() {
        assertTrue(TunnelStall.shouldResetBackoff(rxDelta = 100, handshakeAgeMs = 200_000))
        assertTrue(TunnelStall.shouldResetBackoff(rxDelta = 0, handshakeAgeMs = 60_000))
        assertTrue(TunnelStall.shouldResetBackoff(rxDelta = 100, handshakeAgeMs = 60_000))
    }

    @Test
    fun backoffNotResetWhenStalled() {
        assertFalse(TunnelStall.shouldResetBackoff(rxDelta = 0, handshakeAgeMs = 200_000))
    }

    @Test
    fun idleOnStableNetworkDoesNotTriggerStall() {
        assertFalse(
            TunnelStall.isStalled(
                elapsedMs = 20_000,
                rxDelta = 0,
                txDelta = 0,
                handshakeAgeMs = 200_000,
            ),
        )
    }
}
