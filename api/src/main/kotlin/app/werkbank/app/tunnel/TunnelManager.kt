package app.werkbank.app.tunnel

import app.werkbank.database.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.util.concurrent.ConcurrentHashMap

class TunnelManager {
    private val tunnels = ConcurrentHashMap<User.Id, MutableStateFlow<TunnelInstance?>>()

    private fun flowFor(userId: User.Id): MutableStateFlow<TunnelInstance?> =
        tunnels.getOrPut(userId) { MutableStateFlow(null) }

    /** The currently active tunnel for [user], or `null` if none is connected. */
    fun getTunnel(user: User): TunnelInstance? = flowFor(user.id.value).value

    /** Observe tunnel connect/disconnect for [user]. Emits the current value immediately. */
    fun tunnelFlow(user: User): StateFlow<TunnelInstance?> = flowFor(user.id.value)

    fun onNewIncomingTunnel(user: User, tunnelInstance: TunnelInstance) {
        flowFor(user.id.value).getAndUpdate { tunnelInstance }?.close()
    }

    fun onTunnelClosed(user: User) {
        flowFor(user.id.value).getAndUpdate { null }?.close()
    }
}
