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
        // soft pastel wash with a few color blooms behind the cards
        val root = vbox().apply { background = WashDrawable(); clipChildren = false }
        window.statusBarColor = 0xFFFFEAF3.toInt()
        window.navigationBarColor = 0xFFE3F3FF.toInt()
        val bar = hbox().apply { setPadding(dp(14), dp(14), dp(14), dp(10)); clipChildren = false; clipToPadding = false }
        if (hasBack) {
            bar.addView(label("←", 22f, Pal.INK, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                contentDescription = "Back"
                val r = dpf(24f)
                background = pressable(rounded(Pal.CARD, r, dp(1), Pal.LINE), r)
                lift(dpf(2f))
                pressScale(0.92f)
                setOnClickListener { finish() }
            }, lp(dp(46), dp(46), r = dp(8)))
        }
        titleView = label("", 25f, Pal.INK, true, 1).apply { setPadding(dp(if (hasBack) 4 else 6), 0, dp(8), 0) }
        bar.addView(titleView, lp(0, WRAP, 1f))
        actions = hbox()
        bar.addView(actions, lp(WRAP, WRAP))
        // flashlight in the corner of every grown-up screen, for finding a binky in the dark
        if (com.avriella.tv.cam.Torch.available(this)) bar.addView(FlashButton(this), lp(dp(46), dp(46), l = dp(8)))
        root.addView(bar, lp())
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = vbox().apply { setPadding(dp(18), dp(4), dp(18), dp(48)); clipChildren = false; clipToPadding = false }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)
    }

    fun addAction(text: String, onClick: () -> Unit) {
        actions.addView(outlinePill(text, Pal.INK, 14f, onClick), lp(WRAP, WRAP, l = dp(6)))
    }

    private var entered = false

    override fun onResume() {
        super.onResume()
        rebuild()
        if (!entered) {
            // the first time the screen opens, let the content drift up into place
            entered = true
            body.alpha = 0f
            body.translationY = dpf(14f)
            body.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    fun rebuild() {
        val y = scroll.scrollY
        body.removeAllViews()
        build()
        scroll.post { scroll.scrollTo(0, y) }
    }

    abstract fun build()
}
