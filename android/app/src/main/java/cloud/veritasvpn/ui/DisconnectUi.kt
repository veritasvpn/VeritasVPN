package cloud.veritasvpn.ui

/**
 * The connect circle disconnects inside the app. Always-on and lockdown are
 * system switches this process cannot clear, and the tap must not open them.
 */
object DisconnectUi {
    const val LOCKDOWN_NOTE =
        "Disconnected. Always-on VPN is still on, so other apps stay blocked until you turn it off in system VPN settings."

    data class Result(
        val launchSettings: Boolean,
        val note: String?,
        val callsPrepare: Boolean = false,
        val leavesApp: Boolean = false,
    )

    fun afterCircleTap(lockdownEnabled: Boolean): Result = Result(
        launchSettings = false,
        note = if (lockdownEnabled) LOCKDOWN_NOTE else null,
        callsPrepare = false,
        leavesApp = false,
    )

    /** Connected and Reconnecting both land on the offline home. Nothing else opens. */
    fun showsOfflineHome(connected: Boolean, connecting: Boolean, reconnecting: Boolean): Boolean =
        !connected && !connecting && !reconnecting

    /**
     * Always false. Stopping the VPN service restarts an Always-on VPN and
     * takes the task off screen, including when the platform revokes the
     * tunnel because the circle tap closed it.
     */
    fun stopService(
        @Suppress("UNUSED_PARAMETER") userEndedSession: Boolean,
        @Suppress("UNUSED_PARAMETER") systemRevoke: Boolean,
    ): Boolean = false

    /**
     * Closing the VPN interface while Always-on lockdown is on makes the
     * system take this task off screen. Keep the interface up in that case.
     * With lockdown off, closing it does not leave the app.
     */
    fun keepVpnInterface(lockdownEnabled: Boolean): Boolean = lockdownEnabled

    fun circleTapLine(lockdownEnabled: Boolean): String =
        "ui circle-tap lockdown=${if (lockdownEnabled) 1 else 0} finish=0 settings=0 prepare=0"
}
