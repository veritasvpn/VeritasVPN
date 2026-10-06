package cloud.veritasvpn.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroMarkLookTest {
    @Test
    fun disconnectedPhasesStayDim() {
        for (phase in listOf(HeroPhase.Ready, HeroPhase.Upsell)) {
            for (motion in listOf(true, false)) {
                val look = heroMarkLook(phase, motion = motion, elapsedLiveMs = 60_000L)
                assertDim(look)
            }
        }
    }

    @Test
    fun protectedIsFullWhenTheRampIsAlreadySettled() {
        for (motion in listOf(true, false)) {
            assertFull(heroMarkLook(HeroPhase.Protected, motion = motion, elapsedLiveMs = -1L))
            assertFull(heroMarkLook(HeroPhase.Protected, motion = motion, elapsedLiveMs = 1400L))
        }
        assertFull(heroMarkLook(HeroPhase.Protected, motion = false, elapsedLiveMs = 0L))
    }

    @Test
    fun protectedContinuesTheConnectingRamp() {
        val elapsed = 700L
        val connecting = heroMarkLook(HeroPhase.Connecting, motion = true, elapsedLiveMs = elapsed)
        val protectedPhase = heroMarkLook(HeroPhase.Protected, motion = true, elapsedLiveMs = elapsed)
        assertEquals(connecting.brightness, protectedPhase.brightness, 0.0001f)
        assertEquals(connecting.saturate, protectedPhase.saturate, 0.0001f)
        assertEquals(connecting.opacity, protectedPhase.opacity, 0.0001f)
        assertTrue(protectedPhase.brightness > 0.58f)
        assertTrue(protectedPhase.brightness < 1.08f)
    }

    @Test
    fun reducedMotionSkipsTheRamp() {
        for (phase in listOf(HeroPhase.Checking, HeroPhase.Connecting)) {
            val look = heroMarkLook(phase, motion = false, elapsedLiveMs = 0L)
            assertFull(look)
        }
    }

    @Test
    fun connectingRampEasesTowardFullThenHolds() {
        val start = heroMarkLook(HeroPhase.Connecting, motion = true, elapsedLiveMs = 0L)
        assertDim(start)

        val glow = FastOutSlowInEasing.transform(700f / 1400f)
        val mid = heroMarkLook(HeroPhase.Checking, motion = true, elapsedLiveMs = 700L)
        assertEquals(lerp(0.58f, 1.08f, glow), mid.brightness, 0.0001f)
        assertEquals(lerp(0.48f, 1.12f, glow), mid.saturate, 0.0001f)
        assertEquals(lerp(0.66f, 1f, glow), mid.opacity, 0.0001f)

        assertFull(heroMarkLook(HeroPhase.Connecting, motion = true, elapsedLiveMs = 1400L))
        assertFull(heroMarkLook(HeroPhase.Checking, motion = true, elapsedLiveMs = 60_000L))
    }

    @Test
    fun glowIsMonotonicAndClamped() {
        assertEquals(0f, heroMarkGlow(-20L), 0f)
        assertEquals(0f, heroMarkGlow(0L), 0f)
        assertEquals(1f, heroMarkGlow(1400L), 0f)
        assertEquals(1f, heroMarkGlow(1401L), 0f)
        var previous = -1f
        for (elapsed in 0L..1400L step 50L) {
            val glow = heroMarkGlow(elapsed)
            assertTrue(glow >= previous)
            previous = glow
        }
    }

    @Test
    fun liveClockSurvivesConnectingIntoProtectedAndRestartsAfterDisconnect() {
        val checking = nextHeroMarkLiveStartMs(previousStartMs = -1L, phase = HeroPhase.Checking, nowMs = 1_000L)
        assertEquals(1_000L, checking)
        val connecting = nextHeroMarkLiveStartMs(previousStartMs = checking, phase = HeroPhase.Connecting, nowMs = 5_000L)
        assertEquals(1_000L, connecting)
        val protectedPhase = nextHeroMarkLiveStartMs(previousStartMs = connecting, phase = HeroPhase.Protected, nowMs = 5_500L)
        assertEquals(1_000L, protectedPhase)
        val alreadyUp = nextHeroMarkLiveStartMs(previousStartMs = -1L, phase = HeroPhase.Protected, nowMs = 5_500L)
        assertEquals(-1L, alreadyUp)
        val disconnected = nextHeroMarkLiveStartMs(previousStartMs = protectedPhase, phase = HeroPhase.Ready, nowMs = 6_000L)
        assertEquals(-1L, disconnected)
        val again = nextHeroMarkLiveStartMs(previousStartMs = disconnected, phase = HeroPhase.Connecting, nowMs = 7_000L)
        assertEquals(7_000L, again)
        assertTrue(heroMarkLive(HeroPhase.Checking))
        assertTrue(heroMarkLive(HeroPhase.Connecting))
        assertFalse(heroMarkLive(HeroPhase.Ready))
        assertFalse(heroMarkLive(HeroPhase.Upsell))
        assertFalse(heroMarkLive(HeroPhase.Protected))
    }

    @Test
    fun pulsePhaseIsWallClockAndSurvivesTheProtectedHandoff() {
        val during = pulseUnitFromElapsed(2_500L)
        val after = pulseUnitFromElapsed(2_500L)
        assertEquals(during, after, 0f)
        assertEquals(pulseUnitFromElapsed(400L), pulseUnitFromElapsed(400L + 2_560L), 0.0001f)
        assertTrue(pulseUnitFromElapsed(200L) > pulseUnitFromElapsed(0L))
    }

    @Test
    fun pulseStrengthEasesWithoutLeavingTheLoop() {
        assertEquals(0.16f, heroPulseAmplitude(HeroPhase.Ready, motion = true), 0f)
        assertEquals(0.16f, heroPulseAmplitude(HeroPhase.Connecting, motion = true), 0f)
        assertEquals(0.16f, heroPulseAmplitude(HeroPhase.Checking, motion = true), 0f)
        assertEquals(0.06f, heroPulseAmplitude(HeroPhase.Protected, motion = true), 0f)
        assertEquals(0f, heroPulseAmplitude(HeroPhase.Protected, motion = false), 0f)

        assertEquals(0.16f, pulseEnvelope(0.16f, 0.06f, 0L), 0.0001f)
        val mid = pulseEnvelope(0.16f, 0.06f, 100L)
        assertTrue(mid < 0.16f)
        assertTrue(mid > 0.06f)
        assertEquals(0.06f, pulseEnvelope(0.16f, 0.06f, 280L), 0.0001f)
        assertEquals(0.16f, pulseEnvelope(0.16f, 0.16f, 10_000L), 0.0001f)
    }

    @Test
    fun colorMatrixMatchesCssBrightnessThenSaturation() {
        val identity = heroMarkColorMatrix(brightness = 1f, saturate = 1f).values
        assertEquals(1f, identity[0], 0.0001f)
        assertEquals(0f, identity[1], 0.0001f)
        assertEquals(1f, identity[6], 0.0001f)
        assertEquals(1f, identity[12], 0.0001f)
        assertEquals(1f, identity[18], 0.0001f)

        val bright = heroMarkColorMatrix(brightness = 2f, saturate = 1f).values
        assertEquals(2f, bright[0], 0.0001f)
        assertEquals(2f, bright[6], 0.0001f)
        assertEquals(2f, bright[12], 0.0001f)
        assertEquals(0f, bright[1], 0.0001f)

        val gray = heroMarkColorMatrix(brightness = 1f, saturate = 0f).values
        assertEquals(0.213f, gray[0], 0.0001f)
        assertEquals(0.715f, gray[1], 0.0001f)
        assertEquals(0.072f, gray[2], 0.0001f)
        assertEquals(gray[0], gray[5], 0.0001f)
        assertEquals(gray[1], gray[6], 0.0001f)
    }

    private fun assertDim(look: HeroMarkLook) {
        assertEquals(0.58f, look.brightness, 0.0001f)
        assertEquals(0.48f, look.saturate, 0.0001f)
        assertEquals(0.66f, look.opacity, 0.0001f)
    }

    private fun assertFull(look: HeroMarkLook) {
        assertEquals(1.08f, look.brightness, 0.0001f)
        assertEquals(1.12f, look.saturate, 0.0001f)
        assertEquals(1f, look.opacity, 0.0001f)
    }

    private fun lerp(start: Float, stop: Float, fraction: Float): Float {
        return start + (stop - start) * fraction
    }
}
