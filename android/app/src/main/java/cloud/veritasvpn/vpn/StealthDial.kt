package cloud.veritasvpn.vpn

import java.net.Socket

/**
 * How a Stealth TCP socket is pinned to the current underlay, and when a
 * roam may close it.
 *
 * protect() alone can stamp a stale VPN netId after repeated tunnel cycles.
 * The dial protect()s and then bind()s. A missing network or a failed bind
 * fails the dial. There is no protect-only fallback. The supervisor retries.
 *
 * A roam must not close that socket, or create a new peer, while the TLS
 * upgrade has not finished and the underlay has not changed. Messages here
 * never include an address or a raw timeout string.
 */
object StealthDial {
    enum class Stage { TCP, TLS, UPGRADE, OPEN }

    const val CONNECT_TIMEOUT_MS = 10_000
    const val UPGRADE_BUDGET_MS = 15_000L

    /** After the websocket is up, let WireGuard finish before a same-network redial. */
    const val OPEN_QUIET_MS = 5_000L

    /** Shown on the status line and notification. The stage stays in diagnostics. */
    const val USER_UNREACHABLE = "Stealth can't reach the server on this network"
    const val NO_NETWORK = USER_UNREACHABLE
    const val PROTECT_FAILED = USER_UNREACHABLE
    const val BIND_FAILED = USER_UNREACHABLE

    class Failure(val stage: Stage, val userMessage: String) : java.io.IOException(userMessage)

    /**
     * protect(), then bind(). [bind] throwing closes the socket and fails the
     * dial. The caller connects only after this returns.
     */
    fun protectThenBind(
        socket: Socket,
        protect: (Socket) -> Boolean,
        bind: (Socket) -> Unit,
    ) {
        if (!protect(socket)) {
            closeQuietly(socket)
            throw Failure(Stage.TCP, PROTECT_FAILED)
        }
        try {
            bind(socket)
        } catch (e: Exception) {
            closeQuietly(socket)
            throw Failure(Stage.TCP, BIND_FAILED)
        }
    }

    /**
     * No network: do not protect, do not bind, do not connect.
     * Otherwise [protectThenBind].
     */
    fun dial(
        socket: Socket,
        networkAvailable: Boolean,
        protect: (Socket) -> Boolean,
        bind: (Socket) -> Unit,
    ) {
        if (!networkAvailable) {
            closeQuietly(socket)
            throw Failure(Stage.TCP, NO_NETWORK)
        }
        protectThenBind(socket, protect, bind)
    }

    /**
     * True when a same-network roam may close the socket or create a peer.
     * A different underlay handle always may: the next dial has to bind there.
     */
    fun mayRedial(
        stage: Stage?,
        dialing: Boolean,
        attemptStartedAtMs: Long,
        openedAtMs: Long,
        boundHandle: Long?,
        currentHandle: Long?,
        nowMs: Long,
    ): Boolean {
        if (boundHandle != null && currentHandle != null && boundHandle != currentHandle) return true
        if (stage == Stage.OPEN) {
            if (openedAtMs <= 0L) return true
            return nowMs - openedAtMs >= OPEN_QUIET_MS
        }
        if (!dialing || stage == null) return true
        if (attemptStartedAtMs <= 0L) return false
        return nowMs - attemptStartedAtMs >= UPGRADE_BUDGET_MS
    }

    fun userFacing(stage: Stage, error: Throwable): String {
        // Stage and the exception stay out of the sentence. [detail] records
        // the stage for diagnostics. [stage] selects that record at the call.
        if (stage == Stage.OPEN) return USER_UNREACHABLE
        if (error is Failure) return publish(error.userMessage) ?: USER_UNREACHABLE
        return USER_UNREACHABLE
    }

    /**
     * Diagnostics only. A bare stage while the upgrade is in progress, or
     * `tcp: <safe error>` after a failure. Null once the upgrade is open.
     */
    fun detail(stage: Stage?, lastError: String?): String? {
        if (stage == null || stage == Stage.OPEN) return null
        val stageName = wire(stage)
        val err = publish(lastError)
        if (err.isNullOrEmpty() || isStageToken(err)) return stageName
        val body = err.removePrefix("$stageName:").trim()
        if (body.isEmpty() || isStageToken(body)) return stageName
        return "$stageName: $body"
    }

    /**
     * Sentence for the status line and notification. Null when [detail] is
     * only a stage name (`tcp`, `tls`, `upgrade`).
     */
    fun userLine(detail: String?): String? {
        val text = publish(detail) ?: return null
        val body = STAGE_PREFIX.replace(text, "").trim()
        if (body.isEmpty() || isStageToken(body)) return null
        if (body.contains("tcp") || body.contains("tls") || body.contains("upgrade")) {
            return USER_UNREACHABLE
        }
        return body
    }

    fun isStageToken(text: String): Boolean =
        text == "tcp" || text == "tls" || text == "upgrade" || text == "open"

    /**
     * Direct mode (no Stealth detail) keeps the existing notice. Stealth
     * shows a plain sentence, never the stage. The first connect says
     * Connecting; a later attempt says Reconnecting.
     */
    fun statusLine(
        reconnecting: Boolean,
        handshakeOk: Boolean,
        notice: String?,
        stealthDetail: String?,
    ): String? {
        val friendly = userLine(stealthDetail)
        val stageOnly = isStageDetail(stealthDetail)
        if (friendly != null && (reconnecting || !handshakeOk)) {
            val lead = if (reconnecting) "Reconnecting…" else "Connecting…"
            return "$lead · $friendly"
        }
        if (!reconnecting) {
            if (!handshakeOk && stageOnly) return "Connecting…"
            return null
        }
        return EndpointRefresh.statusWhileReconnecting(notice)
    }

    /** Drop addresses and raw timeout text before anything is shown or logged. */
    fun publish(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > 160) return null
        if (text.any { it == '\n' || it == '\r' || it == '/' || it == '\\' }) return null
        if (IPV4.containsMatchIn(text) || IPV6.containsMatchIn(text)) return null
        val lower = text.lowercase()
        if (lower.contains("timeout") || lower.contains("timed out")) return null
        if (text.contains("Exception") || text.startsWith("java.")) return null
        return text
    }

    fun wire(stage: Stage): String = when (stage) {
        Stage.TCP -> "tcp"
        Stage.TLS -> "tls"
        Stage.UPGRADE -> "upgrade"
        Stage.OPEN -> "open"
    }

    private fun isStageDetail(detail: String?): Boolean {
        val text = publish(detail) ?: return false
        return isStageToken(text)
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private val IPV4 = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")

    /**
     * Compressed and full IPv6, including a trailing IPv4 tail
     * (`::ffff:192.0.2.1`, which [IPV4] also matches).
     */
    private val IPV6 = Regex(
        """(?i)(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:])""",
    )

    private val STAGE_PREFIX = Regex("""^(?:tcp|tls|upgrade|open)\s*:\s*""")
}
