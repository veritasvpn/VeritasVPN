package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StealthBackoffTest {

    @Test
    fun sequenceStartsAtInitialAndDoubles() {
        val values = StealthBackoff.sequence().take(6).toList()
        assertEquals(2_000L, values[0])
        assertEquals(4_000L, values[1])
        assertEquals(8_000L, values[2])
        assertEquals(16_000L, values[3])
        assertEquals(30_000L, values[4])
        assertEquals(30_000L, values[5])
    }

    @Test
    fun sequenceCapsAtMax() {
        val values = StealthBackoff.sequence().take(20).toList()
        assertTrue(values.all { it <= StealthBackoff.MAX_MS })
        assertTrue(values.drop(4).all { it == StealthBackoff.MAX_MS })
    }

    @Test
    fun newSequenceStartsFromInitial() {
        val first = StealthBackoff.sequence().take(3).toList()
        val second = StealthBackoff.sequence().take(3).toList()
        assertEquals(first, second)
        assertEquals(StealthBackoff.INITIAL_MS, first[0])
    }
}
