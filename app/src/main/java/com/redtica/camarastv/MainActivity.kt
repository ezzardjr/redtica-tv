package com.redtica.camarastv

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.sqrt

class MainActivity : Activity() {
    private lateinit var libVlc: LibVLC
    private lateinit var root: FrameLayout
    private lateinit var grid: LinearLayout
    private lateinit var fullLayout: VLCVideoLayout
    private lateinit var fullLabel: TextView
    private lateinit var infoBar: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val releaser = Executors.newSingleThreadExecutor()

    private class Tile(
        val frame: FrameLayout, val video: VLCVideoLayout,
        val label: TextView, val status: TextView, val rec: TextView
    ) {
        var camIndex = -1
        var player: MediaPlayer? = null
    }

    private val tiles = ArrayList<Tile>()
    private var page = 0
    private var fullIndex = -1
    private var fullPlayer: MediaPlayer? = null
    private var infoUntil = 0L
    private val stateListener: () -> Unit = {
        refreshOverlays()
        if (AppState.maintenanceRequest) { AppState.maintenanceRequest = false; Kiosk.maintenance(this) }
        if (AppState.removeOwnerRequest) { AppState.removeOwnerRequest = false; Kiosk.removeOwner(this) }
    }
    private val configListener: () -> Unit = {
        if (started) { closeFull(restartGrid = false); stopAll(); buildGrid(); startPage() }
    }
    private var started = false

    private val rotate = object : Runnable {
        override fun run() {
            val sec = AppState.config.rotateSeconds
            if (sec > 0 && fullIndex < 0 && pageCount() > 1) changePage(+1)
            ui.postDelayed(this, (if (sec > 0) sec else 10) * 1000L)
        }
    }
    private val tick = object : Runnable {
        override fun run() { refreshOverlays(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        libVlc = LibVLC(this, arrayListOf(
            "--rtsp-tcp", "--no-audio", "--network-caching=400",
            "--drop-late-frames", "--skip-frames"
        ))

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        fullLayout = VLCVideoLayout(this).apply { visibility = View.GONE }
        root.addView(fullLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        fullLabel = overlayText(20f).apply { visibility = View.GONE }
        root.addView(fullLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(24, 24, 0, 0) })

        infoBar = overlayText(16f).apply { setPadding(24, 14, 24, 14); setBackgroundColor(0xCC0B1E33.toInt()) }
        root.addView(infoBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        if (savedInstanceState == null) Splash.show(this, root)

        CameraService.start(this)
        infoUntil = System.currentTimeMillis() + 20_000
        buildGrid()
    }

    override fun onStart() {
        super.onStart()
        started = true
        AppState.addListener(stateListener)
        AppState.addConfigListener(configListener)
        startPage()
        ui.post(tick)
        ui.postDelayed(rotate, 10_000)
    }

    override fun onResume() {
        super.onResume()
        Kiosk.enter(this)
    }

    override fun onStop() {
        started = false
        AppState.removeListener(stateListener)
        AppState.removeConfigListener(configListener)
        ui.removeCallbacks(tick); ui.removeCallbacks(rotate)
        closeFull(restartGrid = false)
        stopAll()
        super.onStop()
    }

    override fun onDestroy() {
        releaser.execute { try { libVlc.release() } catch (_: Exception) { } }
        releaser.shutdown()
        super.onDestroy()
    }

    // ---------------- Mosaico ----------------
    private fun activeCams(): List<Int> {
        val c = AppState.config
        return (0 until MAX_CAMS).filter { c.cameras[it].isConfigured }
    }

    private fun pageCount(): Int {
        val n = activeCams().size
        val size = AppState.config.gridSize
        return if (n == 0) 1 else ceil(n / size.toDouble()).toInt()
    }

    private fun buildGrid() {
        grid.removeAllViews(); tiles.clear()
        val size = AppState.config.gridSize
        val side = ceil(sqrt(size.toDouble())).toInt()
        for (r in 0 until side) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            for (col in 0 until side) {
                val t = makeTile()
                row.addView(t.frame, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                tiles.add(t)
            }
        }
        if (page >= pageCount()) page = 0
    }

    private fun makeTile(): Tile {
        val frame = FrameLayout(this).apply {
            isFocusable = true; isFocusableInTouchMode = false
            setPadding(3, 3, 3, 3)
            background = border(false)
        }
        val video = VLCVideoLayout(this)
        frame.addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val status = overlayText(14f).apply { gravity = Gravity.CENTER; setTextColor(0xFFB0C4DE.toInt()) }
        frame.addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val label = overlayText(13f).apply { setPadding(10, 4, 10, 4); setBackgroundColor(0x99000000.toInt()) }
        frame.addView(label, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        val rec = overlayText(13f).apply {
            text = "● REC"; setTextColor(Color.RED); setPadding(10, 4, 10, 4); visibility = View.GONE
            setBackgroundColor(0x99000000.toInt())
        }
        frame.addView(rec, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        val tile = Tile(frame, video, label, status, rec)
        frame.setOnFocusChangeListener { v, has -> v.background = border(has) }
        frame.setOnClickListener { if (tile.camIndex >= 0) openFull(tile.camIndex) }
        return tile
    }

    private fun startPage() {
        val cams = activeCams()
        val size = tiles.size
        val cfg = AppState.config
        tiles.forEachIndexed { slot, t ->
            val idx = cams.getOrNull(page * size + slot) ?: -1
            t.camIndex = idx
            if (idx < 0) {
                t.label.text = ""; t.status.text = if (cams.isEmpty() && slot == 0) "Sin cámaras configuradas" else ""
                return@forEachIndexed
            }
            t.label.text = "${idx + 1} · ${cfg.cameras[idx].label(idx)}"
            t.status.text = "Conectando…"
            t.player = play(t.video, idx, main = size == 1) { ok -> t.status.text = if (ok) "" else "Sin señal · reintentando" }
        }
        if (tiles.none { it.frame.hasFocus() }) tiles.firstOrNull()?.frame?.requestFocus()
        refreshOverlays()
    }

    private fun stopAll() {
        tiles.forEach { t -> t.player?.let { release(it) }; t.player = null }
    }

    private fun play(layout: VLCVideoLayout, idx: Int, main: Boolean, onState: (Boolean) -> Unit): MediaPlayer {
        val cfg = AppState.config
        val cam = cfg.cameras[idx]
        val mp = MediaPlayer(libVlc)
        mp.attachViews(layout, null, false, false)
        val m = Media(libVlc, Uri.parse(cam.rtspUrl(main)))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":rtsp-tcp")
        m.addOption(":rtsp-user=${cam.effUser(cfg)}")
        m.addOption(":rtsp-pwd=${cam.effPass(cfg)}")
        m.addOption(":network-caching=400")
        mp.media = m
        m.release()
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Vout -> if (ev.voutCount > 0) onState(true)
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> {
                    onState(false)
                    ui.postDelayed({ if (!mp.isReleased && started) { mp.stop(); mp.play() } }, 5000)
                }
            }
        }
        mp.play()
        return mp
    }

    private fun release(mp: MediaPlayer) {
        try { mp.setEventListener(null); mp.detachViews() } catch (_: Exception) { }
        releaser.execute { try { mp.stop(); mp.release() } catch (_: Exception) { } }
    }

    private fun changePage(delta: Int) {
        val n = pageCount()
        if (n <= 1) return
        stopAll()
        page = (page + delta + n) % n
        startPage()
    }

    // ---------------- Pantalla completa ----------------
    private fun openFull(idx: Int) {
        stopAll()
        grid.visibility = View.GONE
        fullIndex = idx
        fullLayout.visibility = View.VISIBLE
        fullLabel.visibility = View.VISIBLE
        fullLabel.text = "${idx + 1} · ${AppState.config.cameras[idx].label(idx)}  (Atrás = mosaico · Mantener OK = menú)"
        fullPlayer = play(fullLayout, idx, main = true) { ok ->
            fullLabel.text = "${idx + 1} · ${AppState.config.cameras[idx].label(idx)}" + if (ok) "" else "  · sin señal"
        }
    }

    private fun closeFull(restartGrid: Boolean = true) {
        if (fullIndex < 0) return
        fullPlayer?.let { release(it) }; fullPlayer = null
        val last = fullIndex
        fullIndex = -1
        fullLayout.visibility = View.GONE
        fullLabel.visibility = View.GONE
        grid.visibility = View.VISIBLE
        if (restartGrid) {
            startPage()
            tiles.firstOrNull { it.camIndex == last }?.frame?.requestFocus()
        }
    }

    private fun stepFull(delta: Int) {
        val cams = activeCams()
        if (cams.isEmpty()) return
        val pos = cams.indexOf(fullIndex).coerceAtLeast(0)
        val next = cams[(pos + delta + cams.size) % cams.size]
        fullPlayer?.let { release(it) }; fullPlayer = null
        fullIndex = -1
        openFull(next)
    }

    // ---------------- Menú en pantalla ----------------
    // Los controles tipo Xiaomi Mi TV Stick no tienen tecla MENU, CH+/CH- ni números,
    // y el botón de "cuadros" lo toma el sistema. Por eso el menú se abre también
    // manteniendo presionado OK (centro del aro).
    private var menuBox: LinearLayout? = null
    private var centerDownAt = 0L
    private var centerLong = false

    private fun menuOpen() = menuBox != null

    private fun openMenu() {
        if (menuOpen()) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = GradientDrawable().apply { setColor(0xEE0B1E33.toInt()); cornerRadius = 24f }
        }
        val title = overlayText(20f).apply { text = "Menú · ${AppState.config.siteName}"; setPadding(8, 0, 8, 18) }
        box.addView(title)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (fullIndex >= 0) items.add("Volver al mosaico" to { closeFull() })
        val cur = AppState.config.gridSize
        listOf(1, 4, 9, 16).forEach { n ->
            items.add(("Mosaico de $n ${if (n == 1) "cuadro" else "cuadros"}" + if (n == cur) "  ✓" else "") to {
                if (n != AppState.config.gridSize) AppState.update { it.gridSize = n }
            })
        }
        if (pageCount() > 1) {
            items.add("Página siguiente" to { if (fullIndex >= 0) closeFull(); changePage(+1) })
            items.add("Página anterior" to { if (fullIndex >= 0) closeFull(); changePage(-1) })
        }
        items.add("Mostrar información (IP, Drive, PIN)" to {
            infoUntil = System.currentTimeMillis() + 60_000; refreshOverlays()
        })
        items.add("Cerrar menú" to { })
        var first: View? = null
        items.forEach { (label, action) ->
            val tv = overlayText(18f).apply {
                text = label; setPadding(24, 14, 24, 14)
                isFocusable = true; isFocusableInTouchMode = false
                background = menuItemBg(false)
                setOnFocusChangeListener { v, has -> v.background = menuItemBg(has) }
                setOnClickListener { closeMenu(); action() }
            }
            box.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, 4, 0, 4) })
            if (first == null) first = tv
        }
        root.addView(box, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        menuBox = box
        first?.requestFocus()
    }

    private fun closeMenu() {
        val box = menuBox ?: return
        root.removeView(box)
        menuBox = null
        if (fullIndex < 0) (tiles.firstOrNull { it.frame.hasFocus() } ?: tiles.firstOrNull())?.frame?.requestFocus()
    }

    private fun menuItemBg(focused: Boolean) = GradientDrawable().apply {
        setColor(if (focused) 0xFF3FA9FF.toInt() else 0x00000000)
        cornerRadius = 12f
    }

    private fun isCenter(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (isCenter(k) && !menuOpen()) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) { centerDownAt = System.currentTimeMillis(); centerLong = false }
                    else if (!centerLong && System.currentTimeMillis() - centerDownAt >= 600) { centerLong = true; openMenu() }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (Splash.skipIfShowing()) return true
                    if (!centerLong) currentFocus?.performClick()
                    centerLong = false
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- Control remoto ----------------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (Splash.skipIfShowing()) return true
        if (menuOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KEYCODE_ALL_APPS) { closeMenu(); return true }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (fullIndex >= 0) { closeFull(); return true }
                if (AppState.config.kiosk) return true // en kiosco no se sale de la app
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_GUIDE, KEYCODE_ALL_APPS,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> { openMenu(); return true }
            KeyEvent.KEYCODE_INFO -> {
                infoUntil = if (System.currentTimeMillis() < infoUntil) 0 else System.currentTimeMillis() + 60_000
                refreshOverlays(); return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (fullIndex >= 0) stepFull(+1) else changePage(+1); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (fullIndex >= 0) stepFull(-1) else changePage(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (fullIndex >= 0) { stepFull(+1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (fullIndex >= 0) { stepFull(-1); return true }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val idx = keyCode - KeyEvent.KEYCODE_1
                if (AppState.config.cameras[idx].isConfigured) {
                    if (fullIndex >= 0) { fullPlayer?.let { release(it) }; fullPlayer = null; fullIndex = -1 }
                    openFull(idx)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Overlays ----------------
    private fun refreshOverlays() {
        tiles.forEach { t ->
            t.rec.visibility = if (t.camIndex >= 0 && t.camIndex in AppState.recording) View.VISIBLE else View.GONE
            if (t.camIndex >= 0) {
                val st = AppState.camStatus[t.camIndex]
                t.label.text = "${t.camIndex + 1} · ${AppState.config.cameras[t.camIndex].label(t.camIndex)}" +
                    (if (st != null) " · $st" else "")
            }
        }
        val cfg = AppState.config
        val show = System.currentTimeMillis() < infoUntil || activeCams().isEmpty()
        if (show) {
            infoBar.visibility = View.VISIBLE
            val pages = pageCount()
            infoBar.text = buildString {
                append("${cfg.siteName}   ·   Configurar desde el celular: http://${AppState.localIp()}:8080   ·   PIN ${cfg.pin}\n")
                append(AppState.driveStatus)
                if (AppState.driveUserCode.isNotEmpty()) append("   ·   Código: ${AppState.driveUserCode} en ${AppState.driveVerifyUrl}")
                append("   ·   Pendientes: ${AppState.pendingFiles}")
                if (pages > 1) append("   ·   Página ${page + 1}/$pages")
                append("   ·   Mantener OK = menú")
                if (AppState.lastEvent.isNotEmpty()) append("\nÚltimo evento: ${AppState.lastEvent}")
            }
        } else infoBar.visibility = View.GONE
    }

    companion object {
        // KeyEvent.KEYCODE_ALL_APPS (API 28); el botón de "cuadros" en algunos controles
        private const val KEYCODE_ALL_APPS = 284
    }

    private fun overlayText(size: Float) = TextView(this).apply {
        textSize = size; setTextColor(Color.WHITE); setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private fun border(focused: Boolean) = GradientDrawable().apply {
        setColor(Color.BLACK)
        setStroke(if (focused) 6 else 1, if (focused) 0xFF3FA9FF.toInt() else 0xFF202830.toInt())
    }
}
