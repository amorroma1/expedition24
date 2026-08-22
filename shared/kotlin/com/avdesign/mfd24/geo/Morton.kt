// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.geo

/**
 * 32-bit Morton (Z-order) key over a 65536 x 65536 equirectangular grid.
 *
 * Cell size is 180/65536 deg of latitude (~305 m) and 360/65536 deg of longitude (~611 m at the
 * equator, less towards the poles) — fine enough that a 5 km query touches only a couple of dozen
 * rows of cells.
 *
 * Bit layout: longitude occupies the even bits, latitude the odd ones. That puts the four high
 * bits of each axis in the top byte of the key, which is what the 256-entry bucket table indexes.
 *
 * Keys are unsigned; compare them with [compareKeys], never with `<`.
 */
object Morton {

    const val GRID: Int = 65536

    fun quantizeLon(lon: Double): Int {
        val v = ((lon + 180.0) / 360.0 * GRID).toInt()
        return if (v < 0) 0 else if (v >= GRID) GRID - 1 else v
    }

    fun quantizeLat(lat: Double): Int {
        val v = ((lat + 90.0) / 180.0 * GRID).toInt()
        return if (v < 0) 0 else if (v >= GRID) GRID - 1 else v
    }

    /** Interleaves [x] (longitude cell, even bits) and [y] (latitude cell, odd bits). */
    fun encode(x: Int, y: Int): Int = spread(x) or (spread(y) shl 1)

    fun decodeX(key: Int): Int = compact(key)

    fun decodeY(key: Int): Int = compact(key ushr 1)

    fun bucketOf(key: Int): Int = key ushr 24

    /** Unsigned comparison — Morton keys routinely have the sign bit set. */
    fun compareKeys(a: Int, b: Int): Int = java.lang.Integer.compareUnsigned(a, b)

    private fun spread(v: Int): Int {
        var x = v and 0xFFFF
        x = (x or (x shl 8)) and 0x00FF00FF
        x = (x or (x shl 4)) and 0x0F0F0F0F
        x = (x or (x shl 2)) and 0x33333333
        x = (x or (x shl 1)) and 0x55555555
        return x
    }

    private fun compact(v: Int): Int {
        var x = v and 0x55555555
        x = (x or (x ushr 1)) and 0x33333333
        x = (x or (x ushr 2)) and 0x0F0F0F0F
        x = (x or (x ushr 4)) and 0x00FF00FF
        x = (x or (x ushr 8)) and 0x0000FFFF
        return x
    }
}
