// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** One observation, already scaled into the integer units the renderer works in. */
data class WeatherSample(
    val temperatureDeciC: Int,
    val pressureDeciHpa: Int,
    val conditionIndex: Int,
    val observedAt: Long,
)

/**
 * Weather from Open-Meteo.
 *
 * Chosen because it needs no API key and no account, which keeps the watch face installable
 * straight from a build. `pressure_msl` is mean-sea-level pressure, which is QNH by definition — no
 * conversion is needed for the altimeter setting shown on the dial.
 *
 * Parsed with the platform `org.json` rather than a serialisation library: the payload is three
 * numbers and pulling in Moshi or kotlinx-serialization for it would be pure weight.
 */
object OpenMeteoClient {


    /** No figure for this hour. */
    const val NO_VALUE = -1

    private const val TAG = "OpenMeteoClient"
    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Blocking; call from a background dispatcher. Returns null on any failure. */
    fun fetch(latitude: Double, longitude: Double, nowMillis: Long): WeatherSample? {
        val url = String.format(
            Locale.US,
            "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,pressure_msl",
            ENDPOINT, latitude, longitude,
        )

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "Open-Meteo returned HTTP $status")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body, nowMillis)
        } catch (e: IOException) {
            Log.w(TAG, "Weather fetch failed", e)
            null
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "Malformed weather payload", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parse(body: String, nowMillis: Long): WeatherSample? {
        val current = JSONObject(body).optJSONObject("current") ?: return null
        if (!current.has("temperature_2m")) return null
        val temperature = current.getDouble("temperature_2m")
        val pressure = current.optDouble("pressure_msl", Double.NaN)
        val code = current.optInt("weather_code", -1)
        return WeatherSample(
            temperatureDeciC = (temperature * 10.0).roundToInt(),
            pressureDeciHpa = if (pressure.isNaN()) 0 else (pressure * 10.0).roundToInt(),
            conditionIndex = if (code < 0) WeatherCondition.UNKNOWN else WeatherCondition.fromWmoCode(code),
            observedAt = nowMillis,
        )
    }
}
