package cloud.veritasvpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeKickTest {
    @Test
    fun keepaliveIsStrippedSoTheFirstHandshakeWaitsUntilAfterProtect() {
        val userspace = """
            private_key=abc
            public_key=def
            endpoint=203.0.113.10:443
            persistent_keepalive_interval=25
            allowed_ip=0.0.0.0/0
        """.trimIndent()
        val stripped = HandshakeKick.withoutKeepalive(userspace)
        assertFalse(stripped.lineSequence().any { HandshakeKick.isKeepaliveLine(it) })
        assertTrue(stripped.contains("endpoint=203.0.113.10:443"))
        assertTrue(stripped.contains("allowed_ip=0.0.0.0/0"))

        val quick = """
            [Peer]
            PublicKey = def
            Endpoint = 203.0.113.10:443
            PersistentKeepalive = 25
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val quiet = HandshakeKick.withoutKeepalive(quick)
        assertFalse(quiet.lineSequence().any { HandshakeKick.isKeepaliveLine(it) })
        assertTrue(quiet.contains("Endpoint = 203.0.113.10:443"))
        assertTrue(HandshakeKick.KEEPALIVE_EVERY_MS in 15_000L..25_000L)
    }
}
