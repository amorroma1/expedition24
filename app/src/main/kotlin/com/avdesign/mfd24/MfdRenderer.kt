// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ContentDescriptionLabel
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyle
import com.avdesign.mfd24.astro.AstroTime
import com.avdesign.mfd24.astro.DteWindows
import com.avdesign.mfd24.astro.EarthSky
import com.avdesign.mfd24.astro.MarsSolarTime
import com.avdesign.mfd24.astro.MoonSky
import com.avdesign.mfd24.astro.MoonState
import com.avdesign.mfd24.astro.PlanetMode
import com.avdesign.mfd24.astro.Rovers
import com.avdesign.mfd24.astro.SolarTime
import com.avdesign.mfd24.data.Alerts
import com.avdesign.mfd24.data.MarsCommState
import com.avdesign.mfd24.data.TelemetryState
import com.avdesign.mfd24.data.SensorSlots
import com.avdesign.mfd24.data.VigilanceState
import com.avdesign.mfd24.data.VigilanceStore
import com.avdesign.mfd24.data.WatchShiftState
import com.avdesign.mfd24.render.AmbientAuto
import com.avdesign.mfd24.render.CommWindowLayer
import com.avdesign.mfd24.render.AmbientLayer
import com.avdesign.mfd24.render.DaylightLayer
import com.avdesign.mfd24.render.DialLayer
import com.avdesign.mfd24.render.DialTransition
import com.avdesign.mfd24.render.Geometry
import com.avdesign.mfd24.render.HandsLayer
import com.avdesign.mfd24.render.SecondsMarker
import com.avdesign.mfd24.render.TelemetryLayer
import com.avdesign.mfd24.render.WakeTransition
import com.avdesign.mfd24.render.DutyArcLayer
import com.avdesign.mfd24.style.Palette
import com.avdesign.mfd24.style.StyleSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Canvas renderer for MFD-24.
 *
 * ### Zero allocation
 * [render] allocates nothing. Every `Paint`, `Path`, `Matrix`, `RectF` and character buffer is
 * built in [Geometry.rebuild] or in a layer's constructor; text is emitted through
 * `Canvas.drawText(char[], …)` from hand-formatted buffers rather than through `String.format`;
 * no lambdas, iterators or enums appear in the drawing path. The one object created per frame is
 * the [ZonedDateTime] the framework itself hands us — we read its fields and derive the epoch
 * without producing an `Instant`.
 *
 * The static dial (background, rings, 24-hour scale, numerals) is rasterised once into a bitmap and
 * blitted as a single quad, so a frame costs one texture draw plus a handful of paths and a few
 * short text runs.
 */
class MfdRenderer(
    surfaceHolder: SurfaceHolder,
    private val currentUserStyleRepository: CurrentUserStyleRepository,
    watchState: WatchState,
    private val telemetry: TelemetryState,
    private val watchShift: WatchShiftState,
    private val vigilance: VigilanceState,
    private val onWeatherEnabled: (Boolean) -> Unit,
    /**
     * Called only when the answer changes, so the frame loop does not poke the service.
     *
     * Arguments: whether to monitor, the interval, the vibration amplitude, and when the watch
     * under way began — the last so the service can tell an incident from this watch from one left
     * over by the previous one.
     */
    /**
     * `(active, intervalMillis, vibeAmplitude, sosVolume, logHeartRate, shiftStartMillis,
     * shiftEndMillis)`.
     *
     * Six loose parameters rather than one object, and deliberately: this is called from inside
     * `render()`, which allocates nothing, and a data class here would allocate on every change.
     * It only fires when something actually changed, so the ugliness is paid once per setting.
     */
    private val onVigilanceRequest: (Boolean, Long, Int, Int, Boolean, Long, Long) -> Unit,
    /** Which reading each slot beside the hub wants, as SensorSlots.Kind ordinals. */
    private val onSensorSlots: (Int, Int) -> Unit,
    /** The Mars comm windows, or null on any world that has none. */
    private val marsComm: MarsCommState? = null,
    /** Fired only when the selected rover changes, like every callback out of applyStyle. */
    private val onRoverSelected: (Int) -> Unit = { },
    /** Fired only when the enabled-relay bitmask changes. */
    private val onRelayMask: (Int) -> Unit = { },
    /** Resolved strings for the spoken labels — a Renderer has no Context to resolve its own. */
    private val descriptions: FaceDescriptions,
) : Renderer.CanvasRenderer2<MfdRenderer.Assets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    IDLE_FRAME_PERIOD_MS,
    false,
) {

    /** Nothing is worth sharing between concurrent instances yet; the type is still required. */
    class Assets : SharedAssets {
        override fun onDestroy() = Unit
    }

    private val geometry = Geometry(
        if (PlanetMode.fromOptionId(BuildConfig.WORLD) == PlanetMode.MARS) {
            Geometry.DUTY_ARC_RADIUS_MARS
        } else {
            Geometry.DUTY_ARC_RADIUS
        }
    )
    private val palette = Palette()

    /**
     * A second palette, monochrome and blue-free, used only in always-on. Kept as its own object
     * rather than recomputed per frame because the layers all read plain `Int` fields off it.
     */
    private val ambientPalette = Palette()

    private val dialLayer = DialLayer()
    private val daylightLayer = DaylightLayer()
    private val transition = DialTransition()
    private val wake = WakeTransition()
    private val handsLayer = HandsLayer()
    private val secondsMarker = SecondsMarker()
    private val dutyArcLayer = DutyArcLayer()
    private val commWindowLayer = CommWindowLayer()
    private val telemetryLayer = TelemetryLayer()
    private val ambientLayer = AmbientLayer()

    private val lowBitAmbient = watchState.hasLowBitAmbient
    private val burnInProtection = watchState.hasBurnInProtection

    /**
     * The body the hour hand keeps time on. Fixed per flavor: a build is one world's instrument,
     * so the value comes from `BuildConfig.WORLD` and is never written at runtime. The engine
     * underneath still takes it as a parameter — the arithmetic is general and tested that way —
     * which is why this is a field and not inlined.
     */
    private val planetMode: Int = PlanetMode.fromOptionId(BuildConfig.WORLD)

    @Volatile
    private var fahrenheit: Boolean = false

    @Volatile
    private var mmHg: Boolean = false

    @Volatile
    private var midnightAs24: Boolean = false

    @Volatile
    private var midnightUp: Boolean = false

    @Volatile
    private var nadirEnabled: Boolean = false

    /**
     * Which rover's meridian the Mars dial runs on. Read per frame by [planetHoursAt], written
     * only from the style; meaningless — and untouched — on any other world.
     */
    @Volatile
    private var roverIndex: Int = Rovers.PERSEVERANCE

    /**
     * Enabled relay satellites, a bitmask in [StyleSchema.RELAY_SETTINGS] order. Held here so a
     * style change can reach the ephemeris side the same way the weather switch reaches the
     * telemetry side; the renderer itself never reads it.
     */
    @Volatile
    private var relayMask: Int = 0xF

    /**
     * Whether the relay windows on file reach past now, mirrored from [marsComm] each frame.
     * False until the ephemeris side first publishes — which is also the honest reading: a face
     * that has never loaded ephemerides says NO EPHEMERIS rather than showing an empty line
     * that could mean "no passes today". Render-thread only.
     */
    private var relayWindowsValid: Boolean = false

    // The daylight band as drawn this frame: the transition's eased angles on Earth, a
    // per-frame mapping of the sol's instants on Mars. Render-thread only.
    private var daylightDrawStart = 0f
    private var daylightDrawSweep = 0f

    // The twilight shoulders either side of the Mars band, mapped per frame like the band
    // itself; zero sweeps on any other world. Render-thread only.
    private var twilightMorningStart = 0f
    private var twilightMorningSweep = 0f
    private var twilightEveningStart = 0f
    private var twilightEveningSweep = 0f

    /** One-way light time to Earth this frame, seconds; −1 where the row has no such thing. */
    private var owltSeconds = -1

    /** Solar conjunction: the DTE line thins to a hairline and the readout flies the flag. */
    private var dteBlocked = false

    // Comm windows: instants copied when the state's version moves, angles refilled per frame
    // like the duty arc's. All preallocated — render() allocates nothing.
    private var marsCommVersionSeen = -1
    private var marsDteCount = 0
    private var marsRelayCount = 0
    private val marsDteStart = LongArray(DteWindows.MAX)
    private val marsDteEnd = LongArray(DteWindows.MAX)
    private val marsRelayStart = LongArray(MarsCommState.MAX_RELAY_WINDOWS)
    private val marsRelayEnd = LongArray(MarsCommState.MAX_RELAY_WINDOWS)
    private val commDteAngles = FloatArray(DteWindows.MAX * 2)
    private val commRelayAngles = FloatArray(MarsCommState.MAX_RELAY_WINDOWS * 2)
    private var commDteArcCount = 0
    private var commRelayArcCount = 0

    @Volatile
    private var solarMarkEnabled: Boolean = false

    @Volatile
    private var lunarMarkEnabled: Boolean = false

    /** Scratch for the moon model, refilled in place; render() allocates nothing. */
    private val moonState = MoonState()

    /**
     * Whether the readout leads its third row with a reference-frame symbol. On Earth it does
     * not — with one world on offer the glyph said nothing — but a face that keeps another
     * world's time wears its symbol, because the one thing the reader must never mistake is
     * which clock they are looking at.
     */
    private val frameSymbol = planetMode != PlanetMode.EARTH

    /** One of [AmbientAuto]'s modes; AUTO resolves per frame against daylight and the duty. */
    @Volatile
    private var ambientDensityMode: Int = AmbientAuto.MODE_FULL

    @Volatile
    private var vigilanceEnabled: Boolean = false

    @Volatile
    private var vigilanceIntervalMillis: Long = 10 * 60_000L

    @Volatile
    private var vibeAmplitude: Int = Alerts.AMPLITUDE_MED

    @Volatile
    private var sosVolume: Int = Alerts.SOS_VOLUME_HIGH

    @Volatile
    private var logHeartRate: Boolean = false

    @Volatile
    private var sensorLeft: Int = SensorSlots.Kind.OFF.ordinal

    @Volatile
    private var sensorRight: Int = SensorSlots.Kind.OFF.ordinal

    private var lastSensorLeft = -1
    private var lastSensorRight = -1

    private var lastVigilanceRequest: Boolean? = null
    private var lastVigilanceInterval = 0L
    private var lastVibeAmplitude = 0
    private var lastSosVolume = -1
    private var lastLogHeartRate = false
    private var lastShiftStart = 0L

    /**
     * Dial angles of the incidents recorded during the watch under way, refilled each frame.
     *
     * Preallocated to the log's own ceiling, because `render()` allocates nothing and the count of
     * marks is data. Angles rather than instants so the mapping happens once, beside the arc's own,
     * in whatever time base the hour hand is using.
     */
    private val incidentAngles = FloatArray(VigilanceStore.MAX_ENTRIES)
    private var incidentMarkCount = 0

    /** Last states spoken through [publishDescriptions], so labels rebuild only on a change. */
    private var lastSpokenDuty = -1
    private var lastSpokenVigilance = -1

    /** Bumped whenever the cached dial bitmap has to be redrawn. */
    private var styleGeneration = 0

    /** Bumped whenever the surface bounds change and layout-derived caches go stale. */
    private var layoutGeneration = 0

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    init {
        Log.d(TAG, "ambient: lowBit=$lowBitAmbient burnInProtection=$burnInProtection")
        applyStyle(currentUserStyleRepository.userStyle.value)
        scope.launch {
            currentUserStyleRepository.userStyle.collect { style ->
                applyStyle(style)
                invalidate()
            }
        }
    }

    override suspend fun createSharedAssets(): Assets = Assets()

    override fun onDestroy() {
        scope.cancel()
        dialLayer.recycle()
        super.onDestroy()
    }

    private fun applyStyle(style: UserStyle) {
        val paletteId = StyleSchema.optionId(style, StyleSchema.PALETTE, Palette.ID_AMBER)
        fahrenheit = StyleSchema.optionId(style, StyleSchema.TEMP_UNIT, StyleSchema.TEMP_CELSIUS) ==
            StyleSchema.TEMP_FAHRENHEIT
        mmHg = StyleSchema.optionId(style, StyleSchema.PRESSURE_UNIT, StyleSchema.PRESSURE_HPA) ==
            StyleSchema.PRESSURE_MMHG
        midnightAs24 = StyleSchema.optionId(
            style, StyleSchema.MIDNIGHT_LABEL, StyleSchema.MIDNIGHT_AS_00
        ) == StyleSchema.MIDNIGHT_AS_24
        midnightUp = StyleSchema.optionId(
            style, StyleSchema.DIAL_TOP, StyleSchema.DIAL_TOP_NOON
        ) == StyleSchema.DIAL_TOP_MIDNIGHT
        nadirEnabled = StyleSchema.optionId(
            style, StyleSchema.NADIR, StyleSchema.NADIR_OFF
        ) == StyleSchema.NADIR_ON
        solarMarkEnabled = StyleSchema.optionId(
            style, StyleSchema.SOLAR_MARK, StyleSchema.SOLAR_OFF
        ) == StyleSchema.SOLAR_ON
        lunarMarkEnabled = StyleSchema.optionId(
            style, StyleSchema.LUNAR_MARK, StyleSchema.LUNAR_OFF
        ) == StyleSchema.LUNAR_ON
        // The weather switch reaches the repository as well as the renderer: off means nothing is
        // fetched, not merely that the row is blank. Gated on the world as well as the style,
        // because the Mars schema carries no weather setting and the fallback here is ON — left
        // unguarded, the Mars face would quietly fetch Earth weather it can never show.
        onWeatherEnabled(
            planetMode == PlanetMode.EARTH &&
                StyleSchema.optionId(style, StyleSchema.WEATHER, StyleSchema.WEATHER_ON) ==
                StyleSchema.WEATHER_ON
        )
        vigilanceEnabled = StyleSchema.optionId(
            style, StyleSchema.VIGILANCE, StyleSchema.VIGILANCE_OFF
        ) == StyleSchema.VIGILANCE_ON
        sensorLeft = SensorSlots.Kind.ofId(
            StyleSchema.optionId(style, StyleSchema.SENSOR_LEFT, SensorSlots.Kind.OFF.id)
        ).ordinal
        sensorRight = SensorSlots.Kind.ofId(
            StyleSchema.optionId(style, StyleSchema.SENSOR_RIGHT, SensorSlots.Kind.OFF.id)
        ).ordinal
        vigilanceIntervalMillis = StyleSchema.vigilanceIntervalMillis(
            StyleSchema.optionId(
                style, StyleSchema.VIGILANCE_INTERVAL, StyleSchema.VIGILANCE_INTERVAL_DEFAULT
            )
        )
        vibeAmplitude = StyleSchema.vibeAmplitude(
            StyleSchema.optionId(style, StyleSchema.VIBE_STRENGTH, StyleSchema.VIBE_MED)
        )
        sosVolume = StyleSchema.sosVolume(
            StyleSchema.optionId(style, StyleSchema.SOS_SOUND, StyleSchema.SOS_SOUND_HIGH)
        )
        logHeartRate = StyleSchema.optionId(
            style, StyleSchema.LOG_HEART_RATE, StyleSchema.LOG_HR_OFF
        ) == StyleSchema.LOG_HR_ON
        val newRover = Rovers.fromOptionId(
            StyleSchema.optionId(style, StyleSchema.ROVER, Rovers.ID_PERSEVERANCE)
        )
        if (newRover != roverIndex) {
            roverIndex = newRover
            onRoverSelected(newRover)
        }
        val newRelayMask = StyleSchema.relayMask(style)
        if (newRelayMask != relayMask) {
            relayMask = newRelayMask
            onRelayMask(newRelayMask)
        }
        palette.update(paletteId, planetMode)
        // Always-on is the same palette, dimmed. Nothing to cross-fade on waking.
        ambientPalette.updateAmbientFrom(palette)
        ambientDensityMode = StyleSchema.ambientDensityMode(
            StyleSchema.optionId(style, StyleSchema.AMBIENT_DENSITY, StyleSchema.AMBIENT_FULL)
        )
        styleGeneration++
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: Assets,
    ) {
        val flipped = midnightUp
        if (!geometry.matches(bounds, flipped)) {
            geometry.rebuild(bounds, flipped)
            layoutGeneration++
        }

        // Epoch milliseconds without building an Instant.
        val epochMillis = zonedDateTime.toEpochSecond() * 1000L + zonedDateTime.nano / 1_000_000L
        val realOffsetMillis = zonedDateTime.offset.totalSeconds * 1000L

        val mode = planetMode

        // One full turn of the hour hand on this world; the daylight band and the duty arc both
        // sweep against it.
        val period = planetPeriodMillis(mode)

        // The dial's frame-of-reference offset: the time zone on Earth, the selected rover's
        // meridian on Mars, expressed in that world's own milliseconds. One number, so that a
        // zone change and a rover switch are the same event to the transition — everything on
        // the hour scale glides together, the way the brief's Earth face already re-sets.
        val frameOffsetMillis = when (mode) {
            PlanetMode.MARS ->
                Math.round(Rovers.LON_EAST[roverIndex] / 360.0 * AstroTime.SOL_IN_MILLIS)
            PlanetMode.MOON -> 0L
            else -> realOffsetMillis
        }

        // Where the daylight band wants to be, before easing — Earth only. On Earth the band's
        // instants stay put while positions and zones move it, so easing its *angles* is what
        // makes a change of position morph the band in place. On Mars the band is drawn per
        // frame from its instants through the eased offset instead (below, beside the duty
        // arc), because a rover switch moves the offset and the instants by nearly opposite
        // amounts: angle-eased, the band sat parked while the whole dial travelled past it,
        // which read as broken. The Moon has no band at all — a lunar day is a synodic month.
        var targetDaylightStart = 0f
        var targetDaylightSweep = 0f
        if (nadirEnabled && mode == PlanetMode.EARTH && telemetry.daylightValid) {
            when (telemetry.daylightKind) {
                SolarTime.POLAR_DAY -> targetDaylightSweep = 360f
                SolarTime.POLAR_NIGHT -> targetDaylightSweep = 0f
                else -> {
                    targetDaylightStart = AstroTime.hourHandAngle(
                        planetHoursAt(telemetry.sunriseMillis, mode, frameOffsetMillis), flipped
                    )
                    targetDaylightSweep = sweepFor(
                        telemetry.sunsetMillis - telemetry.sunriseMillis, period
                    )
                }
            }
        }

        // Cross a time zone or move a long way and everything on the hour scale has to shift at
        // once. Easing the offset carries the hour hand, both ends of the duty arc and the
        // daylight band together, so the dial re-sets itself the way a good chronograph does
        // instead of teleporting.
        transition.update(
            epochMillis, frameOffsetMillis, targetDaylightStart, targetDaylightSweep,
            Math.round(period),
        )
        val utcOffsetMillis = transition.hourOffsetMillis

        // What the band actually draws this frame. Earth reads the transition's eased angles;
        // Mars maps the sol's own instants through the eased offset, exactly as the duty arc
        // and the comm windows do, so a rover switch carries the band around with the dial.
        daylightDrawStart = transition.daylightStart
        daylightDrawSweep = transition.daylightSweep
        twilightMorningSweep = 0f
        twilightEveningSweep = 0f
        if (mode == PlanetMode.MARS) {
            daylightDrawStart = 0f
            daylightDrawSweep = 0f
            if (nadirEnabled && telemetry.daylightValid) {
                when (telemetry.daylightKind) {
                    SolarTime.POLAR_DAY -> daylightDrawSweep = 360f
                    SolarTime.POLAR_NIGHT -> daylightDrawSweep = 0f
                    else -> {
                        daylightDrawStart = AstroTime.hourHandAngle(
                            planetHoursAt(telemetry.sunriseMillis, mode, utcOffsetMillis), flipped
                        )
                        daylightDrawSweep = sweepFor(
                            telemetry.sunsetMillis - telemetry.sunriseMillis, period
                        )
                        // The twilight shoulders: −6° to the horizon on each side, in the
                        // band's own hue at half weight, mapped through the same eased offset
                        // so they ride with everything else.
                        val comm = marsComm
                        if (comm != null) {
                            val dawnFrom = comm.twilightStartMillis
                            val duskUntil = comm.twilightEndMillis
                            if (dawnFrom in 1 until telemetry.sunriseMillis) {
                                twilightMorningStart = AstroTime.hourHandAngle(
                                    planetHoursAt(dawnFrom, mode, utcOffsetMillis), flipped
                                )
                                twilightMorningSweep =
                                    sweepFor(telemetry.sunriseMillis - dawnFrom, period)
                            }
                            if (duskUntil > telemetry.sunsetMillis) {
                                twilightEveningStart = AstroTime.hourHandAngle(
                                    planetHoursAt(telemetry.sunsetMillis, mode, utcOffsetMillis),
                                    flipped,
                                )
                                twilightEveningSweep =
                                    sweepFor(duskUntil - telemetry.sunsetMillis, period)
                            }
                        }
                    }
                }
            }
        }

        // The link telemetry: light time to Earth and the conjunction flag. Scalar astronomy in
        // the frame loop, by the moon model's own precedent; nothing here allocates.
        owltSeconds = -1
        dteBlocked = false
        if (mode == PlanetMode.MARS) {
            val linkNow = System.currentTimeMillis()
            owltSeconds = EarthSky.oneWayLightSeconds(linkNow).toInt()
            dteBlocked = EarthSky.sunEarthAngleDeg(linkNow) < EarthSky.CONJUNCTION_DEG
        }

        // Full rate while the dial is gliding, and while the face is sweeping out of always-on.
        // See [IDLE_FRAME_PERIOD_MS].
        val ambientNow = renderParameters.drawMode == DrawMode.AMBIENT
        wake.update(epochMillis, ambientNow)
        setFrameRate(
            if (transition.animating || wake.active) {
                ANIMATING_FRAME_PERIOD_MS
            } else {
                IDLE_FRAME_PERIOD_MS
            }
        )

        // Two offsets, two hands. The hour scale takes the whole zone change; the minute hand only
        // what is left inside an hour, which is nothing at all for most of the world. Sharing one
        // eased offset between them sent the minute hand round once per hour of change.
        val hoursOfDay = planetHoursAt(epochMillis, mode, utcOffsetMillis)
        val minuteHours = planetHoursAt(epochMillis, mode, transition.minuteOffsetMillis)

        val hourAngle = AstroTime.hourHandAngle(hoursOfDay, flipped)
        val minuteAngle = AstroTime.minuteHandAngle(minuteHours)

        // The duty timer runs on the wall clock, not on the instant we are asked to draw. Headless
        // preview instances render at a fixed representative time, and measuring a real shift
        // against that produced a countdown of several hundred thousand hours in the watch picker.
        val wallMillis = System.currentTimeMillis()
        val dutyState = watchShift.dutyState(wallMillis)
        val wallHourAngle = AstroTime.hourHandAngle(
            planetHoursAt(wallMillis, mode, utcOffsetMillis), flipped
        )
        var dutySpanStart = 0f
        var dutySpanSweep = 0f
        var dutyRemainingStart = 0f
        var dutyRemainingSweep = 0f
        incidentMarkCount = 0

        // No duty arc on the Moon. Arc angles are computed in whatever time base the hour hand
        // uses, so that they always agree with it — and a lunar day is a synodic month, which
        // makes an eight-hour Earth shift about four degrees of dial. Arithmetically right and
        // impossible to read, so the arc is dropped there and the duty readout carries the shift
        // on its own.
        val showDutyArc = dutyState != WatchShiftState.DUTY_OFF && mode != PlanetMode.MOON &&
            // A booked shift keeps its arc off the dial until it is within a turn of it. Past that
            // the arc says something false — see WatchShiftState.pendingArcVisible.
            (dutyState != WatchShiftState.DUTY_PENDING ||
                watchShift.pendingArcVisible(wallMillis, period))
        if (showDutyArc) {
            dutySpanStart = AstroTime.hourHandAngle(
                planetHoursAt(watchShift.startMillis, mode, utcOffsetMillis), flipped
            )
            dutySpanSweep = sweepFor(watchShift.endMillis - watchShift.startMillis, period)

            // Marks for the incidents belonging to *this* watch. The log is permanent and absolute,
            // so the shift's own window is the whole of the filter — which is also what makes a
            // mark disappear when the next watch begins, without anything having to clear it.
            val marks = vigilance.incidentTimes
            var i = 0
            while (i < marks.size && incidentMarkCount < incidentAngles.size) {
                val at = marks[i]
                if (at >= watchShift.startMillis && at <= watchShift.endMillis) {
                    incidentAngles[incidentMarkCount++] = AstroTime.hourHandAngle(
                        planetHoursAt(at, mode, utcOffsetMillis), flipped
                    )
                }
                i++
            }

            when (dutyState) {
                // Booked but not begun: none of it is served yet, so all of it is bright.
                WatchShiftState.DUTY_PENDING -> {
                    dutyRemainingStart = dutySpanStart
                    dutyRemainingSweep = dutySpanSweep
                }

                WatchShiftState.DUTY_ACTIVE -> {
                    dutyRemainingStart = wallHourAngle
                    dutyRemainingSweep = sweepFor(watchShift.endMillis - wallMillis, period)
                }

                // Served: the whole arc goes grey and nothing is highlighted.
                else -> dutyRemainingSweep = 0f
            }
        }

        // Vigilance follows the shift: it exists to watch someone on duty, and there is no sense
        // nagging anyone who is not. Reported only on a change, so a frame never pokes the service.
        val wantVigilance = vigilanceEnabled && dutyState == WatchShiftState.DUTY_ACTIVE
        // The shift's start is one of the things a change is watched for, not just something sent
        // along: ending a watch and beginning another is the case where a stale incident has to be
        // retired, and it need not pass through a frame in which no watch is running.
        if (wantVigilance != lastVigilanceRequest ||
            (wantVigilance && vigilanceIntervalMillis != lastVigilanceInterval) ||
            (wantVigilance && vibeAmplitude != lastVibeAmplitude) ||
            (wantVigilance && sosVolume != lastSosVolume) ||
            (wantVigilance && logHeartRate != lastLogHeartRate) ||
            // Not guarded by wantVigilance: a new watch has to be reported even when nothing is
            // being monitored, because that is what retires the previous watch's incident.
            watchShift.startMillis != lastShiftStart
        ) {
            lastVigilanceRequest = wantVigilance
            lastVigilanceInterval = vigilanceIntervalMillis
            lastVibeAmplitude = vibeAmplitude
            lastSosVolume = sosVolume
            lastLogHeartRate = logHeartRate
            lastShiftStart = watchShift.startMillis
            onVigilanceRequest(
                wantVigilance, vigilanceIntervalMillis, vibeAmplitude, sosVolume, logHeartRate,
                watchShift.startMillis, watchShift.endMillis,
            )
        }

        // Reported on a change only, like the vigilance request: the sensors are registered and
        // unregistered by the service, and a frame must not be able to touch a heart-rate LED.
        if (sensorLeft != lastSensorLeft || sensorRight != lastSensorRight) {
            lastSensorLeft = sensorLeft
            lastSensorRight = sensorRight
            onSensorSlots(sensorLeft, sensorRight)
        }

        // How far through the current vigilance phase we are, as a fraction of it. Arithmetic on
        // three volatile fields — no allocation, and taken from the wall clock rather than the
        // instant handed to render(), which a headless preview pins to a representative time.
        val vigilanceStatus = vigilance.status
        val vigilancePeriod = vigilance.periodMillis
        val vigilanceAlarm = vigilanceStatus == VigilanceState.ALARM
        // Asked for, but not watching. Not the same thing as switched off, and the difference is
        // the whole reason for saying it: a monitor the operator believes is armed and is not.
        val vigilanceSuspended = vigilanceEnabled &&
            (vigilanceStatus == VigilanceState.CHARGING ||
                vigilanceStatus == VigilanceState.OFF_BODY)
        // The arc answers one question and answers it wider: is anything covering me *now*. An
        // incident belongs here and not above, because nothing is watching during one either — but
        // the hub ring stays in the accent for it, since an incident is not a quiet suspension.
        val vigilanceUncovered = vigilanceSuspended ||
            (vigilanceEnabled && vigilanceStatus == VigilanceState.INCIDENT)
        val vigilanceCore = when {
            vigilanceStatus == VigilanceState.ARMED && vigilancePeriod > 0L ->
                (1f - vigilance.remainingMillis(wallMillis).toFloat() / vigilancePeriod.toFloat())
                    .coerceIn(0f, 1f)

            // Asking, or asking loudly: the hub is full either way, and the heavier ring tells
            // them apart. It never goes past full — there is no more than out of time.
            //
            // An incident is deliberately *not* here. A full hub means an answer is owed right
            // now, and nothing else may claim that: an incident is hours old, nothing is sounding,
            // and there is no interval left to be at the end of. Filled, it read as a
            // thirty-second demand that would not go away, and it hid the ring completely — the
            // core and the ring share a colour, so a full core is a solid disc.
            vigilanceStatus == VigilanceState.PROMPT ||
                vigilanceStatus == VigilanceState.ALARM -> 1f

            else -> 0f
        }

        // The comm windows, instants to angles through the same mapping as everything else on
        // the hour scale. Copied out of the shared state only when its version moves; re-angled
        // every frame so a zone glide or a dial flip carries them with the dial.
        commDteArcCount = 0
        commRelayArcCount = 0
        if (mode == PlanetMode.MARS && marsComm != null) {
            if (marsCommVersionSeen != marsComm.version) {
                marsDteCount = marsComm.copyDte(marsDteStart, marsDteEnd)
                marsRelayCount = marsComm.copyRelay(marsRelayStart, marsRelayEnd)
                marsCommVersionSeen = marsComm.version
            }
            relayWindowsValid = marsComm.relayValid
            var w = 0
            while (w < marsDteCount) {
                commDteAngles[2 * w] = AstroTime.hourHandAngle(
                    planetHoursAt(marsDteStart[w], mode, utcOffsetMillis), flipped
                )
                commDteAngles[2 * w + 1] = sweepFor(marsDteEnd[w] - marsDteStart[w], period)
                w++
            }
            commDteArcCount = marsDteCount
            if (relayWindowsValid) {
                w = 0
                while (w < marsRelayCount) {
                    commRelayAngles[2 * w] = AstroTime.hourHandAngle(
                        planetHoursAt(marsRelayStart[w], mode, utcOffsetMillis), flipped
                    )
                    commRelayAngles[2 * w + 1] =
                        sweepFor(marsRelayEnd[w] - marsRelayStart[w], period)
                    w++
                }
                commRelayArcCount = marsRelayCount
            }
        }

        // The sky marks sit at their bodies' hour angles — apparent solar time for the sun, the
        // truncated Meeus series for the moon — mapped straight onto the 24-hour scale. Hour
        // angle, not clock time: the first sun mark rode the band as a fraction of the daylight,
        // which is algebraically the *clock* hour — exactly under the hour hand, an ornament
        // restating the old point-the-hand-at-the-sun trick. Hour angle differs from the clock
        // by the equation of time plus the zone-versus-longitude offset, which is the accuracy
        // the compass actually claims — and it is zone-free, so neither mark moves during a
        // glide while the band re-sets beneath them. Each mark exists only while its body is
        // above the horizon: a compass with nothing in the sky to point at is not a degraded
        // reading, it is no reading.
        var sunTrueAngle = Float.NaN
        var sunUp = false
        // Earth only, and on Mars deliberately absent rather than unimplemented: on a mean-time
        // dial the only sun that touches the band's edges at the physical sunrise and sunset is
        // the hour hand itself — a dot there restates the hand, and the true sun's hour angle
        // hangs up to ±50 minutes clear of its own band at the horizons (Mars's equation of
        // time), which reads as a defect, not a datum. Nobody on this side of the link can
        // point the dial at the sun being marked, so the compass claim the Earth mark earns
        // its keep by is empty there. The hand, the band and the twilight shoulders carry it.
        val marksWanted = nadirEnabled && mode == PlanetMode.EARTH &&
            telemetry.daylightValid && telemetry.daylightKind == SolarTime.NORMAL
        if (marksWanted && telemetry.sunsetMillis > telemetry.sunriseMillis) {
            sunTrueAngle = AstroTime.hourHandAngle(
                AstroTime.apparentSolarDialHours(
                    wallMillis, telemetry.sunriseMillis, telemetry.sunsetMillis,
                ),
                flipped,
            )
            sunUp = wallMillis in telemetry.sunriseMillis..telemetry.sunsetMillis
        }
        val sunAngle = if (solarMarkEnabled && sunUp) sunTrueAngle else Float.NaN

        // The moon needs an observer, and its lit side needs the sun's direction even at night —
        // which is why sunTrueAngle is computed above regardless of the solar setting. Earth
        // only: MoonSky is Earth's own moon from an Earth observer, and Mars has no lunar mark
        // by design.
        var moonAngle = Float.NaN
        var moonFraction = 0f
        if (lunarMarkEnabled && mode == PlanetMode.EARTH && marksWanted && !sunTrueAngle.isNaN() &&
            !telemetry.positionLatDeg.isNaN()
        ) {
            MoonSky.compute(
                wallMillis, telemetry.positionLatDeg, telemetry.positionLonDeg, moonState,
            )
            if (moonState.altitudeDeg > 0.0) {
                moonAngle = AstroTime.hourHandAngle(12.0 + moonState.hourAngleDeg / 15.0, flipped)
                moonFraction = moonState.illuminatedFraction.toFloat()
            }
        }

        publishDescriptions(dutyState, vigilanceStatus)

        // The first tap of the clearing pair earns a short-lived answer in the status line; the
        // deadline lives in the state so the tap listener and this frame agree on it.
        val showClearHint = vigilanceStatus == VigilanceState.INCIDENT &&
            wallMillis < vigilance.clearHintUntilMillis

        // AUTO thins the always-on face after dark and never during an active watch; resolved
        // here because it needs the duty state and the wall clock. Plain comparisons, no
        // allocation.
        val halfDensity = AmbientAuto.halfDensity(
            ambientDensityMode,
            dutyState == WatchShiftState.DUTY_ACTIVE,
            telemetry.daylightValid, telemetry.daylightKind,
            telemetry.sunriseMillis, telemetry.sunsetMillis,
            wallMillis,
        )

        // How long ago the operator stopped answering, from the wall clock rather than the instant
        // handed to render(), which a headless preview pins.
        val incidentMillis = vigilance.incidentMillis
        val incidentElapsedMillis =
            if (incidentMillis == 0L) 0L else (wallMillis - incidentMillis).coerceAtLeast(0L)

        val dutyMillis = when (dutyState) {
            WatchShiftState.DUTY_ACTIVE -> watchShift.remainingMillis(wallMillis)
            WatchShiftState.DUTY_PENDING -> watchShift.untilStartMillis(wallMillis)
            else -> 0L
        }

        if (renderParameters.drawMode == DrawMode.AMBIENT) {
            // On a low-bit panel the full face is the wrong answer: intermediate alphas cannot be
            // represented, so the graduated rings and the haloed type come out as noise rather than
            // as detail. Those devices keep the sparse face.
            if (lowBitAmbient) {
                // A served arc is history; the sparse face only spends pixels on time still to serve.
                ambientLayer.draw(
                    canvas, geometry, palette, mode, epochMillis,
                    hourAngle, minuteAngle, lowBitAmbient, burnInProtection,
                    dutyArcLayer, dutyRemainingStart, dutyRemainingSweep,
                )
                return
            }
            drawFullFace(
                canvas, epochMillis, hourAngle, minuteAngle, showDutyArc, dutyState, dutyMillis,
                dutySpanStart, dutySpanSweep, dutyRemainingStart, dutyRemainingSweep,
                vigilanceCore, vigilanceAlarm, vigilanceSuspended, vigilanceUncovered,
                incidentMillis, incidentElapsedMillis, showClearHint,
                sunAngle, moonAngle, moonFraction, sunTrueAngle,
                ambient = true,
                halfDensity = halfDensity,
            )
            return
        }

        drawFullFace(
            canvas, epochMillis, hourAngle, minuteAngle, showDutyArc, dutyState, dutyMillis,
            dutySpanStart, dutySpanSweep, dutyRemainingStart, dutyRemainingSweep,
            vigilanceCore, vigilanceAlarm, vigilanceSuspended, vigilanceUncovered,
            incidentMillis, incidentElapsedMillis, showClearHint,
            sunAngle, moonAngle, moonFraction, sunTrueAngle,
            ambient = false,
            halfDensity = false,
        )

        // Seconds are one triangular cursor stepping from tick to tick — no hand, no filled ring.
        // Taken off the minute offset, which never has a fractional minute in it, so the cursor
        // holds perfectly still while the rest of the dial re-sets.
        secondsMarker.draw(
            canvas, geometry, palette, AstroTime.secondFraction(minuteHours), layoutGeneration
        )

        // Coming out of always-on, a brightness front sweeps out from the hub over half a second
        // instead of the face arriving at full strength in one frame. Only the light changes:
        // always-on wears the same hues as interactive, so there is nothing to cross-fade.
        if (wake.active) {
            ambientLayer.applyWakeVeil(
                canvas, geometry, wake.brightnessRadius, wake.veilAlpha, layoutGeneration,
            )
        }
    }

    /**
     * The dial, the readout and the hands, shared by both draw modes.
     *
     * Always-on shows the same face as interactive with exactly two things taken away.
     *
     * The **seconds cursor** goes because ambient draws once a minute: a cursor that steps once a
     * second would sit frozen on a second that passed long ago, which is worse than absent.
     *
     * The **background wash** goes because it is the one element that costs a great deal and says
     * nothing. Measured off a real 454 px capture it accounts for about a third of all the light
     * the face emits, spread over most of the dial, and it is a gradient — there is nothing in it
     * to read. On an OLED, black is the panel switched off, so the ground goes black in ambient and
     * every element that does carry information — all 84 ticks, the 24 numerals, the four rows of
     * type, the daylight band, the duty arc, the hands — stays exactly as it is interactively.
     *
     * The two bezel rings live in the same cached bitmap as the wash and go with it. They are
     * decoration; the tick ring already describes the edge of the dial.
     */
    private fun drawFullFace(
        canvas: Canvas,
        epochMillis: Long,
        hourAngle: Float,
        minuteAngle: Float,
        showDutyArc: Boolean,
        dutyState: Int,
        dutyMillis: Long,
        dutySpanStart: Float,
        dutySpanSweep: Float,
        dutyRemainingStart: Float,
        dutyRemainingSweep: Float,
        vigilanceCore: Float,
        vigilanceAlarm: Boolean,
        vigilanceSuspended: Boolean,
        vigilanceUncovered: Boolean,
        incidentMillis: Long,
        incidentElapsedMillis: Long,
        showClearHint: Boolean,
        sunAngle: Float,
        moonAngle: Float,
        moonFraction: Float,
        moonLimbToward: Float,
        ambient: Boolean,
        halfDensity: Boolean,
    ) {
        val mode = planetMode
        val p = if (ambient) ambientPalette else palette

        if (ambient) {
            // Nothing cached and nothing translated. The scale is drawn live because always-on
            // wants it in a different palette and renders once a minute, so a second pair of
            // 454 x 454 bitmaps would cost memory to save time that is not scarce.
            canvas.drawColor(Color.BLACK)
            if (twilightMorningSweep > 0f) {
                daylightLayer.draw(
                    canvas, geometry, p.twilightBand, twilightMorningStart, twilightMorningSweep,
                )
            }
            if (twilightEveningSweep > 0f) {
                daylightLayer.draw(
                    canvas, geometry, p.twilightBand, twilightEveningStart, twilightEveningSweep,
                )
            }
            daylightLayer.draw(
                canvas, geometry, p.daylightBand,
                daylightDrawStart, daylightDrawSweep,
            )
            if (!sunAngle.isNaN()) {
                daylightLayer.drawSun(canvas, geometry, p.sunMark, p.background, sunAngle)
            }
            if (!moonAngle.isNaN()) {
                daylightLayer.drawMoon(
                    canvas, geometry, p.moonMark, p.background,
                    moonAngle, moonFraction, moonLimbToward,
                )
            }
            dialLayer.drawScaleDirect(canvas, geometry, p, midnightAs24)
        } else {
            // One cache check for both bitmaps, before either is handed to the canvas: rebuilding
            // between the two blits recycled a bitmap the frame was still going to draw.
            dialLayer.prepare(geometry, palette, styleGeneration, midnightAs24)
            canvas.drawBitmap(dialLayer.background(), 0f, 0f, null)
            if (twilightMorningSweep > 0f) {
                daylightLayer.draw(
                    canvas, geometry, p.twilightBand, twilightMorningStart, twilightMorningSweep,
                )
            }
            if (twilightEveningSweep > 0f) {
                daylightLayer.draw(
                    canvas, geometry, p.twilightBand, twilightEveningStart, twilightEveningSweep,
                )
            }
            daylightLayer.draw(
                canvas, geometry, p.daylightBand,
                daylightDrawStart, daylightDrawSweep,
            )
            if (!sunAngle.isNaN()) {
                daylightLayer.drawSun(canvas, geometry, p.sunMark, p.background, sunAngle)
            }
            if (!moonAngle.isNaN()) {
                daylightLayer.drawMoon(
                    canvas, geometry, p.moonMark, p.background,
                    moonAngle, moonFraction, moonLimbToward,
                )
            }
            canvas.drawBitmap(dialLayer.scale(), 0f, 0f, null)
        }

        if (showDutyArc) {
            val spanColor = if (dutyState == WatchShiftState.DUTY_SERVED) {
                p.dutyArcSpent
            } else {
                dutyArcLayer.spanColorFor(p.dutyArc)
            }
            val arcWidth = geometry.r * StyleSchema.DUTY_ARC_WIDTH_FRACTION
            dutyArcLayer.draw(
                canvas, geometry, spanColor, p.dutyArc, arcWidth,
                dutySpanStart, dutySpanSweep, dutyRemainingStart, dutyRemainingSweep,
                uncovered = vigilanceUncovered,
            )
            dutyArcLayer.drawIncidents(
                canvas, geometry, p.incidentMark, arcWidth, incidentAngles, incidentMarkCount
            )
        }

        // The comm lines ride the tick ring's edges, in both draw modes — the windows are what
        // this instrument is for, and ambient is exactly when a wrist gets glanced at. Counts
        // are zero on any world but Mars, so the calls cost nothing elsewhere.
        if (commDteArcCount > 0 || commRelayArcCount > 0) {
            // In conjunction the geometry still holds — Earth is above the horizon — but the
            // corona is in the way, so the DTE line thins to a hairline: the same idiom as the
            // duty arc's uncovered width, a line that says what can actually pass.
            val dteStroke = if (dteBlocked) {
                geometry.commStrokeWidth * CONJUNCTION_STROKE_FRACTION
            } else {
                geometry.commStrokeWidth
            }
            commWindowLayer.draw(
                canvas, geometry.commInnerTrack, p.commWindow, dteStroke,
                commDteAngles, commDteArcCount,
            )
            commWindowLayer.draw(
                canvas, geometry.commOuterTrack, p.commWindow, geometry.commStrokeWidth,
                commRelayAngles, commRelayArcCount,
            )
        }

        // Printed on the dial, under the hands, the way dial text always is. The layer haloes its
        // own type in the background colour, which keeps it separated from the ticks and the arc.
        telemetryLayer.draw(
            canvas, geometry, p, telemetry, mode, epochMillis,
            fahrenheit, mmHg, dutyState, dutyMillis, vigilance.status, showClearHint,
            incidentMillis, incidentElapsedMillis, sensorLeft, sensorRight, layoutGeneration,
            frameSymbol, roverIndex, relayWindowsValid, owltSeconds, dteBlocked,
        )

        handsLayer.drawHourMinute(canvas, geometry, p, hourAngle, minuteAngle)
        handsLayer.drawHub(
            canvas, geometry, p, vigilanceCore, vigilanceAlarm, vigilanceSuspended
        )

        // Last, so it thins everything above it at once.
        if (ambient && halfDensity) {
            ambientLayer.applyHalfDensity(canvas, geometry, epochMillis)
        }
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: Assets,
    ) {
        // No complication slots, so there is nothing to highlight; just clear the layer.
        canvas.drawColor(Color.TRANSPARENT)
    }

    /**
     * Hours since local midnight on the selected world, in `[0, 24)`.
     *
     * Earth uses the device's UTC offset rather than the [ZonedDateTime]'s fields, because the
     * duty arc needs this for arbitrary past and future instants, not just for "now".
     */
    private fun planetHoursAt(epochMillis: Long, mode: Int, utcOffsetMillis: Long): Double =
        when (mode) {
            // The selected rover's mean clock, not MTC: the operator works the rover's sol, and
            // every instant on the dial — hour hand, duty arc, daylight, comm windows — maps
            // through this one line, so they can never disagree about whose time it is. The
            // offset is the rover meridian in Mars milliseconds, eased by the transition, which
            // is what makes a rover switch glide instead of snap; settled, it equals
            // MarsSolarTime.lmstHours exactly.
            PlanetMode.MARS ->
                (MarsSolarTime.mtcHours(epochMillis) +
                    utcOffsetMillis / MarsSolarTime.MARS_HOUR_MILLIS).mod(24.0)
            PlanetMode.MOON -> AstroTime.lunarTimeHours(epochMillis)
            else -> AstroTime.localHoursOfDay(epochMillis, utcOffsetMillis)
        }

    /**
     * Speaks the two states the dial otherwise only draws: the duty, and anything the vigilance
     * monitor is holding. The library already provides a label for the time itself.
     *
     * Rebuilt only when a state changes — a handful of times per watch — which is the one
     * exception this file makes to "render() allocates nothing": TalkBack has no way to read a
     * preallocated buffer, and a transition is rare enough that the pair of small objects is
     * cheaper than a second reporting path.
     */
    private fun publishDescriptions(dutyState: Int, vigilanceStatus: Int) {
        if (dutyState == lastSpokenDuty && vigilanceStatus == lastSpokenVigilance) return
        lastSpokenDuty = dutyState
        lastSpokenVigilance = vigilanceStatus

        val labels = ArrayList<Pair<Int, ContentDescriptionLabel>>(2)
        val duty = when (dutyState) {
            WatchShiftState.DUTY_ACTIVE -> descriptions.onDuty
            WatchShiftState.DUTY_PENDING -> descriptions.dutyBooked
            else -> descriptions.offDuty
        }
        labels.add(
            0 to ContentDescriptionLabel(
                PlainComplicationText.Builder(duty).build(), geometry.a11yReadoutBounds, null,
            )
        )
        // Only the states that are not self-evident and not transient: an incident and a bare
        // wrist can each stand for hours, which is exactly when a spoken label is worth having.
        val vigil = when (vigilanceStatus) {
            VigilanceState.INCIDENT -> descriptions.manDown
            VigilanceState.OFF_BODY -> descriptions.offWrist
            else -> null
        }
        if (vigil != null) {
            labels.add(
                1 to ContentDescriptionLabel(
                    PlainComplicationText.Builder(vigil).build(), geometry.a11yStatusBounds, null,
                )
            )
        }
        additionalContentDescriptionLabels = labels
    }

    private fun setFrameRate(periodMillis: Long) {
        if (interactiveDrawModeUpdateDelayMillis != periodMillis) {
            interactiveDrawModeUpdateDelayMillis = periodMillis
        }
    }

    /** Length of one full turn of the hour hand, in Earth milliseconds. */
    private fun planetPeriodMillis(mode: Int): Double = when (mode) {
        PlanetMode.MARS -> AstroTime.SOL_IN_MILLIS
        PlanetMode.MOON -> AstroTime.LUNAR_DAY_IN_MILLIS
        else -> AstroTime.MILLIS_PER_DAY
    }

    /**
     * Arc sweep for a duration, clamped to a full turn. Computed from the duration rather than from
     * the difference of two angles, so a watch of exactly one revolution reads as 360°, not 0°.
     */
    private fun sweepFor(durationMillis: Long, periodMillis: Double): Float {
        if (durationMillis <= 0L) return 0f
        val sweep = durationMillis / periodMillis * 360.0
        return if (sweep >= 360.0) 360f else sweep.toFloat()
    }

    companion object {
        private const val TAG = "MfdRenderer"

        /** The blocked DTE line's width, as a fraction of its normal stroke. */
        private const val CONJUNCTION_STROKE_FRACTION = 0.35f

        /**
         * Resting frame period: once a second.
         *
         * Nothing on this dial moves faster. The seconds cursor steps once a second by
         * construction, and the library rounds any period of 500 ms or more onto its own boundary,
         * so a 1000 ms period lands the frame *on* the second rather than drifting across it. The
         * only continuously moving element is the minute hand, and at a tenth of a degree per
         * second it covers about a third of a pixel between frames — below the resolution of the
         * panel, never mind the eye.
         *
         * The old 16 ms was in the brief, and it cost sixty redraws a second to animate nothing.
         * The system still has the last word: the library clamps the rate further when the battery
         * is low and not charging.
         */
        const val IDLE_FRAME_PERIOD_MS: Long = 1000L

        /**
         * Full rate, used only while [DialTransition] is easing — a time-zone change, or the Nadir
         * band sliding to a new position. Those are the one thing on the face that genuinely wants
         * smooth motion, and they last four seconds.
         */
        const val ANIMATING_FRAME_PERIOD_MS: Long = 16L
    }
}

/**
 * The strings the renderer can speak through TalkBack, resolved by the service.
 *
 * A class rather than reading resources here because a [Renderer] has no `Context` of its own,
 * and handing it one for five strings would be handing it everything.
 */
class FaceDescriptions(
    val onDuty: String,
    val dutyBooked: String,
    val offDuty: String,
    val manDown: String,
    val offWrist: String,
)
