package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProvisionRouteTest {
    @Test
    fun lockdownReconnectUsesTheProtectedClient() {
        assertEquals(
            ProvisionRoute.Route.PROTECTED,
            ProvisionRoute.choose(interfaceHeld = true, protectedAvailable = true),
        )
        assertEquals("protected", ProvisionRoute.logName(ProvisionRoute.Route.PROTECTED))
    }

    @Test
    fun reconnectWithoutTheInterfaceUsesTheNormalClient() {
        assertEquals(
            ProvisionRoute.Route.NORMAL,
            ProvisionRoute.choose(interfaceHeld = false, protectedAvailable = false),
        )
        assertEquals(
            ProvisionRoute.Route.NORMAL,
            ProvisionRoute.choose(interfaceHeld = false, protectedAvailable = true),
        )
        assertEquals("normal", ProvisionRoute.logName(ProvisionRoute.Route.NORMAL))
    }

    @Test
    fun heldInterfaceWithoutAProtectedClientDoesNotUseTheNormalClient() {
        assertEquals(
            ProvisionRoute.Route.BLOCKED,
            ProvisionRoute.choose(interfaceHeld = true, protectedAvailable = false),
        )
        assertEquals("blocked", ProvisionRoute.logName(ProvisionRoute.Route.BLOCKED))
    }

    @Test
    fun activityReconnectPostsThroughTheProtectedClientWhenTheInterfaceIsHeld() {
        val activity = kotlinSource("MainActivity.kt")
        val create = slice(activity, "fun createPeer(", "fun startConnection(")
        assertTrue(create.contains("ProvisionRoute.choose"))
        assertTrue(create.contains("VeritasVpnService.provisioningClient()"))
        assertTrue(create.contains("VeritasVpnService.interfaceHeld()"))
        assertTrue(create.contains("accessForControl"))
        assertTrue(create.contains("refreshForControl"))
        assertTrue(create.contains("httpClient = client"))
        assertTrue(create.contains("AuthenticatedApi.execute"))
        val normal = slice(create, "Route.NORMAL ->", "Route.PROTECTED ->")
        assertFalse(normal.contains("httpClient"))
        val protected = slice(create, "Route.PROTECTED ->", "Route.BLOCKED ->")
        assertTrue(protected.contains("postPeerOutsideTunnel"))

        val service = kotlinSource("VeritasVpnService.kt")
        val hook = slice(service, "fun provisioningClient()", "fun markSessionEnded()")
        assertTrue(hook.contains("underlyingControlClient()"))
        assertTrue(hook.contains("keepBestMatchingWatch()"))
        assertTrue(hook.contains("tunKeeper"))
        val disconnect = slice(service, "ACTION_DISCONNECT -> {", "else -> {")
        assertTrue(disconnect.contains("keepBestMatchingWatch()"))
        val watch = slice(service, "fun keepBestMatchingWatch()", "fun unregisterUnderlayWatch()")
        assertTrue(watch.contains("NET_CAPABILITY_NOT_VPN"))
        assertTrue(watch.contains("registerBestMatchingNetworkCallback"))
        assertFalse(watch.contains("startHealthWatch"))
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
