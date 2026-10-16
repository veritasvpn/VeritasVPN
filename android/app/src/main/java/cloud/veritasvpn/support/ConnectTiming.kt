package cloud.veritasvpn.support

/**
 * Elapsed marks for one connect, from the circle tap through the handshake.
 * The diagnostic ring keeps these. They carry no address, key, or token.
 */
object ConnectTiming {
    private val lock = Any()
    private var startedAtMs = 0L
    private val seen = HashSet<String>()

    /**
     * A best-effort peer delete on the normal client cannot finish while the
     * VPN interface is still up under lockdown: the socket sits until the read
     * timeout. Reconnect must not wait for it.
     */
    fun shouldWaitForCleanup(interfaceHeld: Boolean): Boolean = !interfaceHeld

    fun arm(nowMs: Long) {
        synchronized(lock) {
            startedAtMs = nowMs
            seen.clear()
            seen.add("tap")
        }
        DiagnosticLog.record(line("tap", 0))
    }

    fun isArmed(): Boolean = startedAtMs != 0L

    fun mark(step: String, nowMs: Long = System.currentTimeMillis()) {
        val text = synchronized(lock) {
            val start = startedAtMs
            if (start == 0L || !seen.add(step)) return
            line(step, nowMs - start)
        }
        DiagnosticLog.record(text)
    }

    /** A handshake from the previous session is older than the tap. */
    fun handshakeIsNew(handshakeMs: Long): Boolean {
        val start = startedAtMs
        return start > 0L && handshakeMs > start
    }

    fun finish() {
        synchronized(lock) {
            startedAtMs = 0L
            seen.clear()
        }
    }

    fun line(step: String, elapsedMs: Long): String {
        val safe = step.filter { it.isLetterOrDigit() }
        val ms = elapsedMs.coerceIn(0L, 999_999L)
        return "connect $safe ms=$ms"
    }
}
