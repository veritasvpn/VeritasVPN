package cloud.veritasvpn.vpn

import cloud.veritasvpn.api.UserVisibleError
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

    const val NO_NETWORK = "Stealth tcp: the current network is not available"
    const val PROTECT_FAILED = "Stealth tcp: the tunnel could not release the socket"
    const val BIND_FAILED = "Stealth tcp: the current network could not be used"

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
        if (error is Failure) return publish(error.userMessage) ?: fallback(stage)
        val body = when (error) {
            is SocketTimeoutException,
            is InterruptedIOException,
            -> "the server did not respond"
            is UnknownHostException -> "the server address did not resolve"
            is ConnectException -> "the server did not accept the connection"
            is SSLException -> "the secure connection failed"
            is UserVisibleError -> error.userMessage
                .removePrefix("Stealth ")
                .trim()
                .ifBlank { "the connection failed" }
            else -> "the connection failed"
        }
        return publish("Stealth ${wire(stage)}: $body") ?: fallback(stage)
    }

    /** User-facing stage, or the last safe error. Null once the upgrade is open. */
    fun detail(stage: Stage?, lastError: String?): String? {
        if (stage == null || stage == Stage.OPEN) return null
        val err = publish(lastError)
        if (!err.isNullOrEmpty()) return err
        return wire(stage)
    }

    fun shortStage(stage: Stage?): String? = when (stage) {
        Stage.TCP, Stage.TLS, Stage.UPGRADE -> wire(stage)
        else -> null
    }

    /**
     * Reconnecting line. Direct mode (no Stealth detail) keeps the existing
     * notice. A Stealth detail is shown while reconnecting or before the
     * WireGuard handshake, including the stage.
     */
    fun statusLine(
        reconnecting: Boolean,
        handshakeOk: Boolean,
        notice: String?,
        stealthDetail: String?,
    ): String? {
        val detail = publish(stealthDetail)
        if (detail != null && (reconnecting || !handshakeOk)) {
            return "Reconnecting… · $detail"
        }
        if (!reconnecting) return null
        return EndpointRefresh.statusWhileReconnecting(notice)
    }

    /** Drop addresses and raw timeout text before anything is shown or logged. */
    fun publish(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > 160) return null
        if (text.any { it == '\n' || it == '\r' || it == '/' || it == '\\' }) return null
        if (IPV4.containsMatchIn(text)) return null
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

    private fun fallback(stage: Stage) = "Stealth ${wire(stage)}: the connection failed"

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private val IPV4 = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")
}
