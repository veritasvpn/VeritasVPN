package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DebugLanSeedTest {
    @Test
    fun parseAcceptsHostAndPortOnly() {
        assertEquals("192.168.1.10:51820", DebugLanSeedReceiver.parse("192.168.1.10:51820"))
        assertEquals("192.168.1.10:51820", DebugLanSeedReceiver.parse("  192.168.1.10:51820  "))
        assertEquals("10.0.0.1:1", DebugLanSeedReceiver.parse("10.0.0.1:1"))
        assertEquals("10.0.0.1:65535", DebugLanSeedReceiver.parse("10.0.0.1:65535"))
        assertNull(DebugLanSeedReceiver.parse(null))
        assertNull(DebugLanSeedReceiver.parse(""))
        assertNull(DebugLanSeedReceiver.parse("192.168.1.10"))
        assertNull(DebugLanSeedReceiver.parse("192.168.1.10:0"))
        assertNull(DebugLanSeedReceiver.parse("192.168.1.10:65536"))
        assertNull(DebugLanSeedReceiver.parse("192.168.1.10:51820/32"))
        assertNull(DebugLanSeedReceiver.parse("http://192.168.1.10:51820"))
        assertNull(DebugLanSeedReceiver.parse("user@192.168.1.10:51820"))
    }

    @Test
    fun seedIsDebugOnlyAndDoesNotLogTheAddress() {
        val main = manifest("src/main/AndroidManifest.xml")
        val debug = manifest("src/debug/AndroidManifest.xml")
        assertFalse(main.contains("DEBUG_SEED_LAN"))
        assertFalse(main.contains("DebugLanSeedReceiver"))
        assertTrue(debug.contains("cloud.veritasvpn.DEBUG_SEED_LAN"))
        assertTrue(debug.contains(".vpn.DebugLanSeedReceiver"))
        assertTrue(debug.contains(".ui.CircleDisconnectHarnessActivity"))
        val source = debugSource("DebugLanSeedReceiver.kt")
        assertTrue(source.contains("BuildConfig.DEBUG"))
        assertTrue(source.contains("KEY_ENDPOINT_LAN"))
        assertTrue(source.contains(".commit()"))
        assertFalse(source.contains("Log."))
        assertEquals(DebugLanSeedReceiver.ACTION, "cloud.veritasvpn.DEBUG_SEED_LAN")
    }

    private fun manifest(relative: String): String = read(relative)

    private fun debugSource(name: String): String {
        val roots = listOf(
            File("src/debug/java"),
            File("app/src/debug/java"),
            File("android/app/src/debug/java"),
        )
        val file = roots.firstNotNullOfOrNull { root ->
            if (!root.isDirectory) null else root.walk().firstOrNull { it.isFile && it.name == name }
        } ?: error("$name not found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun read(relative: String): String {
        val roots = listOf(File("."), File("app"), File("android/app"))
        val file = roots.firstNotNullOfOrNull { root ->
            val candidate = File(root, relative)
            if (candidate.isFile) candidate else null
        } ?: error("$relative not found from ${File(".").absolutePath}")
        return file.readText()
    }
}
