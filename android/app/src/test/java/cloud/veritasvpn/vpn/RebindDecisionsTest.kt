package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RebindDecisionsTest {

    @Test
    fun softRebindRequiresNewHandshakeNotJustRxTraffic() {
        val attachedAt = 1_000L
        val baseline = 500L
        assertFalse(
            "rx traffic alone must not count as success",
            RebindDecisions.softRebindSucceeded(
                currentHandshakeMs = 0,
                attachedAtMs = attachedAt,
                baselineHandshakeMs = baseline,
            ),
        )
    }

    @Test
    fun softRebindRejectsStaleHandshakeFromBeforeSwitch() {
        val attachedAt = 2_000L
        val baseline = 1_500L
        assertFalse(
            "handshake from before the switch must not count",
            RebindDecisions.softRebindSucceeded(
                currentHandshakeMs = 1_000,
                attachedAtMs = attachedAt,
                baselineHandshakeMs = baseline,
            ),
        )
    }

    @Test
    fun softRebindAcceptsNewHandshakeAfterBothThresholds() {
        val attachedAt = 2_000L
        val baseline = 1_500L
        assertTrue(
            RebindDecisions.softRebindSucceeded(
                currentHandshakeMs = 2_500,
                attachedAtMs = attachedAt,
                baselineHandshakeMs = baseline,
            ),
        )
    }

    @Test
    fun softRebindRejectsHandshakeAtExactlyAttachedAt() {
        assertFalse(
            RebindDecisions.softRebindSucceeded(
                currentHandshakeMs = 2_000,
                attachedAtMs = 2_000,
                baselineHandshakeMs = 1_500,
            ),
        )
    }

    @Test
    fun softRebindRejectsHandshakeAtExactlyBaseline() {
        assertFalse(
            RebindDecisions.softRebindSucceeded(
                currentHandshakeMs = 1_500,
                attachedAtMs = 2_000,
                baselineHandshakeMs = 1_500,
            ),
        )
    }

    @Test
    fun hardLoopUsesValidatedUnderlayImmediately() {
        assertEquals(
            "wifi-validated",
            RebindDecisions.resolveHardLoopUnderlay(
                elapsedMs = 0,
                fallbackAfterMs = 10_000,
                validatedUnderlay = "wifi-validated",
                unvalidatedUnderlay = "wifi-unvalidated",
            ),
        )
    }

    @Test
    fun hardLoopUsesValidatedEvenAfterFallbackTimeout() {
        assertEquals(
            "wifi-validated",
            RebindDecisions.resolveHardLoopUnderlay(
                elapsedMs = 15_000,
                fallbackAfterMs = 10_000,
                validatedUnderlay = "wifi-validated",
                unvalidatedUnderlay = "wifi-unvalidated",
            ),
        )
    }

    @Test
    fun hardLoopWaitsWhenNoValidatedAndBeforeFallbackTimeout() {
        assertNull(
            RebindDecisions.resolveHardLoopUnderlay(
                elapsedMs = 5_000,
                fallbackAfterMs = 10_000,
                validatedUnderlay = null,
                unvalidatedUnderlay = "wifi-unvalidated",
            ),
        )
    }

    @Test
    fun hardLoopFallsBackToUnvalidatedAfterTimeout() {
        assertEquals(
            "wifi-unvalidated",
            RebindDecisions.resolveHardLoopUnderlay(
                elapsedMs = 10_000,
                fallbackAfterMs = 10_000,
                validatedUnderlay = null,
                unvalidatedUnderlay = "wifi-unvalidated",
            ),
        )
    }

    @Test
    fun hardLoopReturnsNullWhenNeitherAvailable() {
        assertNull(
            RebindDecisions.resolveHardLoopUnderlay(
                elapsedMs = 20_000,
                fallbackAfterMs = 10_000,
                validatedUnderlay = null,
                unvalidatedUnderlay = null,
            ),
        )
    }

    @Test
    fun hardLoopDoesNotExitOnTransientNull() {
        for (elapsed in listOf(0L, 1_000L, 5_000L, 9_999L)) {
            assertNull(
                "must not exit at elapsed=$elapsed",
                RebindDecisions.resolveHardLoopUnderlay(
                    elapsedMs = elapsed,
                    fallbackAfterMs = 10_000,
                    validatedUnderlay = null,
                    unvalidatedUnderlay = null,
                ),
            )
        }
    }
}
