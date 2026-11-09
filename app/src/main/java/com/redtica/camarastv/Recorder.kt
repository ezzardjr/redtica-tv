package com.redtica.camarastv

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Graba clips de evento (MP4, sin re-codificar) usando la salida de stream de VLC. */
class Recorder(private val ctx: Context, private val onClipReady: () -> Unit) {
    private val libVlc = LibVLC(ctx, arrayListOf("--rtsp-tcp", "--no-audio"))
    private val exec = Executors.newCachedThreadPool()
    private val lastStart = HashMap<Int, Long>()

    companion object {
        fun pendingDir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "pendientes").apply { mkdirs() }

        fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
    }

    fun record(index: Int, reason: String) {
        val cfg = AppState.config
        val cam = cfg.cameras[index]
        if (!cfg.recordEnabled || !cam.isConfigured) return
        synchronized(lastStart) {
            val now = System.currentTimeMillis()
            if (now - (lastStart[index] ?: 0) < 5_000) return
            if (!AppState.recording.add(index)) return
            lastStart[index] = now
        }
        AppState.lastEvent = "${cam.label(index)}: $reason"
        AppState.changed()

        exec.execute {
            var mp: MediaPlayer? = null
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val base = "${ts}_${safe(cam.label(index))}_${safe(reason)}"
                val dir = pendingDir(ctx)

                // 1) Foto instantánea del evento (rápida, sirve de vista previa en Drive)
                try {
                    if (cam.ip.isNotBlank()) Hik.snapshot(cam, cfg)?.let { File(dir, "$base.jpg").writeBytes(it) }
                } catch (_: Exception) { }

                // 2) Clip de video
                val tmp = File(dir, "$base.mp4.part")
                val media = Media(libVlc, Uri.parse(cam.rtspUrl(cfg.recordMain)))
                media.addOption(":rtsp-tcp")
                media.addOption(":rtsp-user=${cam.effUser(cfg)}")
                media.addOption(":rtsp-pwd=${cam.effPass(cfg)}")
                media.addOption(":network-caching=1000")
                media.addOption(":no-sout-audio")
                media.addOption(":sout=#std{access=file,mux=mp4,dst=\"${tmp.absolutePath}\"}")
                val player = MediaPlayer(libVlc)
                mp = player
                player.media = media
                media.release()
                player.play()
                Thread.sleep(cfg.clipSeconds.coerceIn(5, 300) * 1000L)
                player.stop()
                if (tmp.length() > 20_000) tmp.renameTo(File(dir, "$base.mp4")) else tmp.delete()
            } catch (_: Exception) {
            } finally {
                try { mp?.release() } catch (_: Exception) { }
                AppState.recording.remove(index)
                AppState.changed()
                onClipReady()
            }
        }
    }

    fun release() {
        exec.shutdownNow()
        try { libVlc.release() } catch (_: Exception) { }
    }
}
