package cloud.veritasvpn.ui

/**
 * Transfer counters while the tunnel is up. During Reconnecting the bytes
 * are local queues, not traffic the server has acknowledged, so they are
 * not shown as a live rate. Handshake age is separate and stays visible.
 */
fun liveStatsTitle(reconnecting: Boolean): String =
    if (reconnecting) "STATS" else "LIVE STATS"

fun presentedTransferBytes(bytes: Long, reconnecting: Boolean): String =
    if (reconnecting) "—" else formatTransferBytes(bytes)

fun transferCountersAreLive(reconnecting: Boolean): Boolean = !reconnecting

fun formatTransferBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
