package cloud.veritasvpn.vpn

object ReconnectDecisions {

    fun shouldRestoreSession(hasConfig: Boolean, hasPeerId: Boolean): Boolean =
        hasConfig && hasPeerId

    fun shouldTimeoutDelete(
        connecting: Boolean,
        hadEstablishedSession: Boolean,
        sessionEstablishedAt: Long,
        awaitingVpnConsent: Boolean,
    ): Boolean {
        val stableEstablished = sessionEstablishedAt > 0L
        return connecting && !hadEstablishedSession && !stableEstablished && !awaitingVpnConsent
    }

    fun shouldActivityReconnect(
        userWantsConnected: Boolean,
        connected: Boolean,
        connecting: Boolean,
        peerGoneAt: Long,
        reprovisionInProgressAt: Long,
        nowMs: Long,
        hasSavedSession: Boolean,
    ): Boolean {
        val reprovisionBusy = reprovisionInProgressAt > 0L &&
            nowMs - reprovisionInProgressAt < 60_000L
        return userWantsConnected && !connected && !connecting &&
            peerGoneAt > 0L && !reprovisionBusy && !hasSavedSession
    }
}
