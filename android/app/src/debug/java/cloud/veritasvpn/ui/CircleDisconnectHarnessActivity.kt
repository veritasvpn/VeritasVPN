package cloud.veritasvpn.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import cloud.veritasvpn.ui.theme.VeritasVPNTheme

/**
 * Debug-only host so an instrumented test can tap the connected circle.
 * Not in the release manifest.
 */
class CircleDisconnectHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VeritasVPNTheme {
                HeroConnectControl(
                    phase = HeroPhase.Protected,
                    onClick = {
                        CircleDisconnect.run(lockdownEnabled = true) {
                            // The production circle path does not finish this activity.
                        }
                    },
                )
            }
        }
    }
}
