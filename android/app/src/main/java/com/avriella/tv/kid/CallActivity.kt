package com.avriella.tv.kid

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.avriella.tv.cam.Call

/**
 * Full-screen video call with a parent, on top of whatever she was doing. Touch is ignored so she
 * can't hang up by accident; the parent ends the call from the iPhone. When it closes, the screen
 * underneath (her show, game or sleep sounds) comes back and carries on.
 */
class CallActivity : Activity() {
    private lateinit var web: WebView
    private val audio get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "Phone")
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // only our own call page asks; it needs the camera and microphone
                    if (request.origin?.host == "avriella.call") request.grant(request.resources) else request.deny()
                }
            }
        }
        setContentView(web)
        val html = assets.open("call.html").use { it.readBytes().toString(Charsets.UTF_8) }
        // an https base address makes the page a "secure context", which video calls require
        web.loadDataWithBaseURL("https://avriella.call/", html, "text/html", "utf-8", null)
        speakerOn()
    }

    /** Calls should come out of the loudspeaker, not the earpiece. */
    private fun speakerOn() {
        oldMode = audio.mode
        @Suppress("DEPRECATION") run { oldSpeaker = audio.isSpeakerphoneOn }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { audio.setCommunicationDevice(it) }
        } else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = true }
    }

    private fun speakerRestore() {
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = oldSpeaker }
        audio.mode = oldMode
    }

    inner class Bridge {
        @JavascriptInterface fun offer(): String = Call.offer
        @JavascriptInterface fun answer(sdp: String) { Call.answer = sdp }
        @JavascriptInterface fun state(): String = Call.state
        @JavascriptInterface fun connected() { Call.connected() }
        @JavascriptInterface fun hangup() { Call.end(); runOnUiThread { finish() } }
        @JavascriptInterface fun log(s: String) { android.util.Log.i("AvriellaCall", s) }
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // she can look and listen, but not hang up by tapping around
    override fun dispatchTouchEvent(ev: MotionEvent) = true
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = event.keyCode
        return if (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_VOLUME_DOWN) super.dispatchKeyEvent(event) else true
    }

    override fun onDestroy() {
        if (Call.state != "ended") Call.end()
        speakerRestore()
        try { web.loadUrl("about:blank"); web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
