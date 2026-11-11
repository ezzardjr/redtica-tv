package com.redtica.tv

import android.content.Context
import android.graphics.Color
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Animación de inicio: video de RedTica (con audio) a pantalla completa.
 * Al terminar se difumina y deja ver el canal que ya cargó por detrás.
 * Cualquier tecla la salta (con el mismo difuminado).
 */
@UnstableApi
class IntroView(context: Context, private val onDone: () -> Unit) : FrameLayout(context) {

    private var finished = false
    private var fading = false
    private val player = ExoPlayer.Builder(context).build()
    private val view = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        setShutterBackgroundColor(Color.BLACK)
        this.player = this@IntroView.player
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { skip() }
    }

    fun start() {
        player.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(pickVideo())))
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) fadeOut(1200)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { fadeOut(300) }
        })
        player.prepare()
        player.playWhenReady = true
        // Empieza a difuminar 1 s antes del final, mientras termina el audio
        postDelayed({ fadeOut(1200) }, 9_000)
    }

    /** 4K si la pantalla es 4K y el decodificador lo soporta; si no, 1080p. */
    private fun pickVideo(): Int = if (screenIs4k() && decoderSupports4k()) R.raw.intro_4k else R.raw.intro_1080

    private fun screenIs4k(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION")
            val d = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            val best = (d.supportedModes + d.mode).maxOf { maxOf(it.physicalWidth, it.physicalHeight) }
            best >= 3800
        } else false
    } catch (e: Exception) { false }

    private fun decoderSupports4k(): Boolean = try {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 3840, 2160)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 24)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null
    } catch (e: Exception) { false }

    /** Tecla del control: salta la animación. */
    fun skip() = fadeOut(500)

    private fun fadeOut(ms: Long) {
        if (fading || finished) return
        fading = true
        animate().alpha(0f).setDuration(ms)
            .setUpdateListener { player.volume = alpha }
            .withEndAction { finish() }.start()
    }

    fun finish() {
        if (finished) return
        finished = true
        removeCallbacks(null)
        player.release()
        (parent as? android.view.ViewGroup)?.removeView(this)
        onDone()
    }
}
