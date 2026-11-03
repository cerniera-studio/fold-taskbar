package com.fergusonconsultancy.foldtaskbar

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
 * Now playing on the bar: the artwork as a tile the size of an app icon, with a fine
 * waveform along its foot. The bars move while the music plays and settle flat when it
 * pauses: the playing state is what moves them (the bar cannot hear the audio itself).
 */
@SuppressLint("ViewConstructor")
class LiveView(context: Context, private val sizePx: Int) : View(context) {
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private var ink = Kit.INK
    private var art: Bitmap? = null
    private var fallback: android.graphics.drawable.Drawable? = null
    private var playing = false
    private var level = 0f
    private var startedAt = SystemClock.uptimeMillis()
    private val r = RectF()

    fun setTrack(t: Track, icon: android.graphics.drawable.Drawable?) {
        if (art !== t.art) {
            art = t.art
            artPaint.shader = t.art?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        }
        fallback = icon
        contentDescription = if (t.artist.isBlank()) t.title else "${t.title}, ${t.artist}"
        if (playing != t.playing) { playing = t.playing; postInvalidateOnAnimation() }
        invalidate()
    }

    fun setInk(c: Int) { ink = c; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizePx, sizePx)

    override fun onDraw(canvas: Canvas) {
        val s = sizePx.toFloat()
        r.set(0f, 0f, s, s)
        val corner = s * 0.24f
        val a = art
        if (a != null) {
            // Centre-crop the artwork into the tile
            val k = maxOf(s / a.width, s / a.height)
            artPaint.shader?.setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate((s - a.width * k) / 2, (s - a.height * k) / 2) })
            canvas.drawRoundRect(r, corner, corner, artPaint)
        } else {
            plate.color = 0xFF1D2621.toInt()
            canvas.drawRoundRect(r, corner, corner, plate)
            fallback?.let { d -> val m = (s * 0.2f).toInt(); d.setBounds(m, m, sizePx - m, sizePx - m); d.draw(canvas) }
        }
        // A shade along the foot so the waveform reads over any artwork
        scrim.shader = android.graphics.LinearGradient(0f, s * 0.55f, 0f, s, 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
        canvas.save(); canvas.clipRect(0f, s * 0.55f, s, s)
        canvas.drawRoundRect(r, corner, corner, scrim)
        canvas.restore()

        // Eases towards moving while playing, towards flat when paused
        level += ((if (playing) 1f else 0f) - level) * 0.12f
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val bars = 5
        val bw = s * 0.07f
        val span = s * 0.5f
        val base = s * 0.86f
        wave.strokeWidth = bw
        wave.color = 0xFFF6F1E6.toInt()
        for (i in 0 until bars) {
            val x = s / 2 - span / 2 + span * i / (bars - 1)
            val phase = sin(t * (5.1f + i * 1.7f) + i * 1.3f) * 0.5f + 0.5f
            val hgt = bw + (s * 0.2f) * level * (0.35f + 0.65f * phase)
            canvas.drawLine(x, base, x, base - hgt + bw, wave)
        }
        if (playing || level > 0.01f) postInvalidateOnAnimation()
    }
}

/**
 * The time and the battery, drawn only while an app has hidden Android's status bar. The time
 * in Jost above a fine ring that fills with the charge: the zone's ink, champagne while
 * charging, Alfa red below 15%.
 */
@SuppressLint("ViewConstructor")
class StatusView(context: Context, private val sizePx: Int) : View(context) {
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Kit.sans(context, 600); textAlign = Paint.Align.CENTER
        textSize = context.resources.displayMetrics.scaledDensity * 12.5f
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var ink = Kit.INK
    private var level = -1
    private var charging = false
    private val fmt = android.text.format.DateFormat.getTimeFormat(context)
    private val r = RectF()

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    level = if (l < 0) -1 else l * 100 / sc.coerceAtLeast(1)
                    val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                }
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

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizePx, sizePx)

    override fun onDraw(canvas: Canvas) {
        val s = sizePx.toFloat()
        timePaint.color = ink
        // Fit the time to the tile at any icon size
        val time = fmt.format(java.util.Date()).replace(" ", " ")
        timePaint.textSize = context.resources.displayMetrics.scaledDensity * 12.5f
        val w = timePaint.measureText(time)
        if (w > s * 0.94f) timePaint.textSize *= s * 0.94f / w
        canvas.drawText(time, s / 2, s * 0.40f, timePaint)
        val stroke = s * 0.055f
        val d = s * 0.34f
        r.set(s / 2 - d / 2, s * 0.56f, s / 2 + d / 2, s * 0.56f + d)
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        track.color = (ink and 0x00FFFFFF) or 0x40000000
        canvas.drawOval(r, track)
        if (level >= 0) {
            arc.color = when { charging -> Kit.CHAMPAGNE; level < 15 -> Kit.ROSSO; else -> ink }
            canvas.drawArc(r, -90f, 360f * level / 100f, false, arc)
        }
    }
}
