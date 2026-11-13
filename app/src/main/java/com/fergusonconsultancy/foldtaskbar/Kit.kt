package com.fergusonconsultancy.foldtaskbar

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.cerniera.kit.Fold
import com.cerniera.kit.Kit
import com.cerniera.kit.px
import com.cerniera.kit.text

/*
 * The settings kit now lives in the Kit (cerniera-studio/kit), the family's shared design system
 * (12 Nov 2026): palette, type, controls, the doctrine and crash reports. This file keeps the one
 * piece only the taskbar uses.
 */

/** A titled block of notes inside a fold, for the design notes on each choice. */
fun Context.notesCard(title: String, sub: String, notes: List<Pair<String, String>>): View = Fold(this, title, sub).apply {
    val ctx = this@notesCard
    notes.forEachIndexed { i, (name, text) ->
        body.addView(ctx.text(name, 15f, Kit.INK, 500), LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = ctx.px(16) })
        body.addView(ctx.text(text, 13.5f, Kit.MUTED, 400), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(3) })
    }
}
