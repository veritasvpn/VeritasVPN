package cloud.veritasvpn.support

/**
 * Redacted support report. Callers pass already-separated fields.
 * The formatter never accepts a WireGuard config, key, or token.
 */
data class DiagnosticReport(
    val versionName: String,
    val versionCode: String,
    val osVersion: String,
    val device: String,
    val connectionState: String,
    val transport: String,
    val handshakeAge: String,
    val endpoint: String,
    val lastError: String,
) {
    fun versionLabel(): String =
        if (versionCode.isBlank()) "v$versionName" else "v$versionName ($versionCode)"

    fun text(): String = formatDiagnosticReport(this)
}

private val SECRET_MARKER = Regex(
    """(?i)(private\s*key|preshared\s*key|public\s*key|authorization|bearer\s|token\s*=|password\s*[:=]|secret\s*[:=])"""
)
private val WG_KEY = Regex("""[A-Za-z0-9+/]{42,44}=""")
private val ENDPOINT = Regex(
    """^(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?|(?:\d{1,3}\.){3}\d{1,3}):(\d{1,5})$"""
)
private val VERSION_NAME = Regex("""^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$""")
private val PROGRESS_STATUS = Regex(
    """(?i)^(restoring|connecting|reconnecting|disconnecting|creating secure|establishing)\b"""
)

fun isRecordableError(raw: String?): Boolean {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return false
    return !PROGRESS_STATUS.containsMatchIn(text)
}

fun sanitizeError(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return "none"
    var text = trimmed.replace("\r\n", "\n").replace('\r', '\n')
    if (text.length > 500) text = text.take(500)
    text = WG_KEY.replace(text, "[redacted]")
    text = Regex("""(?i)(private\s*key|preshared\s*key|public\s*key)\s*=\s*\S+""")
        .replace(text, "$1=[redacted]")
    text = Regex("""(?i)bearer\s+\S+""").replace(text, "Bearer [redacted]")
    text = Regex("""(?i)(authorization\s*[:=]\s*)\S+""").replace(text, "$1[redacted]")
    text = Regex("""(?i)(token|password|secret)\s*[:=]\s*\S+""").replace(text, "$1=[redacted]")
    if (
        text.contains("[Interface]", ignoreCase = true) ||
        text.contains("[Peer]", ignoreCase = true) ||
        text.contains("PrivateKey", ignoreCase = true) ||
        text.contains("PresharedKey", ignoreCase = true)
    ) {
        return "An error occurred (details redacted)."
    }
    return text.replace('\n', ' ').trim().ifBlank { "none" }
}

fun sanitizeEndpoint(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed.length > 120) return null
    if (SECRET_MARKER.containsMatchIn(trimmed) || WG_KEY.containsMatchIn(trimmed)) return null
    if (trimmed.any { it.isWhitespace() || it == '/' || it == '\\' || it == '=' }) return null
    val match = ENDPOINT.matchEntire(trimmed) ?: return null
    val port = match.groupValues[2].toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return trimmed
}

fun isLoopbackEndpoint(endpoint: String): Boolean {
    val host = endpoint.substringBeforeLast(':').trim().removePrefix("[").removeSuffix("]").lowercase()
    return host == "localhost" ||
        host == "127.0.0.1" ||
        host == "0.0.0.0" ||
        host == "::1" ||
        host == "0:0:0:0:0:0:0:1" ||
        host.startsWith("127.")
}

/**
 * Public node address only. Stealth prefers the TLS endpoint over a local
 * WireGuard listener. Loopback addresses are never shown.
 */
fun choosePublicEndpoint(active: String, wan: String, stealth: String, transport: String): String {
    val preferStealth = transport.equals("stealth", ignoreCase = true) ||
        transport.equals("switching", ignoreCase = true)
    val ordered = if (preferStealth) {
        listOf(stealth, wan, active)
    } else {
        listOf(active, wan, stealth)
    }
    for (candidate in ordered) {
        val clean = sanitizeEndpoint(candidate) ?: continue
        if (isLoopbackEndpoint(clean)) continue
        return clean
    }
    return "—"
}

fun formatHandshakeAge(handshakeEpochMs: Long, nowMs: Long): String {
    if (handshakeEpochMs <= 0L) return "—"
    val ageSec = ((nowMs - handshakeEpochMs) / 1000L).coerceAtLeast(0L)
    return when {
        ageSec < 60 -> "${ageSec}s ago"
        ageSec < 3600 -> "${ageSec / 60}m ago"
        else -> "${ageSec / 3600}h ago"
    }
}

fun connectionStateLabel(connected: Boolean, connecting: Boolean): String = when {
    connected -> "Connected"
    connecting -> "Connecting"
    else -> "Disconnected"
}

fun transportLabel(transport: String): String = when (transport.trim().lowercase()) {
    "udp" -> "UDP"
    "stealth" -> "Stealth"
    "switching" -> "Switching to Stealth"
    else -> "—"
}

fun sanitizeVersionName(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    return if (VERSION_NAME.matches(trimmed)) trimmed else "unknown"
}

fun sanitizeVersionCode(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    return if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() } && trimmed.length <= 12) trimmed else ""
}

fun sanitizePlainField(raw: String?, maxLen: Int = 80): String {
    val trimmed = raw?.trim().orEmpty().replace('\n', ' ').replace('\r', ' ')
    if (trimmed.isEmpty()) return ""
    if (SECRET_MARKER.containsMatchIn(trimmed) || WG_KEY.containsMatchIn(trimmed)) return ""
    return trimmed.take(maxLen)
}

fun buildDiagnosticReport(
    versionName: String,
    versionCode: String,
    osVersion: String,
    device: String,
    connected: Boolean,
    connecting: Boolean,
    handshakeEpochMs: Long,
    nowMs: Long,
    transport: String,
    lastError: String?,
    activeEndpoint: String,
    wanEndpoint: String,
    stealthEndpoint: String,
): DiagnosticReport {
    val safeError = sanitizeError(lastError)
    return DiagnosticReport(
        versionName = sanitizeVersionName(versionName),
        versionCode = sanitizeVersionCode(versionCode),
        osVersion = sanitizePlainField(osVersion).ifBlank { "unknown" },
        device = sanitizePlainField(device),
        connectionState = connectionStateLabel(connected, connecting),
        transport = transportLabel(transport),
        handshakeAge = formatHandshakeAge(handshakeEpochMs, nowMs),
        endpoint = choosePublicEndpoint(activeEndpoint, wanEndpoint, stealthEndpoint, transport),
        lastError = safeError,
    )
}

fun formatDiagnosticReport(report: DiagnosticReport): String = buildString {
    appendLine("VeritasVPN diagnostic report")
    append("App: ")
    append(report.versionName)
    if (report.versionCode.isNotBlank()) {
        append(" (")
        append(report.versionCode)
        append(")")
    }
    appendLine()
    append("OS: ")
    appendLine(report.osVersion)
    if (report.device.isNotBlank()) {
        append("Device: ")
        appendLine(report.device)
    }
    append("Connection: ")
    appendLine(report.connectionState)
    append("Transport: ")
    appendLine(report.transport)
    append("Handshake: ")
    appendLine(report.handshakeAge)
    append("Endpoint: ")
    appendLine(report.endpoint)
    append("Last error: ")
    append(report.lastError)
}.let { text ->
    if (SECRET_MARKER.containsMatchIn(text) || WG_KEY.containsMatchIn(text) || text.contains("[Interface]")) {
        "VeritasVPN diagnostic report\nLast error: details redacted"
    } else {
        text
    }
}

fun contactBody(includeDiagnostics: Boolean, reportText: String): String {
    val intro = "Hello VeritasVPN Support,\n\n"
    if (!includeDiagnostics) return intro
    val safe = formatSafeReportText(reportText)
    return intro + "---\n" + safe + "\n"
}

fun formatSafeReportText(reportText: String): String {
    val text = reportText.trim()
    if (text.isEmpty() || SECRET_MARKER.containsMatchIn(text) || WG_KEY.containsMatchIn(text)) {
        return "VeritasVPN diagnostic report\nLast error: details redacted"
    }
    return text
}
