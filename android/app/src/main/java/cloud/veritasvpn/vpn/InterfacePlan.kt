package cloud.veritasvpn.vpn

/**
 * The tun address has to be the one in the peer config.
 *
 * A kill-switch hold keeps a different address (and excludes this app).
 * Reusing that interface lets the handshake complete and then drops every
 * data packet: the server only accepts the assigned tunnel address.
 */
object InterfacePlan {
    fun addressHost(config: String): String? {
        val raw = interfaceAddress(config) ?: return null
        val first = raw.substringBefore(',').trim()
        val host = first.substringBefore('/').trim()
        return host.ifEmpty { null }
    }

    /** True when the live tun is missing or is not this peer's address. */
    fun needsRebuild(currentHost: String?, config: String): Boolean {
        val want = addressHost(config) ?: return false
        val have = currentHost?.substringBefore('/')?.trim().orEmpty()
        return have.isEmpty() || have != want
    }

    private fun interfaceAddress(config: String): String? {
        var inInterface = false
        for (line in config.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inInterface = trimmed.equals("[Interface]", ignoreCase = true)
                continue
            }
            if (!inInterface || !trimmed.startsWith("Address", ignoreCase = true)) continue
            val eq = trimmed.indexOf('=')
            if (eq < 0) continue
            val value = trimmed.substring(eq + 1).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }
}
