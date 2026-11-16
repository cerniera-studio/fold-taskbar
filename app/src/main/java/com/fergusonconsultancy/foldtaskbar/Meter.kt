package com.fergusonconsultancy.foldtaskbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

// ---- Riserva's meter (16 Nov 2026) -----------------------------------------------------------
// Riserva works out hours of battery left on each screen from how this phone is actually used.
// That needs to know, all day, which screen is on and how much charge is left, and only a
// process that is always running can hear the screen go on and off. The bar is always running,
// so it reads the meter and hands each reading to Riserva. It sends nothing anywhere else, and
// does nothing at all unless Riserva is installed.

class Meter(private val c: Context, private val isMain: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val bm = c.getSystemService(BatteryManager::class.java)
    private val pm = c.getSystemService(PowerManager::class.java)
    private var started = false
    private var lastState: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) { soon(150) }
    }
    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        // A fold or unfold: read once the screen has settled
        override fun onDisplayChanged(id: Int) = soon(600)
    }
    private val tick = object : Runnable {
        override fun run() {
            sample(force = true)
            main.removeCallbacks(this)
            if (pm.isInteractive) main.postDelayed(this, TICK_MS)
        }
    }
    private val check = Runnable { sample(force = false) }

    /** Starts or stops reading, as Riserva's presence and the setting allow. Safe to call again. */
    fun start() {
        val want = Prefs.riservaMeter(c) && installed()
        if (want == started) return
        if (!want) { stop(); return }
        started = true
        c.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        c.getSystemService(DisplayManager::class.java).registerDisplayListener(displays, main)
        main.post(tick)
        EventLog.add(c, "Riserva: the bar is reading the battery for Riserva")
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { c.unregisterReceiver(receiver) }
        runCatching { c.getSystemService(DisplayManager::class.java).unregisterDisplayListener(displays) }
        main.removeCallbacks(tick); main.removeCallbacks(check)
    }

    private fun soon(ms: Long) { main.removeCallbacks(check); main.postDelayed(check, ms) }

    private fun installed() = runCatching { c.packageManager.getPackageInfo(RISERVA, 0) }.isSuccess

    private fun state() = when { !pm.isInteractive -> "off"; isMain() -> "main"; else -> "cover" }

    /** One reading, sent to Riserva. Unforced readings are sent only when the state changed. */
    private fun sample(force: Boolean) {
        if (!started) return
        val state = state()
        if (!force && state == lastState) return
        // A new on-screen stretch restarts the regular readings
        if (state != "off" && state != lastState) { main.removeCallbacks(tick); main.postDelayed(tick, TICK_MS) }
        lastState = state
        val i = Intent(ACTION).setClassName(RISERVA, "$RISERVA.Reading")
            .putExtra("t", System.currentTimeMillis())
            .putExtra("state", state)
            .putExtra("level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .putExtra("charge", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .putExtra("current", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .putExtra("charging", bm.isCharging)
        runCatching { c.sendBroadcast(i) }
    }

    companion object {
        const val RISERVA = "com.cerniera.riserva"
        const val ACTION = "com.cerniera.riserva.READING"
        /** While a screen is on, a reading this often, so long stretches still show their slope. */
        const val TICK_MS = 5 * 60_000L
    }
}
