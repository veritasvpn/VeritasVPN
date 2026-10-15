package cloud.veritasvpn.support

/**
 * Text for the diagnostic ring. Exit reasons and crash frames only.
 * Messages, addresses, keys, and tokens are not stored.
 */
object ProcessExitLog {
    const val PACKAGE_PREFIX = "cloud.veritasvpn."
    private val IPV4 = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")

    fun reasonName(code: Int): String = when (code) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "CODE_$code"
    }

    /** `main` for this package, `vpn` for a separate `:vpn` process, otherwise ignore. */
    fun processLabel(processName: String?, packageName: String): String? {
        if (processName.isNullOrBlank() || packageName.isBlank()) return null
        if (processName == packageName) return "main"
        if (processName == "$packageName:vpn") return "vpn"
        return null
    }

    fun exitLine(
        process: String,
        reason: String,
        description: String?,
        importance: Int,
        timestampMs: Long,
    ): String? {
        if (process != "main" && process != "vpn") return null
        if (timestampMs < 0L) return null
        val reasonSafe = reason.takeIf { it.all { c -> c.isLetterOrDigit() || c == '_' } } ?: return null
        val desc = sanitizeDescription(description)
        return "exit proc=$process reason=$reasonSafe importance=$importance ts=$timestampMs desc=$desc"
    }

    fun sanitizeDescription(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "none"
        if (text.length > 80 || IPV4.containsMatchIn(text)) return "redacted"
        if (text.any { !(it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' || it == '.') }) {
            return "redacted"
        }
        return text
    }

    fun frame(className: String, method: String, file: String?, line: Int): String? {
        if (!className.startsWith(PACKAGE_PREFIX)) return null
        if (!className.all { it.isLetterOrDigit() || it == '.' || it == '$' || it == '_' }) return null
        if (method.isEmpty() || !method.all { it.isLetterOrDigit() || it == '_' || it == '$' }) return null
        val fileSafe = file?.takeIf { name ->
            name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '.' || it == '_' }
        } ?: "Unknown"
        return if (line > 0) "$className.$method $fileSafe:$line" else "$className.$method $fileSafe"
    }

    /** Exception class, then at most [limit] frames from this package. No message. */
    fun crashLines(className: String, frames: List<String>, limit: Int = 10): List<String> {
        val safeClass = className.takeIf {
            it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '$' || c == '_' }
        } ?: "Unknown"
        val ours = frames.filter { line ->
            line.startsWith(PACKAGE_PREFIX) &&
                line.length <= 160 &&
                line.all { it.isLetterOrDigit() || it == '.' || it == '$' || it == '_' || it == ':' || it == ' ' }
        }.take(limit)
        return listOf("crash $safeClass") + ours.map { "frame $it" }
    }
}
