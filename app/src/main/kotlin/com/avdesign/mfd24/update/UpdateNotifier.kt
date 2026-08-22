// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.avdesign.mfd24.BuildConfig
import com.avdesign.mfd24.R
import com.avdesign.mfd24.data.WatchShiftController
import com.avdesign.mfd24.data.WatchShiftState

/**
 * The once-per-release nudge.
 *
 * Rides the telemetry worker's half-hourly run but asks GitHub at most once a day — an update
 * check is not weather, and 48 identical requests a day is how a free API starts rate-limiting a
 * user-agent.
 *
 * **It reads, and that is all it does.** No APK is fetched here. Spending somebody's connection
 * and somebody's flash on three megabytes they have not agreed to is not a favour, and an update
 * that has quietly staged itself invites the tap that installs it without the thought that should
 * come first. The download happens on the install screen, after a deliberate tap.
 *
 * **It does not run during a watch.** A shift under way is the one time this app must be boring:
 * an update is a decision to make afterwards, not something to be nudged about while standing a
 * watch, and an install replaces the process a dead-man's monitor is living in.
 *
 * **It does not install, and neither does anything else here.** Wear OS 3 does not let an app
 * install an app, so the whole feature is: notice, and point. What it leaves behind is a pending
 * version in [UpdateStore], which the ABOUT chip names, plus exactly one notification per release —
 * keyed on the version, not on time, so no release is announced twice and no day re-announces an
 * ignored one. Tapping it shows the release page as a QR ([ReleaseLinkActivity]). If
 * `POST_NOTIFICATIONS` was declined the platform drops it silently; ABOUT still names the release,
 * so nothing is lost but the nudge.
 */
object UpdateNotifier {

    /** Blocking; call from the telemetry worker's dispatcher. */
    fun checkAndNotify(context: Context, nowMillis: Long) {
        // Switched off in ABOUT: no request, no reminder, nothing. The only unsolicited network
        // call this app makes is also the only one that can be refused outright.
        if (!UpdateStore.checkEnabled(context)) return
        // Not while a watch is under way. Deliberately checked first, so a watch does not even
        // spend the daily slot — the check happens once the wearer is off duty and can act on it.
        if (WatchShiftController.get(context).state.dutyState(nowMillis) ==
            WatchShiftState.DUTY_ACTIVE
        ) {
            return
        }
        if (nowMillis - UpdateStore.lastCheckedAt(context) < CHECK_INTERVAL_MILLIS) return
        UpdateStore.noteChecked(context, nowMillis)

        val release = ReleaseCheck.latest() ?: return
        if (!ReleaseCheck.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            // Up to date: clear the offer, so ABOUT stops naming a release that is now installed.
            UpdateStore.notePending(context, null)
            return
        }
        // Recorded, not fetched: the version alone, which is all the ABOUT row needs to say a
        // release exists. The notes are read on the update screen, which re-reads them anyway.
        UpdateStore.notePending(context, release.version)

        if (UpdateStore.notifiedVersion(context) == release.version) return
        UpdateStore.noteNotified(context, release.version)
        notify(context, release.version)
        Log.i(TAG, "release ${release.version} announced")
    }

    private fun notify(context: Context, version: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ReleaseLinkActivity::class.java)
                .putExtra(ReleaseLinkActivity.EXTRA_VERSION, version),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.update_notification_title, version))
                .setContentText(context.getString(R.string.update_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private const val TAG = "ReleaseCheck"
    private const val CHANNEL_ID = "mfd24_updates"
    private const val NOTIFICATION_ID = 240
    private const val CHECK_INTERVAL_MILLIS = 24L * 3_600_000L
}
