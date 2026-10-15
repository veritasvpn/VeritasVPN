package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoamRecoveryTest {
    private val wifi = 10L
    private val cellular = 20L

    @Test
    fun sameNetworkCallbackLeavesAHealthyHandshakeAlone() {
        val plan = RoamRecovery.plan(
            previousHandle = wifi,
            currentHandle = wifi,
            currentHasAddress = true,
            failedSocketSwaps = 0,
            handshakeHealthy = true,
        )
        assertFalse(plan.touchTunnel)
        assertFalse(plan.readPeerOnCurrentNetwork)
        assertNull(plan.install)
        assertTrue(RoamRecovery.sameNetworkLeavesTunnelAlone(sameHandle = true, handshakeHealthy = true))
        assertFalse(RoamRecovery.sameNetworkLeavesTunnelAlone(sameHandle = true, handshakeHealthy = false))
    }

    @Test
    fun wifiLostThenCellularReadsThePeerAndRebuildsAfterOneFailedSwap() {
        val lost = RoamRecovery.plan(
            previousHandle = wifi,
            currentHandle = null,
            currentHasAddress = false,
            failedSocketSwaps = 0,
            handshakeHealthy = false,
        )
        assertFalse(lost.touchTunnel)

        val cellularUp = RoamRecovery.plan(
            previousHandle = wifi,
            currentHandle = cellular,
            currentHasAddress = true,
            failedSocketSwaps = 0,
            handshakeHealthy = false,
        )
        assertTrue(cellularUp.touchTunnel)
        assertTrue(cellularUp.readPeerOnCurrentNetwork)
        assertEquals(RoamRecovery.Install.SOCKET_SWAP, cellularUp.install)

        val afterFailedSwap = RoamRecovery.plan(
            previousHandle = wifi,
            currentHandle = cellular,
            currentHasAddress = true,
            failedSocketSwaps = 1,
            handshakeHealthy = false,
        )
        assertEquals(RoamRecovery.Install.FULL_REBUILD, afterFailedSwap.install)
        assertTrue(afterFailedSwap.readPeerOnCurrentNetwork)
        assertFalse(
            RoamRecovery.failureEligible(
                socketSwapTried = true,
                rebuildTried = false,
                recoveryPending = false,
            ),
        )
        assertFalse(
            RoamRecovery.failureEligible(
                socketSwapTried = true,
                rebuildTried = true,
                recoveryPending = true,
            ),
        )
        assertTrue(
            RoamRecovery.failureEligible(
                socketSwapTried = true,
                rebuildTried = true,
                recoveryPending = false,
            ),
        )
    }
}
