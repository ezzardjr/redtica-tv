package com.redtica.tv

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.InputStream

/** Levanta el túnel WireGuard embebido en la app (assets/redtica.conf). */
class TunnelManager(context: Context) {

    private val backend = GoBackend(context.applicationContext)

    private val tunnel = object : Tunnel {
        override fun getName() = "redtica"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    fun up(conf: InputStream) {
        val config = conf.use { Config.parse(it) }
        backend.setState(tunnel, Tunnel.State.UP, config)
    }

    fun down() {
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (_: Exception) {
        }
    }
}
