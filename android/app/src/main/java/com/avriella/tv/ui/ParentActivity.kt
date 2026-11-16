package com.avriella.tv.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Base for the grown-up screens: a top bar + a scrolling body that is rebuilt on every resume. */
abstract class ParentActivity : Activity() {
    protected lateinit var body: LinearLayout
    protected lateinit var titleView: TextView
    private lateinit var actions: LinearLayout
    private lateinit var scroll: ScrollView
    open val hasBack = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox().apply { setBackgroundColor(Pal.BG) }
        val bar = hbox().apply { setPadding(dp(8), dp(12), dp(12), dp(6)) }
        if (hasBack) {
            bar.addView(label("←", 26f, Pal.INK, true).apply {
                gravity = Gravity.CENTER
                val r = dpf(24f)
                background = pressable(rounded(0, r), r)
                setOnClickListener { finish() }
            }, lp(dp(48), dp(48)))
        }
        titleView = label("", 24f, Pal.INK, true, 1).apply { setPadding(dp(if (hasBack) 4 else 12), 0, dp(8), 0) }
        bar.addView(titleView, lp(0, WRAP, 1f))
        actions = hbox()
        bar.addView(actions, lp(WRAP, WRAP))
        root.addView(bar, lp())
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = vbox().apply { setPadding(dp(16), dp(4), dp(16), dp(40)) }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)
    }

    fun addAction(text: String, onClick: () -> Unit) {
        actions.addView(outlinePill(text, Pal.INK, 14f, onClick), lp(WRAP, WRAP, l = dp(6)))
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    fun rebuild() {
        val y = scroll.scrollY
        body.removeAllViews()
        build()
        scroll.post { scroll.scrollTo(0, y) }
    }

    abstract fun build()
}
