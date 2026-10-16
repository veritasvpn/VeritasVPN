package cloud.veritasvpn.vpn

import cloud.veritasvpn.support.ConnectTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConnectTimingTest {
    @Test
    fun lockdownReconnectDoesNotWaitForTheBlockedDelete() {
        assertFalse(ConnectTiming.shouldWaitForCleanup(interfaceHeld = true))
        assertTrue(ConnectTiming.shouldWaitForCleanup(interfaceHeld = false))
    }

    @Test
    fun marksStayInsideTheDiagnosticAlphabet() {
        val line = ConnectTiming.line("dns", 1_500)
        assertEquals("connect dns ms=1500", line)
        assertEquals("connect tap ms=0", ConnectTiming.line("tap", -5))
        assertTrue(line.all { it.isLetterOrDigit() || it == ' ' || it in "=._\$:-" })
        assertFalse(ConnectTiming.line("dns", 4).contains("+"))
        assertFalse(ConnectTiming.line("dns", 4).contains(":"))
    }

    @Test
    fun reconnectRecordsEachStepAndSkipsTheCleanupWait() {
        val activity = kotlinSource("MainActivity.kt")
        val start = slice(activity, "fun startConnection(", "override fun onNewIntent")
        assertTrue(start.contains("ConnectTiming.arm"))
        assertTrue(start.contains("shouldWaitForCleanup"))
        assertTrue(start.contains("peerCleanupJob?.cancel()"))
        assertTrue(start.contains("retireProvisioningClient()"))
        assertTrue(start.contains("ConnectTiming.mark(\"skipcleanup\")"))
        assertTrue(start.contains("ConnectTiming.mark(\"provision\")"))
        val skip = slice(start, "peerCleanupJob?.cancel()", "ConnectTiming.mark(\"skipcleanup\")")
        assertTrue(skip.contains("retireProvisioningClient()"))
        val create = slice(activity, "fun createPeer(", "fun postPeerOutsideTunnel(")
        assertTrue(create.contains("ConnectTiming.mark(\"client\")"))
        val delete = slice(activity, "fun deletePeerBestEffort(", "fun clearLocalSessionUi()")
        assertTrue(delete.contains("provisioningClient()"))
        assertTrue(delete.contains("if (held && protected == null) return@launch"))

        val api = kotlinSource("ApiClient.kt")
        val post = slice(api, "suspend fun post(", "suspend fun postFast(")
        assertTrue(post.contains("notingDns"))
        val dns = slice(api, "fun notingDns(", "suspend fun delete(path: String, body:")
        assertTrue(dns.contains("ConnectTiming.mark(\"dns\")"))

        val service = kotlinSource("VeritasVpnService.kt")
        assertTrue(service.contains("ConnectTiming.mark(\"tun\")"))
        assertTrue(service.contains("ConnectTiming.mark(\"handshake\")"))
        val bring = slice(service, "fun bringUp(", "fun switchToStealth(")
        assertTrue(bring.contains("rebindLiveTunnel"))
        assertTrue(bring.contains("kickHandshakeAfterProtect"))
        val kept = slice(bring, "keptTun ->", "followSystemDefault()")
        assertTrue(kept.contains("rebindLiveTunnel(parsed, gen)"))
        val shared = slice(service, "fun retireSharedClient()", "fun tunnelJobs()")
        assertTrue(shared.indexOf("socketCloseScope.launch(Dispatchers.IO)") < shared.indexOf("evictAll"))
        val keptDisconnect = slice(service, "releaseTunnelOnce(disconnectGen, keepInterface = keepInterface)", "broadcastState(false, null)")
        assertTrue(keptDisconnect.contains("retireSharedClient()"))
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
