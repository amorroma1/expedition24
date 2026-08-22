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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.avdesign.mfd24.R
import kotlin.math.sqrt

/**
 * Vigilance monitoring: a dead-man's check on the operator while a watch is under way.
 *
 * Every interval it asks for a sign of life — a nudge on the wrist, answered by moving the arm or
 * touching the screen. Thirty unanswered seconds and it escalates to SOS, which only a deliberate
 * touch or a button will stop.
 *
 * ### Why this is a foreground service
 * A watch face is a wallpaper. Once the screen goes off, the platform is free to stop delivering
 * sensor events to it and to stop running its timers on time — which is precisely when a vigilance
 * monitor has to work. So this runs as a foreground service.
 *
 * ### What it costs, and why it is no longer a wake lock
 * It used to hold a `PARTIAL_WAKE_LOCK` for the entire watch, which kept the application processor
 * awake for hours on end to watch for a signal that lives below 3 Hz. Two things replaced it, and
 * neither gives up a single sample:
 *
 *  - **Batching.** The accelerometer on this watch is a *non-wakeup* sensor with a 2016-event FIFO,
 *    so with [BATCH_LATENCY_MICROS] of report latency the sensor hub accumulates while the
 *    processor sleeps and hands the whole run over the next time something wakes it — which on Wear
 *    is at worst the once-a-minute always-on redraw. One interrupt every twenty seconds instead of
 *    fifty a second.
 *  - **The wake lock only while it is actually asking.** Nothing is held during [VigilanceState.ARMED];
 *    the interval's end is an `AlarmManager` alarm, which fires with the processor asleep. The lock
 *    is taken when the nudge goes out and held through the thirty-second answer window and any
 *    alarm, because *those* thirty seconds must be counted accurately and answered instantly.
 *
 * The sample *rate* is not one of the two, and [SENSOR_PERIOD_MICROS] explains why lowering it is
 * the one obvious saving that must not be taken.
 *
 * A gap longer than [MotionFilter.MAX_GAP_SECONDS] resets the filter rather than being read as
 * stillness: a batch that never arrived is missing evidence, not evidence of absence. The failure
 * direction is a nudge that need not have been sent, which is the safe way round for a dead-man's
 * switch.
 *
 * One consequence to know about: while the watch is in Doze the platform will not run an exact
 * alarm more than once every nine minutes per app, so a five-minute interval can stretch. The
 * escalation still happens, late rather than never, and the ten- and fifteen-minute intervals are
 * clear of the limit entirely.
 *
 * On charge the whole thing suspends: a watch on a charger is off a wrist, and there is nobody
 * there to fail to respond. Off the wrist it suspends for the same reason — see [setOnBody], which
 * also states the risk that buys.
 */
class VigilanceService : Service(), SensorEventListener {

    private val handler = Handler(Looper.getMainLooper())
    private val filter = MotionFilter()

    private lateinit var state: VigilanceState
    private lateinit var store: VigilanceStore
    private lateinit var sos: SosSignal
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var intervalMillis = DEFAULT_INTERVAL_MILLIS
    private var vibeAmplitude = Alerts.AMPLITUDE_MED
    private var toneVolume = Alerts.SOS_VOLUME_MED
    private var logHeartRate = false
    private var charging = false

    private var heartSensor: Sensor? = null

    /** Why the pulse sensor is on, or null when it is off. See [sampleHeartRate]. */
    private var heartPurpose: Int? = null

    /** Last plausible pulse while the operator was demonstrably moving, and when it was taken. */
    private var baselineBpm = IncidentRecord.NO_BPM
    private var baselineAtMillis = 0L

    /** Pulse during the answer window now open, cleared when the window closes either way. */
    private var promptBpm = IncidentRecord.NO_BPM

    /**
     * The pulse window's own timeout, held as one instance so it can be cancelled by identity.
     * `removeCallbacksAndMessages(null)` is used liberally in this class for the state machine's
     * timers, and the LED must not be left burning by a cancel meant for something else — nor
     * switched off by one.
     */
    private val heartTimeout = Runnable { stopHeartRate() }

    /**
     * False when [startForeground] was refused, which on API 34+ the `health` service type can be:
     * the platform demands one of the sensor runtime permissions alongside
     * `FOREGROUND_SERVICE_HEALTH`, and the editor asks for it, but a grant can be revoked from
     * settings afterwards. A service that cannot go foreground cannot sense with the screen off,
     * so it declines to pretend: it stops instead of arming a monitor that would quietly die.
     */
    private var foregrounded = false
    private var lastSensorNanos = 0L

    /**
     * Whether the watch is on a wrist. True until told otherwise, so hardware without an off-body
     * detector — and the moment before the first reading arrives — leaves the monitor armed. The
     * safe default for a dead-man's switch is watching.
     */
    private var onBody = true

    /** Nothing to watch: on a charger, or off the wrist. */
    private val suspended: Boolean
        get() = charging || !onBody

    /** null when not listening; otherwise whether the current registration is the unbatched one. */
    private var listeningImmediate: Boolean? = null

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> setCharging(true)
                Intent.ACTION_POWER_DISCONNECTED -> setCharging(false)
                // Waking the screen takes a button press, which is a sign of life in itself.
                Intent.ACTION_SCREEN_ON -> acknowledge()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        state = VigilanceMonitor.get(this).state
        store = VigilanceStore(this)
        sos = SosSignal(this)
        sensorManager = getSystemService(SensorManager::class.java)
        // TYPE_LINEAR_ACCELERATION is absent on this hardware — there is no fusion sensor at all —
        // and it would be redundant anyway: the filter is fed the magnitude, so gravity arrives as
        // a constant and the high-pass eats it.
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // On-change and wakeUp, and it needs no permission — this watch already runs it for four
        // other clients, so subscribing adds no sensor that was not already powered.
        offBodySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        // Present on this hardware but gated by BODY_SENSORS, and an LED against skin: registered
        // only in the two windows that need it, never for the life of the service.
        heartSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onSuccess { foregrounded = true }
            .onFailure {
                // See [foregrounded]. Failing loudly here beats a SecurityException later or a
                // monitor the platform silences the moment the screen goes off.
                Log.e(TAG, "Could not go foreground; vigilance cannot run", it)
                stopSelf()
                return
            }

        // Created, not acquired. See the class KDoc: it is held only while an answer is owed.
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)

        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )

        // Registered for the life of the service rather than around the interval: it is on-change,
        // so a wrist that stays put costs nothing, and the state has to be known the moment a
        // prompt is due rather than acquired then.
        offBodySensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        charging = readChargingState()
        // An incident outlives the process that recorded it. Restored before anything else decides
        // what to do, because it overrides all of it: a watch that has already been through an
        // unanswered escalation must not quietly go back to counting as though nothing happened.
        state.incidentMillis = store.incidentAt()
        state.publishIncidents(store.log())
        // The settings come back the same way: a START_STICKY restart delivers a null intent into
        // a fresh process, and the fields' defaults are not what the user chose.
        intervalMillis = store.intervalMillis(intervalMillis)
        vibeAmplitude = store.vibeAmplitude(vibeAmplitude)
        toneVolume = store.toneVolume(toneVolume)
        logHeartRate = store.logHeartRate(logHeartRate)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregrounded) return START_NOT_STICKY
        when (intent?.action) {
            VigilanceMonitor.ACTION_ACKNOWLEDGE -> acknowledge()

            VigilanceMonitor.ACTION_CLEAR_INCIDENT -> dismissIncident()

            // The interval ran out with the processor asleep; this is the alarm arriving.
            ACTION_CHECK -> if (!suspended) prompt()

            else -> {
                intervalMillis =
                    intent?.getLongExtra(EXTRA_INTERVAL_MILLIS, intervalMillis) ?: intervalMillis
                vibeAmplitude =
                    intent?.getIntExtra(EXTRA_VIBE_AMPLITUDE, vibeAmplitude) ?: vibeAmplitude
                toneVolume =
                    intent?.getIntExtra(EXTRA_TONE_VOLUME, toneVolume) ?: toneVolume
                logHeartRate =
                    intent?.getBooleanExtra(EXTRA_LOG_HEART_RATE, logHeartRate) ?: logHeartRate
                // Persisted, because a START_STICKY restart is a null intent into a fresh
                // process: without this the fields' defaults quietly replaced the user's choice
                // until the face's next frame happened to re-report it.
                store.saveConfig(intervalMillis, vibeAmplitude, toneVolume, logHeartRate)
                retireIncidentBefore(intent?.getLongExtra(EXTRA_SHIFT_START_MILLIS, 0L) ?: 0L)
                settle()
            }
        }
        // Restarted without its extras it still knows the interval it was last given — onCreate
        // reads it back from the store the line above wrote.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelCheck()
        sos.stop()
        stopListening()
        stopHeartRate()
        sensorManager?.unregisterListener(this)
        runCatching { unregisterReceiver(powerReceiver) }
        releaseWakeLock()
        wakeLock = null
        // An incident is not ended by the service ending. See VigilanceMonitor.stop.
        if (state.incidentMillis == 0L) state.status = VigilanceState.OFF
        state.deadlineMillis = 0L
        state.periodMillis = 0L
        super.onDestroy()
    }

    // --- The state machine -----------------------------------------------------------------

    /**
     * Picks the state the service belongs in, from scratch.
     *
     * Every transition ends here rather than choosing for itself, because the precedence is the
     * whole of the policy and it was previously written out at three call sites that had already
     * begun to disagree: an incident outranks everything, a charger and a bare wrist outrank
     * counting, and counting is what is left.
     */
    private fun settle() {
        when {
            state.incidentMillis != 0L -> holdIncident()
            charging -> suspendFor(VigilanceState.CHARGING, "on charge")
            !onBody -> suspendFor(VigilanceState.OFF_BODY, "off the wrist")
            else -> arm()
        }
    }

    /**
     * Drops an incident belonging to an earlier watch.
     *
     * Starting a watch is a deliberate act by somebody demonstrably conscious, so a record from the
     * *previous* one has no business holding the monitor down through this one — and holding it
     * down is what it did: the service came back up, found the incident, and settled into
     * [holdIncident] instead of arming, leaving the new watch with no dead-man's switch at all and
     * a full hub core to say so.
     *
     * Only the incident in force is dropped. The log keeps the entry, and the mark stays on the arc
     * of the watch it happened on.
     */
    private fun retireIncidentBefore(shiftStartMillis: Long) {
        if (!VigilanceStore.belongsToEarlierWatch(state.incidentMillis, shiftStartMillis)) return
        Log.d(TAG, "incident predates this watch; retiring it")
        clearIncident()
    }

    /** Back to watching, with a fresh interval ahead and nothing holding the processor awake. */
    private fun arm() {
        sos.stop()
        handler.removeCallbacksAndMessages(null)
        stopHeartRate()
        promptBpm = IncidentRecord.NO_BPM
        releaseWakeLock()
        state.status = VigilanceState.ARMED
        state.periodMillis = intervalMillis
        state.deadlineMillis = System.currentTimeMillis() + intervalMillis
        // Batched: the hub collects, the processor sleeps.
        listen(immediate = false)
        scheduleCheck(state.deadlineMillis)
        // The reference pulse is taken *here*, on the way back to watching — which is the one
        // moment the operator is known to be conscious, because something they did got us here.
        // A baseline sampled at any other time is a number with no claim attached to it.
        sampleHeartRate(PURPOSE_BASELINE, BASELINE_SAMPLE_MILLIS)
    }

    /** The interval is up: ask for a sign of life. */
    private fun prompt() {
        if (suspended) return
        // From here the answer window has to be counted accurately and a movement seen the moment
        // it happens, so the processor stays up and the sensor stops batching.
        holdWakeLock()
        listen(immediate = true)
        Alerts.signal(this, vibeAmplitude)
        state.status = VigilanceState.PROMPT
        state.periodMillis = PROMPT_WINDOW_MILLIS
        state.deadlineMillis = System.currentTimeMillis() + PROMPT_WINDOW_MILLIS
        // The pulse that matters: taken between the nudge and the SOS, which is the operator at
        // the moment they stopped answering. Read against the baseline, never on its own.
        sampleHeartRate(PURPOSE_PROMPT, PROMPT_WINDOW_MILLIS)
        handler.postDelayed(::escalate, PROMPT_WINDOW_MILLIS)
        Log.d(TAG, "prompting; ${PROMPT_WINDOW_MILLIS / 1000}s to answer")
    }

    /** Nobody answered. */
    private fun escalate() {
        if (suspended) return
        holdWakeLock()
        state.status = VigilanceState.ALARM
        state.deadlineMillis = 0L
        state.periodMillis = 0L
        // The moment of the incident is fixed here, when the answer window closed, not five minutes
        // later when the signalling gives up — that is when the operator stopped responding.
        recordIncident(System.currentTimeMillis())
        // The window is closed; whatever the sensor got is what the record carries.
        stopHeartRate()
        sos.start(vibeAmplitude, toneVolume, ::signallingExhausted)
        Log.w(TAG, "no response; sounding SOS")
    }

    /**
     * Five minutes of SOS and nobody came.
     *
     * Signalling stops and the wake lock goes with it. The incident stays on the dial, and the
     * battery that would have gone into another hour of beeping goes into keeping it legible for
     * whoever finds the watch.
     */
    private fun signallingExhausted() = holdIncident()

    /**
     * Settles into the incident state without signalling: the state a restarted service comes back
     * to when there is an incident on file, and the state the SOS leaves behind.
     */
    private fun holdIncident() {
        handler.removeCallbacksAndMessages(null)
        cancelCheck()
        sos.stop()
        stopListening()
        stopHeartRate()
        releaseWakeLock()
        state.status = VigilanceState.INCIDENT
        state.deadlineMillis = 0L
        state.periodMillis = 0L
        Log.w(TAG, "incident on file; holding")
    }

    /**
     * Writes the incident where a restart, or a flat battery and a charger, cannot lose it — twice,
     * to the two records [VigilanceStore] keeps apart. The one in force is what the dial shows and
     * what a deliberate touch clears; the log entry is permanent, and is what puts the mark on the
     * duty arc.
     */
    private fun recordIncident(atMillis: Long) {
        state.incidentMillis = atMillis
        store.setIncidentAt(atMillis)
        state.publishIncidents(
            store.append(
                IncidentRecord(
                    atMillis = atMillis,
                    bpm = promptBpm,
                    baselineBpm = baselineBpm,
                    baselineAtMillis = baselineAtMillis,
                )
            )
        )
    }

    private fun clearIncident() {
        state.incidentMillis = 0L
        store.clearIncidentAt()
    }

    /**
     * A sign of life, from wherever. Silences an alarm and restarts the interval — an operator who
     * is demonstrably moving does not need asking again for a while.
     */
    fun acknowledge() {
        if (suspended) return
        if (state.status == VigilanceState.OFF) return
        // An incident is not answered by a sign of life — the escalation it records already went
        // unanswered, and a sleeve brushing the screen must not erase it. Clearing is its own
        // deliberate act.
        if (state.status == VigilanceState.INCIDENT) return
        val wasAlarming = state.status == VigilanceState.ALARM
        arm()
        if (wasAlarming) Log.d(TAG, "alarm acknowledged")
    }

    /** Deliberately clearing a recorded incident, and only then going back to watching. */
    private fun dismissIncident() {
        if (state.incidentMillis == 0L) return
        Log.d(TAG, "incident cleared")
        clearIncident()
        settle()
    }

    private fun setCharging(value: Boolean) {
        if (charging == value) return
        charging = value
        settle()
    }

    /**
     * The wrist came or went.
     *
     * A watch on a table cannot fail to respond, so every nudge it is sent is a false one — and a
     * false nudge at three in the morning is how a dead-man's switch gets switched off for good.
     * That is what this is for, and it is the largest single source of false incidents.
     *
     * The risk it buys is stated plainly: a detector that reports off-body while the watch is
     * *worn* — a loose strap, a sleeve — stops the monitor watching a wrist that is still there,
     * which is the unsafe direction. It is taken because the detector on this hardware is a
     * dedicated on-change sensor rather than an inference, and because the state is on the dial:
     * `OFF WRIST` is shown for exactly this, so a monitor that has stopped watching says so instead
     * of looking like one that is armed.
     */
    private fun setOnBody(value: Boolean) {
        if (onBody == value) return
        onBody = value
        Log.d(TAG, if (value) "back on the wrist" else "off the wrist")
        settle()
    }

    private fun suspendFor(status: Int, reason: String) {
        handler.removeCallbacksAndMessages(null)
        cancelCheck()
        sos.stop()
        // Nothing to watch and nobody to watch it: stop the sensor outright rather than batching
        // events no one will read.
        stopListening()
        stopHeartRate()
        releaseWakeLock()
        state.status = status
        state.deadlineMillis = 0L
        state.periodMillis = 0L
        Log.d(TAG, "$reason; monitoring suspended")
    }

    private fun readChargingState(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return status != 0
    }

    // --- Power ---------------------------------------------------------------------------------

    /**
     * Takes the wake lock if it is not already held, with a timeout well past any window that could
     * legitimately need it — a lock leaked by a bug then costs minutes of battery rather than days.
     */
    private fun holdWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }

    private fun scheduleCheck(atMillis: Long) {
        val manager = getSystemService(AlarmManager::class.java) ?: return
        val pending = checkIntent()
        // Same fallback as the duty timer: without the exact-alarm permission an inexact alarm is
        // still far better than a timer that a sleeping processor never runs.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        }.onFailure { Log.w(TAG, "Could not schedule the vigilance check", it) }
    }

    private fun cancelCheck() {
        getSystemService(AlarmManager::class.java)?.let { runCatching { it.cancel(checkIntent()) } }
    }

    private fun checkIntent(): PendingIntent = PendingIntent.getForegroundService(
        this,
        REQUEST_CHECK,
        Intent(this, VigilanceService::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // --- Motion ------------------------------------------------------------------------------

    /**
     * Registers the accelerometer, batched or not.
     *
     * Changing the report latency means re-registering, and re-registering breaks the run of
     * samples the filter is integrating, so its state goes with it. A no-op when the requested mode
     * is already the one in force.
     *
     * Named sensor on the way out as well as in: the single-argument `unregisterListener` drops
     * *every* sensor this listener holds, which would take the off-body detector with it the first
     * time a prompt went out and leave the watch believing it was worn for ever after.
     */
    private fun listen(immediate: Boolean) {
        if (listeningImmediate == immediate) return
        val manager = sensorManager ?: return
        val sensor = accelerometer ?: return
        manager.unregisterListener(this, sensor)
        filter.reset()
        lastSensorNanos = 0L
        manager.registerListener(
            this,
            sensor,
            SENSOR_PERIOD_MICROS,
            if (immediate) 0 else BATCH_LATENCY_MICROS,
        )
        listeningImmediate = immediate
    }

    private fun stopListening() {
        accelerometer?.let { sensorManager?.unregisterListener(this, it) }
        listeningImmediate = null
        filter.reset()
        lastSensorNanos = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type == Sensor.TYPE_HEART_RATE) {
            onHeartRate(event.values.getOrNull(0)?.toInt() ?: 0)
            return
        }
        if (event.sensor?.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            // 1.0 is on-body, 0.0 is off. Compared loosely because it is a float carrying a flag.
            setOnBody(event.values[0] > 0.5f)
            return
        }
        if (suspended || state.status == VigilanceState.OFF) return

        // Batched events carry the timestamps they were sampled at, not the moment they arrived, so
        // the filter still sees an even 13 Hz however late the batch is handed over.
        val deltaSeconds = if (lastSensorNanos == 0L) {
            0f
        } else {
            (event.timestamp - lastSensorNanos) / 1_000_000_000f
        }
        lastSensorNanos = event.timestamp

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        if (filter.accept(magnitude, deltaSeconds)) {
            acknowledge()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- Heart rate ----------------------------------------------------------------------------

    /**
     * Runs the pulse sensor for [windowMillis] and no longer.
     *
     * The optical sensor is an LED pressed against skin: leaving it on for a watch's worth of hours
     * would cost more than everything else this service does put together, and it would be for a
     * number nobody reads until an incident. So it runs in exactly two windows — the moment after a
     * confirmed sign of life, and the thirty seconds between the nudge and the SOS — and the rest
     * of the time it is off.
     *
     * A no-op unless the wearer asked for it: the setting is off by default, and it needs
     * `BODY_SENSORS`, which a watch face cannot request for itself — the editor asks, and until it
     * is granted the row is not offered. Absent hardware, a refused registration and a sensor that
     * never locks on are all the same outcome, and the record says nothing rather than guessing.
     */
    private fun sampleHeartRate(purpose: Int, windowMillis: Long) {
        if (!logHeartRate) return
        val sensor = heartSensor ?: return
        if (heartPurpose == purpose) return
        stopHeartRate()
        val registered = runCatching {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) == true
        }.getOrDefault(false)
        if (!registered) {
            Log.d(TAG, "pulse sensor refused; the record will carry no reading")
            return
        }
        heartPurpose = purpose
        // Stops itself: the window is the whole point, and a callback that forgets to unregister
        // leaves the LED burning until the next state change happens to notice.
        handler.postDelayed(heartTimeout, windowMillis)
    }

    private fun stopHeartRate() {
        handler.removeCallbacks(heartTimeout)
        if (heartPurpose == null) return
        heartPurpose = null
        heartSensor?.let { runCatching { sensorManager?.unregisterListener(this, it) } }
    }

    /**
     * A reading arrived. Zero means "not locked on" rather than "no heartbeat", which is why this
     * goes through [IncidentLog.plausibleBpm] instead of a null check — the same rule the sensor
     * slots use, and for the same reason.
     */
    private fun onHeartRate(bpm: Int) {
        if (!IncidentLog.plausibleBpm(bpm)) return
        when (heartPurpose) {
            PURPOSE_BASELINE -> {
                baselineBpm = bpm
                baselineAtMillis = System.currentTimeMillis()
            }
            PURPOSE_PROMPT -> promptBpm = bpm
            else -> return
        }
    }

    // --- Foreground plumbing -----------------------------------------------------------------

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vigilance_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vigilance_notification))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Vigilance"
        private const val CHANNEL_ID = "mfd24_vigilance"
        private const val NOTIFICATION_ID = 24
        private const val WAKE_LOCK_TAG = "mfd24:vigilance"
        private const val REQUEST_CHECK = 4001

        /** Delivered by the interval alarm: the answer window is due to open. */
        const val ACTION_CHECK = "com.avdesign.mfd24.action.VIGILANCE_CHECK"

        const val EXTRA_INTERVAL_MILLIS = "interval_millis"
        const val EXTRA_VIBE_AMPLITUDE = "vibe_amplitude"

        /**
         * When the watch under way began. Carried on every start so the service can tell an
         * incident from this watch — which must hold — from one belonging to a previous watch,
         * which must not. See [retireIncidentBefore].
         */
        const val EXTRA_SHIFT_START_MILLIS = "shift_start_millis"

        const val EXTRA_TONE_VOLUME = "sos_volume"
        const val EXTRA_LOG_HEART_RATE = "log_heart_rate"

        /** Thirty seconds to answer, per the brief. */
        const val PROMPT_WINDOW_MILLIS = 30_000L

        private const val PURPOSE_BASELINE = 1
        private const val PURPOSE_PROMPT = 2

        /**
         * How long the reference pulse is allowed to take.
         *
         * A wrist optical sensor needs a few seconds of contact before it reports anything at all,
         * and this runs after every confirmed sign of life — so it has to be long enough to lock on
         * and short enough that the LED is off again well before the next check is due, even at the
         * five-minute interval.
         */
        private const val BASELINE_SAMPLE_MILLIS = 20_000L

        /** What the monitor watches at until told otherwise; matches the style schema's default. */
        const val DEFAULT_INTERVAL_MILLIS = 10 * 60_000L

        /**
         * About 50 Hz, which the LSM6DSO serves at its 52 Hz rate.
         *
         * It looks like four times more than a 3 Hz band needs, and dropping it to the chip's 13 Hz
         * minimum was tried. It is wrong, and [MotionFilterTest] says why: sampling at 13 Hz puts
         * Nyquist at 6.5 Hz, and engine noise at 12 and 14 Hz — two of the four frequencies the
         * filter is required to reject — folds to **1 Hz**, dead centre of the arm-movement band.
         * A folded rotor is indistinguishable from a wrist, and the failure direction is a monitor
         * that reports a sleeping operator as awake.
         *
         * The rule the rate has to satisfy is therefore not Nyquist for the *band* but Nyquist for
         * the *noise*: whatever must be rejected has to be resolved by the band-pass that was
         * measured, rather than by the sensor's own anti-alias filter, which cannot be inspected
         * from here and cannot be tested. Rejecting up to 20 Hz needs better than 40 Hz.
         *
         * The battery was never in the sample rate anyway. It was in the wake lock.
         */
        const val SENSOR_PERIOD_MICROS = 20_000

        /**
         * Twenty seconds of report latency while armed, which is where the saving actually is: one
         * hand-over instead of a thousand interrupts.
         *
         * 50 Hz for 20 s is about 1000 events. The FIFO holds 2016 and guarantees this client only
         * 200 of them, so the figure has to stay well clear of the ceiling — two other clients are
         * already on this accelerometer. Overflow costs samples, and a lost batch is read as
         * missing evidence rather than as stillness, so the cost is a nudge that need not have been
         * sent.
         */
        private const val BATCH_LATENCY_MICROS = 20_000_000

        /**
         * Ceiling on how long the wake lock may be held. Longer than the answer window and any
         * plausible alarm, short enough that a leak is a nuisance rather than a flat battery.
         */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L
    }
}
