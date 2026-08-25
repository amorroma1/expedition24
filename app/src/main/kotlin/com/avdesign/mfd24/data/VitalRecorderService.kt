// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.avdesign.mfd24.R
import com.avdesign.mfd24.health.DayBins
import com.avdesign.mfd24.health.DayLogCodec
import java.util.TimeZone

/**
 * Writes the day: one quarter-hourly tick that reads the step counter, takes a short pulse
 * sample, and files both into the bin the clock is standing in.
 *
 * ### Why this is a foreground service and not a worker
 * A watch face is a wallpaper, and a wallpaper stops receiving sensor events the moment the
 * screen goes off — which is most of the day and all of the night, the two parts of it this
 * record exists to describe. `WorkManager` cannot help either: its database is
 * credential-encrypted, so nothing it schedules runs before the first unlock after a reboot.
 * A foreground service of type `health` is the one honest way to keep a day's record, which is
 * why the recorder is opt-in and says so.
 *
 * ### What it costs, stated plainly
 * One alarm and one counter read per quarter-hour — the step counter runs in the sensor hub
 * whether or not anything asks — plus an optical pulse sample of about twenty seconds, so the
 * LED is lit for two to seven per cent of the interval depending on the setting. There is **no
 * standing accelerometer**: vigilance runs one because a dead-man's switch must see a wrist move
 * the moment it does, but a day log only needs to know whether steps happened in a quarter-hour,
 * and the hub's own low-power step pipeline already computes exactly that. Re-deriving a worse
 * version of it at 50 Hz would be pure battery.
 *
 * ### Doze, and why the bins are fifteen minutes
 * In deep idle the platform serves an app roughly one while-idle alarm every nine minutes, and
 * the vigilance monitor already draws on that budget — so this one asks only for
 * `setAndAllowWhileIdle` and is *designed* to be served late. A stretched alarm lands in the
 * same bin or the next, and the day's totals do not care at all: every bin's steps are a
 * difference of two cumulative counter readings, so lateness moves a figure's shape, never its
 * sum. Bins the recorder never reached stay unflagged, which the dial draws as a gap — the
 * honest statement that nobody was looking, as distinct from nothing having happened.
 */
class VitalRecorderService : Service(), SensorEventListener {

    private lateinit var monitor: VitalMonitor
    private lateinit var store: VitalStore

    private val handler = Handler(Looper.getMainLooper())

    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var heartSensor: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val hr = ByteArray(DayBins.BIN_COUNT)
    private val steps = ShortArray(DayBins.BIN_COUNT)
    private val flags = ByteArray(DayBins.BIN_COUNT)

    @Volatile
    private var intervalMillis: Long = DEFAULT_INTERVAL_MILLIS

    /** Defaults to worn: hardware without the detector must not record a day as off the wrist. */
    @Volatile
    private var onBody = true

    @Volatile
    private var charging = false

    /** The tick under way, or null between ticks. Holds what the sensors have answered so far. */
    private var tick: Tick? = null

    private val tickTimeout = Runnable { finishTick("timed out") }

    /**
     * False when [startForeground] was refused — on API 34+ the `health` type needs one of the
     * body-sensor permissions, and a refusal must stop the service rather than surface later as
     * a SecurityException from somewhere less obvious.
     */
    private var foregrounded = false

    private var chargeReceiverRegistered = false

    private val chargeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            charging = intent.action == Intent.ACTION_POWER_CONNECTED
        }
    }

    private class Tick(val nowMillis: Long) {
        var stepsRead = false
        var counter = -1L
        var heartDone = false
        var bpm = DayBins.NO_BPM
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        monitor = VitalMonitor.get(this)
        store = monitor.store
        intervalMillis = store.intervalMillis(DEFAULT_INTERVAL_MILLIS)

        sensorManager = getSystemService(SensorManager::class.java)
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        heartSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        offBodySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)

        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onSuccess { foregrounded = true }
            .onFailure {
                Log.w(TAG, "Could not go foreground; the day cannot be recorded", it)
                stopSelf()
            }
        if (!foregrounded) return

        charging = readChargingState()
        runCatching {
            registerReceiver(
                chargeReceiver,
                IntentFilter(Intent.ACTION_POWER_CONNECTED).apply {
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                },
            )
            chargeReceiverRegistered = true
        }
        // On-change, wake-up, no permission, and already powered for other clients: subscribing
        // costs nothing and it is what keeps a watch on a table out of the sleep inference.
        offBodySensor?.let {
            runCatching { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregrounded) return START_NOT_STICKY
        // A restart is handed a null intent, which is why the configuration is on file rather
        // than only in the extras.
        when (intent?.action) {
            VitalMonitor.ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }

            VitalMonitor.ACTION_START -> {
                intervalMillis = intent.getLongExtra(
                    VitalMonitor.EXTRA_INTERVAL_MILLIS, store.intervalMillis(DEFAULT_INTERVAL_MILLIS),
                )
                store.saveConfig(recording = true, intervalMillis = intervalMillis)
                beginTick(System.currentTimeMillis())
            }

            ACTION_TICK -> beginTick(System.currentTimeMillis())

            else -> {
                if (!store.recording(false)) {
                    stopRecording()
                    return START_NOT_STICKY
                }
                beginTick(System.currentTimeMillis())
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickTimeout)
        sensorManager?.unregisterListener(this)
        if (chargeReceiverRegistered) {
            runCatching { unregisterReceiver(chargeReceiver) }
            chargeReceiverRegistered = false
        }
        releaseWakeLock()
        super.onDestroy()
    }

    // --- The tick ------------------------------------------------------------------------------

    private fun beginTick(nowMillis: Long) {
        // Scheduled first: a tick that dies half-way through must not take the chain with it.
        scheduleNext(nowMillis + intervalMillis)
        if (tick != null) return
        holdWakeLock()
        loadDay(nowMillis)

        val current = Tick(nowMillis)
        tick = current
        handler.postDelayed(tickTimeout, TICK_TIMEOUT_MILLIS)

        // The counter is cumulative, so one registration and its first event carry everything
        // this tick needs; keeping it registered between ticks would spend hub FIFO on events
        // nobody reads. Named unregister on the way out — the single-argument form would take
        // the off-body detector with it.
        val sensor = stepSensor
        if (sensor == null) {
            current.stepsRead = true
        } else {
            val registered = runCatching {
                sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) == true
            }.getOrDefault(false)
            if (!registered) current.stepsRead = true
        }

        // The LED is the one real cost here, so it is spent only where it can mean something:
        // not off the wrist, not on a charger, and never at all without the sensor.
        val heart = heartSensor
        if (heart == null || !onBody || charging) {
            current.heartDone = true
        } else {
            val registered = runCatching {
                sensorManager?.registerListener(this, heart, SensorManager.SENSOR_DELAY_NORMAL) == true
            }.getOrDefault(false)
            if (registered) {
                handler.postDelayed({ stopHeartRate() }, HEART_WINDOW_MILLIS)
            } else {
                current.heartDone = true
            }
        }
        maybeFinishTick()
    }

    private fun maybeFinishTick() {
        val current = tick ?: return
        if (!current.stepsRead || !current.heartDone) return
        finishTick("complete")
    }

    private fun finishTick(reason: String) {
        val current = tick ?: return
        tick = null
        handler.removeCallbacks(tickTimeout)
        stopHeartRate()
        stopStepRead()

        val offset = TimeZone.getDefault().getOffset(current.nowMillis)
        val bin = DayBins.binIndex(current.nowMillis, offset)

        var delta = 0L
        if (current.counter >= 0L) {
            delta = DayBins.stepDelta(current.counter, store.counterLast())
            store.saveCounterLast(current.counter)
        }
        // The whole delta lands in the bin the clock is in. Spreading it over the bins a late
        // alarm skipped would be invention; this way one bin's shape is off and every total is
        // exact, and the skipped bins stay honestly unflagged.
        val previousSteps = steps[bin].toInt()
        val binSteps = (previousSteps + delta).coerceAtMost(Short.MAX_VALUE.toLong()).toInt()
        steps[bin] = binSteps.toShort()

        if (current.bpm != DayBins.NO_BPM) hr[bin] = current.bpm.toByte()

        var f = flags[bin].toInt() and 0xFF
        f = f or DayBins.FLAG_SAMPLED
        if (onBody) f = f or DayBins.FLAG_ON_BODY
        if (charging) f = f or DayBins.FLAG_CHARGING
        if (delta >= DayBins.MOVING_MIN_STEPS) f = f or DayBins.FLAG_MOVING
        flags[bin] = f.toByte()

        val day = DayBins.localEpochDay(current.nowMillis, offset)
        store.saveToday(DayLogCodec.pack(day, hr, steps, flags))
        monitor.republish(current.nowMillis)
        releaseWakeLock()
        Log.d(TAG, "bin $bin: $reason, +$delta steps, ${current.bpm} bpm, flags $f")
    }

    /**
     * Reads today's bins into the working arrays, closing the previous day first when the clock
     * has crossed local midnight since the last tick.
     */
    private fun loadDay(nowMillis: Long) {
        val offset = TimeZone.getDefault().getOffset(nowMillis)
        val today = DayBins.localEpochDay(nowMillis, offset)
        val stored = DayLogCodec.unpack(store.today(), hr, steps, flags)
        if (stored == today) return

        if (stored != DayLogCodec.NO_DAY) {
            // The day that just ended keeps its own bins; the new one opens empty.
            val closing = DayLogCodec.pack(stored, hr, steps, flags)
            java.util.Arrays.fill(hr, 0)
            java.util.Arrays.fill(steps, 0)
            java.util.Arrays.fill(flags, 0)
            store.rollOver(closing, DayLogCodec.pack(today, hr, steps, flags))
            Log.i(TAG, "day $stored closed; $today opens")
            return
        }
        java.util.Arrays.fill(hr, 0)
        java.util.Arrays.fill(steps, 0)
        java.util.Arrays.fill(flags, 0)
        store.saveToday(DayLogCodec.pack(today, hr, steps, flags))
    }

    private fun stopRecording() {
        cancelNext()
        handler.removeCallbacks(tickTimeout)
        tick = null
        stopHeartRate()
        stopStepRead()
        releaseWakeLock()
        stopSelf()
    }

    // --- Sensors -------------------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent?) {
        val type = event?.sensor?.type ?: return
        when (type) {
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                onBody = event.values.firstOrNull() != 0f
            }

            Sensor.TYPE_STEP_COUNTER -> {
                val current = tick ?: return
                if (current.stepsRead) return
                current.counter = (event.values.firstOrNull() ?: return).toLong()
                current.stepsRead = true
                stopStepRead()
                maybeFinishTick()
            }

            Sensor.TYPE_HEART_RATE -> {
                val current = tick ?: return
                val bpm = (event.values.firstOrNull() ?: return).toInt()
                // A zero means "not locked on", never "no heartbeat" — the slots' own rule. The
                // last plausible reading of the window is kept, because the optical sensor
                // converges: the last lock is the best lock.
                if (IncidentLog.plausibleBpm(bpm)) current.bpm = bpm
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun stopStepRead() {
        stepSensor?.let { runCatching { sensorManager?.unregisterListener(this, it) } }
    }

    private fun stopHeartRate() {
        heartSensor?.let { runCatching { sensorManager?.unregisterListener(this, it) } }
        val current = tick ?: return
        if (current.heartDone) return
        current.heartDone = true
        maybeFinishTick()
    }

    private fun readChargingState(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return status != 0
    }

    // --- Power ---------------------------------------------------------------------------------

    private fun holdWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }

    /**
     * The next tick, deliberately inexact. Vigilance spends this app's exact-alarm budget on a
     * deadline that matters to the minute; a day log has no such claim, and being served late is
     * a shape this design already absorbs.
     */
    private fun scheduleNext(atMillis: Long) {
        val manager = getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, tickIntent())
        }.onFailure { Log.w(TAG, "Could not schedule the next bin", it) }
    }

    private fun cancelNext() {
        getSystemService(AlarmManager::class.java)?.let { runCatching { it.cancel(tickIntent()) } }
    }

    private fun tickIntent(): PendingIntent = PendingIntent.getForegroundService(
        this,
        REQUEST_TICK,
        Intent(this, VitalRecorderService::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // --- Foreground plumbing -------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vital_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vital_notification))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VitalRecorder"

        const val ACTION_TICK = "com.avdesign.mfd24.action.VITAL_TICK"

        /** Ten minutes: 144 pulse samples a day, and a comfortable two per quarter-hour bin. */
        const val DEFAULT_INTERVAL_MILLIS: Long = 10 * 60_000L

        /** How long the LED is given to lock on. Optical pulse needs several seconds from cold. */
        private const val HEART_WINDOW_MILLIS: Long = 20_000L

        /** A tick that has not heard from its sensors by now files what it has and lets go. */
        private const val TICK_TIMEOUT_MILLIS: Long = 30_000L

        /** Far past any window that could legitimately need it: a leak costs minutes, not days. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS: Long = 60_000L

        // Distinct from vigilance's 24 / mfd24_vigilance / mfd24:vigilance / 4001 throughout:
        // both are health services and both may run at once.
        private const val NOTIFICATION_ID = 25
        private const val CHANNEL_ID = "mfd24_vital"
        private const val WAKE_LOCK_TAG = "mfd24:vital"
        private const val REQUEST_TICK = 4002
    }
}
