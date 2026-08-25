# Working notes — MFD-24

Operational notes for continuing this project. The README explains *what* the watch face does and
why it is designed that way; this file is the things that will waste your time if you do not know
them. Assume the README has been read.

---

## Where things stand

Last updated 2026-08-28. Three faces, three branches, three releases; the working branch is
`vital`.

| Face | Branch | Published | On the watch | Unreleased work on the branch |
|---|---|---|---|---|
| MFD-24 (duty) | `main` | `v2.7.0` (`versionCode` 19) | 2.7.0, the published binary | the always-on sensor gate (`bb521ba`) |
| MFD-24-Mars | `mars` | `mars-v0.1.0` | 0.1.0 | the same gate (`d4f302b`) |
| MFD-24-Vital | `vital` | `vital-v0.1.4` | **0.1.5**, ahead of the release | the graphs pass, `TRACK SLEEP`, the ring seam |

**Version fields move once per publish, not once per commit**, so a branch sitting ahead of its
release is the normal state. `main` and `mars` carry one fix each and keep their released version
numbers; `vital` was bumped through 0.1.0…0.1.5 in two days because each of those *was* published
and withdrawn, which is what the release policy says to do rather than replacing bytes under a
number somebody already has.

**Withdrawn deliberately:** `vital-v0.1.0`, `0.1.1`, `0.1.2` and `0.1.3` — each replaced within
hours by the next, release object and tag deleted through the API. The reasons are worth keeping,
because they are the same reason: the daylight band grew four weights and stopped saying where the
day was (see the band entry in the invariants).

- **The README was cut from 1022 lines to ~155 on 2026-08-22**, and the deep half moved to
  `docs/DESIGN.md` (layout, how it works, the full settings reference, the dial's degraded states,
  tests, notes, data sources). What stays in the README: the pitch, the pictures, what you get, a
  four-line install, a settings summary, what it is *not*, and a build block — with `<details>`
  blocks for the update policy and the permission table. The rule going forward: **the README is
  read by somebody deciding whether to install this; anything a reader only wants once they have
  decided belongs in `docs/`.**

- **The assets are one scene, and it is staged.** Every screenshot and GIF is a pilot's morning
  departure from **KJFK** — emulator, manual position 40.6413/-73.7781, `America/New_York`, an
  eight-hour watch begun at 08:10 local, battery forced to 95 %, real METAR off the wire. The
  scene is named in the README caption so nobody thinks the dial invented an airport. One value
  cannot be staged honestly from outside: the emulator's Goldfish heart-rate sensor ignores
  `adb emu sensor set heart-rate` and always reports 0, so the pulse of 67 came from a
  **throwaway local patch to `SensorSlots` that was reverted before committing** — check
  `git status` after any future shoot. Capture recipe: `screenrecord` → 320 px 5 fps frames →
  PIL, quantised to a 128-colour palette; `imageio-ffmpeg` for the MP4s (there is no system
  ffmpeg).

- **Emulator capture traps, paid for twice.** `screenrecord` emits nothing while the display
  dozes, and on battery a Wear emulator dozes within seconds — `settings put global
  wear_ambient_enabled 0` plus `settings put secure ambient_enabled 0` keeps it interactive, while
  putting it *on charge* replaces the whole face with the system charging screen. So: capture
  within the wake window, and splice held stills for anything ambient. A single `input tap` during
  a recording still does not reliably produce the `TAP AGAIN` swap; mandown.gif carries a real
  `screencap` of it spliced over that beat.
- **Emulator note that cost time twice:** injecting prefs after an uninstall/reinstall needs the
  **new** uid — the app was `u0_a74`, came back as `u0_a76`, and files chowned to the old one are
  silently unreadable, which looks exactly like an injection that did not take.
- **Injecting prefs is write-then-stop, never stop-then-write.** The face is a wallpaper and the
  platform revives it within a second of `am force-stop`, so a file written *after* the stop is
  read by nobody and is then overwritten by the process's own in-memory map on its next write.
  Put the heredoc and the `force-stop` in one `adb shell` call, in that order. Both failure modes
  look identical to a feature that does not work: state that never appears, or state that appears
  and then vanishes.
- **On the watch, current: 2.7.0**, installed in place over 2.6.4 on 2026-08-22 (settings reset by
  the schema change; the shift and the incident record survive, being device-protected). Seen on the
  dial: a live watch counting down with its arc, green palette, `33°C SCT Q1007`, `NO SITE` — there
  is nothing in the bundled POI data within 5 km of the user — the solar mark, and the steps slot
  reading a figure from the platform. The `com.avdesign.mfd24.moon` 0.1.0 debug placeholder from the
  multitool era may still be on the device; it is a separate app and harmless, but it is the last
  thing on the watch that names an unshipped face, so uninstall it when convenient.
- **Earlier, 2026-08-22:** release 2.3.0, installed over 2.2.0 in place (settings reset
  by the schema change; the shift and the incident record survived, being device-protected). The
  dial came up holding a **real** incident from the user's own run — `MAN DOWN 19:10Z +01:50` with
  two notches on the arc — which is the first unanswered escalation this project has produced
  outside a test. Left alone deliberately: it is their record to clear.
- **On the watch as of 2026-08-21 evening:** release 2.2.0, a **fresh install** — the 1.4.0
  install was gone (the user had removed it; only a `com.avdesign.mfd24.moon` 0.1.0 debug
  placeholder was on the device, left in place — side-by-side is by design). So **all settings
  are schema defaults and prefs are empty**. Granted after install: fine + background location,
  `BODY_SENSORS`, `ACTIVITY_RECOGNITION`, and the `REQUEST_INSTALL_PACKAGES` appop (via
  `appops set`, for the updater). The fixed port was restored with `adb tcpip 5555` after the
  reboot-forced pairing dance (that day's wireless-debugging port was 38283). Verified on the
  watch: ABOUT renders, and UPDATE reached GitHub over the watch's own Wi-Fi and answered
  `Up to date — this is the latest release.` Position, palette and slots are for the user to set
  in the editor.
- **Verified on hardware** (2026-08-20): the full vigilance escalation — nudge, thirty-second window,
  SOS with **sound** confirmed audible, the five-minute cap, and the incident it leaves behind, which
  rendered as `MAN DOWN 08:09Z +01:09` and survived process restarts. Off-body suspension, with clean
  `off the wrist` / `back on the wrist` transitions in the log. The incident log listed in the editor.
  Double-tap-to-clear, after which the duty row returns and the arc goes back to full width. The
  collapsible editor, on the watch's own screen. Heart rate reading 80 bpm on the wrist.
- **Verified on hardware** (2026-08-20, the post-review build): the background-refresh fallback —
  with every provider off (`location_mode 0`) and only a cached fix in the store, refresh logs
  `no cached fix. providers[fused=off, ...]` then `-> OK [site=true weather=true]` and the dial
  keeps `IEV 0.2KM`; at 1.3.0 this was NO_FIX and a blank. Note the quirk found on the way: this
  watch (API 30) hands `getLastKnownLocation` to background callers *without*
  `ACCESS_BACKGROUND_LOCATION`, so the missing-permission case had to be forced by disabling
  location system-wide — newer APIs are stricter, the fallback still matters. `TAP AGAIN`: first
  tap on `MAN DOWN` swaps the status line, it reverts after ~2.5 s, and the double-tap loop still
  clears. `ALWAYS-ON > AUTO`: solid by day, checkerboarded at night (6116 lit pixels against
  12011 by day — measured off AOD screencaps), and solid again at night the moment a watch runs,
  arc and DUTY row on the dial. The editor's third AUTO segment and its caption render correctly.
- **Verified on an API 36 emulator** (2026-08-20; AVD `api36`, hand-written config over the
  `android-36.1` phone image — `avdmanager` is unavailable, but an AVD is just two ini files):
  the API 34+ health-FGS gate is real and the guard holds. Without `ACTIVITY_RECOGNITION`,
  `startForeground` throws exactly the predicted SecurityException ("requires ... any of
  [ACTIVITY_RECOGNITION, HIGH_SAMPLING_RATE_SENSORS, health.READ_*]"), the service logs
  `Could not go foreground; vigilance cannot run` and stops itself — no crash, process stays up.
  With the grant it goes foreground as type health and settles into `on charge; monitoring
  suspended`. `POST_NOTIFICATIONS` gates exactly as expected: denied, the FGS runs with zero
  `NotificationRecord`s; granted, the ongoing notification appears. The editor's request flow
  itself was not exercised (no Wear 33+ system image; `EditorSession` cannot be created on a
  phone — the activity finishes cleanly there, also verified). Driving a non-exported service
  from the shell needs `run-as <pkg> am start-foreground-service --user 0 ...` with the app
  brought TOP first (start `WatchConfigActivity`); debug builds only.
- **Not verified on hardware:** `ALWAYS-ON > HALVED`, the wake veil's clip to the colour disc, the
  absence of a duty arc on the Moon, and the warship and helicopter site glyphs — nothing in the
  bundled POI data near the user carries those flags. Steps have been seen reading (512) but the
  daily baseline has not been watched across a midnight or a reboot — and the day boundary is now
  *local* midnight, not UTC as it was at 1.3.0.
- **A double tap cannot be scripted through `adb`.** Two `input tap` calls in sequence are more than
  `DOUBLE_TAP_MILLIS` apart because each spawns its own JVM; running them inside one `adb shell` loop
  (`for i in 1 2 3; do input touchscreen tap X Y; input touchscreen tap X Y; sleep 2; done`) lands it.

## The vital branch (local)

A third face on the same tree: `vital` flavor, `com.avdesign.mfd24.vital`, "MFD-24-Vital",
installs beside the duty face. Branched off `main`, not merged with `mars` — the two share only
the idioms. Off by default and opt-in throughout, because it runs a service and an LED.

**What it is.** The day on three rings at the hour hand's point (pulse, activity, sleep — hue
*and* stroke weight, so the palette can stay calm), a score above the hub, a double tap to the
report, and a `GRAPHS` row in the recorder section for the three records drawn across a screen.
The daylight band gained hourly cloud shading, twilight wedges, the next alarm as a notch and
calendar hours as arcs on its outer edge. Duty arc and vigilance work exactly as on the duty
face; only the standing `MAN DOWN` text is dropped there.

Facts paid for on hardware or in review, all now in code and tests:

- **The first step-counter read is a baseline and contributes nothing.** The recorder's very
  first tick on the watch reported the whole counter since boot — twelve thousand steps painted
  onto one quarter-hour. `DayBins.stepDelta` returns zero with no previous reading; a counter
  *below* its last value still returns the counter, because those steps happened after the reboot.
- **The hour sunrise falls in was being dropped.** The per-hour band shading kept an hour only
  when its *start* fell inside the daylight span — true of every hour but the one containing
  sunrise. Seen on the wrist as a dark notch at 06–07; the clipping now lives in `BandClip`,
  pure and tested.
- **Only the next alarm is reachable.** `getNextAlarmClock` gives one alarm, no permission; the
  full list belongs to whichever clock app set it and is exposed to nobody. The mark drops itself
  once fired, and anything past a day is not drawn — on a 24-hour dial it would sit at the hour
  of an alarm set for tonight.
- **`run-as` is unavailable on the release build**, so the day log cannot be read off the watch
  by hand; verify through the face, the report and the graphs instead.
- **A double tap cannot be scripted as two `input tap` calls** (each spawns a JVM): use one
  `adb shell` with both taps, the same trap the incident-clear gesture documents.

Doze shapes the whole recorder: 15-minute bins, inexact `setAndAllowWhileIdle` (vigilance owns
the app's exactness), and totals that survive lateness because every bin's steps are a difference
of two cumulative readings. Sleep is inferred from worn + still + a pulse near the wearer's own
floor, with the pulse confirming and never vetoing — the night is exactly when the platform
thins the samples.

---

## Reading the battery on the watch

Everything below came out of one overnight run and settled an argument that could not be settled
by reasoning about the code. Do this rather than estimating.

```powershell
# since the last full charge; the counters reset when the watch is unplugged at 100 %
adb -s <dev> shell "dumpsys batterystats --charged | grep -E '^  (Time on battery|Screen on)'"
adb -s <dev> shell "dumpsys batterystats --charged | grep -A 30 'Estimated power use'"
adb -s <dev> shell "dumpsys batterystats --charged | grep -E 'Sensor [0-9]+' -B 2"
adb -s <dev> shell "dumpsys sensorservice | head -8"      # what is running *now*, and how fast
adb -s <dev> shell "pm list packages -U"                  # uid -> package, for the table above
```

**The run of 25–26 August 2026, vital face, always-on, recorder at 10 minutes.** 15 h 29 m on
battery, 100 % → 77 % — **1.48 %/hour**, about 9 mA on a 580 mA·h cell, projecting to some 67
hours. The screen was *interactive* for **eleven minutes** of that; the rest was ambient. Actual
drain 133–139 mA·h against a computed 93.6, and the model's own breakdown:
idle 66.6, unaccounted 39.8, system 7.4, screen 6.5, **the face itself 3.1** (cpu 1.5, sensors
1.5). The recorder held `mfd24:vital` for 33 minutes across 111 ticks — its twenty-second pulse
windows, stretched by Doze to about one every 8.4 minutes, exactly as designed.

**The same measurement after the ambient gate, 26–28 August: 2 d 7 h 13 m on battery, 100 % → 27 %.**
That is **1.32 %/hour** against 1.48, a projected 75.6 hours against 67, and about 7.7 mA against
8.6. The sensor line is where the change actually shows: our heart-rate time fell from **15 h 25 m
in 15.5 hours (99.6 % of the run) to 1 h 31 m in 55 hours (2.7 %)** — a duty cycle thirty-six times
lower — across 411 activations of about thirteen seconds each, which is the recorder's windows plus
the twenty-four minutes somebody spent looking at the watch. The recorder's own wake lock: 1 h 14 m
over 244 ticks.

**Eleven per cent of battery for thirty-six times less sensor is the honest headline**, and the
gap between those two numbers is the lesson: the PPG's draw is not where the model puts it, the run
lengths differ, and a wearer's day is not a controlled experiment. What is left in front of it is
not ours — the system holds the light sensor for always-on brightness (1 d 18 h of the run) and the
Wear app holds the off-body detector continuously. The remaining lever we own is the recorder
interval: at fifteen minutes instead of ten the heart-rate time would fall by a third again.

Three things to know when reading that dump:

- **Sensor handles are decimal in `batterystats` and hex in `sensorservice`.** 100 is 0x64 heart
  rate, 105 is 0x69 step counter, 108 is 0x6c the off-body detector, 1 is the accelerometer,
  7 is the light sensor (the system holds it for always-on brightness).
- **The power model does not know what the optical sensor costs on this watch.** The PPG runs
  through the sensor hub, and its draw lands in `idle` and `unaccounted` rather than against the
  uid holding it — which is why our face was blamed for 3.1 mA·h while holding the LED on for
  fifteen hours. Do not read the per-uid table as the answer; read the sensor *times*.
- **Nobody else was holding the heart-rate sensor.** Mobvoi's own continuous monitoring was not
  running: every second of sensor 100 was ours. Worth checking before blaming the platform.

## Verify an install by its hash, not by its version

`com.avdesign.mfd24` reported 2.7.0 / `versionCode` 19 on the watch, matching the published
release — and the binary was a *different build of the same version*, sha `ef515a13…` against the
release's `df5ecb4a…`. Unzipping both showed 438 entries either side and four differing:
`classes.dex` (a kilobyte larger), `resources.arsc`, `AndroidManifest.xml` and the baseline
profile. The watch had a build made before the final amend of 2.7.0; nothing was wrong with it,
and nothing said so either.

```powershell
$path = (adb -s <dev> shell "pm path <pkg>") -replace "package:",""
adb -s <dev> shell "sha256sum $path"       # against the SHA-256 in the release notes
```

Cheap, and the only way to know that the thing being debugged is the thing that was published.

## Releasing

**One release object, one tag, one commit — per face.** Each publish deletes the previous release
object *and* its tag for **that face only**, because a tag is what keeps a collapsed history
publicly reachable, which defeats the point of collapsing it.

Three faces publish from one repository, and they are kept apart by two names that must agree:

| Face | Branch | Tag | Asset | `UPDATE_TAG_PREFIX` |
|---|---|---|---|---|
| MFD-24 | `main` | `v2.7.0` | `app-earth-release.apk` | `v` |
| MFD-24-Mars | `mars` | `mars-v0.1.0` | `app-mars-release.apk` | `mars-v` |
| MFD-24-Vital | `vital` | `vital-v0.1.0` | `app-vital-release.apk` | `vital-v` |

`ReleaseCheck` reads the release **list** and filters it by that pair — tag prefix *and* the asset
this flavour expects — then takes the numerically highest version. `releases/latest` is never
asked for: it is one release per *repository*, so whichever face published most recently would
hide the others' updates for good. `ReleaseCheckTest` pins the crossings, including that the Earth
prefix `v` is a prefix of `vital-v` as well and that the asset name is what separates them.

**Publish every non-Earth release with `make_latest: false`.** The build in the field — Earth
2.7.0 — still carries the old `releases/latest` updater, so a Mars or Vital release marked latest
is the one it would ask about. The gap closes for good only when an Earth release ships with the
list-based check; until then it is one flag on the create call. `git push origin :refs/tags/x` may be refused in this environment; the GitHub API
(`DELETE /repos/{repo}/git/refs/tags/{tag}`) does it, and the publish script already carries that
step alongside the release-object delete.

**Three checks before the notes are written**, all cheap and all of which have caught something:

1. **Nothing in the source is newer than the APK** —
   `find app/src shared app/build.gradle.kts app/proguard-rules.pro -type f -newer <apk>` must come
   back empty, or the thing being published is not the thing that was built.
2. **The published asset's SHA-256 matches the notes and the local file.** Download the asset back
   through the API and hash it; the script interpolates the local hash, so a mismatch means the
   upload, not the arithmetic.
3. **`main`, the tag and the release's `target_commitish` all name the same commit.** After an
   amend, force the tag as well — otherwise the release page and the branch quietly describe
   different trees, and the `raw.githubusercontent.com` images in the notes resolve through the tag.

**Rarely, and only for something worth the wearer's attention.** Set by the user on 2026-08-22
after a run of six releases in two days: a release is a request for somebody's time — they have to
notice it, read it, fetch it and install it over ADB, because the platform allows nothing else — and
spending that on a polish commit teaches them to ignore the next one. Batch the small things behind
the next real change. The version fields move once per publish, not once per commit; `main` can sit
ahead of the latest release for as long as it takes.

## One repository, one branch per face

`main` is the duty face and the default branch; `mars` and `vital` are the other two, each a single
squashed commit off `main` carrying one whole face. They are **branches, not forks**: everything
below `render/`, `astro/`, `geo/` and `text/` is shared, and a fix to the dial belongs on `main`
first. None of the three is merged into another — the faces differ by flavour, not by history, and
a merge would only produce a tree that builds all three from one branch for no one's benefit.

`video-demo` is the standing exception: the film set, never merged and never pushed. The
pre-squash histories (`pre-squash-2026-08-22`, `mars-pre-squash-2026-08-24`) are local-only
insurance and stay that way.

Deleting a remote branch is worth two assertions first, which is how `earth` went: that the default
branch is the one being kept, and that the branch being deleted points at the same commit.

**The flavor of one is deliberate.** A `world` dimension carrying a single `earth` flavor looks
redundant, and removing it would rename every task and output path this project is written
against — `assembleEarthRelease`, `testEarthDebugUnitTest`,
`app/build/outputs/apk/earth/release/app-earth-release.apk`, the release asset name, the CI
workflow, and `UPDATE_ASSET`, which the updater matches an asset by. Keep it.

Two facts about the app's identity that predate the split and still hold: the callsign derives from
`ANDROID_ID`, which is scoped per signing key, so a reinstall over the same key keeps the same
callsign; and a duty survives a change of watch face entirely, because its alarms and its vigilance
service belong to the app and not to the wallpaper.

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testEarthDebugUnitTest :app:assembleEarthDebug
```

One product flavor, `earth`, on the `world` dimension — see above for why the dimension stays. It
keeps `com.avdesign.mfd24` so the installed base updates in place. APKs land under
`app/build/outputs/apk/earth/<type>/app-earth-<type>.apk`.

There is no system JDK on this machine — `JAVA_HOME` must point at Android Studio's bundled JBR 21
or nothing builds.

The toolchain versions were **chosen to match what is already in the local Gradle cache**, so a
clean build works with little or no network: Gradle 8.7, AGP 8.3.0, Kotlin 1.9.22, Compose compiler
1.5.8, wear-compose 1.3.0, watchface 1.2.0. Do not bump them casually.

`cmdline-tools` is empty, so `sdkmanager` is unavailable and no new SDK components can be installed.
Installed platforms: 33, 34, 36. `compileSdk`/`targetSdk` 34, `minSdk` 30.

The POI binary asset is generated by `:tools:poi` from the CSVs in `tools/poi/data` on every build —
never commit `poi_v1.bin`, and never hand-edit it.

---

## Devices

### Emulator

`Wear_OS_Large_Round`, API 30, 454×454 — the same resolution as the real watch.

```powershell
# make the watch face active (works on emulator and real watch)
adb -s emulator-5554 shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE `
    --es operation set-watchface `
    --ecn component com.avdesign.mfd24/com.avdesign.mfd24.MfdWatchFaceService

# change time zone — setprop alone does NOT take effect reliably
adb -s emulator-5554 root
adb -s emulator-5554 shell "service call alarm 3 s16 America/New_York"
```

**`adb emu geo fix` only reaches an active listener.** With no one subscribed the fix is dropped and
`getLastKnownLocation` still returns null, so a position change cannot be demonstrated on the
emulator unless something is mid-`getCurrentLocation`. Time-zone changes work fine; position
changes do not. Do not chase this — it is an emulator limitation, not a bug.

**Starting the emulator, and driving screens that have no way in.**

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Wear_OS_Large_Round -no-snapshot-load -no-boot-anim
```

With `adb root` on the emulator, `am start -n <pkg>/<activity>` **does** launch a non-exported
activity, which is the only sane way to iterate on the report, the graphs and the export screens:
the alternative is the long-press-and-pencil dance, which is flaky and lands on notifications about
as often as on the editor. The same command is refused on the watch, where adbd is not root.

Seeding the wellness face: `mfd24_vital` in device-protected prefs, keys `today` and `yesterday`
holding the packed day (`DayLogCodec` grammar, `f:hr:steps` per bin), plus `recording`, `interval`,
`midnight_up`, `midnight_24`, `sleep_offbody` and the two session instants. Write it, `chown` to
the app's **current** uid — it changes on reinstall — and `am force-stop` in the same `adb shell`.

### Real watch — TicWatch Pro 3 Ultra GPS

`192.168.10.130`, Wear OS 3.5 / API 30 / 454×454, product `rubyfish`.

**The watch now answers on a fixed port: `adb connect 192.168.10.130:5555`.** Its adbd was
switched to classic TCP mode with `adb tcpip 5555` (2026-08-21), which ends the pairing dance and
the port scanning below — reconnecting after a Wi-Fi drop is one command with a known port. The
mode survives sleep but **not a reboot**: `persist.adb.tcp.port` needs root, so after a reboot
ask for the wireless-debugging port once and run `adb tcpip 5555` again. The notes below remain
for that one bootstrap step.

**How that failure looks, met on 2026-08-22.** The watch answered on 5555, then dropped the
connection *between* the `push` and the `pm install`; afterwards 5555 refused, every previously used
port refused, and a full parallel scan of 30000–50000 found **nothing open at all** while `ping`
still got a reply. That combination — host up, no ports — means wireless debugging is off, i.e. the
watch rebooted and lost the tcpip mode, and no amount of retrying will help: the port has to be read
off the watch's own screen. A retry loop is worth running anyway for the ordinary case (a sleeping
watch refuses for a minute and then answers), but stop it once a scan comes back empty.

**Stage the APK, then install as a separate step.** `adb push` to `/data/local/tmp` takes under a
second and **survives everything afterwards** — the file was still there hours later, through the
disconnect, the lost port and a new session. So push first, install second, and a dropped
connection costs a reconnect rather than a re-upload:

```powershell
adb -s 192.168.10.130:<port> push app/build/outputs/apk/earth/release/app-earth-release.apk /data/local/tmp/mfd24.apk
adb -s 192.168.10.130:<port> shell pm install -r /data/local/tmp/mfd24.apk
adb -s 192.168.10.130:<port> tcpip 5555      # put the fixed port back before letting go
```

Wireless debugging is otherwise the only route, and it is fiddly:

- **The port changes every time** wireless debugging is toggled. There are two different ports: the
  *pairing* port (shown with the six-digit code) and the *connect* port. `adb connect` to the
  pairing port always fails.
- Pairing codes expire with the dialog. `adb pair <ip>:<pairport> <code>` has to run while the
  dialog is still open, otherwise it fails with `protocol fault (couldn't read status message)`.
- mDNS discovery finds nothing on this network, so the connect port has to be asked for or found by
  scanning. A parallel TCP scan of 35000–50000 takes about a minute and reliably finds both ports.
- **The watch drops off Wi-Fi when the screen sleeps.** Long `adb` calls die mid-command with
  `error: closed` or `device offline`. Keep commands short, wake the screen first, and expect to
  reconnect on a fresh port.

```powershell
adb -s 192.168.10.130:<port> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 192.168.10.130:<port> shell pm grant com.avdesign.mfd24 android.permission.ACCESS_FINE_LOCATION
adb -s 192.168.10.130:<port> shell pm grant com.avdesign.mfd24 android.permission.ACCESS_BACKGROUND_LOCATION
```

### Driving the settings editor

The list is six sections — duty control, vigilance, sensors, position, display, units — and anything that
fits side by side is a `SegmentedRow` rather than a stack of full-width chips. Order is not
arbitrary: duration comes before the two actions that spend it, scheduling sits inside duty control
rather than in a section of its own, and position comes before Nadir and Weather because both depend
on having one.

**The six sections are collapsible, one open at a time, duty control open on entry.** This changes
how the editor is driven: collapse whatever is open and the headers sit at fixed, predictable
positions — roughly y = 140, 227, 315, 400 on a 454 px screen, with sensors and units one swipe
further. Get to a setting by collapsing first and opening the section you want, not by swiping
through the whole list. `DONE` is a screen and a half from the top instead of eight.

Blind tapping fights back here: a tap that misses a header by half a row lands on the section above
or below and opens *that*, which looks like nothing happened until you read the next screenshot.
Screenshot, read the positions, tap once, screenshot again — batching taps between screenshots is
how a session ends up three sections away from where it thinks it is.

Section state is not persisted, on purpose: a fresh `WatchConfigActivity` every time means the list
always starts in the same shape. Do not "improve" that by remembering it — the geometry would then
depend on invisible history, which is exactly what makes blind tapping unreadable.

There is no way to script it: navigation is blind tapping on a `ScalingLazyColumn`. Screenshot
between every step and read the positions off the image. Opening it takes a long-press on the face
followed by a tap on the pencil, and it is flaky — retry in a loop until `dumpsys activity
activities` shows `mResumedActivity: … WatchConfigActivity`. Grep for the **resumed-activity line**,
not for the class name anywhere in the dump: the activity sits in the stack for a while before it is
resumed, and matching the bare name reports success while SysUI is still on screen. On the emulator
also set `screen_off_timeout`, or it dozes mid-sequence and the next screenshot is the watch face.

**The long press has to be a real one, and `input swipe` is not it.** `input swipe X Y X Y 900`
with identical coordinates does nothing the picker notices, however long the duration — the sequence
that works is a held touch inside **one** `adb shell`, then the pencil:

```powershell
adb -s <dev> shell "input motionevent DOWN 227 227; sleep 1.2; input motionevent UP 227 227; sleep 2; input tap 227 415"
```

The picker shows the face with the pencil at about (227, 415) on a 454 px screen. Expect to repeat
the whole thing three or four times — the first attempts open the picker and then fall back to the
face — so wrap it in a loop that checks the resumed activity after each round. Two related things
that are **not** bugs: `am start -n …/.editor.WatchConfigActivity` finishes immediately and leaves
SysUI resumed, because there is no `EditorSession` outside the picker's flow; and the picker's own
preview renders a fixed representative instant (a date months away), because it is a headless
instance.

Blind tapping *will* change settings you did not intend. Several surprises during development
(palette flipping colour, a 13-hour watch length) were mis-taps, not bugs. Verify with a screenshot
before diagnosing. The user is happy for the watch to be driven this way — it is a test device — so
tap through new UI there rather than trusting the emulator, and put the settings back afterwards.

Two things that cost time before they were written down:

- **Row positions move between openings.** The list does not always restore to the same scroll
  offset, so coordinates read off one session's screenshot miss by half a row in the next. Take the
  screenshot, then tap, every time.
- **A vertical swipe sometimes dismisses the editor** rather than scrolling it, on the emulator more
  than on the watch. Check `dumpsys activity activities` after a scroll before trusting the next
  screenshot; the face underneath looks nothing like the list, so it is obvious once you look.

Screenshots off the watch have to go through a file — `adb shell "screencap -p /sdcard/s.png"` then
`adb pull` — because `exec-out screencap` returns a corrupt PNG when the watch is dozing. Under Git
Bash, `export MSYS_NO_PATHCONV=1` first or `/sdcard/...` is rewritten into a Windows path.

---

## Invariants — break these and something subtle goes wrong

**`render()` allocates nothing.** Every `Paint`, `Path`, `RectF` and buffer is built in
`Geometry.rebuild()` or a layer's constructor. Text goes through `Canvas.drawText(char[], …)` fed by
`TextBuf`, which formats digits by hand. No `String.format`, no concatenation, no boxing, no lambdas
in the drawing path. The framework's own `ZonedDateTime` is the single unavoidable allocation.

**Everything time-related is stored as absolute epoch millis.** Shift start/end, sunrise/sunset. The
UTC offset is applied only when mapping to a dial angle. This is what makes a shift survive a
time-zone change, and `TimeZoneShiftTest` pins it.

**The transition eases the offset at two moduli, not one.** `DialTransition` carries
`hourOffsetMillis` (short way round a **day**) and `minuteOffsetMillis` (short way round an
**hour**). Collapsing them into one number spins the minute hand a full turn per hour of change and
the seconds cursor eight times faster still. `DialTransitionTest` measures travel distance
specifically to stop this coming back.

**The frame rate is dynamic, and `render()` sets it.** `IDLE_FRAME_PERIOD_MS` is 1000 and
`ANIMATING_FRAME_PERIOD_MS` is 16; the renderer writes `interactiveDrawModeUpdateDelayMillis` every
frame from `DialTransition.animating`. The library recomputes the delay *after* each render, so the
change lands on the next frame, and it snaps any period of 500 ms or more onto its own boundary —
which is what keeps the seconds cursor on the second. Do not pin this back to 16.

**Both draw modes go through `drawFullFace`.** Always-on is the interactive face minus the seconds
cursor and minus the background wash, not a separate drawing. Keep them sharing one path: the reason
the readout and the dial cannot drift apart between modes is that there is only one of each. The wash
is dropped because it is a third of the emitted light and carries no information — measured 29.9 to
15.4 mean luminance on the watch, for a 17.5 % to 15.4 % change in bright content.

**One palette, two brightnesses.** The palette is three blue-free hues and always-on is
`Palette.updateAmbientFrom` — the *same* hues scaled to `AMBIENT_LEVEL`, on a black ground. Dim by
scaling channels, never by alpha: alpha composites badly where antialiased strokes overlap, and the
hue has to stay put or waking would have something to cross-fade. That single decision is what makes
`WakeTransition` a brightness ramp and nothing more; an earlier version swept colour and brightness
as two fronts and drew the whole face twice per frame to do it.

There is no separate always-on tint, no watch-arc colour and no watch-arc width. All three were
menu items that changed nothing you could see — the arc pair because the arc is only drawn while a
shift is running, which is most of the time not the case, so they read as broken rather than
optional. The arc takes the accent hue and `StyleSchema.ARC_WIDTH_FRACTION`.

**Always-on does not drift.** `ambientPalette` is a separate `Palette` instance so the layers keep
reading plain `Int` fields. The dial scale is drawn *live* in ambient through
`DialLayer.drawScaleDirect` rather than from the cached bitmaps, because it needs a different palette
and ambient renders once a minute — caching a second pair of 454x454 bitmaps would cost memory to
save time that is not scarce. `DaylightLayer` takes its colour as a parameter, because the band
follows the palette: a fixed teal clashed with every hue but the one it was picked against, and had
blue in it besides, so always-on could not have used it anyway.

Burn-in is handled by having no blue and, optionally, by `AmbientLayer.applyHalfDensity` — a 2x2
checkerboard punched with `DST_OUT`. **Its phase must keep alternating**; a fixed checkerboard just
burns in a checkerboard. Do not reintroduce a moving frame: a ring walk slid visibly for minutes at a
time and the user spotted it inside an hour. `ALWAYS-ON > AUTO` drives the same checkerboard from
the daylight window — thinned after sunset, never during an active watch, solid without a position —
and the rule is the pure `render/AmbientAuto.kt`, pinned by `AmbientAutoTest`.

**Low-bit panels keep `AmbientLayer.draw`,** the sparse face. Intermediate alphas cannot be shown
there, so graduated rings and haloed type become noise. `AmbientLayer` owns the burn-in drift for
the sparse face only — four positions, one step a minute. The full face does not drift at all:
under 24 numerals and four rows of type any useful drift is a drift you can watch, so it leans on
the blue-free palette and the checkerboard instead.

**The hour hand is never inside a booked arc.** That is the property, not the threshold, and
`WatchShiftState.pendingArcVisible` exists to hold it: the arc waits until the start is one turn of
the dial *less the watch's own length* away, less `ARC_CLEARANCE_TURNS` (an hour of dial) for
clearance. The length has to be in it — the hand stands inside the span exactly while the start is
between one revolution and one revolution minus the length away, so a fixed twenty-five-hour lead-in
left a sixteen-hour watch with the hand sitting inside its booked arc for sixteen hours. Counted in
turns, not hours, because a sol is longer than a day. `MfdRenderer` folds it into `showDutyArc`, and
`PendingArcTest` sweeps three days at ten-minute steps asserting the hand is outside the span every
time the arc is shown, rather than restating the constant.

**No watch arc on the Moon.** A lunar day is a synodic month, so an Earth shift is a few degrees of
dial. `showWatchArc` in `render()` gates both the interactive and the ambient arc; the duty readout
is deliberately unaffected.

**A booked start can never be in the past.** `WatchShiftController.earliestBookableStart` is the
floor — now rounded up to the five-minute step — and the editor is the one caller of
`schedule()`: both schedule steppers and ARM TIMER clamp to a **live** `System.currentTimeMillis()`,
not the composition's 20-second `nowMillis`, because `bookedStart` is remembered with no keys and
the editor can sit open. Unclamped, a past start falls through `schedule()` into an immediate
start with the chime — indistinguishable from a mis-tap. `BookableStartTest` pins the floor.

**The updater trusts the platform, and only the platform.** `update/ReleaseCheck` asks
`releases/latest` (flavor-scoped via `UPDATE_TAG_PREFIX`/`UPDATE_ASSET` buildConfig fields, so an
Earth watch never sees a Mars build), compares versions numerically per component — a string
compare buries `2.10.0` under `2.9.1` — and `UpdateInstaller` streams the APK into a
`PackageInstaller` session: the system prompt confirms, the package manager enforces the signing
key. One cached build in `cache/updates/`, pruned to the latest. The daily check rides
`TelemetryWorker` (gated inside `UpdateNotifier`, never failing the weather), and the
notification fires **once per release**, keyed on the stored version, not on time. Platform
findings from the API 30 Wear emulator: there is **no** `MANAGE_UNKNOWN_APP_SOURCES` activity
(the raw intent throws `ActivityNotFoundException` — caught, flow proceeds), the installer's own
SETTINGS button dead-ends too, the grant lands only via
`appops set com.avdesign.mfd24 REQUEST_INSTALL_PACKAGES allow`, and the final confirm is refused
outright with "Install/Uninstall actions not supported on Wear". On the real watch the *check*
path is verified (2.2.0 asked GitHub and printed "Up to date"); whether Mobvoi's installer allows
the final commit cannot be known until the next release offers itself — the docs promise the
install only "where the platform allows".

**A face is announced when it is built, and not before.** The rule as first written — nothing in
the repository names anything unshipped — came from a README family box, a plan in these notes and
a repository description that all named faces which did not exist. That is still the thing being
prevented: **a roadmap in a public repo is a roadmap for whoever forks first**, and a placeholder
flavour named after an idea is the worst of it.

What changed on 2026-08-25, at the user's direction, is what counts as unshipped. `mars` and
`vital` are complete faces that build, test and run on the watch; both are pushed and both are
described in the README. So the line is **working code, not intentions**: a face that exists on a
branch and can be installed may be public, with its documentation and its screenshots; an idea, a
plan, a reserved flavour or a name with nothing behind it stays off-repo. The shared arithmetic in
`astro/` was always on the right side of that line — engine, not announcement.

**The graphs screen shows the same twenty-four hours the rings do, and says so with hands.**
Three decisions, each of which replaced something that had looked reasonable:

- **The window rolls.** Midnight-to-midnight of today cut a night that began at nine in half —
  somebody who went to bed early opened the graphs in the morning and found the sleep and the
  evening pulse missing, which is what they had opened them for. It is now today up to the hand
  and yesterday ahead of it, the same composition `VitalMonitor` publishes for the rings.
- **Two schematic hands** at the hour and minute, through `AstroTime.hourHandAngle` and the
  stored `DIAL TOP`, so the seam between today and yesterday is *where the hand points* and
  nothing has to be counted round from midnight.
- **One grammar for all three panels: a line.** Pulse was ninety-six marks (the eye joins them
  wrongly at this size), activity was bars, sleep was banded arcs — three instruments on one
  screen, and the first second of reading went on working out which was which. Now each panel
  fills a radius-and-colour per quarter-hour and one renderer strokes a quadratic through each
  point, from midpoint to midpoint, so the pieces meet tangentially and read as one stroke while
  the colour still changes with the value.

**What the line must never do is bridge a hole.** It is drawn only between two quarter-hours that
were both measured, and it breaks again at the seam, where adjacent array entries are a day apart.
That is the same rule the separate marks were protecting, and it is the reason the platform's thin
hours still read as absence. A sampled quarter-hour with a value of zero is *not* a hole — on the
activity panel it sits on the inner circle, because it is a measurement.

**The pulse axis is the day's own range, on a logarithm.** Fixed 40–150 had both faults at once: a
quiet day flattened into a circle using a fifth of the panel, and a run at 170 was *clamped* — the
peak pressed against the rim, the graph lying about the one moment anybody would look for. The
ends now come from the day with four beats of air either side, a floor of 25 beats of span so
sensor noise cannot look like a day, and the mapping is logarithmic because a pulse is a ratio:
50→60 deserves the same distance as 100→120. A guide circle outside the day's range is not drawn
at all — the empty 120 ring was two thirds of the panel on a quiet day and it was what pushed the
line into the middle.

**The rings leave half an hour clear ahead of the hand.** `TRAIL_SKIP_BINS` = 2, wrapping past
midnight, applied to all three rings by `ActivityTrailLayer`'s skip window. The rings carry a
rolling twenty-four hours, so without a seam the arc runs straight past the hand's tip into
yesterday's quarter-hours and nothing on a full circle says which end is now. Drawn as absence
rather than as a mark, because absence is the one thing these rings already mean.

**A declared night is the model's one input that is not a measurement.** `TRACK SLEEP` in the
recorder section writes two instants into `VitalStore`, and `SleepModel` reads them as bin indices
of the pair (`DayBins.pairBin`, `VitalStore.sleepSessionBins` — one converter, because the face,
the report and the graphs must agree on which night is which). Inside a session the night window
stops applying, the opening threshold relaxes to `QUIET_MAX_STEPS`, and off-body stillness counts
whether or not the standing switch is on. It exists because two pairs of cases are identical to a
wrist and obvious to a wearer: a film on the sofa against an early night, and a bedside table
against an empty room. Sixteen hours caps a session nobody closed.

**The sleep model was tuned against one real night, and the raw export is why that was
possible.** 25 August 2026: the wearer was in bed midnight to 07:30 and the report said
`Slept 1:30, 2 wakes`. Three separate faults, none of which could be seen from the report and all
of which were obvious in the grid once `EXPORT RAW` could put it on a desk — scan the QR with
`pyzbar`, pipe it through `tools/vital/decode_day.py`, read the CSV:

- the pulse ceiling sat at resting + 10 % (69 bpm against a resting 63) and vetoed ordinary light
  sleep in the seventies. Now `AWAKE_MARGIN_PCT` is 45.
- `QUIET_MAX_STEPS` was 20, and quarter-hours carrying 22, 41 and 43 steps — an arm under a duvet —
  ended the night three times. Now 60; a real trip out of bed is a hundred and more.
- **a quarter-hour nobody watched was charged to the wearer as fifteen minutes out of bed.** Four
  dozed-through samples became four wakings and an hour off the total. Bridged bins now count
  toward the time awake only if they were actually sampled, which is what the KDoc claimed all
  along.

Two more followed from a **staged** day rather than a real one, which is the argument for keeping
a believable seed around: a quiet evening at sixty steps a quarter-hour opened a night at eight in
the evening and ran it to morning, and a sedentary desk morning came back as ninety minutes of nap
drawn in the deep band. So a run now *begins* on `QUIET_START_STEPS` (20) and only *continues* on
`QUIET_MAX_STEPS` (60); outside the night window a candidate needs a real reading within
`NAP_MARGIN_PCT` (15 %) of the floor; and the loose night rule applies to a *new* run only inside
`SLEEP_START_TO_MINUTES` (06:00), so a night that runs to half past nine keeps it while a Tuesday
morning does not.

After all three of the first set: `Slept 8:00, 1 wake` against a true 7:30. The half-hour over is the far edge —
a still, quiet hour after waking reads as more sleep — and it is the deliberate side to be wrong
on. `the night of 25 August, as the watch actually recorded it` keeps the whole grid as a test.

**Anything drawn outside the face has to be told the face's orientation.** The graphs, the
report and the export are plain activities with no `EditorSession` and no style of their own, so
the renderer publishes `DIAL TOP`, the midnight label and the off-wrist switch into `VitalStore`
from `applyStyle` (through `onVitalStyle`, since a `Renderer` has no Context). A round graph drawn
midnight-up beside a dial drawn noon-up is one the reader has to flip in their head, and it looks
like a bug in the data rather than a difference of convention.

**The ring's leading edge is painted from the live reading, not from the last written bin.**
`MfdRenderer` overwrites the current bin's pulse and activity colours each frame when there is a
heart rate on the dial, and restores that bin from the record when the reading goes away
(`liveBin`). Without it the tip of the ring is a faint placeholder until the quarter-hour closes,
while the pictogram beside it is bright coral — and the pictogram's whole job is to say *this
number is that colour*.

**Sizes for the raw export, measured rather than guessed** (`RawDayCodecTest`): 388 bytes a day
fixed, ~180 deflated, ~240 as Base64, ~280 as a whole packet. Two days fit one QR code with room
over; a week is ~1.1 kB against the encoder's 858-byte ceiling, which is why the chip offers two
days and not everything. A bespoke delta-and-run encoding was written and thrown away — it beat
deflate by a few dozen bytes and needed a decoder nobody else has, where deflate is
`zlib.decompress` everywhere.

**A stored finding must be re-tested against the build reading it.** `UpdateStore.pendingVersion`
filters through `ReleaseCheck.offerable`, which returns nothing unless the stored version is
strictly newer than `BuildConfig.VERSION_NAME`. Without it the ABOUT chip advertised a *downgrade*:
seen on the watch at 2.6.1's predecessor — 2.6.0 installed by hand, the last check's finding of
2.5.1 still on file, `UPDATE AVAILABLE` lit, and the QR pointing at a release the wearer had
already passed. The check runs once a day and never during a watch, so a hand-installed build
leaves the old answer standing for up to a day; deciding it at read time means it self-corrects
with no network and no waiting. `ReleaseCheckTest` pins it.

**Wear OS 3 will not let an app install an app — settled, on hardware, and the feature was cut to
match.** The session commits and `STATUS_PENDING_USER_ACTION` arrives; the platform's own
`PackageInstallerActivity` then answers "Install/Uninstall actions not supported on Wear".
Confirmed on the API 30 emulator **and on the TicWatch Pro 3 Ultra (Wear 3.5)** on 2026-08-22.
Every route round it is shut to an ordinary app: the confirmation cannot be skipped without a
system install permission; `pm install` cannot read a file an app can write, because SELinux denies
the system server read on `/sdcard` (`avc: denied { read } … tcontext=u:object_r:fuse:s0`) — the
error text itself points at `/data/local/tmp`, which an app cannot write to; and device-owner
provisioning is neither available on a paired watch nor proportionate. **`adb push` to
`/data/local/tmp` then `pm install -r` does work**, and is how builds go onto the watch.

So 2.6.0 deleted the lot: `UpdateActivity`, `UpdateInstaller`, `UpdateResultReceiver`, the download
cache, the rollback copy, the settings-list reminder — and the **`REQUEST_INSTALL_PACKAGES`
permission**, which this app asked for and could never use. What is left is what a watch can
honestly do: `UpdateNotifier` asks once a day, off duty, when `RELEASE_CHECK` is on; one
notification per release; and `ABOUT → RELEASES` names the version and opens `ReleaseLinkActivity`
— the release page as a QR, the same idiom the incident log leaves by. The notes live on that page,
in a browser, at a readable size. Two seconds with a phone beat two minutes of scrolling a wall of
text on a 1.2-inch screen, which is what the old screen was.

**Declare scratch fields above the `init` block that uses them.** Kotlin runs initialisers in
source order: `VitalMonitor.init` calls `republish`, so an array declared *after* the init block is
still null on the first publication. On a debug build that is a `NullPointerException` with a
readable name; on a release build it arrives as
`Parameter specified as non-null is null: method c2.L.b, parameter out` and takes the watch face
and the recorder down at start — the face cannot even be selected. Cost one install to find,
2026-08-26, and it is the second entry in these notes whose lesson is *the release APK on the
watch is the only gate that counts*.

**A release build is the only place minification bugs live.** Selecting the steps slot crashed the
face on the watch while every emulator check passed, because debug builds are not minified. R8
renamed the fields proto-lite looks up by name, Health Services' client threw inside a static
initializer (`Field name_ for m1.A not found`), and that arrives as an **Error** — `catch
(Exception)` let it through and `onVisibilityChanged` took the process down. Two fixes, both
needed: keep rules in `proguard-rules.pro` for the protobuf and Health Services classes, and
`catch (Throwable)` in `DailySteps`, because an optional row's provider must never be able to kill
the dial when a fallback is one line below. **Anything reflective has to be exercised on a release
APK before it ships** — that is now the rule.

**The platform's daily step total is not reachable on this watch.** The Health Services passive
subscription binds (confirmed in `dumpsys activity services …healthservices`: our package is the
calling client) and then delivers nothing, so what the row shows is our own count since the slot
was switched on — the user walked seven steps and saw seven, with a real daily total far higher.
2.6.4 logs the provider's advertised capabilities and every value it does deliver, which is the
evidence needed before deciding whether the dependency earns its place. What is certain either
way: from the next local midnight the counter-and-baseline path is exact, because the boundary is
recorded; only the installation day is unknowable, and a zero invented on that day is no longer
published.

**And the evidence that looks like it settles this does not — a mistake made and corrected on
2026-08-22.** Minutes after 2.7.0 was installed the log read
`DailySteps: daily steps from the platform: 480`, with `STEPS_DAILY supported: true; provider offers
11 passive types`. That looks conclusive — a fresh install cannot have counted 480 steps — and the
notes were rewritten to say the provider does deliver the day's total. **It proves nothing**, as the
user pointed out: the previous build had been subscribed for a day, the provider accumulates
independently of *this* install, and the user had been walking. 480 fits "the day's real total" and
"everything since the previous subscription started" equally well, and the rewrite was reverted.
Only one experiment separates them: uninstall the app, leave the watch alone for half a day of
walking, install, and read the **first** figure it shows. Until somebody runs that, the honest
wording is the one in the README footnote, and this is the standard to hold: an observation that two
explanations fit is not a finding.

**Steps come from the platform, not from us.** `DailySteps` subscribes to Health Services'
`STEPS_DAILY`, which is the figure the rest of the watch shows and is accumulated whether or not
this face was installed. The old counter-and-baseline path (`stepsToday`, `baselineFor`, still
tested) is the fallback for builds without the service — and a second fallback arms after 30 s if
the subscription is accepted but never delivers, because dashes for ever read as a broken feature.
The bug this fixes was the first thing every new wearer met: walk four thousand steps, install the
face, read `0`. Dashes until the first real figure, never a zero standing in for one. This is the
project's one added dependency (`androidx.health:health-services-client`); it was not in the local
Gradle cache and had to be fetched.

**The open section is outlined, and the outline arrives as brightness.** `SectionHeader` strokes
the whole stadium and ramps the hue's channels over `WakeTransition.DURATION_MILLIS` — the wake
sweep's own half-second, read from its constant so the two cannot drift. It was first built as a
line that drew itself clockwise; a moving end reads as an animation rather than as a state, and
the eye follows the motion instead of the row it marks. A fade is the gesture the face already
has, so it is learned once and met twice — and it dims by scaling channels, not by alpha, for the
same reason the palette does. Two details that cost a rebuild each: the outline must come **after** `.background()`
in the modifier chain or the opaque fill paints over it, and the first composition **snaps**
instead of animating, or opening the editor replays a second of drawing for a choice nobody made.
The accordion's pin now re-anchors for six frames rather than one: correcting after a single frame
still let one frame of the jump reach the screen, which is exactly what the eye catches.

**A served watch retires an hour after it ends.** `WatchShiftState.dutyState` returns `DUTY_OFF`
past `end + SERVED_VISIBLE_MILLIS`, which takes the grey arc and the duty row together — they are
the same claim. Derived from the clock rather than fired by anything, so a process asleep across
the boundary comes back to the right answer and nothing has to run at the hour mark; the instants
stay in storage untouched. This reverses the earlier "a served shift is history worth seeing":
an hour is how long that is true for, after which it is furniture sitting where the next watch's
arc goes. `ServedWatchTest` pins the boundary.

**The incident log covers one watch, and says which.** `VigilanceMonitor.noteShiftStart` empties it
when the shift start differs from the one stored in `VigilanceStore.logShiftStart()` — stored, not
inferred, and that is the whole point: the renderer reports the shift start on the first frame after
any restart, so comparing against an in-memory value would wipe the log of the watch *under way*.
The watch's start, end and length are kept beside the log and printed above it in the editor and as
the packet's `WATCH` line, because a bare instant is detached from the shift it happened in. The
32-entry ceiling and 30-day ageing stay as backstops beneath the per-watch rule. This is a
deliberate product decision by the user, stated plainly in the README: an instrument for people who
stand watches, not a certified recorder — a kept journal would be a different feature with
different promises, and anything wanted from the current log leaves via `EXPORT LOG` before the
next watch begins.

**The SOS is a burst pattern, and the arithmetic is `SosSchedule`.** Thirty seconds of calling,
then a doubled SOS a minute apart, four times — five bursts plus the nudge, about 4 min 56 s end to
end, and *silent between bursts*. The old shape was 56 continuous cycles over five minutes, every
one of them holding the processor awake and driving the vibrator: the largest single draw this face
could produce, spent on the one occasion the battery must not go flat. `SosScheduleTest` pins the
shape, including that most of the escalation is silence, because the alternative to a test here is
sitting next to a wrist with a stopwatch.

**The SOS is felt once, then sounded twice — and the tone generator is built once.** Both halves
fix the same bug: the SOS could not be heard. A vibrating case drowns its own speaker, so the beats
can no longer share a timing table with the buzz; and the old code built a fresh `ToneGenerator`
per cycle, which is exactly the trap `Alerts` documents — on this watch the speaker path powers
down, and constructing a generator and calling `startTone` in the same breath loses the mark. One
generator per run, built at `start()` with the first mark a beat later. `SosSignal` also logs the
alarm stream's level and never raises it: a monitor that turns the wearer's volume up behind their
back is a monitor they switch off. Verified on the emulator — `alarm stream at 5/7, tone volume
100`, then `no answer after 5 bursts` 4 min 56 s later.

**A pulse in the log is two numbers and an instant, or nothing.** `LOG PULSE` records the heart
rate during the unanswered check *and* the last reading taken while the operator was moving, with
the instant of that reference — a lone pulse is unreadable, because 48 bpm is an athlete asleep or
a casualty. The sensor runs in exactly two windows (`sampleHeartRate`, self-cancelling through its
own `Runnable` token) and never for the life of the service: it is an LED against skin. A missing
reading is `NO_BPM` and prints nothing anywhere — never a dash, which reads as a measured zero.
The row is offered only once `BODY_SENSORS` is granted, since a watch face cannot request it.

**The incident log grew a suffix, not a new shape.** `IncidentLog` packs
`at[:bpm[:base:baseAt]]`, comma separated, and still parses the bare instants every release before
2.3.0 wrote — the log lives in device-protected prefs and is never migrated, so a parser that could
not read the old form would silently empty the record on update. `IncidentPulseTest` pins that, and
that a record with the setting off packs exactly as compactly as it used to. The renderer keeps a
separate `incidentTimes: LongArray` published beside the records, because `render()` allocates
nothing and the marks are walked once a frame.

**The POI sources are half generated.** `airports.csv`, `ports.csv` and `spaceports.csv` are
curated; `navalbases.csv` and `heliports.csv` come from `tools/poi/build_poi.py` reading an Overpass
snapshot that `tools/poi/fetch_osm.py` puts in `tools/poi/raw/` (gitignored). Edit the generator,
not the CSV. OSM is ODbL — the attribution in the README is an obligation, not a courtesy.

**Rank beats distance in the site lock, and a helipad ranks below a port.** `PoiFormat.priorityOf`
takes flags as well as type for exactly that. Ranking helipads with the aerodromes they share a type
with made Toulon report a naval air station four kilometres away instead of the naval base half a
kilometre away.

**Military is carried by colour, not only by shape.** `SiteGlyph.isMilitary` drives the site
glyph's paint to `palette.second`; the reference-frame symbol on the row above stays on `lume`, so
`drawGlyphedLine` takes a colour rather than a palette. Encoding ownership in the silhouette alone
does not survive 22 px — and cannot work at all for the spaceport, which draws one shape either way.

**Judge a glyph at 22 px, never at 100.** `python tools/poi/preview_glyphs.py` parses
`Glyphs.kt` itself and renders every silhouette at true size next to a magnified copy of the
same raster. It reads the Kotlin rather than restating the shapes so the preview cannot
reassure you about a glyph that no longer exists.

**Site pictograms come from type *and* flags**, through the pure `render/SiteGlyph.kt` — helipad
outranks military, spaceports ignore both. `SiteGlyphTest` enumerates it. The silhouettes are
authored in a 100x100 box from clockwise sub-paths only, so non-zero winding gives their union; only
the moon glyph needs a real `Path.op`. They are drawn at about 22 px, which is why the pairs that
must be told apart differ by where the *mass* sits, not by fine detail. The spaceport is drawn as
a launch *complex* — vehicle, service tower, ground line — rather than a bare rocket: a rocket is a
pointed body with fins at the bottom, and so is a delta-wing fighter. It is the only asymmetric
glyph in the set, which is what separates them at 22 px.

**A hand-typed position never touches the device-fix keys.** `TelemetryStore` keeps them apart and
`TelemetryRepository.adoptBestPosition()` is the single place that decides which is in force. They
used to share a key, which made `hasCachedPosition()` lie and gave the resolve-distance gate a
fictional origin. The source is chosen by the user (`manualPositionSelected`), never inferred from
the OS permission — an app cannot revoke its own location permission on API 30, so inferring it left
the editor with a switch that could not switch anything off.

**Manual positions step in hundredths and do earn the site lock.** At 0.01 degrees the quantisation
is about 1.1 km, comfortably inside the 5 km radius; at tenths it was 11 km, which is why the row
used to be withheld. The 500 m gate therefore measures from where the search itself last ran,
device or manual — `ResolveOrigin`, whose one rule is that only `resolveSite` writes it: written
from the check it creeps under a slow drift, skipped for the unset case it never arms at all, and
both have happened. `ResolveOriginTest` pins the rule.

**The duty duration is not in the style schema.** It lives in `WatchShiftController`'s own
device-protected prefs beside the shift itself, because it is timer configuration: a schema change
invalidates the stored style, and a custom length has to survive both that and a trip through the
presets. `WatchShiftController.clear()` therefore removes the four shift keys by name — an earlier
`prefs.edit().clear()` would have taken the duration with it every time a shift was cancelled.

**The vigilance sample rate is set by the noise, not by the band.** 50 Hz looks like eight times more
than a 3 Hz band-pass needs, and the accelerometer will run at 13. Do not take it: at 13 Hz, Nyquist
is 6.5 and the 12 and 14 Hz engine noise `MotionFilterTest` is required to reject folds onto **1 Hz**,
the centre of the arm-movement band. Rejection would then depend on the sensor's own anti-alias
filter, which cannot be seen or tested from here, and the failure direction is a monitor reporting an
unconscious operator as awake. `the sample rate resolves the noise it has to reject` asserts the rule
and reads the shipped constant, so lowering it fails the build. Verified by setting it to 13 Hz: that
test plus three real filter tests go red.

**Vigilance holds the wake lock only while an answer is owed.** Armed, nothing is held: the end of
the interval is an `AlarmManager` alarm and the accelerometer batches into the hub's FIFO with 20 s
of report latency, so the processor sleeps through the interval. The lock is taken in `prompt()` and
released by the next `arm()`. Confirmed on the watch with `dumpsys power | grep ACQ mfd24:vigilance`
returning nothing while armed, and `dumpsys sensorservice` showing the registration as
`sampling_period 20.0 ms, batching_period 20000.0 ms`. Do not reintroduce a permanently held lock —
and note the platform will not run an exact alarm more often than every nine minutes in Doze, so the
five-minute interval can stretch.

**No hardware wake-up sensor is usable for this.** The watch exposes Significant Motion and a
wrist-tilt detector, both `wakeUp` and both free in power terms, and both fire continuously in a
moving vehicle — which reports an unconscious operator as awake. There is no wake-up accelerometer,
and no `TYPE_LINEAR_ACCELERATION` at all: `dumpsys sensorservice` lists no fusion sensors.

**Sensor slot pictograms live in `Glyphs.kt` and in the preview tool.** `heart` and `pedestrian`
are authored in the same 100 x 100 box as the site silhouettes and listed in
`tools/poi/preview_glyphs.py`, which parses `Glyphs.kt` itself — add a glyph without adding it there
and the preview will go on reassuring you about a set that no longer matches. They are drawn to the height of the
digits beside them (`SENSOR_TEXT_SIZE` x `SENSOR_GLYPH_RATIO`), smaller than the site set's 22 px,
which only works because a heart and a walking figure are shapes the reader already holds. Station
pressure keeps letters on purpose.

**A slot is one line at one size**, and the glyph cache is keyed on the *position* as well as the
reading: the pair is centred as a unit, so a pulse going from 99 to 100 widens the line and moves
the glyph. Widths come from `MONO_ADVANCE`, not from measuring — the same 0.60 em the whole width
budget is written in.

**The barometer's first reading is a register default, not a reading.** On this watch
`TYPE_PRESSURE` put **2048.0 hPa** on the dial, and the number says what it is: the LPS22HH reports
pressure as a 24-bit value divided by 4096, so `0x800000 / 4096` is exactly 2048.0 — half scale,
which is what the register holds before the first conversion. `dumpsys sensorservice` shows the
sensor settling to a sane 993.6 hPa once it is running. `SensorSlots` gates the reading on a
plausible range and shows dashes otherwise, the same way a heart rate of zero is treated as "not
locked on" rather than as a pulse.

**The battery receiver is registered from `createWatchFace`, not `onCreate`, and only when not
headless.** Preview instances are created and destroyed constantly by the picker and the editor
through `WatchFaceControlService`, and their teardown does not run this service's `onDestroy`; a
receiver registered in `onCreate` therefore leaked a dispatcher per preview, which the platform
reports as `IntentReceiverLeaked` against `WatchFaceControlService`. Anything else registered for the
life of the face has the same trap.

**The sensor slots run only while the screen is on — and always-on does not count.**
`watchState.isVisible` stays true in ambient: the face is on the screen, dimmed, once a minute.
Gating the slots on visibility alone left the heart-rate LED lit around the clock, and the watch
said so plainly — `dumpsys batterystats` blamed our uid for **15 h 25 m of sensor 0x64 out of
15 h 29 m on battery**, with the screen interactive for eleven minutes of it, and
`dumpsys sensorservice` showed the PPG running at 66.7 ms. The gate is now
`isVisible && !isAmbient`, in both the style callback and the collector. When reading that dump:
batterystats prints sensor handles in decimal, `sensorservice` in hex — 100 is 0x64 heart rate,
105 is 0x69 step counter, 108 is 0x6c the off-body detector.

**The original rule, which the above is the correct expression of:** `SensorSlots.configure` is driven from
`MfdWatchFaceService`, from both the style (via `onSensorSlots`, reported on a change only) and
`watchState.isVisible`. Heart rate is an LED against the wrist, so a frame must never be able to
switch it on and a headless preview must not either — the callback is a no-op for headless
instances. Two of the three readings need a runtime permission the watch face cannot request, so the
editor asks for it the moment the slot is set, and the row shows `--` until it is granted. Blood
oxygen is *not* offerable: `android.sensor.ppg_spo2` is a vendor type with no public constant and it
only produces a value when Mobvoi's own app measures.

**The step baseline has a reboot case.** `SensorSlots.baselineFor` is pure and tested because a
counter that comes back *below* its own baseline means the hardware counter restarted without the day
doing so, and without that case the row reads negative until midnight.

**The hub ring dims when vigilance is suspended, not when it is off.** `MfdRenderer` only sets
`vigilanceSuspended` when vigilance was actually asked for, so a face that does not use it keeps the
accent hub it always had. An empty core cannot carry this on its own — that is also what a monitor
that has just been answered looks like.

**Incident marks are drawn in `palette.lume`, haloed on the background** — not the accent, which is
what the arc itself uses, so the mark read as a slightly brighter piece of the band. The palette
guarantees the accent never shares the lume's hue; that guarantee is the strongest contrast the dial
has and the mark is what spends it. A third hue is not available: no blue, and always-on dims these
two and nothing else.

**The incident log is readable only from the editor.** The vigilance section lists it, newest first,
with `CLEAR LOG` under it. There is no other way in on a release build — the record is in
device-protected preferences and `run-as` needs a debuggable package.

**An incident belongs to the watch it happened on.** Two records, deliberately apart in
`VigilanceStore`: the incident *in force* — what the dial shows and what holds the monitor down —
and the *log*, which nothing clears but its 32-entry ceiling. Starting a new watch retires the one in
force, and it has to happen in **two** places: `VigilanceService.retireIncidentBefore` for the
START_STICKY restart path, and `VigilanceMonitor.noteShiftStart` for the case where vigilance is
switched off and the service never runs at all. Miss the second and a new shift begins with `MAN
DOWN` still on the dial, a full hub core, and nothing alive that could ever take it down. The
renderer therefore reports a change of `watchShift.startMillis` **even when vigilance is off** —
that clause in `MfdRenderer` is not guarded by `wantVigilance`, on purpose.

**Ending a watch does not clear an incident.** `VigilanceMonitor.stop()` and
`VigilanceService.onDestroy` both leave `status` at `INCIDENT` when there is one on file, and
`VigilanceMonitor`'s `init` restores it from storage so it is on screen from the first frame after a
reboot. A shift that ran out while nobody was answering is exactly the case the record exists for;
blanking the dial the moment the countdown expired would throw it away at the hour it matters most.
`clearIncident()` therefore has a no-service path, or the double tap would start a foreground service
to arm a dead-man's switch for a shift that is not running.

**An incident mark takes `palette.incidentMark`, the third hue** — the blue-free one the palette is
not already spending on the dial and the accent. Green unless the lume is green. It is *not* the
opposite of the dial's colour, which is the trap: under amber the accent and therefore the arc are
red, so a red mark is as invisible there as an accent-coloured one. The mark fills the band's own
cross-section, no overshoot and no halo, and separates by hue alone; both earlier attempts (accent,
then lume-with-halo) failed on the same dial.

**The arc says whether anything is watching.** `vigilanceUncovered` thins the remaining part of the
duty arc to `UNCOVERED_WIDTH` while vigilance is switched on and not watching — charging, off the
wrist, or holding an incident. Distinct from `vigilanceSuspended`, which dims the hub ring and
excludes the incident case on purpose: an incident is not a quiet suspension. Both are gated on
vigilance being enabled, so a face that does not use it never sees either.

**The guided tour writes nothing.** `demo/DemoActivity` renders the production layers over
synthetic values — its MAN DOWN is drawn, not recorded: no shift keys, no incident in force, no
log entry. A marked-then-purged demo entry was considered and rejected, because it would put a
deletion path into the log and a bug in the purge would eat real entries. Every frame carries a
`DEMO` watermark so a screenshot of the tour cannot pass for a real incident. Compressed time goes
through `DemoClock`, whose one promise — a speed change bends the rate, never the needle — is
pinned by `DemoClockTest`. The wrist-answer scene feeds the shipped `MotionFilter`, so the demo
answers to exactly the movement the monitor would.

**The incident log is bounded twice: 32 entries and 30 days.** `VigilanceStore.pruned` ages
entries out on every read — oldest-first prefix drop, pure, no scheduled job — and a backwards
clock jump prunes nothing, because for a record the failure direction is loss. Thirty days is the
uncertified-aid answer: long enough for any debrief or rota cycle, short enough not to become a
dossier on the wearer. CLEAR LOG in the editor remains the immediate door.

**The exported log is signed by a derived callsign.** `export/Callsign.kt` maps a salted SHA-256
of `ANDROID_ID` onto `PREFIX-NN` (twenty prefixes × 99) plus an eight-hex short id; the packet's
second line carries both, and the export screen prints the callsign over the QR. Identity lives
at the exit only — the stored log stays pure instants. The prefix list and the salt are part of
every issued identity: reorder or re-salt and every watch in the field is renamed. `CallsignTest`
pins a golden value computed outside the codebase.

**The editor's scroll boundary is anchored to item 0** (`autoCentering itemIndex = 0`), because
the default anchors to item 1 — the first *row* of whatever section is open — and expanding the
first section then moved the boundary itself: the whole list jumped to the re-clamped position,
and no pin could hold it, because a clamp outranks a scroll. Sections run duty, vigilance,
sensors, position, display, units — the hardware family together, and the headers taper.

**The log leaves the watch as one packet on two channels.** `export/LogExportActivity` shows a
QR of `LogPacket.build()` and plays the same bytes as Bell 202 AFSK (1200 Bd, 8N1 — APRS's
physical layer, `minimodem --rx 1200` on the receive side; async framing, not AX.25, so no TNC is
needed). Read-only by construction. The QR encoder is `export/QrCode.kt`, written in-repo rather
than pulled in: byte mode, level L, versions 1-20, mask 0 fixed — the log is bounded, so the
encoder is bounded to match. Verified end to end: a screenshot of the on-watch screen decodes
with zbar, CRC and all. `Afsk.kt` keeps phase continuous across the tone switch and a whole
number of samples per bit (48 kHz / 1200 Bd = 40); `AfskTest` demodulates the signal with a
Goertzel detector and reads the text back.

**The editor accordion never scrolls on toggle.** Opening a section used to
`animateScrollToItem`, and the list lurched on every tap. Now the tapped header is pinned exactly
where the finger found it — even at the bottom, even though the opened rows stay below the fold —
by re-anchoring one frame after the reflow. Headers carry lazy-list keys because a header's
*index* moves when a section above it collapses; its key does not. Do not reintroduce a reveal
scroll.

**The weather row prefers the locked field's own METAR.** `TelemetryRepository.metarIcao()` gates
it hard: an aerodrome, not a helipad, and a code of exactly four upper-case letters — which works
because the airports data now carries **ICAO only, never IATA**. That was a deliberate re-parse
(OurAirports, matched by code and coordinates within 5 km): the mixed column burned once, with
Hostomel carrying ICAO while Zhuliany carried IATA and nothing able to tell which was which. 86
fields with no ICAO anywhere keep local codes and the gate declines them. Every METAR miss —
absent, stale past 75 minutes, no temperature, closed feed — falls back to Open-Meteo silently;
the fallback is the *local norm*, not a corner case: Ukrainian fields publish nothing in wartime,
verified against the live API. The cache still ages from the fetch, not the observation — a METAR
is up to an hour old at issue, and a cache keyed to obsTime would refetch on every screen-on for
the back half of every hour. `MetarClientTest` pins the parse and the refusals against a real
KJFK payload.

**The daylight band is one arc and one weight: sunrise to sunset, and nothing else on it.** It
reached four weights and came back to one, over two days and three cuts, and the sequence is worth
keeping because each addition was defensible on its own:

- *hour-by-hour cloud shading* — an overcast Kyiv afternoon came out level with its own dusk
  (measured at the band radius, green channel 48 for 15:00 against 47 for 20:00). Cut with
  `render/BandClip.kt`, `render/BandShade.kt` and `DaylightLayer.drawSegmented`.
- *a civil and an astronomical wedge either side* — collapsed to one astronomical wedge, then cut
  entirely on 2026-08-25 at the user's word. The measurement that preceded the cut had them at
  exactly the right instants: drawn 03:57–05:57 and 20:01–22:08 against a computed 03:54 and 22:07,
  with the day 05:57–20:01 against a sunrise of 06:01. **Being right was not the question.**
- with the wedges went `setTwilight`, the four state fields, `Palette.twilightBand` and two of the
  three `SolarTime` passes per refresh.

The rule that came out of it: **the band says where the day begins and ends, and a reader glancing
at a wrist has to get that in one look.** Anything else drawn on the ring is one more thing to rule
out first, however true it is. The duty face never shaded anything and was legible throughout.

**The sky marks sit at hour angles, never at clock hours — and never ease.** The first solar
mark rode the daylight band as a fraction, which is algebraically the clock hour: exactly under
the hour hand, an ornament restating point-the-hand-at-the-sun. Both marks now take
`12 h + HA` — the sun from the daylight window's transit (`AstroTime.apparentSolarDialHours`,
pinned against PyEphem), the moon from the truncated Meeus series in `astro/MoonSky.kt`
(`MoonSkyTest`, three golden instants). Hour angle is zone-free, so neither mark moves during a
glide while the band re-sets beneath them — that is physics, not an omission. Each mark exists
only while its body is above the horizon and only with NADIR plus a position. The sun is
`Palette.sunMark` (fixed sun-amber, zero blue); the moon is `Palette.moonMark` grey with its
honest phase, lit side facing the sun's mark — grey is the one deliberate blue exception, shared
with SPENT_GREY, because a blue-free grey is yellow. The moon needs an observer, which is why
`TelemetryState` now mirrors `positionLatDeg/LonDeg`.

**The guided tour ships nowhere.** It was cut from the product build — the editor sells settings,
not lessons, and the video carries the introduction now. The film-set variant lives on the
`video-demo` branch only. The Earth face also drops the reference-frame symbol from the weather
row (`frameSymbol = false` in the renderer): with one world on offer the glyph said nothing.

**Incident marks are filtered, never cleared.** The log is absolute epoch millis like everything else
here, so "the marks for this watch" is `at in shiftStart..shiftEnd` and nothing has to erase last
watch's marks. They go through `DutyArcLayer.drawIncidents` in `palette.second` at
`NOTCH_STROKE`/`NOTCH_OVERSHOOT`, crossing the band and standing proud of it — a tick thinner than
the arc reads as a scratch at 454 px. The angles are filled into a preallocated `FloatArray` in
`render()` beside the arc's own, because `render()` allocates nothing and a `List` iterator is an
allocation.

**Off-body suspends vigilance, and the dial says so.** `TYPE_LOW_LATENCY_OFFBODY_DETECT` is on-change,
wakeUp, needs no permission, and already has four clients on this watch, so subscribing costs
nothing. Two traps: it is registered for the life of the service, so every
`unregisterListener` in the sensor path must **name the accelerometer** — the single-argument form
drops every sensor the listener holds and would leave the watch believing it was worn for ever. And
`onBody` defaults to **true**, so hardware without the sensor stays armed. The failure direction is
the unsafe one — a loose strap reading off-body stops the switch watching a wrist that is there —
which is why `OFF WRIST` is the one suspension that prints a label. **Note for testing: a watch left
on a table may now suspend instead of alarming**, so the dead-man's cycle can no longer be exercised
by putting it down.

**An incident outranks everything and is cleared only deliberately.** `VigilanceState.INCIDENT` is
what the SOS leaves behind after `SosSignal.MAX_CYCLES`, and `VigilanceService` restores it from
device-protected prefs in `onCreate` *before* deciding whether to arm — a restarted service must not
quietly go back to counting. `acknowledge()` deliberately ignores it; only `ACTION_CLEAR_INCIDENT`
clears it, and the face sends that on a double tap. **Do not make it a long press:** on Wear the
system takes a long press on the watch face to open the picker, so the face never sees the hold.

**The battery row is seven characters, and that is what pays for its position.** `Geometry`'s width
budget allows 0.42 r for a sixteen-character line; `BAT 84%` sits at 0.520 r because its half-width
is only 0.180 r, putting the corner at 0.550 r against the numerals' 0.632 r. Lengthen that row and
the arithmetic stops holding. It carries `lumeDim` in every state and goes **bold** under 25 % rather
than changing colour, because the accent is already spoken for three times over.

**A force-stop does not lose the shift or the duration.** Both are in device-protected
`SharedPreferences`, and killing the process and reopening the editor brings back the running
countdown and the custom length. Verified on the watch, and worth knowing before diagnosing a
"settings reset" that is really a schema change.

**Segment labels are abbreviated, and the abbreviations are separate strings.** `editor_seg_*` exist
because `PHOSPHOR GREEN` and `HECTOPASCAL` do not fit a third of a row, while the platform's own
style list still shows the full names and the screen reader still reads the `_sr` ones. Judge a new
label by installing it: `MIDNIGHT UP` silently lost its `UP` at half a row's width.

**Stepper buttons repeat while held.** `StepButton` uses `detectTapGestures(onPress = ...)`: a tap
still acts on *release*, because these sit in a `ScalingLazyColumn` and acting on the press would
nudge the value whenever a scroll started on one. Callers take the tick size as a parameter and pass
`maxStep` — 1 for anything counted in days or hours, `COORD_MAX_STEP` for the coordinate rows — and
anything expensive (a `UserStyle` write) goes in `onCommit`, which runs once on release rather than
twenty times a second.

**The dial cache is two bitmaps.** `DialLayer.background()` (opaque) and `.scale()` (transparent
ticks and numerals), with the daylight band drawn between them per frame. Merging them back into one
means re-rasterising 454×454 whenever the band moves, which is impossible while it animates.

**`Geometry` carries the text width budget in a comment.** Adding or lengthening any readout row
means redoing that arithmetic: monospace advance 0.60 em, a row at offset y with half-width w
reaches `sqrt(w² + y²)`, and the hour numerals' inner edge is about 0.632 r. Currently the worst
corner sits near 0.57 r.

---

## Platform traps already paid for

Each of these cost real debugging time. The symptom is given because that is how you will meet them
again.

| Symptom | Cause |
|---|---|
| `NO FIX` on the real watch with a good position in `dumpsys location` | `lastKnown()` gated reads on `isProviderEnabled`. Wear's legacy `location_providers_allowed` lists **`gps` only** while fused and passive hold the actual fix. Never gate a cached read on that. |
| Location silently null from the worker | `ACCESS_BACKGROUND_LOCATION` missing. A wallpaper and a `WorkManager` job are both background on API 29+, and the platform returns null rather than an error. |
| Countdown reads hundreds of thousands of hours in the watch picker | Headless preview instances render at a fixed representative instant. The duty timer must use `System.currentTimeMillis()`, not the `ZonedDateTime` passed to `render()`. |
| Daylight band a day out, badly so at high east longitude | `SolarTime` day selection needs **round**, not floor. Flooring picks the solar day whose noon is at or before the instant, which slips near midnight UTC and at longitude. |
| Moon glyph renders as a cat's eye | Two circles with opposite winding: the cutter reaches past the disc and under the non-zero rule the overhang still fills. Use `Path.op(DIFFERENCE)`. |
| End-of-watch chime fires twice | A wall-clock jump makes the platform re-deliver a due `RTC_WAKEUP`. `WatchShiftController` records which boundary it has announced. |
| Selection in the editor never updates | `EditorSession.userStyle` is a proxy that does not implement `collect` — the library logs it. The editor mirrors selections in local state and pushes each change in. |
| Bitmap "Cannot draw a recycled Bitmap" on a style change | `DialLayer` rebuilt from inside its two accessors, so they could disagree about the cache key and the second call recycled a bitmap the first had already given the canvas. One `prepare()` before both blits. |
| Weather stays on the old country after the position moves | The cache was aged but not placed. `WeatherCache.isStale` takes distance as well as TTL; the observation's own lat/lon live in the store beside it. |
| Two MFD-24 faces in the picker, or settings gone after a pull | The `applicationId` changed — to the system that makes a new app rather than an update, so the previous one stays installed alongside holding its own style and shift state. It has happened twice: `com.dclink.…` → `com.avdesign.expedition24`, then `…expedition24` → `com.avdesign.mfd24` at the 1.2.0 rename. `pm list packages | grep -E 'expedition|mfd|dclink'` and uninstall the stale ones. |
| Persisted settings reset after an update | Changing the `UserStyleSchema` invalidates the stored style on-device. Expected on any release that adds or removes a setting; warn about it. |
| Default option ignored | `ListUserStyleSetting` resolves `defaultOption` with `indexOf`, and `Option` has no equality by id. The default must be **the same instance** from the options list. |

Two more worth knowing:

- `LocationManager.FUSED_PROVIDER` is a public constant only from API 31. It inlines as `"fused"` so
  it compiles and runs, but `isProviderEnabled("fused")` may throw `IllegalArgumentException` on
  API 30. Everything that touches providers catches it.
- Vigilance monitoring **must** be a foreground service with a wake lock. A wallpaper stops getting
  sensor events once the screen is off, which is exactly when a dead-man's check matters. It is off
  by default because it costs battery.

---

## Tests

178 JVM tests, `:app:testEarthDebugUnitTest` (the task took a flavour at 2.0.0). They are the specification for the parts that cannot be
eyeballed:

- `MotionFilterTest` — the band-pass **is** its tests. Ship roll, aircraft pitching, engine and
  rotor vibration, and roll-plus-vibration together must all be rejected; an arm movement through
  that noise must be found. The first implementation (difference of two one-pole low-passes, 6 dB
  per octave) failed these and was replaced with two poles a side. It now runs at the rate the
  service actually requests, read from `VigilanceService.SENSOR_PERIOD_MICROS`, and
  `the sample rate resolves the noise it has to reject` fails the build if that rate is lowered to
  where engine noise would alias into the pass band.
- `DialTransitionTest` — travel distance per hand across zone changes and the date line.
- `TimeZoneShiftTest` — a shift's length and the dial geometry across zones, half-hour zones included.
- `SolarTimeTest` — sunrise and sunset against published day lengths, polar day and night, the
  antimeridian.
- `PoiDatabaseTest` — the Morton range query against exhaustive brute force over 600 random queries.
  A Z-curve is discontinuous; the naive "scan around my own key" misses sites across a quadrant
  boundary, quietly. Also the rank order, which is not obvious: a helipad ranks below a *port*.
- `AstroTimeTest` — MSD/MTC, lunar day, dial angles, the packed UTC calendar date.
- `SiteGlyphTest` — every type/flag combination that picks a site pictogram, including the
  military helipad, where the priority order is a decision rather than an accident.
- `WeatherCacheTest` — the age *and* distance rule, including a backwards clock jump.
- `GeometryBudgetTest` — the readout width budget as arithmetic: every row's worst corner against
  the numerals' inner edge, from the shipped constants. Lengthen a row or nudge a baseline and this
  fails instead of a numeral getting clipped on a wrist.
- `ResolveOriginTest` / `AmbientAutoTest` — the site-search distance gate (both of its historical
  failure modes) and the AUTO half-density rule.
- `WakeTransitionTest` — the wake sweep, which is only on screen for half a second and is therefore
  far easier to pin here than to catch on a wrist: that colour always leads brightness, that both
  fronts clear the corners, and that a backwards clock jump settles rather than freezing it.

When adding anything with arithmetic in it, put it behind a pure function and test it. Everything in
`astro/`, `geo/`, `text/` and `MotionFilter`/`DialTransition` is deliberately Android-free for that
reason.

---

## Open questions raised with the user and not yet settled

Injecting `mfd24_vigilance.xml` / `mfd24_watch_shift.xml` into
`/data/user_de/0/com.avdesign.mfd24/shared_prefs` under `adb root` remains the way to stage a state
that cannot be reached by waiting — and it is **emulator-only**, because the release build on the
watch is not debuggable and `run-as` is therefore unavailable there. Write-then-`force-stop`, in one
`adb shell` call, and mind the uid change after a reinstall.

- **Does the platform's step provider report the day, or the subscription?** The one experiment that
  answers it: uninstall the app, leave the watch alone through half a day of walking, install, and
  read the **first** figure the row shows. Everything observed so far fits both answers — see the
  steps entry in the invariants for why 480 was not the proof it looked like. Until it is run, the
  row is documented as "steps today as this watch understands them" and the fallback carries the
  honest promise.

- **FGS start from a background uid is refused on API 31+.** Seen directly on the API 36
  emulator: with the app not TOP, `startForegroundService` throws
  `ForegroundServiceStartNotAllowedException` at the *caller*. `VigilanceMonitor.send()` wraps it
  in `runCatching`, so the face cannot crash — but whether a Wear 4/5 device counts the visible
  wallpaper's uid as exempt is still unknown, and if it does not, switching vigilance on from the
  editor (app TOP) works while a later automatic re-arm from the face alone may quietly fail.
  Needs a real Wear 4/5 device or system image to settle.
- **Vigilance escalation is under test on hardware.** Filter, state machine, service startup, the
  batched registration and the absence of a held wake lock are all verified on the watch. The full
  nudge → 30 s → SOS cycle was being run for the first time on 2026-08-19, with the watch off
  charge and motionless; record the result here.
- **`dumpsys battery unplug` was used to force the armed path** while the watch sat on its charger.
  It is sticky — the platform keeps reporting unplugged until `dumpsys battery reset` or a reboot,
  which would leave vigilance running on charge and the battery UI lying. Reset it after any such
  test.
- **Release notes are written by a throwaway script, not by hand.** Each publish so far has been a
  small Python script hitting the GitHub API with the token from `git credential fill`: create the
  release from the tag, delete any stale asset, upload `app-release.apk`, and interpolate the APK's
  own SHA-256 into the notes. Screenshots in the notes have to be absolute
  `raw.githubusercontent.com` URLs pinned to the tag — relative paths do not render on a release
  page.

---

## Repository

`github.com/amorroma1/expedition24`, **one branch, `main`** — `earth` was deleted on 2026-08-22 once
it was a byte-identical copy. The repo root *is* the project root. Three names disagree with the
app's, all deliberately: the local folder is still `tickwath3pro`, the remote is still
`expedition24`, and the Gradle signing properties, the keystore's key alias and the certificate's DN
all still say `expedition24` because they name the **release key** — which cannot be renamed without
minting a new one, and a new key could not install over an old build. Only the first two are
cosmetic; the third is load-bearing. Do not "tidy" it. Renaming the *remote* is the one that is
actually available if tidiness ever wins: GitHub leaves a redirect, and only `ReleaseCheck.REPO` and
the addresses in the documentation would have to move.

**The product name stays `MFD-24`** — asked and decided on 2026-08-22, when the user wondered whether
it sounded too dry. It is dry on purpose: MFD is the cockpit's Multi-Function Display and `-24` is
the day in one revolution, so the name reads as instrument nomenclature to exactly the people the
face is for, where an evocative name would read as a consumer app and undercut the pitch. And the
change is not the three `strings.xml` lines it looks like: `applicationId` cannot move without the
platform treating it as a new app (settings and shift state lost, two faces in the picker — it has
happened twice here), `Callsign.SALT` is the literal `"MFD24"` and re-salting renames every
instrument in the field, and the name appears about forty times across thirteen documents in six
languages. If the dryness ever needs answering, the cheap and reversible move is a **tagline** beside
the designation in the README hero, the release title and the repository description — no identifier
touched.

**Every new source file needs the SPDX header.** Two lines above the `package` declaration:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov
```

`#` instead of `//` for Python, below any shebang. The full notice lives in `LICENSE` and in the
README rather than at the top of sixty files — the KDoc there is load-bearing and should stay on the
first screen.

**The bundled POI data is not GPL.** `navalbases.csv` and `heliports.csv` are OpenStreetMap-derived
and therefore ODbL, which is share-alike *on the database* and is not discharged by the GPL. Any
`poi_v1.bin` built from them inherits that. If the data sources change, update the License section
of the README as well.

### CI

`.github/workflows/build.yml`, two jobs on every push and pull request:

- **build** — tests, `assembleDebug`, `assembleRelease`, then an assertion that
  `assets/poi_v1.bin` is in both APKs *and* stored uncompressed. That check exists because the
  failure it catches has happened twice with a green build: the index comes from a generator task,
  and when its wiring to the asset source set slips the app ships without it and quietly never
  resolves a site. Verified against all four cases — no APK, present, missing, compressed.
- **conventions** — SPDX header coverage, and that `gradlew` is executable and LF. Both are
  invisible on Windows, which is where this is developed.

Uses **JDK 17**, not something newer: `:tools:poi` asks for `jvmToolchain(17)`, and if the build JDK
is a different version Gradle fails at configuration time hunting for a second installation. That is
also why release signing is conditional on `~/.android/debug.keystore` existing — a runner has no
such file, and a signing config pointing at a missing keystore fails at packaging. No key means an
unsigned release, which is all CI needs.

`.gitattributes` pins `gradlew` to LF and `gradlew.bat` to CRLF, and `gradlew` is committed mode
100755. Get either wrong and the build breaks only on a machine that is not this one.

`.github/copilot-instructions.md` arrived with the GitHub scaffold and is boilerplate Azure advice
with nothing to do with this project. Harmless; delete it whenever.

---

## Style

Match the surrounding code: KDoc on every class explaining *why* it exists and what would go wrong
otherwise, comments that name the failure a decision prevents rather than restating the code, and
British spelling in prose.

Documentation ships in six languages: the README and `docs/INSTALL.md` in English, their
counterparts under `docs/i18n/` in French, German, Italian, Japanese and Chinese. A change to any
of them is not done until it is carried to all six. Prefer deleting a dependency to adding one — Play Services location was
dropped on purpose, and the framework `LocationManager` path now works. There is **one** deliberate
exception, added at 2.5.0: `androidx.health:health-services-client`, for the platform's own daily
step total. It earned its place by fixing something no amount of local arithmetic could — a step
count that could only ever start from the moment this app was installed — and it degrades to the
old sensor path where the service is absent. Anything else asking to be added should expect the
same two questions: what can it do that we cannot, and what happens on a watch that lacks it.
