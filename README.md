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

Without it, the app uses Android's *screen pinning*. That blocks almost everything, but Android's own "unpin" gesture can still get out. Full Lock removes that gesture and also blocks the notification shade and the power menu.

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

1. On her phone: **Manage → Baby cam**, turn it on, and allow the camera.
2. On the iPhone (same Wi-Fi), open Safari and go to the address shown, e.g. `http://192.168.1.23:8080`.
3. Enter the 6-digit code. Tap **Share → Add to Home Screen** so it opens like an app.
4. Use **🔄 Back cam / Front cam** to switch cameras, **↻ Rotate** if the picture is sideways, and **📸 Photo** for a snapshot.

The viewer also shows what she's watching, whether she's on a game or the sleep screen, and her phone's battery level.

- The cam server only runs while a show or game is on (or during a test from the Baby cam screen). The camera itself only turns on while an iPhone is actually watching.
- It only works on your home Wi-Fi. To watch from anywhere, install **Tailscale** on her phone and your iPhones. An extra `100.x.x.x` address then appears in the Baby cam screen.
- If GitHub Pages is enabled on this repo (Settings → Pages → `main` / `docs`), the hosted page asks for the phone's address and forwards you to it.

## Building

```
cd android
gradlew assembleDebug
```
Needs JDK 17+ and the Android SDK (compileSdk 35).
