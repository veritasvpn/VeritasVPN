package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReprovisionBackoffTest {

    @Test
    fun delayGrowsFromFiveSecondsToSixty() {
        var delay = ReprovisionBackoff.INITIAL_MS
        assertEquals(5_000L, delay)
        delay = ReprovisionBackoff.next(delay)
        assertEquals(10_000L, delay)
        delay = ReprovisionBackoff.next(delay)
        assertEquals(20_000L, delay)
        delay = ReprovisionBackoff.next(delay)
        assertEquals(40_000L, delay)
        delay = ReprovisionBackoff.next(delay)
        assertEquals(60_000L, delay)
        delay = ReprovisionBackoff.next(delay)
        assertEquals(60_000L, delay)
    }

    @Test
    fun failureSchedulesRetryWhileSessionIntended() {
        assertTrue(
            ReprovisionBackoff.shouldScheduleRetry(
                succeeded = false,
                generationMatches = true,
                sessionIntended = true,
                disconnectInProgress = false,
            ),
        )
    }

    @Test
    fun successDoesNotScheduleRetry() {
        assertFalse(
            ReprovisionBackoff.shouldScheduleRetry(
                succeeded = true,
                generationMatches = true,
                sessionIntended = true,
                disconnectInProgress = false,
            ),
        )
    }

    @Test
    fun staleOrEndedSessionDoesNotScheduleRetry() {
        assertFalse(
            ReprovisionBackoff.shouldScheduleRetry(
                succeeded = false,
                generationMatches = false,
                sessionIntended = true,
                disconnectInProgress = false,
            ),
        )
        assertFalse(
            ReprovisionBackoff.shouldScheduleRetry(
                succeeded = false,
                generationMatches = true,
                sessionIntended = false,
                disconnectInProgress = false,
            ),
        )
        assertFalse(
            ReprovisionBackoff.shouldScheduleRetry(
                succeeded = false,
                generationMatches = true,
                sessionIntended = true,
                disconnectInProgress = true,
            ),
        )
    }
}
