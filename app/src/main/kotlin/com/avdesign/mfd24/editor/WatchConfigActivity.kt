// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.editor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.watchface.editor.EditorSession
import androidx.wear.watchface.style.UserStyleSetting
import com.avdesign.mfd24.BuildConfig
import com.avdesign.mfd24.R
import com.avdesign.mfd24.export.LogExportActivity
import com.avdesign.mfd24.data.VitalStore
import com.avdesign.mfd24.health.RawDayExportActivity
import com.avdesign.mfd24.health.VitalGraphsActivity
import com.avdesign.mfd24.export.RepoLinkActivity
import com.avdesign.mfd24.update.ReleaseLinkActivity
import com.avdesign.mfd24.update.UpdateStore
import com.avdesign.mfd24.data.SensorSlots
import com.avdesign.mfd24.data.TelemetryRepository
import com.avdesign.mfd24.data.TelemetryState
import com.avdesign.mfd24.data.WatchShiftController
import com.avdesign.mfd24.data.IncidentRecord
import com.avdesign.mfd24.data.VigilanceMonitor
import com.avdesign.mfd24.data.VigilanceStore
import com.avdesign.mfd24.data.WatchShiftState
import com.avdesign.mfd24.render.WakeTransition
import com.avdesign.mfd24.style.Palette
import com.avdesign.mfd24.style.StyleSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * On-watch configuration, reached through the system's long-press "Customise" affordance.
 *
 * Everything it edits lives in the watch face's [androidx.wear.watchface.style.UserStyleSchema], so
 * the session persists the changes and the running watch face picks them up live — this screen owns
 * no settings of its own.
 *
 * It also collects the location permission, which the watch face itself cannot: a
 * `WallpaperService` has no UI to put a runtime-permission dialog on top of.
 */
class WatchConfigActivity : ComponentActivity() {

    private lateinit var requestLocation: ActivityResultLauncher<String>
    private val locationGranted: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Every other runtime permission goes through one launcher, for the same reason the location
     * one is here at all: a `WallpaperService` has no UI to put a permission dialog on, so the
     * watch face can never ask for anything itself. Sensor permissions are requested when a slot
     * is set to the reading that needs them; background location when the user asks for it from
     * the position section; notifications and activity recognition when vigilance goes on, because
     * API 33 gates the foreground notice and API 34 gates the health service type itself.
     */
    private lateinit var requestPermission: ActivityResultLauncher<String>
    private val grantCount: MutableState<Int> = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            locationGranted.value = it
        }
        locationGranted.value = hasLocationPermission()

        // The count is a nudge for recomposition, not a value: which permissions are held is read
        // straight from the package manager, and this only says that the answer may have changed.
        requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            grantCount.value = grantCount.value + 1
        }

        lifecycleScope.launch {
            val session = try {
                EditorSession.createOnWatchEditorSession(this@WatchConfigActivity)
            } catch (e: Exception) {
                Log.e(TAG, "Not launched as a watch face editor", e)
                finish()
                return@launch
            }

            val watchShift = WatchShiftController.get(this@WatchConfigActivity)
            val repository = TelemetryRepository.get(this@WatchConfigActivity)

            setContent {
                MaterialTheme(colors = CockpitColors) {
                    ConfigScreen(
                        session = session,
                        watchShift = watchShift,
                        repository = repository,
                        locationGranted = locationGranted.value,
                        onRequestLocation = {
                            requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        grants = grantCount.value,
                        onRequestPermission = { requestPermission.launch(it) },
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        locationGranted.value = hasLocationPermission()
        // Background location is granted on a settings screen, not in a dialog, so the result
        // arrives as a resume rather than through the launcher: nudge the readers to re-check.
        grantCount.value = grantCount.value + 1
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "WatchConfigActivity"
    }
}

/** Ending a duty on purpose is a decision, not an emergency, so it is not the alarm red. */
private val Terracotta = Color(0xFFB85C38)

/**
 * Which face this build's editor sells. A compile-time constant, so the duty half and the
 * wellness half of the list are decided at build time and R8 drops whichever is dead.
 */
private val VITAL_FACE = BuildConfig.WORLD == "vital"

@Composable
private fun ConfigScreen(
    session: EditorSession,
    watchShift: WatchShiftController,
    repository: TelemetryRepository,
    locationGranted: Boolean,
    onRequestLocation: () -> Unit,
    grants: Int,
    onRequestPermission: (String) -> Unit,
    onDone: () -> Unit,
) {
    // The on-watch EditorSession exposes `userStyle` through a proxy that does not implement
    // `collect` (the library logs "userStyle proxy encountered unexpected method name 'collect'"),
    // so the flow cannot drive recomposition. This screen is the only writer, so it mirrors the
    // selections locally and pushes each change into the session.
    var palette by rememberOption(session, StyleSchema.PALETTE, Palette.ID_AMBER)
    var tempUnit by rememberOption(session, StyleSchema.TEMP_UNIT, StyleSchema.TEMP_CELSIUS)
    var pressureUnit by rememberOption(session, StyleSchema.PRESSURE_UNIT, StyleSchema.PRESSURE_HPA)
    var midnightLabel by rememberOption(
        session, StyleSchema.MIDNIGHT_LABEL, StyleSchema.MIDNIGHT_AS_00
    )
    var dialTop by rememberOption(session, StyleSchema.DIAL_TOP, StyleSchema.DIAL_TOP_NOON)
    var nadir by rememberOption(session, StyleSchema.NADIR, StyleSchema.NADIR_OFF)
    var solarMark by rememberOption(session, StyleSchema.SOLAR_MARK, StyleSchema.SOLAR_OFF)
    var lunarMark by rememberOption(session, StyleSchema.LUNAR_MARK, StyleSchema.LUNAR_OFF)
    var vigilance by rememberOption(session, StyleSchema.VIGILANCE, StyleSchema.VIGILANCE_OFF)
    var vigilanceInterval by rememberOption(
        session, StyleSchema.VIGILANCE_INTERVAL, StyleSchema.VIGILANCE_INTERVAL_DEFAULT
    )
    var vibeStrength by rememberOption(session, StyleSchema.VIBE_STRENGTH, StyleSchema.VIBE_MED)
    var sosSound by rememberOption(session, StyleSchema.SOS_SOUND, StyleSchema.SOS_SOUND_HIGH)
    var logHeartRate by rememberOption(
        session, StyleSchema.LOG_HEART_RATE, StyleSchema.LOG_HR_OFF
    )
    var weather by rememberOption(session, StyleSchema.WEATHER, StyleSchema.WEATHER_ON)
    var alarmMark by rememberOption(session, StyleSchema.ALARM_MARK, StyleSchema.ALARM_OFF)
    var calendarMarks by rememberOption(
        session, StyleSchema.CALENDAR_MARKS, StyleSchema.CALENDAR_OFF
    )
    var record by rememberOption(session, StyleSchema.RECORD, StyleSchema.RECORD_OFF)
    var recordInterval by rememberOption(
        session, StyleSchema.RECORD_INTERVAL, StyleSchema.RECORD_INTERVAL_DEFAULT
    )
    var sleepOffBody by rememberOption(
        session, StyleSchema.SLEEP_OFFBODY, StyleSchema.SLEEP_OFFBODY_OFF
    )

    var ambientDensity by rememberOption(
        session, StyleSchema.AMBIENT_DENSITY, StyleSchema.AMBIENT_FULL
    )

    // Position state, held in hundredths of a degree: one step is about 1.1 km of latitude and
    // less of longitude away from the equator, which fits inside the 5 km site lock. It used to be
    // tenths, and 11 km of quantisation is what forced a manual position to forgo that row.
    var manualActive by remember { mutableStateOf(repository.manualPositionSelected) }
    // Seeded from whatever position is in force when there is no manual one on file yet: even
    // with the hold-to-repeat, starting from 0.00 is the Gulf of Guinea and seconds of travel.
    var manualLat by remember {
        mutableIntStateOf(toHundredths(repository.manualLatitude(), repository.currentLatitude()))
    }
    var manualLon by remember {
        mutableIntStateOf(toHundredths(repository.manualLongitude(), repository.currentLongitude()))
    }
    var hasPosition by remember { mutableStateOf(repository.state.hasPosition) }

    // What is actually in force. Mirrored into Compose state for the same reason the style
    // selections are: TelemetryState is a plain volatile holder, and a lazy list item that merely
    // read it would never be told to recompose.
    var positionSource by remember { mutableIntStateOf(repository.state.positionSource) }

    // Set when a tap on AUTO had to detour through the permission dialog, so the grant can finish
    // what the tap began. Without it the switch stayed on MANUAL after the user said yes, and the
    // second tap it silently waited for looked like the first one not working.
    var autoRequested by remember { mutableStateOf(false) }

    // Only a *transition* into granted is interesting. Keyed on the flag alone this would also
    // fire on every open of the editor with permission already held, and waking the GPS because
    // somebody looked at the settings is exactly the background work the brief rules out.
    var grantSeen by remember { mutableStateOf(locationGranted) }
    LaunchedEffect(locationGranted) {
        if (locationGranted && !grantSeen) {
            if (autoRequested) {
                autoRequested = false
                repository.manualPositionSelected = false
            }
            // The user has just said yes and is watching this screen: go and get a real position
            // now rather than leaving NO SITE up until the half-hourly worker next runs.
            runCatching { repository.onLocationGranted() }
            hasPosition = repository.state.hasPosition
            positionSource = repository.state.positionSource
            manualActive = repository.manualPositionSelected
        }
        grantSeen = locationGranted
    }
    // Duty length lives in the controller's own storage, not in the style: it is timer
    // configuration, and the custom figure has to survive a trip through the presets.
    var durationPreset by remember { mutableStateOf(watchShift.durationPreset) }
    var customMillis by remember { mutableLongStateOf(watchShift.customDurationMillis) }
    val selectedMillis = WatchShiftController.presetMillis(durationPreset) ?: customMillis

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dutyState by remember { mutableIntStateOf(watchShift.state.dutyState(nowMillis)) }

    // Booked start, held locally: it is a specific instant, not a style, and it only matters until
    // the user presses BOOK START. Defaults to the next round five minutes an hour from now.
    var bookedStart by remember { mutableLongStateOf(defaultBookedStart(nowMillis)) }

    // Keep the countdown on the chip honest while the screen is open.
    LaunchedEffect(dutyState) {
        while (true) {
            delay(20_000L)
            nowMillis = System.currentTimeMillis()
            dutyState = watchShift.state.dutyState(nowMillis)
        }
    }

    // Read once per opening of the editor. The monitor loads it at process start and only the
    // service ever appends, so there is nothing here to keep live.
    val editorContext = LocalContext.current
    // Not a style setting: a session is an instant in device-protected storage, read straight
    // from the store so the face and this screen cannot disagree about whether a night is open.
    var sleepTracking by remember {
        mutableStateOf(VitalStore(editorContext).sleepSessionRunning(System.currentTimeMillis()))
    }
    var sensorLeft by rememberOption(session, StyleSchema.SENSOR_LEFT, SensorSlots.Kind.OFF.id)
    var sensorRight by rememberOption(session, StyleSchema.SENSOR_RIGHT, SensorSlots.Kind.OFF.id)
    val vigilanceMonitor = remember { VigilanceMonitor.get(editorContext) }
    var incidents by remember { mutableStateOf(vigilanceMonitor.state.incidents) }

    // The release the daily check found, if any. It is named on the ABOUT version row and nowhere
    // else: a banner at the top of this list was worth its interruption only while it could put one
    // tap between hearing about a release and installing it, and Wear OS took the second half away.
    // Somebody who opens the settings has come to change a setting.
    val pendingUpdate = remember { UpdateStore.pendingVersion(editorContext) }
    var updateCheck by remember { mutableStateOf(UpdateStore.checkEnabled(editorContext)) }
    var hints by remember { mutableStateOf(EditorPrefs.hintsShown(editorContext)) }


    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    // Which section is open. One at a time, and not remembered between openings of the editor: a
    // fresh activity every time means the list always starts in the same shape, which is worth more
    // here than continuity — the rows are navigated by looking at where they are, and a layout that
    // depended on what was expanded last week would be unreadable.
    var openSection by remember { mutableIntStateOf(SECTION_DUTY) }

    // Toggling never scrolls towards the freshly opened content. It used to, and the whole list
    // lurched on every tap: the eye had just committed to a spot and the spot moved. Instead the
    // tapped header is pinned exactly where the finger found it — even at the bottom of the
    // screen, even though that leaves the opened rows below the fold — by re-anchoring the list
    // one frame after the sections reflow. Headers carry keys because a header's *index* moves
    // when a section above it collapses; its key does not.
    val toggleSection: (Int) -> Unit = { id ->
        val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == headerKey(id) }
        openSection = if (openSection == id) SECTION_NONE else id
        if (anchor != null) {
            scope.launch {
                runCatching {
                    // Re-pinned every frame for a handful of frames rather than once. Correcting
                    // after a single frame still let one frame of the jump reach the screen, and
                    // one frame of a header sliding is exactly what the eye catches; the list also
                    // settles over more than one pass when the section that closed was taller than
                    // the one that opened. Cheap: it stops as soon as the offset stops moving.
                    repeat(PIN_FRAMES) {
                        withFrameNanos { }
                        val moved = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == headerKey(id) } ?: return@runCatching
                        if (moved.offset == anchor.offset) return@runCatching
                        listState.scrollToItem(moved.index, anchor.offset)
                    }
                }
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 24.dp),
        // The scroll boundary is anchored to item 0 — the DUTY CONTROL header, whose size never
        // changes — rather than the default item 1, which is the first *row* of whatever is open.
        // Anchored there, expanding the first section changed the boundary's height, and the
        // whole list jumped to the re-clamped position on every open: the one header the pin
        // below could not hold, because a clamp outranks a scroll.
        autoCentering = AutoCenteringParams(itemIndex = 0),
    ) {
        // ---- Duty ------------------------------------------------------------------------------
        // Both actions in this section spend the same duration, so it is chosen once at the top and
        // neither START NOW nor ARM TIMER carries a length of its own. Scheduling used to be a
        // section two screens further down, which put the figure it spends out of sight of the rows
        // that set it.
        // ---- Recorder --------------------------------------------------------------------------
        // First on the wellness face, where duty control sits on the other one: everything the
        // trail and the report can say depends on whether this is switched on, and it costs a
        // service and an LED, so it is asked about before anything cosmetic.
        if (VITAL_FACE) {
            item(key = headerKey(SECTION_RECORDER)) {
                SectionHeader(
                    text = stringResource(R.string.editor_section_recorder),
                    expanded = openSection == SECTION_RECORDER,
                    onClick = { toggleSection(SECTION_RECORDER) },
                )
            }
            if (openSection == SECTION_RECORDER) {
                item {
                    SegmentedSetting(
                        label = stringResource(R.string.editor_label_record),
                        options = RECORD_OPTIONS,
                        selectedId = record,
                        onSelect = { id ->
                            session.select(StyleSchema.RECORD, id)
                            record = id
                            // The two grants the recorder needs are the ones the monitor asks
                            // for: a watch face cannot raise a dialog, so the switch and the
                            // request live in the same row.
                            if (id == StyleSchema.RECORD_ON) {
                                if (!heldPermission(
                                        editorContext, Manifest.permission.BODY_SENSORS, grants,
                                    )
                                ) {
                                    onRequestPermission(Manifest.permission.BODY_SENSORS)
                                } else if (!heldPermission(
                                        editorContext,
                                        Manifest.permission.ACTIVITY_RECOGNITION,
                                        grants,
                                    )
                                ) {
                                    onRequestPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                                }
                            }
                        },
                    )
                }
                item { Hint(stringResource(R.string.editor_record_rationale), hints) }
                // The day's three records, side on. A row rather than a gesture on the face:
                // this is where somebody goes deliberately, and the dial's own double tap is
                // already spoken for by the report.
                item {
                    OptionChip(
                        label = stringResource(R.string.editor_graphs),
                        selected = false,
                        onClick = {
                            editorContext.startActivity(
                                Intent(editorContext, VitalGraphsActivity::class.java)
                            )
                        },
                    )
                }
                // And the grid the graphs are drawn from, unread and uninferred: the thing to
                // send somebody when the argument is about why a night came back wrong.
                item {
                    OptionChip(
                        label = stringResource(R.string.editor_raw_export),
                        selected = false,
                        onClick = {
                            editorContext.startActivity(
                                Intent(editorContext, RawDayExportActivity::class.java)
                            )
                        },
                    )
                }
                if (record == StyleSchema.RECORD_ON) {
                    item {
                        SegmentedSetting(
                            label = stringResource(R.string.editor_label_record_interval),
                            options = RECORD_INTERVAL_OPTIONS,
                            selectedId = recordInterval,
                            onSelect = { id ->
                                session.select(StyleSchema.RECORD_INTERVAL, id)
                                recordInterval = id
                            },
                        )
                    }
                    // Under the interval, because it only means anything to somebody already
                    // recording, and off unless they say otherwise: it trades most of what makes
                    // the sleep reading trustworthy for an answer where there would be none.
                    item {
                        SegmentedSetting(
                            label = stringResource(R.string.editor_label_offbody),
                            options = OFFBODY_OPTIONS,
                            selectedId = sleepOffBody,
                            onSelect = { id ->
                                session.select(StyleSchema.SLEEP_OFFBODY, id)
                                sleepOffBody = id
                            },
                        )
                    }
                    item {
                        Hint(stringResource(R.string.editor_offbody_rationale), hints)
                    }
                    // The declared night. A chip rather than a switch because it is an act with a
                    // time on it — "I am going to bed now" — and not a preference.
                    item {
                        OptionChip(
                            label = stringResource(
                                if (sleepTracking) {
                                    R.string.editor_track_sleep_on
                                } else {
                                    R.string.editor_track_sleep
                                }
                            ),
                            selected = sleepTracking,
                            onClick = {
                                val now = System.currentTimeMillis()
                                val store = VitalStore(editorContext)
                                if (sleepTracking) store.endSleepSession(now)
                                else store.startSleepSession(now)
                                sleepTracking = !sleepTracking
                            },
                        )
                    }
                    item {
                        Hint(stringResource(R.string.editor_track_sleep_rationale), hints)
                    }
                }
            }
        }

        item(key = headerKey(SECTION_DUTY)) {
            SectionHeader(
                text = stringResource(R.string.editor_section_watch),
                expanded = openSection == SECTION_DUTY,
                onClick = { toggleSection(SECTION_DUTY) },
            )
        }
        if (openSection == SECTION_DUTY) {

            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_duration),
                    options = DURATION_PRESETS,
                    selectedId = durationPreset,
                    onSelect = { id ->
                        durationPreset = id
                        watchShift.durationPreset = id
                    },
                )
            }
            // The custom row appears only when CST is chosen, directly under the presets it belongs to.
            if (durationPreset == WatchShiftController.PRESET_CUSTOM) {
                item {
                    StepperRow(
                        value = stringResource(
                            R.string.editor_duration_value,
                            (customMillis / 3_600_000L).toInt(),
                            (customMillis % 3_600_000L / 60_000L).toInt(),
                        ),
                        onDown = { n ->
                            customMillis = (customMillis - n * WatchShiftController.STEP_MILLIS)
                                .coerceAtLeast(WatchShiftController.MIN_MILLIS)
                        },
                        onUp = { n ->
                            customMillis = (customMillis + n * WatchShiftController.STEP_MILLIS)
                                .coerceAtMost(WatchShiftController.MAX_MILLIS)
                        },
                        maxStep = DURATION_MAX_STEP,
                        // Written on release rather than per tick, and written at all so that CST comes
                        // back to the figure it was left at.
                        onCommit = { watchShift.customDurationMillis = customMillis },
                    )
                }
            }

            item { Label(stringResource(R.string.editor_label_schedule)) }
            // Both steppers floor at the next bookable instant, read off a live clock rather
            // than the 20-second `nowMillis`: stepping below "now" would arm a start that
            // schedule() converts into an immediate, chiming start — a mis-tap in disguise.
            item {
                StepperRow(
                    value = formatBookedDate(bookedStart),
                    onDown = { n ->
                        bookedStart = (bookedStart - n * DAY_MILLIS)
                            .coerceAtLeast(earliestBookableNow())
                    },
                    onUp = { n -> bookedStart += n * DAY_MILLIS },
                )
            }
            // One HH:MM row rather than an hour row and a minute row: the hold accelerates fast enough
            // to cross half a day in a couple of seconds, so splitting them bought nothing but height.
            item {
                StepperRow(
                    value = stringResource(
                        R.string.editor_start_time, hourOf(bookedStart), minuteOf(bookedStart)
                    ),
                    onDown = { n ->
                        bookedStart = (bookedStart - n * WatchShiftController.STEP_MILLIS)
                            .coerceAtLeast(earliestBookableNow())
                    },
                    onUp = { n -> bookedStart += n * WatchShiftController.STEP_MILLIS },
                    maxStep = DURATION_MAX_STEP,
                )
            }
            item {
                OptionChip(
                    label = stringResource(R.string.editor_schedule_watch),
                    selected = dutyState == WatchShiftState.DUTY_PENDING,
                    onClick = {
                        // The chosen minute may have slipped past while the editor sat open;
                        // re-clamp so arming books the next step instead of starting now.
                        bookedStart = bookedStart.coerceAtLeast(earliestBookableNow())
                        watchShift.schedule(bookedStart, selectedMillis)
                        nowMillis = System.currentTimeMillis()
                        dutyState = watchShift.state.dutyState(nowMillis)
                    },
                )
            }
            // The section's conclusion, and so its last row: everything above decides what it will do.
            item {
                val running = dutyState == WatchShiftState.DUTY_ACTIVE ||
                    dutyState == WatchShiftState.DUTY_PENDING
                val secondary = dutySecondaryText(dutyState, watchShift, nowMillis)
                Chip(
                    onClick = {
                        if (running) watchShift.cancel() else watchShift.start(selectedMillis)
                        nowMillis = System.currentTimeMillis()
                        dutyState = watchShift.state.dutyState(nowMillis)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (running) {
                        // Terracotta rather than the palette's alarm red: ending a duty on purpose is
                        // a decision, not an emergency.
                        ChipDefaults.primaryChipColors(backgroundColor = Terracotta)
                    } else {
                        ChipDefaults.primaryChipColors()
                    },
                    label = {
                        Text(
                            text = stringResource(
                                if (running) R.string.editor_end_watch else R.string.editor_start_watch
                            ),
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = if (secondary != null) {
                        { Text(text = secondary, maxLines = 1) }
                    } else {
                        null
                    },
                )
            }
        }


        // ---- Vigilance -------------------------------------------------------------------------
        // Directly after the duty it watches over, rather than between the two ways of starting one.
        item(key = headerKey(SECTION_VIGILANCE)) {
            SectionHeader(
                text = stringResource(R.string.editor_section_vigilance),
                expanded = openSection == SECTION_VIGILANCE,
                onClick = { toggleSection(SECTION_VIGILANCE) },
            )
        }
        if (openSection == SECTION_VIGILANCE) {
            item {
                SegmentedRow(
                    options = VIGILANCE_OPTIONS,
                    selectedId = vigilance,
                    onSelect = { id ->
                        session.select(StyleSchema.VIGILANCE, id)
                        vigilance = id
                        // What newer platforms gate the monitor on, asked for at the moment of
                        // switching it on: API 34 refuses the health foreground service without
                        // ACTIVITY_RECOGNITION, and API 33 hides its notification without
                        // POST_NOTIFICATIONS. One at a time - a second launch cancels the first.
                        if (id == StyleSchema.VIGILANCE_ON) {
                            vigilancePermissionToRequest(editorContext, grants)
                                ?.let(onRequestPermission)
                        }
                    },
                )
            }
            item { Hint(stringResource(R.string.editor_vigilance_needs_watch), hints) }
            if (vigilance == StyleSchema.VIGILANCE_ON &&
                vigilancePermissionToRequest(editorContext, grants) != null
            ) {
                item { Hint(stringResource(R.string.editor_vigilance_notifications), hints) }
            }

            // The log has nowhere else to be read. It is written to device-protected preferences
            // that a release build gives no way into - run-as needs a debuggable package — so
            // without this row the marks on the duty arc are the only sight of it, and they only
            // ever show the watch under way.
            item { Label(stringResource(R.string.editor_label_incidents)) }
            // Which watch these instants belong to. Without it the list is bare moments, and a
            // bare moment cannot say whether it was ten minutes into a night watch or the last
            // hour of a sixteen — which is most of what the list is read for.
            formatLogShift(
                vigilanceMonitor.state.logShiftStartMillis,
                vigilanceMonitor.state.logShiftEndMillis,
            )?.let { watch -> item { Caption(watch) } }
            if (incidents.isEmpty()) {
                item { Caption(stringResource(R.string.editor_incidents_none)) }
            } else {
                // Newest first: the question asked of a log on a wrist is almost always "when was
                // the last one", and that answer should not need scrolling to.
                items(incidents.size) { index ->
                    val record = incidents[incidents.size - 1 - index]
                    // One list item, two lines — stacked in a Column rather than emitted as two
                    // composables, which a lazy list draws on top of each other inside the one
                    // slot it gave them. The pulse is a second line rather than a longer first
                    // one: the instant is what the eye scans for, and a reading it had to read
                    // past would cost the scan. Absent readings print nothing at all — never a
                    // dash that could be mistaken for a measured zero.
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Caption(formatIncident(record))
                        formatIncidentPulse(record)?.let { Caption(it) }
                    }
                }
                // The log's way off a release build: no companion app, no network, no debug
                // bridge — but every watch has a speaker and every phone has a camera and a
                // microphone. One screen shows the QR and plays the Bell 202 burst together; see
                // export/Afsk.kt for why the sound is a 1979 standard and nothing bespoke.
                item {
                    OptionChip(
                        label = stringResource(R.string.editor_tx_log),
                        selected = false,
                        onClick = {
                            editorContext.startActivity(
                                Intent(editorContext, LogExportActivity::class.java)
                            )
                        },
                    )
                }
                item { Hint(stringResource(R.string.editor_tx_caption), hints) }
                item {
                    OptionChip(
                        label = stringResource(R.string.editor_incidents_clear),
                        selected = false,
                        onClick = {
                            vigilanceMonitor.clearLog()
                            incidents = VigilanceStore.EMPTY
                        },
                    )
                }
            }
            if (vigilance == StyleSchema.VIGILANCE_ON) {
                item {
                    SegmentedSetting(
                        label = stringResource(R.string.editor_label_vigilance_interval),
                        options = VIGILANCE_INTERVAL_OPTIONS,
                        selectedId = vigilanceInterval,
                        onSelect = { id ->
                            session.select(StyleSchema.VIGILANCE_INTERVAL, id)
                            vigilanceInterval = id
                        },
                    )
                }
                // Doze will not run an exact alarm oftener than every nine minutes per app, so
                // the shortest setting quietly stretches. Said here, not discovered on a wrist.
                if (vigilanceInterval == "5") {
                    item { Hint(stringResource(R.string.editor_vigilance_doze), hints) }
                }
                item {
                    SegmentedSetting(
                        label = stringResource(R.string.editor_label_vibe),
                        options = VIBE_OPTIONS,
                        selectedId = vibeStrength,
                        onSelect = { id ->
                            session.select(StyleSchema.VIBE_STRENGTH, id)
                            vibeStrength = id
                        },
                    )
                }
                // Sound is its own setting because it addresses somebody else: the buzz is for the
                // wearer, the tone is for whoever has to find them.
                item {
                    SegmentedSetting(
                        label = stringResource(R.string.editor_label_sos_sound),
                        options = SOS_SOUND_OPTIONS,
                        selectedId = sosSound,
                        onSelect = { id ->
                            session.select(StyleSchema.SOS_SOUND, id)
                            sosSound = id
                        },
                    )
                }
                item { Hint(stringResource(R.string.editor_sos_sound_note), hints) }
                // Offered only where it can work. BODY_SENSORS is a runtime grant a watch face
                // cannot request for itself, so with it missing this row would be a switch that
                // switches nothing — the permission caption above already asks.
                if (bodySensorsGranted(editorContext, grants)) {
                    item {
                        SegmentedSetting(
                            label = stringResource(R.string.editor_label_log_hr),
                            options = LOG_HR_OPTIONS,
                            selectedId = logHeartRate,
                            onSelect = { id ->
                                session.select(StyleSchema.LOG_HEART_RATE, id)
                                logHeartRate = id
                            },
                        )
                    }
                    item { Hint(stringResource(R.string.editor_log_hr_note), hints) }
                }
            }
        }


        // ---- Sensors ---------------------------------------------------------------------------
        // Between the monitor and the position, not at the bottom of the list: everything from
        // here up is about what hardware watches or reads the wearer, which is one family of
        // decision — and the headers now taper down the screen, so a swipe reads as one shape
        // instead of a long word snagging the eye below the fold.
        item(key = headerKey(SECTION_SENSORS)) {
            SectionHeader(
                text = stringResource(R.string.editor_section_sensors),
                expanded = openSection == SECTION_SENSORS,
                onClick = { toggleSection(SECTION_SENSORS) },
            )
        }
        if (openSection == SECTION_SENSORS) {
            item {
                SensorSlotSetting(
                    label = stringResource(R.string.editor_label_sensor_left),
                    selectedId = sensorLeft,
                    otherId = sensorRight,
                    grants = grants,
                    onRequestPermission = onRequestPermission,
                    onSelect = { id ->
                        session.select(StyleSchema.SENSOR_LEFT, id)
                        sensorLeft = id
                    },
                )
            }
            item {
                SensorSlotSetting(
                    label = stringResource(R.string.editor_label_sensor_right),
                    selectedId = sensorRight,
                    otherId = sensorLeft,
                    grants = grants,
                    onRequestPermission = onRequestPermission,
                    onSelect = { id ->
                        session.select(StyleSchema.SENSOR_RIGHT, id)
                        sensorRight = id
                    },
                )
            }
            if (missingSensorPermission(editorContext, sensorLeft, sensorRight, grants)) {
                item { Hint(stringResource(R.string.editor_sensor_needs_permission), hints) }
            }
        }

        // ---- Position --------------------------------------------------------------------------
        // Ahead of the display settings because NADIR and WEATHER both need a position: this was the
        // last section on the screen, so the line saying one is required pointed at rows the user
        // had not scrolled to yet.
        item(key = headerKey(SECTION_POSITION)) {
            SectionHeader(
                text = stringResource(R.string.editor_section_position),
                expanded = openSection == SECTION_POSITION,
                onClick = { toggleSection(SECTION_POSITION) },
            )
        }
        if (openSection == SECTION_POSITION) {

            // Where the position comes from is the user's choice, not a reading of the OS permission.
            // It used to be the latter, which left no way to switch the device off: on API 30 an app
            // cannot hand its own location permission back, so with the permission granted the chip
            // had nothing to toggle and the manual entry below it could never appear.
            item {
                SegmentedRow(
                    options = POSITION_OPTIONS,
                    selectedId = if (manualActive) POSITION_MANUAL else POSITION_AUTO,
                    onSelect = { id ->
                        if (id == POSITION_AUTO && !locationGranted) {
                            autoRequested = true
                            onRequestLocation()
                        } else {
                            val manual = id == POSITION_MANUAL
                            repository.manualPositionSelected = manual
                            manualActive = manual
                            hasPosition = repository.state.hasPosition
                            positionSource = repository.state.positionSource
                            if (!manual) {
                                // Go and get one now; the point of switching back is to see it happen.
                                scope.launch {
                                    runCatching { repository.onLocationGranted() }
                                    hasPosition = repository.state.hasPosition
                                    positionSource = repository.state.positionSource
                                }
                            }
                        }
                    },
                )
            }

            // What is actually in force, which is not always what was asked for: choose manual with no
            // coordinates on file yet and the answer is still "nothing".
            item {
                val source = positionSource
                val subRes = when (source) {
                    TelemetryState.POSITION_DEVICE -> R.string.editor_position_device_sub
                    TelemetryState.POSITION_MANUAL -> R.string.editor_position_manual_sub
                    else -> R.string.editor_position_none_sub
                }
                val labelRes = when (source) {
                    TelemetryState.POSITION_DEVICE -> R.string.editor_location_device
                    TelemetryState.POSITION_MANUAL -> R.string.editor_location_manual
                    else -> R.string.editor_location_none
                }
                Chip(
                    onClick = { if (!locationGranted && !manualActive) onRequestLocation() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (source == TelemetryState.POSITION_NONE) {
                        ChipDefaults.secondaryChipColors()
                    } else {
                        ChipDefaults.primaryChipColors()
                    },
                    label = { Text(text = stringResource(labelRes), maxLines = 1) },
                    secondaryLabel = { Text(text = stringResource(subRes), maxLines = 1) },
                )
            }
            item { Hint(stringResource(R.string.editor_location_rationale), hints) }

            // "While in use" covers only this screen: the half-hourly worker and the face itself
            // are background callers, and without "all the time" the platform hands them null.
            // The system grants it on a settings screen, so the answer comes back through
            // onResume rather than the launcher.
            if (locationGranted && !manualActive &&
                !heldPermission(
                    editorContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION, grants
                )
            ) {
                item { Hint(stringResource(R.string.editor_background_rationale), hints) }
                item {
                    OptionChip(
                        label = stringResource(R.string.editor_background_location),
                        selected = false,
                        onClick = {
                            onRequestPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        },
                    )
                }
            }

            // Coordinate entry follows the choice, not the permission: picking manual is exactly when
            // you need to be able to type one in, whether or not the platform would also give you one.
            if (manualActive) {
                item {
                    StepperRow(
                        value = stringResource(R.string.editor_manual_lat, formatDegrees(manualLat)),
                        onDown = { n -> manualLat = (manualLat - n).coerceAtLeast(-9000) },
                        onUp = { n -> manualLat = (manualLat + n).coerceAtMost(9000) },
                        // 18000 hundredths from pole to pole; this is the row the hold exists for.
                        maxStep = COORD_MAX_STEP,
                    )
                }
                item {
                    StepperRow(
                        value = stringResource(R.string.editor_manual_lon, formatDegrees(manualLon)),
                        onDown = { n -> manualLon = (manualLon - n).coerceAtLeast(-18000) },
                        onUp = { n -> manualLon = (manualLon + n).coerceAtMost(18000) },
                        maxStep = COORD_MAX_STEP,
                    )
                }
                item {
                    OptionChip(
                        label = stringResource(R.string.editor_manual_apply),
                        selected = positionSource == TelemetryState.POSITION_MANUAL,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    repository.setManualPosition(
                                        manualLat / 100.0,
                                        manualLon / 100.0,
                                    )
                                }
                                manualActive = true
                                hasPosition = repository.state.hasPosition
                                positionSource = repository.state.positionSource
                            }
                        },
                    )
                }
                if (repository.hasManualPosition()) {
                    item {
                        OptionChip(
                            label = stringResource(R.string.editor_manual_clear),
                            selected = false,
                            onClick = {
                                repository.clearManualPosition()
                                manualActive = false
                                hasPosition = repository.state.hasPosition
                                positionSource = repository.state.positionSource
                            },
                        )
                    }
                }
            }
        }


        // ---- Display ---------------------------------------------------------------------------
        // Seven settings that only change how the face looks, under one heading. They were seven
        // sections of a header and two full-width chips each: fifteen rows to say seven things, and
        // nothing in the list to show which of them belonged together.
        item(key = headerKey(SECTION_DISPLAY)) {
            SectionHeader(
                text = stringResource(R.string.editor_section_display),
                expanded = openSection == SECTION_DISPLAY,
                onClick = { toggleSection(SECTION_DISPLAY) },
            )
        }
        if (openSection == SECTION_DISPLAY) {
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_palette),
                    options = PALETTE_OPTIONS,
                    selectedId = palette,
                    onSelect = { id ->
                        session.select(StyleSchema.PALETTE, id)
                        palette = id
                    },
                )
            }
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_dial_top),
                    options = DIAL_TOP_OPTIONS,
                    selectedId = dialTop,
                    onSelect = { id ->
                        session.select(StyleSchema.DIAL_TOP, id)
                        dialTop = id
                    },
                )
            }
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_midnight),
                    options = MIDNIGHT_OPTIONS,
                    selectedId = midnightLabel,
                    onSelect = { id ->
                        session.select(StyleSchema.MIDNIGHT_LABEL, id)
                        midnightLabel = id
                    },
                )
            }
            item {
                // Nothing to shade without a position, so the choice is not offered as if it were live.
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_nadir),
                    options = NADIR_OPTIONS,
                    selectedId = nadir,
                    onSelect = { id ->
                        session.select(StyleSchema.NADIR, id)
                        nadir = id
                    },
                    isEnabled = { hasPosition },
                )
            }
            if (!hasPosition) {
                item { Hint(stringResource(R.string.editor_nadir_needs_position), hints) }
            }
            // Below Nadir because it draws on Nadir: the sun's true position on the band, which
            // is a solar compass for whoever knows how to hold one. Gated the same way — with no
            // band there is nothing to read the mark against.
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_solar),
                    options = SOLAR_OPTIONS,
                    selectedId = solarMark,
                    onSelect = { id ->
                        session.select(StyleSchema.SOLAR_MARK, id)
                        solarMark = id
                    },
                    isEnabled = { id ->
                        id == StyleSchema.SOLAR_OFF ||
                            (nadir == StyleSchema.NADIR_ON && hasPosition)
                    },
                )
            }
            if (solarMark == StyleSchema.SOLAR_ON) {
                item { Hint(stringResource(R.string.editor_solar_note), hints) }
            }
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_lunar),
                    options = LUNAR_OPTIONS,
                    selectedId = lunarMark,
                    onSelect = { id ->
                        session.select(StyleSchema.LUNAR_MARK, id)
                        lunarMark = id
                    },
                    isEnabled = { id ->
                        id == StyleSchema.LUNAR_OFF ||
                            (nadir == StyleSchema.NADIR_ON && hasPosition)
                    },
                )
            }
            if (lunarMark == StyleSchema.LUNAR_ON) {
                item { Hint(stringResource(R.string.editor_lunar_note), hints) }
            }
            if (VITAL_FACE) {
                item {
                    SegmentedSetting(
                        label = stringResource(R.string.editor_label_alarm),
                        options = ALARM_OPTIONS,
                        selectedId = alarmMark,
                        onSelect = { id ->
                            session.select(StyleSchema.ALARM_MARK, id)
                            alarmMark = id
                        },
                    )
                }
                if (alarmMark == StyleSchema.ALARM_ON) {
                    item { Hint(stringResource(R.string.editor_alarm_note), hints) }
                }
                item {
                    SegmentedSetting(
                        label = stringResource(R.string.editor_label_calendar),
                        options = CALENDAR_OPTIONS,
                        selectedId = calendarMarks,
                        onSelect = { id ->
                            session.select(StyleSchema.CALENDAR_MARKS, id)
                            calendarMarks = id
                            // The grant is collected here, with the switch that needs it: a
                            // watch face cannot raise a dialog of its own.
                            if (id == StyleSchema.CALENDAR_ON &&
                                !heldPermission(
                                    editorContext, Manifest.permission.READ_CALENDAR, grants,
                                )
                            ) {
                                onRequestPermission(Manifest.permission.READ_CALENDAR)
                            }
                        },
                    )
                }
                if (calendarMarks == StyleSchema.CALENDAR_ON) {
                    item { Hint(stringResource(R.string.editor_calendar_note), hints) }
                }
            }
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_weather),
                    options = WEATHER_OPTIONS,
                    selectedId = weather,
                    onSelect = { id ->
                        session.select(StyleSchema.WEATHER, id)
                        weather = id
                    },
                    isEnabled = { id -> hasPosition || id == StyleSchema.WEATHER_OFF },
                )
            }
            // Always-on wears the lume palette, dimmed -- there is no separate colour to choose, and
            // so nothing changes hue when the watch wakes. All that is left is whether it is thinned.
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_ambient),
                    options = AMBIENT_DENSITY_OPTIONS,
                    selectedId = ambientDensity,
                    onSelect = { id ->
                        session.select(StyleSchema.AMBIENT_DENSITY, id)
                        ambientDensity = id
                    },
                )
            }
            // AUTO is a rule, not a state, so it gets the one line the other options do not need.
            if (ambientDensity == StyleSchema.AMBIENT_AUTO) {
                item { Hint(stringResource(R.string.editor_ambient_auto_note), hints) }
            }
            // Last in the section that decides how things look, because this decides how *this
            // list* looks. Not a style setting for all that: it changes nothing the renderer
            // draws, and a schema change must not cost somebody their palette to reset it.
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_hints),
                    options = HINT_OPTIONS,
                    selectedId = if (hints) HINTS_ON else HINTS_OFF,
                    onSelect = { id ->
                        hints = id == HINTS_ON
                        EditorPrefs.setHintsShown(editorContext, hints)
                    },
                )
            }
            item { Hint(stringResource(R.string.editor_hints_note), hints) }
        }


        // ---- Units -----------------------------------------------------------------------------
        // Both rows labelled: unlabelled, the four chips ran together and the second pair read as
        // more of the first.
        // Units serve the weather row alone, and the wellness face prints no weather — its
        // forecast lives in the daylight band's shading, which has no unit to choose.
        if (!VITAL_FACE) item(key = headerKey(SECTION_UNITS)) {
            SectionHeader(
                text = stringResource(R.string.editor_section_units),
                expanded = openSection == SECTION_UNITS,
                onClick = { toggleSection(SECTION_UNITS) },
            )
        }
        if (openSection == SECTION_UNITS && !VITAL_FACE) {
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_temperature),
                    options = TEMP_OPTIONS,
                    selectedId = tempUnit,
                    onSelect = { id ->
                        session.select(StyleSchema.TEMP_UNIT, id)
                        tempUnit = id
                    },
                )
            }
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_pressure),
                    options = PRESSURE_OPTIONS,
                    selectedId = pressureUnit,
                    onSelect = { id ->
                        session.select(StyleSchema.PRESSURE_UNIT, id)
                        pressureUnit = id
                    },
                )
            }
        }


        // ---- About -----------------------------------------------------------------------------
        // Last on purpose: identity and maintenance, not operation. The update path itself lives
        // in UpdateActivity — the notes deserve a whole screen, not three rows of an accordion.
        item(key = headerKey(SECTION_ABOUT)) {
            SectionHeader(
                text = stringResource(R.string.editor_section_about),
                expanded = openSection == SECTION_ABOUT,
                onClick = { toggleSection(SECTION_ABOUT) },
            )
        }
        if (openSection == SECTION_ABOUT) {
            item {
                Caption(
                    stringResource(
                        R.string.editor_about_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    )
                )
            }
            // The one switch that decides whether this app talks to the network unasked. Not a
            // style setting: it changes nothing the renderer draws, and it must survive a schema
            // change.
            item {
                SegmentedSetting(
                    label = stringResource(R.string.editor_label_update_check),
                    options = UPDATE_CHECK_OPTIONS,
                    selectedId = if (updateCheck) UPDATE_CHECK_ON else UPDATE_CHECK_OFF,
                    onSelect = { id ->
                        updateCheck = id == UPDATE_CHECK_ON
                        UpdateStore.setCheckEnabled(editorContext, updateCheck)
                    },
                )
            }
            item { Hint(stringResource(R.string.editor_update_check_note), hints) }
            item {
                OptionChip(
                    label = stringResource(R.string.editor_about_github),
                    selected = false,
                    onClick = {
                        editorContext.startActivity(
                            Intent(editorContext, RepoLinkActivity::class.java)
                        )
                    },
                )
            }
            // One chip, and it says whether there is anything to go and get. Tapping it shows the
            // release page as a QR — the notes, the checksum and the APK are all there, in a
            // browser, at a size a person can read. Nothing is laid out on the watch and nothing is
            // installed by it, because Wear OS will not let an app install an app.
            item {
                OptionChip(
                    label = if (pendingUpdate == null) {
                        stringResource(R.string.editor_about_update)
                    } else {
                        stringResource(R.string.editor_about_update_available, pendingUpdate)
                    },
                    selected = pendingUpdate != null,
                    onClick = {
                        editorContext.startActivity(
                            Intent(editorContext, ReleaseLinkActivity::class.java)
                                .putExtra(ReleaseLinkActivity.EXTRA_VERSION, pendingUpdate.orEmpty())
                        )
                    },
                )
            }
            item { Hint(stringResource(R.string.editor_about_update_note), hints) }
        }


        // The list's full stop. It stays, though a swipe also leaves: on a round screen a swipe
        // that starts on a row is a scroll and a swipe that starts on the bezel is a dismissal,
        // and the two are half a centimetre apart — a deliberate way out is worth one row. Centred
        // and in the lume amber, so it reads as the end of the list rather than as one more
        // setting that happens to be last.
        item {
            Chip(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                label = {
                    Text(
                        text = stringResource(R.string.editor_done),
                        style = MaterialTheme.typography.button,
                        color = MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
    }
}


/**
 * One of the two slots beside the hub.
 *
 * Selecting a reading that needs a permission asks for it there and then, rather than at some later
 * moment the user cannot connect to what they did. If it is refused the setting still takes — the
 * face shows dashes — because refusing a permission is not the same as not wanting the row, and a
 * setting that silently reverted would be the more confusing of the two.
 */
@Composable
private fun SensorSlotSetting(
    label: String,
    selectedId: String,
    otherId: String,
    // Unused except as the recomposition nudge every permission reader in this file leans on.
    @Suppress("UNUSED_PARAMETER") grants: Int,
    onRequestPermission: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Label(label)
        SegmentedRow(
            options = SENSOR_OPTIONS,
            selectedId = selectedId,
            // A reading taken by the other slot is greyed out here. Both slots showing the same
            // number is not harmful, but it is never what was meant, and the pair of them is the
            // whole point of having two.
            isEnabled = { id -> id == SensorSlots.Kind.OFF.id || id != otherId },
            onSelect = { id ->
                onSelect(id)
                val permission = SensorSlots.Kind.ofId(id).permission
                if (permission != null && !hasPermission(context, permission)) {
                    onRequestPermission(permission)
                }
            },
        )
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** [hasPermission] with the [grants] nudge, for readers inside the composition. */
private fun heldPermission(
    context: Context,
    permission: String,
    @Suppress("UNUSED_PARAMETER") grants: Int,
): Boolean = hasPermission(context, permission)

/**
 * The permission enabling vigilance still lacks on this platform, or null when it has them all.
 *
 * Ordered by how badly the miss hurts: on API 34 the `health` foreground service will not start
 * at all without ACTIVITY_RECOGNITION (or another sensor permission), which is a dead-man's
 * switch that never arms; on API 33 the service runs but its ongoing notification is invisible.
 * Neither exists on the API 30 watches this face was built on.
 */
private fun vigilancePermissionToRequest(
    context: Context,
    @Suppress("UNUSED_PARAMETER") grants: Int,
): String? = when {
    android.os.Build.VERSION.SDK_INT >= 34 &&
        !hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ->
        Manifest.permission.ACTIVITY_RECOGNITION

    android.os.Build.VERSION.SDK_INT >= 33 &&
        !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS) ->
        Manifest.permission.POST_NOTIFICATIONS

    else -> null
}

/**
 * Whether either slot is set to a reading whose permission is not held.
 *
 * [grants] is unused except to make this recompose after the dialog closes: what is actually asked
 * is the package manager, which a Compose snapshot has no way of observing.
 */
private fun missingSensorPermission(
    context: Context,
    left: String,
    right: String,
    @Suppress("UNUSED_PARAMETER") grants: Int,
): Boolean = listOf(left, right).any { id ->
    val permission = SensorSlots.Kind.ofId(id).permission
    permission != null && !hasPermission(context, permission)
}

@Composable
private fun rememberOption(
    session: EditorSession,
    settingId: UserStyleSetting.Id,
    fallback: String,
): MutableState<String> = remember(settingId) {
    mutableStateOf(StyleSchema.optionId(session.userStyle.value, settingId, fallback))
}


/**
 * `[ – ]  8 h  [ + ]` — a free numeric choice rather than a menu of preset watch lengths.
 *
 * Both buttons repeat while held. Latitude is stepped in hundredths of a degree over a range of
 * 18000 of them, so tapping from where you are to anywhere else would be thousands of presses;
 * holding gets there in seconds.
 *
 * @param onDown called with the number of units to subtract this tick
 * @param onUp called with the number of units to add this tick
 * @param maxStep how far one tick may jump once the repeat is at full speed. One for anything
 *   counted in days or hours, where a fast repeat is already plenty; higher only for the
 *   coordinate rows, whose range is far too wide to cross a unit at a time.
 * @param onCommit run once when the press ends, for callers whose write is too expensive to do on
 *   every tick — pushing a value into the [EditorSession] twenty times a second, for instance.
 */
@Composable
private fun StepperRow(
    value: String,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit,
    maxStep: Int = 1,
    onCommit: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StepButton(stringResource(R.string.editor_step_down), maxStep, onDown, onCommit)
        Text(
            text = value,
            style = MaterialTheme.typography.title3,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
        )
        StepButton(stringResource(R.string.editor_step_up), maxStep, onUp, onCommit)
    }
}

/**
 * One round stepper button, with press-and-hold auto-repeat.
 *
 * A plain tap still applies exactly one unit on *release*, which is what `clickable` did before:
 * these buttons sit inside a `ScalingLazyColumn`, and acting on the press instead would nudge the
 * value every time a scroll happened to start on top of one. A hold past [HOLD_DELAY_MS] starts
 * repeating and accelerating until the finger comes off.
 */
@Composable
private fun StepButton(
    label: String,
    maxStep: Int,
    onStep: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    // The gesture coroutine outlives individual recompositions, so it must not capture a stale
    // lambda: these keep it pointed at the current one.
    val step by rememberUpdatedState(onStep)
    val commit by rememberUpdatedState(onCommit)
    val haptics = LocalHapticFeedback.current

    var pressed by remember { mutableStateOf(false) }
    var repeating by remember { mutableStateOf(false) }

    // Held is worth showing: without it there is no way to tell auto-repeat has engaged except by
    // watching the number, which is the thing you are trying to read.
    val background = when {
        repeating -> MaterialTheme.colors.primary
        pressed -> MaterialTheme.colors.primaryVariant
        else -> MaterialTheme.colors.surface
    }

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(background)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .pointerInput(maxStep) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            // null = the delay expired and the finger is still down; true = a
                            // normal tap; false = a parent took the gesture, i.e. a scroll, and
                            // nothing should happen at all.
                            when (withTimeoutOrNull(HOLD_DELAY_MS) { tryAwaitRelease() }) {
                                true -> {
                                    step(1)
                                    commit()
                                }

                                false -> Unit

                                null -> {
                                    repeating = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    var tick = 0
                                    while (true) {
                                        step(repeatStep(tick, maxStep))
                                        tick++
                                        val done = withTimeoutOrNull(REPEAT_TICK_MS) {
                                            tryAwaitRelease()
                                        }
                                        if (done != null) break
                                    }
                                    commit()
                                }
                            }
                        } finally {
                            pressed = false
                            repeating = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.title2,
            color = if (repeating) MaterialTheme.colors.onPrimary else MaterialTheme.colors.primary,
        )
    }
}

/**
 * The seven sections of the list, and the sentinel for none open.
 *
 * Identity, not position: a header's item index moves when a section above it expands, which is
 * why the headers are keyed by [headerKey] and the pinning in `toggleSection` looks items up by
 * key rather than by index.
 */
private const val SECTION_NONE = -1
private const val SECTION_DUTY = 0
private const val SECTION_VIGILANCE = 1
private const val SECTION_SENSORS = 2
private const val SECTION_POSITION = 3
private const val SECTION_DISPLAY = 4
private const val SECTION_UNITS = 5
private const val SECTION_ABOUT = 6

/**
 * The wellness face's recorder, in the slot its duty control vacated: what the day log costs
 * and how often the pulse is taken are the first thing that face is asked about.
 */
private const val SECTION_RECORDER = 7

/** Stable identity for a section header in the lazy list, whatever its index this frame. */
private fun headerKey(section: Int): String = "header-" + section

/** Small enough to read as punctuation on the header rather than as a second label. */
private val CHEVRON_SIZE = 10.dp

/** The open section's outline: thin, and half a second of light. */
private val TRACE_STROKE = 2.dp

/**
 * How long the outline takes to come up or go down.
 *
 * Deliberately the wake sweep's own figure rather than a number picked for this list: the same
 * ramp, the same duration, the same meaning — something has just become live. Read from the
 * renderer's constant so the two cannot drift apart.
 */
private val TRACE_MILLIS = WakeTransition.DURATION_MILLIS.toInt()

/** How many frames the tapped header is held in place for while the list reflows. */
private const val PIN_FRAMES = 6

/** Wait before a hold becomes a repeat: long enough that a deliberate single tap never trips it. */
private const val HOLD_DELAY_MS = 350L

/** About twenty ticks a second once repeating. */
private const val REPEAT_TICK_MS = 50L

/**
 * How many units one repeat tick moves, ramping up the longer the button is held and then clamped
 * to the row's own ceiling.
 *
 * The ramp is what makes a wide range crossable without making a narrow one uncontrollable: the
 * first three quarters of a second still move one unit at a time, so a short hold is precise, and
 * only a sustained one reaches full speed — about 20 degrees a second on a coordinate row.
 */
private fun repeatStep(tick: Int, maxStep: Int): Int {
    val ramp = when {
        tick < 15 -> 1
        tick < 30 -> 2
        tick < 45 -> 5
        tick < 60 -> 10
        tick < 80 -> 25
        tick < 100 -> 50
        else -> 100
    }
    return if (ramp > maxStep) maxStep else ramp
}

/**
 * A row of equal-width chips acting as one choice, for options short enough to sit side by side.
 *
 * Four presets on one line where four full-width chips would be four screens of scrolling. The cells
 * share the width evenly so the row reads as a single control rather than as a cluster of buttons,
 * and the selected one is filled while the rest are outlined — an outline is legible on a black
 * dial without spending the accent colour four times over.
 *
 * [isEnabled] is per cell rather than per row because that is how the dependencies actually fall:
 * NADIR has nothing to shade without a position, but WEATHER can always be turned *off*, so
 * greying the whole row would trap a setting the user is allowed to change.
 */
@Composable
private fun SegmentedRow(
    options: List<Segment>,
    selectedId: String,
    onSelect: (String) -> Unit,
    isEnabled: (String) -> Boolean = { true },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (segment in options) {
            val chosen = segment.id == selectedId
            val enabled = isEnabled(segment.id)
            val accent = segment.tint ?: MaterialTheme.colors.primary
            val description = stringResource(segment.speechRes)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(CircleShape)
                    .then(
                        if (chosen) {
                            Modifier.background(if (enabled) accent else accent.copy(alpha = 0.35f))
                        } else {
                            Modifier.border(
                                1.dp,
                                when {
                                    !enabled -> MaterialTheme.colors.surface
                                    // Tinted cells keep their hue unselected as well, or the
                                    // palette row offers three identically amber outlines.
                                    segment.tint != null -> segment.tint
                                    else -> MaterialTheme.colors.primaryVariant
                                },
                                CircleShape,
                            )
                        }
                    )
                    .clickable(enabled = enabled) { onSelect(segment.id) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(segment.labelRes),
                    style = MaterialTheme.typography.button,
                    color = when {
                        chosen -> MaterialTheme.colors.onPrimary
                        enabled -> MaterialTheme.colors.onBackground
                        else -> MaterialTheme.colors.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A labelled [SegmentedRow], as one list item rather than two.
 *
 * One item because a `ScalingLazyColumn` scales and spaces its items independently: a label in an
 * item of its own drifts away from the control it names as the list scrolls past, and pays the
 * inter-item gap for the privilege. Folding the pair together is what let seven appearance settings
 * fit under a single DISPLAY header instead of carrying seven headers of their own.
 */
@Composable
private fun SegmentedSetting(
    label: String,
    options: List<Segment>,
    selectedId: String,
    onSelect: (String) -> Unit,
    isEnabled: (String) -> Boolean = { true },
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Label(label)
        SegmentedRow(options, selectedId, onSelect, isEnabled)
    }
}

/**
 * A sub-heading inside a section: the section's amber, one size down.
 *
 * Amber rather than grey because these name the control below them; in grey, DURATION read as a
 * remark about the row above it. The size is what keeps the hierarchy legible — a full-size amber
 * line under DISPLAY would look like another section beginning.
 */
@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 1.dp),
    )
}

/** A section heading: the largest amber line on the screen, and the only thing that starts one. */
@Composable
private fun SectionHeader(text: String, expanded: Boolean, onClick: () -> Unit) {
    // The open section is outlined, and the outline arrives the way the face itself does: as
    // brightness.
    //
    // One section is open at a time, so which one it is is the most useful thing this list can say
    // — and the chevron alone says it in eight pixels of glyph. An earlier version drew the line
    // on, clockwise, over a second. It read as an animation rather than as a state, which is
    // exactly the wrong emphasis for a row you are trying to look past: the eye followed the
    // moving end instead of the section it was marking.
    //
    // So it ramps in brightness over `WakeTransition.DURATION_MILLIS` — the same half-second and
    // the same idea as waking from always-on, where colour is already there and only the light
    // arrives. One gesture learned once, met twice. Brightness by scaling the hue's channels
    // rather than by alpha, for the reason the renderer's palette gives: alpha composites badly
    // and shifts the hue, and this hue is the lume.
    //
    // Snapped rather than ramped on the first composition, so opening the editor does not replay
    // half a second of light for a choice nobody just made.
    val trace = remember { Animatable(if (expanded) 1f else 0f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!settled) {
            settled = true
            trace.snapTo(if (expanded) 1f else 0f)
            return@LaunchedEffect
        }
        trace.animateTo(if (expanded) 1f else 0f, tween(TRACE_MILLIS, easing = LinearEasing))
    }
    val outline = MaterialTheme.colors.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colors.surface)
            .drawBehind {
                val progress = trace.value
                if (progress <= 0f) return@drawBehind
                val stroke = TRACE_STROKE.toPx()
                val inset = stroke / 2f
                val radius = (size.height - stroke) / 2f
                // The whole contour, every frame, at the brightness the ramp is currently at. A
                // stadium rather than an arc because that is the shape of the row.
                drawRoundRect(
                    color = Color(
                        red = outline.red * progress,
                        green = outline.green * progress,
                        blue = outline.blue * progress,
                    ),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = stroke),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(expanded = expanded, color = MaterialTheme.colors.primary)
        Text(
            text = text,
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * The open/closed marker on a [SectionHeader]: a small triangle, pointing right when the section is
 * shut and down when it is open.
 *
 * Drawn rather than set in type or taken from an icon pack. The glyphs that would do this are not
 * reliably in the system font on Wear, and `material-icons` is a dependency to carry for one
 * triangle — this project drops dependencies rather than collecting them.
 */
@Composable
private fun Chevron(expanded: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(CHEVRON_SIZE)) {
        val w = size.width
        val h = size.height
        val path = Path()
        if (expanded) {
            path.moveTo(w * 0.15f, h * 0.32f)
            path.lineTo(w * 0.85f, h * 0.32f)
            path.lineTo(w * 0.50f, h * 0.72f)
        } else {
            path.moveTo(w * 0.32f, h * 0.15f)
            path.lineTo(w * 0.72f, h * 0.50f)
            path.lineTo(w * 0.32f, h * 0.85f)
        }
        path.close()
        drawPath(path, color)
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption3,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

/**
 * A full-width chip standing for one choice or one action.
 *
 * What is left of this after the settings moved to segmented rows: the two commands that need a
 * whole line of their own, and DONE. The colour swatch it used to carry for the palette rows went
 * with them — each segment wears its own hue now, which says the same thing in less space.
 */
@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) {
            ChipDefaults.primaryChipColors()
        } else {
            ChipDefaults.secondaryChipColors()
        },
        label = { Text(text = label, maxLines = 1) },
    )
}

/**
 * Applies a list option to the live style. The editor session persists it and the running watch
 * face redraws immediately.
 */
private fun EditorSession.select(settingId: UserStyleSetting.Id, optionId: String) {
    val setting = userStyleSchema[settingId] ?: return
    val option = setting.options.firstOrNull { it.id.toString() == optionId } ?: return
    val mutable = userStyle.value.toMutableUserStyle()
    mutable[setting] = option
    userStyle.value = mutable.toUserStyle()
}


/**
 * One cell of a [SegmentedRow].
 *
 * [speechRes] exists because the visible labels are abbreviated to fit three or four to a line:
 * `CST` and `hPa` are legible on a dial and useless read aloud, and the long forms are still what
 * the platform's own style list shows. [tint] lets a cell carry its own hue, which is how the
 * palette row shows what it is offering now that there is no width left for a swatch.
 */
private class Segment(
    val id: String,
    val labelRes: Int,
    val speechRes: Int = labelRes,
    val tint: Color? = null,
)

private val DURATION_PRESETS = listOf(
    Segment(
        WatchShiftController.PRESET_4H, R.string.editor_duration_4h,
        R.string.editor_duration_4h_sr,
    ),
    Segment(
        WatchShiftController.PRESET_8H, R.string.editor_duration_8h,
        R.string.editor_duration_8h_sr,
    ),
    Segment(
        WatchShiftController.PRESET_12H, R.string.editor_duration_12h,
        R.string.editor_duration_12h_sr,
    ),
    Segment(
        WatchShiftController.PRESET_CUSTOM, R.string.editor_duration_custom,
        R.string.editor_duration_custom_sr,
    ),
)

/** Where the position comes from. Local to the editor: the repository holds a boolean. */
private const val POSITION_AUTO = "auto"
private const val POSITION_MANUAL = "manual"
private val POSITION_OPTIONS = listOf(
    Segment(POSITION_AUTO, R.string.editor_position_auto),
    Segment(POSITION_MANUAL, R.string.editor_position_manual),
)

/**
 * Ceiling on one repeat tick of a five-minute stepper: three steps, so a quarter of an hour a tick at
 * full speed. Enough to cross sixteen hours in about three seconds and still stop where you meant.
 */
private const val DURATION_MAX_STEP = 3

/**
 * A whole degree per tick at full speed, i.e. about twenty degrees a second — enough to cross the
 * globe in a few seconds now that the unit is a hundredth rather than a tenth.
 */
private const val COORD_MAX_STEP = 100

/** `3:42` — hours and minutes left, rounded up so it never reads 0:00 while time remains. */
private fun formatRemaining(remainingMillis: Long): String {
    val totalMinutes = ((remainingMillis + 59_999L) / 60_000L).toInt()
    return String.format(Locale.US, "%02d:%02d", totalMinutes / 60, totalMinutes % 60)
}

/** Secondary line on the timer chip: what the shift is doing right now, if anything. */
@Composable
private fun dutySecondaryText(
    dutyState: Int,
    watchShift: WatchShiftController,
    nowMillis: Long,
): String? = when (dutyState) {
    WatchShiftState.DUTY_ACTIVE -> stringResource(
        R.string.editor_watch_running,
        formatRemaining(watchShift.state.remainingMillis(nowMillis)),
    )

    WatchShiftState.DUTY_PENDING -> stringResource(
        R.string.editor_watch_pending,
        formatRemaining(watchShift.state.untilStartMillis(nowMillis)),
    )

    WatchShiftState.DUTY_SERVED -> stringResource(R.string.editor_watch_served)

    else -> null
}

/**
 * Degrees to hundredths, the unit the manual steppers work in, falling back to [orElse] and then
 * to zero. NaN means "never set" for both.
 */
private fun toHundredths(degrees: Double, orElse: Double): Int {
    val value = if (degrees.isNaN()) orElse else degrees
    return if (value.isNaN()) 0 else Math.round(value * 100.0).toInt()
}

/** `55.75` / `-33.92` — two decimals, which is what a hundredth-of-a-degree stepper expresses. */
private fun formatDegrees(hundredths: Int): String =
    String.format(Locale.US, "%.2f", hundredths / 100.0)

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
private const val HOUR_MILLIS = 60 * 60 * 1000L

/** An hour from now, rounded up to the next multiple of the minute step. */
private fun defaultBookedStart(nowMillis: Long): Long =
    WatchShiftController.earliestBookableStart(nowMillis + HOUR_MILLIS)

/** The schedule steppers' floor, read off a live clock — see the comment at their rows. */
private fun earliestBookableNow(): Long =
    WatchShiftController.earliestBookableStart(System.currentTimeMillis())

private fun localDateTime(millis: Long): LocalDateTime =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()

private fun hourOf(millis: Long): Int = localDateTime(millis).hour

private fun minuteOf(millis: Long): Int = localDateTime(millis).minute

private fun formatBookedDate(millis: Long): String =
    localDateTime(millis).format(BOOKED_DATE_FORMAT).uppercase(Locale.US)

/**
 * `19 AUG 21:14Z` — an incident, in the same Zulu the dial prints it in.
 *
 * Local time would be the wrong answer twice over: the record is kept in absolute millis precisely
 * so it survives a change of zone, and the face states it in Zulu, so a log that disagreed with the
 * dial would be a second version of events.
 */
private fun formatIncident(record: IncidentRecord): String =
    INCIDENT_FORMAT.format(Instant.ofEpochMilli(record.atMillis).atZone(ZoneOffset.UTC))

/**
 * `HR 41 · REF 58 AT 15:12Z` — the incident pulse and the reference it has to be read against.
 *
 * Null when there is nothing measured, because a row that prints dashes for an absent reading
 * invites them to be read as a low one. The reference carries its own instant: a baseline from
 * four hours ago is a different claim from one taken four minutes before the operator stopped
 * answering, and the editor is where somebody decides which they are looking at.
 */
private fun formatIncidentPulse(record: IncidentRecord): String? {
    if (!record.hasBpm && !record.hasBaseline) return null
    val out = StringBuilder()
    if (record.hasBpm) out.append("HR ").append(record.bpm)
    if (record.hasBaseline) {
        if (out.isNotEmpty()) out.append(" · ")
        out.append("REF ").append(record.baselineBpm).append(' ')
        out.append(
            PULSE_TIME_FORMAT.format(
                Instant.ofEpochMilli(record.baselineAtMillis).atZone(ZoneOffset.UTC)
            )
        )
    }
    return out.toString()
}

/**
 * `WATCH 21 Aug 20:00–04:00Z · 8h` — the shift the log on file belongs to.
 *
 * Null when nothing is on file. The end carries the Zulu marker and the start does not, because
 * they are the same clock and marking it twice is noise on a row this narrow.
 */
private fun formatLogShift(startMillis: Long, endMillis: Long): String? {
    if (startMillis <= 0L || endMillis <= startMillis) return null
    val start = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC)
    val end = Instant.ofEpochMilli(endMillis).atZone(ZoneOffset.UTC)
    val minutes = (endMillis - startMillis) / 60_000L
    val length = if (minutes % 60L == 0L) {
        (minutes / 60L).toString() + "h"
    } else {
        "%dh%02d".format(minutes / 60L, minutes % 60L)
    }
    return "WATCH " + INCIDENT_FORMAT.format(start).removeSuffix("Z") +
        "–" + PULSE_TIME_FORMAT.format(end) + " · " + length
}

/**
 * An explanatory line under a row — the same type as [Caption], but hideable.
 *
 * Two composables rather than a flag on one, because the distinction is the whole point: a hint
 * explains, a caption reports. `HINTS OFF` silences every hint in the list and touches no reading.
 */
@Composable
private fun Hint(text: String, shown: Boolean) {
    if (!shown) return
    Caption(text)
}

/** Whether the pulse sensor may be read at all — the heart-rate log row is offered only then. */
private fun bodySensorsGranted(context: Context, grants: Int): Boolean {
    @Suppress("UNUSED_EXPRESSION") grants // recomposition key: a grant arrives without a state change
    return ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) ==
        PackageManager.PERMISSION_GRANTED
}

private val INCIDENT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM HH:mm'Z'", Locale.UK)

/** Just the clock: the reference pulse's date is the incident's, a line above. */
private val PULSE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm'Z'", Locale.UK)

private val BOOKED_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

// "MIDNIGHT UP" is a character too wide for half a row and lost its "UP"; under a label that
// already says DIAL TOP, the direction was the redundant half anyway.
private val DIAL_TOP_OPTIONS = listOf(
    Segment(StyleSchema.DIAL_TOP_NOON, R.string.editor_seg_dial_noon, R.string.dial_top_noon_sr),
    Segment(
        StyleSchema.DIAL_TOP_MIDNIGHT, R.string.editor_seg_dial_midnight,
        R.string.dial_top_midnight_sr,
    ),
)
private val NADIR_OPTIONS = listOf(
    Segment(StyleSchema.NADIR_OFF, R.string.nadir_off, R.string.nadir_off_sr),
    Segment(StyleSchema.NADIR_ON, R.string.nadir_on, R.string.nadir_on_sr),
)
private val SOLAR_OPTIONS = listOf(
    Segment(StyleSchema.SOLAR_OFF, R.string.solar_off, R.string.solar_off_sr),
    Segment(StyleSchema.SOLAR_ON, R.string.solar_on, R.string.solar_on_sr),
)
private val LUNAR_OPTIONS = listOf(
    Segment(StyleSchema.LUNAR_OFF, R.string.lunar_off, R.string.lunar_off_sr),
    Segment(StyleSchema.LUNAR_ON, R.string.lunar_on, R.string.lunar_on_sr),
)
private val ALARM_OPTIONS = listOf(
    Segment(StyleSchema.ALARM_OFF, R.string.alarm_off, R.string.alarm_off_sr),
    Segment(StyleSchema.ALARM_ON, R.string.alarm_on, R.string.alarm_on_sr),
)
private val CALENDAR_OPTIONS = listOf(
    Segment(StyleSchema.CALENDAR_OFF, R.string.calendar_off, R.string.calendar_off_sr),
    Segment(StyleSchema.CALENDAR_ON, R.string.calendar_on, R.string.calendar_on_sr),
)
private val RECORD_OPTIONS = listOf(
    Segment(StyleSchema.RECORD_OFF, R.string.record_off, R.string.record_off_sr),
    Segment(StyleSchema.RECORD_ON, R.string.record_on, R.string.record_on_sr),
)
private val RECORD_INTERVAL_OPTIONS = listOf(
    Segment("5", R.string.record_5, R.string.record_5_sr),
    Segment("10", R.string.record_10, R.string.record_10_sr),
    Segment("15", R.string.record_15, R.string.record_15_sr),
)
private val OFFBODY_OPTIONS = listOf(
    Segment(StyleSchema.SLEEP_OFFBODY_OFF, R.string.offbody_off, R.string.offbody_off_sr),
    Segment(StyleSchema.SLEEP_OFFBODY_ON, R.string.offbody_on, R.string.offbody_on_sr),
)
private val VIGILANCE_OPTIONS = listOf(
    Segment(StyleSchema.VIGILANCE_OFF, R.string.vigilance_off, R.string.vigilance_off_sr),
    Segment(StyleSchema.VIGILANCE_ON, R.string.vigilance_on, R.string.vigilance_on_sr),
)
private val VIGILANCE_INTERVAL_OPTIONS = listOf(
    Segment("5", R.string.vigilance_5, R.string.vigilance_5_sr),
    Segment("10", R.string.vigilance_10, R.string.vigilance_10_sr),
    Segment("15", R.string.vigilance_15, R.string.vigilance_15_sr),
)
private val SENSOR_OPTIONS = listOf(
    Segment(SensorSlots.Kind.OFF.id, R.string.sensor_off, R.string.sensor_off_sr),
    Segment(SensorSlots.Kind.HEART_RATE.id, R.string.sensor_hr, R.string.sensor_hr_sr),
    Segment(SensorSlots.Kind.STEPS.id, R.string.sensor_steps, R.string.sensor_steps_sr),
    Segment(SensorSlots.Kind.PRESSURE.id, R.string.sensor_qfe, R.string.sensor_qfe_sr),
)

/** ABOUT's release-check switch. Ids are local: this one is not in the style schema. */
private const val UPDATE_CHECK_ON = "on"
private const val UPDATE_CHECK_OFF = "off"

private const val HINTS_ON = "on"
private const val HINTS_OFF = "off"

private val HINT_OPTIONS = listOf(
    Segment(HINTS_ON, R.string.hints_on, R.string.hints_on_sr),
    Segment(HINTS_OFF, R.string.hints_off, R.string.hints_off_sr),
)

private val UPDATE_CHECK_OPTIONS = listOf(
    Segment(UPDATE_CHECK_ON, R.string.update_check_on, R.string.update_check_on_sr),
    Segment(UPDATE_CHECK_OFF, R.string.update_check_off, R.string.update_check_off_sr),
)

private val SOS_SOUND_OPTIONS = listOf(
    Segment(StyleSchema.SOS_SOUND_OFF, R.string.sos_off, R.string.sos_off_sr),
    Segment(StyleSchema.SOS_SOUND_LOW, R.string.sos_low, R.string.sos_low_sr),
    Segment(StyleSchema.SOS_SOUND_MED, R.string.sos_med, R.string.sos_med_sr),
    Segment(StyleSchema.SOS_SOUND_HIGH, R.string.sos_high, R.string.sos_high_sr),
)

private val LOG_HR_OPTIONS = listOf(
    Segment(StyleSchema.LOG_HR_OFF, R.string.log_hr_off, R.string.log_hr_off_sr),
    Segment(StyleSchema.LOG_HR_ON, R.string.log_hr_on, R.string.log_hr_on_sr),
)

private val VIBE_OPTIONS = listOf(
    Segment(StyleSchema.VIBE_LOW, R.string.vibe_low, R.string.vibe_low_sr),
    Segment(StyleSchema.VIBE_MED, R.string.vibe_med, R.string.vibe_med_sr),
    Segment(StyleSchema.VIBE_HIGH, R.string.vibe_high, R.string.vibe_high_sr),
)
private val WEATHER_OPTIONS = listOf(
    Segment(StyleSchema.WEATHER_ON, R.string.weather_on, R.string.weather_on_sr),
    Segment(StyleSchema.WEATHER_OFF, R.string.weather_off, R.string.weather_off_sr),
)
private val AMBIENT_DENSITY_OPTIONS = listOf(
    Segment(StyleSchema.AMBIENT_FULL, R.string.ambient_full, R.string.ambient_full_sr),
    Segment(StyleSchema.AMBIENT_HALF, R.string.ambient_half, R.string.ambient_half_sr),
    Segment(StyleSchema.AMBIENT_AUTO, R.string.ambient_auto, R.string.ambient_auto_sr),
)
private val MIDNIGHT_OPTIONS = listOf(
    Segment(StyleSchema.MIDNIGHT_AS_00, R.string.midnight_00, R.string.midnight_00_sr),
    Segment(StyleSchema.MIDNIGHT_AS_24, R.string.midnight_24, R.string.midnight_24_sr),
)
// Each cell wears its own hue, which says more than a name does and survives the loss of the
// swatch that a full-width chip had room for.
private val PALETTE_OPTIONS = listOf(
    Segment(
        Palette.ID_AMBER, R.string.editor_seg_palette_amber, R.string.palette_amber_sr,
        Color(Palette.ALERT_AMBER),
    ),
    Segment(
        Palette.ID_GREEN, R.string.editor_seg_palette_green, R.string.palette_green_sr,
        Color(Palette.PHOSPHOR_GREEN),
    ),
    Segment(
        Palette.ID_RED, R.string.editor_seg_palette_red, R.string.palette_red_sr,
        Color(Palette.NVG_RED),
    ),
)
private val TEMP_OPTIONS = listOf(
    Segment(
        StyleSchema.TEMP_CELSIUS, R.string.editor_seg_temp_celsius, R.string.temp_celsius_sr
    ),
    Segment(
        StyleSchema.TEMP_FAHRENHEIT, R.string.editor_seg_temp_fahrenheit,
        R.string.temp_fahrenheit_sr,
    ),
)
private val PRESSURE_OPTIONS = listOf(
    Segment(StyleSchema.PRESSURE_HPA, R.string.editor_seg_pressure_hpa, R.string.pressure_hpa_sr),
    Segment(
        StyleSchema.PRESSURE_MMHG, R.string.editor_seg_pressure_mmhg, R.string.pressure_mmhg_sr
    ),
)
