package com.avriella.tv.cam

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.avriella.tv.data.Library
import com.avriella.tv.kid.CallActivity

/**
 * Video call with a parent. The iPhone sends a WebRTC offer, her phone opens the call screen on
 * top of whatever she's watching (which pauses), answers, and the two phones stream to each other
 * directly. Hanging up closes the call screen and her show / sounds carry on where they were.
 */
object Call {
    @Volatile var id = ""; private set
    @Volatile var offer = ""; private set
    @Volatile var answer = ""
    /** idle · ringing · connected · ended */
    @Volatile var state = "idle"
    private val ui = Handler(Looper.getMainLooper())

    /** Returns "id:<call id>" or a friendly error. */
    fun start(offerJson: String): String {
        if (state == "ringing" || state == "connected") end()
        id = Library.newId()
        offer = offerJson
        answer = ""
        state = "ringing"
        CamState.inCall = true
        FrontCamera.instance?.pauseForCall()
        // wake her phone if it's asleep, then open the call screen on top of everything
        if (!Wake.ready()) Wake.wakeAndWait(5000)
        val ok = Remote.onMain(4000) {
            val a = Wake.top?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && Wake.ready() }
            val ctx = a ?: Library.appCtx
            try {
                ctx.startActivity(Intent(ctx, CallActivity::class.java).apply { if (a == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                true
            } catch (e: Exception) { android.util.Log.w("AvriellaCall", "couldn't open the call screen", e); false }
        } ?: false
        if (!ok) { end(); return "Couldn't open the call on her phone." }
        // give up if nobody connects
        val myId = id
        ui.postDelayed({ if (id == myId && state == "ringing") end() }, 60_000)
        return "id:$id"
    }

    fun connected() { if (state == "ringing") state = "connected" }

    fun end() {
        if (state == "idle" || state == "ended") { state = "ended"; return }
        state = "ended"
        offer = ""
        CamState.inCall = false
        FrontCamera.instance?.resumeAfterCall()
    }
}
