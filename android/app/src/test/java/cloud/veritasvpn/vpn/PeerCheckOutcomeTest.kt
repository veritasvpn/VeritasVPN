package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerCheckOutcomeTest {

    @Test
    fun aliveOn200() {
        assertEquals(
            PeerCheckOutcome.Outcome.PEER_ALIVE,
            PeerCheckOutcome.map(200),
        )
    }

    @Test
    fun refreshAndRetryOn401() {
        assertEquals(
            PeerCheckOutcome.Outcome.REFRESH_AND_RETRY,
            PeerCheckOutcome.map(401),
        )
    }

    @Test
    fun peerGoneOn404() {
        assertEquals(
            PeerCheckOutcome.Outcome.PEER_GONE,
            PeerCheckOutcome.map(404),
        )
    }

    @Test
    fun peerGoneOn410() {
        assertEquals(
            PeerCheckOutcome.Outcome.PEER_GONE,
            PeerCheckOutcome.map(410),
        )
    }

    @Test
    fun networkErrorOnIOException() {
        assertEquals(
            PeerCheckOutcome.Outcome.NETWORK_ERROR,
            PeerCheckOutcome.map(0, networkError = true),
        )
    }

    @Test
    fun networkErrorOnUnknownStatus() {
        assertEquals(
            PeerCheckOutcome.Outcome.NETWORK_ERROR,
            PeerCheckOutcome.map(500),
        )
    }

    @Test
    fun networkErrorOnTimeout() {
        assertEquals(
            PeerCheckOutcome.Outcome.NETWORK_ERROR,
            PeerCheckOutcome.map(0),
        )
    }

    @Test
    fun aliveTakesPrecedenceOverNetworkError() {
        assertEquals(
            PeerCheckOutcome.Outcome.PEER_ALIVE,
            PeerCheckOutcome.map(200, networkError = true),
        )
    }
}
