package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackendConfigTest {
    @Test
    fun connectReprovisionAndRebuildMatchThePeerTheServerCreated() {
        val created = peerConfig(
            privateKey = "clientPrivateA",
            presharedKey = "pskA",
            address = "10.8.0.2/32",
            serverKey = "serverA",
        )
        val connected = BackendConfig.select(stored = null, incoming = created, advertisedServerKey = "serverA")
        assertTrue(BackendConfig.matches(connected, BackendConfig.fields(created)!!))

        val reprovisioned = peerConfig(
            privateKey = "clientPrivateB",
            presharedKey = "pskB",
            address = "10.8.0.9/32",
            serverKey = "serverB",
        )
        // A stale template from the previous peer must not be installed.
        val afterReprovision = BackendConfig.select(
            stored = reprovisioned,
            incoming = created,
            advertisedServerKey = "serverB",
        )
        assertTrue(BackendConfig.matches(afterReprovision, BackendConfig.fields(reprovisioned)!!))
        assertFalse(afterReprovision.contains("clientPrivateA"))
        assertFalse(afterReprovision.contains("pskA"))
        assertFalse(afterReprovision.contains("10.8.0.2/32"))

        val rebuilt = BackendConfig.select(
            stored = reprovisioned,
            incoming = reprovisioned,
            advertisedServerKey = "serverC",
        )
        val got = BackendConfig.fields(rebuilt)!!
        assertEquals("clientPrivateB", got.privateKey)
        assertEquals("pskB", got.presharedKey)
        assertEquals("10.8.0.9/32", got.address)
        assertEquals("serverC", got.serverPublicKey)
    }

    @Test
    fun sameClientKeepsPresharedKeyAndAddressWhenTheTemplateDropsThem() {
        val stored = peerConfig(
            privateKey = "clientPrivateA",
            presharedKey = "pskA",
            address = "10.8.0.2/32",
            serverKey = "serverA",
        )
        val dropped = peerConfig(
            privateKey = "clientPrivateA",
            presharedKey = null,
            address = "10.9.9.9/32",
            serverKey = "serverA",
        )
        val selected = BackendConfig.select(stored, dropped, advertisedServerKey = "serverA")
        val got = BackendConfig.fields(selected)!!
        assertEquals("clientPrivateA", got.privateKey)
        assertEquals("pskA", got.presharedKey)
        assertEquals("10.8.0.2/32", got.address)
        assertEquals("serverA", got.serverPublicKey)
    }

    @Test
    fun lockdownHoldAddressIsReplacedByThePeerAddress() {
        val config = peerConfig(
            privateKey = "clientPrivateA",
            presharedKey = "pskA",
            address = "10.8.0.2/32",
            serverKey = "serverA",
        )
        assertTrue(InterfacePlan.needsRebuild(LockdownHold.IPV4_ADDRESS, config))
        assertTrue(InterfacePlan.needsRebuild(null, config))
        assertFalse(InterfacePlan.needsRebuild("10.8.0.2", config))
        assertEquals("10.8.0.2", InterfacePlan.addressHost(config))
    }

    @Test
    fun serviceInstallsTheSelectedConfigAndRefreshesAMismatchedTun() {
        val service = kotlinSource("VeritasVpnService.kt")
        assertTrue(service.contains("BackendConfig.select"))
        assertTrue(service.contains("InterfacePlan.needsRebuild"))
        val bring = slice(service, "fun bringUp(", "fun switchToStealth(")
        val kept = slice(bring, "keptTun ->", "else ->")
        assertTrue(kept.contains("InterfacePlan.needsRebuild"))
        assertTrue(kept.contains("openInterface(parsed)"))
        assertTrue(kept.indexOf("openInterface(parsed)") < kept.indexOf("rebindLiveTunnel(parsed, gen)"))
        val plane = slice(service, "fun dataPlaneNetwork()", "fun dataPlaneSnap()")
        assertTrue(plane.contains("defaultUnderlayNetwork"))
    }

    private fun peerConfig(
        privateKey: String,
        presharedKey: String?,
        address: String,
        serverKey: String,
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = $address")
        appendLine("DNS = 10.8.0.1")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $serverKey")
        if (!presharedKey.isNullOrEmpty()) appendLine("PresharedKey = $presharedKey")
        appendLine("Endpoint = 203.0.113.10:51820")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("PersistentKeepalive = 25")
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
        } ?: error("$name not found")
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }
}
