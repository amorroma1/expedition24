// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays a [LogPacket] through the speaker as [Afsk] tones.
 *
 * One transmission per press, a few seconds long, on the alarm stream — the same path the SOS
 * proved audible on this hardware. STATIC mode because the whole burst is at most a couple of
 * hundred kilobytes of PCM and exists before playback starts; a streaming path would be moving
 * parts for nothing.
 *
 * Transmit-only by design: receiving would need the microphone permission and a demodulator, and
 * the phone on the other side of the desk already has both in `minimodem --rx 1200`.
 */
class LogTransmitter {

    private var track: AudioTrack? = null

    /** Whether a burst is still sounding; the editor greys its chip on this. */
    val transmitting: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /**
     * Modulates [packet] and plays it. A press during a transmission restarts it — the operator
     * repositioning the microphone wants a fresh burst, not a queue.
     */
    fun transmit(packet: String, onDone: () -> Unit) {
        stop()
        val pcm = Afsk.modulate(packet)
        val fresh = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Afsk.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .apply {
                    write(pcm, 0, pcm.size)
                    // The marker fires on the last frame; that is the release and the callback.
                    notificationMarkerPosition = pcm.size
                    setPlaybackPositionUpdateListener(object :
                        AudioTrack.OnPlaybackPositionUpdateListener {
                        override fun onMarkerReached(t: AudioTrack) {
                            stop()
                            onDone()
                        }

                        override fun onPeriodicNotification(t: AudioTrack) = Unit
                    })
                    play()
                }
        }.onFailure {
            Log.w(TAG, "Could not transmit the log", it)
            onDone()
        }.getOrNull()
        track = fresh
    }

    fun stop() {
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
    }

    private companion object {
        const val TAG = "LogTransmitter"
    }
}
