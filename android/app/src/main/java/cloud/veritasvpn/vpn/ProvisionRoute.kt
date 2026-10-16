package cloud.veritasvpn.vpn

/**
 * Where a reconnect sends the peer request.
 *
 * Always-on lockdown drops every socket that was not passed to
 * VpnService.protect while this app's interface is still up. An in-app
 * disconnect leaves that interface up and turns WireGuard off, so the normal
 * client never reaches the API. The protected system-default client is the
 * one the roam path already uses. With the interface closed, lockdown is not
 * blocking this app and the normal client works.
 */
object ProvisionRoute {
    enum class Route { PROTECTED, NORMAL, BLOCKED }

    fun choose(interfaceHeld: Boolean, protectedAvailable: Boolean): Route = when {
        interfaceHeld && protectedAvailable -> Route.PROTECTED
        interfaceHeld -> Route.BLOCKED
        else -> Route.NORMAL
    }

    fun logName(route: Route): String = when (route) {
        Route.PROTECTED -> "protected"
        Route.NORMAL -> "normal"
        Route.BLOCKED -> "blocked"
    }
}
