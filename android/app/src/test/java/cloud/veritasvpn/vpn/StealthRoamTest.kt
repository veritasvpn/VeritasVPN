package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Stealth network change dials one tunnel. The burst of link and capability
 * callbacks that follows, and a handshake that is already a few seconds old,
 * must not open another one.
 */
class StealthRoamTest {

    @Test
    fun oneSwitchThenRepeatedCallbacksKeepASingleTunnel() {
        var memory = StealthRoam.Memory()
        val settled = StealthRoam.next(memory, observation(live = WIFI, bound = WIFI, open = true, now = T0))
        memory = settled.first
        assertEquals(StealthRoam.Action.HEALTHY, settled.second)
        assertEquals(0, memory.dials)

        val switched = StealthRoam.next(
            memory,
            observation(live = CELL, bound = WIFI, open = true, now = T0 + 1_000),
        )
        memory = switched.first
        assertEquals(StealthRoam.Action.DIAL, switched.second)
        assertEquals(1, memory.dials)

        val burst = listOf(
            observation(live = CELL, bound = WIFI, open = true, now = T0 + 1_050),
            observation(live = CELL, bound = WIFI, open = false, dialing = true, now = T0 + 1_200),
            observation(live = CELL, bound = null, open = false, dialing = true, now = T0 + 1_400),
            observation(live = CELL, bound = CELL, open = false, dialing = true, now = T0 + 2_000),
            observation(
                live = CELL,
                bound = CELL,
                open = true,
                handshakeMs = T0 + 2_500 - 11_000,
                rx = 16_000,
                tx = 13_000,
                now = T0 + 2_500,
            ),
        ) + (1..8).map { step ->
            observation(
                live = CELL,
                bound = CELL,
                open = true,
                handshakeMs = T0 + 2_500 - 11_000,
                rx = 16_000L + step * 1_000,
                tx = 13_000L + step * 2_000,
                now = T0 + 3_000 + step * 400L,
                force = step % 2 == 0,
            )
        }
        var last = StealthRoam.Action.WAIT
        for (sample in burst) {
            val step = StealthRoam.next(memory, sample)
            memory = step.first
            last = step.second
            assertEquals(1, memory.dials)
        }
        assertEquals(StealthRoam.Action.HEALTHY, last)
    }

    @Test
    fun aSecondNetworkChangeDialsExactlyOneMoreTunnel() {
        var memory = dialOnto(CELL)
        assertEquals(1, memory.dials)
        val again = StealthRoam.next(
            memory,
            observation(live = WIFI, bound = CELL, open = true, rx = 20_000, tx = 18_000, now = T0 + 40_000),
        )
        memory = again.first
        assertEquals(StealthRoam.Action.DIAL, again.second)
        assertEquals(2, memory.dials)
        repeat(6) { step ->
            val follow = StealthRoam.next(
                memory,
                observation(
                    live = WIFI,
                    bound = WIFI,
                    open = true,
                    rx = 21_000L + step,
                    tx = 19_000L + step,
                    now = T0 + 41_000 + step * 500L,
                    force = true,
                ),
            )
            memory = follow.first
            assertEquals(StealthRoam.Action.HEALTHY, follow.second)
            assertEquals(2, memory.dials)
        }
    }

    @Test
    fun bytesMovingWithAnElevenSecondHandshakeIsHealthy() {
        val memory = StealthRoam.Memory(
            issuedHandle = CELL,
            issuedAtMs = T0,
            baselineRx = 1_000,
            baselineTx = 1_000,
            dials = 1,
        )
        val step = StealthRoam.next(
            memory,
            observation(
                live = CELL,
                bound = CELL,
                open = true,
                handshakeMs = T0 + 5_000 - 11_000,
                rx = 27_000,
                tx = 37_000,
                now = T0 + 5_000,
                force = true,
            ),
        )
        assertEquals(StealthRoam.Action.HEALTHY, step.second)
        assertEquals(1, step.first.dials)
    }

    @Test
    fun forceDuringTheOpenDialDoesNotStartAnotherTunnel() {
        var memory = dialOnto(CELL)
        val forcing = StealthRoam.next(
            memory,
            observation(
                live = CELL,
                bound = WIFI,
                open = false,
                dialing = true,
                now = T0 + 2_000,
                force = true,
            ),
        )
        memory = forcing.first
        assertEquals(StealthRoam.Action.WAIT, forcing.second)
        assertEquals(1, memory.dials)
    }

    @Test
    fun txOnlyGrowthCountsAsDeadAndCanRedialWhileTheSocketIsOpen() {
        val issued = StealthRoam.Memory(
            issuedHandle = CELL,
            issuedAtMs = T0,
            baselineRx = 1_000,
            baselineTx = 1_000,
            dials = 1,
        )
        val duringGap = StealthRoam.next(
            issued,
            observation(
                live = CELL,
                bound = CELL,
                open = true,
                handshakeMs = 0,
                rx = 1_000,
                tx = 50_000,
                now = T0 + 10_000,
                force = true,
            ),
        )
        assertEquals(StealthRoam.Action.WAIT, duringGap.second)
        assertEquals(1, duringGap.first.dials)
        val later = StealthRoam.next(
            issued,
            observation(
                live = CELL,
                bound = CELL,
                open = true,
                handshakeMs = 0,
                rx = 1_000,
                tx = 80_000,
                now = T0 + StealthRoam.SAME_NETWORK_GAP_MS + 1,
                force = true,
            ),
        )
        assertEquals(StealthRoam.Action.DIAL, later.second)
        assertEquals(2, later.first.dials)
    }

    @Test
    fun aDeadTunnelCanBeRedialedOnceTheGapHasPassed() {
        val issued = StealthRoam.Memory(
            issuedHandle = CELL,
            issuedAtMs = T0,
            baselineRx = 100,
            baselineTx = 100,
            dials = 1,
        )
        val early = StealthRoam.next(
            issued,
            observation(
                live = CELL,
                bound = CELL,
                open = false,
                handshakeMs = 0,
                rx = 100,
                tx = 100,
                now = T0 + 10_000,
                force = true,
            ),
        )
        assertEquals(StealthRoam.Action.WAIT, early.second)
        assertEquals(1, early.first.dials)
        val later = StealthRoam.next(
            issued,
            observation(
                live = CELL,
                bound = CELL,
                open = false,
                handshakeMs = 0,
                rx = 100,
                tx = 100,
                now = T0 + StealthRoam.SAME_NETWORK_GAP_MS + 1,
                force = true,
            ),
        )
        assertEquals(StealthRoam.Action.DIAL, later.second)
        assertEquals(2, later.first.dials)
    }

    private fun dialOnto(handle: Long): StealthRoam.Memory {
        val first = StealthRoam.next(
            StealthRoam.Memory(),
            observation(live = handle, bound = null, open = false, dialing = false, now = T0),
        )
        assertEquals(StealthRoam.Action.DIAL, first.second)
        return first.first
    }

    private fun observation(
        live: Long?,
        bound: Long?,
        open: Boolean,
        dialing: Boolean = false,
        handshakeMs: Long = T0 - 11_000,
        rx: Long = 2_000,
        tx: Long = 1_500,
        now: Long,
        force: Boolean = false,
    ) = StealthRoam.Observation(
        liveHandle = live,
        boundHandle = bound,
        open = open,
        dialing = dialing,
        handshakeMs = handshakeMs,
        rx = rx,
        tx = tx,
        nowMs = now,
        force = force,
    )

    private companion object {
        const val WIFI = 11L
        const val CELL = 22L
        const val T0 = 1_000_000L
    }
}
