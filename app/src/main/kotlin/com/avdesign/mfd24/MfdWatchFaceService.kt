// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import android.view.SurfaceHolder
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import com.avdesign.mfd24.astro.PlanetMode
import com.avdesign.mfd24.data.MarsCommRepository
import com.avdesign.mfd24.data.MarsEphemerisWorker
import com.avdesign.mfd24.data.TelemetryRepository
import com.avdesign.mfd24.data.SensorSlots
import com.avdesign.mfd24.data.TelemetryWorker
import com.avdesign.mfd24.data.VigilanceMonitor
import com.avdesign.mfd24.data.VigilanceState
import com.avdesign.mfd24.data.WatchShiftController
import com.avdesign.mfd24.style.StyleSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Entry point for MFD-24.
 *
 * The service is `directBootAware`, so it must be able to render before the user has unlocked the
 * watch. Everything on that path — the time bases, the dial, the cached telemetry in
 * device-protected storage — works without credential-encrypted storage. The one thing that does
 * not is WorkManager, so scheduling the periodic refresh waits for `ACTION_USER_UNLOCKED`.
 */
class MfdWatchFaceService : WatchFaceService() {

    private lateinit var repository: TelemetryRepository
    private lateinit var watchShift: WatchShiftController
    private lateinit var vigilance: VigilanceMonitor
    private lateinit var sensorSlots: SensorSlots

    /** Present only on the mars flavor; everything else leaves it null. */
    private var marsComm: MarsCommRepository? = null

    /** Last slots asked for, so a visibility change can re-apply them without the renderer. */
    private var slotLeft = 0
    private var slotRight = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var unlockReceiver: BroadcastReceiver? = null
    private var lastOpportunisticRefresh = 0L

    /** True once [batteryReceiver] is registered, so it is unregistered exactly once. */
    private var batteryRegistered = false

    /**
     * Battery level, pushed in as the platform reports it.
     *
     * `ACTION_BATTERY_CHANGED` is a sticky broadcast, so registering also delivers the current value
     * immediately — there is nothing to poll and no first frame with the row missing. It cannot be
     * declared in the manifest, hence a receiver registered in code.
     *
     * Registered from `createWatchFace` and only for the real face, never from `onCreate`. Headless
     * preview instances are created and torn down constantly by the picker and the editor, through
     * `WatchFaceControlService`, and their teardown does not run this service's `onDestroy` — so a
     * receiver registered in `onCreate` leaked one dispatcher per preview, which the platform
     * reports as `IntentReceiverLeaked`. A preview has no use for a live battery level anyway.
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            repository.state.batteryPercent = (level * 100 / scale).coerceIn(0, 100)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = TelemetryRepository.get(this)
        watchShift = WatchShiftController.get(this)
        vigilance = VigilanceMonitor.get(this)
        sensorSlots = SensorSlots(this)
        if (BuildConfig.WORLD == PlanetMode.ID_MARS) {
            // The Mars face has no location, no site and no weather, so the telemetry worker's
            // whole pipeline would be a half-hourly no-op; its sky comes from arithmetic and,
            // for the relay line, its own ephemeris fetch.
            marsComm = MarsCommRepository.get(this)
            if (!MarsEphemerisWorker.scheduleIfUnlocked(this)) {
                registerUnlockReceiver()
            }
        } else if (!TelemetryWorker.scheduleIfUnlocked(this)) {
            registerUnlockReceiver()
        }
    }

    private fun followBatteryLevel() {
        if (batteryRegistered) return
        runCatching {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryRegistered = true
        }.onFailure { Log.w(TAG, "Could not follow the battery level", it) }
    }

    override fun onDestroy() {
        unlockReceiver?.let {
            runCatching { unregisterReceiver(it) }
            unlockReceiver = null
        }
        if (batteryRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            batteryRegistered = false
        }
        sensorSlots.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun createUserStyleSchema(): UserStyleSchema =
        StyleSchema.create(resources, PlanetMode.fromOptionId(BuildConfig.WORLD))

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val headless = watchState.isHeadless
        val renderer = MfdRenderer(
            surfaceHolder,
            currentUserStyleRepository,
            watchState,
            repository.state,
            watchShift.state,
            vigilance.state,
            // Headless preview instances render the editor's thumbnails under their own style,
            // but the repository and the monitor are process-wide. Left connected, a preview whose
            // style has weather off would silence the real face's weather, and one whose style has
            // vigilance off would stop a dead-man's switch that is genuinely running.
            onWeatherEnabled = if (headless) {
                { }
            } else {
                { enabled -> repository.weatherEnabled = enabled }
            },
            // A headless preview must not light the heart-rate LED to render a thumbnail.
            onSensorSlots = if (headless) {
                { _, _ -> }
            } else {
                { left, right ->
                    slotLeft = left
                    slotRight = right
                    sensorSlots.configure(left, right, watchState.isVisible.value == true)
                }
            },
            marsComm = marsComm?.state,
            // Same headless rule as the weather: a preview's style must not re-aim the
            // process-wide ephemeris side.
            onRoverSelected = if (headless) {
                { }
            } else {
                { index -> marsComm?.setRover(index) }
            },
            onRelayMask = if (headless) {
                { }
            } else {
                { mask -> marsComm?.setRelayMask(mask) }
            },
            descriptions = FaceDescriptions(
                onDuty = getString(R.string.a11y_on_duty),
                dutyBooked = getString(R.string.a11y_duty_booked),
                offDuty = getString(R.string.a11y_off_duty),
                manDown = getString(R.string.a11y_man_down),
                offWrist = getString(R.string.a11y_off_wrist),
            ),
            onVigilanceRequest = if (headless) {
                { _, _, _, _, _, _, _ -> }
            } else {
                { active, interval, amplitude, sosVolume, logHeartRate, shiftStart, shiftEnd ->
                    // Before either branch, and whether or not this watch is being monitored: a
                    // new watch retires the previous watch's incident and takes its log with it.
                    vigilance.noteShiftStart(shiftStart, shiftEnd)
                    if (active) {
                        vigilance.start(interval, amplitude, sosVolume, logHeartRate, shiftStart)
                    } else {
                        vigilance.stop()
                    }
                }
            },
        )

        // Headless instances exist only to render previews for the editor; they must not trigger
        // network or location work, and must not register receivers their teardown will not undo.
        if (!headless) {
            followBatteryLevel()
            serviceScope.launch {
                watchState.isVisible.collect { visible ->
                    // The sensors follow the screen. Heart rate means an LED against the wrist, and
                    // a number nobody is looking at is not worth lighting it for.
                    sensorSlots.configure(slotLeft, slotRight, visible == true)
                    if (visible == true) {
                        // Each face refreshes what it actually runs on: Earth its telemetry,
                        // Mars its sky — cheap arithmetic, and screen-on is when stale daylight
                        // or windows would otherwise be seen.
                        marsComm?.refreshLocal(System.currentTimeMillis())
                        if (BuildConfig.WORLD == PlanetMode.ID_EARTH) refreshOpportunistically()
                    }
                }
            }
        }

        // A tap anywhere on the face is the operator saying "still here" — the primary way out of
        // an SOS, and the one that works with the wrist held still.
        //
        // A recorded incident is not cleared that way. It is the one piece of state a sleeve or a
        // harness must not be able to destroy, so it takes two taps inside [DOUBLE_TAP_MILLIS].
        // Not a long press: on Wear a long press on the watch face belongs to the system, which
        // opens the face picker with it, so a watch face never sees the hold it would need.
        var lastTapMillis = 0L
        return WatchFace(WatchFaceType.ANALOG, renderer)
            .setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(
                    tapType: Int,
                    tapEvent: TapEvent,
                    complicationSlot: ComplicationSlot?,
                ) {
                    if (tapType != TapType.UP) return
                    // Keyed on the status rather than on the record, so the pair of taps is asked
                    // for exactly while the dial is showing MAN DOWN. A record left on file with
                    // nothing on screen must not swallow ordinary taps.
                    if (vigilance.state.status == VigilanceState.INCIDENT) {
                        val now = System.currentTimeMillis()
                        val doubled = now - lastTapMillis in 1..DOUBLE_TAP_MILLIS
                        lastTapMillis = if (doubled) 0L else now
                        if (doubled) {
                            vigilance.state.clearHintUntilMillis = 0L
                            vigilance.clearIncident()
                        } else {
                            // The gesture's second half, said where the first tap landed. The
                            // hint outlives the 900 ms pairing window on purpose: at the resting
                            // one-frame-a-second rate a shorter hint could fall between frames,
                            // and a tap while it still shows simply opens a fresh window.
                            vigilance.state.clearHintUntilMillis = now + CLEAR_HINT_MILLIS
                            renderer.invalidate()
                        }
                        return
                    }
                    vigilance.acknowledge()
                }
            })
    }

    /**
     * Refresh when the screen comes on.
     *
     * Normally this reuses a cached fix and never wakes the GPS — the periodic worker owns the
     * expensive path. The exception is a cold start: with no position on record at all there is
     * nothing to reuse, and waiting for the worker's first run would leave the telemetry window
     * reading `NO FIX` for up to half an hour after install. One acquisition in that case is worth
     * it; it is a single request, not a subscription.
     */
    private fun refreshOpportunistically() {
        val now = System.currentTimeMillis()
        if (now - lastOpportunisticRefresh < OPPORTUNISTIC_INTERVAL_MS) return
        lastOpportunisticRefresh = now
        val coldStart = !repository.hasCachedPosition()
        Log.d(TAG, "screen on: refreshing telemetry (coldStart=$coldStart)")
        serviceScope.launch {
            runCatching { repository.refresh(allowActiveFix = coldStart, nowMillis = now) }
                .onFailure { Log.w(TAG, "Opportunistic telemetry refresh failed", it) }
        }
    }

    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                val scheduled = if (BuildConfig.WORLD == PlanetMode.ID_MARS) {
                    MarsEphemerisWorker.scheduleIfUnlocked(context)
                } else {
                    TelemetryWorker.scheduleIfUnlocked(context)
                }
                if (scheduled) {
                    runCatching { context.unregisterReceiver(this) }
                    unlockReceiver = null
                }
            }
        }
        unlockReceiver = receiver
        registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
    }

    private companion object {
        const val TAG = "MfdWatchFace"

        /** Floor between screen-on refreshes, so waking the watch repeatedly costs nothing. */
        const val OPPORTUNISTIC_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * How long the second tap has to arrive to count as clearing an incident. Long enough to be
         * comfortable deliberately, short enough that two unrelated brushes will not pair up.
         */
        const val DOUBLE_TAP_MILLIS = 900L

        /**
         * How long `TAP AGAIN` stays on the status line after the first tap. Longer than
         * [DOUBLE_TAP_MILLIS] so it survives the resting frame rate; see the tap listener.
         */
        const val CLEAR_HINT_MILLIS = 2500L
    }
}
