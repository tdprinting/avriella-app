# Avriella TV

An Android app (built for a Moto G Power) that turns a phone into a locked-down, offline baby TV, with a built-in **baby cam** that parents can watch from their iPhones.

- **Offline shows and movies.** Plays video files stored on the phone. No streaming or internet needed.
- **Shows and playlists.** Sort videos into shows (like "Bluey"), or build playlists in your own order. Auto-play, loop, shuffle, and it remembers where she stopped.
- **Kid lock.** Touch, Back, Home, Recents, notifications and every button are blocked. **Hold BOTH volume buttons for about 1 second** to open Parent controls.
- **Baby games.** Finger Paint, Bubble Pop, Peekaboo Pup, Music Pads and Sparkle Sky: big targets, soft sounds, and no menus she can wander into.
- **Baby cam.** Watch the **front or back camera** live from Safari on an iPhone. The camera runs in the background with no preview on screen, so her video keeps playing while you watch.

## Folders

| Folder | What it is |
|---|---|
| `android/` | The Android app (Kotlin, no third-party libraries) |
| `docs/` | The iPhone viewer web app. It's bundled into the APK, and GitHub Pages can host it too |
| `release/AvriellaTV.apk` | Ready-to-install APK |

## 1. Install on her phone

1. Copy `release/AvriellaTV.apk` to the phone (USB, Google Drive, etc.) and tap it. Allow "install unknown apps" when asked.
   Or with USB debugging on: `adb install -r release/AvriellaTV.apk`
2. Copy her videos onto the phone, for example into `Download/Bluey/`. Use finished `.mp4` files, not `.crdownload` files.
3. Open **Avriella TV → Manage → Add a whole folder as a show**, and pick the Bluey folder. Done.

## 2. Turn on Full Lock (recommended, one time)

Without it, the app uses Android's *screen pinning*. That blocks almost everything, but Android's own "unpin" gesture can still get out. Full Lock removes that gesture and also blocks the notification shade and the power menu. It also stops the **"App is pinned – Got it"** popup, which otherwise appears on her screen every time a show starts remotely.

**If Test DPC is already on her phone** (it is the device owner), skip the steps below: open the app's menu, tap **Basic lock**, tap **Open Test DPC**, go to **Lock task → Manage lock task list**, tick **Avriella TV**, then tap OK. It takes about 30 seconds.

Otherwise:

1. Settings → About phone → tap **Build number** 7 times.
2. Settings → System → Developer options → **USB debugging** on.
3. Settings → Passwords & accounts → remove all accounts for now. Android requires this, and you can re-add them right after.
4. Connect to the PC and run:
   ```
   adb shell dpm set-device-owner com.avriella.tv/.kid.AdminReceiver
   ```
5. Re-add your Google account. In the app, **Kid lock** should now say *Full lock is ON*.

To uninstall later, first tap **Kid lock → Turn off Full Lock**.

## 3. Baby cam on your iPhones

**No codes, no addresses.** Her phone announces itself on your network as `avriella.local`, and the Baby Cam app finds it by that name (plus any address it has seen before) every time you open it, on home Wi-Fi or on your iPhone's hotspot in the car. iPhones on the same Wi-Fi or hotspot connect without a code. If you'd rather require one, turn on **Manage → Baby cam → Require a code**. The connection stays up whenever Avriella TV is open on her phone, including the parent menu. Allow the camera with **"While using the app"** (not "Only this time"), or the camera turns off again after the app closes.

**Scrubbing:** drag (or tap) the progress bar in Now Playing to jump to any spot in her video.


**One-time setup on her phone:** go to **Manage → Baby cam**, turn it on, and allow the camera. Note the address (for example `http://192.168.1.23:8080`) and the 6-digit code.

**Install the Baby Cam app on each iPhone:**
1. In Safari, open **https://tdprinting.github.io/avriella-app/**
2. Tap **Share ⬆︎ → Add to Home Screen → Add**. A Baby Cam icon appears on the home screen.
3. Open it **from the home screen**, enter the address and code, and tap **Save & watch**.
   (Set it up from the home-screen icon, not in Safari. The home-screen app keeps its own settings.)

From then on, tapping the icon connects automatically. You can turn that off with the *Connect automatically* switch.

**In the viewer:**
- **Now playing** shows what's on her phone, with a picture, a progress bar and ⏮ ⏯ ⏭ buttons.
- **📺 Pick a video** lists every show, playlist and video saved on her phone. Tap one, then **Play on her phone**, and it starts right away and auto-plays the rest of that show. If she's in a game, it switches her to the video (still locked).
- **🔄 Back cam / Front cam** switches cameras. Her video keeps playing.
- **↻ Rotate**, **🪞 Mirror** and **📸 Photo** do what they say.
- **Auto-find her phone** (on by default): if her phone gets a new Wi-Fi address, the viewer searches your home Wi-Fi and reconnects on its own.

> Auto-find runs inside the viewer, because Safari doesn't let a GitHub page search your home network. If her phone's address changes while the iPhone app is closed, the first tap may fail. Tap **✏️ Change** and type the new address, or (best) give her phone a fixed address so it never changes: in your router app, "reserve" its IP address. Tailscale also gives a fixed address.

The viewer also shows what she's watching, whether she's on a game or the sleep screen, and her phone's battery level.

- The cam server only runs while a show or game is on (or during a test from the Baby cam screen). The camera itself only turns on while an iPhone is actually watching.
- It only works on your home Wi-Fi. To watch from anywhere, install **Tailscale** on her phone and your iPhones. An extra `100.x.x.x` address then appears in the Baby cam screen.
- If GitHub Pages is enabled on this repo (Settings → Pages → `main` / `docs`), the hosted page asks for the phone's address and forwards you to it.

## 4. Sleep sounds

The sleep sounds list starts with six recorded lullabies that loop forever with no gap: Rainy Night Lullaby, Soft Rain Lullaby, Lullaby by the Water, Dreamy Lullaby, Twinkle Dreams and Moonlight Melody. They live in `android/app/src/main/res/raw` as loop-ready OGG files.

On the parent home screen, the **😴 Sleep sounds** tiles start calm sounds with slow, dark pictures and a very dim screen. Choices: ocean, rain, thunderstorm, waterfall, stream, forest, crickets, wind, heartbeat, shushing, lullaby, fan, white noise and brown noise. They're made live on the phone, so they need no internet and never repeat in an obvious loop. You can also pick one from the iPhone (**😴 Sleep sounds** card) to switch her straight from a show to bedtime. When the sleep timer ends, the sound fades out slowly instead of cutting off.

## 5. Keep video level

**Manage → Settings → Keep video level** (also in the parent panel and on the iPhone under **🎛️ Her phone**). When it's on, the screen stops flipping and the picture turns with the motion sensor instead, so the show stays upright however she holds or flips the phone.

## 6. Car mode (no Wi-Fi)

1. On your iPhone turn on **Personal Hotspot**.
2. On her phone join that hotspot once (it remembers it).
3. Her phone shows a `172.20.10.x` address in **Manage → Baby cam**. In the iPhone app tap **✏️ Change → 🚗 Car mode** and enter it, then pick **🚗 Car** on the connect screen.

Tip: give her phone a fixed IP of `172.20.10.9` on your hotspot (her Wi-Fi settings → your hotspot → IP settings: Static, gateway `172.20.10.1`, prefix 28) so it never changes. The phones talk directly, so this works with no cell signal. The iPhone app also opens offline once it has been opened online one time. Bluetooth isn't possible, because iPhone web apps can't use it.

## 7. Wake her phone, and watching from work

- **Remote wake-up:** the iPhone connection now stays on all the time, even when her screen is off. Tap **☀️ Wake her phone** in the Baby Cam app, or just pick a video or sleep sound and her phone wakes up and plays it. On her phone, once: **Manage → Baby cam → allow "Display over other apps" and "Stay connected while asleep"**. Keeping her phone on the charger is best.
- **Touch lock from the iPhone:** under 🎛️ Her phone, tap **Unlock touch / Lock touch**. Holding both volume buttons on her phone still opens the parent panel as before.
- **From anywhere (work, cell data), nothing to install:** open the Baby Cam app once on your home Wi-Fi and it pairs by itself. When you're away, it connects through free public message relays instead. Everything is end-to-end encrypted with a secret only her phone and your paired iPhones have; the relay only sees scrambled data. Away from home the camera shows about 3 pictures a second instead of smooth video. Controls, the baby book and photos all work the same. To revoke: Manage → Baby cam → Unpair all iPhones. (Tailscale still works too if you ever want smooth video from away.)

## 8. Baby book

Tap **👶 Baby book** on the parent home screen, or scroll to it in the Baby Cam app on your iPhone.
- **Log** baths, feedings, diapers, naps, medicine, measurements, milestones and notes with one tap. You can add a note and change the time.
- **Her info:** birthday (Feb 7, 2026, 8:40 AM), doctor, allergies and more, plus her exact age in years, months, days, weeks and hours.
- **Stats & monthly photos:** the last 7 days at a glance and one photo per month. On the 1st of every month her phone reminds you to take this month's picture. **🎞️ Flip mode** plays all the photos in order, newborn to now, so you can watch her grow.
- Everything is saved on her phone; nothing goes to the internet.

## 9. Talk to her and video call

In the Baby Cam app, the **💬 Talk to her** card has two buttons.
- **🎙️ Hold to talk.** Hold the button, or tap once to start and again to send. Her phone plays your voice. Her show or sleep sounds turn down while you talk, then fade back up. Pick a **voice filter** first for fun (chipmunk, monster, robot, alien, echo, radio, underwater, ghost, dino). **🔊 Hear my last one** replays it on your iPhone.
- **📹 Video call.** Her phone wakes up and pauses whatever she's watching. It shows you full screen with her own picture in the corner, using the loudspeaker. When you hang up, her show or sounds pick up right where they left off. Video calls need the Baby Cam app opened from the web link (https), not the home-Wi-Fi address. Allow the microphone on her phone once when the app asks.
- The call connects directly between the phones. It usually works from anywhere, but some strict networks (for example some work or hotel Wi-Fi) can block it. Talk still works there.

## Building

```
cd android
gradlew assembleDebug
```
Needs JDK 17+ and the Android SDK (compileSdk 35).
