package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LockdownHoldTest {
    @Test
    fun holdDisallowsThisAppAndKeepsBypassApps() {
        val packages = LockdownHold.disallowedPackages(
            "cloud.veritasvpn",
            setOf("com.example.mail", " cloud.veritasvpn "),
        )
        assertEquals(setOf("cloud.veritasvpn", "com.example.mail"), packages)
    }

    @Test
    fun holdRoutesAreInsideTheTun() {
        assertEquals(32, LockdownHold.IPV4_PREFIX)
        assertEquals("0.0.0.0", LockdownHold.IPV4_ROUTE)
        assertEquals("::", LockdownHold.IPV6_ROUTE)
        assertFalse(LockdownHold.DNS == LockdownHold.IPV4_ADDRESS)
    }

    @Test
    fun serviceReplacesTheSignedOutTunWithAnAppExemptHold() {
        val service = kotlinSource("VeritasVpnService.kt")
        val release = slice(service, "fun releaseTunnelOnce", "fun turnUserspaceOff()")
        assertTrue(release.contains("openLockdownHold(expectedGen, alreadyLockdown = true)"))
        assertTrue(release.contains("turnUserspaceOffLocked"))
        assertFalse(release.contains("setState"))
        assertFalse(release.contains("stopSelf("))
        val hold = slice(service, "fun establishLockdownHoldLocked", "fun closeTunKeeper")
        assertTrue(hold.contains("addDisallowedApplication(own)"))
        assertTrue(hold.contains("LockdownHold.IPV4_ROUTE"))
        assertTrue(hold.contains("LockdownHold.IPV6_ROUTE"))
        assertFalse(hold.contains("wgTurnOn"))
        assertFalse(hold.contains(".bindSocket("))
        val code = service
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertEquals(2, Regex("""\.bindSocket\(""").findAll(code).count())
        assertFalse(code.contains("setUnderlyingNetworks(arrayOf"))
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
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }
}
