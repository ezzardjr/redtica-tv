package com.redtica.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animación de inicio de RedTica TV.
 * Larga (10 s) la primera vez del día; corta (3 s) las demás. Cualquier tecla la salta.
 */
class IntroView(context: Context, private val longVersion: Boolean, private val onDone: () -> Unit) :
    FrameLayout(context) {

    private val totalMs = if (longVersion) 10_000L else 3_000L
    private var finished = false

    /** Hilos de luz tipo fibra óptica que cruzan la pantalla. */
    private inner class FiberView(ctx: Context) : View(ctx) {
        private val rnd = Random(7)
        private val fibers = List(18) {
            floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.8f + 0.4f, rnd.nextFloat(), if (rnd.nextBoolean()) 1f else 0f)
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
        var t = 0f
            set(v) { field = v; invalidate() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            for ((i, f) in fibers.withIndex()) {
                val y0 = f[0] * h
                val speed = f[1]
                val phase = f[2]
                val green = f[3] == 1f
                val head = ((t * speed + phase) % 1.4f) * w * 1.2f - w * 0.1f
                val len = w * 0.35f
                val wave = sin((t * 2 + i) * 1.3f) * h * 0.03f
                val c1 = if (green) Color.argb(0, 120, 220, 60) else Color.argb(0, 40, 170, 255)
                val c2 = if (green) Color.argb(230, 150, 255, 80) else Color.argb(230, 90, 200, 255)
                paint.shader = LinearGradient(head - len, y0, head, y0 + wave, c1, c2, Shader.TileMode.CLAMP)
                c.drawLine(head - len, y0 - wave, head, y0 + wave, paint)
            }
        }
    }

    private val fiber = FiberView(context)
    private val logo = ImageView(context).apply {
        setImageResource(R.drawable.logo_intro)
        alpha = 0f; scaleX = 0.4f; scaleY = 0.4f
    }
    private val tagline = TextView(context).apply {
        text = "Rapidez Natural, Conexión Infinita"
        setTextColor(Color.WHITE)
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        alpha = 0f
        setShadowLayer(12f, 0f, 0f, Color.rgb(46, 196, 241))
    }
    private val animators = mutableListOf<ValueAnimator>()

    init {
        setBackgroundColor(Color.rgb(6, 20, 48))
        isClickable = true
        addView(fiber, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val size = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        column.addView(logo, LinearLayout.LayoutParams(size, size))
        column.addView(tagline, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = size / 12 })
        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setOnClickListener { finish() }
    }

    fun start() {
        val k = totalMs / 10_000f  // escala de tiempos para la versión corta
        // Fibra todo el tiempo
        animators += ValueAnimator.ofFloat(0f, totalMs / 1000f).apply {
            duration = totalMs; addUpdateListener { fiber.t = it.animatedValue as Float }; start()
        }
        // Logo aparece y crece (2 s → 5 s)
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay((2000 * k).toLong()).setDuration((1500 * k).toLong())
            .setInterpolator(OvershootInterpolator(1.2f)).start()
        // Brillo: pulso suave del logo
        if (longVersion) {
            animators += ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 3600; duration = 1600; repeatCount = 1; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { val v = it.animatedValue as Float; logo.scaleX = 1f + v * 0.05f; logo.scaleY = 1f + v * 0.05f }
                start()
            }
            // "Movimiento" de la ranita: leve vaivén
            animators += ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 5000; duration = 3000
                addUpdateListener { val v = it.animatedValue as Float; logo.rotation = sin(v * 12.56f) * 3f }
                start()
            }
        }
        // Lema (5 s → 8 s)
        tagline.animate().alpha(1f).setStartDelay((5000 * k).toLong()).setDuration((800 * k).toLong())
            .setInterpolator(DecelerateInterpolator()).start()
        // Salida (8 s → 10 s)
        postDelayed({ fadeOut() }, (8000 * k).toLong())
    }

    private fun fadeOut() {
        if (finished) return
        animate().alpha(0f).setDuration(if (longVersion) 1500 else 500).withEndAction { finish() }.start()
    }

    fun finish() {
        if (finished) return
        finished = true
        animators.forEach { it.cancel() }
        removeCallbacks(null)
        (parent as? android.view.ViewGroup)?.removeView(this)
        onDone()
    }
}
