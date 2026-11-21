package com.avriella.tv

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.avriella.tv.cam.CamState
import com.avriella.tv.data.Library
import com.avriella.tv.data.Shelf
import com.avriella.tv.games.GAMES
import com.avriella.tv.games.Sounds
import com.avriella.tv.kid.GameActivity
import com.avriella.tv.kid.KidLock
import com.avriella.tv.kid.PlayerActivity
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.ParentActivity
import com.avriella.tv.ui.ProgressLine
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.art
import com.avriella.tv.ui.card
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.formatDuration
import com.avriella.tv.ui.grid
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.pressable
import com.avriella.tv.ui.rounded
import com.avriella.tv.ui.section
import com.avriella.tv.ui.PupView
import com.avriella.tv.ui.toyBackground
import com.avriella.tv.ui.popIn
import com.avriella.tv.ui.breathe
import com.avriella.tv.ui.lift
import com.avriella.tv.ui.pressScale
import com.avriella.tv.ui.blend
import com.avriella.tv.ui.CHUNKY
import com.avriella.tv.ui.toast
import com.avriella.tv.ui.vbox

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        Thread { Sounds.init(this) }.start()
        com.avriella.tv.cam.KeeperJob.schedule(this)
        try { PhotoReminder.schedule(this) } catch (e: Exception) { android.util.Log.w("AvriellaBaby", "couldn't schedule photo reminder", e) }
        // The iPhone connection stays up all the time (screen off and asleep too), so parents can
        // check in and wake her phone up from anywhere. It only stops if they turn it off.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { startCam(a) }
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {
                com.avriella.tv.cam.Wake.top = java.lang.ref.WeakReference(a)
                com.avriella.tv.cam.Wake.topResumed = true
                startCam(a)
            }
            override fun onActivityPaused(a: android.app.Activity) {
                if (com.avriella.tv.cam.Wake.top?.get() === a) com.avriella.tv.cam.Wake.topResumed = false
            }
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {
                if (com.avriella.tv.cam.Wake.top?.get() === a) com.avriella.tv.cam.Wake.top = null
            }
        })
    }

    companion object {
        /** Starts the iPhone connection (web server + camera on demand) if the parents turned it on. */
        fun startCam(a: android.content.Context) {
            if (!Library.settings.camEnabled) { android.util.Log.i("AvriellaCam", "iPhone connection is turned off in settings"); return }
            try { a.startForegroundService(Intent(a, com.avriella.tv.cam.CamService::class.java)) } catch (e: Exception) {
                android.util.Log.w("AvriellaCam", "couldn't start cam service", e)
            }
        }
    }
}

/** Parent home: one tap on any tile starts it, locked. */
class MainActivity : ParentActivity() {
    override val hasBack = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        addAction("👶 Baby book") { startActivity(Intent(this, BabyActivity::class.java)) }
        addAction("⚙️ Manage") { startActivity(Intent(this, ManageActivity::class.java)) }
    }

    private var askedCamera = false
    private var askedNotify = false
    private var askedMic = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) App.startCam(this)  // switch the camera on now that it's allowed
    }

    private val remoteHandler: (com.avriella.tv.cam.Remote.Cmd) -> String = { c ->
        if (c.action == "play" && c.videoId != null && Library.videos.containsKey(c.videoId)) {
            PlayerActivity.start(this, c.shelfId, c.videoId); "ok"
        } else if (c.action == "sounds") { com.avriella.tv.kid.SleepSoundsActivity.start(this, c.value); "ok" }
        else "Her phone is on the parent menu."
    }

    override fun onResume() {
        // If something ever crashed while locked, don't leave the parents stuck.
        KidLock.exit(this)
        CamState.mode = "menu"
        super.onResume()
        com.avriella.tv.ui.DvdOpen.clear(this)
        com.avriella.tv.data.Covers.fetchMissing(this) // covers and disc art for any movies just added
        com.avriella.tv.cam.Remote.handler = remoteHandler
        if (Library.settings.camEnabled && !askedCamera &&
            checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedCamera = true
            toast("Allow the camera (choose \"While using the app\") so you can watch from your iPhone.")
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 41)
        } else if (!askedMic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedMic = true
            toast("Allow the microphone so you can video call her from your iPhone.")
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 43)
        } else if (!askedNotify && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotify = true
            toast("Allow notifications so the app can remind you to take her monthly photo.")
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    override fun onPause() {
        if (com.avriella.tv.cam.Remote.handler === remoteHandler) com.avriella.tv.cam.Remote.handler = null
        super.onPause()
    }

    override fun build() {
        val s = Library.settings
        titleView.text = ""
        body.addView(hero(s.childName), lp(t = dp(2)))
        body.addView(label("Tap ▶ on anything and it starts locked. To come back here, hold BOTH volume buttons for 1 second.", 13f, Pal.SUB).apply {
            setPadding(dp(8), dp(10), dp(8), dp(2))
        })

        if (Library.videos.isEmpty()) {
            val c = card(22)
            c.addView(label("No videos yet", 20f, Pal.INK, true))
            c.addView(label("Copy her shows onto this phone (for example into a \"Bluey\" folder in Downloads), then add them here.", 15f, Pal.SUB).apply { setPadding(0, dp(6), 0, dp(14)) })
            c.addView(pill("➕  Add videos", Pal.BLUE) { startActivity(Intent(this, ManageActivity::class.java)) }, lp(WRAP, WRAP))
            body.addView(c, lp(t = dp(12)))
        } else {
            com.avriella.tv.cam.Cast.current()?.let { tv ->
                body.addView(pill("📺  Now on $tv — TV remote", Pal.PURPLE, Color.WHITE, 17f) {
                    startActivity(Intent(this, TvRemoteActivity::class.java))
                }, lp(MATCH, WRAP, t = dp(12)))
            }
            continueCard()?.let { body.addView(it, lp(t = dp(12))) }
            body.addView(pill("🎮  TV remote — control the Fire TVs", Pal.CARD, Pal.INK, 16f) {
                startActivity(Intent(this, FireRemoteActivity::class.java))
            }, lp(MATCH, WRAP, t = dp(12)))

            val shows = Library.shows()
            body.addView(badge("📺", "Shows", Pal.BLUE))
            if (shows.isEmpty()) body.addView(hintCard("Make a show (like \"Bluey\") in Manage to sort her videos."))
            else body.addView(grid(shows.map { shelfTile(it) }))

            val pls = Library.playlists()
            body.addView(badge("🎵", "Playlists", Pal.PURPLE))
            if (pls.isEmpty()) body.addView(hintCard("Playlists are your own mixes that auto-play in order. Make one in Manage → New playlist."))
            else body.addView(grid(pls.map { shelfTile(it) }))

            body.addView(pill("🎞️  All videos (${Library.videos.size})", Pal.ORANGE, Color.WHITE, 17f) {
                startActivity(Intent(this, ShelfActivity::class.java).putExtra("shelf", Library.ALL))
            }, lp(MATCH, WRAP, t = dp(14)))
        }

        body.addView(badge("🎮", "Games", Pal.GREEN))
        body.addView(grid(GAMES.map { g ->
            tile(g.name, g.blurb, g.color, g.emoji, null, onOpen = { GameActivity.start(this, g.id) }, onPlay = { GameActivity.start(this, g.id) })
        }))

        body.addView(badge("🌙", "Sleep sounds", 0xFF4B4FB8.toInt()))
        body.addView(label("Calm sounds with a dark, dimmed screen for naps and bedtime.", 14f, Pal.SUB).apply { setPadding(dp(4), 0, dp(4), dp(6)) })
        body.addView(grid(com.avriella.tv.sleep.SLEEP_SOUNDS.map { s ->
            tile(s.name, "Tap to start", 0xFF4B4FB8.toInt(), s.emoji, null,
                onOpen = { com.avriella.tv.kid.SleepSoundsActivity.start(this, s.id) },
                onPlay = { com.avriella.tv.kid.SleepSoundsActivity.start(this, s.id) })
        }))

        body.addView(badge("🛡️", "Safety", Pal.ORANGE))
        val full = KidLock.isFullLock(this)
        body.addView(statusRow(
            if (full) "🔒  Full lock is ON" else "⚠️  Basic lock (tap to set up Full Lock)",
            if (full) Pal.SOFT_GREEN else Pal.SOFT_ORANGE,
        ) { startActivity(Intent(this, LockSetupActivity::class.java)) }, lp(t = dp(4)))
        val camText = when {
            !s.camEnabled -> "📷  Baby cam is off (tap to set up iPhone viewing)"
            CamState.running -> "📷  Baby cam is ON right now"
            else -> "📷  Baby cam turns on while she watches"
        }
        body.addView(statusRow(camText, if (s.camEnabled) Pal.SOFT_BLUE else Pal.CARD) {
            startActivity(Intent(this, CamSetupActivity::class.java))
        }, lp(t = dp(8)))
    }

    private fun hintCard(t: String) = card(16).apply { addView(label(t, 14f, Pal.SUB)) }

    private fun statusRow(t: String, bg: Int, onClick: () -> Unit) = label(t, 15f, Pal.INK, true).apply {
        val r = dpf(18f)
        background = pressable(rounded(bg, r, dp(1), Pal.LINE), r)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }

    private fun continueCard(): View? {
        val v = Library.settings.lastVideoId?.let { Library.videos[it] } ?: return null
        val shelfId = Library.settings.lastShelfId ?: Library.ALL
        val c = card(14)
        c.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF3D86F0.toInt(), 0xFF5AA8FF.toInt(), 0xFFFF9A3D.toInt()),
        ).apply { cornerRadius = dpf(28f) }
        c.elevation = dpf(6f)
        val row = hbox()
        val a = com.avriella.tv.ui.DvdCase(this, v, Pal.ORANGE)
        row.addView(a, lp(dp(96), WRAP, r = dp(16)))
        val col = vbox()
        col.addView(label("✨ KEEP WATCHING", 12f, 0xFFFFF3C4.toInt(), true))
        col.addView(label(v.title, 17f, Color.WHITE, true, 2))
        col.addView(label("from ${Library.shelfName(shelfId)}", 13f, 0xE6FFFFFF.toInt(), lines = 1))
        col.addView(ProgressLine(this, v.progress, 0xFFFFE14D.toInt(), 0x44FFFFFF), lp(MATCH, dp(6), t = dp(8)))
        row.addView(col, lp(0, WRAP, 1f))
        c.addView(row)
        val go = pill("▶  Play for ${Library.settings.childName}", Pal.YELLOW, Pal.INK, 19f) { com.avriella.tv.ui.DvdOpen.choose(this, a) { PlayerActivity.start(this, shelfId, v.id) } }
        go.breathe(0.03f)
        c.addView(go, lp(MATCH, WRAP, t = dp(12)))
        return c
    }

    private fun shelfTile(s: Shelf): View {
        val vids = Library.videosIn(s.id)
        val total = vids.sumOf { it.durationMs }
        val sub = "${vids.size} video${if (vids.size == 1) "" else "s"}" + (if (total > 0) " · ${formatDuration(total)}" else "")
        return tile(s.name, sub, s.color, s.emoji, vids.firstOrNull(),
            onOpen = { startActivity(Intent(this, ShelfActivity::class.java).putExtra("shelf", s.id)) },
            onPlay = if (vids.isEmpty()) null else ({ PlayerActivity.start(this, s.id) }))
    }

    private fun tile(title: String, sub: String, color: Int, emoji: String, thumb: com.avriella.tv.data.Video?, onOpen: () -> Unit, onPlay: (() -> Unit)?): View {
        val c = vbox()
        val r = dpf(24f)
        c.background = pressable(toyBackground(color, r, depth = dp(6)), r, 0x40FFFFFF)
        c.lift(dpf(5f), color)
        c.setPadding(dp(8), dp(8), dp(8), dp(16))
        c.setOnClickListener { onOpen() }
        // shows and playlists stand like DVD cases (the cover of their first video); games and sounds keep their picture
        val dvd = thumb?.let { com.avriella.tv.ui.DvdCase(this, it, color) }
        val a: FrameLayout = dvd ?: art(blend(color, Color.WHITE, 0.25f), emoji, thumb, 0.62f, 18f, 46f)
        if (onPlay != null) {
            val play = label("▶", 20f, color, true).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.WHITE, dpf(24f), dp(3), Pal.YELLOW)
                elevation = dpf(4f)
                pressScale(0.85f)
                setOnClickListener { if (dvd != null) com.avriella.tv.ui.DvdOpen.play(this@MainActivity, dvd) { onPlay() } else onPlay() }
            }
            a.addView(play, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) })
        }
        c.addView(a, lp(MATCH, WRAP))
        c.addView(label(title, 17f, Color.WHITE, true, 2).apply {
            setPadding(dp(6), dp(9), dp(6), 0); typeface = CHUNKY
            setShadowLayer(dpf(3f), 0f, dpf(1f), 0x55000000)
        })
        c.addView(label(sub, 13f, 0xEEFFFFFF.toInt(), lines = 2).apply { setPadding(dp(6), dp(2), dp(6), 0) })
        return c
    }

    /** Big friendly header: the puppy mascot waving hello next to her name. */
    private fun hero(name: String): View {
        val c = vbox()
        val r = dpf(30f)
        c.background = toyBackground(Pal.CARD, r, 0xFFBFD8F2.toInt(), dp(6))
        c.lift(dpf(6f))
        c.setPadding(dp(14), dp(12), dp(16), dp(18))
        val row = hbox()
        val pup = PupView(this)
        row.addView(pup, lp(dp(104), dp(104)))
        val col = vbox()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hi = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..20 -> "Good evening"; else -> "Sleepy time" }
        col.addView(label("$hi,", 15f, Pal.SUB, true))
        col.addView(label(name + "!", 32f, Pal.BLUE, true, 1).apply { typeface = CHUNKY; letterSpacing = -0.02f })
        // a row of little colored dots, like confetti
        val dots = hbox()
        intArrayOf(Pal.ORANGE, Pal.YELLOW, Pal.GREEN, Pal.TILE[3], Pal.PURPLE).forEach { col2 ->
            dots.addView(View(this).apply { background = rounded(col2, dpf(5f)) }, lp(dp(10), dp(10), r = dp(6)))
        }
        col.addView(dots, lp(WRAP, WRAP, t = dp(8)))
        row.addView(col, lp(0, WRAP, 1f, l = dp(10)))
        c.addView(row)
        c.popIn()
        return c
    }

    /** Section title with its emoji in a bright round badge. */
    private fun badge(emoji: String, title: String, color: Int): View {
        val row = hbox().apply { setPadding(dp(4), dp(26), dp(4), dp(8)) }
        row.addView(label(emoji, 20f).apply {
            gravity = Gravity.CENTER
            background = toyBackground(color, dpf(22f), depth = dp(3))
            setPadding(0, 0, 0, dp(3))
        }, lp(dp(44), dp(44), r = dp(10)))
        row.addView(label(title, 24f, Pal.INK, true).apply { typeface = CHUNKY; setShadowLayer(dpf(6f), 0f, dpf(1f), 0xCCFFFFFF.toInt()) }, lp(WRAP, WRAP))
        return row
    }
}
