// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The two optional readouts either side of the hub, and the sensors behind them.
 *
 * ### Why they run only while the screen is on
 * The heart-rate sensor is an optical one: reading it means lighting an LED against the wrist and
 * keeping it lit. Left registered it would run for the whole watch, which is the sort of standing
 * cost the rest of this face is built to avoid — and it would buy nothing, because a number nobody
 * is looking at is not information. So the registration follows the watch face's own visibility:
 * the sensors come up when the screen does and go down with it. What you read is therefore a
 * reading taken while you were reading it.
 *
 * The consequence to know about is that a value takes a moment to arrive. Optical heart rate needs
 * several seconds to lock from cold, so the readout shows `--` first and fills in. That is honest,
 * and better than showing a number from ten minutes ago as though it were now.
 *
 * ### Where the step count comes from
 * From the platform, when the platform will give it: [DailySteps] subscribes to Health Services'
 * own `DAILY_STEPS`, which is the figure the rest of the watch shows and is accumulated whether or
 * not this face was installed. That matters for the first day of every new wearer — walk four
 * thousand steps, install the face, and a self-counted row would say `0`.
 *
 * The counter path below is the fallback for builds with no Health Services.
 * [Sensor.TYPE_STEP_COUNTER] counts from the last reboot, so steps for the day need the counter's
 * value at the day's start subtracted, and that has to be kept somewhere a reboot cannot lose — so
 * it lives beside the shift and the incident log in device-protected storage. A reboot resets the
 * hardware counter without resetting the day, so the baseline is dropped whenever the counter comes
 * back *below* it: see [stepsToday].
 */
class SensorSlots(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(SensorManager::class.java)
    private val state = TelemetryRepository.get(appContext).state

    private val prefs: SharedPreferences = appContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The kinds currently asked for, as [Kind] values. Both [Kind.OFF] means nothing is running. */
    private var left = Kind.OFF
    private var right = Kind.OFF
    private var listening = false

    private val dailySteps = DailySteps(appContext)

    /** True while the platform is supplying the step total, so the raw counter is left alone. */
    private var stepsFromPlatform = false

    /**
     * True once the platform has actually delivered a figure — not merely accepted the
     * subscription.
     *
     * The two are different, and the difference decides which number the dial shows. A provider
     * that binds and then says nothing needs the raw counter behind it; a provider that answers
     * owns the row, and the counter must not be allowed to overwrite it — they measure different
     * things, and whichever wrote last would win.
     */
    private var platformDelivered = false

    private val handler = Handler(Looper.getMainLooper())

    /** Set by [stepsToday] when it had to create today's zero point. See [onSensorChanged]. */
    private var baselineIsNew = false

    /**
     * Falls back to the raw counter when the platform accepted the subscription and then said
     * nothing.
     *
     * A passive listener reports on the platform's own schedule, so dashes for a few seconds are
     * normal and honest. Dashes for ever are not: a Health Services build that takes the
     * subscription and never delivers would leave this row permanently empty, which reads as the
     * feature being broken. So the counter is brought up alongside after
     * [PLATFORM_GRACE_MILLIS] if nothing has arrived. Whichever reports next wins, and the
     * platform's figure is the one that keeps winning — it arrives repeatedly.
     */
    private val stepsFallback = Runnable {
        if (state.stepsToday != NO_READING) return@Runnable
        val sensor = manager?.getDefaultSensor(Kind.STEPS.type) ?: return@Runnable
        Log.d(TAG, "no daily total yet; bringing up the step counter as well")
        manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    /**
     * Which kinds to feed, and whether to be running at all.
     *
     * Called on a style change and on every visibility change, so it has to be cheap and idempotent
     * when nothing has moved.
     */
    fun configure(leftKind: Int, rightKind: Int, visible: Boolean) {
        val wantLeft = Kind.of(leftKind)
        val wantRight = Kind.of(rightKind)
        val changed = wantLeft != left || wantRight != right
        left = wantLeft
        right = wantRight
        val shouldListen = visible && (left != Kind.OFF || right != Kind.OFF)
        if (shouldListen && (!listening || changed)) {
            stop()
            start()
        } else if (!shouldListen && listening) {
            stop()
        }
    }

    private fun start() {
        val sensorManager = manager ?: return
        var any = false
        for (kind in Kind.entries) {
            if (kind == Kind.OFF || (kind != left && kind != right)) continue
            if (!permitted(kind)) {
                // Nothing to do about it here — the editor is where a permission can be asked for,
                // and the readout shows dashes until it is granted.
                Log.d(TAG, "${kind.name} not permitted")
                continue
            }
            if (kind == Kind.STEPS) {
                // The platform's own total first. Only if it will not give one does the raw
                // counter — and this app's own baseline arithmetic — come into it at all.
                stepsFromPlatform = dailySteps.start { steps ->
                    // The platform answered: it owns this row from here on. The fallback is
                    // cancelled and the counter, if it had already been brought up, is dropped.
                    if (!platformDelivered) {
                        platformDelivered = true
                        handler.removeCallbacks(stepsFallback)
                        manager?.getDefaultSensor(Kind.STEPS.type)?.let { sensor ->
                            runCatching { manager?.unregisterListener(this, sensor) }
                        }
                    }
                    state.stepsToday = steps
                }
                if (stepsFromPlatform) {
                    handler.postDelayed(stepsFallback, PLATFORM_GRACE_MILLIS)
                    any = true
                    continue
                }
            }
            val sensor = sensorManager.getDefaultSensor(kind.type) ?: continue
            // SENSOR_DELAY_UI rather than anything faster: none of these change quickly, and the
            // two on-change sensors ignore the rate entirely.
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            any = true
        }
        listening = any
    }

    fun stop() {
        if (!listening) return
        manager?.unregisterListener(this)
        handler.removeCallbacks(stepsFallback)
        if (stepsFromPlatform) {
            dailySteps.stop()
            stepsFromPlatform = false
        }
        platformDelivered = false
        listening = false
    }

    private fun permitted(kind: Kind): Boolean {
        val permission = kind.permission ?: return true
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor?.type) {
            Sensor.TYPE_HEART_RATE -> {
                // A zero or a negative is the sensor saying it has not locked on, not a pulse of
                // zero. Left as no reading, so the dial keeps showing dashes rather than a corpse.
                val bpm = event.values[0].toInt()
                state.heartRate = if (bpm > 0) bpm else NO_READING
            }

            // Ignored outright once the platform is answering: see platformDelivered.
            Sensor.TYPE_STEP_COUNTER -> if (!platformDelivered) {
                // A zero this app has just invented is not a reading. The counter measures from
                // the last reboot, so the first sample of a day is subtracted from itself and
                // yields exactly 0 — which is what a freshly installed face used to show somebody
                // who had already walked four thousand steps. Dashes are honest; 0 is not, so it
                // waits for a step or for the platform's own daily total to arrive.
                val steps = stepsToday(event.values[0].toLong(), System.currentTimeMillis())
                if (steps > 0 || !baselineIsNew) state.stepsToday = steps
            }

            Sensor.TYPE_PRESSURE -> {
                // Gated on plausibility for the same reason a heart rate of zero is: the first
                // event after registering can carry something that is not a reading at all. This
                // watch produced 2048 hPa, which is not a pressure that exists anywhere on Earth --
                // Everest is about 330 and the highest ever recorded at sea level about 1084. A
                // number the dial cannot mean is worse than dashes.
                val hpa = event.values[0]
                state.localPressureTenths = if (hpa in MIN_PRESSURE_HPA..MAX_PRESSURE_HPA) {
                    Math.round(hpa * 10f)
                } else {
                    NO_READING
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Steps since the most recent local midnight, from a counter that counts since boot.
     *
     * Local rather than UTC, which it used to be: every other figure of the day on this dial is
     * either labelled Z or runs on local time, and a step count that silently reset at 21:00 or
     * 03:00 local read as the sensor glitching. The offset is a parameter so the two day-boundary
     * cases stay testable without depending on the JVM's default zone.
     *
     * Internal rather than private so a test can hold it to the two cases that are easy to get
     * wrong: a new day, and a reboot part-way through one.
     */
    internal fun stepsToday(
        counter: Long,
        nowMillis: Long,
        zoneOffsetMillis: Int = TimeZone.getDefault().getOffset(nowMillis),
    ): Int {
        val day = (nowMillis + zoneOffsetMillis) / DAY_MILLIS
        val storedDay = prefs.getLong(KEY_BASELINE_DAY, -1L)
        val stored = prefs.getLong(KEY_BASELINE_COUNT, 0L)
        val baseline = baselineFor(counter, stored, storedDay, day)
        // Whether this call is the one that *invented* today's zero point. A baseline made here
        // makes the subtraction below return 0 by construction, and 0 is what the caller must not
        // publish: see onSensorChanged.
        baselineIsNew = day != storedDay
        if (baseline != stored || day != storedDay) {
            runCatching {
                prefs.edit()
                    .putLong(KEY_BASELINE_DAY, day)
                    .putLong(KEY_BASELINE_COUNT, baseline)
                    .apply()
            }
        }
        val steps = counter - baseline
        return if (steps > Int.MAX_VALUE) Int.MAX_VALUE else steps.toInt()
    }

    /**
     * What a slot can show.
     *
     * The set is what this hardware actually offers through a public API, which is narrower than
     * the sensor list suggests. Blood oxygen is present as `android.sensor.ppg_spo2`, a vendor type
     * with no `Sensor.TYPE_*` constant, and it is on-change — it produces a value when the
     * manufacturer's own app runs a measurement and not otherwise, so a watch face reading it would
     * show either nothing or something from yesterday. Ambient temperature is present too, and it
     * is the barometer chip's own die temperature: it reads the warmth of the watch, not the air.
     */
    enum class Kind(val id: String, val type: Int, val permission: String?) {
        OFF("off", -1, null),
        HEART_RATE("hr", Sensor.TYPE_HEART_RATE, Manifest.permission.BODY_SENSORS),
        STEPS("steps", Sensor.TYPE_STEP_COUNTER, Manifest.permission.ACTIVITY_RECOGNITION),

        /**
         * Station pressure from the watch's own barometer, which is a different reading from the
         * `Q` on the weather row: that is sea-level pressure for the nearest station, this is the
         * air where you are standing. Costs no permission and no radio.
         */
        PRESSURE("qfe", Sensor.TYPE_PRESSURE, null),
        ;

        companion object {
            fun of(ordinal: Int): Kind = entries.getOrElse(ordinal) { OFF }

            fun ofId(id: String): Kind = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    companion object {
        /**
         * How long the platform's own step total is waited for before the raw counter is brought
         * up beside it. Long enough for a passive listener's first delivery, short enough that a
         * silent provider does not cost the row a whole day.
         */
        private const val PLATFORM_GRACE_MILLIS = 30_000L

        /**
         * Where the day's step count is measured from.
         *
         * Pure, and separate from the write, because it is the arithmetic and it has two traps. A
         * new day restarts it, obviously. Less obviously, a counter that comes back *below* its own
         * baseline means the hardware counter restarted without the day doing so — a reboot — and
         * without that case the readout would go negative and stay there until midnight.
         */
        fun baselineFor(counter: Long, storedBaseline: Long, storedDay: Long, day: Long): Long =
            if (day != storedDay || counter < storedBaseline) counter else storedBaseline

        private const val TAG = "SensorSlots"
        private const val PREFS_NAME = "mfd24_sensors"
        private const val KEY_BASELINE_DAY = "step_day"
        private const val KEY_BASELINE_COUNT = "step_baseline"

        private val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)

        /** No value yet, or none obtainable. The readout shows dashes. */
        const val NO_READING: Int = -1

        /**
         * The range a station-pressure reading has to fall in to be shown.
         *
         * Wide enough for anywhere a person can stand -- the summit of Everest is about 330 hPa and
         * the highest ever recorded at sea level about 1084 -- and narrow enough to reject whatever
         * it is this watch reports before the barometer has settled.
         */
        private const val MIN_PRESSURE_HPA = 250f
        private const val MAX_PRESSURE_HPA = 1150f
    }
}
