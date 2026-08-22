# MFD-24

[![build](https://github.com/amorroma1/expedition24/actions/workflows/build.yml/badge.svg)](https://github.com/amorroma1/expedition24/actions/workflows/build.yml)

**English** · [Français](docs/i18n/README.fr.md) · [Deutsch](docs/i18n/README.de.md) · [Italiano](docs/i18n/README.it.md) · [日本語](docs/i18n/README.ja.md) · [中文](docs/i18n/README.zh.md)

A 24-hour instrument dial for people who stand watches — pilots, mariners, UAV crews, duty
officers. One turn of the hour hand per day, your shift drawn as an arc of that day, a dead-man's
monitor that escalates to SOS and leaves a record, and the telemetry a watch actually runs on.

Everything works offline except the weather. No companion app, no account, no analytics. GPL.

| Interactive | Always-on |
|---|---|
| ![](docs/screenshots/earth.png) | ![](docs/screenshots/ambient.png) |

*A watch under way at New York JFK. The same face in both shots: always-on is the interactive dial,
dimmed, minus the seconds cursor and the background wash.*

**Wear OS 3.0+ (API 30).** Built and worn on a TicWatch Pro 3 Ultra (454 × 454).
**[Install →](docs/INSTALL.md)**

---

## What you get

| | |
|---|---|
| **24-hour dial** | One revolution per day. Noon up or midnight up. Minutes on their own ring; the second is a cursor that steps, so nothing sweeps. |
| **Watch timer** | Start now or book ahead. An arc spans the hours on duty and dims as they are served; `DUTY: 04:40 REM` counts down. Both boundaries beep and buzz with the screen off. A finished watch clears itself an hour later. |
| **Vigilance monitor** | Off by default. Every 5/10/15 min it wants a sign of life; thirty seconds later it sounds SOS in bursts and leaves `MAN DOWN` with the Zulu moment on the dial. Optional: your pulse during the missed check, against your last moving reading. |
| **Zulu + date-time group** | `Z 22AUG 12:16:13`, the clock a log entry is written in. |
| **Weather** | Temperature, METAR-style condition and QNH. At an aerodrome it is that field's **own METAR**; everywhere else Open-Meteo. Refreshed every 30 minutes. |
| **5 km site lock** | Names the nearest airfield, port, heliport or spaceport from 9,649 sites in a 194 KB on-device index. Military sites are drawn in the accent colour. |
| **Nadir, sun and moon** | Daylight shaded across the hour band, computed offline. The sun and the moon sit at their true hour angles — point a mark at the real thing and the dial is a **compass**. The moon carries its honest phase. |
| **Sensor slots** | Two optional readouts beside the hub: heart rate, the platform's own daily step total, or station pressure. Off by default; they run only while the screen is on. |
| **Always-on** | The same dial, dimmed. `AUTO` thins it to every other pixel after sunset — never during a night watch. |
| **Direct boot** | Draws populated before the watch is unlocked. |

## In action

| A missed check, answered late | Crossing time zones | Waking |
|---|---|---|
| ![](docs/media/mandown.gif) | ![](docs/media/glide.gif) | ![](docs/media/wake.gif) |
| The SOS went unanswered: `MAN DOWN`, the Zulu moment, a mark on the arc. One tap answers with `TAP AGAIN`; a second, inside a second, clears it — a sleeve cannot. | The hour scale glides the short way round the day; the minute hand only takes what is left inside the hour. Remaining duty time never changes. | Only the light arrives. Always-on wears the same hues, so there is nothing to cross-fade. |

## Install

Download `app-earth-release.apk` from the
[latest release](https://github.com/amorroma1/expedition24/releases/latest) and sideload it. Wear OS
has no sideloading UI, so this is an ADB job:

```
adb connect 192.168.1.50:41234
adb install -r app-earth-release.apk
```

Then pick MFD-24 in the watch's own face picker.
**[Full guide — three routes, from a phone-only install to building from source →](docs/INSTALL.md)**

<details>
<summary><b>Updates, and why this is not on Google Play</b></summary>

<br>

The watch checks GitHub **once a day, off duty**, and tells you once per release. It downloads
nothing and installs nothing: **Wear OS does not let an app install an app** (verified on a TicWatch
Pro 3 Ultra). `ABOUT → RELEASES` shows the release page as a QR code for a phone camera, and the
install stays `adb install -r`. `ABOUT → RELEASE CHECK` turns the whole thing off.

It is not on Google Play on purpose: a dead-man's monitor should not be an impulse install, the
permissions it legitimately needs keep a hobby project in permanent store review, and an APK with
its SHA-256 in the release notes is more verifiable than a listing. Full reasoning in
[INSTALL.md](docs/INSTALL.md#why-mfd-24-is-not-on-google-play).

</details>

<details>
<summary><b id="permissions">Permissions — nothing is requested at install time</b></summary>

<br>

Location is asked for from the settings screen, because a watch face is a `WallpaperService` and
cannot raise a dialog itself.

| Permission | For | Without it |
|---|---|---|
| `ACCESS_FINE_LOCATION` | weather, Nadir, the site lock | those three are blank; the clock and the astronomy are unaffected |
| `ACCESS_BACKGROUND_LOCATION` | the same, from the 30-minute refresh job | the platform returns null rather than an error, which looks exactly like no signal |
| `WAKE_LOCK`, `FOREGROUND_SERVICE_HEALTH` | the vigilance monitor, which must keep sensing with the screen off | that feature only |
| `VIBRATE` | shift boundaries and the SOS | silent boundaries |
| `SCHEDULE_EXACT_ALARM` | boundaries landing on the minute | requested but **not** required; without it a boundary can slip a few minutes |
| `INTERNET` | Open-Meteo and METAR every 30 min, plus the daily release check | no weather, no release check |
| `BODY_SENSORS` | the heart-rate slot and the pulse in an incident | dashes; the `LOG PULSE` row is not offered |
| `ACTIVITY_RECOGNITION` | the steps slot; on Wear 5+ also what lets the vigilance service start | dashes; on newer Wear, no vigilance |
| `POST_NOTIFICATIONS` | the monitor's ongoing notice, and the once-per-release update notice | the monitor runs unannounced |

No contacts, no storage, and no network beyond those calls. A hand-typed position replaces location
entirely — see **Position** in the settings.

</details>

## Settings

**Long-press the face, then tap the pencil.** Seven collapsible sections, one open at a time; the
open one is outlined.

| Sections | Duty control | Scheduling | Display | About |
|---|---|---|---|---|
| ![](docs/screenshots/settings-sections.png) | ![](docs/screenshots/settings-duty.png) | ![](docs/screenshots/settings-schedule.png) | ![](docs/screenshots/settings-display.png) | ![](docs/screenshots/settings-about.png) |

- **Duty control** — duration `4h` `8h` `12h` `CST`, a booked start, and one chip reading
  `START NOW` or a terracotta `END DUTY`. A start in the past cannot be booked.
- **Vigilance** — on/off, check interval, `VIBE STRENGTH`, `SOS SOUND` (OFF/LOW/MED/HIGH),
  `LOG PULSE`, and the incident log with its export.
- **Sensors** — what each hub slot reads.
- **Position** — automatic, or coordinates typed by hand.
- **Display** — palette, dial top, midnight mark, Nadir, sun and moon marks, weather, always-on,
  and `HINTS` for the small print under each row.
- **Units** — °C/°F, hPa/mmHg.
- **About** — version, the repository as a QR, the release check.

**[Every row, with the reasoning →](docs/DESIGN.md#settings)**

## The dial, in detail

| ![](docs/screenshots/duty.png) | ![](docs/screenshots/ambient-auto.png) | ![](docs/screenshots/ambient-duty.png) |
|---|---|---|
| Mid-watch: the arc is the shift, the bright part is what is left to serve. | `ALWAYS-ON > AUTO` thins the face after sunset — half the light, half the burn-in duty cycle. | A running watch keeps the full face all night: a night duty is exactly when the dial is read in the dark. |

**[How it works — layout, astronomy, the vigilance state machine, the tests →](docs/DESIGN.md)**

<details>
<summary><b>Footnotes — the step count, the hardware it needs, and what it weighs</b></summary>

<br>

**How the step count is arrived at.** The row prefers the platform's own daily figure, through
Health Services, because that is the number the rest of the watch agrees with and it does not care
when this face was installed. Where a watch has no such provider, or the provider binds and then
says nothing, the face counts for itself from the hardware step counter. That fallback is exact
**from the next local midnight onward**, because the counter's value is recorded at the day
boundary; on the day you install it, it can only count from the moment you switch the slot on, and
it shows dashes rather than a `0` it would have invented. One watch tested here reports its "daily"
total as steps since the subscription began rather than since midnight — a provider quirk, not a
setting, and the reason the row is worth reading as *steps counted*, not as a fitness statistic.

**Screen.** Round, and tested at **454 × 454**. Every position on the dial is a fraction of the
radius, so other round sizes should be fine; square and chin-cut screens are not designed for and
will crop the outer scale. Text is sized from the radius too — the readout width budget is
arithmetic and there is a test that fails if a row would reach the hour numerals.

**Hardware and performance.** Wear OS 3.0+ (API 30), any watch the platform runs on; no GPU
features beyond an ordinary canvas. `render()` allocates nothing, so there is no garbage collector
pause to schedule around, and the frame rate is demand-driven: **one frame a second at rest**,
16 ms only while a transition is animating, and one frame a minute in always-on. The optional
sensors are the only real cost — heart rate is an LED, and the vigilance monitor keeps the
accelerometer batching in the sensor hub rather than waking the processor. Everything else runs off
cached values.

**Size.** About **3.5 MB** installed, of which roughly 2.8 MB is code, 190 KB the on-device site
index and 220 KB the picker preview. The index is stored uncompressed on purpose so it can be read
without unpacking. English resources only: the app's own text is English, and carrying eighty
locales of library strings it can never display cost 260 KB.

</details>

## What it is not

A personal instrument, not a certified one. The vigilance monitor is an uncertified aid: it can be
switched off, suspended by a loose strap, or delayed by the platform's own doze limits. The incident
log covers **one watch** — starting another empties it, so anything worth keeping leaves via
`EXPORT LOG` first. Nothing here substitutes for a device a regulator has approved.

## Build

```
git clone https://github.com/amorroma1/expedition24.git
cd expedition24
./gradlew :app:assembleEarthDebug
```

JDK 17+ and the Android SDK. 178 JVM tests: `./gradlew :app:testEarthDebugUnitTest`.

## Licence

GPL-3.0-or-later — see [LICENSE](LICENSE). Naval bases and heliports are derived from OpenStreetMap
(© OpenStreetMap contributors, **ODbL**), so any index built from them carries that licence too;
airports, ports and spaceports are curated in this repository. Weather from
[Open-Meteo](https://open-meteo.com/) (CC BY 4.0) and
[aviationweather.gov](https://aviationweather.gov/) (US NOAA, public domain).
