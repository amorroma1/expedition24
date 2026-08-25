// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log

/**
 * The two things on the dial that come from elsewhere on the watch: the next alarm, and whether
 * the calendar has anything to say about an hour.
 *
 * Both are drawn on the daylight band because both are *when* rather than *what*, and the band
 * is the face's own answer to when. Neither is an agenda: the alarm is one mark because the
 * platform only offers one, and the calendar marks say an hour is spoken for, not by whom or how
 * often. A wearer who wants the detail opens the calendar; what a dial can honestly add is the
 * shape of the day at a glance.
 *
 * ### What the platform actually gives
 * **The next alarm and nothing more.** `getNextAlarmClock` returns the single soonest alarm for
 * the user, with no permission and no cost; the full list lives inside whichever clock app set
 * it and is not exposed to anybody. So the face draws the next one and says nothing about the
 * rest — and drops it entirely once it has fired, because a mark for an alarm that has already
 * gone off is a mark that lies about the future.
 *
 * **The calendar, with permission.** `CalendarContract.Instances` answers for a window, which is
 * exactly the query a 24-hour dial wants. Only what has actually synced to the watch is there —
 * usually whatever the phone's calendar app has pushed across — and an empty answer is
 * indistinguishable from an empty day, which is why the row that turns this on says so.
 */
object DayMarks {

    private const val TAG = "DayMarks"

    /** As many spans as a dial can carry before marks stop being distinguishable. */
    const val MAX_EVENTS = 24

    /** Below this an event is a point on the dial rather than a span; drawn as a minimum arc. */
    const val MIN_EVENT_MILLIS = 10 * 60_000L

    /**
     * The next alarm, or zero when there is none — or when the one on file has already fired,
     * which the platform leaves in place for a while and the dial must not.
     */
    fun nextAlarmMillis(context: Context, nowMillis: Long): Long {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return 0L
        val info = runCatching { manager.nextAlarmClock }.getOrNull() ?: return 0L
        val at = info.triggerTime
        if (at <= nowMillis) return 0L
        // Past a day ahead a mark on a 24-hour dial says something false: it would sit at the
        // same angle as an alarm set for tonight. The same rule the booked duty arc keeps.
        if (at - nowMillis > 24 * 3_600_000L) return 0L
        return at
    }

    fun hasCalendarPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Fills [outStart] and [outEnd] with the timed events between [fromMillis] and [toMillis],
     * clipped to that window, and returns how many were written.
     *
     * All-day events are left out on purpose: they cover the whole dial, so drawing one would
     * paint every hour as spoken for and tell the wearer nothing. Blocking — call it from the
     * background, like every other query in this package.
     */
    fun calendarEvents(
        context: Context,
        fromMillis: Long,
        toMillis: Long,
        outStart: LongArray,
        outEnd: LongArray,
    ): Int {
        if (!hasCalendarPermission(context)) return 0
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMillis.toString())
            .appendPath(toMillis.toString())
            .build()
        var cursor: Cursor? = null
        var count = 0
        try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                ),
                null,
                null,
                CalendarContract.Instances.BEGIN + " ASC",
            ) ?: return 0
            while (cursor.moveToNext() && count < outStart.size) {
                if (cursor.getInt(2) != 0) continue
                val begin = cursor.getLong(0)
                val end = cursor.getLong(1)
                if (end <= fromMillis || begin >= toMillis) continue
                val clippedStart = maxOf(begin, fromMillis)
                val clippedEnd = minOf(maxOf(end, clippedStart + MIN_EVENT_MILLIS), toMillis)
                if (clippedEnd <= clippedStart) continue
                outStart[count] = clippedStart
                outEnd[count] = clippedEnd
                count++
            }
        } catch (e: SecurityException) {
            // The grant can be taken back from the system settings between one query and the
            // next; a face must not die of it.
            Log.i(TAG, "calendar refused", e)
            return 0
        } catch (e: RuntimeException) {
            Log.i(TAG, "calendar query failed", e)
            return 0
        } finally {
            cursor?.close()
        }
        return count
    }
}
