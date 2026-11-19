package com.fergusonconsultancy.foldtaskbar

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Fluido (18 Nov 2026): Nodo's knots that wait for an app ("next time I open Spotify, add the
 * album"). The bar already sees the app in front; Nodo lists the apps its knots wait for (packages
 * only, never the words), and the bar tells Nodo when one of those comes to the front. Nothing is
 * sent for any other app, and nothing at all without Nodo installed.
 */
class NodoCues(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var waiting: Set<String> = emptySet()
    private var last: String? = null

    private val observer = object : ContentObserver(main) { override fun onChange(self: Boolean) = read() }

    fun start() {
        runCatching { app.contentResolver.registerContentObserver(URI, true, observer) }
        read()
    }

    fun stop() { runCatching { app.contentResolver.unregisterContentObserver(observer) } }

    private fun read() = io.execute {
        waiting = runCatching {
            app.contentResolver.query(URI, null, null, null, null)?.use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
        }.getOrNull() ?: emptySet()
    }

    /** The app in front, as the bar sees it. Tells Nodo once each time a waited-for app comes forward. */
    fun front(pkg: String) {
        if (pkg == last) return
        last = pkg
        if (pkg !in waiting) return
        runCatching { app.sendBroadcast(Intent(ACTION).setPackage(NODO).putExtra("app", pkg)) }
        EventLog.add(app, "Told Nodo: ${pkg.substringAfterLast('.')} opened")
    }

    companion object {
        const val NODO = "com.cerniera.nodo"
        const val ACTION = "com.cerniera.nodo.CUE"
        val URI: Uri = Uri.parse("content://com.cerniera.nodo.cues/apps")
    }
}
