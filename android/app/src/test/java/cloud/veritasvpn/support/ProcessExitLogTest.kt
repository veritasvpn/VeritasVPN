package cloud.veritasvpn.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcessExitLogTest {
    @Test
    fun exitLineNamesTheReasonWithoutAnAddress() {
        val line = ProcessExitLog.exitLine(
            process = "main",
            reason = ProcessExitLog.reasonName(4),
            description = "low memory",
            importance = 100,
            timestampMs = 1_700_000_000_000L,
        )
        assertEquals(
            "exit proc=main reason=CRASH importance=100 ts=1700000000000 desc=low memory",
            line,
        )
        assertEquals("vpn", ProcessExitLog.processLabel("cloud.veritasvpn:vpn", "cloud.veritasvpn"))
        assertEquals("main", ProcessExitLog.processLabel("cloud.veritasvpn", "cloud.veritasvpn"))
        assertNull(ProcessExitLog.processLabel("cloud.veritasvpn:other", "cloud.veritasvpn"))
        assertEquals("redacted", ProcessExitLog.sanitizeDescription("died at 203.0.113.10"))
        assertNull(
            ProcessExitLog.exitLine(
                process = "other",
                reason = "CRASH",
                description = null,
                importance = 100,
                timestampMs = 1L,
            ),
        )
    }

    @Test
    fun crashLinesKeepOurFramesOnly() {
        val frames = listOf(
            ProcessExitLog.frame("cloud.veritasvpn.MainActivity", "onCreate", "MainActivity.kt", 155),
            ProcessExitLog.frame("android.app.Activity", "performCreate", "Activity.java", 10),
            ProcessExitLog.frame("cloud.veritasvpn.vpn.VeritasVpnService", "onRevoke", "VeritasVpnService.kt", 590),
        )
        val lines = ProcessExitLog.crashLines("java.lang.IllegalStateException", frames.filterNotNull())
        assertEquals("crash java.lang.IllegalStateException", lines[0])
        assertEquals("frame cloud.veritasvpn.MainActivity.onCreate MainActivity.kt:155", lines[1])
        assertEquals(
            "frame cloud.veritasvpn.vpn.VeritasVpnService.onRevoke VeritasVpnService.kt:590",
            lines[2],
        )
        assertEquals(3, lines.size)
        assertFalse(lines.any { it.contains("203.0.113") })
        assertNull(ProcessExitLog.frame("android.app.Activity", "performCreate", "Activity.java", 10))
    }

    @Test
    fun applicationRecordsExitsAndInstallsTheHandler() {
        val source = File("src/main/java/cloud/veritasvpn/VeritasVpnApplication.kt").let { primary ->
            val file = if (primary.isFile) primary else File("app/src/main/java/cloud/veritasvpn/VeritasVpnApplication.kt")
            file.readText()
        }
        assertTrue(source.contains("DiagnosticLog.install"))
        assertTrue(source.contains("DiagnosticLog.recordExitReasons"))
        val log = File("src/main/java/cloud/veritasvpn/support/DiagnosticLog.kt").let { primary ->
            val file = if (primary.isFile) primary else File("app/src/main/java/cloud/veritasvpn/support/DiagnosticLog.kt")
            file.readText()
        }
        assertTrue(log.contains("getHistoricalProcessExitReasons"))
        assertTrue(log.contains("uncaughtException"))
        assertTrue(log.contains("recordCrash"))
        assertTrue(log.contains("fd.sync"))
        val service = File("src/main/java/cloud/veritasvpn/vpn/VeritasVpnService.kt").let { primary ->
            if (primary.isFile) primary else File("app/src/main/java/cloud/veritasvpn/vpn/VeritasVpnService.kt")
        }.readText()
        assertTrue(service.contains("userspace turn-off"))
        assertTrue(service.contains("disconnect roam-joined"))
    }
}
