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
 * The short beep-and-buzz that marks the start and the end of a watch.
 *
 * Both are best-effort: a watch may have no speaker, the user may be in theatre mode, and neither
 * failure is worth crashing a watch face over — hence the blanket [runCatching].
 */
object Alerts {

    private const val TAG = "Alerts"
    private const val TONE_MILLIS = 180
    private const val VIBRATION_MILLIS = 180L

    /** Long enough for a powered-down speaker path to come up, short enough to read as immediate. */
    private const val WARM_UP_MILLIS = 120L

    /**
     * @param amplitude 1..255, or [VibrationEffect.DEFAULT_AMPLITUDE] to leave it to the platform.
     *   The vigilance nudge passes the user's VIBE STRENGTH; the watch boundary chimes do not,
     *   because that setting is about being woken, not about being told the time.
     */
    @JvmOverloads
    fun signal(context: Context, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        beep()
        vibrate(context, amplitude)
    }

    /**
     * The generator is built once and kept, rather than made and released around each beep.
     *
     * A `ToneGenerator` owns an `AudioTrack`, and on this watch the speaker path is powered down
     * when nothing has used it. Constructing one and calling `startTone` in the same breath asks a
     * codec that is still waking up to play a 180 ms tone, and the tone is gone before it is ready —
     * which is exactly what a vigilance nudge does, since it fires after minutes of silence with
     * the screen off. The vibration was felt and the beep was not.
     *
     * Keeping it costs one idle `AudioTrack`. Releasing it cost the alert.
     */
    @Volatile
    private var tone: ToneGenerator? = null

    private fun beep() {
        runCatching {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
                .also { tone = it }
            // Still give the path a moment on the very first use of the process.
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    runCatching { generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MILLIS) }
                        .onFailure { Log.w(TAG, "startTone refused", it) }
                },
                WARM_UP_MILLIS,
            )
        }.onFailure { Log.w(TAG, "Could not sound the watch tone", it) }
    }

    private fun vibrate(context: Context, amplitude: Int) {
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MILLIS, amplitude))
        }.onFailure { Log.w(TAG, "Could not vibrate", it) }
    }

    private const val TONE_VOLUME = 90

    /** VIBE STRENGTH, as vibrator amplitudes. Low is felt on a bare wrist, high through a sleeve. */
    const val AMPLITUDE_LOW = 90
    const val AMPLITUDE_MED = 170
    const val AMPLITUDE_HIGH = 255

    /**
     * SOS SOUND, as `ToneGenerator` volumes.
     *
     * A separate setting from VIBE STRENGTH because they address different people: the buzz is for
     * the wearer, the tone is for whoever has to find them. `OFF` is a real option — a watch on a
     * quiet ward or a covert task cannot beep, and the wrist and the record still work.
     *
     * These do not ramp. Volume that starts low to be polite is volume that is missed, and the one
     * thing that can silence a perfectly good generator is the alarm stream being turned down —
     * which `SosSignal` logs rather than overriding.
     */
    const val SOS_VOLUME_OFF = 0
    const val SOS_VOLUME_LOW = 40
    const val SOS_VOLUME_MED = 70
    const val SOS_VOLUME_HIGH = 100
}
