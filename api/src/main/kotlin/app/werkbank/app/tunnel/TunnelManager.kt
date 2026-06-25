package app.werkbank.app.tunnel

import app.werkbank.database.User
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

class TunnelManager {
    private val tunnels = mutableMapOf<User.Id, TunnelInstance>()
    private val tunnelsFlow = mutableMapOf<User.Id, List<Channel<TunnelInstance?>>>()

    fun getTunnel(user: User): TunnelInstance? = tunnels[user.id.value]

    suspend fun onNewIncomingTunnel(
        user: User,
        tunnelInstance: TunnelInstance,
    ) {
        tunnels[user.id.value] = tunnelInstance
        tunnelsFlow[user.id.value]?.forEach { it.send(tunnelInstance) }
    }

    suspend fun onTunnelClosed(user: User) {
        val tunnel = tunnels.remove(user.id.value)
        tunnel?.close()
        tunnelsFlow[user.id.value]?.forEach { it.send(null) }
    }

    suspend fun subscribeToTunnel(user: User): Channel<TunnelInstance?> {
        val channel = Channel<TunnelInstance?>(8, BufferOverflow.DROP_OLDEST)
        if (tunnelsFlow[user.id.value] == null) tunnelsFlow[user.id.value] = listOf(channel)
        else tunnelsFlow[user.id.value] = tunnelsFlow[user.id.value]!!.plus(channel)
        channel.send(tunnels[user.id.value])
        return channel
    }

    fun unsubscribeFromTunnel(user: User, channel: Channel<TunnelInstance?>) {
        channel.close()
        tunnelsFlow[user.id.value] = tunnelsFlow[user.id.value]!!.minus(channel)
    }
}