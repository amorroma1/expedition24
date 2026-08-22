// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.clearPassiveListenerCallback
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The day's step count, taken from the watch rather than counted here.
 *
 * The raw `TYPE_STEP_COUNTER` sensor counts from the last reboot, which is not a figure anybody
 * wants, so the old path subtracted the counter's value at the day's start — a baseline this app
 * had to observe for itself. That works only for a day this app was present for the whole of. The
 * case it gets wrong is the first one every new wearer meets: walk four thousand steps, install the
 * face, and the row says `0`. The number is defensible and completely useless, and it disagrees
 * with every other step count on the watch.
 *
 * Health Services keeps the figure the rest of the device agrees with — `DAILY_STEPS`, reset by the
 * platform at local midnight, accumulated whether or not this app was installed. That is what the
 * row should say, so that is what it now says.
 *
 * ### Why it is still optional
 * Not every build has the service, and a watch face that shows nothing because a Google component
 * is missing is a watch face that is broken on exactly the hardware most likely to be running a
 * third-party dial. So this reports whether it could subscribe, and [SensorSlots] keeps its
 * counter-and-baseline path for when it could not. One number, two ways of getting it, and the
 * better one first.
 *
 * ### What it costs
 * A passive subscription is the platform's own aggregation — the sensor is already running for the
 * system's step count — so this adds no hardware. It is registered while the STEPS slot is on and
 * the screen is awake, and dropped with it, like every other reading here.
 */
class DailySteps(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private var callback: PassiveListenerCallback? = null

    /**
     * Subscribes, and reports whether the platform accepted.
     *
     * False means the caller should fall back: no Health Services on this build, or a client that
     * refused. The value arrives asynchronously — a passive listener delivers on the platform's
     * own schedule, so the row shows dashes until the first update lands rather than a zero that
     * would be read as a step count.
     */
    fun start(onSteps: (Int) -> Unit): Boolean {
        if (callback != null) return true
        return try {
            val client = HealthServices.getClient(appContext).passiveMonitoringClient
            val listener = object : PassiveListenerCallback {
                override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                    val steps = dataPoints.getData(DataType.STEPS_DAILY).lastOrNull() ?: return
                    val value = steps.value
                    // Logged as well as used: whether this watch's provider sends the day's total
                    // or something else is the one fact that decides whether the platform path is
                    // worth having at all, and it cannot be read off the dial.
                    Log.i(TAG, "daily steps from the platform: $value")
                    if (value in 0..MAX_PLAUSIBLE) onSteps(value.toInt())
                }
            }
            // Asked, and logged, because a provider that does not offer the daily total will
            // simply never call back — silence is indistinguishable from "no steps yet" from
            // inside the callback, and one of those is a reason to stop asking.
            scope.launch {
                runCatching {
                    val supported = client.getCapabilities()
                        .supportedDataTypesPassiveMonitoring
                    Log.i(
                        TAG,
                        "STEPS_DAILY supported: " + supported.contains(DataType.STEPS_DAILY) +
                            "; provider offers " + supported.size + " passive types",
                    )
                }.onFailure { Log.w(TAG, "could not read Health Services capabilities", it) }
            }
            client.setPassiveListenerCallback(
                PassiveListenerConfig.builder()
                    .setDataTypes(setOf(DataType.STEPS_DAILY))
                    .build(),
                listener,
            )
            callback = listener
            true
        } catch (t: Throwable) {
            // Throwable, and not merely Exception, because of one that actually happened: with
            // R8 on, proto-lite's reflective field lookup inside the client's static initializer
            // failed and threw ExceptionInInitializerError — an Error. `catch (Exception)` let it
            // through and the whole watch face died the moment the steps slot was switched on.
            // The keep rules in proguard-rules.pro fix the cause; this catches the class of
            // failure. An optional row's provider must never be able to take the dial down, and
            // there is a working fallback one line below.
            Log.w(TAG, "no daily steps from Health Services; falling back to the counter", t)
            false
        }
    }

    fun stop() {
        if (callback == null) return
        callback = null
        // The suspending overload, so nothing here has to name a ListenableFuture — the Guava
        // type the async API returns is not on this module's classpath and does not need to be.
        scope.launch {
            // runCatching catches Throwable, which is what is wanted here for the reason given in
            // start(): unsubscribing must not be able to crash the face either.
            runCatching {
                HealthServices.getClient(appContext)
                    .passiveMonitoringClient
                    .clearPassiveListenerCallback()
            }.onFailure { Log.w(TAG, "could not unsubscribe from daily steps", it) }
        }
    }

    private companion object {
        const val TAG = "DailySteps"

        /** A day of walking is tens of thousands; anything past this is not a step count. */
        const val MAX_PLAUSIBLE = 200_000L
    }
}
