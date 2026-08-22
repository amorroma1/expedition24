// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.geo

import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A single search result. Reused across queries so the resolver never allocates in a loop. */
class PoiHit {
    @JvmField
    val code: CharArray = CharArray(PoiFormat.CODE_BYTES)

    @JvmField
    var codeLength: Int = 0

    @JvmField
    var type: Int = 0

    @JvmField
    var flags: Int = 0

    @JvmField
    var distanceMeters: Double = 0.0
}

/**
 * Read-only view over the packed `poi_v1.bin` asset: world airports, ports and spaceports indexed
 * by a 32-bit Morton key.
 *
 * ### Why the query looks the way it does
 * A Z-order curve keeps nearby points near each other in key space, but it is not continuous — two
 * cells that touch on the map can sit far apart in the key ordering. Scanning "a few hundred
 * records either side of my own key" is the obvious approach and it silently misses sites across a
 * quadrant boundary, which near a city edge is exactly where the interesting ones are.
 *
 * So [queryNearest] decomposes the search box into one Morton range per row of cells. Within a row
 * the latitude bits are fixed, which makes the key monotonic in longitude, so
 * `[morton(xMin, y) .. morton(xMax, y)]` is a genuine bound. Each row costs one binary search plus
 * a very short scan (the ranges hold a few hundred keys and the database has ~10^4 records spread
 * over 2^32 keys), and a 5 km box is around 33 rows. Total cost is tens of microseconds, which is
 * why this runs off the render thread only for freshness, not for speed.
 */
class PoiDatabase private constructor(
    private val buffer: ByteBuffer,
    private val recordsOffset: Int,
    val count: Int,
    private val buckets: IntArray,
) {

    private fun keyAt(index: Int): Int =
        buffer.getInt(recordsOffset + index * PoiFormat.RECORD_BYTES + PoiFormat.OFFSET_MORTON)

    private fun latAt(index: Int): Float =
        buffer.getFloat(recordsOffset + index * PoiFormat.RECORD_BYTES + PoiFormat.OFFSET_LAT)

    private fun lonAt(index: Int): Float =
        buffer.getFloat(recordsOffset + index * PoiFormat.RECORD_BYTES + PoiFormat.OFFSET_LON)

    private fun typeAt(index: Int): Int =
        buffer.get(recordsOffset + index * PoiFormat.RECORD_BYTES + PoiFormat.OFFSET_TYPE).toInt() and 0xFF

    private fun flagsAt(index: Int): Int =
        buffer.get(recordsOffset + index * PoiFormat.RECORD_BYTES + PoiFormat.OFFSET_FLAGS).toInt() and 0xFF

    private fun readCode(index: Int, out: CharArray): Int {
        val base = recordsOffset + index * PoiFormat.RECORD_BYTES + PoiFormat.OFFSET_CODE
        var n = 0
        while (n < PoiFormat.CODE_BYTES) {
            val b = buffer.get(base + n).toInt() and 0xFF
            if (b == 0) break
            out[n] = b.toChar()
            n++
        }
        return n
    }

    /**
     * Finds the most interesting site within [radiusMeters] of the given position and writes it
     * into [out].
     *
     * Ties are broken by rank first (spaceport > airfield > port > helipad) and by distance
     * second, so standing at Baikonur reports the cosmodrome rather than its airstrip.
     *
     * @return true if a site was found.
     */
    fun queryNearest(lat: Double, lon: Double, radiusMeters: Double, out: PoiHit): Boolean {
        if (count == 0) return false

        val dLat = Geo.latDeltaDegrees(radiusMeters)
        val dLon = Geo.lonDeltaDegrees(lat, radiusMeters)

        val yMin = Morton.quantizeLat(lat - dLat)
        val yMax = Morton.quantizeLat(lat + dLat)

        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE
        var bestPriority = -1

        // A box straddling the antimeridian becomes two spans in cell space.
        val lonLo = lon - dLon
        val lonHi = lon + dLon
        val spans: Int
        val xLo0: Int
        val xHi0: Int
        val xLo1: Int
        val xHi1: Int
        if (lonLo < -180.0 || lonHi > 180.0) {
            spans = 2
            xLo0 = Morton.quantizeLon(if (lonLo < -180.0) lonLo + 360.0 else lonLo)
            xHi0 = Morton.GRID - 1
            xLo1 = 0
            xHi1 = Morton.quantizeLon(if (lonHi > 180.0) lonHi - 360.0 else lonHi)
        } else {
            spans = 1
            xLo0 = Morton.quantizeLon(lonLo)
            xHi0 = Morton.quantizeLon(lonHi)
            xLo1 = 0
            xHi1 = -1
        }

        var y = yMin
        while (y <= yMax) {
            var span = 0
            while (span < spans) {
                val x0 = if (span == 0) xLo0 else xLo1
                val x1 = if (span == 0) xHi0 else xHi1
                if (x1 >= x0) {
                    val lo = Morton.encode(x0, y)
                    val hi = Morton.encode(x1, y)
                    var i = lowerBound(lo)
                    while (i < count && Morton.compareKeys(keyAt(i), hi) <= 0) {
                        val key = keyAt(i)
                        // The key range for a row also contains keys from other rows and columns.
                        if (Morton.decodeY(key) == y) {
                            val cellX = Morton.decodeX(key)
                            if (cellX in x0..x1) {
                                val d = Geo.haversineMeters(
                                    lat, lon, latAt(i).toDouble(), lonAt(i).toDouble()
                                )
                                if (d <= radiusMeters) {
                                    val priority = PoiFormat.priorityOf(typeAt(i), flagsAt(i))
                                    if (priority > bestPriority ||
                                        (priority == bestPriority && d < bestDistance)
                                    ) {
                                        bestPriority = priority
                                        bestDistance = d
                                        bestIndex = i
                                    }
                                }
                            }
                        }
                        i++
                    }
                }
                span++
            }
            y++
        }

        if (bestIndex < 0) return false
        out.codeLength = readCode(bestIndex, out.code)
        out.type = typeAt(bestIndex)
        out.flags = flagsAt(bestIndex)
        out.distanceMeters = bestDistance
        return true
    }

    /** Index of the first record whose key is >= [key], using the bucket table to seed the search. */
    private fun lowerBound(key: Int): Int {
        val bucket = Morton.bucketOf(key)
        var low = buckets[bucket]
        var high = if (bucket + 1 < PoiFormat.BUCKET_COUNT) buckets[bucket + 1] else count
        if (high < low) high = count
        while (low < high) {
            val mid = (low + high) ushr 1
            if (Morton.compareKeys(keyAt(mid), key) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        private const val TAG = "PoiDatabase"

        /** Loads the packed asset, or returns null if it is missing or malformed. */
        fun open(context: Context): PoiDatabase? {
            val bytes = try {
                context.assets.open(PoiFormat.ASSET_NAME).use { it.readBytes() }
            } catch (e: IOException) {
                Log.w(TAG, "POI asset unavailable", e)
                return null
            }
            return fromBytes(bytes)
        }

        /** Validates and wraps an in-memory image of the format. Returns null if it is malformed. */
        fun fromBytes(bytes: ByteArray): PoiDatabase? {
            if (bytes.size < PoiFormat.HEADER_BYTES) {
                Log.w(TAG, "POI asset truncated: ${bytes.size} bytes")
                return null
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.getInt(0) != PoiFormat.MAGIC) {
                Log.w(TAG, "POI asset magic mismatch")
                return null
            }
            val version = buffer.getShort(4).toInt() and 0xFFFF
            if (version != PoiFormat.VERSION) {
                Log.w(TAG, "POI asset version $version, expected ${PoiFormat.VERSION}")
                return null
            }
            val count = buffer.getInt(8)
            val expected = PoiFormat.HEADER_BYTES + count * PoiFormat.RECORD_BYTES
            if (count < 0 || bytes.size < expected) {
                Log.w(TAG, "POI asset declares $count records but holds ${bytes.size} bytes")
                return null
            }

            val buckets = IntArray(PoiFormat.BUCKET_COUNT)
            for (i in 0 until PoiFormat.BUCKET_COUNT) {
                buckets[i] = buffer.getInt(12 + i * 4)
            }
            return PoiDatabase(buffer, PoiFormat.HEADER_BYTES, count, buckets)
        }
    }
}
