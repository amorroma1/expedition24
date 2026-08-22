// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import com.avdesign.mfd24.R
import com.avdesign.mfd24.data.VigilanceMonitor

/**
 * The log's two ways off the watch, on one screen: a QR code for the camera in front of you and a
 * Bell 202 burst for the microphone beside it. Both start together — the QR is up while the tones
 * play, so one gesture serves whichever receiver the debrief happens to have.
 *
 * The payload is one [LogPacket] for both channels, checksum included, so the photograph and the
 * recording can never disagree. A tap replays the burst — repositioning the microphone wants a
 * fresh transmission, and the QR loses nothing by staying put. Leaving the screen stops the sound.
 *
 * Read-only by construction: this activity takes the log from the monitor's state and writes
 * nowhere.
 */
class LogExportActivity : Activity() {

    private val transmitter = LogTransmitter()
    private lateinit var view: QrPanelView

    /** Whether the burst is still sounding; drawn as the caption under the code. */
    private var sounding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // ANDROID_ID is per-app and per-signing-key from API 26: stable across reinstalls of a
        // release build, reset by a factory reset. Exactly the lifetime a callsign should have.
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "UNIDENTIFIED"
        val callsign = Callsign.of(deviceId)
        val vigilanceState = VigilanceMonitor.get(this).state
        val packet = LogPacket.build(
            vigilanceState.incidents,
            callsign,
            Callsign.shortId(deviceId),
            vigilanceState.logShiftStartMillis,
            vigilanceState.logShiftEndMillis,
        )
        // Whose log this is, said on the screen as well as inside the code: the person holding
        // the camera reads the screen, not the payload.
        view = QrPanelView(
            context = this,
            payload = packet,
            topCaption = callsign,
            bottomCaption = {
                getString(if (sounding) R.string.export_sounding else R.string.export_idle)
            },
            onTap = { transmit(packet) },
        )
        setContentView(view)
        transmit(packet)
    }

    private fun transmit(packet: String) {
        sounding = true
        view.invalidate()
        transmitter.transmit(packet) {
            sounding = false
            view.postInvalidate()
        }
    }

    override fun onPause() {
        transmitter.stop()
        sounding = false
        super.onPause()
    }
}
