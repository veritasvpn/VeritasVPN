package cloud.veritasvpn.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CircleTapStaysResumedTest {
    @get:Rule
    val rule = createAndroidComposeRule<CircleDisconnectHarnessActivity>()

    @Test
    fun tapWhileConnectedLeavesActivityResumed() {
        rule.onNodeWithContentDescription("Disconnect").performClick()
        rule.waitForIdle()
        val activity = rule.activity
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        assertFalse(activity.isFinishing)
        assertFalse(activity.isDestroyed)
    }
}
