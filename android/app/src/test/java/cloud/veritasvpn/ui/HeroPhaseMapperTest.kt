package cloud.veritasvpn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroPhaseMapperTest {

    @Test
    fun protectedWhenConnectedAndHandshakeOk() {
        assertEquals(
            HeroPhase.Protected,
            HeroPhaseMapper.decide(
                connected = true,
                connecting = false,
                reconnecting = false,
                handshakeOk = true,
            ),
        )
    }

    @Test
    fun reconnectingWhenConnectedButNoHandshake() {
        assertEquals(
            HeroPhase.Reconnecting,
            HeroPhaseMapper.decide(
                connected = true,
                connecting = false,
                reconnecting = false,
                handshakeOk = false,
            ),
        )
    }

    @Test
    fun reconnectingWhenReconnectingFlagSet() {
        assertEquals(
            HeroPhase.Reconnecting,
            HeroPhaseMapper.decide(
                connected = true,
                connecting = false,
                reconnecting = true,
                handshakeOk = false,
            ),
        )
    }

    @Test
    fun connectingWhenNotYetConnected() {
        assertEquals(
            HeroPhase.Connecting,
            HeroPhaseMapper.decide(
                connected = false,
                connecting = true,
                reconnecting = false,
                handshakeOk = false,
            ),
        )
    }

    @Test
    fun readyWhenDisconnected() {
        assertEquals(
            HeroPhase.Ready,
            HeroPhaseMapper.decide(
                connected = false,
                connecting = false,
                reconnecting = false,
                handshakeOk = false,
            ),
        )
    }

    @Test
    fun neverShowsProtectedWithoutHandshake() {
        val phase = HeroPhaseMapper.decide(
            connected = true,
            connecting = false,
            reconnecting = false,
            handshakeOk = false,
        )
        assertEquals(HeroPhase.Reconnecting, phase)
    }
}
