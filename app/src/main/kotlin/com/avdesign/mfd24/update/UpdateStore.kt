// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.update

import android.content.Context
import com.avdesign.mfd24.BuildConfig

/**
 * Three facts about releases, and no files.
 *
 * This used to manage downloads, a rollback copy and a "told once" banner — the machinery of an
 * in-app updater. All of it went when the platform's answer became clear: **Wear OS 3 does not let
 * an app install an app.** What is left is what a watch can honestly do, which is know that a
 * release exists and point at it.
 *
 *  - Whether it may ask at all ([checkEnabled]) — the only unprompted network call this app makes,
 *    so the only one that has to be refusable outright, in ABOUT.
 *  - What the last check found ([pendingVersion]), which is what the ABOUT chip names.
 *  - Which release has already been announced ([notifiedVersion]), so a release is announced once
 *    rather than once a day.
 *
 * No APK is ever downloaded, so there is nothing to prune, nothing staged, and no
 * `REQUEST_INSTALL_PACKAGES` in the manifest — a permission this app asked for, could not use, and
 * is better off without.
 */
object UpdateStore {

    /**
     * Whether the daily check may run.
     *
     * On by default and one tap from off. A watch that must not talk to GitHub should be able to
     * not talk to GitHub, and the answer belongs next to the version it is about.
     */
    fun checkEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHECK_ENABLED, true)

    fun setCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECK_ENABLED, enabled).apply()
        // Switching it off clears what the last check found: nothing should keep announcing a
        // release the wearer has just said they do not want to hear about.
        if (!enabled) notePending(context, null)
    }

    /**
     * The newest release on offer, or null.
     *
     * Filtered through [ReleaseCheck.offerable] rather than returned raw: a finding that is no
     * longer newer than the build reading it is stale, and a stale finding was advertising a
     * downgrade. Forgotten on the way out, so it stops being asked about.
     */
    fun pendingVersion(context: Context): String? {
        val stored = prefs(context).getString(KEY_PENDING, null)
        val offerable = ReleaseCheck.offerable(stored, BuildConfig.VERSION_NAME)
        if (offerable == null && stored != null) notePending(context, null)
        return offerable
    }

    fun notePending(context: Context, version: String?) {
        val editor = prefs(context).edit()
        if (version == null) editor.remove(KEY_PENDING) else editor.putString(KEY_PENDING, version)
        editor.apply()
    }

    /** The release version the wearer was last notified about, or null. */
    fun notifiedVersion(context: Context): String? =
        prefs(context).getString(KEY_NOTIFIED, null)

    fun noteNotified(context: Context, version: String) {
        prefs(context).edit().putString(KEY_NOTIFIED, version).apply()
    }

    /** When GitHub was last asked, epoch millis; 0 when never. */
    fun lastCheckedAt(context: Context): Long =
        prefs(context).getLong(KEY_CHECKED_AT, 0L)

    fun noteChecked(context: Context, nowMillis: Long) {
        prefs(context).edit().putLong(KEY_CHECKED_AT, nowMillis).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "mfd24_update"
    private const val KEY_CHECK_ENABLED = "check_enabled"
    private const val KEY_PENDING = "pending_version"
    private const val KEY_NOTIFIED = "notified_version"
    private const val KEY_CHECKED_AT = "checked_at"
}
