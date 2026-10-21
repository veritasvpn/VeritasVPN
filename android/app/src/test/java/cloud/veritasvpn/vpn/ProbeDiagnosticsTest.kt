package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeDiagnosticsTest {

    @Test
    fun endpointKindIsLanOrWanOnly() {
        assertEquals(
            "WAN",
            ProbeDiagnostics.endpointKind("203.0.113.10:443", "192.168.1.1:51820", "203.0.113.10:443"),
        )
        assertEquals(
            "LAN",
            ProbeDiagnostics.endpointKind("192.168.1.1:51820", "192.168.1.1:51820", "203.0.113.10:443"),
        )
        assertEquals("LAN", ProbeDiagnostics.endpointKind("10.0.0.5:51820", null, null))
        assertEquals("WAN", ProbeDiagnostics.endpointKind("203.0.113.10:443", null, null))
    }

    @Test
    fun probeLineOmitsAddressesEndpointsKeysAndTokens() {
        val line = ProbeDiagnostics.format(
            kind = ProbeDiagnostics.endpointKind("203.0.113.10:51820", "192.168.1.1:51820", "203.0.113.10:443"),
            networkType = ProbeDiagnostics.networkType(wifi = true, cellular = false, ethernet = false),
            handle = 77L,
            socketBound = true,
        )
        assertEquals(
            "path-adapt hard probe kind=WAN net=WIFI handle=77 bound=true",
            line,
        )
        assertFalse(line.contains("key="))
        assertFalse(line.contains("203.0.113.10"))
        assertFalse(line.contains("192.168.1.1"))
        assertFalse(line.contains("10.7.0.2"))
        assertFalse(line.contains("51820"))
        assertFalse(line.contains("Endpoint"))
        assertFalse(line.contains("PrivateKey"))
        assertFalse(line.contains("Bearer"))
        assertFalse(line.contains("token"))
    }

    @Test
    fun unsafeKindOrNetworkTypeCannotSmuggleAnAddress() {
        val line = ProbeDiagnostics.format(
            kind = "203.0.113.10:51820",
            networkType = "10.1.2.3",
            handle = 1L,
            socketBound = false,
        )
        assertFalse(line.contains("203.0.113.10"))
        assertFalse(line.contains("10.1.2.3"))
        assertFalse(line.contains(":51820"))
        assertFalse(line.contains("key="))
        assertTrue(line.contains("kind=WAN"))
        assertTrue(line.contains("net=OTHER"))
        assertTrue(line.startsWith("path-adapt hard probe kind="))
    }
}
