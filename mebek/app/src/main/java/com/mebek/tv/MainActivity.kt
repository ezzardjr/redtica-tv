package com.mebek.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** App de prueba Mebek TV: lista fija de canales (assets/canales.m3u), arriba/abajo cambia, OK abre la lista. */
@UnstableApi
class MainActivity : Activity() {
    data class Ch(val name: String, val url: String, val referer: String? = null, val ua: String? = null)

    private val ui = Handler(Looper.getMainLooper())
    private val channels = mutableListOf<Ch>()
    private var current = 0
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var banner: TextView
    private lateinit var info: TextView
    private lateinit var listPanel: LinearLayout
    private lateinit var list: RecyclerView
    private val prefs by lazy { getSharedPreferences("mebek", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player)
        banner = findViewById(R.id.banner)
        info = findViewById(R.id.info)
        listPanel = findViewById(R.id.listPanel)
        list = findViewById(R.id.list)
        loadChannels()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()
        buildPlayer()
        current = prefs.getInt("ultimo", 0).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        if (channels.isNotEmpty()) play(current)
    }

    private fun loadChannels() {
        val lines = assets.open("canales.m3u").bufferedReader().readLines()
        var name: String? = null; var ref: String? = null; var ua: String? = null
        for (l in lines) {
            val t = l.trim()
            when {
                t.startsWith("#EXTINF") -> { name = t.substringAfter(",").trim(); ref = null; ua = null }
                t.startsWith("#EXTVLCOPT:http-referrer=") -> ref = t.substringAfter("=")
                t.startsWith("#EXTVLCOPT:http-user-agent=") -> ua = t.substringAfter("=")
                t.isNotEmpty() && !t.startsWith("#") -> { channels.add(Ch(name ?: t, t, ref, ua)); name = null; ref = null; ua = null }
            }
        }
    }

    private lateinit var extractors: DefaultExtractorsFactory

    /** Fuente con los encabezados que pide cada canal (referer / navegador). */
    private fun sourceFor(ch: Ch) = DefaultMediaSourceFactory(
        DefaultHttpDataSource.Factory()
            .setUserAgent(ch.ua ?: "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            .setConnectTimeoutMs(8000).setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(true)
            .apply { ch.referer?.let { setDefaultRequestProperties(mapOf("Referer" to it, "Origin" to it.substringBefore("/", "").let { _ -> android.net.Uri.parse(it).scheme + "://" + android.net.Uri.parse(it).host })) } },
        extractors
    ).createMediaSource(MediaItem.fromUri(ch.url))

    private fun buildPlayer() {
        extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 40_000, 2_500, 5_000).build()
        player = ExoPlayer.Builder(this)
            .setLoadControl(load).build().also { p ->
                playerView.player = p
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        showBanner("${channels[current].name}\nSin señal: ${error.errorCodeName}", 6000)
                        ui.postDelayed({ play(current) }, 5000)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ui.postDelayed({ showInfo() }, 1500)
                    }
                })
            }
    }

    private fun play(i: Int) {
        if (channels.isEmpty()) return
        current = (i % channels.size + channels.size) % channels.size
        prefs.edit().putInt("ultimo", current).apply()
        ui.removeCallbacksAndMessages(null)
        val ch = channels[current]
        player?.apply {
            setMediaSource(sourceFor(ch))
            prepare(); playWhenReady = true
        }
        showBanner("${current + 1}  ${ch.name}", 4000)
        list.adapter?.notifyDataSetChanged()
    }

    private fun showBanner(text: String, ms: Long) {
        banner.text = text; banner.visibility = View.VISIBLE
        banner.removeCallbacks(hideBanner); banner.postDelayed(hideBanner, ms)
    }
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    /** Calidad real que está llegando: resolución, cuadros por segundo, tasa de datos. */
    private fun showInfo() {
        val f = player?.videoFormat ?: return
        val fps = if (f.frameRate > 0) "%.2f fps".format(f.frameRate) else "? fps"
        val br = if (f.bitrate > 0) " · ${f.bitrate / 1000} kbps" else ""
        info.text = "${channels[current].name}\n${f.width}x${f.height} · $fps · ${f.sampleMimeType?.substringAfter('/')}$br"
        info.visibility = View.VISIBLE
        info.removeCallbacks(hideInfo); info.postDelayed(hideInfo, 8000)
    }
    private val hideInfo = Runnable { info.visibility = View.GONE }

    private fun listVisible() = listPanel.visibility == View.VISIBLE
    private fun showList() {
        listPanel.visibility = View.VISIBLE
        list.scrollToPosition(current)
        list.post { list.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus() }
    }
    private fun hideList() { listPanel.visibility = View.GONE; playerView.requestFocus() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (listVisible()) {
            if (code == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) hideList(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { play(current + 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> { showInfo(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { showList(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        finish()   // al salir se cierra del todo
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        player?.release(); player = null
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val t: TextView) : RecyclerView.ViewHolder(t)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val t = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(28, 18, 28, 18); textSize = 18f; isFocusable = true; isFocusableInTouchMode = true
                setOnFocusChangeListener { v, has -> v.setBackgroundColor(if (has) 0xFF3E7D1E.toInt() else Color.TRANSPARENT) }
            }
            return VH(t)
        }
        override fun getItemCount() = channels.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            h.t.text = "${pos + 1}  ${channels[pos].name}"
            h.t.setTextColor(if (pos == current) 0xFF7CC242.toInt() else Color.WHITE)
            h.t.setOnClickListener { hideList(); play(pos) }
        }
    }
}
