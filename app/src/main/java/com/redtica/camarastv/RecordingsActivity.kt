package com.redtica.camarastv

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.concurrent.Executors

/**
 * Grabaciones: los oficiales ven en el TV los clips que las cámaras han subido a Google Drive
 * (por día) y los que aún están en el TV pendientes de subir.
 * Control: OK = abrir / pausar · Izq./Der. = retroceder / adelantar 10 s · Atrás = volver.
 */
class RecordingsActivity : Activity() {
    private class Item(val label: String, val id: String = "", val local: File? = null,
                       val folder: Boolean = false, val name: String = "", val size: Long = 0)

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var list: ListView
    private lateinit var video: VLCVideoLayout
    private lateinit var image: ImageView
    private lateinit var playLabel: TextView
    private lateinit var libVlc: LibVLC
    private var player: MediaPlayer? = null
    private val io = Executors.newSingleThreadExecutor()

    private var items: List<Item> = emptyList()
    private var inDay = false
    private var dayPos = 0
    private var viewing = false
    @Volatile private var alive = true
    @Volatile private var loadSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        libVlc = LibVLC(this, arrayListOf("--no-audio"))

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF10151C.toInt()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 24) }
        titleView = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE) }
        statusView = TextView(this).apply { textSize = 16f; setTextColor(0xFFC9A248.toInt()); setPadding(0, 8, 0, 16) }
        list = ListView(this).apply {
            isFocusable = true
            setSelector(android.R.drawable.list_selector_background)
            setOnItemClickListener { _, _, pos, _ -> open(pos) }
        }
        col.addView(titleView); col.addView(statusView)
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(TextView(this).apply {
            textSize = 14f; setTextColor(0xFF8FA3B8.toInt()); setPadding(0, 12, 0, 0)
            text = "OK = abrir   ·   Atrás = volver   ·   Durante el video: OK = pausa, Izq./Der. = 10 s"
        })
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        video = VLCVideoLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        root.addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        image = ImageView(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        root.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        playLabel = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); setShadowLayer(3f, 1f, 1f, Color.BLACK)
            setPadding(16, 8, 16, 8); setBackgroundColor(0x99000000.toInt()); visibility = View.GONE
        }
        root.addView(playLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(24, 24, 0, 0) })
        setContentView(root)
        loadDays()
    }

    // ---------- Listas ----------
    private fun show(title: String, newItems: List<Item>, status: String, select: Int = 0) {
        items = newItems
        titleView.text = title
        statusView.text = status
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, newItems.map { it.label }) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    textSize = 20f; setTextColor(Color.WHITE); setPadding(24, 18, 24, 18)
                }
        }
        if (newItems.isNotEmpty()) list.setSelection(select.coerceIn(0, newItems.size - 1))
        list.requestFocus()
    }

    private fun loadDays() {
        inDay = false
        val seq = ++loadSeq
        val local = localClips()
        val base = ArrayList<Item>()
        if (local.isNotEmpty()) base.add(Item("En este TV, pendientes de subir (${local.size})", folder = true, id = "local"))
        show("Grabaciones · ${AppState.config.siteName}", base,
            if (AppState.config.driveLinked) "Cargando días…" else "El respaldo no está vinculado: solo se muestran los clips de este TV.")
        if (!AppState.config.driveLinked) return
        io.execute {
            try {
                val days = Drive.listDays().map { Item(prettyDay(it.second), id = it.first, folder = true, name = it.second) }
                ui(seq) { show("Grabaciones · ${AppState.config.siteName}", base + days,
                    if (days.isEmpty()) "Todavía no hay grabaciones guardadas." else "${days.size} día(s) con grabaciones", dayPos) }
            } catch (e: Exception) {
                ui(seq) { statusView.text = "No se pudieron cargar las grabaciones. Revise la conexión a Internet." }
            }
        }
    }

    private fun loadDay(day: Item) {
        inDay = true
        val seq = ++loadSeq
        if (day.id == "local") {
            val clips = localClips().map { Item(prettyClip(it.name), local = it, name = it.name, size = it.length()) }
            show(day.label, clips, "${clips.size} archivo(s)")
            return
        }
        show(day.label, emptyList(), "Cargando…")
        io.execute {
            try {
                val clips = Drive.listClips(day.id).map { Item(prettyClip(it.name), id = it.id, name = it.name, size = it.size) }
                ui(seq) { show(day.label, clips, "${clips.count { it.name.endsWith(".mp4") }} video(s) y ${clips.count { it.name.endsWith(".jpg") }} foto(s)") }
            } catch (e: Exception) {
                ui(seq) { statusView.text = "No se pudieron cargar las grabaciones. Revise la conexión a Internet." }
            }
        }
    }

    private fun open(pos: Int) {
        val it = items.getOrNull(pos) ?: return
        if (it.folder) { dayPos = pos; loadDay(it); return }
        if (it.local != null) { view(it.local, it.label); return }
        val seq = ++loadSeq
        statusView.text = "Descargando ${it.label}…"
        io.execute {
            try {
                val dir = File(cacheDir, "clips").apply { mkdirs() }
                val f = File(dir, it.name)
                if (!f.exists() || f.length() != it.size) Drive.download(it.id, f)
                // Mantiene el caché pequeño: conserva los 15 más recientes
                dir.listFiles()?.sortedByDescending { c -> c.lastModified() }?.drop(15)?.forEach { c -> c.delete() }
                ui(seq) { statusView.text = ""; view(f, it.label) }
            } catch (e: Exception) {
                ui(seq) { statusView.text = "No se pudo abrir el video. Revise la conexión a Internet." }
            }
        }
    }

    // ---------- Reproducción ----------
    private fun view(f: File, label: String) {
        viewing = true
        playLabel.text = label; playLabel.visibility = View.VISIBLE
        if (f.name.endsWith(".jpg")) {
            image.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
            image.visibility = View.VISIBLE
            return
        }
        video.visibility = View.VISIBLE
        val mp = MediaPlayer(libVlc)
        player = mp
        mp.attachViews(video, null, false, false)
        val m = Media(libVlc, Uri.fromFile(f))
        m.setHWDecoderEnabled(true, false)
        mp.media = m
        m.release()
        mp.setEventListener { ev ->
            if (ev.type == MediaPlayer.Event.EndReached || ev.type == MediaPlayer.Event.EncounteredError) {
                runOnUiThread { if (player === mp) closeView() }
            }
        }
        mp.play()
    }

    private fun closeView() {
        viewing = false
        player?.let { mp ->
            player = null
            try { mp.setEventListener(null); mp.detachViews() } catch (_: Exception) { }
            io.execute { try { mp.stop(); mp.release() } catch (_: Exception) { } }
        }
        video.visibility = View.GONE
        image.visibility = View.GONE; image.setImageDrawable(null)
        playLabel.visibility = View.GONE
        list.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (viewing) {
            val mp = player
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> closeView()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                    if (mp != null) { if (mp.isPlaying) mp.pause() else mp.play() } else closeView()
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> mp?.let { it.time = it.time + 10_000 }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> mp?.let { it.time = maxOf(0, it.time - 10_000) }
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (inDay) loadDays() else finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        if (viewing) closeView()
        super.onStop()
    }

    override fun onDestroy() {
        alive = false
        io.execute { try { libVlc.release() } catch (_: Exception) { } }
        io.shutdown()
        super.onDestroy()
    }

    // ---------- Utilidades ----------
    private fun ui(seq: Int, block: () -> Unit) = runOnUiThread { if (alive && seq == loadSeq) block() }

    private fun localClips(): List<File> =
        (Recorder.pendingDir(this).listFiles() ?: emptyArray())
            .filter { it.isFile && (it.name.endsWith(".mp4") || it.name.endsWith(".jpg")) }
            .sortedByDescending { it.name }

    /** 2026-11-09 → "Lunes 9 de noviembre de 2026" */
    private fun prettyDay(name: String): String = try {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(name)!!
        java.text.SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", java.util.Locale("es", "CR")).format(d)
            .replaceFirstChar { it.uppercase() }
    } catch (e: Exception) { name }

    /** 2026-11-09_13-25-41_Entrada_VMD.mp4 → "13:25:41 · Entrada · Movimiento · video" */
    private fun prettyClip(name: String): String {
        val kind = if (name.endsWith(".jpg")) "foto" else "video"
        val p = name.substringBeforeLast('.').split("_")
        if (p.size < 3) return name
        val time = p[1].replace('-', ':')
        val ev = when (p.last().lowercase()) {
            "vmd" -> "Movimiento"; "fielddetection" -> "Intrusión"
            "linedetection" -> "Cruce de línea"; "regionentrance" -> "Entrada a zona"
            else -> p.last()
        }
        val cam = if (p.size > 3) p.subList(2, p.size - 1).joinToString(" ") else p[2]
        return "$time  ·  $cam  ·  $ev  ·  $kind"
    }
}
