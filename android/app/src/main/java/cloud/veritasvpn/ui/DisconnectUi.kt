package cloud.veritasvpn.ui

import cloud.veritasvpn.vpn.VpnKillSwitch

/**
 * The connect circle disconnects inside the app. Always-on and lockdown are
 * system switches this process cannot clear, and the tap must not open them.
 */
object DisconnectUi {
    const val LOCKDOWN_NOTE =
        "Disconnected. Always-on VPN is still on, so other apps stay blocked until you turn it off in system VPN settings."

    /**
     * Used when a disconnected read cannot prove the switches are on.
     * It does not claim that Always-on is still enabled.
     */
    const val CHECK_NOTE =
        "Disconnected. Check system VPN settings if other apps stay blocked."

    /** What the disconnect line may claim. */
    enum class Note { On, Off, Unknown }

    /**
     * Disconnect copy from a fresh read.
     *
     * A live service that reports both switches off clears the line, including
     * when settings still say on. A live service that still reports both on
     * is believed unless settings already say off, in which case the two
     * sources disagree and the line must not assert that Always-on is on.
     * With no service, settings are the remaining signal. A hidden package
     * is [Note.Unknown].
     */
    fun lockdownNote(
        settings: VpnKillSwitch.Sight?,
        serviceAlwaysOn: Boolean?,
        serviceLockdown: Boolean?,
    ): Note {
        if (serviceAlwaysOn != null && serviceLockdown != null) {
            if (serviceAlwaysOn && serviceLockdown) {
                if (settings == VpnKillSwitch.Sight.Off) return Note.Unknown
                return Note.On
            }
            return Note.Off
        }
        return when (settings) {
            VpnKillSwitch.Sight.On -> Note.On
            VpnKillSwitch.Sight.Off -> Note.Off
            VpnKillSwitch.Sight.Unreadable, null -> Note.Unknown
        }
    }

    fun text(note: Note): String? = when (note) {
        Note.On -> LOCKDOWN_NOTE
        Note.Off -> null
        Note.Unknown -> CHECK_NOTE
    }

    /**
     * [Unknown] replaces an assertion that is already on screen, or the line
     * just produced by a disconnect. It does not appear on a quiet home screen.
     */
    fun textFor(note: Note, replaceAssertion: Boolean): String? = when (note) {
        Note.On -> LOCKDOWN_NOTE
        Note.Off -> null
        Note.Unknown -> if (replaceAssertion) CHECK_NOTE else null
    }

    /** A state broadcast must not wipe either disconnect line. */
    fun keepDisconnectNote(error: String?, current: String?): String? =
        error ?: current?.takeIf { it == LOCKDOWN_NOTE || it == CHECK_NOTE }

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
