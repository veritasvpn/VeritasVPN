package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointSelectorTest {

    @Test
    fun lanUnderlaySelectsLanPortExactly() {
        val chosen = EndpointSelector.choose(
            current = "192.168.0.6:51820",
            lan = "192.168.0.6:51820",
            wan = "170.51.31.139:443",
            underlayIpv4s = listOf("192.168.0.14"),
        )
        assertEquals("192.168.0.6:51820", chosen)
    }

    @Test
    fun publicUnderlaySelectsWanPortExactly() {
        val chosen = EndpointSelector.choose(
            current = "192.168.0.6:51820",
            lan = "192.168.0.6:51820",
            wan = "170.51.31.139:443",
            underlayIpv4s = listOf("10.64.0.2"),
        )
        assertEquals("170.51.31.139:443", chosen)
    }

    @Test
    fun leavingLanDoesNotKeepPrivateEndpoint() {
        val chosen = EndpointSelector.choose(
            current = "192.168.0.6:51820",
            lan = "192.168.0.6:51820",
            wan = "170.51.31.139:443",
            underlayIpv4s = listOf("100.64.1.8"),
        )
        assertEquals("170.51.31.139:443", chosen)
    }

    @Test
    fun joiningLanSwitchesFromWanToLan() {
        val chosen = EndpointSelector.choose(
            current = "170.51.31.139:443",
            lan = "192.168.0.6:51820",
            wan = "170.51.31.139:443",
            underlayIpv4s = listOf("192.168.0.5"),
        )
        assertEquals("192.168.0.6:51820", chosen)
    }

    @Test
    fun privateCurrentIsLanFallbackWhenApiOmitsLan() {
        val chosen = EndpointSelector.choose(
            current = "192.168.0.6:51820",
            lan = null,
            wan = "170.51.31.139:443",
            underlayIpv4s = listOf("192.168.0.3"),
        )
        assertEquals("192.168.0.6:51820", chosen)
    }

    @Test
    fun emptyUnderlayKeepsCurrent() {
        val chosen = EndpointSelector.choose(
            current = "192.168.0.6:51820",
            lan = "192.168.0.6:51820",
            wan = "170.51.31.139:443",
            underlayIpv4s = emptyList(),
        )
        assertEquals("192.168.0.6:51820", chosen)
    }

    @Test
    fun neverSynthesizesWanHostWithLanPort() {
        val chosen = EndpointSelector.choose(
            current = "192.168.0.6:51820",
            lan = "192.168.0.6:51820",
            wan = "170.51.31.139:443",
            underlayIpv4s = listOf("8.8.8.8"),
        )
        assertEquals("170.51.31.139:443", chosen)
    }

    @Test
    fun sameSlash24DetectsLanNeighbors() {
        assertTrue(EndpointSelector.sameIpv4Slash24("192.168.0.6", "192.168.0.3"))
        assertFalse(EndpointSelector.sameIpv4Slash24("192.168.0.6", "192.168.1.3"))
    }

    @Test
    fun cafeSlash24SelectsLanIfAskedSoServiceMustNotCallChooseOnFirstBind() {
        // choose() cannot tell the node LAN from any other 192.168.0.0/24.
        // VeritasVpnService must not call this until the underlay fingerprint
        // actually changes; otherwise a WAN connect on cafe Wi‑Fi is swapped
        // to 192.168.0.6:51820 and browsing blackholes.
        val chosen = EndpointSelector.choose(
            current = "170.51.31.139:443",
            lan = "192.168.0.6:51820",
            wan = "170.51.31.139:443",
            underlayIpv4s = listOf("192.168.0.10"),
        )
        assertEquals("192.168.0.6:51820", chosen)
    }

    @Test
    fun replaceEndpointRewritesOnlyThePeerLine() {
        val config = """
            [Interface]
            Address = 10.0.0.2/32
            DNS = 10.0.0.1

            [Peer]
            Endpoint = 192.168.0.6:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent() + "\n"
        val updated = EndpointSelector.replaceEndpoint(config, "170.51.31.139:443")
        assertEquals("170.51.31.139:443", EndpointSelector.endpointFromConfig(updated))
        assertTrue(updated.contains("Address = 10.0.0.2/32"))
        assertFalse(updated.contains("192.168.0.6:51820"))
    }
}
