package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCheckLogTest {

    @Test
    fun peerCheckLineKeepsOutcomeCodeViaAndHandle() {
        assertEquals(
            "peer check result=PEER_GONE code=410 via=underlying handle=42",
            PeerCheckLog.line("PEER_GONE", 410, "underlying", 42L),
        )
        assertEquals(
            "peer check result=NETWORK_ERROR code=0 via=tunnel handle=none",
            PeerCheckLog.line("NETWORK_ERROR", 0, "tunnel", null),
        )
    }

    @Test
    fun secretsAndOtherNetworksCannotBeLogged() {
        val line = PeerCheckLog.line(
            outcome = "PEER_GONE Bearer abc.def 203.0.113.8",
            code = 410,
            via = "cellular",
            handle = -1L,
        )
        assertEquals("peer check result=NETWORK_ERROR code=410 via=tunnel handle=none", line)
        assertFalse(line.contains("Bearer"))
        assertFalse(line.contains("203.0.113.8"))
        assertFalse(line.contains("cellular"))
        assertFalse(PeerCheckLog.reprovision("token=abc 10.1.2.3", 500).contains("token"))
        assertFalse(PeerCheckLog.reprovision("token=abc 10.1.2.3", 500).contains("10.1.2.3"))
    }

    @Test
    fun codesStayInTheHttpRange() {
        assertEquals(
            "re-provision attempt outcome=failed code=599",
            PeerCheckLog.reprovision("failed", 900),
        )
        assertEquals(
            "re-provision attempt outcome=ok code=200",
            PeerCheckLog.reprovision("ok", 200),
        )
        assertEquals(
            "re-provision attempt outcome=failed code=0",
            PeerCheckLog.reprovision("nope", -5),
        )
    }

    @Test
    fun snapshotSummaryDropsUnknownFacts() {
        assertEquals(
            "PEER_GONE code=410 via=underlying",
            PeerCheckLog.summary("PEER_GONE", 410, "underlying"),
        )
        assertEquals("—", PeerCheckLog.summary("PrivateKey", 410, "underlying"))
        assertEquals("attempts=2 last=failed code=0", PeerCheckLog.reprovisionSummary(2, "failed", 0))
        assertEquals("attempts=0", PeerCheckLog.reprovisionSummary(0, "", -1))
        assertEquals("attempts=1", PeerCheckLog.reprovisionSummary(1, "203.0.113.8", 200))
    }

    @Test
    fun onlyFinishedCreatesCountAsAttempts() {
        assertTrue(PeerCheckLog.countsAttempt("ok"))
        assertTrue(PeerCheckLog.countsAttempt("failed"))
        assertFalse(PeerCheckLog.countsAttempt("started"))
        assertFalse(PeerCheckLog.countsAttempt("skipped"))
        assertFalse(PeerCheckLog.countsAttempt("PEER_GONE"))
    }
}
