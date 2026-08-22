// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import com.avdesign.mfd24.data.IncidentRecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-verifiable parts of the QR encoder. A camera is the end-to-end judge, but each of
 * these catches a class of bug a camera reports only as "no code found": Reed-Solomon arithmetic,
 * the two BCH words, version selection, and the function-pattern geometry every decoder locks
 * onto before it reads a single data bit.
 */
class QrCodeTest {

    // GF(2^8) helpers mirrored here so the test does not trust the code under test's own field.
    private fun gfMul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var p = 0
        while (y != 0) {
            if (y and 1 == 1) p = p xor x
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
            y = y shr 1
        }
        return p
    }

    @Test
    fun `data plus its ecc is divisible by every generator root`() {
        // The defining property of a Reed-Solomon codeword: evaluate the polynomial at 2^i for
        // each of the generator's roots and get zero. If this holds, a decoder's syndrome check
        // holds; if it does not, nothing else about the symbol matters.
        val data = intArrayOf(0x40, 0xD2, 0x75, 0x47, 0x76, 0x17, 0x32, 0x06, 0x27, 0x26)
        val degree = 10
        val ecc = QrCode.reedSolomon(data, degree)
        val poly = data + ecc
        var root = 1
        repeat(degree) {
            var value = 0
            for (coeff in poly) value = gfMul(value, root) xor coeff
            assertEquals("root 2^$it", 0, value)
            root = gfMul(root, 2)
        }
    }

    @Test
    fun `the format word is the published constant for level L mask 0`() {
        var rem = FORMAT_DATA
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        assertEquals(QrCode.FORMAT_L_MASK0, (FORMAT_DATA shl 10 or rem) xor 0x5412)
    }

    @Test
    fun `the version words match their BCH derivation`() {
        for (v in 7..QrCode.MAX_VERSION) {
            var rem = v
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            assertEquals("v$v", v shl 12 or rem, QrCode.VERSION_BITS[v - 7])
        }
    }

    @Test
    fun `version selection takes the smallest fit and refuses the unfittable`() {
        assertEquals(17, QrCode.byteCapacity(1))
        assertEquals(21, QrCode.encode("A".repeat(17)).size)              // v1 is 21 modules
        assertEquals(25, QrCode.encode("A".repeat(18)).size)              // one byte over: v2
        val tooBig = "A".repeat(QrCode.byteCapacity(QrCode.MAX_VERSION) + 1)
        assertTrue(runCatching { QrCode.encode(tooBig) }.isFailure)
    }

    @Test
    fun `a real packet produces the geometry every decoder locks onto`() {
        val matrix = QrCode.encode(
            LogPacket.build(arrayOf(IncidentRecord(1_787_230_037_000L)), "RAVEN-42", "10396EB9")
        )
        val n = matrix.size
        assertEquals("odd size", 1, n % 2)

        // The three finder centres, dark, inside their light ring.
        for ((r, c) in listOf(3 to 3, 3 to n - 4, n - 4 to 3)) {
            assertTrue(matrix[r][c])
            assertFalse(matrix[r - 2][c])
        }
        // Timing patterns alternate between the finders.
        for (i in 8 until n - 8) {
            assertEquals("timing row", i % 2 == 0, matrix[6][i])
            assertEquals("timing col", i % 2 == 0, matrix[i][6])
        }
        // The one module that is dark in every QR ever printed.
        assertTrue(matrix[n - 8][8])
    }

    private companion object {
        /** Level L (01) with mask 0, the only format this encoder emits. */
        const val FORMAT_DATA = 0b01000
    }
}
