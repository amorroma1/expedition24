# Installing MFD-24

**English** · [Français](i18n/INSTALL.fr.md) · [Deutsch](i18n/INSTALL.de.md) · [Italiano](i18n/INSTALL.it.md) · [日本語](i18n/INSTALL.ja.md) · [中文](i18n/INSTALL.zh.md)

MFD-24 is distributed one way: a signed `app-earth-release.apk` on the
[GitHub releases page](https://github.com/amorroma1/expedition24/releases/latest). Wear OS has no
sideloading UI, so every route below ends in ADB — the only question is which machine runs it.
Three routes, easiest first. All of them need [developer options](#first-developer-options-on-the-watch)
switched on, so start there.

Requires Wear OS 3.0 or newer (API 30). Built and worn on a TicWatch Pro 3 Ultra (454 × 454); the
layout is proportional to the radius, so other round screens should be fine.

## Why MFD-24 is not on Google Play

A decision, not a backlog item. Three reasons:

- **A dead-man's monitor should not be an impulse install.** The vigilance feature is an
  uncertified aid — useful exactly to people who read what it does and what it does not promise
  before trusting it. A store listing is built to be tapped on in thirty seconds; a sideload is
  read, checked and installed on purpose, by the audience the face was built for.
- **The permissions are the expensive kind.** Background location, body sensors and a health-type
  foreground service are legitimate here — the weather job runs in the background, the monitor
  reads the accelerometer with the screen off — but on Play they put a hobby project into the
  same standing-review machinery as commercial wellness apps, with policy churn every year and
  removal as the default outcome of silence. That time is better spent on the watch face.
- **You can verify what you install.** Every release carries the APK's SHA-256 in its notes, the
  APK is signed with the same key since 1.0.0, and the source that produced it is one tag away.
  A store would add an intermediary, not assurance.

None of this is about licensing — GPL software is allowed on Play. It is about who installs a
watch-standing instrument, and how deliberately.

## First: developer options on the watch

1. On the watch: **Settings → System → About → Versions** (wording varies by maker) and tap
   **Build number** seven times, until it says you are a developer.
2. Back in Settings, open **Developer options** and switch on **ADB debugging** and
   **Wireless debugging** (on Wear OS 3 it may be called **Debug over Wi-Fi**).
3. Put the watch on the **same Wi-Fi network** as the phone or computer that will do the install.

## Route 1 — a phone and nothing else: Wear Installer 2

The gentlest route: a free Android phone app that runs the ADB handshake for you and shows each
step on screen. It is third-party freeware (Wear Installer 2, by Malcolm Bryant / freepoc) — not
part of this project, but widely used for exactly this job.

1. On the **phone**, install **Wear Installer 2** from Google Play.
2. On the **phone**, download `app-earth-release.apk` from the
   [latest release](https://github.com/amorroma1/expedition24/releases/latest).
3. In Wear Installer 2, follow its wizard: it asks for the watch's IP address and the pairing code
   — both are on the watch under **Developer options → Wireless debugging → Pair new device**.
4. Point it at the downloaded APK and let it install.
5. On the watch: long-press the current face, swipe to **MFD-24**, tap it.

## Route 2 — a computer with ADB

The canonical route, and the one everything else is sugar over.

1. Get [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)
   (a small zip; `adb` is inside) and download `app-earth-release.apk` from the
   [latest release](https://github.com/amorroma1/expedition24/releases/latest).
2. *(Worth the ten seconds)* Check the download against the SHA-256 printed in the release notes:
   `certutil -hashfile app-earth-release.apk SHA256` on Windows,
   `shasum -a 256 app-earth-release.apk` on macOS/Linux.
3. On the watch: **Developer options → Wireless debugging → Pair new device**. It shows an IP with
   a **pairing port** and a six-digit code. While that dialog is open:

   ```
   adb pair 192.168.1.50:37000 123456
   ```

4. Back on the Wireless debugging screen the watch shows a second, **different** port — the
   connect port:

   ```
   adb connect 192.168.1.50:41234
   adb install -r app-earth-release.apk
   ```

5. Make it the active face — pick it in the watch's own face picker, or:

   ```
   adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
       --es operation set-watchface \
       --ecn component com.avdesign.mfd24/com.avdesign.mfd24.MfdWatchFaceService
   ```

What goes wrong, because it will:

| Symptom | Cause and cure |
|---|---|
| `adb connect` fails or hangs | You gave it the **pairing** port. The connect port is the different number on the main Wireless debugging screen. |
| `protocol fault (couldn't read status message)` | The pairing code expired with its dialog. Reopen **Pair new device** and run `adb pair` while it is showing. |
| `error: closed` or `device offline` mid-command | The watch dropped off Wi-Fi when its screen slept. Wake the screen, reconnect — expect a **new port** if wireless debugging was toggled. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed build is signed with a different key than the one you are installing (a self-built APK over a release, or the reverse). Uninstall first — settings go with it. |

## Route 3 — build from source

For reading, patching, or trusting no binary but your own.

```
git clone https://github.com/amorroma1/expedition24.git
cd expedition24
./gradlew :app:assembleEarthDebug
adb install -r app/build/outputs/apk/earth/debug/app-earth-debug.apk
```

You need a JDK 17+ (`JAVA_HOME` pointing at Android Studio's bundled one is enough) and the
Android SDK. `assembleEarthRelease` builds the release variant; without a signing key configured
it falls back to the debug keystore so the APK still installs on your own watch.

**The signature is the boundary:** a self-built APK and the GitHub release cannot install over
each other, because Android requires updates to share a signing key. Crossing over means
uninstalling first, and settings, duty state and the incident log go with the uninstall. Pick a
lane — releases for wearing, your own builds for hacking — and stay in it.

## Updating

The watch will tell you when there is a new release. It will not install it, and it does not
pretend it can.

- **Once a day, off duty, it asks GitHub whether something newer exists.** One small JSON body.
  **Nothing is downloaded, ever.** The check never runs while a watch is under way, and
  **ABOUT → RELEASE CHECK** switches it off entirely.
- **One notification per release**, and **ABOUT → RELEASES** names the version whenever one is
  waiting.
- **Tapping it shows the release page as a QR code.** Point a phone at it: the notes, the SHA-256
  and the APK are all on that page, in a browser, at a size a person can actually read.
- **Then install it the ordinary way**, from a computer:

  ```
  adb install -r app-earth-release.apk
  ```

**Why not from the watch?** Because Wear OS does not allow it. The install session commits, the
platform asks for confirmation, and its own installer answers *"Install/Uninstall actions not
supported on Wear"* — verified on the API 30 emulator and on a TicWatch Pro 3 Ultra running Wear
3.5. Every way round is closed to an ordinary app, so the button that used to be here was a button
that could not work, and the wall of release notes beside it was a wall of text on a 1.2-inch
screen. Both are gone; the QR is what replaced them.

*(If an APK is already on the watch, `adb push` it to `/data/local/tmp/` and
`adb shell pm install -r /data/local/tmp/…` — that path works where `/sdcard` gives an SELinux
denial.)*

## After installing

- **Settings** live behind a long-press on the face, then the pencil. Everything the face needs
  it asks for there — location for weather, Nadir and the site lock; sensor permissions the moment
  a slot that needs one is chosen. Nothing is requested at install time. The full permission table
  is in the [README](../README.md#permissions).
- **Updates** announce themselves and wait — see [Updating](#updating) above; `adb install -r`
  with the new release always works too. Settings survive either way, except on releases that
  change the settings schema, which is called out in the release notes when it happens.
- **No companion app, no account.** The APK is the whole product.
