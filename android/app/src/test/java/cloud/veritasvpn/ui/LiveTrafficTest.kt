package cloud.veritasvpn.ui

import cloud.veritasvpn.support.formatHandshakeAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrafficTest {

    @Test
    fun reconnectingHidesLocalCountersAndKeepsHandshakeAge() {
        assertEquals("STATS", liveStatsTitle(reconnecting = true))
        assertEquals("—", presentedTransferBytes(2_097_152L, reconnecting = true))
        assertFalse(transferCountersAreLive(reconnecting = true))
        val now = 1_700_000_060_000L
        assertEquals("12s ago", formatHandshakeAge(now - 12_000L, now))
    }

    @Test
    fun connectedSessionShowsTheCounters() {
        assertEquals("LIVE STATS", liveStatsTitle(reconnecting = false))
        assertEquals("100 B", presentedTransferBytes(100L, reconnecting = false))
        assertTrue(transferCountersAreLive(reconnecting = false))
        assertEquals("—", formatHandshakeAge(0L, 1_700_000_060_000L))
    }
}
