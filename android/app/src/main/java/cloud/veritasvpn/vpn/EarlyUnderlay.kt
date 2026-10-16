package cloud.veritasvpn.vpn

/**
 * The network a callback just delivered, before it is the validated default.
 *
 * Turning Wi-Fi on delivers that Network from onAvailable or onCapabilities
 * while cellular is still the validated default. The data plane binds to it
 * then, and binds again when it becomes the default. A callback for the other
 * transport does not replace it. Nothing here picks a third network or a mode.
 */
object EarlyUnderlay {
    data class State(
        val early: Long? = null,
        val defaultHandle: Long? = null,
        val bound: Long? = null,
    )

    /** The early callback network wins until it is lost or becomes the default. */
    fun bindTarget(state: State): Long? = state.early ?: state.defaultHandle

    fun onAvailable(
        state: State,
        handle: Long,
        hasAddress: Boolean,
        isDefault: Boolean,
        adoptEarly: Boolean,
    ): State = onUpdate(state, handle, hasAddress, isDefault, adoptEarly)

    fun onCapabilities(
        state: State,
        handle: Long,
        hasAddress: Boolean,
        isDefault: Boolean,
        adoptEarly: Boolean = false,
    ): State = onUpdate(state, handle, hasAddress, isDefault, adoptEarly)

    fun onLost(state: State, handle: Long): State = state.copy(
        early = if (state.early == handle) null else state.early,
        defaultHandle = if (state.defaultHandle == handle) null else state.defaultHandle,
        bound = if (state.bound == handle) null else state.bound,
    )

    /**
     * A new target restarts userspace once. A capability update for the target,
     * and that same network becoming the default, do not.
     */
    fun restartsUserspace(before: State, after: State): Boolean {
        val next = bindTarget(after) ?: return false
        return next != bindTarget(before)
    }

    /** Bind when the target changes, and again when that network becomes the default. */
    fun shouldRebind(bound: Long?, target: Long?, becameDefault: Boolean): Boolean {
        if (target == null) return false
        if (bound != target) return true
        return becameDefault
    }

    /**
     * The handshake wait stays on [attempt] while it is the callback target,
     * even if the validated default is still the previous network. A socket
     * bound to that target is not a stale-net abort.
     */
    fun attemptStillCurrent(attempt: Long, bindTarget: Long?, systemDefault: Long?): Boolean =
        attempt == bindTarget || attempt == systemDefault

    fun staleBlocks(bound: Long?, target: Long?, systemDefault: Long?): Boolean {
        if (bound != null && bound != 0L && bound == target) return false
        if (bound != null && bound != 0L && bound == systemDefault && target == systemDefault) return false
        return target != null && bound != target
    }

    private fun onUpdate(
        state: State,
        handle: Long,
        hasAddress: Boolean,
        isDefault: Boolean,
        adoptEarly: Boolean,
    ): State {
        if (!hasAddress) {
            return if (state.early == handle) state.copy(early = null) else state
        }
        if (isDefault) {
            return state.copy(
                defaultHandle = handle,
                early = if (state.early == handle) null else state.early,
            )
        }
        if (adoptEarly && (state.early == null || state.early == handle)) {
            return state.copy(early = handle)
        }
        return state
    }
}
