package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRebuildTest {

    @Test
    fun noHandshakeButPeerAliveRebuildsOnTheSameNetwork() {
        assertEquals(
            TunnelRebuild.RecoveryStep.REBUILD,
            decide(
                peer = PeerCheckOutcome.Outcome.PEER_ALIVE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = false,
            ),
        )
        assertTrue(
            TunnelRebuild.shouldRebuild(
                PeerCheckOutcome.Outcome.PEER_ALIVE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
            ),
        )
    }

    @Test
    fun networkErrorDoesNotBlockARebuild() {
        assertEquals(
            TunnelRebuild.RecoveryStep.REBUILD,
            decide(
                peer = PeerCheckOutcome.Outcome.NETWORK_ERROR,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = false,
            ),
        )
        assertEquals(
            TunnelRebuild.RecoveryStep.REBUILD,
            decide(
                peer = PeerCheckOutcome.Outcome.NETWORK_ERROR,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
            ),
        )
    }

    @Test
    fun handshakeSuccessStopsTheRecoveryLoop() {
        assertEquals(
            TunnelRebuild.RecoveryStep.STOP,
            decide(
                peer = PeerCheckOutcome.Outcome.PEER_ALIVE,
                handshakeCompleted = true,
                probeRoundCompleted = true,
                rebuilding = true,
                defaultHandle = 42L,
                trackedHandle = 42L,
            ),
        )
    }

    @Test
    fun noHandshakeWaitsThenRebuildsAgain() {
        assertEquals(
            TunnelRebuild.RecoveryStep.REBUILD,
            decide(
                peer = PeerCheckOutcome.Outcome.PEER_ALIVE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = false,
                defaultHandle = 42L,
                trackedHandle = 42L,
            ),
        )
        assertEquals(
            TunnelRebuild.RecoveryStep.WAIT,
            decide(
                peer = PeerCheckOutcome.Outcome.PEER_ALIVE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = true,
                defaultHandle = 42L,
                trackedHandle = 42L,
            ),
        )
        assertEquals(
            TunnelRebuild.RecoveryStep.REBUILD,
            decide(
                peer = PeerCheckOutcome.Outcome.NETWORK_ERROR,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = false,
                defaultHandle = 42L,
                trackedHandle = 42L,
            ),
        )
    }

    @Test
    fun deletedPeerReprovisionsInsteadOfRebuilding() {
        assertEquals(
            TunnelRebuild.RecoveryStep.REPROVISION,
            decide(
                peer = PeerCheckOutcome.Outcome.PEER_GONE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = true,
            ),
        )
        assertFalse(
            TunnelRebuild.shouldRebuild(
                PeerCheckOutcome.Outcome.PEER_GONE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
            ),
        )
    }

    @Test
    fun doesNotRebuildBeforeTheProbeRoundOrWithoutAPeerResult() {
        assertEquals(
            TunnelRebuild.RecoveryStep.WAIT,
            decide(
                peer = PeerCheckOutcome.Outcome.PEER_ALIVE,
                handshakeCompleted = false,
                probeRoundCompleted = false,
                rebuilding = false,
            ),
        )
        assertEquals(
            TunnelRebuild.RecoveryStep.WAIT,
            decide(
                peer = null,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = false,
            ),
        )
        assertFalse(
            TunnelRebuild.shouldRebuild(
                null,
                handshakeCompleted = false,
                probeRoundCompleted = true,
            ),
        )
    }

    @Test
    fun systemDefaultChangeResetsAndALostSessionStops() {
        assertEquals(
            TunnelRebuild.RecoveryStep.RESET,
            decide(
                peer = PeerCheckOutcome.Outcome.PEER_ALIVE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = true,
                defaultHandle = 20L,
                trackedHandle = 10L,
            ),
        )
        assertEquals(
            TunnelRebuild.RecoveryStep.RESET,
            decide(
                peer = PeerCheckOutcome.Outcome.PEER_ALIVE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                defaultHandle = null,
                trackedHandle = 10L,
            ),
        )
        assertEquals(
            TunnelRebuild.RecoveryStep.STOP,
            decide(
                sessionActive = false,
                peer = PeerCheckOutcome.Outcome.PEER_ALIVE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
            ),
        )
    }

    @Test
    fun onlyTheSystemDefaultIsPinnedOrBound() {
        val systemDefault = 20L
        val cellular = 99L
        val pinned = TunnelRebuild.pinHandle(systemDefault)
        val bound = TunnelRebuild.pinHandle(systemDefault)
        assertEquals(systemDefault, pinned)
        assertEquals(bound, pinned)
        assertFalse(pinned == cellular)
        assertNull(TunnelRebuild.pinHandle(null))
        assertFalse(TunnelRebuild.socketNeedsRebind(boundHandle = systemDefault, systemDefaultHandle = systemDefault))
        assertTrue(TunnelRebuild.socketNeedsRebind(boundHandle = cellular, systemDefaultHandle = systemDefault))
        assertTrue(TunnelRebuild.socketNeedsRebind(boundHandle = null, systemDefaultHandle = systemDefault))
        assertFalse(TunnelRebuild.socketNeedsRebind(boundHandle = cellular, systemDefaultHandle = null))
    }

    @Test
    fun newTunIsEstablishedBeforeTheOldOneIsClosed() {
        val order = mutableListOf<String>()
        val created = TunnelRebuild.replaceWhileHeld(
            establishNew = {
                order += "establish"
                "tun"
            },
            closePrevious = {
                order += "close"
            },
        )
        assertEquals("tun", created)
        assertEquals(listOf("establish", "close"), order)

        val closed = mutableListOf<String>()
        val failed = TunnelRebuild.replaceWhileHeld(
            establishNew = { null },
            closePrevious = { closed += "close" },
        )
        assertNull(failed)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun backoffCapsAtSixtySeconds() {
        var wait = TunnelRebuild.MIN_BACKOFF_MS
        assertEquals(5_000L, wait)
        wait = TunnelRebuild.backoffAfter(wait, handshakeSucceeded = false, defaultNetworkChanged = false)
        assertEquals(10_000L, wait)
        wait = TunnelRebuild.backoffAfter(wait, handshakeSucceeded = false, defaultNetworkChanged = false)
        assertEquals(20_000L, wait)
        wait = TunnelRebuild.backoffAfter(wait, handshakeSucceeded = false, defaultNetworkChanged = false)
        assertEquals(40_000L, wait)
        wait = TunnelRebuild.backoffAfter(wait, handshakeSucceeded = false, defaultNetworkChanged = false)
        assertEquals(60_000L, wait)
        wait = TunnelRebuild.backoffAfter(wait, handshakeSucceeded = false, defaultNetworkChanged = false)
        assertEquals(60_000L, wait)
    }

    @Test
    fun backoffResetsOnHandshakeOrSystemDefaultChange() {
        val capped = 60_000L
        assertEquals(
            TunnelRebuild.MIN_BACKOFF_MS,
            TunnelRebuild.backoffAfter(
                capped,
                handshakeSucceeded = true,
                defaultNetworkChanged = false,
            ),
        )
        assertEquals(
            TunnelRebuild.MIN_BACKOFF_MS,
            TunnelRebuild.backoffAfter(
                capped,
                handshakeSucceeded = false,
                defaultNetworkChanged = true,
            ),
        )
    }

    private fun decide(
        sessionActive: Boolean = true,
        peer: PeerCheckOutcome.Outcome?,
        handshakeCompleted: Boolean,
        probeRoundCompleted: Boolean,
        rebuilding: Boolean,
        waiting: Boolean = false,
        defaultHandle: Long? = 42L,
        trackedHandle: Long? = null,
    ): TunnelRebuild.RecoveryStep = TunnelRebuild.decide(
        sessionActive = sessionActive,
        peer = peer,
        handshakeCompleted = handshakeCompleted,
        probeRoundCompleted = probeRoundCompleted,
        rebuilding = rebuilding,
        waiting = waiting,
        defaultHandle = defaultHandle,
        trackedHandle = trackedHandle,
    )
}
