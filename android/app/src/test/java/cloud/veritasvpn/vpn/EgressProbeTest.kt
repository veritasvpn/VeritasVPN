package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.Socket

class EgressProbeTest {
    @Test
    fun endpointsAreTheThreePublicEchoHosts() {
        assertEquals(
            listOf(
                "https://api.ipify.org",
                "https://ifconfig.me/ip",
                "https://icanhazip.com",
            ),
            EgressProbe.ENDPOINTS,
        )
        assertTrue(EgressProbe.ENDPOINTS.all { EgressProbe.allowsUrl(it) })
        assertFalse(EgressProbe.allowsUrl("http://api.ipify.org"))
        assertFalse(EgressProbe.allowsUrl("https://example.com"))
        assertFalse(EgressProbe.allowsHost("ifconfig.co"))
    }

    @Test
    fun probeRequiresVpnNetworkAndNeverFallsBack() {
        assertFalse(EgressProbe.mayProbe(vpnNetworkAvailable = false))
        assertTrue(EgressProbe.mayProbe(vpnNetworkAvailable = true))
        assertFalse(EgressProbe.allowsPhysicalFallback())
    }

    @Test
    fun choosesOnlyAnUnambiguousVpnNetwork() {
        val ours = EgressProbe.Candidate(1L, ownedByApp = true)
        val other = EgressProbe.Candidate(2L, ownedByApp = false)
        val unknown = EgressProbe.Candidate(3L, ownedByApp = null)
        assertEquals(1L, EgressProbe.choose(listOf(other, ours)))
        assertNull(EgressProbe.choose(listOf(ours, ours.copy(key = 4L, ownedByApp = true))))
        assertEquals(3L, EgressProbe.choose(listOf(unknown)))
        assertNull(EgressProbe.choose(listOf(unknown, unknown.copy(key = 5L))))
        assertNull(EgressProbe.choose(emptyList()))
    }

    @Test
    fun parseEchoKeepsAPublicAddressOnly() {
        assertEquals("203.0.113.10", EgressProbe.parseEcho("203.0.113.10\n"))
        val v6 = java.net.InetAddress.getByName("2001:db8::1").hostAddress
        assertEquals(v6, EgressProbe.parseEcho("2001:db8::1"))
        assertNull(EgressProbe.parseEcho("10.1.2.3"))
        assertNull(EgressProbe.parseEcho("127.0.0.1"))
        assertNull(EgressProbe.parseEcho("<html>203.0.113.10</html>"))
        assertNull(EgressProbe.parseEcho("not an address"))
        assertNull(EgressProbe.parseEcho(""))
    }

    @Test
    fun socketFactoryBindsBeforeConnectAndClosesOnFailure() {
        var binds = 0
        val socket = EgressProbe.socketFactory { binds += 1 }.createSocket()
        try {
            assertEquals(1, binds)
            assertFalse(socket.isConnected)
        } finally {
            socket.close()
        }

        assertThrows(IOException::class.java) {
            EgressProbe.socketFactory { throw IOException("bind") }.createSocket()
        }
        var leaked: Socket? = null
        assertThrows(IOException::class.java) {
            EgressProbe.socketFactory { sock ->
                leaked = sock
                throw IllegalStateException("bind")
            }.createSocket()
        }
        assertTrue(leaked!!.isClosed)
    }

    @Test
    fun serviceBindsEgressToTheVpnAndKeepsStealthExclusion() {
        val service = kotlinSource("VeritasVpnService.kt")
        val race = slice(service, "fun raceEgressEndpoints", "fun vpnNetworkForProbe")
        assertTrue(race.indexOf("allowsPhysicalFallback()") < race.indexOf("vpnNetworkForProbe()"))
        assertTrue(race.indexOf("vpnNetworkForProbe()") < race.indexOf("EgressProbe.client"))
        assertTrue(race.contains("?: return@coroutineScope null"))
        assertTrue(race.contains("EgressProbe.bind"))
        assertTrue(race.contains("EgressProbe.fetch"))
        assertFalse(race.contains("ApiClient"))
        assertFalse(race.contains("protect("))
        val open = slice(service, "fun openInterface", "fun openLockdownHold")
        assertTrue(open.contains("addDisallowedApplication(packageName)"))
        val watch = slice(service, "fun registerVpnNetworkWatch", "fun unregisterVpnNetworkWatch")
        assertTrue(watch.contains("registerNetworkCallback"))
        assertFalse(watch.contains("requestNetwork"))
        assertFalse(watch.contains("bindProcessToNetwork"))
        assertFalse(service.contains("logging-interceptor"))
    }

    private fun slice(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, from + start.length)
        assertTrue("$start … $end", from >= 0 && to > from)
        return source.substring(from, to)
    }

    private fun kotlinSource(name: String): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("android/app/src/main/java"),
        )
        val file = roots.firstNotNullOfOrNull { root ->
            root.walk().firstOrNull { it.isFile && it.name == name }
        } ?: error("$name not found from ${File(".").absolutePath}")
        return file.readText()
    }
}
