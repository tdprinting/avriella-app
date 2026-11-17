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

private fun ParentActivity.switchRow(title: String, sub: String, on: Boolean, change: (Boolean) -> Unit): android.view.View {
    val row = hbox().apply { setPadding(0, dp(10), 0, dp(4)) }
    val col = vbox()
    col.addView(label(title, 16f, Pal.INK, true))
    col.addView(label(sub, 13f, Pal.SUB))
    row.addView(col, lp(0, WRAP, 1f))
    row.addView(android.widget.Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, c -> change(c) } })
    return row
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

        if (full && !KidLock.isOwner(this)) {
            body.addView(section("Turning it off"))
            body.addView(label("Full Lock is being allowed by your device manager app (for example Test DPC). To turn it off, open that app → Lock task → Manage lock task list and untick Avriella TV.", 14f, Pal.SUB))
            return
        }
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

        val dpc = KidLock.testDpc(this)
        if (dpc != null) {
            body.addView(section("Turn on Full Lock (one time, ~30 seconds)"))
            val t = card(16)
            t.addView(label("Test DPC already manages this phone, so it just needs to allow Avriella TV. This also stops the \"App is pinned – Got it\" popup from appearing on her screen.", 14f, Pal.SUB))
            t.addView(step(1, "Tap the button below to open Test DPC."))
            t.addView(step(2, "Scroll to \"Lock task\" and tap \"Manage lock task list\"."))
            t.addView(step(3, "Tick Avriella TV, then tap OK."))
            t.addView(step(4, "Come back here – it will say Full lock is ON."))
            t.addView(pill("Open Test DPC", Pal.BLUE) {
                try { startActivity(packageManager.getLaunchIntentForPackage(dpc)) } catch (_: Exception) { toast("Couldn't open Test DPC") }
            }, lp(MATCH, WRAP, t = dp(10)))
            body.addView(t)
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

        body.addView(section("Already using Test DPC (or another device manager)?"))
        body.addView(card(16).apply {
            addView(label("If the lock screen says \"This device belongs to your organization\", another app is already the device owner and the command above won't work. Let that app allow Avriella TV instead:", 14f, Pal.SUB))
            addView(step(1, "Open Test DPC → Lock task → Manage lock task list, and tick Avriella TV."))
            addView(step(2, "In Test DPC → Lock task → Set lock task features, turn every feature off (no Home, no Recents, no notifications, no status bar, no power menu)."))
            addView(step(3, "Come back here. It will say Full lock is ON."))
        }, lp(t = dp(4)))

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
        intro.addView(label("Watch ${s.childName} from your iPhone while she watches her shows. The front camera only turns on while one of you is looking. It works on your home Wi-Fi, in the car on your hotspot, and from anywhere else too. You can even wake her phone and start a video.", 15f, Pal.INK))
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
                s.camEnabled = on; Library.save()
                if (on) com.avriella.tv.App.startCam(this@CamSetupActivity) else stopService(Intent(this@CamSetupActivity, CamService::class.java))
                body.postDelayed({ rebuild() }, 600)
            }
        })
        body.addView(row, lp(t = dp(10)))

        if (!s.camEnabled) return

        body.addView(section("📱  On each iPhone"))
        val c = card(16)
        val addrs = CamState.addresses()
        val port = CamState.port
        c.addView(step(1, "Put the iPhone on the same Wi-Fi as this phone (or turn on its Personal Hotspot and join it from this phone)."))
        c.addView(step(2, "Open the Baby Cam app (tdprinting.github.io/avriella-app). It finds this phone by itself, using the name:"))
        c.addView(mono("http://avriella.local:$port"), lp(MATCH, WRAP, t = dp(4)))
        c.addView(label("If it ever can't, type one of these addresses instead:", 13f, Pal.SUB).apply { setPadding(0, dp(10), 0, 0) })
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
        c.addView(step(3, "Tap Share → \"Add to Home Screen\" so it opens like an app next time."))
        c.addView(switchRow("Require a code", "Off: any iPhone on your Wi-Fi or hotspot connects with no code. On: iPhones must type the code below once.", s.camRequireCode) {
            s.camRequireCode = it; Library.save(); rebuild()
        }, lp(t = dp(8)))
        if (s.camRequireCode) c.addView(label(s.camCode.chunked(3).joinToString(" "), 34f, Pal.INK, true).apply { letterSpacing = 0.1f })
        c.addView(pill("Copy address", Pal.CARD, Pal.INK) { addrs.firstOrNull()?.let { copy("http://$it:$port") } }, lp(WRAP, WRAP, t = dp(6)))
        body.addView(c)

        body.addView(section("🌙  Wake her phone from your iPhone"))
        val w = card(16)
        w.addView(label(if (CamState.running) "✅ Connected. Your iPhone can reach this phone now, even with the screen off." else "Starting the connection…", 14f, Pal.SUB))
        val overlayOk = android.provider.Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        w.addView(label("So you can start a video or sounds while her phone is asleep, allow these two once:", 14f, Pal.INK, true).apply { setPadding(0, dp(10), 0, 0) })
        w.addView(pill(if (overlayOk) "✅  Waking up is allowed" else "1.  Allow waking up  (Display over other apps)", if (overlayOk) Pal.CARD else Pal.GREEN, if (overlayOk) Pal.INK else android.graphics.Color.WHITE) {
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
            catch (_: Exception) { toast("Open Settings → Apps → Avriella TV → Display over other apps") }
        }, lp(MATCH, WRAP, t = dp(8)))
        w.addView(pill(if (batteryOk) "✅  Stays connected while asleep" else "2.  Stay connected while asleep  (battery)", if (batteryOk) Pal.CARD else Pal.GREEN, if (batteryOk) Pal.INK else android.graphics.Color.WHITE) {
            @android.annotation.SuppressLint("BatteryLife")
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
            try { startActivity(i) } catch (_: Exception) { toast("Open Settings → Apps → Avriella TV → Battery → Unrestricted") }
        }, lp(MATCH, WRAP, t = dp(8)))
        w.addView(label("Leaving her phone on the charger overnight is best: the connection stays on all the time so you can check in whenever you like.", 13f, Pal.SUB).apply { setPadding(0, dp(10), 0, 0) })
        body.addView(w)

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

        body.addView(section("🌍  From anywhere (at work, on cell data)"))
        body.addView(card(16).apply {
            addView(label(if (CamState.relay) "✅ Ready. Your paired iPhones can reach this phone from anywhere." else "Connecting to the relay… (needs internet on this phone)", 14f, if (CamState.relay) Pal.GREEN else Pal.SUB, true))
            addView(label("Nothing to install and no accounts. Open the Baby Cam app once while you're on your home Wi-Fi; it pairs by itself. After that, when you're away it connects through a free relay instead.", 14f, Pal.SUB).apply { setPadding(0, dp(8), 0, 0) })
            addView(label("Private: everything is locked with a secret key that only this phone and your paired iPhones have (end-to-end encryption). The relay only passes along scrambled data it can't read. Away from home the camera shows about 2 pictures a second instead of smooth video.", 13f, Pal.SUB).apply { setPadding(0, dp(8), 0, 0) })
            addView(pill("🔐  Unpair all iPhones", Pal.CARD, Pal.INK) {
                confirm("Unpair all iPhones?", "They'll stop working from away until you open the Baby Cam app on your home Wi-Fi again.", "Unpair") {
                    s.relaySecret = com.avriella.tv.cam.RelayClient.newSecret(); Library.save()
                    stopService(Intent(this@CamSetupActivity, CamService::class.java))
                    body.postDelayed({ com.avriella.tv.App.startCam(this@CamSetupActivity); rebuild() }, 800)
                }
            }, lp(MATCH, WRAP, t = dp(10)))
        })
        val ts = com.avriella.tv.cam.Wake.tailscale()
        if (ts != null) body.addView(card(16).apply {
            addView(label("Tailscale is on too: http://$ts:${CamState.port} (smooth video from away).", 13f, Pal.SUB))
        }, lp(t = dp(8)))
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
