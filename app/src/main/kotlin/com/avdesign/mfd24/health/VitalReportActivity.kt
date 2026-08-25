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
import com.avdesign.mfd24.style.Palette
import java.util.TimeZone

/**
 * The day, read back: a score, the two or three things worth changing tomorrow, and the figures
 * they were drawn from.
 *
 * Plain views rather than Compose, the same choice the incident log's export and the release
 * link make: this is one screen, rendered once, with nothing to interact with but a scroll. The
 * editor earns Compose because it is a long list of live controls; a page of text does not.
 *
 * Everything on it is computed here from what is on file — 192 bins of integer arithmetic, well
 * under a frame's worth of work — so the screen cannot disagree with the dial behind it, and
 * nothing has to be kept warm between openings.
 */
class VitalReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = VitalStore(this)
        val hr = ByteArray(DayBins.BIN_COUNT * 2)
        val steps = ShortArray(DayBins.BIN_COUNT * 2)
        val flags = ByteArray(DayBins.BIN_COUNT * 2)

        // Yesterday first, then today: the pair is what makes a night that crossed midnight one
        // run rather than two halves.
        val yesterdayHr = ByteArray(DayBins.BIN_COUNT)
        val yesterdaySteps = ShortArray(DayBins.BIN_COUNT)
        val yesterdayFlags = ByteArray(DayBins.BIN_COUNT)
        DayLogCodec.unpack(store.yesterday(), yesterdayHr, yesterdaySteps, yesterdayFlags)
        System.arraycopy(yesterdayHr, 0, hr, 0, DayBins.BIN_COUNT)
        System.arraycopy(yesterdaySteps, 0, steps, 0, DayBins.BIN_COUNT)
        System.arraycopy(yesterdayFlags, 0, flags, 0, DayBins.BIN_COUNT)

        val todayHr = ByteArray(DayBins.BIN_COUNT)
        val todaySteps = ShortArray(DayBins.BIN_COUNT)
        val todayFlags = ByteArray(DayBins.BIN_COUNT)
        val today = DayLogCodec.unpack(store.today(), todayHr, todaySteps, todayFlags)
        System.arraycopy(todayHr, 0, hr, DayBins.BIN_COUNT, DayBins.BIN_COUNT)
        System.arraycopy(todaySteps, 0, steps, DayBins.BIN_COUNT, DayBins.BIN_COUNT)
        System.arraycopy(todayFlags, 0, flags, DayBins.BIN_COUNT, DayBins.BIN_COUNT)

        val pairCount = DayBins.BIN_COUNT * 2
        val resting = SleepModel.restingBpm(hr, flags, pairCount)
        val sleep = SleepResult()
        SleepModel.infer(
            hr, steps, flags, pairCount, resting, sleep, allowOffBody = store.sleepOffBody(),
        )

        var stepsToday = 0
        var sampled = false
        for (i in 0 until DayBins.BIN_COUNT) {
            if (todayFlags[i].toInt() and DayBins.FLAG_SAMPLED != 0) sampled = true
            stepsToday += todaySteps[i].toInt()
        }

        val history = store.summaryDays()
        val summary = DaySummary(
            epochDay = today,
            steps = stepsToday,
            restingBpm = resting,
            sleepMinutes = sleep.nightMinutes,
            wakeEpisodes = sleep.wakeEpisodes,
            hrHighSharePct = sedentaryHighSharePct(
                todayHr, todaySteps, todayFlags, sedentaryBaseline(history, resting),
            ),
            sleepOnsetMinutes = sleep.onsetMinutes,
        )
        val baselineSteps = DaySummaries.baselineSteps(history)

        setContentView(build(summary, history, baselineSteps, sampled, sleep, todayHr, resting))
    }

    private fun build(
        day: DaySummary,
        history: Array<DaySummary>,
        baselineSteps: Int,
        sampled: Boolean,
        sleep: SleepResult,
        todayHr: ByteArray,
        restingBpm: Int,
    ): ViewGroup {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            // A round screen clips its corners, so the text column is inset well past what a
            // rectangle would need: a line that reaches the glass is a line with its ends cut off.
            val pad = dp(30f)
            setPadding(pad, dp(34f), pad, dp(34f))
        }

        // Nothing has been recorded yet: say so plainly rather than scoring an empty day.
        if (!sampled) {
            column.addView(text(getString(R.string.report_no_data), 13f, Palette.ALERT_AMBER))
            return scroll(column)
        }

        column.addView(text(getString(R.string.report_title), 11f, DIM))
        column.addView(
            text(
                DayAnalysis.score(day, baselineSteps).toString(),
                40f,
                Palette.ALERT_AMBER,
                bold = true,
            )
        )

        val advice = arrayOfNulls<Recommendation>(DayAnalysis.MAX_RECOMMENDATIONS)
        val count = DayAnalysis.recommend(day, history, baselineSteps, advice)
        if (count == 0) {
            column.addView(text(getString(R.string.report_nothing_to_change), 13f, TEXT))
        } else {
            for (i in 0 until count) {
                column.addView(text("• " + getString(stringFor(advice[i]!!)), 13f, TEXT))
            }
        }

        column.addView(spacer())
        column.addView(
            text(getString(R.string.report_steps, day.steps), 13f, DIM),
        )
        column.addView(
            text(
                if (day.restingBpm > DayBins.NO_BPM) {
                    getString(R.string.report_resting, day.restingBpm)
                } else {
                    getString(R.string.report_resting_unknown)
                },
                13f, DIM,
            )
        )
        column.addView(
            text(
                if (sleep.nightMinutes > 0) {
                    getString(
                        R.string.report_sleep,
                        sleep.nightMinutes / 60, sleep.nightMinutes % 60, day.wakeEpisodes,
                    )
                } else {
                    getString(R.string.report_sleep_unknown)
                },
                13f, DIM,
            )
        )

        // The day's pulse in full, which is the detail the rings are deliberately too coarse to
        // carry: four steps of colour say what kind of day it was, and this says what happened
        // in it.
        column.addView(spacer())
        column.addView(text(getString(R.string.report_pulse_graph), 11f, DIM))
        column.addView(
            PulseGraphView(this, todayHr, restingBpm).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(66f),
                )
            }
        )

        column.addView(spacer())
        column.addView(text(getString(R.string.report_disclaimer), 10f, FAINT))
        return scroll(column)
    }

    private fun stringFor(code: Recommendation): Int = when (code) {
        Recommendation.MORE_STEPS -> R.string.advice_more_steps
        Recommendation.HIGH_HR -> R.string.advice_high_hr
        Recommendation.HIGH_HR_PERSISTENT -> R.string.advice_high_hr_persistent
        Recommendation.SLEEP_MORE -> R.string.advice_sleep_more
        Recommendation.SLEEP_EARLIER -> R.string.advice_sleep_earlier
    }

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

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            gravity = Gravity.CENTER
            typeface = Typeface.create(
                Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
            setPadding(0, dp(3f), 0, dp(3f))
        }

    private fun spacer() = TextView(this).apply {
        height = dp(12f)
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    ).toInt()

    private companion object {
        val TEXT = 0xFFE8E4DC.toInt()
        val DIM = 0xFFA8A49C.toInt()
        val FAINT = 0xFF6E6A64.toInt()

        /**
         * The pulse a sitting quarter-hour is measured against: the wearer's own sitting norm
         * over the fortnight, and today's resting rate only until there is one.
         */
        fun sedentaryBaseline(history: Array<DaySummary>, todayResting: Int): Int {
            val baseline = DaySummaries.baselineRestingBpm(history)
            return if (baseline > DayBins.NO_BPM) baseline else todayResting
        }

        /**
         * The share of the day's still, worn, awake quarter-hours whose pulse ran well above
         * that norm — the figure the caffeine advice is drawn from. Zero when there is no norm
         * to measure against, because a share computed from nothing is a number that would be
         * believed.
         */
        fun sedentaryHighSharePct(
            hr: ByteArray,
            steps: ShortArray,
            flags: ByteArray,
            baselineBpm: Int,
        ): Int {
            if (baselineBpm <= DayBins.NO_BPM) return 0
            var still = 0
            var high = 0
            for (i in 0 until DayBins.BIN_COUNT) {
                val f = flags[i].toInt() and 0xFF
                if (!DayBins.isStillAwake(f)) continue
                val bpm = hr[i].toInt() and 0xFF
                if (bpm <= DayBins.NO_BPM) continue
                still++
                if (bpm > baselineBpm + DayAnalysis.SEDENTARY_EXCESS_BPM) high++
            }
            if (still == 0) return 0
            return high * 100 / still
        }
    }
}
