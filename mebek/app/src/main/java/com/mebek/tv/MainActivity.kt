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
    data class Src(val url: String, val referer: String?, val ua: String?)
    data class Ch(val group: String, val name: String, val srcs: List<Src>)
    /** Fila de la lista: encabezado de grupo o canal. */
    private data class Row(val header: String?, val idx: Int)
    private val rows = mutableListOf<Row>()
    private var srcIdx = 0
    /** Estado por canal: -1 sin verificar, -2 caído, >=0 fuente que funciona. */
    private lateinit var status: IntArray
    private var lastDir = 1
    private var checked = 0
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(12)

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
        status = IntArray(channels.size) { -1 }
        loadCache()
        rebuildRows()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()
        buildPlayer()
        current = prefs.getInt("ultimo", 0).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        if (channels.isNotEmpty()) play(if (status[current] == -2) nextAlive(current, 1) else current)
        checkAll()
    }

    // ---------- Verificación de canales desde el propio TV ----------
    private fun cacheKey() = "estado_" + channels.size
    private fun loadCache() {
        val txt = prefs.getString(cacheKey(), null) ?: return
        val age = System.currentTimeMillis() - prefs.getLong(cacheKey() + "_t", 0)
        if (age > 12 * 3600_000L) return   // más de 12 h: se vuelve a verificar todo
        txt.split(",").forEachIndexed { i, v -> if (i < status.size) status[i] = v.toIntOrNull() ?: -1 }
    }
    private fun saveCache() {
        prefs.edit().putString(cacheKey(), status.joinToString(","))
            .putLong(cacheKey() + "_t", System.currentTimeMillis()).apply()
    }

    private fun rebuildRows() {
        rows.clear()
        var last = ""
        channels.forEachIndexed { i, c ->
            if (status[i] == -2) return@forEachIndexed
            if (c.group != last) { rows.add(Row(c.group, -1)); last = c.group }
            rows.add(Row(null, i))
        }
        list.adapter?.notifyDataSetChanged()
    }

    private fun alive() = status.count { it != -2 }
    private fun nextAlive(from: Int, dir: Int): Int {
        var i = from
        repeat(channels.size) {
            i = ((i + dir) % channels.size + channels.size) % channels.size
            if (status[i] != -2) return i
        }
        return from
    }

    /** Prueba si una fuente responde con una lista HLS o con video TS. */
    private fun probe(src: Src): Boolean = try {
        val c = java.net.URL(src.url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", src.ua ?: "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        src.referer?.let { c.setRequestProperty("Referer", it) }
        val ok = c.responseCode in 200..299 && c.inputStream.use { ins ->
            val b = ByteArray(2048); val n = ins.read(b)
            n > 0 && (b[0] == 0x47.toByte() || String(b, 0, n).contains("#EXTM3U") || String(b, 0, n).contains("#EXT-X"))
        }
        c.disconnect(); ok
    } catch (_: Throwable) { false }

    private fun checkAll() {
        checked = 0
        channels.forEachIndexed { i, ch ->
            pool.execute {
                var res = -2
                for ((k, src) in ch.srcs.withIndex()) if (probe(src)) { res = k; break }
                ui.post {
                    status[i] = res; checked++
                    if (checked % 25 == 0 || checked == channels.size) {
                        rebuildRows(); updateTitle()
                        if (checked == channels.size) {
                            saveCache()
                            showBanner("Canales funcionando: ${alive()} de ${channels.size}", 5000)
                        }
                    }
                }
            }
        }
    }

    private fun updateTitle() {
        findViewById<TextView>(R.id.listTitle)?.text =
            if (checked < channels.size) "Mebek TV · verificando ${checked}/${channels.size}" else "Mebek TV · ${alive()} canales"
    }

    private fun loadChannels() {
        val arr = org.json.JSONArray(assets.open("canales.json").bufferedReader().readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val sa = o.getJSONArray("s")
            val srcs = (0 until sa.length()).map { j -> sa.getJSONObject(j).let { Src(it.getString("u"), it.optString("ref").ifEmpty { null }, it.optString("ua").ifEmpty { null }) } }
            channels.add(Ch(o.getString("g"), o.getString("n"), srcs))
        }
    }

    private lateinit var extractors: DefaultExtractorsFactory

    /** Fuente con los encabezados que pide cada canal (referer / navegador). */
    private fun sourceFor(ch: Src) = DefaultMediaSourceFactory(
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
                        val ch = channels[current]
                        if (srcIdx + 1 < ch.srcs.size) { srcIdx++; startSource(); return }   // probar fuente alterna
                        // Caído: se esconde y se pasa al siguiente en la misma dirección
                        status[current] = -2; rebuildRows(); saveCache()
                        showBanner("${ch.name}: no disponible, pasando al siguiente…", 2500)
                        ui.postDelayed({ play(nextAlive(current, lastDir)) }, 1500)
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
        srcIdx = status[current].coerceAtLeast(0)
        startSource()
        showBanner("${current + 1}  ${ch.name}\n${ch.group}", 4000)
        list.adapter?.notifyDataSetChanged()
    }

    private fun startSource() {
        val ch = channels[current]
        player?.apply { setMediaSource(sourceFor(ch.srcs[srcIdx])); prepare(); playWhenReady = true }
    }

    /** Salta al primer canal del grupo siguiente/anterior. */
    private fun jumpGroup(dir: Int) {
        val groups = channels.map { it.group }.distinct()
        val gi = (groups.indexOf(channels[current].group) + dir + groups.size) % groups.size
        val first = channels.indexOfFirst { it.group == groups[gi] }
        play(if (status[first] == -2) nextAlive(first, 1) else first)
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
        val pos = rows.indexOfFirst { it.idx == current }
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 200)
        list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }
    private fun hideList() { listPanel.visibility = View.GONE; playerView.requestFocus() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (listVisible()) {
            if (code == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) hideList(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { lastDir = 1; play(nextAlive(current, 1)); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { lastDir = -1; play(nextAlive(current, -1)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { jumpGroup(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { jumpGroup(-1); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_BLUE -> { showInfo(); return true }
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
        pool.shutdownNow()
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
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = rows[pos]
            if (r.header != null) {
                h.t.text = r.header.uppercase(); h.t.setTextColor(0xFF7CC242.toInt()); h.t.textSize = 15f
                h.t.isFocusable = false; h.t.setOnClickListener(null); h.t.setBackgroundColor(0xFF1C2228.toInt())
            } else {
                h.t.text = "${r.idx + 1}  ${channels[r.idx].name}"; h.t.textSize = 18f
                h.t.setTextColor(if (r.idx == current) 0xFF7CC242.toInt() else Color.WHITE)
                h.t.isFocusable = true; h.t.setBackgroundColor(Color.TRANSPARENT)
                h.t.setOnClickListener { hideList(); play(r.idx) }
            }
        }
    }
}
