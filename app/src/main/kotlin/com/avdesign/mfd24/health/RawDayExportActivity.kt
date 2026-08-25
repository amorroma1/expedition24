// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import com.avdesign.mfd24.R
import com.avdesign.mfd24.data.VitalStore
import com.avdesign.mfd24.export.Callsign
import com.avdesign.mfd24.export.LogTransmitter
import com.avdesign.mfd24.export.QrPanelView

/**
 * The day's raw grid, off the watch through the two channels this hardware has: a QR code and
 * 1200-baud tones.
 *
 * The same idiom as the incident log's export, and deliberately so — a wearer who has scanned one
 * has learned both — but the payload is the opposite kind of thing. The incident packet is a
 * record for a person to read; this is [RawDayCodec]'s grid for a *program* to read, so that when
 * the sleep model gets a night wrong the argument can be settled against the numbers instead of
 * against a screenshot of a conclusion. `tools/vital/decode_day.py` turns a scanned line back into
 * a CSV.
 *
 * Two days, today and yesterday, because a night lives in both of them and a night is what goes
 * wrong. Both fit one code with room over; if a grid ever grows past the encoder's 858 bytes the
 * code cannot be built, so the packet falls back to today alone rather than failing on screen.
 */
class RawDayExportActivity : Activity() {

    private val transmitter = LogTransmitter()
    private lateinit var view: QrPanelView

    private var sounding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "UNIDENTIFIED"
        val callsign = Callsign.of(deviceId)
        val shortId = Callsign.shortId(deviceId)

        val store = VitalStore(this)
        val days = ArrayList<RawDayCodec.Day>(2)
        store.yesterday()?.let { readDay(it)?.let(days::add) }
        store.today()?.let { readDay(it)?.let(days::add) }

        val packet = when {
            days.isEmpty() -> RawDayCodec.packet(callsign, shortId, emptyList())
            else -> fitting(callsign, shortId, days)
        }

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

    /** The most days that still make a code: all of them, else the last one on its own. */
    private fun fitting(
        callsign: String,
        shortId: String,
        days: List<RawDayCodec.Day>,
    ): String {
        val whole = RawDayCodec.packet(callsign, shortId, days)
        if (whole.length <= QR_BYTE_CAPACITY) return whole
        return RawDayCodec.packet(callsign, shortId, listOf(days.last()))
    }

    private fun readDay(packed: String): RawDayCodec.Day? {
        val hr = ByteArray(DayBins.BIN_COUNT)
        val steps = ShortArray(DayBins.BIN_COUNT)
        val flags = ByteArray(DayBins.BIN_COUNT)
        val epochDay = DayLogCodec.unpack(packed, hr, steps, flags)
        if (epochDay <= 0L) return null
        return RawDayCodec.Day(epochDay.toInt(), hr, steps, flags)
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

    private companion object {
        /** What `QrCode` can carry at its own ceiling: version 20, level L. */
        const val QR_BYTE_CAPACITY = 858
    }
}
