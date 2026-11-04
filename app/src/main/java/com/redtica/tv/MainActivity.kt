package com.redtica.tv

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    companion object {
        /** Lista de canales del Astra (por el túnel WireGuard). */
        const val PLAYLIST_URL = "http://10.1.1.10:4000/playlist.m3u8?ts"
        private const val REQ_VPN = 10
    }

    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var listPanel: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var tunnel: TunnelManager

    private var player: ExoPlayer? = null
    private var adapter: ChannelAdapter? = null
    private val channels = mutableListOf<Channel>()
    private var current = 0
    private var vpnDenied = false
    private var connecting = false

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("redtica", MODE_PRIVATE) }
    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val retryPlay = Runnable { play(current) }
    private val retryConnect = Runnable { connect() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player)
        status = findViewById(R.id.status)
        banner = findViewById(R.id.banner)
        listPanel = findViewById(R.id.listPanel)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        tunnel = TunnelManager(this)

        // Toque en pantalla (teléfono): abre/cierra la lista
        playerView.setOnClickListener { if (isListVisible()) hideList() else showList() }

        hideSystemUi()
        requestVpnThenConnect()
    }

    // ---------- Túnel WireGuard ----------

    private fun requestVpnThenConnect() {
        // La primera vez Android pide aceptar la conexión VPN (una sola vez).
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VPN)
        } else {
            connect()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                vpnDenied = false
                connect()
            } else {
                vpnDenied = true
                showStatus(getString(R.string.vpn_denegada))
            }
        }
    }

    private fun connect() {
        if (connecting) return
        connecting = true
        showStatus(getString(R.string.conectando))
        Thread {
            try {
                tunnel.up(assets.open("redtica.conf"))
                ui.post { showStatus(getString(R.string.cargando_lista)) }
                val loaded = loadPlaylistWithRetry()
                ui.post {
                    connecting = false
                    onChannels(loaded)
                }
            } catch (e: Exception) {
                ui.post {
                    connecting = false
                    showStatus(getString(R.string.error_conexion) + "\n" + (e.message ?: e.javaClass.simpleName))
                    ui.removeCallbacks(retryConnect)
                    ui.postDelayed(retryConnect, 10_000)
                }
            }
        }.start()
    }

    /** El handshake de WireGuard tarda un par de segundos: reintenta la lista. */
    private fun loadPlaylistWithRetry(): List<Channel> {
        var last: Exception? = null
        repeat(8) {
            try {
                return M3uParser.fetch(PLAYLIST_URL)
            } catch (e: Exception) {
                last = e
                Thread.sleep(2000)
            }
        }
        throw last ?: IllegalStateException("Sin respuesta del servidor")
    }

    // ---------- Canales y reproducción ----------

    private fun onChannels(loaded: List<Channel>) {
        if (loaded.isEmpty()) {
            showStatus(getString(R.string.lista_vacia))
            return
        }
        channels.clear()
        channels.addAll(loaded)
        adapter = ChannelAdapter(channels) { idx ->
            hideList()
            play(idx)
        }
        list.adapter = adapter
        current = prefs.getInt("ultimo", 0).coerceIn(0, channels.size - 1)
        ensurePlayer()
        play(current)
    }

    private fun ensurePlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    showStatus(getString(R.string.sin_senal))
                    ui.removeCallbacks(retryPlay)
                    ui.postDelayed(retryPlay, 3000)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> status.visibility = View.GONE
                        Player.STATE_ENDED -> {
                            ui.removeCallbacks(retryPlay)
                            ui.postDelayed(retryPlay, 1000)
                        }
                        else -> Unit
                    }
                }
            })
        }
    }

    private fun play(index: Int) {
        if (channels.isEmpty()) return
        ui.removeCallbacks(retryPlay)
        current = (index % channels.size + channels.size) % channels.size
        prefs.edit().putInt("ultimo", current).apply()
        adapter?.current = current

        val ch = channels[current]
        val item = MediaItem.Builder()
            .setUri(ch.url)
            .apply { if (ch.url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player?.run {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
        showBanner(ch)
    }

    // ---------- Control remoto ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // Atrás con la lista abierta: solo cierra la lista
        if (code == KeyEvent.KEYCODE_BACK && isListVisible()) {
            if (event.action == KeyEvent.ACTION_UP) hideList()
            return true
        }

        // OK cuando se negó el permiso VPN: volver a pedirlo
        if (vpnDenied && event.action == KeyEvent.ACTION_DOWN && isOkKey(code)) {
            requestVpnThenConnect()
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && !isListVisible() && channels.isNotEmpty()) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    play(current + 1); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    play(current - 1); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                    showList(); return true
                }
            }
            if (isOkKey(code)) {
                showList(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isOkKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER ||
            code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    // ---------- Interfaz ----------

    private fun isListVisible() = listPanel.visibility == View.VISIBLE

    private fun showList() {
        if (channels.isEmpty()) return
        listPanel.visibility = View.VISIBLE
        list.scrollToPosition(current)
        list.post {
            list.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus()
        }
    }

    private fun hideList() {
        listPanel.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun showBanner(ch: Channel) {
        banner.text = "${current + 1}   ${ch.name}"
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 3500)
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ---------- Ciclo de vida ----------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        if (channels.isNotEmpty()) play(current)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(retryPlay)
        player?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (isFinishing) Thread { tunnel.down() }.start()
    }
}
