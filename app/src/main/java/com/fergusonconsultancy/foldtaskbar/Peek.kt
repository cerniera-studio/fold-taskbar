package com.fergusonconsultancy.foldtaskbar

import com.cerniera.kit.*
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Show on unfold, Peek (2 Nov 2026): only the active-app pill, lit where the bar sits.
 *
 * One highlight and nothing else (the Lit Glass lessons): visible on the first frame as an
 * ember, it swells to full light, holds, and goes out the way the bar would leave, towards the
 * edge the bar rests against. It answers "where is it?" without taking the screen.
 * Never touchable: the strips beneath still call the bar.
 */
class PeekLight(ctx: Context, private val vertical: Boolean, private val towardX: Float, private val towardY: Float) : View(ctx) {

    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PEEK_CORE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PEEK_CORE }
    private var level = 0.18f
    private var drift = 0f
    private var anim: ValueAnimator? = null

    /** Plays once: in [IN_MS], held for [HOLD_MS], out over [OUT_MS], then [done]. */
    fun play(done: () -> Unit) {
        val total = IN_MS + HOLD_MS + OUT_MS
        val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
        anim = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
            duration = total
            interpolator = null
            addUpdateListener {
                val t = it.animatedValue as Float
                when {
                    t < IN_MS -> { level = 0.18f + 0.82f * ease.getInterpolation(t / IN_MS); drift = 0f }
                    t < IN_MS + HOLD_MS -> { level = 1f; drift = 0f }
                    else -> {
                        val f = ease.getInterpolation((t - IN_MS - HOLD_MS) / OUT_MS)
                        level = 1f - f; drift = f
                    }
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = done()
            })
            start()
        }
    }

    fun cancel() { anim?.removeAllListeners(); anim?.cancel(); anim = null }

    override fun onDraw(canvas: Canvas) {
        val len = context.dp(PEEK_LEN_DP).toFloat()
        val thick = context.dp(PEEK_THICK_DP).toFloat()
        // Leaving, the light slips a little towards the edge the bar rests against
        val cx = width / 2f + towardX * drift * context.dp(8)
        val cy = height / 2f + towardY * drift * context.dp(8)
        val pill = if (vertical) RectF(cx - thick / 2, cy - len / 2, cx + thick / 2, cy + len / 2)
            else RectF(cx - len / 2, cy - thick / 2, cx + len / 2, cy + thick / 2)
        val a = level.coerceIn(0f, 1f)
        // A soft glow of its own tone, wider than the pill, falling off towards its ends
        for (k in 3 downTo 1) {
            val g = RectF(pill).apply { inset(-thick * k * 1.1f, -thick * k * 1.1f) }
            glow.alpha = (0x22 * a / k).toInt()
            val r = minOf(g.width(), g.height()) / 2
            canvas.drawRoundRect(g, r, r, glow)
        }
        val halo = RectF(pill).apply { inset(-context.dp(1.5f).toFloat(), -context.dp(1.5f).toFloat()) }
        ring.alpha = (0x8C * a).toInt()
        canvas.drawRoundRect(halo, minOf(halo.width(), halo.height()) / 2, minOf(halo.width(), halo.height()) / 2, ring)
        core.alpha = (255 * a).toInt()
        canvas.drawRoundRect(pill, thick / 2, thick / 2, core)
    }

    companion object {
        const val IN_MS = 320L
        const val HOLD_MS = 1600L
        const val OUT_MS = 520L
        /** Longer and a little thicker than the resting pill (0.42 of the icon by 4dp), so it is found at a glance. */
        const val PEEK_LEN_DP = 26
        const val PEEK_THICK_DP = 5
        /** The window around the pill, room for its glow. */
        const val PEEK_BOX_DP = 64
        private const val PEEK_CORE = 0xFFF6E4BC.toInt()
    }
}
