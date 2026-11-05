package com.redtica.tv

import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    companion object {
        /** Lista de canales del Astra (definida por versión en app/build.gradle.kts). */
        val PLAYLIST_URL: String = BuildConfig.PLAYLIST_URL
        private const val REQ_VPN = 10
    }

    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var listPanel: LinearLayout
    private lateinit var list: RecyclerView
    private var tunnel: TunnelManager? = null

    private var player: ExoPlayer? = null
    private var adapter: ChannelAdapter? = null
    private val channels = mutableListOf<Channel>()
    private var current = 0
    private var vpnDenied = false
    /** Si el multicast no llegó una vez, el resto de la sesión se usa HTTP directo. */
    private var multicastFailed = false
    private var usingMulticast = false
    private var mcastLock: WifiManager.MulticastLock? = null
    private var connecting = false

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("redtica", MODE_PRIVATE) }
    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val retryPlay = Runnable { play(current) }
    /** Si en 5 s el canal multicast no arranca, se pasa a HTTP. */
    private val multicastTimeout = Runnable { if (usingMulticast) fallbackToHttp() }
    private val retryConnect = Runnable { connect() }

    /** Número que el cliente va marcando con el control (ej. 4 y 2 = canal 42). */
    private var typed = ""
    private val goToTyped = Runnable { jumpToTyped() }

    /** Formatos de pantalla que se rotan con la flecha derecha. */
    private val formats = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.formato_completa,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.formato_zoom,
        AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.formato_original
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player)
        status = findViewById(R.id.status)
        banner = findViewById(R.id.banner)
        listPanel = findViewById(R.id.listPanel)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        if (BuildConfig.USA_TUNEL) tunnel = TunnelManager(this)
        if (BuildConfig.USA_MULTICAST) acquireMulticastLock()

        // Toque en pantalla (teléfono): abre/cierra la lista
        playerView.setOnClickListener { if (isListVisible()) hideList() else showList() }
        // Toque largo (teléfono): cambia el formato de pantalla
        playerView.setOnLongClickListener { cycleFormat(); true }

        hideSystemUi()
        // Versión cliente: conexión directa por la red de REDTICA, sin VPN
        if (BuildConfig.USA_TUNEL) requestVpnThenConnect() else connect()
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
                tunnel?.up(assets.open("redtica.conf"))
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
        val ordered = try {
            M3uParser.applyOrder(loaded, assets.open("orden.txt").bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            loaded.onEachIndexed { i, c -> c.num = i + 1 }
        }
        channels.clear()
        channels.addAll(ordered)
        if (BuildConfig.USA_MULTICAST) {
            try {
                M3uParser.applyMulticast(channels, assets.open("multicast.txt").bufferedReader().use { it.readText() })
            } catch (_: Exception) {
            }
        }
        adapter = ChannelAdapter(channels) { idx ->
            hideList()
            play(idx)
        }
        list.adapter = adapter
        // Último canal visto (por id, así no cambia si se reordena la lista)
        val lastId = prefs.getString("ultimo_id", null)
        current = channels.indexOfFirst { it.id != null && it.id == lastId }.takeIf { it >= 0 } ?: 0
        ensurePlayer()
        play(current)
    }

    private fun ensurePlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (usingMulticast) {
                        fallbackToHttp(); return
                    }
                    showStatus(getString(R.string.sin_senal))
                    ui.removeCallbacks(retryPlay)
                    ui.postDelayed(retryPlay, 3000)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            status.visibility = View.GONE
                            ui.removeCallbacks(multicastTimeout)
                        }
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
        val ch = channels[current]
        prefs.edit().putString("ultimo_id", ch.id).apply()
        adapter?.current = current
        applyFormat(ch)
        ui.removeCallbacks(multicastTimeout)
        val mcast = if (BuildConfig.USA_MULTICAST && !multicastFailed) ch.mcast else null
        usingMulticast = mcast != null
        val uri = mcast ?: ch.url
        if (usingMulticast) ui.postDelayed(multicastTimeout, 5000)
        val item = MediaItem.Builder()
            .setUri(uri)
            .apply { if (uri.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player?.run {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
        showBanner(ch)
    }

    /** El multicast no llegó (ONU sin IPTV, red sin IGMP): se sigue por HTTP. */
    private fun fallbackToHttp() {
        ui.removeCallbacks(multicastTimeout)
        multicastFailed = true
        usingMulticast = false
        play(current)
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            mcastLock = wifi.createMulticastLock("redtica-tv").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
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
            digitOf(code)?.let { onDigit(it); return true }
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TV_ZOOM_MODE,
                KeyEvent.KEYCODE_PROG_RED -> {
                    cycleFormat(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    play(current + 1); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    play(current - 1); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                    showList(); return true
                }
            }
            if (isOkKey(code)) {
                if (typed.isNotEmpty()) jumpToTyped() else showList()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Número de canal con el control ----------

    private fun digitOf(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    private fun onDigit(d: Int) {
        if (typed.length >= 3) typed = ""
        typed += d
        banner.text = typed
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.removeCallbacks(goToTyped)
        // Con 3 cifras salta de una vez; si no, espera 2 s por la siguiente cifra
        if (typed.length == 3) jumpToTyped() else ui.postDelayed(goToTyped, 2000)
    }

    private fun jumpToTyped() {
        ui.removeCallbacks(goToTyped)
        val n = typed.toIntOrNull()
        typed = ""
        val idx = if (n == null) -1 else channels.indexOfFirst { it.num == n }
        if (idx >= 0) {
            play(idx)
        } else {
            banner.text = getString(R.string.canal_no_existe, n ?: 0)
            ui.postDelayed(hideBanner, 2000)
        }
    }

    // ---------- Formato de pantalla ----------

    /** Formato guardado para el canal; por defecto pantalla completa. */
    private fun formatIndex(ch: Channel): Int =
        prefs.getInt("formato_" + (ch.id ?: ch.name), 0).coerceIn(0, formats.size - 1)

    private fun applyFormat(ch: Channel) {
        playerView.resizeMode = formats[formatIndex(ch)].first
    }

    private fun cycleFormat() {
        if (channels.isEmpty()) return
        val ch = channels[current]
        val next = (formatIndex(ch) + 1) % formats.size
        prefs.edit().putInt("formato_" + (ch.id ?: ch.name), next).apply()
        applyFormat(ch)
        banner.text = getString(formats[next].second)
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 2500)
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
        banner.text = "${ch.num}   ${ch.name}"
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
        try {
            mcastLock?.release()
        } catch (_: Exception) {
        }
        val t = tunnel
        if (isFinishing && t != null) Thread { t.down() }.start()
    }
}
