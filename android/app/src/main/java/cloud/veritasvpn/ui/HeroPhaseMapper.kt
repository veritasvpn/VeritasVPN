package cloud.veritasvpn.ui

import cloud.veritasvpn.ui.HeroPhase

object HeroPhaseMapper {

    fun decide(
        connected: Boolean,
        connecting: Boolean,
        reconnecting: Boolean,
        handshakeOk: Boolean,
    ): HeroPhase = when {
        connecting && !connected -> HeroPhase.Connecting
        connected && reconnecting -> HeroPhase.Reconnecting
        connected && !handshakeOk -> HeroPhase.Reconnecting
        connected && handshakeOk -> HeroPhase.Protected
        else -> HeroPhase.Ready
    }
}
