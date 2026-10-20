package cloud.veritasvpn.vpn

/**
 * The WireGuard config the userspace backend actually runs.
 *
 * Connect, reprovision, and a server-key rebuild must all install the peer
 * the server created. An older saved tunnel must not contribute its private
 * key, preshared key, or interface address. A server-key refresh may replace
 * only the peer public key.
 */
object BackendConfig {
    data class Fields(
        val privateKey: String,
        val presharedKey: String?,
        val address: String,
        val serverPublicKey: String,
    )

    fun fields(config: String): Fields? {
        val privateKey = sectionValue(config, "Interface", "PrivateKey") ?: return null
        val address = sectionValue(config, "Interface", "Address") ?: return null
        val serverPublicKey = ServerKeyConfig.peerPublicKey(config) ?: return null
        return Fields(
            privateKey = privateKey,
            presharedKey = sectionValue(config, "Peer", "PresharedKey"),
            address = address,
            serverPublicKey = serverPublicKey,
        )
    }

    fun matches(config: String, expected: Fields): Boolean {
        val got = fields(config) ?: return false
        return got == expected
    }

    /**
     * Config to parse and pass to the backend.
     *
     * [stored] wins when it is a different client (a newer provision). [incoming]
     * wins for the same client so an endpoint update is kept, and a blank
     * preshared key or address is filled back in from [stored]. [advertisedServerKey]
     * replaces only the server public key.
     */
    fun select(stored: String?, incoming: String, advertisedServerKey: String?): String {
        val storedFields = stored?.let { fields(it) }
        val incomingFields = fields(incoming)
        val winner = when {
            stored.isNullOrBlank() || storedFields == null -> incoming
            incomingFields == null -> stored
            storedFields.privateKey != incomingFields.privateKey -> stored
            else -> mergeSameClient(stored, incoming, storedFields, incomingFields)
        }
        return applyServerKey(winner, advertisedServerKey)
    }

    private fun applyServerKey(config: String, advertisedServerKey: String?): String {
        val rebuilt = ServerKeyConfig.withServerKey(config, advertisedServerKey)
        val before = fields(config) ?: return config
        val after = fields(rebuilt) ?: return config
        if (after.privateKey != before.privateKey ||
            after.presharedKey != before.presharedKey ||
            after.address != before.address
        ) {
            return config
        }
        return rebuilt
    }

    private fun mergeSameClient(
        stored: String,
        incoming: String,
        storedFields: Fields,
        incomingFields: Fields,
    ): String {
        var result = incoming
        if (incomingFields.presharedKey.isNullOrEmpty() && !storedFields.presharedKey.isNullOrEmpty()) {
            result = insertPeerLine(result, "PresharedKey", storedFields.presharedKey)
        }
        // Same client key. Keep the address already stored for that peer so a
        // template cannot point the tun at a different interface address.
        if (storedFields.address.isNotBlank() && incomingFields.address != storedFields.address) {
            result = replaceInterfaceValue(result, "Address", storedFields.address)
        }
        return result
    }

    private fun insertPeerLine(config: String, name: String, value: String): String {
        if (sectionValue(config, "Peer", name) != null) return config
        val lines = config.split('\n').toMutableList()
        val peer = lines.indexOfFirst { it.trim().equals("[Peer]", ignoreCase = true) }
        if (peer < 0) return config
        var insertAt = peer + 1
        while (insertAt < lines.size &&
            !lines[insertAt].trim().startsWith("[") &&
            !lines[insertAt].trim().startsWith("PublicKey", ignoreCase = true)
        ) {
            insertAt++
        }
        if (insertAt < lines.size && lines[insertAt].trim().startsWith("PublicKey", ignoreCase = true)) {
            insertAt++
        }
        lines.add(insertAt, "$name = $value")
        return lines.joinToString("\n")
    }

    private fun replaceInterfaceValue(config: String, name: String, value: String): String {
        var inInterface = false
        var replaced = false
        val lines = config.split('\n').map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inInterface = trimmed.equals("[Interface]", ignoreCase = true)
                return@map line
            }
            if (inInterface && !replaced &&
                trimmed.startsWith(name, ignoreCase = true) &&
                trimmed.contains('=')
            ) {
                replaced = true
                "$name = $value"
            } else {
                line
            }
        }
        return lines.joinToString("\n")
    }

    private fun sectionValue(config: String, section: String, name: String): String? {
        var inSection = false
        for (line in config.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inSection = trimmed.equals("[$section]", ignoreCase = true)
                continue
            }
            if (!inSection || !trimmed.startsWith(name, ignoreCase = true)) continue
            val eq = trimmed.indexOf('=')
            if (eq < 0) continue
            val value = trimmed.substring(eq + 1).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }
}
