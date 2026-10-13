package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectDecisionsTest {

    @Test
    fun restoreRefusesWithoutPeerId() {
        assertFalse(ReconnectDecisions.shouldRestoreSession(hasConfig = true, hasPeerId = false))
    }

    @Test
    fun restoreRefusesWithoutConfig() {
        assertFalse(ReconnectDecisions.shouldRestoreSession(hasConfig = false, hasPeerId = true))
    }

    @Test
    fun restoreProceedsWithBoth() {
        assertTrue(ReconnectDecisions.shouldRestoreSession(hasConfig = true, hasPeerId = true))
    }

    @Test
    fun restoreRefusesWithNeither() {
        assertFalse(ReconnectDecisions.shouldRestoreSession(hasConfig = false, hasPeerId = false))
    }

    @Test
    fun timeoutDoesNotDeleteWhenEstablishedMarkerSet() {
        assertFalse(
            ReconnectDecisions.shouldTimeoutDelete(
                connecting = true,
                hadEstablishedSession = false,
                sessionEstablishedAt = 1_000L,
                awaitingVpnConsent = false,
            ),
        )
    }

    @Test
    fun timeoutDoesNotDeleteWhenHadEstablishedSession() {
        assertFalse(
            ReconnectDecisions.shouldTimeoutDelete(
                connecting = true,
                hadEstablishedSession = true,
                sessionEstablishedAt = 0L,
                awaitingVpnConsent = false,
            ),
        )
    }

    @Test
    fun timeoutDeletesWhenNoEstablishedMarker() {
        assertTrue(
            ReconnectDecisions.shouldTimeoutDelete(
                connecting = true,
                hadEstablishedSession = false,
                sessionEstablishedAt = 0L,
                awaitingVpnConsent = false,
            ),
        )
    }

    @Test
    fun timeoutDoesNotDeleteWhenNotConnecting() {
        assertFalse(
            ReconnectDecisions.shouldTimeoutDelete(
                connecting = false,
                hadEstablishedSession = false,
                sessionEstablishedAt = 0L,
                awaitingVpnConsent = false,
            ),
        )
    }

    @Test
    fun timeoutDoesNotDeleteWhenAwaitingConsent() {
        assertFalse(
            ReconnectDecisions.shouldTimeoutDelete(
                connecting = true,
                hadEstablishedSession = false,
                sessionEstablishedAt = 0L,
                awaitingVpnConsent = true,
            ),
        )
    }

    @Test
    fun activityReconnectsWhenPeerGoneAndServiceNotBusy() {
        assertTrue(
            ReconnectDecisions.shouldActivityReconnect(
                userWantsConnected = true,
                connected = false,
                connecting = false,
                peerGoneAt = 1_000L,
                reprovisionInProgressAt = 0L,
                nowMs = 2_000L,
                hasSavedSession = false,
            ),
        )
    }

    @Test
    fun activitySkipsReconnectWhenServiceReprovisioning() {
        assertFalse(
            ReconnectDecisions.shouldActivityReconnect(
                userWantsConnected = true,
                connected = false,
                connecting = false,
                peerGoneAt = 1_000L,
                reprovisionInProgressAt = 1_500L,
                nowMs = 2_000L,
                hasSavedSession = false,
            ),
        )
    }

    @Test
    fun activityReconnectsWhenReprovisionExpired() {
        assertTrue(
            ReconnectDecisions.shouldActivityReconnect(
                userWantsConnected = true,
                connected = false,
                connecting = false,
                peerGoneAt = 1_000L,
                reprovisionInProgressAt = 1_500L,
                nowMs = 70_000L,
                hasSavedSession = false,
            ),
        )
    }

    @Test
    fun activitySkipsReconnectWhenPeerNotGone() {
        assertFalse(
            ReconnectDecisions.shouldActivityReconnect(
                userWantsConnected = true,
                connected = false,
                connecting = false,
                peerGoneAt = 0L,
                reprovisionInProgressAt = 0L,
                nowMs = 2_000L,
                hasSavedSession = false,
            ),
        )
    }

    @Test
    fun activitySkipsReconnectWhenSessionRestored() {
        assertFalse(
            ReconnectDecisions.shouldActivityReconnect(
                userWantsConnected = true,
                connected = false,
                connecting = false,
                peerGoneAt = 1_000L,
                reprovisionInProgressAt = 0L,
                nowMs = 2_000L,
                hasSavedSession = true,
            ),
        )
    }
}
