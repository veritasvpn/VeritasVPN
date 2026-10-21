package cloud.veritasvpn.vpn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ReprovisionFlightTest {

    @Before
    fun reset() {
        ReprovisionFlight.cancelCurrent()
    }

    @After
    fun cleanup() {
        ReprovisionFlight.cancelCurrent()
    }

    @Test
    fun runningClaimRejectsASecondCreate() {
        val persisted = mutableListOf<Long>()
        val first = ReprovisionFlight.tryClaim(0L, 1_000L, replaceWaiting = false) { persisted += it }
        val second = ReprovisionFlight.tryClaim(0L, 1_100L, replaceWaiting = true) { persisted += it }
        assertTrue(first != null)
        assertNull(second)
        assertEquals(listOf(1_000L), persisted)
    }

    @Test
    fun waitingClaimIsReplacedOnlyByTheService() {
        val first = ReprovisionFlight.tryClaim(0L, 1_000L, replaceWaiting = false) { }
        assertTrue(ReprovisionFlight.markWaiting(first!!, 2_000L) { })
        assertNull(ReprovisionFlight.tryClaim(0L, 3_000L, replaceWaiting = false) { })
        val persisted = mutableListOf<Long>()
        val next = ReprovisionFlight.tryClaim(0L, 3_000L, replaceWaiting = true) { persisted += it }
        assertTrue(next != null)
        assertNotEquals(first, next)
        assertEquals(listOf(3_000L), persisted)
    }

    @Test
    fun peerGoneRetriesOnceUnderTheFlightWindow() {
        val now = 30_000L
        assertEquals(
            ReprovisionFlight.Decision.SKIP,
            ReprovisionFlight.decide(ReprovisionFlight.Phase.IDLE, persistedAtMs = 1_000L, nowMs = now),
        )
        assertNull(ReprovisionFlight.tryClaim(1_000L, now, replaceWaiting = true) { })
        val persisted = mutableListOf<Long>()
        val epoch = ReprovisionFlight.tryClaim(
            persistedAtMs = 1_000L,
            nowMs = now,
            replaceWaiting = true,
            peerGone = true,
            upgradeHeld = true,
        ) { persisted += it }
        assertTrue(epoch != null)
        assertEquals(listOf(now), persisted)
        assertNull(
            ReprovisionFlight.tryClaim(
                persistedAtMs = 1_000L,
                nowMs = now + 1_000L,
                replaceWaiting = true,
                peerGone = true,
                upgradeHeld = true,
            ) { },
        )
    }

    @Test
    fun recentPersistedClaimBlocksARestartedProcess() {
        assertEquals(
            ReprovisionFlight.Decision.SKIP,
            ReprovisionFlight.decide(ReprovisionFlight.Phase.IDLE, persistedAtMs = 1_000L, nowMs = 30_000L),
        )
        assertNull(ReprovisionFlight.tryClaim(1_000L, 30_000L, replaceWaiting = true) { })
        assertTrue(ReprovisionFlight.blocksActivity(1_000L, 30_000L))
        val later = ReprovisionFlight.tryClaim(1_000L, 70_000L, replaceWaiting = false) { }
        assertTrue(later != null)
        assertFalse(ReprovisionFlight.persistedBusy(1_000L, 70_000L))
    }

    @Test
    fun releaseDoesNotClearABackoffOrAnOlderEpoch() {
        val epoch = ReprovisionFlight.tryClaim(0L, 1_000L, replaceWaiting = false) { }!!
        assertTrue(ReprovisionFlight.markWaiting(epoch, 2_000L) { })
        var cleared = false
        ReprovisionFlight.release(epoch) { cleared = true }
        assertFalse(cleared)
        assertTrue(ReprovisionFlight.claimWaiting(epoch))
        ReprovisionFlight.cancelCurrent()
        ReprovisionFlight.release(epoch) { cleared = true }
        assertFalse(cleared)
        assertFalse(ReprovisionFlight.claimWaiting(epoch))
    }

    @Test
    fun releaseClearsTheRunningEpoch() {
        val epoch = ReprovisionFlight.tryClaim(0L, 1_000L, replaceWaiting = false) { }!!
        var cleared = false
        ReprovisionFlight.release(epoch) { cleared = true }
        assertTrue(cleared)
        assertFalse(ReprovisionFlight.blocksActivity(0L, 2_000L))
    }

    @Test
    fun onlyOneCallerClaimsTheFlight() {
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val wins = AtomicInteger(0)
        repeat(8) {
            Thread {
                start.await()
                val epoch = ReprovisionFlight.tryClaim(0L, 5_000L, replaceWaiting = false) { }
                if (epoch != null) wins.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, wins.get())
    }
}
