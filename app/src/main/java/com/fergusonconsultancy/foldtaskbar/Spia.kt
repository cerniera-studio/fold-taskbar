package com.fergusonconsultancy.foldtaskbar

import com.cerniera.kit.*
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.View
import java.util.concurrent.CopyOnWriteArrayList

// ---- Spia (16 Nov 2026) -------------------------------------------------------------------
// A car's tell-tale lamps, in the bar's trim: one small lamp per person or kind of thing that
// matters, lit for as long as its notification stands. Clearing the notification (here, in the
// shade or in the app itself) puts the lamp out. Nothing is stored or sent: the lamps are worked
// out afresh from the notifications Android is showing, in memory only.

/** The kinds of lamp, in the order they sit on the bar. Colour alone says which is which. */
enum class Lamp(val label: String, val detail: String, val colour: Int) {
    PERSON("People", "One rosso lamp for each person you name", 0xFFD7262E.toInt()),
    WORK("Work", "One amber lamp for work apps", 0xFFE39A3B.toInt()),
    ARRIVING("Arriving", "One green lamp for something on its way: a ride, a delivery", 0xFF8FB79E.toInt()),
    FAMILY("Cerniera", "One champagne lamp for the Cerniera apps: a Nodo knot falling due, Curva's kick-off", 0xFFD9BB86.toInt()),
}

/** One lit lamp: who or what it is for, the latest line, and the notifications behind it. */
data class Lit(
    val id: String,
    val kind: Lamp,
    val who: String,
    val line: String,
    val keys: List<String>,
    val pkg: String,
    val intent: PendingIntent?,
    val autoCancel: Boolean,
    val time: Long,
)

object Spia {
    /** The lamps lit now, in bar order. */
    @Volatile var lamps: List<Lit> = emptyList(); private set
    /** The listener while Android has it connected; only it can clear a notification. */
    @Volatile var listener: MediaListener? = null
    private val watchers = CopyOnWriteArrayList<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun watch(f: () -> Unit) { watchers += f }
    fun unwatch(f: () -> Unit) { watchers -= f }

    // Starting points for the kinds (general knowledge of package names, editable in Settings)
    val WORK_DEFAULTS = setOf("com.microsoft.teams", "com.microsoft.office.outlook", "com.Slack", "com.google.android.gm")
    val ARRIVING_DEFAULTS = setOf("com.ubercab", "com.ubercab.eats", "com.deliveroo.orderapp", "com.justeat.app.uk",
        "com.amazon.mShop.android.shopping", "com.royalmail.rmapp", "com.dpd.yourdpd", "com.evri.app")

    /** Works the lamps out again from everything Android is showing. Any thread. */
    fun recompute(c: Context, shown: Array<StatusBarNotification>?) {
        val next = if (!Prefs.spia(c) || shown == null) emptyList() else light(c, shown)
        if (next == lamps) return
        lamps = next
        main.post { watchers.forEach { runCatching { it() } } }
    }

    private fun light(c: Context, shown: Array<StatusBarNotification>): List<Lit> {
        val people = Prefs.spiaPeople(c)
        val work = Prefs.spiaApps(c, Lamp.WORK)
        val arriving = Prefs.spiaApps(c, Lamp.ARRIVING)
        val family = Prefs.spiaFamily(c)
        val byId = LinkedHashMap<String, Lit>()
        for (sbn in shown.sortedBy { it.postTime }) {
            val n = sbn.notification ?: continue
            if (sbn.packageName == c.packageName) continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            // Media players are the now-playing tile's business, never a lamp
            if (n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) continue
            val pkg = sbn.packageName
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            val ongoing = !sbn.isClearable

            val person = if (ongoing) null else people.firstOrNull { name -> senders(n, title).any { it.contains(name, ignoreCase = true) } }
            val (kind, id, who) = when {
                person != null -> Triple(Lamp.PERSON, "person:${person.lowercase()}", person)
                pkg in arriving -> Triple(Lamp.ARRIVING, "kind:arriving", label(c, pkg))
                ongoing -> continue
                pkg in work -> Triple(Lamp.WORK, "kind:work", label(c, pkg))
                family && pkg.startsWith("com.cerniera.") -> Triple(Lamp.FAMILY, "kind:family", label(c, pkg))
                else -> continue
            }
            val line = when {
                kind == Lamp.PERSON -> lastMessage(n) ?: text.ifBlank { title }
                text.isBlank() -> title
                title.isBlank() -> text
                else -> "$title · $text"
            }.replace('\n', ' ').trim()
            val before = byId[id]
            byId[id] = Lit(id, kind, if (kind == Lamp.PERSON) who else if (before != null && before.who != who) before.who + " and others" else who,
                line, (before?.keys ?: emptyList()) + sbn.key, pkg, n.contentIntent,
                n.flags and Notification.FLAG_AUTO_CANCEL != 0, sbn.postTime)
        }
        return byId.values.sortedWith(compareBy<Lit> { it.kind.ordinal }.thenBy { it.who.lowercase() })
    }

    /** Everyone a notification could be from: message senders, the people it lists, its title. */
    private fun senders(n: Notification, title: String): List<String> {
        val out = ArrayList<String>()
        val x = n.extras ?: return listOf(title)
        @Suppress("DEPRECATION")
        x.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { p ->
            val b = p as? android.os.Bundle ?: return@forEach
            b.getCharSequence("sender")?.let { out += it.toString() }
            @Suppress("DEPRECATION")
            (b.getParcelable<android.os.Parcelable>("sender_person") as? android.app.Person)?.name?.let { out += it.toString() }
        }
        @Suppress("DEPRECATION")
        x.getParcelableArrayList<android.os.Parcelable>(Notification.EXTRA_PEOPLE_LIST)?.forEach { p ->
            (p as? android.app.Person)?.name?.let { out += it.toString() }
        }
        x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.let { out += it.toString() }
        // Some apps (Teams among them) give only a name in the title: the fallback
        out += title
        return out
    }

    private fun lastMessage(n: Notification): String? {
        @Suppress("DEPRECATION")
        val m = n.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle ?: return null
        return m.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun label(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Opens what the lamp is about, as tapping its notification would. */
    fun open(c: Context, lit: Lit) {
        val sent = lit.intent?.let { pi ->
            runCatching {
                val opts = android.app.ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= 34) opts.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(c, 0, null, null, null, null, opts.toBundle())
            }.isSuccess
        } == true
        if (!sent) c.packageManager.getLaunchIntentForPackage(lit.pkg)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        EventLog.add(c, "Spia: opened ${lit.kind.label.lowercase()} lamp (${lit.who})")
        // Tapped in the shade, these would go; tapped here, they go too
        if (lit.autoCancel) clear(lit, quiet = true)
    }

    /** Puts a lamp out by clearing its notifications, as a swipe in the shade would. */
    fun clear(lit: Lit, quiet: Boolean = false) {
        val l = listener ?: return
        lit.keys.forEach { runCatching { l.cancelNotification(it) } }
        if (!quiet) EventLog.add(l, "Spia: cleared ${lit.kind.label.lowercase()} lamp (${lit.who})")
    }

    fun clearAll() = lamps.forEach { clear(it, quiet = true) }
}

/**
 * The tell-tale cluster on the bar (16 Nov 2026). Lamps stand in columns of up to three, like
 * the small lamps in a binnacle, each a rounded lozenge with its own glow over dark glass. The
 * cluster grows into its place, so the icons beside it ease apart instead of jumping.
 * Concentrato: while an app has the whole screen, the cluster folds down to one pip in the
 * colour of its most urgent lamp, and opens again when the status bar returns.
 */
@SuppressLint("ViewConstructor")
class SpiaView(context: Context, private val sizePx: Int, private val vertical: Boolean) : View(context) {
    private val strip = context.dp(6)
    // 26 Nov 2026: longer along the bar and brighter, so a lamp reads at a glance (they were too faint).
    // Across the bar they keep their size, which the bar's icon size sets.
    private val lampLong = context.dp(17).toFloat()
    private val lampShort = context.dp(8).toFloat()
    private val gapPx = context.dp(4).toFloat()
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, context.resources.displayMetrics.density * 0.75f) }
    private val r = RectF()
    private var lamps: List<Lit> = emptyList()
    private var compact = false
    private var darkGlass = true
    /** 0 to 1: how far the cluster has grown into its place. */
    private var grown = 0f
    private var growAnim: android.animation.ValueAnimator? = null

    fun setLamps(l: List<Lit>, compactNow: Boolean) {
        // A lamp newly lit breathes for a moment; a bar laid out again shows them steady
        if (l.size > lastCount) pulseAt = SystemClock.uptimeMillis()
        lastCount = l.size
        val shape = l.size != lamps.size || compactNow != compact
        lamps = l; compact = compactNow
        contentDescription = if (l.isEmpty()) "No lamps lit" else "Lamps lit: " + l.joinToString { it.who }
        if (shape) requestLayout()
        invalidate()
    }

    fun setGlass(dark: Boolean) { if (darkGlass != dark) { darkGlass = dark; invalidate() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        growAnim?.cancel()
        // Already on the bar before it was laid out again: in place at once, nothing grows
        if (onBar) { grown = 1f; requestLayout(); return }
        onBar = true
        growAnim = android.animation.ValueAnimator.ofFloat(grown, 1f).apply {
            duration = Kit.GLIDE; interpolator = Kit.ease
            addUpdateListener { grown = it.animatedValue as Float; requestLayout(); invalidate() }
            start()
        }
    }

    /** Shrinks back into the trim before the bar lets it go. */
    fun recede(done: () -> Unit) {
        growAnim?.cancel()
        growAnim = android.animation.ValueAnimator.ofFloat(grown, 0f).apply {
            duration = Kit.QUICK; interpolator = Kit.ease
            addUpdateListener { grown = it.animatedValue as Float; requestLayout(); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onBar = false; done() }
            })
            start()
        }
    }

    private fun cols() = if (compact) 1 else ((lamps.size + 2) / 3).coerceAtLeast(1)
    private fun span() = cols() * lampLong + (cols() - 1) * gapPx

    override fun onMeasure(w: Int, h: Int) {
        val across = (span() * grown).toInt().coerceAtLeast(1)
        if (vertical) setMeasuredDimension(sizePx, across) else setMeasuredDimension(across, sizePx + strip)
    }

    override fun onDraw(canvas: Canvas) {
        if (lamps.isEmpty()) return
        canvas.save()
        val alpha = grown
        // The lamps breathe a little when they first light, then hold steady, as a tell-tale does
        val since = (SystemClock.uptimeMillis() - pulseAt) / 1000f
        val pulse = if (since < 1.2f) 0.75f + 0.25f * kotlin.math.sin(since * 10f) else 1f
        if (since < 1.2f) postInvalidateDelayed(33)
        val shown = if (compact) listOf(lamps.first()) else lamps
        val rows = if (compact) 1 else minOf(3, shown.size)
        val stack = rows * lampShort + (rows - 1) * gapPx
        val boxLong = if (vertical) height.toFloat() else width.toFloat()
        val boxShort = if (vertical) width.toFloat() else sizePx.toFloat()
        val startShort = (boxShort - stack) / 2f
        val startLong = (boxLong - span() * grown) / 2f
        shown.forEachIndexed { i, lit ->
            val col = i / 3; val row = i % 3
            val a0 = startLong + col * (lampLong + gapPx) * grown
            val b0 = startShort + row * (lampShort + gapPx)
            if (vertical) r.set(b0, a0, b0 + lampShort, a0 + lampLong * grown)
            else r.set(a0, b0, a0 + lampLong * grown, b0 + lampShort)
            if (r.width() < 1f || r.height() < 1f) return@forEachIndexed
            val rad = minOf(r.width(), r.height()) / 2f
            // A glow only where light can glow: over dark glass
            if (darkGlass) {
                // Two layers: a wide soft bloom and a tight bright one, as a lit lens throws light
                val wide = context.dp(6).toFloat(); val tight = context.dp(2.5f).toFloat()
                glow.color = (lit.kind.colour and 0x00FFFFFF) or ((0x40 * alpha * pulse).toInt() shl 24)
                canvas.drawRoundRect(RectF(r).apply { inset(-wide, -wide) }, rad + wide, rad + wide, glow)
                glow.color = (lit.kind.colour and 0x00FFFFFF) or ((0x99 * alpha * pulse).toInt() shl 24)
                canvas.drawRoundRect(RectF(r).apply { inset(-tight, -tight) }, rad + tight, rad + tight, glow)
            }
            core.color = lit.kind.colour
            core.alpha = (255 * alpha * (if (darkGlass) pulse else 1f)).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(r, rad, rad, core)
            // Over light glass there is no glow, so a deeper rim of the lamp's own colour holds its edge
            if (!darkGlass) {
                edge.color = darken(lit.kind.colour, 0.4f); edge.alpha = (180 * alpha).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(r, rad, rad, edge)
            }
            // A fine bright line on the lozenge's upper edge, the lens catching the light
            core.color = 0xFFFFFFFF.toInt(); core.alpha = (110 * alpha).toInt()
            val hl = RectF(r.left + rad * 0.6f, r.top + r.height() * 0.18f, r.right - rad * 0.6f, r.top + r.height() * 0.36f)
            if (hl.width() > 1f) canvas.drawRoundRect(hl, hl.height() / 2, hl.height() / 2, core)
        }
        canvas.restore()
    }

    /** [c] taken [k] of the way to black. */
    private fun darken(c: Int, k: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xFF) * (1f - k)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** Whether the cluster is on the bar, across the bar being laid out again. */
        private var onBar = false
        private var lastCount = 0
        private var pulseAt = 0L
    }
}
