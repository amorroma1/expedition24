// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.avdesign.mfd24.update.UpdateNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The Mars flavor's periodic work: keep the relay ephemerides ahead of the clock, and carry the
 * daily release check that rides [TelemetryWorker] on Earth — that worker is never scheduled on
 * this flavor, its whole pipeline being location, site and weather.
 *
 * Six-hourly where the weather is half-hourly, because orbits are not weather: each fetch
 * covers thirty hours, refetch triggers with six still on file, and between runs the windows on
 * the dial are absolute instants that need no refreshing. A run that finds every cache fresh
 * downloads nothing.
 */
class MarsEphemerisWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = MarsCommRepository.get(applicationContext)
        val now = System.currentTimeMillis()
        // Same rule as the Earth worker: a failed check must never fail the fetch it rode with.
        runCatching { UpdateNotifier.checkAndNotify(applicationContext, now) }
        repository.refreshLocal(now)
        val fetched = withContext(Dispatchers.IO) { repository.refreshRelay(now) }
        // Retry only while something is actually missing: with coverage on file the next
        // scheduled run is soon enough, and a retry chain against a down API buys nothing.
        return if (fetched || repository.state.relayValid) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "mfd24-ephemeris"
        private const val EXPEDITED_NAME = "mfd24-ephemeris-now"
        private const val INTERVAL_HOURS = 6L

        /**
         * One immediate run, for the moments a person is watching: a rover switch or a relay
         * toggle empties or reshapes the line, and the six-hourly schedule is the wrong clock
         * to answer an editor on. REPLACE, because only the latest ask matters.
         */
        fun fetchNow(context: Context) {
            if (!UserManagerCompat.isUserUnlocked(context)) return
            val request = OneTimeWorkRequestBuilder<MarsEphemerisWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                EXPEDITED_NAME, ExistingWorkPolicy.REPLACE, request,
            )
        }

        /**
         * Enqueues the periodic fetch; same unlock guard as [TelemetryWorker], because
         * WorkManager's database lives in credential-encrypted storage.
         */
        fun scheduleIfUnlocked(context: Context): Boolean {
            if (!UserManagerCompat.isUserUnlocked(context)) return false
            val request = PeriodicWorkRequestBuilder<MarsEphemerisWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
            return true
        }
    }
}
