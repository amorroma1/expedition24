// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.avdesign.mfd24.update.UpdateNotifier
import java.util.concurrent.TimeUnit

/**
 * Periodic refresh of position, nearest site and weather.
 *
 * Runs every 30 minutes and only when a network is available — the brief asks for minimal
 * background work, and a watch face that keeps the radio or the GPS busy is a battery bug, not a
 * feature. Everything the renderer needs is cached, so a missed run costs nothing but freshness.
 */
class TelemetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = TelemetryRepository.get(applicationContext)
        // The active fix is for the scheduled attempt only. A retry means the last attempt already
        // found nothing, and each active attempt runs the receiver for up to 45 seconds — a backoff
        // chain of those is a GPS left on indoors for a fix that is not coming. Retries settle for
        // whatever is cached; the next half-hourly run asks properly again.
        val active = runAttemptCount == 0
        // Piggybacks on the run the radio is already up for; gated to once a day inside, and a
        // failed check must never fail the weather it rode in on.
        runCatching { UpdateNotifier.checkAndNotify(applicationContext, System.currentTimeMillis()) }
        repository.refreshMarks(System.currentTimeMillis())
        return when (repository.refresh(allowActiveFix = active, nowMillis = System.currentTimeMillis())) {
            RefreshOutcome.OK -> Result.success()
            // Nothing to retry until the user grants access, and WorkManager would just burn slots.
            RefreshOutcome.NO_PERMISSION -> Result.success()
            RefreshOutcome.NO_FIX, RefreshOutcome.NETWORK_ERROR -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "mfd24-telemetry"
        private const val INTERVAL_MINUTES = 30L

        /**
         * Enqueues the periodic refresh.
         *
         * WorkManager keeps its database in credential-encrypted storage, so it cannot be touched
         * before the user unlocks the watch. The watch face is `directBootAware` and may well be
         * running by then, hence the guard — the service re-calls this on `ACTION_USER_UNLOCKED`.
         */
        fun scheduleIfUnlocked(context: Context): Boolean {
            if (!UserManagerCompat.isUserUnlocked(context)) return false

            val request = PeriodicWorkRequestBuilder<TelemetryWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            return true
        }
    }
}
