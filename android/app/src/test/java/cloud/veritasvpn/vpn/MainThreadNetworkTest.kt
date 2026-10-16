package cloud.veritasvpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainThreadNetworkTest {
    @Test
    fun serviceEntryPointsDoNotCloseSocketsOnTheCaller() {
        val service = kotlinSource("VeritasVpnService.kt")
        for (block in listOf(
            slice(service, "override fun onStartCommand", "private fun onAuthRestored"),
            slice(service, "override fun onRevoke()", "override fun onDestroy()"),
            slice(service, "override fun onDestroy()", "override fun getName()"),
            slice(service, "private val underlayCallback", "private val bestMatchingCallback"),
            slice(service, "private val bestMatchingCallback", "fun sessionIntended"),
        )) {
            assertFalse(block.contains("evictAll"))
            assertFalse(block.contains("connectionPool"))
            assertFalse(block.contains("call.cancel"))
            assertFalse(block.contains("transport.stop"))
            assertFalse(block.contains("udpSocket"))
        }
    }

    @Test
    fun controlClientEvictRunsOnTheIoScope() {
        val service = kotlinSource("VeritasVpnService.kt")
        val retire = slice(service, "fun retireControlClient", "fun dropUnderlyingControlClient")
        assertTrue(retire.contains("socketCloseScope.launch(Dispatchers.IO)"))
        assertTrue(retire.indexOf("socketCloseScope.launch") < retire.indexOf("evictAll"))
        assertTrue(retire.contains("dispatcher.cancelAll()"))
        val drop = slice(service, "fun dropUnderlyingControlClient", "fun isUsableUnderlay")
        assertFalse(drop.contains("evictAll"))
        assertFalse(drop.contains("retireSharedClient"))
        assertTrue(drop.contains("retireControlClient"))
        val build = slice(service, "fun underlyingControlClient()", "fun retireControlClient")
        assertFalse(build.contains("evictAll"))
        assertTrue(build.contains("retireControlClient"))
    }

    @Test
    fun endpointCancelAndStealthStopLeaveTheCaller() {
        val service = kotlinSource("VeritasVpnService.kt")
        val cancel = slice(service, "fun cancelEndpointCall()", "fun cancelEndpointFetch()")
        assertTrue(cancel.indexOf("socketCloseScope.launch(Dispatchers.IO)") < cancel.indexOf("call.cancel()"))
        val stealth = slice(service, "fun stopStealthTransport()", "fun cancelStealthWork()")
        assertTrue(stealth.indexOf("socketCloseScope.launch(Dispatchers.IO)") < stealth.indexOf("transport.stop()"))
    }

    @Test
    fun connectingTimeoutUsesTheClearRetryMessage() {
        val activity = kotlinSource("MainActivity.kt")
        assertFalse(activity.contains("Connection timed out"))
        assertFalse(activity.contains("timed out"))
        val waiting = slice(activity, "LaunchedEffect(connecting, awaitingVpnConsent)", "fun requestConnect()")
        assertTrue(waiting.contains("EndpointRefresh.GIVE_UP_MESSAGE"))
        assertEqualsGiveUp(
            EndpointRefresh.displayStatus("Connection timed out. Check your network and try again."),
        )
    }

    @Test
    fun debugBuildLogsMainThreadNetwork() {
        val app = kotlinSource("VeritasVpnApplication.kt")
        val policy = slice(app, "if (BuildConfig.DEBUG)", "DiagnosticLog.install")
        assertTrue(policy.contains("detectNetwork()"))
        assertTrue(policy.contains("penaltyLog()"))
        assertFalse(policy.contains("penaltyDeath"))
    }

    private fun assertEqualsGiveUp(actual: String?) {
        assertTrue(actual == EndpointRefresh.GIVE_UP_MESSAGE)
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
