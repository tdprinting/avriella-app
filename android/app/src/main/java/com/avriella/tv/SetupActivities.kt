package com.avriella.tv

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.widget.TextView
import com.avriella.tv.cam.CamService
import com.avriella.tv.cam.CamState
import com.avriella.tv.data.Library
import com.avriella.tv.kid.GameActivity
import com.avriella.tv.kid.KidLock
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.ParentActivity
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.card
import com.avriella.tv.ui.confirm
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.rounded
import com.avriella.tv.ui.section
import com.avriella.tv.ui.toast
import android.widget.Switch
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.vbox

private fun ParentActivity.mono(text: String): TextView = label(text, 14f, Pal.INK, true).apply {
    typeface = Typeface.MONOSPACE
    background = rounded(0xFFF1ECE3.toInt(), dpf(12f))
    setPadding(dp(12), dp(12), dp(12), dp(12))
    setTextIsSelectable(true)
}

private fun ParentActivity.copy(text: String) {
    (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
    toast("Copied")
}

private fun ParentActivity.step(n: Int, text: String) = label("$n.  $text", 15f, Pal.INK).apply { setPadding(0, dp(6), 0, dp(6)) }

/** Explains and manages the two lock levels. */
class LockSetupActivity : ParentActivity() {
    override fun build() {
        titleView.text = "Kid lock"
        val full = KidLock.isFullLock(this)
        val status = card(18, if (full) Pal.SOFT_GREEN else Pal.SOFT_ORANGE)
        status.addView(label(if (full) "🔒  Full lock is ON" else "⚠️  Basic lock is on", 20f, Pal.INK, true))
        status.addView(label(
            if (full) "While a show or game is on, Home, Recents, the notification shade, the status bar and the power menu are all blocked. Touch is blocked during videos. Hold BOTH volume buttons for 1 second to open Parent controls."
            else "Videos still block touch, Back and all buttons, and the app pins itself to the screen. But Android's own \"unpin\" move (holding Back + Recents, or swiping up and holding) can still get out. Set up Full Lock below to block that too.",
            15f, Pal.INK,
        ).apply { setPadding(0, dp(8), 0, 0) })
        body.addView(status, lp(t = dp(6)))

        body.addView(pill("🧪  Try it now (opens Sparkle Sky)", Pal.BLUE) { GameActivity.start(this, "sparkle") }, lp(MATCH, WRAP, t = dp(12)))

        if (full) {
            body.addView(section("Turning it off"))
            body.addView(label("You need to turn Full Lock off before you can uninstall the app.", 14f, Pal.SUB))
            body.addView(pill("Turn off Full Lock", Pal.CARD, Pal.RED) {
                confirm("Turn off Full Lock?", "The app will go back to Basic lock. You can turn Full Lock on again later with the computer.", "Turn off") {
                    toast(if (KidLock.removeFullLock(this)) "Full lock is off" else "Could not turn it off"); rebuild()
                }
            }, lp(MATCH, WRAP, t = dp(8)))
            return
        }

        body.addView(section("Turn on Full Lock (one time, ~5 minutes)"))
        val c = card(16)
        c.addView(label("Android only allows a full kiosk lock for apps set up from a computer. You do this once:", 14f, Pal.SUB))
        c.addView(step(1, "On this phone open Settings → About phone and tap \"Build number\" 7 times to unlock Developer options."))
        c.addView(step(2, "Settings → System → Developer options → turn on \"USB debugging\"."))
        c.addView(step(3, "Settings → Passwords & accounts: remove every account (Google etc.) for now. Android refuses this step while accounts exist. You can add them back right after."))
        c.addView(step(4, "Plug the phone into the computer (allow USB debugging when asked) and run this command:"))
        c.addView(mono(KidLock.ADB_COMMAND), lp(MATCH, WRAP, t = dp(4)))
        c.addView(pill("Copy command", Pal.CARD, Pal.INK) { copy(KidLock.ADB_COMMAND) }, lp(WRAP, WRAP, t = dp(8)))
        c.addView(step(5, "You should see \"Success: Device owner set\". Re-add your Google account, then come back here – it will say Full lock is ON."))
        body.addView(c)

        body.addView(section("Until then: make Basic lock stronger"))
        val b = card(16)
        b.addView(step(1, "Search Settings for \"App pinning\" and turn it on."))
        b.addView(step(2, "Turn on \"Ask for PIN before unpinning\" (or \"Lock device when unpinning\"). Then even if she un-pins, the phone locks."))
        b.addView(step(3, "Use 3-button navigation (Settings → System → Gestures → System navigation). Holding two buttons at once is much harder for a baby than a swipe."))
        body.addView(b)
    }
}

/** Turns the baby cam on/off and shows the address + code for the iPhones. */
class CamSetupActivity : ParentActivity() {
    private val REQ_CAM = 21

    override fun build() {
        titleView.text = "Baby cam"
        val s = Library.settings

        val intro = card(16)
        intro.addView(label("Watch ${s.childName} from your iPhone while she watches her shows. The front camera only turns on while one of you is looking, and only on your home Wi-Fi. Nothing goes to the internet.", 15f, Pal.INK))
        body.addView(intro, lp(t = dp(6)))

        val row = hbox().apply { setPadding(dp(16), dp(14), dp(16), dp(14)); background = rounded(Pal.CARD, dpf(18f)) }
        val col = vbox()
        col.addView(label("Baby cam", 17f, Pal.INK, true))
        col.addView(label("Turns on automatically during shows & games", 13f, Pal.SUB))
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(Switch(this).apply {
            isChecked = s.camEnabled
            setOnCheckedChangeListener { _, on ->
                if (on && !hasCam()) { isChecked = false; askPermissions(); return@setOnCheckedChangeListener }
                s.camEnabled = on; Library.save(); rebuild()
            }
        })
        body.addView(row, lp(t = dp(10)))

        if (!s.camEnabled) return

        body.addView(section("📱  On each iPhone"))
        val c = card(16)
        val addrs = CamState.addresses()
        val port = CamState.port
        c.addView(step(1, "Connect the iPhone to the same Wi-Fi as this phone."))
        c.addView(step(2, "Open Safari and go to:"))
        if (addrs.isEmpty()) c.addView(label("This phone is not on Wi-Fi right now.", 15f, Pal.RED, true))
        addrs.forEach { a ->
            val url = "http://$a:$port"
            val note = when {
                a.startsWith("100.") -> "    (Tailscale / away from home)"
                a.startsWith("172.20.10.") -> "    (🚗 iPhone hotspot / car)"
                else -> ""
            }
            c.addView(mono(url + note), lp(MATCH, WRAP, t = dp(4)))
        }
        c.addView(step(3, "Enter this code:"))
        c.addView(label(s.camCode.chunked(3).joinToString(" "), 34f, Pal.INK, true).apply { letterSpacing = 0.1f })
        c.addView(step(4, "Tap Share → \"Add to Home Screen\" so it opens like an app next time."))
        c.addView(pill("Copy address", Pal.CARD, Pal.INK) { addrs.firstOrNull()?.let { copy("http://$it:$port") } }, lp(WRAP, WRAP, t = dp(6)))
        body.addView(c)

        body.addView(section("Test it"))
        val t = card(16)
        t.addView(label(if (CamState.running) "✅ Cam server is running now – try it from your iPhone." else "The cam normally runs only while she's watching. Start a test so you can set up your iPhone now.", 14f, Pal.SUB))
        t.addView(pill(if (CamState.running) "⏹  Stop test" else "▶  Start test now", if (CamState.running) Pal.RED else Pal.GREEN) {
            if (CamState.running) stopService(Intent(this, CamService::class.java))
            else startForegroundService(Intent(this, CamService::class.java))
            body.postDelayed({ rebuild() }, 600)
        }, lp(MATCH, WRAP, t = dp(10)))
        body.addView(t)

        body.addView(section("Privacy"))
        val p = card(16)
        p.addView(label("Only someone on your Wi-Fi who knows the code can watch. Change the code if you ever share it by mistake. Android shows a green dot at the top while the camera is in use.", 14f, Pal.SUB))
        p.addView(pill("🔁  Make a new code", Pal.CARD, Pal.INK) {
            confirm("New code?", "Both iPhones will need the new code.", "Change") {
                s.camCode = (100000..999999).random().toString(); Library.save(); rebuild()
            }
        }, lp(MATCH, WRAP, t = dp(10)))
        body.addView(p)

        body.addView(section("🚗  Car mode (no Wi-Fi)"))
        body.addView(card(16).apply {
            addView(label("No Wi-Fi, like in the car? The iPhone itself can be the network:", 14f, Pal.INK, true))
            addView(step(1, "On your iPhone: Settings → Personal Hotspot → turn on \"Allow Others to Join\"."))
            addView(step(2, "On this phone: Wi-Fi → pick your iPhone and enter the hotspot password (only once, it remembers)."))
            addView(step(3, "Open a show here. A 172.20.10.x address appears above. Put it in the Baby Cam app under Change → 🚗 Car mode."))
            addView(label("Tip: so the address never changes, open this phone's Wi-Fi settings → your iPhone's hotspot → Edit → IP settings: Static, IP 172.20.10.9, gateway 172.20.10.1, prefix length 28, DNS 172.20.10.1. Then tap 🚗 Car in the Baby Cam app.", 13f, Pal.SUB).apply { setPadding(0, dp(10), 0, 0) })
            addView(label("This works with no cell signal at all. The phones talk directly; nothing goes to the internet. (Bluetooth can't be used, because iPhone web apps aren't allowed to use it.)", 13f, Pal.SUB).apply { setPadding(0, dp(8), 0, 0) })
        }, lp(t = dp(4)))

        body.addView(section("Watching when you're away from home"))
        body.addView(card(16).apply {
            addView(label("Install the free Tailscale app on this phone and on your iPhones, sign in to the same account on all of them, and an extra 100.x.x.x address will appear above. That one works from anywhere.", 14f, Pal.SUB))
        })
    }

    private fun hasCam() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_CAM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM) {
            if (hasCam()) { Library.settings.camEnabled = true; Library.save() } else toast("Camera permission is needed for the baby cam.")
            rebuild()
        }
    }
}
