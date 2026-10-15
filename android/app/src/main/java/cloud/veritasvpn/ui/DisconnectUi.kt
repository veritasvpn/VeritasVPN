package cloud.veritasvpn.ui

/**
 * The connect circle disconnects inside the app. Always-on and lockdown are
 * system switches this process cannot clear, and the tap must not open them.
 */
object DisconnectUi {
    const val LOCKDOWN_NOTE =
        "Disconnected. Always-on VPN is still on, so other apps stay blocked until you turn it off in system VPN settings."

    data class Result(val launchSettings: Boolean, val note: String?)

    fun afterCircleTap(lockdownEnabled: Boolean): Result = Result(
        launchSettings = false,
        note = if (lockdownEnabled) LOCKDOWN_NOTE else null,
    )
}
