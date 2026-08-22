// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.avdesign.mfd24.data.WatchShiftController

/**
 * Marks the boundaries of a watch: sounds the signal when a booked shift comes due, and again when
 * it runs out.
 *
 * It sits at the package root rather than under `data/` because it is declared in the manifest, and
 * keeping manifest components together makes the ProGuard keep-rules obvious.
 */
class WatchShiftReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = WatchShiftController.get(context)
        when (intent.action) {
            ACTION_STARTED -> controller.onStarted()
            ACTION_EXPIRED -> controller.onExpired()
        }
    }

    companion object {
        const val ACTION_STARTED: String = "com.avdesign.mfd24.action.WATCH_STARTED"
        const val ACTION_EXPIRED: String = "com.avdesign.mfd24.action.WATCH_EXPIRED"
    }
}
