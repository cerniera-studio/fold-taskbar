package com.fergusonconsultancy.foldtaskbar

import com.cerniera.kit.*
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.View
import kotlin.math.sin

// ---- Live (3 Nov 2026) ------------------------------------------------------------------
// Two ideas taken from the iPhone Duo's status column, and only where a need puts them:
//  - Now playing: the music that is playing lives on the bar, a new ability, not a copy.
//  - Status in full screen: when an app hides Android's status bar (a video, a game, a reader),
//    the time goes with it. Only then does the bar carry the time and the battery.
// Samsung's own status bar cannot be moved or hidden by an app, so it is never duplicated.

/** Holds Android's permission to see which music is playing. It does nothing else. */
class MediaListener : NotificationListenerService() {
    companion object {
        fun component(c: Context) = ComponentName(c, MediaListener::class.java)
        fun granted(c: Context): Boolean =
            android.provider.Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component(c) } == true
        fun ask(c: Context) = c.startActivity(
            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** What is playing now, read from Android's media sessions. */
data class Track(val pkg: String, val title: String, val artist: String, val art: Bitmap?, val playing: Boolean)

/**
 * Follows the active media sessions and reports the one worth showing: the session playing,
 * or the one paused most recently, for [PAUSED_KEEP_MS] after it paused. Main thread only.
 */
class NowPlaying(private val c: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val msm = c.getSystemService(MediaSessionManager::class.java)
    private var controllers: List<MediaController> = emptyList()
    private val pausedAt = HashMap<String, Long>()
    private val wasPlaying = HashSet<String>()
    var track: Track? = null; private set
    var controller: MediaController? = null; private set
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { main.post { pick() } }
        override fun onMetadataChanged(metadata: MediaMetadata?) { main.post { pick() } }
        override fun onSessionDestroyed() { main.post { refresh() } }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { main.post { refresh() } }
    private val expire = Runnable { pick() }

    fun start() {
        if (started || !MediaListener.granted(c)) return
        runCatching { msm.addOnActiveSessionsChangedListener(sessions, MediaListener.component(c), main) }
            .onSuccess { started = true; refresh() }
    }

    fun stop() {
        if (started) runCatching { msm.removeOnActiveSessionsChangedListener(sessions) }
        started = false
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = emptyList()
        set(null, null)
    }

    private fun refresh() {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = runCatching { msm.getActiveSessions(MediaListener.component(c)) }.getOrDefault(emptyList())
        controllers.forEach { runCatching { it.registerCallback(callback, main) } }
        pick()
    }

    private fun pick() {
        main.removeCallbacks(expire)
        val now = SystemClock.uptimeMillis()
        fun playing(m: MediaController) = m.playbackState?.state.let {
            it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING
        }
        // A pause is only counted when we saw it play: yesterday's paused session never returns
        controllers.forEach { m ->
            if (playing(m)) { wasPlaying += m.packageName; pausedAt.remove(m.packageName) }
            else if (wasPlaying.remove(m.packageName)) pausedAt[m.packageName] = now
        }
        val pick = controllers.firstOrNull { playing(it) }
            ?: controllers.filter { m -> m.metadata != null && pausedAt[m.packageName]?.let { now - it < PAUSED_KEEP_MS } == true }
                .maxByOrNull { pausedAt[it.packageName] ?: 0L }
        val md = pick?.metadata
        if (pick == null || md == null) { set(null, null); return }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) { set(null, null); return }
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val t = Track(pick.packageName, title, artist, art, playing(pick))
        if (!t.playing) main.postDelayed(expire, PAUSED_KEEP_MS - (now - (pausedAt[pick.packageName] ?: now)) + 50)
        set(t, pick)
    }

    private fun set(t: Track?, m: MediaController?) {
        controller = m
        val same = t?.pkg == track?.pkg && t?.title == track?.title && t?.playing == track?.playing && t?.art === track?.art
        track = t
        if (!same) changed()
    }

    fun toggle() = controller?.transportControls?.let { if (track?.playing == true) it.pause() else it.play() }
    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

    /** Opens the player: the screen its session asks for, else the app itself. */
    fun open(): Boolean {
        val m = controller ?: return false
        m.sessionActivity?.let { pi -> if (runCatching { pi.send() }.isSuccess) return true }
        val launch = c.packageManager.getLaunchIntentForPackage(m.packageName) ?: return false
        c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    companion object {
        /** A paused track stays on the bar this long, so a pause is not a disappearance. */
        const val PAUSED_KEEP_MS = 10 * 60_000L
    }
}


/**
 * Now playing on the bar (revised 3 Nov 2026). The first version was a square of artwork with
 * a waveform printed over it, a little lower than the icons and a different shape from them:
 * an afterthought. Now it is built exactly like an app icon:
 *  - the same frame (icon above, the 6dp strip for its light below), so it sits in line;
 *  - the artwork cut to the icon pack's own silhouette and overlay, so it is one of the family;
 *  - the waveform is the tile's light, in the strip where every app keeps its pill, in the
 *    zone's own glow. Playing, the pill becomes five bars that move; paused, they settle back
 *    into a plain pill. The bars follow the playing state, not the sound.
 */
@SuppressLint("ViewConstructor")
class LiveView(context: Context, private val apps: Apps, private val sizePx: Int) : View(context) {
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val strip = context.dp(6)
    private var glow = Kit.CHAMPAGNE
    private var source: Bitmap? = null
    private var dressed: Bitmap? = null
    private var fallback: android.graphics.drawable.Drawable? = null
    private var playing = false
    private var level = 0f
    private val startedAt = SystemClock.uptimeMillis()
    private val r = RectF()

    fun setTrack(t: Track, icon: android.graphics.drawable.Drawable?) {
        if (source !== t.art) {
            source = t.art
            dressed = t.art?.let { apps.frameArt(it) }
        }
        fallback = icon
        contentDescription = if (t.artist.isBlank()) t.title else "${t.title}, ${t.artist}"
        if (playing != t.playing) playing = t.playing
        invalidate()
    }

    fun setGlow(c: Int) { glow = c; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizePx, sizePx + strip)

    override fun onDraw(canvas: Canvas) {
        val s = sizePx.toFloat()
        r.set(0f, 0f, s, s)
        val d = dressed
        val a = source
        when {
            // Dressed by the icon pack: drawn exactly as its icons are
            d != null -> canvas.drawBitmap(d, null, r, artPaint)
            // No pack: a squircle of artwork, the shape One UI gives its own icons
            a != null -> {
                val k = maxOf(s / a.width, s / a.height)
                val shader = BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader.setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate((s - a.width * k) / 2, (s - a.height * k) / 2) })
                artPaint.shader = shader
                canvas.drawRoundRect(r, s * 0.3f, s * 0.3f, artPaint)
                artPaint.shader = null
            }
            // No artwork: the player's own icon
            else -> fallback?.let { f -> f.setBounds(0, 0, sizePx, sizePx); f.draw(canvas) } ?: run {
                plate.color = 0xFF1D2621.toInt(); canvas.drawRoundRect(r, s * 0.3f, s * 0.3f, plate)
            }
        }

        // The light: a pill at rest, five bars while playing
        level += ((if (playing) 1f else 0f) - level) * 0.14f
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val bars = 5
        val bw = context.dp(2.2f).toFloat()
        val span = s * 0.34f
        val mid = s + strip * 0.55f
        wave.strokeWidth = bw
        wave.color = glow
        if (level < 0.02f) {
            // Settled: exactly the pill every app shows beneath it
            wave.strokeWidth = context.dp(3).toFloat()
            canvas.drawLine(s / 2 - span / 2 + bw, mid, s / 2 + span / 2 - bw, mid, wave)
            return
        }
        val gapW = (span - bw) / (bars - 1)
        for (i in 0 until bars) {
            val x = s / 2 - span / 2 + bw / 2 + gapW * i
            val phase = sin(t * (6.3f + i * 1.9f) + i * 1.7f) * 0.5f + 0.5f
            val half = (bw / 2 + (strip * 0.9f) * level * (0.25f + 0.75f * phase)) / 2
            canvas.drawLine(x, mid - half + bw / 2, x, mid + half - bw / 2, wave)
        }
        if (playing || level > 0.02f) postInvalidateDelayed(33)
    }
}

/** How the battery is shown when the bar carries it (3 Nov 2026). */
enum class BatteryStyle(val label: String, val detail: String) {
    ANELLO("Anello", "A fine ring that fills with the charge"),
    FANALE("Fanale", "The Alfa lamp ring: one bead for every 5%, unlit beads keeping an ember. Charging, a single bright head runs round to the charge"),
    LIVELLO("Livello", "Liquid in a round vessel, filled to the charge. Charging, its surface moves"),
    CARBURANTE("Carburante", "A fuel gauge from an Alfa instrument binnacle: E to F, one fine needle"),
}

/**
 * Draws the battery in [style] within a circle of diameter [d] centred at ([cx], [cy]).
 * [t] is seconds, for the charging motion. Colours follow one rule in every style: the zone's
 * ink, champagne while charging, Alfa red below 15%.
 */
object Battery {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private val r = RectF()

    /** Whether a style moves while charging, so its view keeps redrawing. */
    fun moves(style: BatteryStyle) = style == BatteryStyle.FANALE || style == BatteryStyle.LIVELLO

    fun draw(c: Canvas, style: BatteryStyle, cx: Float, cy: Float, d: Float, level: Int, charging: Boolean, ink: Int, t: Float) {
        val lv = level.coerceIn(0, 100)
        val tone = when { charging -> Kit.CHAMPAGNE; lv < 15 -> Kit.ROSSO; else -> ink }
        val faint = (ink and 0x00FFFFFF) or 0x40000000
        p.shader = null; p.strokeCap = Paint.Cap.ROUND
        when (style) {
            BatteryStyle.ANELLO -> {
                val stroke = d * 0.16f
                r.set(cx - d / 2 + stroke / 2, cy - d / 2 + stroke / 2, cx + d / 2 - stroke / 2, cy + d / 2 - stroke / 2)
                p.style = Paint.Style.STROKE; p.strokeWidth = stroke
                p.color = faint; c.drawOval(r, p)
                p.color = tone; c.drawArc(r, -90f, 360f * lv / 100f, false, p)
            }
            BatteryStyle.FANALE -> {
                val beads = 20
                val lit = (lv * beads + 50) / 100
                val ring = d / 2 - d * 0.09f
                val bead = d * 0.075f
                p.style = Paint.Style.FILL
                // The running head while charging: one bright bead, travelling round to the charge
                val head = if (charging && lit > 0) ((t / 1.6f) % 1f * lit).toInt() else -1
                for (i in 0 until beads) {
                    val ang = Math.toRadians(-90.0 + 360.0 * i / beads)
                    val x = cx + ring * kotlin.math.cos(ang).toFloat()
                    val y = cy + ring * kotlin.math.sin(ang).toFloat()
                    when {
                        i == head -> {
                            p.color = (Kit.CHAMPAGNE and 0x00FFFFFF) or 0x55000000
                            c.drawCircle(x, y, bead * 2.1f, p)
                            p.color = 0xFFFFF3DA.toInt(); c.drawCircle(x, y, bead * 1.15f, p)
                        }
                        i < lit -> { p.color = tone; c.drawCircle(x, y, bead, p) }
                        // Unlit beads hold an ember, so the ring is never blank
                        else -> { p.color = (ink and 0x00FFFFFF) or 0x2E000000; c.drawCircle(x, y, bead * 0.8f, p) }
                    }
                }
            }
            BatteryStyle.LIVELLO -> {
                val rad = d / 2
                val wall = d * 0.07f
                // The liquid, clipped to the vessel, its surface at the charge
                c.save()
                path.reset(); path.addCircle(cx, cy, rad - wall * 1.4f, android.graphics.Path.Direction.CW)
                c.clipPath(path)
                val inner = rad - wall * 1.4f
                val top = cy + inner - 2 * inner * lv / 100f
                val amp = if (charging) d * 0.035f else d * 0.012f
                val speed = if (charging) 2.4f else 0f
                path.reset()
                path.moveTo(cx - rad, cy + rad)
                var x = cx - rad
                while (x <= cx + rad) {
                    path.lineTo(x, top + amp * sin((x - cx) / d * 9f + t * speed))
                    x += d / 24f
                }
                path.lineTo(cx + rad, cy + rad); path.close()
                p.style = Paint.Style.FILL
                p.color = (tone and 0x00FFFFFF) or 0xE6000000.toInt()
                c.drawPath(path, p)
                c.restore()
                // The vessel: a hairline, light catching its upper left
                p.style = Paint.Style.STROKE; p.strokeWidth = wall
                p.color = faint; c.drawCircle(cx, cy, rad - wall / 2, p)
                r.set(cx - rad + wall / 2, cy - rad + wall / 2, cx + rad - wall / 2, cy + rad - wall / 2)
                p.color = (ink and 0x00FFFFFF) or 0x8C000000.toInt(); c.drawArc(r, 200f, 60f, false, p)
            }
            BatteryStyle.CARBURANTE -> {
                // 240 degrees from E (lower left) to F (lower right), as on a binnacle dial
                val start = 150f; val sweep = 240f
                val rad = d / 2
                p.style = Paint.Style.STROKE; p.strokeWidth = d * 0.05f
                r.set(cx - rad * 0.86f, cy - rad * 0.86f, cx + rad * 0.86f, cy + rad * 0.86f)
                p.color = faint; c.drawArc(r, start, sweep, false, p)
                // Ticks: E, half and F long; quarters short. The last stretch before E in red
                for (k in 0..4) {
                    val a = Math.toRadians((start + sweep * k / 4).toDouble())
                    val long = k % 2 == 0
                    val r0 = rad * (if (long) 0.62f else 0.72f); val r1 = rad * 0.96f
                    p.strokeWidth = d * (if (long) 0.07f else 0.05f)
                    p.color = if (k == 0) Kit.ROSSO else (ink and 0x00FFFFFF) or 0xB3000000.toInt()
                    c.drawLine(cx + r0 * kotlin.math.cos(a).toFloat(), cy + r0 * kotlin.math.sin(a).toFloat(),
                        cx + r1 * kotlin.math.cos(a).toFloat(), cy + r1 * kotlin.math.sin(a).toFloat(), p)
                }
                // The needle, and its hub
                val a = Math.toRadians((start + sweep * lv / 100f).toDouble())
                p.strokeWidth = d * 0.075f; p.color = tone
                c.drawLine(cx - rad * 0.14f * kotlin.math.cos(a).toFloat(), cy - rad * 0.14f * kotlin.math.sin(a).toFloat(),
                    cx + rad * 0.84f * kotlin.math.cos(a).toFloat(), cy + rad * 0.84f * kotlin.math.sin(a).toFloat(), p)
                p.style = Paint.Style.FILL; c.drawCircle(cx, cy, d * 0.1f, p)
            }
        }
    }
}

/**
 * The time and the battery, drawn only while an app has hidden Android's status bar: the time
 * in Jost above the battery, in the style chosen in Settings. Built in the same frame as an
 * app icon, so it sits in line with them.
 */
@SuppressLint("ViewConstructor")
class StatusView(context: Context, private val sizePx: Int) : View(context) {
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Kit.sans(context, 600); textAlign = Paint.Align.CENTER
    }
    private val strip = context.dp(6)
    private var ink = Kit.INK
    private var level = -1
    private var charging = false
    private val fmt = android.text.format.DateFormat.getTimeFormat(context)
    private val startedAt = SystemClock.uptimeMillis()

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_BATTERY_CHANGED) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                level = if (l < 0) -1 else l * 100 / sc.coerceAtLeast(1)
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
            describe(); invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
        val sticky = if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, f)
        sticky?.let { receiver.onReceive(context, it) }
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        super.onDetachedFromWindow()
    }

    fun setInk(c: Int) { ink = c; invalidate() }

    private fun describe() {
        contentDescription = fmt.format(java.util.Date()) + (if (level >= 0) ", battery $level percent" + (if (charging) ", charging" else "") else "")
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizePx, sizePx + strip)

    override fun onDraw(canvas: Canvas) {
        val s = sizePx.toFloat()
        val time = fmt.format(java.util.Date()).replace(" ", " ")
        timePaint.color = ink
        @Suppress("DEPRECATION")
        timePaint.textSize = context.resources.displayMetrics.scaledDensity * 12.5f
        val w = timePaint.measureText(time)
        if (w > s * 0.94f) timePaint.textSize *= s * 0.94f / w
        canvas.drawText(time, s / 2, s * 0.34f, timePaint)
        if (level < 0) return
        val style = Prefs.batteryStyle(context)
        val d = s * 0.5f
        Battery.draw(canvas, style, s / 2, s * 0.44f + d / 2 + strip * 0.3f, d, level, charging, ink,
            (SystemClock.uptimeMillis() - startedAt) / 1000f)
        if (charging && Battery.moves(style)) postInvalidateDelayed(50)
    }
}
