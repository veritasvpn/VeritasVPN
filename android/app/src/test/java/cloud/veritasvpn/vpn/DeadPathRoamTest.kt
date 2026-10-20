package cloud.veritasvpn.vpn

import cloud.veritasvpn.ui.HeroPhase
import cloud.veritasvpn.ui.HeroPhaseMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Connect where handshake replies never arrive, then move to a network that
 * can answer. The redial has to start within about three seconds, and the
 * dead attempt must not stay on Protected.
 */
class DeadPathRoamTest {
    private val wifi = "wifi"
    private val mobile = "mobile"

    @Test
    fun repliesNeverArriveThenANewNetworkRedialsWithinThreeSeconds() {
        val stuck = DeadPathRoam.Attempt(
            recordedHandle = wifi,
            liveHandle = wifi,
            liveReady = true,
            handshakeFresh = false,
            rxSinceAttempt = 0L,
        )
        assertFalse(DeadPathRoam.redial(stuck))
        assertFalse(DeadPathRoam.workingSession(handshakeFresh = false, rxSinceAttempt = 0L))

        val moved = stuck.copy(liveHandle = mobile, handshakeFresh = false, rxSinceAttempt = 0L)
        assertTrue(DeadPathRoam.redial(moved))
        assertTrue(DeadPathRoam.timeToRedialMs() <= DeadPathRoam.REDIAL_WITHIN_MS)
        assertEquals(
            HeroPhase.Reconnecting,
            HeroPhaseMapper.decide(
                connected = true,
                connecting = false,
                reconnecting = true,
                handshakeOk = DeadPathRoam.workingSession(moved.handshakeFresh, moved.rxSinceAttempt),
            ),
        )
    }

    @Test
    fun freshHandshakeDoesNotHideAWifiToMobileSwitch() {
        val onWifi = DeadPathRoam.Attempt(
            recordedHandle = wifi,
            liveHandle = wifi,
            liveReady = true,
            handshakeFresh = true,
            rxSinceAttempt = 980L,
        )
        assertFalse(DeadPathRoam.redial(onWifi))
        assertTrue(DeadPathRoam.workingSession(onWifi.handshakeFresh, onWifi.rxSinceAttempt))
        assertEquals(
            HeroPhase.Protected,
            HeroPhaseMapper.decide(
                connected = true,
                connecting = false,
                reconnecting = false,
                handshakeOk = true,
            ),
        )

        val onMobile = onWifi.copy(liveHandle = mobile)
        assertTrue(DeadPathRoam.redial(onMobile))
        assertEquals(
            HeroPhase.Reconnecting,
            HeroPhaseMapper.decide(
                connected = true,
                connecting = false,
                reconnecting = DeadPathRoam.redial(onMobile),
                handshakeOk = true,
            ),
        )
    }

    @Test
    fun handshakeWithNoReceivedDataIsNotProtected() {
        assertFalse(DeadPathRoam.workingSession(handshakeFresh = true, rxSinceAttempt = 0L))
        assertEquals(
            HeroPhase.Reconnecting,
            HeroPhaseMapper.decide(
                connected = true,
                connecting = false,
                reconnecting = false,
                handshakeOk = DeadPathRoam.workingSession(handshakeFresh = true, rxSinceAttempt = 0L),
            ),
        )
    }

    @Test
    fun validatedMobileAfterASilentWifiIsAnImmediateRedial() {
        val moved = DeadPathRoam.Attempt(
            recordedHandle = wifi,
            liveHandle = mobile,
            liveReady = true,
            handshakeFresh = false,
            rxSinceAttempt = 0L,
        )
        assertTrue(DeadPathRoam.redial(moved))
        assertEquals(200L, EndpointRefresh.DEBOUNCE_MS)
        assertTrue(DeadPathRoam.timeToRedialMs() <= 3_000L)
    }

    @Test
    fun lostWifiAdoptsAddressedCellularEvenWhenItIsNotAnEarlyWifi() {
        val wifiHandle = 11L
        val mobileHandle = 22L
        val lost = EarlyUnderlay.onLost(EarlyUnderlay.State(defaultHandle = wifiHandle), wifiHandle)
        assertEquals(null, EarlyUnderlay.bindTarget(lost))
        val cellular = EarlyUnderlay.onAvailable(
            lost,
            mobileHandle,
            hasAddress = true,
            isDefault = false,
            adoptEarly = false,
        )
        assertEquals(mobileHandle, EarlyUnderlay.bindTarget(cellular))
    }

    @Test
    fun serviceRedialsWhenTheDefaultMovesAndDoesNotSwitchModeItself() {
        val service = kotlinSource("VeritasVpnService.kt")
        val health = service.substringAfter("addressed.identity != lastUnderlayIdentity")
            .substringBefore("lastStallCheckAtMs")
        assertTrue(health.contains("scheduleUnderlayAdapt(forceHard = moved && recordedGone)"))
        assertTrue(service.contains("DeadPathRoam.redial"))
        assertTrue(service.contains("DeadPathRoam.workingSession"))
        assertEquals(1, Regex("userAskedStealth = true").findAll(service).count())
    }

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
