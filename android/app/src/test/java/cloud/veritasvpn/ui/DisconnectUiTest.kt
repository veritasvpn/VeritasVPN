package cloud.veritasvpn.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DisconnectUiTest {
    @Test
    fun circleTapDisconnectsInAppAndNeverLaunchesSettings() {
        val locked = DisconnectUi.afterCircleTap(lockdownEnabled = true)
        assertFalse(locked.launchSettings)
        assertNotNull(locked.note)

        val open = DisconnectUi.afterCircleTap(lockdownEnabled = false)
        assertFalse(open.launchSettings)
        assertNull(open.note)
    }
}
