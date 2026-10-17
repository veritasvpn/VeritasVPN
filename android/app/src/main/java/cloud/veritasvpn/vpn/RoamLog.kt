package cloud.veritasvpn.vpn

/**
 * In-memory roam steps for Help & Support.
 *
 * Each line is a handle change, the network a peer read used, an endpoint
 * class (LAN, public, or stealth), a socket swap, a handshake result, or a
 * rebuild. Addresses, keys, and tokens are dropped before they are stored.
 */
class RoamLog(private val capacity: Int = CAPACITY) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun record(step: String) {
        val safe = sanitize(step) ?: return
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(safe)
    }

    @Synchronized
    fun snapshot(): String = if (lines.isEmpty()) "—" else lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }

    companion object {
        const val CAPACITY = 40
        private val shared = RoamLog()
        private val IPV4 = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")
        private val SECRET = Regex("""(?i)(private\s*key|preshared|token|bearer|password|secret)""")
        private val KEY = Regex("""[A-Za-z0-9+/]{20,}={0,2}""")

        fun record(step: String) = shared.record(step)

        fun snapshot(): String = shared.snapshot()

        fun sanitize(step: String): String? {
            val text = step.trim().replace('\n', ' ').replace('\r', ' ')
            if (text.isEmpty() || text.length > 160) return null
            if (IPV4.containsMatchIn(text) || SECRET.containsMatchIn(text) || KEY.containsMatchIn(text)) {
                return null
            }
            if (text.any { !(it.isLetterOrDigit() || it == ' ' || it in "=:_-/.,>") }) return null
            return text
        }
    }
}
