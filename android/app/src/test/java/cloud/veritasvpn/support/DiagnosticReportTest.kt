package cloud.veritasvpn.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    private val privateKey = "A".repeat(43) + "="
    private val preshared = "B".repeat(43) + "="

    @Test
    fun reportListsConnectionFactsWithoutSecrets() {
        val report = sample()
        val text = report.text()
        assertTrue(text.contains("App: 0.2.88 (72)"))
        assertTrue(text.contains("OS: Android 14 (API 34)"))
        assertTrue(text.contains("Device: Pixel 8"))
        assertTrue(text.contains("Connection: Connected"))
        assertTrue(text.contains("Transport: UDP"))
        assertTrue(text.contains("Handshake: 12s ago"))
        assertTrue(text.contains("Endpoint: 203.0.113.8:443"))
        assertTrue(text.contains("Last error: none"))
        assertFalse(text.contains("PrivateKey"))
        assertFalse(text.contains("[Interface]"))
    }

    @Test
    fun errorKeysAndTokensAreRedacted() {
        val report = sample(
            lastError = "handshake failed PrivateKey=$privateKey Bearer abcdef.token preshared_key=$preshared",
        )
        val text = report.text()
        assertFalse(text.contains(privateKey))
        assertFalse(text.contains(preshared))
        assertFalse(text.contains("abcdef.token"))
        assertTrue(text.contains("Last error:"))
    }

    @Test
    fun configDumpIsReplaced() {
        val dumped = """
            [Interface]
            PrivateKey = $privateKey
            [Peer]
            PublicKey = $preshared
            Endpoint = 203.0.113.8:443
        """.trimIndent()
        assertEquals(
            "An error occurred (details redacted).",
            sanitizeError(dumped),
        )
        val text = sample(lastError = dumped).text()
        assertFalse(text.contains(privateKey))
        assertFalse(text.contains("[Interface]"))
        assertFalse(text.contains("203.0.113.8:443\nEndpoint"))
    }

    @Test
    fun stealthPrefersPublicTlsEndpointOverLoopback() {
        val endpoint = choosePublicEndpoint(
            active = "127.0.0.1:51820",
            wan = "203.0.113.8:443",
            stealth = "203.0.113.9:443",
            transport = "stealth",
        )
        assertEquals("203.0.113.9:443", endpoint)
    }

    @Test
    fun udpSkipsLoopbackAndSecretEndpoints() {
        val endpoint = choosePublicEndpoint(
            active = "PrivateKey=$privateKey",
            wan = "203.0.113.8:443",
            stealth = "198.51.100.4:443",
            transport = "udp",
        )
        assertEquals("203.0.113.8:443", endpoint)
    }

    @Test
    fun missingEndpointIsADash() {
        assertEquals("—", choosePublicEndpoint("127.0.0.1:1", "", "localhost:443", "udp"))
    }

    @Test
    fun sharingOffOmitsTheReport() {
        val body = contactBody(includeDiagnostics = false, reportText = sample().text())
        assertTrue(body.contains("Hello VeritasVPN Support"))
        assertFalse(body.contains("Endpoint:"))
        assertFalse(body.contains("203.0.113.8"))
    }

    @Test
    fun sharingOnIncludesTheRedactedReport() {
        val report = sample().text()
        val body = contactBody(includeDiagnostics = true, reportText = report)
        assertTrue(body.contains("Endpoint: 203.0.113.8:443"))
        val mailto = SupportLinks.mailtoUrl(includeDiagnostics = true, reportText = report)
        assertTrue(mailto.startsWith("mailto:contact@veritasvpn.cloud?"))
        assertTrue(mailto.contains("subject=VeritasVPN%20support"))
        assertTrue(mailto.contains("Endpoint"))
        assertFalse(mailto.contains(privateKey))
    }

    @Test
    fun poisonedReportIsNotAttached() {
        val body = contactBody(true, "PrivateKey = $privateKey")
        assertFalse(body.contains(privateKey))
        assertTrue(body.contains("details redacted"))
    }

    @Test
    fun progressLinesAreNotStoredAsErrors() {
        assertFalse(isRecordableError(null))
        assertFalse(isRecordableError("Connecting…"))
        assertFalse(isRecordableError("Reconnecting…"))
        assertFalse(isRecordableError("Restoring secure connection…"))
        assertTrue(isRecordableError("Connection timed out. Check your network and try again."))
    }

    @Test
    fun handshakeAgeUsesTheClock() {
        val now = 1_700_000_060_000L
        assertEquals("—", formatHandshakeAge(0L, now))
        assertEquals("12s ago", formatHandshakeAge(now - 12_000L, now))
        assertEquals("2m ago", formatHandshakeAge(now - 120_000L, now))
    }

    @Test
    fun onlyFirstPartyHttpsLinksAreAllowed() {
        assertTrue(SupportLinks.isAllowedHttps(SupportLinks.SUPPORT))
        assertTrue(SupportLinks.isAllowedHttps(SupportLinks.SUPPORT_CONNECT))
        assertTrue(SupportLinks.isAllowedHttps(SupportLinks.PRIVACY))
        assertTrue(SupportLinks.isAllowedHttps(SupportLinks.TERMS))
        assertFalse(SupportLinks.isAllowedHttps("https://example.com/support"))
        assertFalse(SupportLinks.isAllowedHttps("mailto:contact@veritasvpn.cloud"))
    }

    private fun sample(
        lastError: String? = null,
        transport: String = "udp",
        active: String = "203.0.113.8:443",
    ) = buildDiagnosticReport(
        versionName = "0.2.88",
        versionCode = "72",
        osVersion = "Android 14 (API 34)",
        device = "Pixel 8",
        connected = true,
        connecting = false,
        handshakeEpochMs = 1_700_000_000_000L,
        nowMs = 1_700_000_012_000L,
        transport = transport,
        lastError = lastError,
        activeEndpoint = active,
        wanEndpoint = "203.0.113.8:443",
        stealthEndpoint = "203.0.113.9:443",
    )
}
