package app.werkbank.app.tunnel

import app.werkbank.database.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class TunnelManager {
    private val tunnels = ConcurrentHashMap<User.Id, MutableStateFlow<TunnelInstance?>>()

    private fun flowFor(userId: User.Id): MutableStateFlow<TunnelInstance?> =
        tunnels.getOrPut(userId) { MutableStateFlow(null) }

    /** The currently active tunnel for [user], or `null` if none is connected. */
    fun getTunnel(user: User): TunnelInstance? = flowFor(user.id.value).value

    /** Observe tunnel connect/disconnect for [user]. Emits the current value immediately. */
    fun tunnelFlow(user: User): StateFlow<TunnelInstance?> = flowFor(user.id.value)

    /**
     * Claims the single tunnel slot of [user] for [tunnelInstance].
     *
     * Returns `false` when a live tunnel already holds the slot — the caller must then reject its
     * WebSocket, because two tunnels for one account would make request routing ambiguous.
     *
     * A stale predecessor never blocks: a session whose socket is already dead or that stopped
     * answering pings (see [TunnelInstance.isAlive]) is torn down and replaced. Without that, a
     * half-open TCP connection — laptop suspended, network dropped — would lock the account out
     * until the OS gets around to failing the socket.
     */
    fun tryRegister(user: User, tunnelInstance: TunnelInstance): Boolean {
        val flow = flowFor(user.id.value)
        while (true) {
            val current = flow.value
            if (current != null && current.isAlive) return false
            if (flow.compareAndSet(current, tunnelInstance)) {
                current?.terminate()
                return true
            }
        }
    }

    /**
     * Releases the slot when [tunnelInstance]'s socket ended. Only clears the slot if it still holds
     * this very instance, so a tunnel that was already replaced as stale cannot wipe its successor
     * on its way out.
     */
    fun onTunnelClosed(user: User, tunnelInstance: TunnelInstance) {
        flowFor(user.id.value).compareAndSet(tunnelInstance, null)
        tunnelInstance.close()
    }
}
