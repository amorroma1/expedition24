// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.util.Log
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * The locked aerodrome's own METAR, from aviationweather.gov.
 *
 * When the site lock names an airfield, the most authoritative weather on the dial is that
 * field's own observation — the one its tower reads out — not a forecast model's cell that
 * happens to contain it. The API needs no key, answers in JSON so no METAR grammar lives here,
 * and the row's format does not change at all: it was drawn in METAR abbreviations from the
 * start, so this is the row becoming literally what it already looked like.
 *
 * Strictly a refinement, never a dependency: any miss — no METAR at that field, a stale one, a
 * null temperature, a closed feed — returns null and the caller falls back to Open-Meteo. That
 * fallback is not a corner case: most of the 9 649 sites never had a METAR, and whole regions
 * stop publishing in wartime, the user's own included.
 */
object MetarClient {

    private const val TAG = "MetarClient"
    private const val ENDPOINT = "https://aviationweather.gov/api/data/metar"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * A METAR is an hourly product; at 75 minutes it is one missed issue old and the model's
     * nowcast is the fresher truth. Also the gate against a field that stopped reporting years
     * ago but still answers with its last observation.
     */
    const val MAX_AGE_MILLIS: Long = 75 * 60_000L

    /** Blocking; call from a background dispatcher. Null on any failure — the caller falls back. */
    fun fetch(icao: String, nowMillis: Long): WeatherSample? {
        val url = "$ENDPOINT?ids=$icao&format=json"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty(
                    "User-Agent", "MFD-24 watch face (github.com/amorroma1/expedition24)"
                )
            }
            if (connection.responseCode !in 200..299) {
                Log.i(TAG, "aviationweather returned HTTP ${connection.responseCode} for $icao")
                return null
            }
            parse(connection.inputStream.bufferedReader().use { it.readText() }, nowMillis)
        } catch (e: IOException) {
            Log.i(TAG, "METAR fetch failed for $icao", e)
            null
        } catch (e: org.json.JSONException) {
            Log.i(TAG, "Malformed METAR payload for $icao", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parse(body: String, nowMillis: Long): WeatherSample? {
        val reports = JSONArray(body)
        if (reports.length() == 0) return null
        val metar = reports.getJSONObject(0)

        // The observation's own time gates freshness; the *cache* still ages from the fetch,
        // because a METAR is up to an hour old the moment it is issued and a cache keyed to that
        // would refetch on every screen-on for the back half of every hour.
        val observedMillis = metar.optLong("obsTime", 0L) * 1000L
        if (observedMillis <= 0L) return null
        if (nowMillis - observedMillis > MAX_AGE_MILLIS) return null

        // No temperature, no row: the temperature leads the line, and a METAR without one is a
        // special report about something else.
        val temp = metar.optDouble("temp", Double.NaN)
        if (temp.isNaN()) return null

        val altim = metar.optDouble("altim", Double.NaN)
        val pressureDeciHpa = if (!altim.isNaN() && altim in 850.0..1100.0) {
            (altim * 10.0).roundToInt()
        } else {
            0
        }

        return WeatherSample(
            temperatureDeciC = (temp * 10.0).roundToInt(),
            pressureDeciHpa = pressureDeciHpa,
            conditionIndex = WeatherCondition.fromMetar(
                metar.optString("wxString"), metar.optString("cover"),
            ),
            observedAt = nowMillis,
        )
    }
}
