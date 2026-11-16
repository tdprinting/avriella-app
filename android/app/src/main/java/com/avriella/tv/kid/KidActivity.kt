package com.avriella.tv.kid

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.avriella.tv.cam.CamService
import com.avriella.tv.cam.CamState
import com.avriella.tv.cam.Remote
import com.avriella.tv.data.Library
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.chips
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.rounded
import com.avriella.tv.ui.vbox

/**
 * Everything she sees runs inside a KidActivity:
 *  - the app is pinned / kiosk-locked so Home, Recents and notifications can't be reached
 *  - Back, Menu and every other key are swallowed
 *  - touches can be fully blocked (videos) or allowed (games)
 *  - holding BOTH volume buttons for ~1 second opens the Parent controls panel
 */
abstract class KidActivity : Activity() {
    protected lateinit var root: FrameLayout
    private lateinit var sleepy: SleepyView
    private lateinit var hint: TextView
    private lateinit var panel: FrameLayout
    private lateinit var panelBody: LinearLayout
    private lateinit var countdown: TextView
    protected val ui = Handler(Looper.getMainLooper())
    protected var touchLocked = true
    protected var panelOpen = false
        private set
    protected var sleeping = false
        private set
    private var volUp = false
    private var volDown = false
    private var comboArmed = false
    private var panelLeft = 0
    private var lastHint = 0L
    private var sleepAt = 0L
    private var sleepMin = 0
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val comboFire = Runnable { comboArmed = false; if (volUp && volDown && !panelOpen) openPanel() }
    private val panelTick = object : Runnable {
        override fun run() {
            if (!panelOpen) return
            panelLeft--
            if (panelLeft <= 0) { closePanel(); return }
            countdown.text = "Locks again in ${panelLeft}s"
            onPanelTick()
            ui.postDelayed(this, 1000)
        }
    }
    private val sleepCheck = object : Runnable {
        override fun run() {
            if (sleepAt == 0L) return
            if (System.currentTimeMillis() >= sleepAt) { sleepAt = 0; sleepMin = 0; CamState.sleepAt = 0; goToSleep("Night night") }
            else ui.postDelayed(this, 5000)
        }
    }

    abstract fun createContent(): View
    open fun initialTouchLock() = true
    open fun buildPanelExtras(box: LinearLayout) {}
    open fun onPanelTick() {}
    open fun onSleep() {}
    open fun onWake() {}
    open fun camMode(): String = "watching"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        root.addView(createContent(), FrameLayout.LayoutParams(MATCH, MATCH))

        sleepy = SleepyView(this).apply { visibility = View.GONE }
        root.addView(sleepy, FrameLayout.LayoutParams(MATCH, MATCH))

        hint = label("🔒  Hold both volume buttons to unlock", 13f, Color.WHITE, true).apply {
            background = rounded(0x99000000.toInt(), dpf(16f))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            alpha = 0f
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(14), dp(14), 0) })

        buildPanelShell()
        touchLocked = initialTouchLock()
        applyBrightness()
        if (Library.settings.sleepDefaultMin > 0) setSleep(Library.settings.sleepDefaultMin)
        startCam()
        Remote.handler = kidRemote
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        KidLock.enter(this)
        CamState.mode = if (sleeping) "sleeping" else camMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* swallowed on purpose */ }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val down = e.action == KeyEvent.ACTION_DOWN
            if (code == KeyEvent.KEYCODE_VOLUME_UP) volUp = down else volDown = down
            if (volUp && volDown) {
                if (!comboArmed && !panelOpen) { comboArmed = true; ui.postDelayed(comboFire, 800) }
            } else {
                if (comboArmed) { ui.removeCallbacks(comboFire); comboArmed = false }
                if (panelOpen && down && e.repeatCount == 0) {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0,
                    )
                    panelLeft = Library.settings.relockSec
                    rebuildPanel()
                }
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (panelOpen && e.action == KeyEvent.ACTION_UP) closePanel()
            return true
        }
        return true // every other key is ignored while she's in charge
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (panelOpen) {
            panelLeft = Library.settings.relockSec
            return super.dispatchTouchEvent(ev)
        }
        if (touchLocked || sleeping) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) flashHint()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun flashHint() {
        if (!Library.settings.lockHint) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHint < 6000) return
        lastHint = now
        hint.animate().cancel()
        hint.alpha = 0f
        hint.animate().alpha(1f).setDuration(250).withEndAction {
            hint.animate().alpha(0f).setStartDelay(1800).setDuration(500).start()
        }.start()
    }

    @Suppress("DEPRECATION")
    protected fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ---------------- brightness / sleep ----------------
    private fun applyBrightness(override: Float? = null) {
        val v = override ?: when (Library.settings.brightness) { 1 -> 1f; 2 -> 0.35f; 3 -> 0.08f; else -> -1f }
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    private fun setSleep(min: Int) {
        sleepMin = min
        stopAfterCurrent = false
        ui.removeCallbacks(sleepCheck)
        if (min <= 0) { sleepAt = 0; CamState.sleepAt = 0; return }
        sleepAt = System.currentTimeMillis() + min * 60_000L
        CamState.sleepAt = sleepAt
        ui.postDelayed(sleepCheck, 5000)
    }

    protected fun goToSleep(title: String) {
        if (sleeping) return
        sleeping = true
        sleepy.title = title
        sleepy.subtitle = Library.settings.childName
        sleepy.alpha = 0f
        sleepy.visibility = View.VISIBLE
        sleepy.invalidate()
        sleepy.animate().alpha(1f).setDuration(2500).start()
        applyBrightness(0.05f)
        CamState.mode = "sleeping"
        onSleep()
    }

    protected fun wake() {
        if (!sleeping) return
        sleeping = false
        sleepy.visibility = View.GONE
        applyBrightness()
        CamState.mode = camMode()
        onWake()
    }

    // ---------------- parent panel ----------------
    private fun buildPanelShell() {
        panel = FrameLayout(this).apply {
            setBackgroundColor(0xD9101225.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val scroll = ScrollView(this).apply { isFillViewport = false }
        panelBody = vbox().apply {
            background = rounded(0xFF22243F.toInt(), dpf(26f))
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }
        val w = minOf(resources.displayMetrics.widthPixels - dp(32), dp(640))
        scroll.addView(panelBody, FrameLayout.LayoutParams(w, WRAP, Gravity.CENTER_HORIZONTAL).apply { setMargins(0, dp(16), 0, dp(16)) })
        panel.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    protected fun openPanel() {
        panelOpen = true
        panelLeft = Library.settings.relockSec
        rebuildPanel()
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).setDuration(180).start()
        ui.removeCallbacks(panelTick)
        ui.postDelayed(panelTick, 1000)
    }

    protected fun closePanel() {
        panelOpen = false
        ui.removeCallbacks(panelTick)
        panel.visibility = View.GONE
        hideBars()
    }

    protected fun rebuildPanel() {
        val b = panelBody
        b.removeAllViews()
        val head = hbox()
        head.addView(label("👋  Parent controls", 20f, Color.WHITE, true), lp(0, WRAP, 1f))
        countdown = label("Locks again in ${panelLeft}s", 13f, 0xFFB9B8D6.toInt())
        head.addView(countdown, lp(WRAP, WRAP))
        b.addView(head, lp())

        if (sleeping) {
            b.addView(pill("☀️  Wake up & keep going", Pal.ORANGE) { wake(); closePanel() }, lp(MATCH, WRAP, t = dp(14)))
        }

        val extras = vbox()
        buildPanelExtras(extras)
        if (extras.childCount > 0) b.addView(extras, lp(MATCH, WRAP, t = dp(12)))

        // volume
        b.addView(sectionLabel("Volume  (volume buttons work here too)"))
        val vol = hbox()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        vol.addView(roundBtn("−") { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); rebuildPanel() })
        vol.addView(label("🔊  $cur / $max", 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
        vol.addView(roundBtn("+") { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); rebuildPanel() })
        b.addView(vol, lp())

        b.addView(sectionLabel("Screen brightness"))
        b.addView(chips(listOf("Auto", "Bright", "Dim", "Night"), Library.settings.brightness, dark = true) {
            Library.settings.brightness = it; Library.save(); if (!sleeping) applyBrightness()
        })

        val sleepOpts = listOf(0, 15, 30, 45, 60, 90)
        val remaining = if (sleepAt > 0) " — stops in ${((sleepAt - System.currentTimeMillis()) / 60000 + 1)} min" else ""
        b.addView(sectionLabel("Sleep timer$remaining"))
        b.addView(chips(sleepOpts.map { if (it == 0) "Off" else "$it min" }, sleepOpts.indexOf(sleepMin).coerceAtLeast(0), dark = true) {
            setSleep(sleepOpts[it]); rebuildPanel()
        })

        b.addView(sectionLabel("Touch screen"))
        b.addView(chips(listOf("🔒 Locked", "👆 She can touch"), if (touchLocked) 0 else 1, dark = true) {
            touchLocked = it == 0
        })

        if (!KidLock.isFullLock(this)) {
            b.addView(label("⚠️ Basic lock only. Set up Full Lock in the app's Kid Lock screen so Home & notifications are blocked too.", 13f, 0xFFFFC98B.toInt()).apply {
                setPadding(0, dp(14), 0, 0)
            })
        }

        val actions = hbox()
        actions.addView(pill("🔒  Lock & give back", Pal.GREEN) { closePanel() }, lp(0, WRAP, 1f, r = dp(6)))
        actions.addView(pill("🏠  Exit to menu", 0x33FFFFFF) { exitKidMode() }, lp(0, WRAP, 1f, l = dp(6)))
        b.addView(actions, lp(MATCH, WRAP, t = dp(18)))
    }

    protected fun sectionLabel(t: String) = label(t, 13f, 0xFFB9B8D6.toInt(), true).apply { setPadding(0, dp(14), 0, dp(6)) }

    protected fun roundBtn(t: String, sp: Float = 22f, onClick: () -> Unit): TextView =
        label(t, sp, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0x33FFFFFF, dpf(28f))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { onClick() }
        }

    protected fun exitKidMode() {
        onExitKidMode()
        stopCam()
        KidLock.exit(this)
        finish()
    }

    open fun onExitKidMode() {}

    /** "Stop after the video that's playing now" (set from the iPhone). */
    protected var stopAfterCurrent = false
        set(v) { field = v; CamState.stopAfter = v }

    /** Shared iPhone commands: brightness and sleep. Everything else goes to the screen itself. */
    private val kidRemote: (Remote.Cmd) -> String = { c ->
        when (c.action) {
            "brightness" -> {
                val b = c.value?.toIntOrNull()
                if (b == null) "Unknown brightness" else {
                    Library.settings.brightness = b.coerceIn(0, 3); Library.save()
                    if (!sleeping) applyBrightness()
                    if (panelOpen) rebuildPanel()
                    "ok"
                }
            }
            "sleep" -> {
                when (c.value) {
                    "now" -> { setSleep(0); goToSleep("Night night") }
                    "end" -> { setSleep(0); stopAfterCurrent = true }
                    else -> setSleep(c.value?.toIntOrNull() ?: 0)
                }
                if (panelOpen) rebuildPanel()
                "ok"
            }
            "wake" -> { wake(); if (panelOpen) rebuildPanel(); "ok" }
            else -> onRemote(c)
        }
    }

    /** Screen-specific iPhone commands (play a video, pause, next...). */
    open fun onRemote(c: Remote.Cmd): String = "Not available right now."

    // ---------------- baby cam ----------------
    private fun startCam() {
        if (!Library.settings.camEnabled) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            val i = Intent(this, CamService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun stopCam() {
        CamState.mode = "menu"
        CamState.nowPlaying = ""
        CamState.sleepAt = 0
        CamState.stopAfter = false
        // the cam keeps running in the menu only if the parents want "always on"; default: stop.
        stopService(Intent(this, CamService::class.java))
    }

    override fun onDestroy() {
        if (Remote.handler === kidRemote) Remote.handler = null
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
