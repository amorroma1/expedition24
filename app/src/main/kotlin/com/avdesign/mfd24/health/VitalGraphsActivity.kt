// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.avdesign.mfd24.R
import com.avdesign.mfd24.data.VitalStore

/**
 * The day's three records, one under another: pulse, activity, sleep.
 *
 * The rings on the dial are a shape to glance at; a graph is a thing to read, and reading wants
 * a straight line and a screen. Opened from its own row in the settings rather than by a tap on
 * the face, because this is where somebody goes deliberately — the face's own double tap belongs
 * to the report, which answers "how was today" in one number and three sentences.
 *
 * Plain views, like the report and the export before it: three panels drawn once, with nothing
 * to interact with but a scroll.
 */
class VitalGraphsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = VitalStore(this)
        val nowMillis = System.currentTimeMillis()
        val offset = java.util.TimeZone.getDefault().getOffset(nowMillis)
        val todayEpochDay = DayBins.localEpochDay(nowMillis, offset)
        val currentBin = DayBins.binIndex(nowMillis, offset)

        val todayHr = ByteArray(DayBins.BIN_COUNT)
        val todaySteps = ShortArray(DayBins.BIN_COUNT)
        val todayFlags = ByteArray(DayBins.BIN_COUNT)
        DayLogCodec.unpack(store.today(), todayHr, todaySteps, todayFlags)

        // Sleep is inferred rather than read: the night under way has not been through a day
        // close, so its bins carry no flag yet, and a graph that waited for one would be empty
        // every morning — which is exactly when somebody looks at it.
        val pairHr = ByteArray(DayBins.BIN_COUNT * 2)
        val pairSteps = ShortArray(DayBins.BIN_COUNT * 2)
        val pairFlags = ByteArray(DayBins.BIN_COUNT * 2)
        DayLogCodec.unpack(store.yesterday(), pairHr, pairSteps, pairFlags)
        System.arraycopy(todayHr, 0, pairHr, DayBins.BIN_COUNT, DayBins.BIN_COUNT)
        System.arraycopy(todaySteps, 0, pairSteps, DayBins.BIN_COUNT, DayBins.BIN_COUNT)
        System.arraycopy(todayFlags, 0, pairFlags, DayBins.BIN_COUNT, DayBins.BIN_COUNT)
        val resting = SleepModel.restingBpm(pairHr, pairFlags, DayBins.BIN_COUNT * 2)
        val sleep = SleepResult()
        val session = IntArray(2)
        store.sleepSessionBins(todayEpochDay, offset, session)
        SleepModel.infer(
            pairHr, pairSteps, pairFlags, DayBins.BIN_COUNT * 2, resting, sleep,
            allowOffBody = store.sleepOffBody(),
            sessionFrom = session[0], sessionTo = session[1],
        )
        for (r in 0 until sleep.runCount) {
            for (b in sleep.startBin[r] until sleep.endBin[r]) {
                pairFlags[b] = (pairFlags[b].toInt() or DayBins.FLAG_SLEEP).toByte()
            }
        }

        // The graphs show the same twenty-four hours the rings do: today up to the hand, then
        // yesterday's bins ahead of it.
        //
        // They used to show midnight to midnight of today alone, and that cut the night in half.
        // Somebody who went to bed at nine opened the graphs in the morning and found their sleep
        // and their evening pulse missing — the two things they had opened the graphs to see. A
        // face that answers "how was last night" has to hold last night, and the dial already
        // solved that; the screen behind it should not solve it differently.
        val hr = ByteArray(DayBins.BIN_COUNT)
        val steps = ShortArray(DayBins.BIN_COUNT)
        val flags = ByteArray(DayBins.BIN_COUNT)
        for (i in 0 until DayBins.BIN_COUNT) {
            val fromToday = i <= currentBin
            val src = if (fromToday) DayBins.BIN_COUNT + i else i
            hr[i] = pairHr[src]
            steps[i] = pairSteps[src]
            flags[i] = pairFlags[src]
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            val pad = dp(26f)
            setPadding(pad, dp(30f), pad, dp(34f))
        }

        var any = false
        for (i in 0 until DayBins.BIN_COUNT) {
            if (flags[i].toInt() and DayBins.FLAG_SAMPLED != 0) {
                any = true
                break
            }
        }
        if (!any) {
            column.addView(text(getString(R.string.report_no_data), 13f))
            setContentView(scroll(column))
            return
        }

        val midnightUp = store.midnightUp()
        val as24 = store.midnightAs24()
        // The hour of the local day, for the hands. Read once: this screen is drawn and left
        // alone, and a hand that crept while somebody scrolled would be a clock nobody asked for.
        val nowHours = ((nowMillis + offset) % DayBins.DAY_MILLIS) / 3_600_000.0
        panel(column, R.string.graphs_pulse, DayGraphView.KIND_PULSE, hr, steps, flags, resting,
            midnightUp, as24, nowHours, currentBin)
        panel(column, R.string.graphs_activity, DayGraphView.KIND_ACTIVITY, hr, steps, flags,
            resting, midnightUp, as24, nowHours, currentBin)
        panel(column, R.string.graphs_sleep, DayGraphView.KIND_SLEEP, hr, steps, flags, resting,
            midnightUp, as24, nowHours, currentBin)
        column.addView(text(getString(R.string.graphs_axis), 10f))
        setContentView(scroll(column))
    }

    private fun panel(
        column: LinearLayout,
        titleRes: Int,
        kind: Int,
        hr: ByteArray,
        steps: ShortArray,
        flags: ByteArray,
        resting: Int,
        midnightUp: Boolean,
        midnightAs24: Boolean,
        nowHours: Double,
        nowBin: Int,
    ) {
        column.addView(text(getString(titleRes), 11f))
        column.addView(
            DayRingGraphView(
                this, kind, hr, steps, flags, resting, midnightUp, midnightAs24, nowHours, nowBin,
            ).apply {
                // Square, and as wide as the screen allows: a round graph reads by radius, and
                // a squashed circle would put the same pulse at two distances.
                layoutParams = LinearLayout.LayoutParams(panelSize, panelSize).apply {
                    bottomMargin = dp(10f)
                }
            }
        )
    }

    /** The side of one round panel: the screen's width, less the column's own padding. */
    private val panelSize: Int
        get() = resources.displayMetrics.widthPixels - dp(52f)

    private fun scroll(child: ViewGroup): ViewGroup = ScrollView(this).apply {
        setBackgroundColor(Color.BLACK)
        isFillViewport = true
        addView(
            child,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun text(value: String, sizeSp: Float) = TextView(this).apply {
        text = value
        setTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(2f), 0, dp(3f))
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    ).toInt()

    private companion object {
        val DIM = 0xFFA8A49C.toInt()
    }
}
