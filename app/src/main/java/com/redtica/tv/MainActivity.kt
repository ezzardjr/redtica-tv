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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import android.view.Display
import androidx.media3.exoplayer.analytics.AnalyticsListener
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@UnstableApi
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
    /** El multicast ya funcionó en esta red: un corte no manda a HTTP, se reintenta multicast. */
    private var multicastWorked = false

    // ---------- Diagnóstico (tecla INFO o azul) ----------
    private var diag: TextView? = null
    private var okPending = false
    private var intro: IntroView? = null

    /** Animación de inicio: 10 s la primera vez del día, 3 s las demás. */
    private var outro: IntroView? = null
    private var selector: DefaultTrackSelector? = null
    // Modo túnel (prueba): el video pasa por el procesador de imagen del TV, que desentrelaza
    private var tunnelOn: Boolean
        get() = prefs.getBoolean("tunnel", false)
        set(v) { prefs.edit().putBoolean("tunnel", v).apply() }

    private fun toggleTunnel() {
        tunnelOn = !tunnelOn
        selector?.let { it.setParameters(it.buildUponParameters().setTunnelingEnabled(tunnelOn)) }
        android.widget.Toast.makeText(this, "Modo túnel: " + (if (tunnelOn) "SÍ" else "NO"), android.widget.Toast.LENGTH_SHORT).show()
        play(current)
    }

    /** Guarda cualquier cierre inesperado para mostrarlo en la próxima apertura. */
    private fun installCrashCatcher() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val sp = getSharedPreferences("redtica_crash", MODE_PRIVATE)
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                sp.edit().putString("last", "v${BuildConfig.VERSION_NAME} | ${t.name}\n" + sw.toString().take(1500)).commit()
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun showLastCrash() {
        val sp = getSharedPreferences("redtica_crash", MODE_PRIVATE)
        val txt = sp.getString("last", null) ?: return
        sp.edit().remove("last").apply()
        val t = TextView(this).apply {
            text = "La app se cerró por un error la vez anterior. Tome una foto de esto:\n\n$txt"
            setTextColor(Color.WHITE); textSize = 11f
            setBackgroundColor(0xEE400000.toInt()); setPadding(32, 24, 32, 24)
        }
        (findViewById<View>(android.R.id.content) as FrameLayout).addView(t,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        ui.postDelayed({ (t.parent as? android.view.ViewGroup)?.removeView(t) }, 30_000)
    }

    /** Video de salida: silencia el canal, lo reproduce y cierra la app al terminar. */
    private fun showOutro() {
        if (outro != null) return
        if (diag?.visibility == View.VISIBLE) toggleDiag()
        hideOkHint()
        player?.volume = 0f
        player?.pause()
        val v = IntroView(this, isExit = true) {
            outro = null
            finishAndRemoveTask()
        }
        outro = v
        (findViewById<View>(android.R.id.content) as FrameLayout).addView(
            v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        v.start()
    }

    private fun showIntro() {
        // Se muestra completa cada vez que se abre la app
        val v = IntroView(this) {
            intro = null
            showLastCrash()
            player?.volume = 1f   // vuelve el audio del canal
        }
        intro = v
        (findViewById<View>(android.R.id.content) as FrameLayout).addView(
            v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        v.start()
    }
    private var okDownAt = 0L
    private var okHoldFired = false
    private var lastKeyInfo = "-"
    private var okHintView: TextView? = null
    private val okHold = Runnable {
        if (okPending) { okPending = false; okHoldFired = true; hideOkHint(); toggleDiag() }
    }
    private val okHint = Runnable {
        if (!okPending) return@Runnable
        val t = TextView(this).apply {
            text = "Mantenga OK presionado para abrir el diagnóstico…"
            setTextColor(android.graphics.Color.WHITE); textSize = 20f
            setBackgroundColor(0xCC000000.toInt()); setPadding(40, 24, 40, 24)
        }
        okHintView = t
        (findViewById<View>(android.R.id.content) as FrameLayout).addView(t,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 80 })
    }
    private fun hideOkHint() {
        okHintView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        okHintView = null
    }

    // ---------- Ajuste de la frecuencia de la pantalla a los cuadros del canal ----------
    @Volatile private var fpsSamples = 0
    @Volatile private var fpsFirstUs = -1L
    @Volatile private var fpsLastUs = -1L
    @Volatile private var fpsDone = false
    private var detectedFps = 0f
    private val frameRateProbe = VideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
        if (fpsDone) return@VideoFrameMetadataListener
        if (format.frameRate > 0f && format.frameRate < 121f) {
            fpsDone = true
            ui.post { applyDisplayMode(format.frameRate) }
            return@VideoFrameMetadataListener
        }
        if (fpsFirstUs < 0) fpsFirstUs = presentationTimeUs
        if (presentationTimeUs > fpsLastUs) fpsLastUs = presentationTimeUs
        fpsSamples++
        if (fpsSamples >= 90 && fpsLastUs > fpsFirstUs) {
            fpsDone = true
            val fps = (fpsSamples - 1) * 1_000_000f / (fpsLastUs - fpsFirstUs)
            ui.post { applyDisplayMode(fps) }
        }
    }

    private fun resetFrameRateProbe() {
        fpsSamples = 0; fpsFirstUs = -1L; fpsLastUs = -1L; fpsDone = false
    }

    /** Elige el modo de pantalla (misma resolución) cuya frecuencia sea múltiplo exacto de los cuadros del canal. */
    private fun applyDisplayMode(rawFps: Float) {
        // Redondea a valores estándar de TV (23.976, 25, 29.97, 50, 59.94...)
        val std = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
        val fps = std.minByOrNull { kotlin.math.abs(it - rawFps) }?.takeIf { kotlin.math.abs(it - rawFps) < 1.5f } ?: rawFps
        detectedFps = fps
        if (android.os.Build.VERSION.SDK_INT < 23) return
        try {
            @Suppress("DEPRECATION")
            val display: Display = windowManager.defaultDisplay
            val cur = display.mode
            val candidates = display.supportedModes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            fun score(hz: Float): Float {
                val ratio = hz / fps
                val k = kotlin.math.round(ratio)
                return if (k < 1f) 99f else kotlin.math.abs(ratio - k) / k
            }
            val best = candidates.minByOrNull { score(it.refreshRate) * 1000 + (if (it.refreshRate < 48f) 1f else 0f) } ?: return
            if (score(best.refreshRate) > 0.002f) return
            if (best.modeId != cur.modeId && score(cur.refreshRate) > 0.0005f) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
        } catch (_: Exception) {
        }
    }

    private fun displayHz(): String = try {
        @Suppress("DEPRECATION")
        "%.2f Hz".format(windowManager.defaultDisplay.refreshRate)
    } catch (_: Exception) { "?" }
    private var droppedFrames = 0
    private var rebuffers = 0
    private var videoDecoder = "-"
    private val diagTick = object : Runnable {
        override fun run() {
            try { updateDiag() } catch (e: Throwable) { diag?.text = "Error en diagnóstico: ${e.javaClass.simpleName}: ${e.message}" }
            ui.postDelayed(this, 1000)
        }
    }
    private var usingMulticast = false
    private var mcastLock: WifiManager.MulticastLock? = null
    private var connecting = false

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("redtica", MODE_PRIVATE) }
    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val retryPlay = Runnable { play(current) }
    /** Si en 5 s el canal multicast no arranca, se pasa a HTTP. */
    private val multicastTimeout = Runnable { if (usingMulticast && !multicastWorked) fallbackToHttp() }
    private val retryConnect = Runnable { connect() }

    /** Número que el cliente va marcando con el control (ej. 4 y 2 = canal 42). */
    private var typed = ""
    private val goToTyped = Runnable { jumpToTyped() }

    /** Canal al que ya se le aplicó el audio guardado (para hacerlo una sola vez). */
    private var audioAppliedFor: String? = null

    /** Formatos de pantalla que se rotan con la flecha derecha. */
    private val formats = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.formato_completa,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.formato_zoom,
        AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.formato_original
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
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
        // Toque largo (teléfono): cambia el audio
        playerView.setOnLongClickListener { cycleAudio(); true }

        hideSystemUi()
        showIntro()
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
        // Colchón de video: arranca con 3 s guardados y, si la red se traba,
        // espera a tener 6 s antes de seguir, para que no se vuelva a pegar enseguida.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 3_000, 6_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // Decodificación en modo asíncrono (más fluida en TVs) y respaldo a otro decodificador si falla
        val renderers = DefaultRenderersFactory(this)
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
        // Modo túnel: el TV sincroniza audio y video por hardware (como Netflix/YouTube).
        // Si el TV no lo soporta, ExoPlayer usa el modo normal automáticamente.
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(tunnelOn))
        }
        selector = trackSelector
        player = ExoPlayer.Builder(this, renderers).setLoadControl(loadControl)
            .setTrackSelector(trackSelector).build().also { p ->
            if (intro != null) p.volume = 0f   // el canal carga en silencio detrás de la animación
            p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            p.setVideoFrameMetadataListener(frameRateProbe)
            playerView.player = p
            // Audio en español siempre que el canal lo traiga (si no, el que tenga)
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguages("es", "spa")
                .setPreferredTextLanguages("es")
                .build()
            p.addAnalyticsListener(object : AnalyticsListener {
                override fun onDroppedVideoFrames(t: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                    droppedFrames += count
                }
                override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    videoDecoder = name
                }
                override fun onPlaybackStateChanged(t: AnalyticsListener.EventTime, state: Int) {
                    if (state == Player.STATE_BUFFERING && p.playWhenReady && p.currentPosition > 0) rebuffers++
                }
            })
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (usingMulticast && !multicastWorked) {
                        fallbackToHttp(); return
                    }
                    showStatus(getString(R.string.sin_senal))
                    ui.removeCallbacks(retryPlay)
                    ui.postDelayed(retryPlay, 3000)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    applySavedAudio()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            status.visibility = View.GONE
                            ui.removeCallbacks(multicastTimeout)
                            if (usingMulticast) multicastWorked = true
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
        // Todo por multicast (cable y WiFi): no aumenta el consumo por cliente.
        val mcast = if (BuildConfig.USA_MULTICAST && !multicastFailed) ch.mcast else null
        usingMulticast = mcast != null
        val uri = mcast ?: ch.url
        if (usingMulticast) ui.postDelayed(multicastTimeout, 5000)
        val item = MediaItem.Builder()
            .setUri(uri)
            .apply { if (uri.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        // Canal nuevo: vuelve a la preferencia (español) hasta ver si hay audio guardado
        audioAppliedFor = null
        player?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        }
        player?.run {
            if (usingMulticast) {
                // Lector UDP propio con búfer de 8 MB para aguantar las ráfagas del WiFi
                setMediaSource(
                    ProgressiveMediaSource.Factory(DataSource.Factory { BigUdpDataSource() })
                        .createMediaSource(item)
                )
            } else {
                setMediaItem(item)
            }
            prepare()
            playWhenReady = true
        }
        resetFrameRateProbe()
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

        // Cualquier tecla salta la animación de inicio
        intro?.let { if (event.action == KeyEvent.ACTION_UP) it.skip(); return true }

        // Animación de salida en curso: cualquier tecla la termina y cierra
        outro?.let { if (event.action == KeyEvent.ACTION_UP) it.skip(); return true }

        // Atrás sin lista abierta: animación de salida y luego cerrar la app
        if (code == KeyEvent.KEYCODE_BACK && !isListVisible()) {
            if (event.action == KeyEvent.ACTION_UP) showOutro()
            return true
        }

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
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Con el panel de diagnóstico abierto, Derecha prueba el modo túnel
                    if (diag?.visibility == View.VISIBLE) toggleTunnel() else cycleFormat()
                    return true
                }
                KeyEvent.KEYCODE_TV_ZOOM_MODE,
                KeyEvent.KEYCODE_PROG_RED -> {
                    cycleFormat(); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
                KeyEvent.KEYCODE_PROG_GREEN -> {
                    cycleAudio(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    play(current + 1); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    play(current - 1); return true
                }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE -> {
                    toggleDiag(); return true
                }
                // Botón de menú/ajustes (el cuadrado del control Xiaomi): panel de diagnóstico
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                    toggleDiag(); return true
                }
                KeyEvent.KEYCODE_GUIDE -> {
                    showList(); return true
                }
            }
        }
        // OK: pulsación corta = lista (o ir al número tecleado); mantenido 5 s = panel de diagnóstico
        if (isOkKey(code) && !isListVisible() && channels.isNotEmpty()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!okPending && !okHoldFired) {
                    okPending = true
                    okDownAt = android.os.SystemClock.uptimeMillis()
                    ui.postDelayed(okHint, 1500)
                    ui.postDelayed(okHold, 5000)
                }
                return true   // las repeticiones del control se ignoran
            }
            if (event.action == KeyEvent.ACTION_UP) {
                ui.removeCallbacks(okHint); ui.removeCallbacks(okHold)
                hideOkHint()
                val held = maxOf(android.os.SystemClock.uptimeMillis() - okDownAt, event.eventTime - event.downTime)
                lastKeyInfo = "OK mantenido ${held} ms"
                if (okHoldFired) { okHoldFired = false; return true }
                if (okPending) {
                    okPending = false
                    if (held >= 5000) toggleDiag()
                    else if (typed.isNotEmpty()) jumpToTyped() else showList()
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) lastKeyInfo = "Tecla ${KeyEvent.keyCodeToString(code)} ($code)"
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

    // ---------- Audio (idioma) ----------

    /** Pistas de audio del canal actual: (grupo, índice dentro del grupo). */
    private fun audioTracks(): List<Pair<Tracks.Group, Int>> {
        val p = player ?: return emptyList()
        return p.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
    }

    private fun audioName(g: Tracks.Group, i: Int, pos: Int): String {
        val lang = g.getTrackFormat(i).language
        val name = if (lang.isNullOrBlank() || lang == "und" || lang.length < 2) null
        else Locale(lang).getDisplayLanguage(Locale("es")).takeIf { it.isNotBlank() && it != lang }
        return (name?.replaceFirstChar { it.uppercase() }) ?: getString(R.string.audio_n, pos + 1)
    }

    private fun cycleAudio() {
        val p = player ?: return
        if (channels.isEmpty()) return
        val list = audioTracks()
        if (list.size < 2) {
            showMsg(getString(R.string.audio_unico)); return
        }
        val cur = list.indexOfFirst { (g, i) -> g.isTrackSelected(i) }.coerceAtLeast(0)
        val next = (cur + 1) % list.size
        selectAudio(list, next)
        prefs.edit().putInt("audio_" + (channels[current].id ?: channels[current].name), next).apply()
        audioAppliedFor = channels[current].id ?: channels[current].name
        showMsg(getString(R.string.audio_sel, audioName(list[next].first, list[next].second, next), next + 1, list.size))
    }

    private fun selectAudio(list: List<Pair<Tracks.Group, Int>>, idx: Int) {
        val p = player ?: return
        val (g, i) = list[idx]
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            .build()
    }

    /** Si el cliente eligió otro audio para este canal, se aplica al abrirlo. */
    private fun applySavedAudio() {
        if (channels.isEmpty()) return
        val key = channels[current].id ?: channels[current].name
        if (audioAppliedFor == key) return
        val list = audioTracks()
        if (list.isEmpty()) return
        audioAppliedFor = key
        val saved = prefs.getInt("audio_$key", -1)
        if (saved in list.indices) selectAudio(list, saved)
    }

    private fun showMsg(msg: String) {
        banner.text = msg
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 2500)
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
        // Al salir de la app (Inicio u otra app) se cierra por completo
        if (!isChangingConfigurations) finishAndRemoveTask()
    }

    // ---------- Panel de diagnóstico ----------

    private fun toggleDiag() = try { toggleDiagInner() } catch (e: Throwable) {
        lastKeyInfo = "error panel: ${e.javaClass.simpleName}: ${e.message}"
        android.widget.Toast.makeText(this, lastKeyInfo, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun toggleDiagInner() {
        val d = diag
        if (d != null && d.visibility == View.VISIBLE) {
            d.visibility = View.GONE
            ui.removeCallbacks(diagTick)
            return
        }
        if (d == null) {
            diag = TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xCC000000.toInt())
                textSize = 14f
                setPadding(24, 16, 24, 16)
                (findViewById<View>(android.R.id.content) as FrameLayout).addView(
                    this, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ).apply { setMargins(32, 32, 32, 32) }
                )
            }
        }
        droppedFrames = 0
        rebuffers = 0
        diag?.visibility = View.VISIBLE
        ui.removeCallbacks(diagTick)
        ui.post(diagTick)
    }

    private fun networkInfo(): String = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = if (android.os.Build.VERSION.SDK_INT >= 23) cm.getNetworkCapabilities(cm.activeNetwork) else null
        when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Cable"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                @Suppress("DEPRECATION")
                val wi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo
                val band = if (wi.frequency > 4900) "5 GHz" else "2.4 GHz"
                "WiFi $band, señal ${wi.rssi} dBm, ${wi.linkSpeed} Mbps"
            }
            else -> "?"
        }
    } catch (_: Exception) { "?" }

    private fun updateDiag() {
        val p = player ?: return
        val f = p.videoFormat
        val mem = Runtime.getRuntime()
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val buf = (p.totalBufferedDuration / 100) / 10.0
        diag?.text = buildString {
            appendLine("Fuente: " + if (usingMulticast) "Multicast" else "HTTP")
            appendLine("Red: " + networkInfo())
            appendLine("Video: " + (f?.let { "${it.width}x${it.height} ${it.sampleMimeType?.substringAfter('/')} ${it.bitrate.takeIf { b -> b > 0 }?.let { b -> "${b / 1000} kbps" } ?: ""}" } ?: "-"))
            appendLine("App: v${BuildConfig.VERSION_NAME} | Modo túnel: ${if (tunnelOn) "SÍ" else "no"} (Derecha cambia) | Última tecla: $lastKeyInfo")
            appendLine("Decodificador: $videoDecoder")
            appendLine("Cuadros del canal: " + (if (detectedFps > 0f) "%.2f fps".format(detectedFps) else "midiendo...") + " | Pantalla: " + displayHz())
            appendLine("Colchón: $buf s")
            appendLine("Pausas por carga: $rebuffers")
            appendLine("Cuadros perdidos: $droppedFrames")
            append("RAM libre TV: ${mi.availMem / 1048576} de ${mi.totalMem / 1048576} MB" + if (mi.lowMemory) " (BAJA)" else "")
        }
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
        if (isFinishing) {
            // Cierre total: al volver a entrar arranca de cero (con la animación)
            Thread {
                try { t?.down() } catch (_: Exception) {}
                android.os.Process.killProcess(android.os.Process.myPid())
            }.start()
        }
    }
}
