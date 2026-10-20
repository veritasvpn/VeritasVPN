package cloud.veritasvpn.vpn

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlockedNetworkNoticeTest {
    @Test
    fun staysHiddenUntilTheStallHasLastedAboutTenSeconds() {
        val start = 1_000L
        val first = BlockedNetworkNotice.advance(sample(now = start))
        assertFalse(first.visible)
        val early = BlockedNetworkNotice.advance(
            sample(now = start + 9_000L, clock = first.clock),
        )
        assertFalse(early.visible)
        val due = BlockedNetworkNotice.advance(
            sample(now = start + 10_000L, clock = early.clock),
        )
        assertTrue(due.visible)
        assertTrue(due.offerStealthSwitch)
        assertFalse(due.suggestOtherNetwork)
    }

    @Test
    fun hidesAsSoonAsThisAttemptHandshakes() {
        val shown = showAt(20_000L)
        val cleared = BlockedNetworkNotice.advance(
            sample(
                now = 21_000L,
                handshakeMs = 20_500L,
                clock = shown.clock,
            ),
        )
        assertFalse(cleared.visible)
        assertEquals(0L, cleared.clock.stallSinceMs)
    }

    @Test
    fun anOlderHandshakeDoesNotCountDuringReconnect() {
        val step = BlockedNetworkNotice.advance(
            sample(
                now = 30_000L,
                connecting = false,
                reconnecting = true,
                connected = true,
                handshakeMs = 1_000L,
                handshakeOk = true,
                clock = BlockedNetworkNotice.Clock(stallSinceMs = 10_000L, rxAtStall = 0L),
            ),
        )
        assertTrue(step.visible)
    }

    @Test
    fun risingReceiveBytesDelayTheCardAndASecondRiseHidesIt() {
        val start = BlockedNetworkNotice.advance(sample(now = 1_000_000L, rxBytes = 10L))
        val bumped = BlockedNetworkNotice.advance(
            sample(now = 1_009_000L, rxBytes = 40L, clock = start.clock),
        )
        assertFalse(bumped.visible)
        val stillWaiting = BlockedNetworkNotice.advance(
            sample(now = 1_018_000L, rxBytes = 40L, clock = bumped.clock),
        )
        assertFalse(stillWaiting.visible)
        val shown = BlockedNetworkNotice.advance(
            sample(now = 1_028_000L, rxBytes = 40L, clock = stillWaiting.clock),
        )
        assertTrue(shown.visible)
        val oneByte = BlockedNetworkNotice.advance(
            sample(now = 1_028_500L, rxBytes = 41L, clock = shown.clock),
        )
        assertTrue(oneByte.visible)
        val sustained = BlockedNetworkNotice.advance(
            sample(now = 1_029_000L, rxBytes = 80L, clock = oneByte.clock),
        )
        assertFalse(sustained.visible)
    }

    @Test
    fun noValidatedNetworkHidesTheCard() {
        val step = BlockedNetworkNotice.advance(
            sample(
                now = 30_000L,
                networkValidated = false,
                clock = BlockedNetworkNotice.Clock(stallSinceMs = 0L),
            ),
        )
        assertFalse(step.visible)
    }

    @Test
    fun dismissHidesUntilTheHandshakeEndsTheEpisode() {
        val shown = showAt(20_000L)
        val dismissed = BlockedNetworkNotice.advance(
            sample(now = 25_000L, dismissed = true, clock = shown.clock),
        )
        assertFalse(dismissed.visible)
        val still = BlockedNetworkNotice.advance(
            sample(now = 40_000L, dismissed = true, clock = dismissed.clock),
        )
        assertFalse(still.visible)
    }

    @Test
    fun stealthSessionOffersAnotherNetworkAndNotASwitch() {
        val step = BlockedNetworkNotice.advance(
            sample(
                now = 20_000L,
                alreadyStealth = true,
                clock = BlockedNetworkNotice.Clock(stallSinceMs = 10_000L, rxAtStall = 0L),
            ),
        )
        assertTrue(step.visible)
        assertFalse(step.offerStealthSwitch)
        assertTrue(step.suggestOtherNetwork)
    }

    @Test
    fun idleProtectedSessionDoesNotShowTheCard() {
        val step = BlockedNetworkNotice.advance(
            sample(
                now = 50_000L,
                connecting = false,
                reconnecting = false,
                connected = true,
                handshakeOk = true,
                handshakeMs = 40_000L,
            ),
        )
        assertFalse(step.visible)
    }

    @Test
    fun reconnectingCopyMentionsTheNetworkOnlyWhileBlocked() {
        assertEquals("Reconnecting…", BlockedNetworkNotice.reconnectingText(false))
        assertTrue(BlockedNetworkNotice.reconnectingText(true).contains("blocking VPN"))
        assertFalse(BlockedNetworkNotice.reconnectingText(true).contains("timeout", ignoreCase = true))
    }

    @Test
    fun nothingInTheDecisionSwitchesModeUnlessTheUserTaps() {
        val modes = listOf(StealthMode.AUTO, StealthMode.UDP, StealthMode.STEALTH)
        for (mode in modes) {
            assertEquals(mode, StealthApply.modeAfter(mode, userTappedSwitch = false))
        }
        assertEquals(
            StealthMode.STEALTH,
            StealthApply.modeAfter(StealthMode.AUTO, userTappedSwitch = true),
        )
        assertEquals(
            StealthMode.STEALTH,
            StealthApply.modeAfter(StealthMode.UDP, userTappedSwitch = true),
        )
    }

    @Test
    fun serviceSwitchesToStealthOnlyFromTheUserAction() {
        val service = kotlinSource("VeritasVpnService.kt")
        val calls = Regex("switchToStealth\\(").findAll(service).count()
        assertEquals(2, calls)
        val action = service.substringAfter("ACTION_APPLY_STEALTH ->")
            .substringBefore("ACTION_DISCONNECT ->")
        assertTrue(action.contains("switchToStealth("))
        assertFalse(service.contains("setStealthMode"))
        assertEquals(1, Regex("userAskedStealth = true").findAll(service).count())
        assertTrue(service.contains("userAskedStealth = false"))
    }

    @Test
    fun mobileDataOpensTheInternetPanelOnApi29AndWirelessSettingsBeforeThat() {
        assertEquals(
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
            MobileDataSettings.panelAction(29),
        )
        assertEquals(Settings.ACTION_WIRELESS_SETTINGS, MobileDataSettings.panelAction(28))
        assertFalse(MobileDataSettings.panelAction(29).contains("VPN"))
    }

    @Test
    fun validatedUnderlayCountsAndADeadVpnAloneDoesNot() {
        assertFalse(ValidatedNetworks.hasInternet(emptyList()))
        assertFalse(
            ValidatedNetworks.hasInternet(
                listOf(ValidatedNetworks.Fact(vpn = true, validated = false)),
            ),
        )
        assertTrue(
            ValidatedNetworks.hasInternet(
                listOf(
                    ValidatedNetworks.Fact(vpn = true, validated = false),
                    ValidatedNetworks.Fact(vpn = false, validated = true),
                ),
            ),
        )
        assertFalse(
            ValidatedNetworks.hasInternet(
                listOf(
                    ValidatedNetworks.Fact(vpn = true, validated = true),
                    ValidatedNetworks.Fact(vpn = false, validated = false),
                ),
            ),
        )
    }

    private fun showAt(now: Long): BlockedNetworkNotice.Step {
        val first = BlockedNetworkNotice.advance(sample(now = now - 10_000L))
        return BlockedNetworkNotice.advance(sample(now = now, clock = first.clock))
    }

    private fun sample(
        now: Long,
        connecting: Boolean = true,
        reconnecting: Boolean = false,
        connected: Boolean = false,
        handshakeOk: Boolean = false,
        handshakeMs: Long = 0L,
        attemptStartedMs: Long = 5_000L,
        rxBytes: Long = 0L,
        networkValidated: Boolean = true,
        alreadyStealth: Boolean = false,
        dismissed: Boolean = false,
        clock: BlockedNetworkNotice.Clock = BlockedNetworkNotice.Clock(),
    ) = BlockedNetworkNotice.Sample(
        nowMs = now,
        connecting = connecting,
        reconnecting = reconnecting,
        connected = connected,
        handshakeOk = handshakeOk,
        handshakeMs = handshakeMs,
        attemptStartedMs = attemptStartedMs,
        rxBytes = rxBytes,
        networkValidated = networkValidated,
        alreadyStealth = alreadyStealth,
        dismissed = dismissed,
        clock = clock,
    )

    private fun kotlinSource(name: String): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("android/app/src/main/java"),
        )
        val file = roots.firstNotNullOfOrNull { root ->
            root.walk().firstOrNull { it.isFile && it.name == name }
        } ?: error("$name not found")
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }
}
