package com.redtica.camarastv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animación de arranque Mebek (≈7.5 s). Cualquier tecla del control la salta.
 *
 *  0.0 s  Fondo marfil con destellos dorados que suben suavemente.
 *  0.3 s  La "M" se dibuja trazo a trazo: pierna izquierda (sube), la V (baja), pierna derecha (sube),
 *         con una chispa dorada en la punta de cada trazo.
 *  2.3 s  Pulso de la M y un brillo que la atraviesa en diagonal.
 *  3.0 s  La línea dorada se abre desde el centro con dos chispas que viajan a los extremos.
 *  3.5 s  M-E-B-E-K aparecen letra por letra.
 *  4.7 s  "EXCELENCIA SIN LÍMITES" entra cerrando su espaciado.
 *  5.9 s  Segundo brillo sobre la M.
 *  6.9 s  Se desvanece hacia las cámaras.
 */
object Splash {
    const val TOTAL = 6900L
    private var actual: LogoAnimView? = null

    /** Si la animación está en pantalla, la salta y devuelve true. */
    fun skipIfShowing(): Boolean {
        val v = actual ?: return false
        v.skip(); return true
    }

    fun show(act: Activity, parent: FrameLayout) {
        var view: LogoAnimView? = null
        val v = LogoAnimView(act) {
            view?.let { lv ->
                lv.animate().alpha(0f).setDuration(700).withEndAction {
                    lv.stop(); parent.removeView(lv); actual = null
                }.start()
            }
        }
        view = v
        actual = v
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.setOnKeyListener { _, _, _ -> v.skip(); true }
        v.setOnClickListener { v.skip() }
        parent.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        v.requestFocus()
    }
}

class LogoAnimView(ctx: Context, private val onFinish: () -> Unit) : View(ctx) {
    private val bmM: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.mebek_m)
    private val bmTexto: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.mebek_texto)
    private val bmLema: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.mebek_lema)
    private val bmLinea: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.mebek_linea)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val bg = Paint()
    private val gold = Color.rgb(201, 162, 72)

    private var start = 0L
    private var done = false
    private var stopped = false

    // Geometría
    private val rM = RectF(); private val rLinea = RectF(); private val rTexto = RectF(); private val rLema = RectF()
    private val src = Rect(); private val dst = RectF()

    // Fronteras de cada letra de MEBEK dentro de la imagen (fracción del ancho)
    private val letras = floatArrayOf(0f, 0.20f, 0.41f, 0.62f, 0.83f, 1f)

    private class Particula(var x: Float, var y: Float, val r: Float, val vel: Float, val fase: Float, val a: Float)
    private val particulas = ArrayList<Particula>()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val hM = h * 0.30f; val wM = hM * bmM.width / bmM.height
        val gap1 = h * 0.05f; val hLin = max(2f, h / 360f)
        val hTx = h * 0.085f; val wTx = hTx * bmTexto.width / bmTexto.height
        val gap2 = h * 0.035f
        val hLe = h * 0.04f; val wLe = hLe * bmLema.width / bmLema.height
        val total = hM + gap1 + hLin + gap1 + hTx + gap2 + hLe
        var y = (h - total) / 2f
        rM.set((w - wM) / 2, y, (w + wM) / 2, y + hM); y += hM + gap1
        rLinea.set(w * 0.19f, y, w * 0.81f, y + hLin); y += hLin + gap1
        rTexto.set((w - wTx) / 2, y, (w + wTx) / 2, y + hTx); y += hTx + gap2
        rLema.set((w - wLe) / 2, y, (w + wLe) / 2, y + hLe)

        bg.shader = RadialGradient(w / 2f, h / 2f, max(w, h) * 0.75f,
            Color.rgb(255, 255, 255), Color.rgb(240, 234, 220), Shader.TileMode.CLAMP)

        particulas.clear()
        val rnd = Random(7)
        repeat(70) {
            particulas.add(Particula(rnd.nextFloat() * w, rnd.nextFloat() * h,
                (1.2f + rnd.nextFloat() * 2.8f) * h / 720f, (12 + rnd.nextFloat() * 30) * h / 720f,
                rnd.nextFloat() * 6.28f, 0.15f + rnd.nextFloat() * 0.45f))
        }
    }

    fun skip() {
        if (done) return
        start = SystemClock.uptimeMillis() - Splash.TOTAL
        invalidate()
    }

    fun stop() { stopped = true }

    // ---------- utilidades de tiempo ----------
    private fun p(t: Float, ini: Float, dur: Float) = min(1f, max(0f, (t - ini) / dur))
    private fun easeOut(x: Float) = 1f - (1f - x).pow(3)
    private fun easeInOut(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

    override fun onDraw(c: Canvas) {
        if (start == 0L) start = SystemClock.uptimeMillis()
        val t = (SystemClock.uptimeMillis() - start).toFloat()
        val w = width.toFloat(); val h = height.toFloat()

        // Fondo
        c.drawRect(0f, 0f, w, h, bg)

        // Partículas doradas
        val pIn = p(t, 0f, 900f)
        for (pt in particulas) {
            val yy = ((pt.y - t / 1000f * pt.vel) % h + h) % h
            val xx = pt.x + sin(t / 1400f + pt.fase) * pt.r * 6
            glow.color = gold
            glow.alpha = (255 * pt.a * pIn * (0.6f + 0.4f * sin(t / 600f + pt.fase))).toInt().coerceIn(0, 255)
            c.drawCircle(xx, yy, pt.r, glow)
        }

        // ---------- La M trazo a trazo ----------
        val pIzq = easeInOut(p(t, 300f, 800f))
        val pV = easeInOut(p(t, 900f, 800f))
        val pDer = easeInOut(p(t, 1500f, 800f))
        val pulso = p(t, 2300f, 700f)
        val esc = 1f + 0.045f * sin(PI.toFloat() * pulso)

        c.save()
        c.scale(esc, esc, rM.centerX(), rM.centerY())
        val layer = c.saveLayer(rM.left - 10, rM.top - 10, rM.right + 10, rM.bottom + 10, null)
        drawPiece(c, 0f, 0.20f, 1f - pIzq, 1f)          // pierna izquierda (sube)
        drawPiece(c, 0.20f, 0.80f, 0f, pV)              // V central (baja)
        drawPiece(c, 0.80f, 1f, 1f - pDer, 1f)          // pierna derecha (sube)
        // Brillos diagonales
        drawShine(c, p(t, 2400f, 900f))
        drawShine(c, p(t, 5900f, 900f))
        c.restoreToCount(layer)
        c.restore()

        // Chispas en la punta de cada trazo
        if (pIzq in 0.01f..0.99f) spark(c, rM.left + rM.width() * 0.10f, rM.top + rM.height() * (1 - pIzq), 1f)
        if (pV in 0.01f..0.99f) {
            spark(c, rM.left + rM.width() * (0.20f + 0.30f * pV), rM.top + rM.height() * 0.55f * pV, 0.8f)
            spark(c, rM.right - rM.width() * (0.20f + 0.30f * pV), rM.top + rM.height() * 0.55f * pV, 0.8f)
        }
        if (pDer in 0.01f..0.99f) spark(c, rM.left + rM.width() * 0.90f, rM.top + rM.height() * (1 - pDer), 1f)

        // ---------- Línea dorada ----------
        val pLin = easeOut(p(t, 3000f, 900f))
        if (pLin > 0f) {
            val half = rLinea.width() / 2 * pLin
            src.set(0, 0, bmLinea.width, bmLinea.height)
            dst.set(rLinea.centerX() - half, rLinea.top, rLinea.centerX() + half, rLinea.bottom)
            paint.alpha = 255
            c.drawBitmap(bmLinea, src, dst, paint)
            if (pLin < 0.98f) {
                spark(c, rLinea.centerX() - half, rLinea.centerY(), 0.7f)
                spark(c, rLinea.centerX() + half, rLinea.centerY(), 0.7f)
            }
        }

        // ---------- MEBEK letra por letra ----------
        for (i in 0 until 5) {
            val pl = easeOut(p(t, 3500f + i * 170f, 650f))
            if (pl <= 0f) continue
            val a = letras[i]; val b = letras[i + 1]
            src.set((bmTexto.width * a).toInt(), 0, (bmTexto.width * b).toInt(), bmTexto.height)
            val dy = (1 - pl) * rTexto.height() * 0.45f
            dst.set(rTexto.left + rTexto.width() * a, rTexto.top - dy, rTexto.left + rTexto.width() * b, rTexto.bottom - dy)
            paint.alpha = (255 * pl).toInt()
            c.drawBitmap(bmTexto, src, dst, paint)
        }

        // ---------- Lema cerrando el espaciado ----------
        val pLe = easeOut(p(t, 4700f, 1100f))
        if (pLe > 0f) {
            val sx = 1.18f - 0.18f * pLe
            val cw = rLema.width() * sx / 2
            src.set(0, 0, bmLema.width, bmLema.height)
            dst.set(rLema.centerX() - cw, rLema.top, rLema.centerX() + cw, rLema.bottom)
            paint.alpha = (255 * pLe).toInt()
            c.drawBitmap(bmLema, src, dst, paint)
        }
        paint.alpha = 255

        if (t >= Splash.TOTAL && !done) { done = true; onFinish() }
        if (!stopped) postInvalidateOnAnimation()
    }

    /** Dibuja una franja vertical de la M (x en fracción), recortada entre yIni..yFin (fracción). */
    private fun drawPiece(c: Canvas, x0: Float, x1: Float, yIni: Float, yFin: Float) {
        if (yFin <= yIni) return
        src.set((bmM.width * x0).toInt(), (bmM.height * yIni).toInt(), (bmM.width * x1).toInt(), (bmM.height * yFin).toInt())
        if (src.height() <= 0) return
        dst.set(rM.left + rM.width() * x0, rM.top + rM.height() * yIni, rM.left + rM.width() * x1, rM.top + rM.height() * yFin)
        paint.alpha = 255
        c.drawBitmap(bmM, src, dst, paint)
    }

    /** Franja de luz diagonal que cruza la M (solo pinta sobre lo ya dibujado). */
    private fun drawShine(c: Canvas, pr: Float) {
        if (pr <= 0f || pr >= 1f) return
        val band = rM.width() * 0.35f
        val x = rM.left - band * 2 + (rM.width() + band * 4) * easeInOut(pr)
        shine.shader = LinearGradient(x - band, rM.top, x + band, rM.bottom,
            intArrayOf(Color.TRANSPARENT, Color.argb(200, 255, 248, 225), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(rM.left - 10, rM.top - 10, rM.right + 10, rM.bottom + 10, shine)
    }

    /** Chispa dorada con halo. */
    private fun spark(c: Canvas, x: Float, y: Float, k: Float) {
        val r = height * 0.03f * k
        glow.shader = RadialGradient(x, y, r, intArrayOf(Color.argb(255, 255, 250, 230), Color.argb(170, 230, 190, 90), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        glow.alpha = 255
        c.drawCircle(x, y, r, glow)
        glow.shader = null
    }
}
