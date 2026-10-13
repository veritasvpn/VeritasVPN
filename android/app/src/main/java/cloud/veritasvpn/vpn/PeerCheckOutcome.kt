package cloud.veritasvpn.vpn

object PeerCheckOutcome {

    enum class Outcome { PEER_ALIVE, REFRESH_AND_RETRY, PEER_GONE, NETWORK_ERROR, SIGNED_OUT }

    fun map(statusCode: Int, networkError: Boolean = false): Outcome = when {
        statusCode == 200 -> Outcome.PEER_ALIVE
        statusCode == 401 -> Outcome.REFRESH_AND_RETRY
        statusCode == 404 || statusCode == 410 -> Outcome.PEER_GONE
        networkError -> Outcome.NETWORK_ERROR
        else -> Outcome.NETWORK_ERROR
    }
}
