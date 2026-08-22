// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/**
 * The important test in this file is [range_query_agrees_with_brute_force].
 *
 * A Z-order curve is not continuous: two cells that touch on the map can be far apart in key order.
 * The naive "binary search my own key, then scan a few hundred records either side" misses sites
 * across a quadrant boundary, and it does so quietly — the watch face just shows nothing. Checking
 * the range decomposition against an exhaustive scan over the same points is the only way to know
 * the query is actually correct, including at those boundaries.
 */
class PoiDatabaseTest {

    private class Point(
        val code: String,
        val lat: Double,
        val lon: Double,
        val type: Int,
        val flags: Int = 0,
    ) {
        val key: Int = Morton.encode(Morton.quantizeLon(lon), Morton.quantizeLat(lat))
    }

    @Test
    fun `rejects a corrupt image`() {
        assertNull(PoiDatabase.fromBytes(ByteArray(10)))
        val badMagic = ByteArray(PoiFormat.HEADER_BYTES)
        assertNull(PoiDatabase.fromBytes(badMagic))
    }

    @Test
    fun `reads back what was written`() {
        val points = listOf(
            Point("SVO", 55.972642, 37.414589, PoiFormat.TYPE_AIRPORT),
            Point("BAIK", 45.965, 63.305, PoiFormat.TYPE_SPACEPORT),
            Point("NLROT", 51.95, 4.14, PoiFormat.TYPE_PORT),
        )
        val db = PoiDatabase.fromBytes(pack(points))
        assertNotNull(db)
        assertEquals(3, db!!.count)

        val hit = PoiHit()
        assertTrue(db.queryNearest(55.9726, 37.4146, 5_000.0, hit))
        assertEquals("SVO", String(hit.code, 0, hit.codeLength))
        assertEquals(PoiFormat.TYPE_AIRPORT, hit.type)
        assertTrue("distance was ${hit.distanceMeters}", hit.distanceMeters < 100.0)
    }

    @Test
    fun `a helipad does not mask the facility it sits next to`() {
        // Toulon: the naval base is half a kilometre away, the naval air station's helipad four
        // and a half. Ranking every aerodrome above every port reported the helipad, which is how
        // this rule earned its test.
        val points = listOf(
            Point("TOULON", 43.11681, 5.90741, PoiFormat.TYPE_PORT, PoiFormat.FLAG_MILITARY),
            Point(
                "LFTR", 43.09722, 6.14583, PoiFormat.TYPE_AIRPORT,
                PoiFormat.FLAG_HELIPAD or PoiFormat.FLAG_MILITARY,
            ),
        )
        val db = PoiDatabase.fromBytes(pack(points))!!
        val hit = PoiHit()

        assertTrue(db.queryNearest(43.12, 5.91, 25_000.0, hit))
        assertEquals("TOULON", String(hit.code, 0, hit.codeLength))

        // Standing on the helipad itself, with the port out of range, it is still reported.
        assertTrue(db.queryNearest(43.0972, 6.1458, 5_000.0, hit))
        assertEquals("LFTR", String(hit.code, 0, hit.codeLength))
    }

    @Test
    fun `rank order runs spaceport, airfield, port, helipad`() {
        fun rank(type: Int, flags: Int = 0) = PoiFormat.priorityOf(type, flags)
        assertTrue(rank(PoiFormat.TYPE_SPACEPORT) > rank(PoiFormat.TYPE_AIRPORT))
        assertTrue(rank(PoiFormat.TYPE_AIRPORT) > rank(PoiFormat.TYPE_PORT))
        assertTrue(
            rank(PoiFormat.TYPE_PORT) >
                rank(PoiFormat.TYPE_AIRPORT, PoiFormat.FLAG_HELIPAD)
        )
        // Who owns a place does not change how significant it is.
        assertEquals(
            rank(PoiFormat.TYPE_PORT),
            rank(PoiFormat.TYPE_PORT, PoiFormat.FLAG_MILITARY),
        )
    }

    @Test
    fun `reports nothing when the nearest site is outside the radius`() {
        val db = PoiDatabase.fromBytes(
            pack(listOf(Point("SVO", 55.972642, 37.414589, PoiFormat.TYPE_AIRPORT)))
        )!!
        val hit = PoiHit()
        // Red Square is about 29 km from Sheremetyevo.
        assertFalse(db.queryNearest(55.7539, 37.6208, 5_000.0, hit))
    }

    @Test
    fun `a spaceport outranks a closer airfield`() {
        val db = PoiDatabase.fromBytes(
            pack(
                listOf(
                    // Baikonur's own airstrip is nearer to the query point than the pad.
                    Point("BXY", 45.9700, 63.2100, PoiFormat.TYPE_AIRPORT),
                    Point("BAIK", 45.9650, 63.3050, PoiFormat.TYPE_SPACEPORT),
                )
            )
        )!!
        val hit = PoiHit()
        assertTrue(db.queryNearest(45.968, 63.220, 10_000.0, hit))
        assertEquals("BAIK", String(hit.code, 0, hit.codeLength))
    }

    @Test
    fun `range query agrees with brute force`() {
        val random = Random(1234)
        val points = ArrayList<Point>(6_000)

        // A global scatter, so quadrant boundaries of the Z-curve are crossed all over the place.
        repeat(4_000) { i ->
            points.add(
                Point(
                    "G$i".take(6),
                    random.nextDouble(-85.0, 85.0),
                    random.nextDouble(-180.0, 180.0),
                    random.nextInt(3),
                )
            )
        }
        // Dense clusters, so queries actually find something more often than not.
        val clusterCentres = List(40) {
            random.nextDouble(-70.0, 70.0) to random.nextDouble(-179.0, 179.0)
        }
        clusterCentres.forEachIndexed { c, (lat, lon) ->
            repeat(50) { i ->
                points.add(
                    Point(
                        "C$c$i".take(6),
                        (lat + random.nextDouble(-0.09, 0.09)).coerceIn(-89.0, 89.0),
                        wrapLon(lon + random.nextDouble(-0.09, 0.09)),
                        random.nextInt(3),
                    )
                )
            }
        }

        val db = PoiDatabase.fromBytes(pack(points))!!
        assertEquals(points.size, db.count)

        val hit = PoiHit()
        var found = 0
        repeat(600) { q ->
            val (lat, lon) = if (q % 2 == 0) {
                val (cLat, cLon) = clusterCentres[random.nextInt(clusterCentres.size)]
                (cLat + random.nextDouble(-0.1, 0.1)).coerceIn(-89.0, 89.0) to
                    wrapLon(cLon + random.nextDouble(-0.1, 0.1))
            } else {
                random.nextDouble(-85.0, 85.0) to random.nextDouble(-180.0, 180.0)
            }
            val radius = 5_000.0

            val expected = bruteForce(points, lat, lon, radius)
            val actual = if (db.queryNearest(lat, lon, radius, hit)) {
                String(hit.code, 0, hit.codeLength)
            } else {
                null
            }

            assertEquals(
                "query $q at ($lat, $lon)",
                expected?.code,
                actual,
            )
            if (expected != null) {
                found++
                assertEquals(
                    expected.type,
                    hit.type,
                )
                assertEquals(
                    distanceTo(expected, lat, lon),
                    hit.distanceMeters,
                    1e-6,
                )
            }
        }
        assertTrue("only $found queries found a site; the fixture is not exercising hits", found > 100)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun wrapLon(lon: Double): Double = when {
        lon > 180.0 -> lon - 360.0
        lon < -180.0 -> lon + 360.0
        else -> lon
    }

    /** Distances use the float-rounded coordinates the file actually stores. */
    private fun distanceTo(point: Point, lat: Double, lon: Double): Double =
        Geo.haversineMeters(lat, lon, point.lat.toFloat().toDouble(), point.lon.toFloat().toDouble())

    private fun bruteForce(points: List<Point>, lat: Double, lon: Double, radius: Double): Point? {
        var best: Point? = null
        var bestDistance = Double.MAX_VALUE
        var bestPriority = -1
        for (p in points) {
            val d = distanceTo(p, lat, lon)
            if (d > radius) continue
            val priority = PoiFormat.priorityOf(p.type, p.flags)
            // A tie on both priority and distance would make "the" answer ambiguous, and the two
            // implementations visit the points in different orders. Random fixtures never tie, so
            // fail loudly rather than compare something meaningless.
            check(!(priority == bestPriority && abs(d - bestDistance) < 1e-9)) {
                "ambiguous fixture: two sites at the same priority and distance"
            }
            if (priority > bestPriority || (priority == bestPriority && d < bestDistance)) {
                bestPriority = priority
                bestDistance = d
                best = p
            }
        }
        return best
    }

    /** Writes the same layout as `:tools:poi`, independently, so a format slip shows up here. */
    private fun pack(points: List<Point>): ByteArray {
        val sorted = points.sortedWith { a, b -> Morton.compareKeys(a.key, b.key) }
        val size = PoiFormat.HEADER_BYTES + sorted.size * PoiFormat.RECORD_BYTES
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(PoiFormat.MAGIC)
        buffer.putShort(PoiFormat.VERSION.toShort())
        buffer.putShort(0)
        buffer.putInt(sorted.size)

        var index = 0
        for (bucket in 0 until PoiFormat.BUCKET_COUNT) {
            while (index < sorted.size && Morton.bucketOf(sorted[index].key) < bucket) index++
            buffer.putInt(index)
        }

        for (p in sorted) {
            buffer.putInt(p.key)
            buffer.putFloat(p.lat.toFloat())
            buffer.putFloat(p.lon.toFloat())
            buffer.put(p.type.toByte())
            buffer.put(p.flags.toByte())
            val bytes = p.code.toByteArray(Charsets.US_ASCII)
            for (i in 0 until PoiFormat.CODE_BYTES) {
                buffer.put(if (i < bytes.size) bytes[i] else 0)
            }
        }
        return buffer.array()
    }
}
