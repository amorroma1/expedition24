// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan
import kotlin.math.tan

/** One fixed-step elevation table: when each sample was taken and how high the target stood. */
class HorizonsTable(maxRows: Int = 1024) {

    @JvmField
    val timesMillis = LongArray(maxRows)

    @JvmField
    val elevationDeg = DoubleArray(maxRows)

    @JvmField
    var count: Int = 0
}

/**
 * The relay orbiters' ephemerides, from JPL Horizons — the one thing on this face no local
 * arithmetic can produce: MRO and Odyssey fly maintained orbits, corrected from the ground, so
 * their future positions are a download, not a formula. The API needs no key and answers a
 * fixed-step observer table; elevations are parsed numerically and thresholded by the caller,
 * so what "visible" means lives in exactly one constant shared with the direct-to-Earth line.
 *
 * Two conventions the endpoint enforces, both paid for during development: Mars site longitude
 * goes in **west-positive** (an east-positive value is refused with a one-line error), and
 * `SITE_COORD` with `COORD_TYPE=GEODETIC` wants the **planetographic** latitude, so the rovers'
 * planetocentric values are converted at the door.
 *
 * Strictly a data source, never trusted blindly: any malformed, truncated or non-monotonic
 * table is rejected whole. A corrupt payload that became comm windows would be worse than the
 * `NO EPHEMERIS` notice its rejection produces.
 */
object HorizonsClient {

    /** A table was read, whole. */
    const val OK: Int = 0

    /** Network down, HTTP error, or a body no table could be read from. Worth retrying soon. */
    const val FAILED: Int = 1

    /**
     * Horizons answered plainly that it holds no trajectory for these dates — MAVEN's published
     * ephemeris simply ends, met live on 2026-08-23 ("No ephemeris for target ... after
     * 2026-MAR-01"). Not a failure to retry into: the spacecraft contributes nothing until JPL
     * publishes more, and hammering the API will not make it.
     */
    const val NO_COVERAGE: Int = 2

    private const val TAG = "HorizonsClient"
    private const val ENDPOINT = "https://ssd.jpl.nasa.gov/api/horizons.api"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Mars flattening, for the planetocentric-to-planetographic latitude conversion. */
    private const val MARS_FLATTENING = 0.005886

    /** Sampling pitch: a low pass lasts minutes, and edges are interpolated between samples. */
    private const val STEP = "2m"

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /**
     * Blocking; call from a background dispatcher. Fills [out] with the elevation table of
     * [command] (a Horizons spacecraft id, e.g. `-74` for MRO) as seen from the site, over
     * [startMillis]..[stopMillis] UTC. Answers [OK], [FAILED] or [NO_COVERAGE]; on anything
     * but [OK] the caller keeps its cache.
     */
    fun fetch(
        command: String,
        lonEastDeg: Double,
        latDeg: Double,
        startMillis: Long,
        stopMillis: Long,
        out: HorizonsTable,
    ): Int {
        val graphicLat = Math.toDegrees(
            atan(tan(Math.toRadians(latDeg)) / ((1.0 - MARS_FLATTENING) * (1.0 - MARS_FLATTENING)))
        )
        val site = "%.4f,%.4f,0".format(java.util.Locale.US, -lonEastDeg, graphicLat)
        val url = ENDPOINT +
            "?format=text" +
            "&COMMAND=" + quote(command) +
            "&OBJ_DATA=" + quote("NO") +
            "&MAKE_EPHEM=" + quote("YES") +
            "&EPHEM_TYPE=" + quote("OBSERVER") +
            "&CENTER=" + quote("coord@499") +
            "&COORD_TYPE=" + quote("GEODETIC") +
            "&SITE_COORD=" + quote(site) +
            "&START_TIME=" + quote(utcMinute(startMillis)) +
            "&STOP_TIME=" + quote(utcMinute(stopMillis)) +
            "&STEP_SIZE=" + quote(STEP) +
            "&QUANTITIES=" + quote("4") +
            "&ANG_FORMAT=" + quote("DEG") +
            "&CSV_FORMAT=" + quote("YES") +
            "&TIME_DIGITS=" + quote("MINUTES")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty(
                    "User-Agent", "MFD-24 watch face (github.com/amorroma1/expedition24)"
                )
            }
            if (connection.responseCode !in 200..299) {
                Log.i(TAG, "horizons returned HTTP ${connection.responseCode} for $command")
                return FAILED
            }
            parse(connection.inputStream.bufferedReader().use { it.readText() }, out)
        } catch (e: IOException) {
            Log.i(TAG, "horizons fetch failed for $command", e)
            FAILED
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Reads the `$$SOE`..`$$EOE` CSV block into [out]. Timestamps are Horizons' fixed
     * `yyyy-MMM-dd HH:mm` with English month abbreviations, parsed against a twelve-entry table
     * rather than a locale-sensitive formatter. Rejects — whole, never partially — a body with
     * no data block, an unparseable row, or timestamps that fail to increase; a body that
     * *says* it has no trajectory for the asked dates is [NO_COVERAGE], a different answer
     * from a body that could not be read.
     */
    internal fun parse(body: String, out: HorizonsTable): Int {
        out.count = 0
        val start = body.indexOf("\$\$SOE")
        val end = body.indexOf("\$\$EOE")
        if (start < 0 || end < 0 || end <= start) {
            if (body.contains("No ephemeris for target")) {
                Log.i(TAG, "horizons holds no trajectory for these dates")
                return NO_COVERAGE
            }
            Log.i(TAG, "horizons payload carries no data block")
            return FAILED
        }
        var previous = Long.MIN_VALUE
        for (line in body.substring(start + 5, end).lineSequence()) {
            val row = line.trim()
            if (row.isEmpty()) continue
            if (out.count >= out.timesMillis.size) {
                Log.i(TAG, "horizons table overflows ${out.timesMillis.size} rows")
                return FAILED
            }
            // timestamp, solar-presence flag, marker, azimuth, elevation[, trailing empty]
            val fields = row.split(',')
            if (fields.size < 5) return failRow(row)
            val at = parseTimestamp(fields[0].trim()) ?: return failRow(row)
            val elevation = fields[4].trim().toDoubleOrNull() ?: return failRow(row)
            if (at <= previous) return failRow(row)
            previous = at
            out.timesMillis[out.count] = at
            out.elevationDeg[out.count] = elevation
            out.count++
        }
        if (out.count == 0) {
            Log.i(TAG, "horizons data block is empty")
            return FAILED
        }
        return OK
    }

    private fun failRow(row: String): Int {
        Log.i(TAG, "horizons row refused: $row")
        return FAILED
    }

    /** `2026-Aug-23 03:56` to epoch millis, UTC — fixed widths, no locale in sight. */
    private fun parseTimestamp(text: String): Long? {
        if (text.length != 17 || text[4] != '-' || text[8] != '-' ||
            text[11] != ' ' || text[14] != ':'
        ) {
            return null
        }
        val year = text.substring(0, 4).toIntOrNull() ?: return null
        val month = MONTHS.indexOf(text.substring(5, 8)) + 1
        if (month == 0) return null
        val day = text.substring(9, 11).toIntOrNull() ?: return null
        val hour = text.substring(12, 14).toIntOrNull() ?: return null
        val minute = text.substring(15, 17).toIntOrNull() ?: return null
        return java.time.LocalDateTime.of(year, month, day, hour, minute)
            .toEpochSecond(java.time.ZoneOffset.UTC) * 1000L
    }

    private fun utcMinute(epochMillis: Long): String {
        val t = java.time.Instant.ofEpochMilli(epochMillis).atOffset(java.time.ZoneOffset.UTC)
        return "%04d-%02d-%02d %02d:%02d".format(
            java.util.Locale.US, t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute,
        )
    }

    private fun quote(value: String): String = URLEncoder.encode("'$value'", "UTF-8")
}
