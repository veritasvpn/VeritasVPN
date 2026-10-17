package cloud.veritasvpn.ui

import cloud.veritasvpn.support.DiagnosticLog
import kotlinx.coroutines.CancellationException

/**
 * Circle-tap side effects. Does not finish the activity, move the task back,
 * recreate it, or start a system screen.
 */
object CircleDisconnect {
    fun run(lockdownEnabled: Boolean, disconnect: () -> Unit) {
        DiagnosticLog.record(DisconnectUi.circleTapLine(lockdownEnabled))
        try {
            disconnect()
        } catch (error: CancellationException) {
            // Cancellation is not a crash and must not escape onto the click thread.
        } catch (error: Exception) {
            DiagnosticLog.recordCrash(error)
        }
    }
}
