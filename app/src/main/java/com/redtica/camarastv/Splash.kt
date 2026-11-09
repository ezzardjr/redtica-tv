package com.redtica.camarastv

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Animación de arranque con el logo de Mebek (≈3.5 s):
 *  1. La "M" aparece creciendo y subiendo.
 *  2. La línea dorada se abre desde el centro.
 *  3. Entra "MEBEK" y luego "EXCELENCIA SIN LÍMITES".
 *  4. La M hace un pulso suave y todo se desvanece hacia las cámaras.
 * Las cámaras se van conectando por detrás mientras tanto.
 */
object Splash {
    fun show(act: Activity, parent: FrameLayout) {
        val h = act.resources.displayMetrics.heightPixels
        val w = act.resources.displayMetrics.widthPixels

        val overlay = FrameLayout(act).apply {
            setBackgroundColor(Color.WHITE)
            isClickable = true
        }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun img(res: Int) = ImageView(act).apply {
            setImageResource(res); adjustViewBounds = true; alpha = 0f
        }
        val m = img(R.drawable.mebek_m)
        val linea = img(R.drawable.mebek_linea).apply { scaleType = ImageView.ScaleType.FIT_XY }
        val texto = img(R.drawable.mebek_texto)
        val lema = img(R.drawable.mebek_lema)

        col.addView(m, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (h * 0.30).toInt()))
        col.addView(linea, LinearLayout.LayoutParams((w * 0.62).toInt(), maxOf(2, h / 360)).apply { topMargin = (h * 0.05).toInt() })
        col.addView(texto, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (h * 0.085).toInt()).apply { topMargin = (h * 0.05).toInt() })
        col.addView(lema, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (h * 0.04).toInt()).apply { topMargin = (h * 0.035).toInt() })

        overlay.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        parent.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val dec = DecelerateInterpolator(1.6f)

        // 1. M
        m.scaleX = 0.55f; m.scaleY = 0.55f; m.translationY = h * 0.06f
        m.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(250).setDuration(1000).setInterpolator(OvershootInterpolator(1.2f)).start()

        // 2. Línea dorada desde el centro
        linea.scaleX = 0f
        linea.animate().alpha(1f).scaleX(1f).setStartDelay(1050).setDuration(700).setInterpolator(dec).start()

        // 3. Textos
        texto.translationY = h * 0.03f
        texto.animate().alpha(1f).translationY(0f).setStartDelay(1450).setDuration(650).setInterpolator(dec).start()
        lema.translationY = h * 0.02f
        lema.animate().alpha(1f).translationY(0f).setStartDelay(1900).setDuration(650).setInterpolator(dec).start()

        // 4. Pulso de la M y salida
        m.postDelayed({
            m.animate().scaleX(1.05f).scaleY(1.05f).setStartDelay(0).setDuration(350).withEndAction {
                m.animate().scaleX(1f).scaleY(1f).setDuration(350).start()
            }.start()
        }, 2650)
        overlay.postDelayed({
            overlay.animate().alpha(0f).setDuration(600).withEndAction {
                overlay.visibility = View.GONE
                parent.removeView(overlay)
            }.start()
        }, 3500)
    }
}
