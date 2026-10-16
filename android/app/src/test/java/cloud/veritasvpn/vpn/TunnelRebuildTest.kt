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
    fun controlClientMayBindOnlyTheCurrentSystemDefault() {
        val systemDefault = 20L
        val other = 99L
        val pinned = TunnelRebuild.pinHandle(systemDefault)
        assertEquals(systemDefault, pinned)
        assertFalse(pinned == other)
        assertNull(TunnelRebuild.pinHandle(null))
        val reaction = TunnelRebuild.onSystemDefault(
            watch = TunnelRebuild.DefaultWatch(seen = true, handle = other),
            nextHandle = systemDefault,
            controlClientOpen = true,
            controlClientHandle = other,
        )
        assertTrue(reaction.dataPlaneBindSocket)
        assertEquals(TunnelRebuild.DataPlaneAction.PROTECT_THEN_BIND_DEFAULT, reaction.dataPlane)
        assertTrue(TunnelRebuild.dataPlaneMayBind(systemDefault, systemDefault))
        assertFalse(TunnelRebuild.dataPlaneMayBind(systemDefault, other))
        assertFalse(TunnelRebuild.shouldReprovisionFreshTunnel(1))
        assertTrue(TunnelRebuild.shouldReprovisionFreshTunnel(TunnelRebuild.REBUILDS_BEFORE_FRESH_TUNNEL))
        assertEquals(systemDefault, TunnelRebuild.pinHandle(systemDefault))
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
    fun signedOutStopsInsteadOfRebuilding() {
        assertEquals(
            TunnelRebuild.RecoveryStep.STOP,
            decide(
                peer = PeerCheckOutcome.Outcome.SIGNED_OUT,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = false,
            ),
        )
        assertFalse(
            TunnelRebuild.shouldRebuild(
                PeerCheckOutcome.Outcome.SIGNED_OUT,
                handshakeCompleted = false,
                probeRoundCompleted = true,
            ),
        )
    }

    @Test
    fun failedDupClosesThePreviousKeeper() {
        assertTrue(
            TunnelRebuild.shouldClosePreviousKeeper(
                newTunEstablished = true,
                duplicateSucceeded = false,
            ),
        )
        assertFalse(
            TunnelRebuild.shouldClosePreviousKeeper(
                newTunEstablished = false,
                duplicateSucceeded = false,
            ),
        )
        assertFalse(
            TunnelRebuild.shouldClosePreviousKeeper(
                newTunEstablished = true,
                duplicateSucceeded = true,
            ),
        )
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
    fun defaultNetworkChangeDropsNetworkErrorAndKeepsSignedOut() {
        assertTrue(TunnelRebuild.shouldClearCachedPeerOutcome(PeerCheckOutcome.Outcome.NETWORK_ERROR))
        assertTrue(TunnelRebuild.shouldClearCachedPeerOutcome(PeerCheckOutcome.Outcome.REFRESH_AND_RETRY))
        assertTrue(TunnelRebuild.shouldClearCachedPeerOutcome(PeerCheckOutcome.Outcome.PEER_GONE))
        assertTrue(TunnelRebuild.shouldClearCachedPeerOutcome(PeerCheckOutcome.Outcome.PEER_ALIVE))
        assertFalse(TunnelRebuild.shouldClearCachedPeerOutcome(PeerCheckOutcome.Outcome.SIGNED_OUT))
        assertFalse(TunnelRebuild.shouldClearCachedPeerOutcome(null))
    }

    @Test
    fun failedRebuildBypassesThePeerCheckRateLimit() {
        assertTrue(TunnelRebuild.bypassPeerCheckRateLimit(failedRebuildRound = true))
        assertFalse(TunnelRebuild.bypassPeerCheckRateLimit(failedRebuildRound = false))
    }

    @Test
    fun threeRebuildsOnTheSameDefaultForceAnUnderlyingCheck() {
        var streak = TunnelRebuild.RebuildStreak(handle = null, count = 0)
        repeat(2) {
            streak = TunnelRebuild.noteFailedRebuild(streak, 42L)
            assertFalse(TunnelRebuild.shouldForceUnderlyingPeerCheck(streak.count))
        }
        streak = TunnelRebuild.noteFailedRebuild(streak, 42L)
        assertEquals(3, streak.count)
        assertEquals(42L, streak.handle)
        assertTrue(TunnelRebuild.shouldForceUnderlyingPeerCheck(streak.count))
        assertEquals(TunnelRebuild.REBUILDS_BEFORE_UNDERLYING_CHECK, 3)

        val moved = TunnelRebuild.noteFailedRebuild(streak, 99L)
        assertEquals(99L, moved.handle)
        assertEquals(1, moved.count)
        assertFalse(TunnelRebuild.shouldForceUnderlyingPeerCheck(moved.count))

        val gone = TunnelRebuild.noteFailedRebuild(moved, null)
        assertNull(gone.handle)
        assertEquals(1, gone.count)
        assertFalse(TunnelRebuild.shouldForceUnderlyingPeerCheck(gone.count))
    }

    @Test
    fun forcedCheckStillReprovisionsOnlyWhenThePeerIsGone() {
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
        assertEquals(
            TunnelRebuild.RecoveryStep.REPROVISION,
            decide(
                peer = PeerCheckOutcome.Outcome.PEER_GONE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = true,
                defaultHandle = 42L,
                trackedHandle = 42L,
            ),
        )
        assertFalse(
            PeerRecovery.plan(PeerCheckOutcome.Outcome.NETWORK_ERROR).reprovision,
        )
        assertTrue(
            PeerRecovery.plan(PeerCheckOutcome.Outcome.PEER_GONE).reprovision,
        )
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
