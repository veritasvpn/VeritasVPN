package cloud.veritasvpn.vpn

import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Opens the system internet panel. The app does not change networks itself. */
object MobileDataSettings {
    fun panelAction(sdkInt: Int): String =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            Settings.ACTION_WIRELESS_SETTINGS
        }

    fun intent(sdkInt: Int): Intent =
        Intent(panelAction(sdkInt)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
