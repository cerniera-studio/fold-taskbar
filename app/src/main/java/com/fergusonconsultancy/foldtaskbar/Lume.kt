package com.fergusonconsultancy.foldtaskbar

import com.cerniera.kit.*
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

// ---- Lume (Fluido phase 1, 18 Nov 2026) ----------------------------------------------------
// The one small light in the bar's trim where the family shows it has noticed something. At
// rest, a champagne point, barely there. When Senso offers a moment it swells into a ring and
// offers one thing; the icons beside it yield as it grows (Membrane). During a gesture on the bar
// it shrinks back to a point (Concentrato). After RECEDE_MS without a touch it recedes to a lit
// point, still holding the moment (Giulietta). Tap acts; swipe it off the bar and it says nothing
// more about that moment today; hold it and it says plainly what it means, with its one action.
//
// 26 Nov 2026: listening is offered only while now playing is off the bar (the thumbnail already
// is the music's light), and the hold card lost its percentage, "Not today" and "Fluido settings":
// how sure Senso is belongs in Settings → Fluido, and a swipe already puts a moment away.
//
// Rules only, three moments in the pilot: rain coming, leaving soon, listening.

/** What Lume holds, across the bar being laid out again (its views come and go; this stays). */
object Lume {
    val KINDS = setOf(Kind.RAIN_COMING, Kind.LEAVING_SOON, Kind.LISTENING)

    /** The moment held, or null: rest. */
    var moment: Moment? = null
    /** Swollen and offering (true), or receded to a point. */
    var offering = false
    /** A gesture is under way on the bar: Lume steps back to a point. */
    var quiet = false
    /** The track's art, for listening. */
    var art: Bitmap? = null
    /** For listening: the track is paused, so a tap resumes it. */
    var paused = false

    private val views = java.util.Collections.newSetFromMap(java.util.WeakHashMap<LumeView, Boolean>())
    fun attach(v: LumeView) { views += v }
    fun detach(v: LumeView) { views -= v }
    fun changed() = views.toList().forEach { it.refresh() }
}

/** What the bar does when Lume is touched. */
interface LumeActions {
    fun onLume(view: View)
    fun onLumeHeld(view: View)
    fun onLumeDismissed(view: View)
}

@SuppressLint("ViewConstructor")
class LumeView(context: Context, private val sizePx: Int, private val vertical: Boolean, private val actions: LumeActions) : View(context) {
    private val restLong = context.dp(12)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(1.6f).toFloat() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = context.dp(1.6f).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var ink = Kit.Fixed.IVORY
    /** 0 a point, 1 a full ring. */
    private var swell = 0f
    /** 0 nothing held, 1 a moment held. */
    private var lit = 0f
    private var anim: android.animation.ValueAnimator? = null

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setInk(c: Int) { if (ink != c) { ink = c; invalidate() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Lume.attach(this)
        // Laid out again with a moment already offered: in place at once, nothing swells twice
        swell = target(); lit = if (Lume.moment != null) 1f else 0f
        describe()
    }

    override fun onDetachedFromWindow() { Lume.detach(this); anim?.cancel(); super.onDetachedFromWindow() }

    private fun target() = if (Lume.moment != null && Lume.offering && !Lume.quiet) 1f else 0f

    /** Moves towards what Lume holds now, on the family's curve. */
    fun refresh() {
        describe()
        val toSwell = target()
        val toLit = if (Lume.moment != null) 1f else 0f
        if (toSwell == swell && toLit == lit) { invalidate(); return }
        anim?.cancel()
        val s0 = swell; val l0 = lit
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            // Stepping back for a gesture is quick; swelling is the longer glide
            duration = if (toSwell < s0) Kit.QUICK else Kit.GLIDE
            interpolator = Kit.ease
            addUpdateListener {
                val t = it.animatedValue as Float
                swell = s0 + (toSwell - s0) * t; lit = l0 + (toLit - l0) * t
                requestLayout(); invalidate()
            }
            start()
        }
    }

    private fun describe() {
        val m = Lume.moment
        contentDescription = if (m == null) "Lume: nothing to say" else "Lume: ${m.kind.label}, ${m.why}. Tap to act, hold for more, swipe away for today"
    }

    override fun onMeasure(w: Int, h: Int) {
        val along = (restLong + (sizePx - restLong) * swell).toInt()
        if (vertical) setMeasuredDimension(sizePx, along) else setMeasuredDimension(along, sizePx + context.dp(6))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = if (vertical) height / 2f else sizePx / 2f
        // Champagne over dark glass; the day's deeper champagne over light glass, so it still reads
        val champagne = if (lum(ink) < 0.5f) DAY_CHAMPAGNE else Kit.Fixed.CHAMPAGNE
        // The point: barely there at rest, brighter while a moment is held
        val point = context.dp(1.6f) + context.dp(0.9f) * lit
        val big = sizePx * 0.40f
        val r = point + (big - point) * swell
        if (lit > 0f && swell < 0.5f) {
            fill.color = withAlpha(champagne, 0.22f * lit * (1f - swell * 2f))
            // The halo stays inside Lume's own narrow box, so it is never cut square
            canvas.drawCircle(cx, cy, minOf(point * 2.6f, minOf(width, height) / 2f - 1f), fill)
        }
        fill.color = withAlpha(champagne, (0.4f + 0.55f * lit) * (1f - swell))
        canvas.drawCircle(cx, cy, r, fill)
        if (swell <= 0.02f) return
        // The ring, and what it offers inside it
        fill.color = withAlpha(champagne, 0.14f * swell)
        canvas.drawCircle(cx, cy, r, fill)
        ring.color = withAlpha(champagne, swell)
        canvas.drawCircle(cx, cy, r, ring)
        val m = Lume.moment ?: return
        val g = r * 0.62f
        line.color = withAlpha(ink, smooth(0.35f, 1f, swell))
        when (m.kind) {
            Kind.RAIN_COMING -> brolly(canvas, cx, cy, g)
            Kind.LEAVING_SOON -> clock(canvas, cx, cy, g)
            Kind.LISTENING -> Lume.art?.let { art(canvas, it, cx, cy, r - ring.strokeWidth) } ?: note(canvas, cx, cy, g)
            else -> canvas.drawCircle(cx, cy, g * 0.2f, line)
        }
    }

    /** A brolly: the canopy, its scallops, the shaft and its hook. */
    private fun brolly(c: Canvas, x: Float, y: Float, g: Float) {
        val top = y - g * 0.15f
        path.reset()
        path.addArc(RectF(x - g * 0.8f, top - g * 0.7f, x + g * 0.8f, top + g * 0.7f), 180f, 180f)
        val w = g * 1.6f / 3f
        for (i in 0 until 3) {
            val l = x - g * 0.8f + i * w
            path.moveTo(l + w, top)
            path.arcTo(RectF(l, top - w * 0.35f, l + w, top + w * 0.35f), 0f, 180f, false)
        }
        path.moveTo(x, top); path.lineTo(x, y + g * 0.62f)
        path.arcTo(RectF(x - g * 0.3f, y + g * 0.47f, x, y + g * 0.77f), 0f, 180f, false)
        c.drawPath(path, line)
    }

    /** The next event: a clock face, its hands set off for the time to go. */
    private fun clock(c: Canvas, x: Float, y: Float, g: Float) {
        c.drawCircle(x, y, g * 0.78f, line)
        c.drawLine(x, y, x, y - g * 0.5f, line)
        c.drawLine(x, y, x + g * 0.36f, y + g * 0.16f, line)
    }

    /** Listening, with no art: a pair of quavers. */
    private fun note(c: Canvas, x: Float, y: Float, g: Float) {
        val a = x - g * 0.45f; val b = x + g * 0.45f
        c.drawCircle(a - g * 0.16f, y + g * 0.5f, g * 0.17f, line)
        c.drawCircle(b - g * 0.16f, y + g * 0.38f, g * 0.17f, line)
        c.drawLine(a, y + g * 0.5f, a, y - g * 0.55f, line)
        c.drawLine(b, y + g * 0.38f, b, y - g * 0.67f, line)
        c.drawLine(a, y - g * 0.55f, b, y - g * 0.67f, line)
    }

    /** The track's art, cut to the ring. */
    private fun art(c: Canvas, bmp: Bitmap, x: Float, y: Float, r: Float) {
        val s = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val k = 2 * r / minOf(bmp.width, bmp.height)
        s.setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate(x - bmp.width * k / 2, y - bmp.height * k / 2) })
        artPaint.shader = s; artPaint.alpha = (255 * swell).toInt()
        c.drawCircle(x, y, r, artPaint)
    }

    // ---- Touch: tap acts, hold says why, a swipe across the bar puts it away for today ----

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var held = false
    private var swiped = false
    private val hold = Runnable { held = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); actions.onLumeHeld(this) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; moved = false; held = false; swiped = false
                // The bar must not take this touch to move itself while Lume decides what it is
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).start()
            }
            MotionEvent.ACTION_MOVE -> {
                val across = if (vertical) e.rawX - downX else e.rawY - downY
                val along = if (vertical) e.rawY - downY else e.rawX - downX
                if (abs(across) > slop || abs(along) > slop) { moved = true; removeCallbacks(hold) }
                if (!swiped && !held && Lume.moment != null && abs(across) > slop * 2 && abs(across) > abs(along)) {
                    swiped = true
                    val dir = if (across > 0) 1f else -1f
                    // Thrown off the bar the way the finger went, fading as it goes
                    animate().cancel()
                    animate().apply { if (vertical) translationX(dir * sizePx) else translationY(dir * sizePx) }
                        .alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(Kit.QUICK).setInterpolator(Kit.ease)
                        .withEndAction { translationX = 0f; translationY = 0f; alpha = 1f; scaleX = 1f; scaleY = 1f; actions.onLumeDismissed(this) }
                        .start()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                if (!swiped) animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(Kit.ease).start()
                if (!moved && !held && !swiped) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    actions.onLume(this)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                if (!swiped) animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
        }
        return true
    }

    companion object {
        /** Avorio's champagne, fixed: Lume over light glass. */
        const val DAY_CHAMPAGNE = 0xFF9A7A45.toInt()
    }
}
