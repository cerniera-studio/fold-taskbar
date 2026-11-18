package com.fergusonconsultancy.foldtaskbar

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.cerniera.kit.Kit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dittico's ghost (18 Nov 2026, redrawn the same evening): the second leaf of a diptych.
 *
 * The view's crease side sits on the phone's crease: x = 0 held upright, y = 0 turned. Everything
 * is drawn in "growth space" (u away from the crease, v along it) and mapped to the screen, so one
 * drawing serves both ways round while the words always read across.
 *
 * - Born: a champagne seam lights on the crease, then the leaf swells out of it, its leading edge
 *   bowed like a meniscus, overshooting a touch and snapping taut (one tick). The icon, the
 *   eyebrow and the name follow it out in turn.
 * - The wait: a line of rosso beads along the hinge edge, the Giulietta's tail light. They go out
 *   one by one from the far end, so how long remains is always visible.
 * - Touch: a finger pauses the beads and the leaf leans towards it. Lifting on it accepts: the
 *   beads flare champagne in a sweep away from the hinge and the leaf flows outward as the split
 *   forms. Sliding off cancels, and the wait resumes.
 * - Recede: the words go first (Concentrato), the leaf is drawn back into the crease, thins to a
 *   seam, and the last point of light dips into the hinge.
 */
@SuppressLint("ViewConstructor")
class DitticoGhost(
    context: Context,
    private val finish: Finish,
    private val icon: Drawable?,
    private val name: String,
    beside: String,
    /** True when the main screen is held upright: the crease is to the left of this view. */
    private val upright: Boolean,
    private val waitMs: Long,
    private val onAccept: () -> Unit,
    private val onExpire: () -> Unit,
) : View(context) {

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ---- measures (growth space) ----
    private val gap = dp(6f)            // the crease to the leaf
    private val margin = dp(22f)        // room for the shadow and the lean
    private val leafU: Float            // how far the leaf reaches from the crease
    private val leafV: Float            // its length along the crease
    private val rHinge = dp(7f)
    private val rFar = dp(28f)
    private val iconSize = dp(46f)

    // ---- type ----
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Kit.display(context); textSize = dp(23f); color = finish.ink
    }
    private val eyebrowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Kit.sans(context, 500); textSize = dp(10.5f); letterSpacing = 0.2f
        color = if (finish == Finish.AVORIO) 0xFF8A6A35.toInt() else Kit.Fixed.CHAMPAGNE
    }
    private val eyebrow = beside.uppercase()
    private val nameText: CharSequence
    private val textW: Float

    init {
        val maxText = dp(190f)
        nameText = TextUtils.ellipsize(name, namePaint, maxText, TextUtils.TruncateAt.END)
        textW = max(namePaint.measureText(nameText.toString()), eyebrowPaint.measureText(eyebrow))
        val contentW = dp(26f) + iconSize + dp(16f) + textW + dp(30f)
        // Upright the leaf reaches out to the side and the words run outward; turned it drops
        // below the crease and the words run along it
        leafU = if (upright) contentW else dp(92f)
        leafV = if (upright) dp(92f) else contentW
        contentDescription = "Open $name ${beside.lowercase()}"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
    }

    private val totalU get() = gap + leafU * 1.1f + dp(10f) + margin
    private val totalV get() = leafV + margin * 2

    override fun onMeasure(w: Int, h: Int) {
        val u = totalU.toInt(); val v = totalV.toInt()
        setMeasuredDimension(if (upright) u else v, if (upright) v else u)
    }

    // ---- state ----
    private var grow = 0f          // 0 seam only … 1 leaf out (springs past 1)
    private var bow = 0f           // the leading edge's meniscus, in px
    private var seam = 0f          // the seam's light
    private var content = 0f       // words and icon
    private var lean = 0f          // a finger's pull
    private var leanV = 0f         // where along the leaf the finger is
    private var flare = -1f        // accept sweep, 0..1, or -1
    private var waited = 0L        // ms of the wait used
    private var waitFrom = 0L      // uptime when the wait last resumed, or 0 while held
    private var leaving = false
    private var accepted = false
    private var anim: ValueAnimator? = null
    private val ease = Kit.ease
    private val soft = PathInterpolator(0.3f, 0f, 0.1f, 1f)

    private val beads = 9

    // ---- paints ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = finish.fill }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = finish.edge }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); strokeCap = Paint.Cap.ROUND }
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val well = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        shadow.setShadowLayer(dp(16f), 0f, dp(5f), 0x73000000)
    }

    // ---- growth space to the screen ----
    private fun x(u: Float, v: Float) = if (upright) u else v
    private fun y(u: Float, v: Float) = if (upright) v else u
    private fun moveTo(u: Float, v: Float) = path.moveTo(x(u, v), y(u, v))
    private fun lineTo(u: Float, v: Float) = path.lineTo(x(u, v), y(u, v))
    private fun quadTo(cu: Float, cv: Float, u: Float, v: Float) = path.quadTo(x(cu, cv), y(cu, cv), x(u, v), y(u, v))

    /** The leaf's outline: tight corners at the hinge, generous ones at the far edge, the far edge bowed. */
    private fun leafPath(reach: Float, v0: Float, v1: Float, bulge: Float) {
        path.reset()
        val u0 = gap
        val u1 = gap + reach
        val rh = min(rHinge, reach / 2)
        val rf = min(rFar, max(0f, reach - rh))
        val mid = (v0 + v1) / 2
        moveTo(u0 + rh, v0)
        lineTo(u1 - rf, v0)
        quadTo(u1, v0, u1, v0 + rf)
        // The leading edge: a meniscus, bowed out (or in) at its middle
        quadTo(u1 + bulge * 2f, mid, u1, v1 - rf)
        quadTo(u1, v1, u1 - rf, v1)
        lineTo(u0 + rh, v1)
        quadTo(u0, v1, u0, v1 - rh)
        lineTo(u0, v0 + rh)
        quadTo(u0, v0, u0 + rh, v0)
        path.close()
    }

    override fun onDraw(c: Canvas) {
        val v0 = margin; val v1 = margin + leafV
        val g = max(0f, grow)
        val reach = leafU * g + lean * dp(5f)

        // The seam on the crease: always first to light and last to go
        if (seam > 0.01f) {
            seamPaint.strokeWidth = dp(1.4f)
            val half = leafV / 2 * (0.25f + 0.75f * min(1f, seam * 1.4f))
            val mid = (v0 + v1) / 2
            seamPaint.shader = if (upright)
                LinearGradient(0f, mid - half, 0f, mid + half, intArrayOf(0, Kit.Fixed.CHAMPAGNE, 0), null, Shader.TileMode.CLAMP)
            else LinearGradient(mid - half, 0f, mid + half, 0f, intArrayOf(0, Kit.Fixed.CHAMPAGNE, 0), null, Shader.TileMode.CLAMP)
            seamPaint.alpha = (255 * min(1f, seam)).toInt()
            c.drawLine(x(dp(1f), mid - half), y(dp(1f), mid - half), x(dp(1f), mid + half), y(dp(1f), mid + half), seamPaint)
        }
        if (reach < dp(2f)) return

        val alpha = min(1f, g * 2.2f)
        leafPath(reach, v0, v1, bow)

        // Shadow, body, the light that pools from the hinge, the edge
        shadow.alpha = (255 * alpha * 0.9f).toInt(); c.drawPath(path, shadow)
        fill.alpha = (Color.alpha(finish.fill) * alpha).toInt(); c.drawPath(path, fill)
        val warm = if (finish == Finish.AVORIO) 0x22B8995F else 0x30D9BB86
        light.shader = LinearGradient(x(gap, 0f), y(gap, 0f), x(gap + leafU * 0.62f, 0f), y(gap + leafU * 0.62f, 0f),
            intArrayOf(warm, 0), null, Shader.TileMode.CLAMP)
        light.alpha = (255 * alpha).toInt(); c.drawPath(path, light)
        if (flare >= 0f) {
            // Accept: champagne flows outward through the leaf
            val f = flare
            light.shader = LinearGradient(x(gap + leafU * (f - 0.35f), 0f), y(gap + leafU * (f - 0.35f), 0f),
                x(gap + leafU * f, 0f), y(gap + leafU * f, 0f),
                intArrayOf(0, 0x55F7E6C2, 0), floatArrayOf(0f, 0.85f, 1f), Shader.TileMode.CLAMP)
            light.alpha = 255; c.drawPath(path, light)
        }
        edge.alpha = (Color.alpha(finish.edge).coerceAtLeast(0x26) * alpha).toInt(); c.drawPath(path, edge)
        // A sheen along the edge that faces the light (top held upright, far side turned)
        sheen.color = if (finish == Finish.AVORIO) 0x55FFFFFF else 0x2EFFFFFF
        sheen.alpha = (Color.alpha(sheen.color) * alpha).toInt()
        val su0 = gap + rHinge + dp(4f); val su1 = gap + reach - rFar
        if (upright && su1 > su0) c.drawLine(x(su0, v0 + dp(1.5f)), y(su0, v0 + dp(1.5f)), x(su1, v0 + dp(1.5f)), y(su1, v0 + dp(1.5f)), sheen)

        drawBeads(c, v0, v1, alpha)
        drawContent(c, v0, v1)
    }

    /** The tail light along the hinge edge, going out one by one from the far end. */
    private fun drawBeads(c: Canvas, v0: Float, v1: Float, alpha: Float) {
        val left = 1f - min(1f, waitUsed() / waitMs.toFloat())
        val u = gap + dp(8f)
        val from = v0 + rFar * 0.55f; val to = v1 - rFar * 0.55f
        val r = dp(2.1f)
        for (i in 0 until beads) {
            val t = i / (beads - 1f)
            val v = from + (to - from) * t
            // Bead i is lit while the wait left covers it; the last one fades rather than snaps
            val lit = ((left * beads) - (beads - 1 - i).toFloat()).coerceIn(0f, 1f)
            var colour = Kit.Fixed.ROSSO
            var a = lit
            if (flare >= 0f) {
                // The sweep: champagne flares through every bead, nearest the hinge first
                val k = (flare * 1.6f - t * 0.6f).coerceIn(0f, 1f)
                colour = Kit.Fixed.CHAMPAGNE; a = sin(k * PI).toFloat().coerceAtLeast(0f)
            } else if (lean > 0f) a = max(a, 0.35f + 0.65f * lit)
            if (a <= 0.01f) {
                bead.clearShadowLayer(); bead.color = (finish.ink and 0x00FFFFFF) or 0x1F000000
                bead.alpha = (0x1F * alpha).toInt()
                c.drawCircle(x(u, v), y(u, v), r * 0.8f, bead); continue
            }
            bead.color = colour
            bead.setShadowLayer(dp(5f) * a, 0f, 0f, (colour and 0x00FFFFFF) or ((0xB0 * a).toInt() shl 24))
            bead.alpha = (255 * a * alpha).toInt()
            c.drawCircle(x(u, v), y(u, v), r, bead)
        }
    }

    private fun drawContent(c: Canvas, v0: Float, v1: Float) {
        if (content <= 0.01f) return
        c.save(); c.clipPath(path)
        fun stage(delay: Float) = ((content - delay) / (1f - delay)).coerceIn(0f, 1f).let { ease.getInterpolation(it) }
        val rise = dp(7f)
        // The words read across both ways round: lay out in screen space from the leaf's rect,
        // clear of the lamps on the hinge edge, rising away from the crease
        val reach = leafU * max(0f, grow)
        val left = if (upright) gap + dp(26f) else margin + dp(26f)
        val centreY = if (upright) (v0 + v1) / 2 else gap + reach / 2 + dp(4f)
        val ic = stage(0f)
        val ix = left - (if (upright) rise * (1 - ic) else 0f)
        val iy = centreY - iconSize / 2 - (if (upright) 0f else rise * (1 - ic))
        val wellR = RectF(ix - dp(5f), iy - dp(5f), ix + iconSize + dp(5f), iy + iconSize + dp(5f))
        well.color = (finish.ink and 0x00FFFFFF) or 0x0F000000
        well.alpha = (0x0F * ic).toInt()
        c.drawRoundRect(wellR, dp(16f), dp(16f), well)
        icon?.let {
            it.setBounds(ix.toInt(), iy.toInt(), (ix + iconSize).toInt(), (iy + iconSize).toInt())
            it.alpha = (255 * ic).toInt(); it.draw(c)
        }
        val tx = left + iconSize + dp(16f)
        val textMid = centreY
        val eb = stage(0.18f)
        eyebrowPaint.alpha = (255 * eb).toInt()
        c.drawText(eyebrow, tx - (if (upright) rise * (1 - eb) else 0f), textMid - dp(9f) - (if (upright) 0f else rise * (1 - eb)), eyebrowPaint)
        val nm = stage(0.32f)
        namePaint.alpha = (255 * nm).toInt()
        c.drawText(nameText, 0, nameText.length, tx - (if (upright) rise * (1 - nm) else 0f), textMid + dp(17f) - (if (upright) 0f else rise * (1 - nm)), namePaint)
        c.restore()
    }

    // ---- the wait ----
    private fun waitUsed(): Long = waited + if (waitFrom > 0) SystemClock.uptimeMillis() - waitFrom else 0

    private val ticker = object : Runnable {
        override fun run() {
            if (leaving || accepted) return
            if (waitUsed() >= waitMs) { onExpire(); return }
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun resumeWait() { if (waitFrom == 0L) waitFrom = SystemClock.uptimeMillis(); removeCallbacks(ticker); postOnAnimation(ticker) }
    private fun holdWait() { if (waitFrom > 0) { waited += SystemClock.uptimeMillis() - waitFrom; waitFrom = 0 } }

    // ---- motion ----
    private fun run(ms: Long, interp: android.animation.TimeInterpolator?, step: (Float) -> Unit, end: () -> Unit = {}) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = interp
            addUpdateListener { step(it.animatedValue as Float); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    /** Born from the crease. */
    fun born() {
        var ticked = false
        run(820L, null, { t ->
            seam = (t / 0.16f).coerceIn(0f, 1f)
            // A soft thing may overshoot: a damped spring from 0.16 s
            val s = ((t - 0.14f) / 0.86f).coerceIn(0f, 1f)
            val spring = if (s <= 0f) 0f else 1f - exp(-8f * s) * kotlin.math.cos(9.5f * s)
            grow = spring
            // The leading edge bows while it travels and settles taut
            bow = dp(9f) * sin(min(1f, s * 1.4f) * PI.toFloat()) * (1f - s)
            if (!ticked && s > 0.3f && grow >= 1f) { ticked = true; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            content = ((t - 0.42f) / 0.58f).coerceIn(0f, 1f)
            seam = if (t < 0.5f) seam else 1f - 0.55f * ((t - 0.5f) / 0.5f)
        }, { grow = 1f; bow = 0f; content = 1f; resumeWait() })
    }

    /** Drawn back into the crease; the last point of light dips into the hinge. */
    fun recede(done: () -> Unit) {
        if (leaving) return
        leaving = true; removeCallbacks(ticker)
        val g0 = grow; val c0 = content; val s0 = seam
        run(560L, null, { t ->
            content = c0 * (1f - (t / 0.3f).coerceIn(0f, 1f))
            val s = ease.getInterpolation(((t - 0.12f) / 0.62f).coerceIn(0f, 1f))
            grow = g0 * (1f - s)
            bow = -dp(7f) * sin(s * PI.toFloat())         // the edge leads inward
            lean = lean * (1f - s)
            val tail = ((t - 0.66f) / 0.34f).coerceIn(0f, 1f)
            seam = if (t < 0.66f) max(s0, 0.8f * s) else 0.8f * (1f - tail)
        }, done)
    }

    private fun accept() {
        if (accepted || leaving) return
        accepted = true; removeCallbacks(ticker)
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onAccept()
        val g0 = grow
        run(460L, soft, { t ->
            flare = t
            grow = g0 + 0.08f * sin(min(1f, t * 1.3f) * PI.toFloat() / 2)
            content = 1f - (t / 0.5f).coerceIn(0f, 1f)
            alpha = 1f - ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
        }, { flare = -1f })
    }

    // ---- touch: hold pauses the lamps and the leaf leans; lift on it accepts ----
    private var down = false
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (leaving || accepted) return false
        val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true; holdWait(); leanTo(1f)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            MotionEvent.ACTION_MOVE -> if (down && !inside) { down = false; leanTo(0f); resumeWait() }
            MotionEvent.ACTION_UP -> { if (down && inside) accept() else if (down) { leanTo(0f); resumeWait() }; down = false }
            MotionEvent.ACTION_CANCEL -> { down = false; leanTo(0f); resumeWait() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); accept(); return true }

    private var leanAnim: ValueAnimator? = null
    private fun leanTo(target: Float) {
        leanAnim?.cancel()
        leanAnim = ValueAnimator.ofFloat(lean, target).apply {
            duration = Kit.QUICK; interpolator = ease
            addUpdateListener { lean = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel(); leanAnim?.cancel(); removeCallbacks(ticker)
    }

    private object Color { fun alpha(c: Int) = (c ushr 24) and 0xFF }
}
