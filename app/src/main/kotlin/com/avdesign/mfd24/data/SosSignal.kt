// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * The escalation: · · · — — — · · · on the wrist, then the same letters out loud, in bursts that
 * [SosSchedule] lays out.
 *
 * ### Felt first, then heard
 * The buzz and the tone used to run on one timing table, simultaneously — the buzz *was* the tone.
 * On a wrist that is the wrong way round twice over. A vibrator against a watch case drowns a small
 * speaker, so the tones were masked by the very beats they were riding; and the person who needs the
 * *sound* is not the wearer — it is whoever is in the room and has to find them. So a unit is now
 * **one vibration SOS, then the SOS twice in tone**: the wrist is told, then the room is told, and
 * neither has to compete with the other.
 *
 * ### Why the tone generator is built once and kept
 * The old code built a fresh `ToneGenerator` per cycle, because the volume is fixed at construction
 * and the volume used to climb. That is precisely the trap `Alerts` documents: on this watch the
 * speaker path is powered down when nothing has used it, so constructing a generator and calling
 * `startTone` in the same breath asks a codec that is still waking to play a 160 ms mark — and the
 * mark is gone before it is ready. Every cycle rebuilt it, so *every* cycle lost its opening marks,
 * which is why the SOS was felt and never heard. One generator per burst run, built at [start] and
 * given a warm-up before the first mark, fixes it; the level is the user's choice and no longer
 * needs to change mid-run.
 *
 * ### What the ramp does now
 * Vibration amplitude still climbs burst by burst towards the user's VIBE STRENGTH — whatever the
 * first burst failed to wake, the fourth might. Volume does not climb: it is a setting, and an
 * emergency signal that starts quiet is a signal that is missed. `OFF` means silent, for anyone
 * whose watch has to stay quiet; the wrist still buzzes.
 */
class SosSignal(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val vibrator = appContext.getSystemService(Vibrator::class.java)

    private var tone: ToneGenerator? = null
    private var burstIndex = 0
    private var running = false
    private var ceiling = Alerts.AMPLITUDE_HIGH
    private var volume = Alerts.SOS_VOLUME_MED
    private var onExhausted: (() -> Unit)? = null

    /**
     * @param maxAmplitude the user's VIBE STRENGTH, as the ceiling the ramp climbs towards.
     * @param toneVolume 0..100 from the user's SOS SOUND setting; zero is silent.
     * @param onExhausted run when the schedule is spent with no answer. The signal stops there
     *   rather than running until the battery is flat: nobody has come, and what the watch does
     *   next — hold the incident on the dial for whoever finds it — needs the power more.
     */
    fun start(
        maxAmplitude: Int = Alerts.AMPLITUDE_HIGH,
        toneVolume: Int = Alerts.SOS_VOLUME_MED,
        onExhausted: (() -> Unit)? = null,
    ) {
        if (running) return
        running = true
        ceiling = maxAmplitude.coerceIn(1, 255)
        volume = toneVolume.coerceIn(0, 100)
        this.onExhausted = onExhausted
        burstIndex = 0
        // Built now, played later: the first mark of the first burst is the one the speaker path
        // used to swallow, and it is also the only one that has any surprise left in it.
        if (volume > 0) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, volume) }
                .onFailure { Log.w(TAG, "No tone generator; the wrist will have to do", it) }
                .getOrNull()
            logStreamVolume()
        }
        playBurst()
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { vibrator?.cancel() }
        releaseTone()
        onExhausted = null
    }

    private fun playBurst() {
        if (!running) return

        val burst = SosSchedule.burst(burstIndex, UNIT_MILLIS)
        if (burst == null) {
            Log.w(
                TAG,
                "no answer after ${SosSchedule.BURSTS} bursts; going quiet and holding the incident",
            )
            val finished = onExhausted
            stop()
            finished?.invoke()
            return
        }
        val index = burstIndex
        burstIndex++

        val amplitude = (START_AMPLITUDE + index * AMPLITUDE_STEP).coerceAtMost(ceiling)
        repeat(burst.units) { unit -> scheduleUnit(unit * UNIT_MILLIS, amplitude) }

        val spent = burst.units * UNIT_MILLIS
        val next = SosSchedule.burst(burstIndex, UNIT_MILLIS)
        val delay = if (next == null) {
            spent
        } else {
            // Absolute offsets from the escalation, so a burst that overruns its slot cannot
            // push the whole schedule late.
            (next.startOffsetMillis - burst.startOffsetMillis - spent).coerceAtLeast(0L) + spent
        }
        handler.postDelayed({ playBurst() }, delay)
    }

    /** One unit: the letters on the wrist, then the letters twice in tone. */
    private fun scheduleUnit(atMillis: Long, amplitude: Int) {
        // One waveform for the whole letter group: the vibrator keeps its own time, so the beats
        // stay put even if the main looper is busy.
        handler.postDelayed({
            if (!running) return@postDelayed
            runCatching {
                val amplitudes = IntArray(PATTERN.size) { index ->
                    if (index % 2 == 1) amplitude else 0
                }
                vibrator?.vibrate(VibrationEffect.createWaveform(PATTERN, amplitudes, -1))
            }.onFailure { Log.w(TAG, "Could not vibrate the pattern", it) }
        }, atMillis)

        // Then the audible pair, clear of the buzz that would have masked it.
        if (volume <= 0) return
        for (repeat in 0 until TONE_REPEATS) {
            val base = atMillis + PATTERN_MILLIS + GAP_AFTER_BUZZ + repeat * (PATTERN_MILLIS + GAP)
            var offset = 0L
            for (index in PATTERN.indices) {
                val duration = PATTERN[index]
                if (index % 2 == 1) {
                    val at = base + offset
                    val length = duration.toInt()
                    handler.postDelayed({
                        if (running) runCatching { tone?.startTone(TONE, length) }
                    }, at)
                }
                offset += duration
            }
        }
    }

    /**
     * The one thing that can silence a working generator without any error: an alarm stream turned
     * down. Logged rather than overridden — the volume knob belongs to whoever is wearing the watch,
     * and a dead-man's monitor that raises the alarm volume behind their back is a monitor they
     * switch off.
     */
    private fun logStreamVolume() {
        runCatching {
            val audio = appContext.getSystemService(AudioManager::class.java) ?: return
            val level = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            Log.d(TAG, "alarm stream at $level/$max, tone volume $volume")
            if (level == 0) Log.w(TAG, "alarm stream is muted; the SOS will only be felt")
        }
    }

    private fun releaseTone() {
        val current = tone ?: return
        tone = null
        runCatching { current.release() }
    }

    private companion object {
        const val TAG = "SosSignal"

        /** A continuous tone, so it can be held for a dash. */
        const val TONE = ToneGenerator.TONE_SUP_RINGTONE

        const val DOT = 160L
        const val DASH = 480L
        const val GAP = 160L
        const val LETTER_GAP = 380L

        /**
         * Alternating off/on from index zero, so the odd entries are the marks: three dots, three
         * dashes, three dots.
         */
        val PATTERN = longArrayOf(
            0L,
            DOT, GAP, DOT, GAP, DOT, LETTER_GAP,
            DASH, GAP, DASH, GAP, DASH, LETTER_GAP,
            DOT, GAP, DOT, GAP, DOT,
        )

        /** The letter group's own length, 4120 ms — summed here so the two never disagree. */
        val PATTERN_MILLIS = PATTERN.sum()

        /** Long enough that the case has stopped ringing before the speaker starts. */
        const val GAP_AFTER_BUZZ = 200L

        /** Two audible SOS per unit: one alone reads as a notification. */
        const val TONE_REPEATS = SosSchedule.UNITS_PER_LATER_BURST

        /**
         * One unit, end to end: the buzz, then two tone repetitions, then a breath.
         *
         * Derived from the pattern rather than written down, because [SosSchedule] divides the
         * thirty-second opening burst by it.
         */
        val UNIT_MILLIS =
            PATTERN_MILLIS + GAP_AFTER_BUZZ + TONE_REPEATS * (PATTERN_MILLIS + GAP) + 400L

        const val START_AMPLITUDE = 140
        const val AMPLITUDE_STEP = 30
    }
}
