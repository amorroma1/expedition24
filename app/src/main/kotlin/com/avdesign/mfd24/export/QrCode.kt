// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

/**
 * A QR encoder just big enough for the incident log, and no bigger.
 *
 * Written here rather than pulled in, because this project deletes dependencies rather than
 * collecting them and the full generality of a QR library is generality the log never uses. The
 * log is bounded — thirty-two ISO lines and a checksum, under eight hundred bytes — so this
 * encoder is bounded to match: byte mode only, error-correction level L only, versions 1 to 20,
 * and always mask 0. A fixed mask forgoes the spec's penalty scoring, which exists to optimise
 * scan reliability, not to enable scanning: every decoder reads every legal mask, and the payload
 * is text a phone reads from twenty centimetres in the dark of its own choosing.
 *
 * The arithmetic is the standard's: Reed-Solomon over GF(2^8) with the 0x11D polynomial, BCH for
 * the format and version words, block interleaving from the level-L table. [QrCodeTest] checks
 * the self-verifiable parts — the Reed-Solomon remainder property, the published format-word
 * constant, the function-pattern geometry — and the end-to-end proof is a phone camera.
 */
object QrCode {

    /** Encodes [text] as ISO-8859-1 bytes and returns the module matrix, true = dark. */
    fun encode(text: String): Array<BooleanArray> {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val version = (1..MAX_VERSION).firstOrNull { bytes.size <= byteCapacity(it) }
            ?: throw IllegalArgumentException("payload of ${bytes.size} bytes does not fit v$MAX_VERSION-L")

        val data = codewords(bytes, version)
        val all = interleave(data, version)

        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(modules, function, version)
        drawCodewords(modules, function, all)
        applyMask0(modules, function)
        drawFormatBits(modules)
        return modules
    }

    // --- Bit stream and error correction -------------------------------------------------------

    /** How many payload bytes fit in [version] at level L, byte mode. */
    internal fun byteCapacity(version: Int): Int {
        val bits = DATA_CODEWORDS[version - 1] * 8
        val countBits = if (version <= 9) 8 else 16
        return (bits - 4 - countBits) / 8
    }

    /** The data codewords: mode, count, payload, terminator, pads. */
    private fun codewords(bytes: ByteArray, version: Int): IntArray {
        val capacity = DATA_CODEWORDS[version - 1]
        val countBits = if (version <= 9) 8 else 16
        val out = IntArray(capacity)
        var bitAt = 0

        fun put(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                if (value shr i and 1 == 1) out[bitAt / 8] = out[bitAt / 8] or (0x80 shr (bitAt % 8))
                bitAt++
            }
        }

        put(4, 4)                       // byte mode
        put(bytes.size, countBits)
        for (b in bytes) put(b.toInt() and 0xFF, 8)
        put(0, minOf(4, capacity * 8 - bitAt))          // terminator
        if (bitAt % 8 != 0) bitAt += 8 - bitAt % 8      // pad to a byte edge
        var pad = 0xEC
        while (bitAt < capacity * 8) {
            put(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        return out
    }

    /** Splits into level-L blocks, appends Reed-Solomon to each, interleaves both halves. */
    private fun interleave(data: IntArray, version: Int): IntArray {
        val ec = EC_PER_BLOCK[version - 1]
        val g1 = BLOCKS_G1[version - 1]
        val g1len = BLOCK_LEN_G1[version - 1]
        val g2 = BLOCKS_G2[version - 1]
        val g2len = g1len + 1

        val blocks = ArrayList<IntArray>(g1 + g2)
        var at = 0
        repeat(g1) { blocks.add(data.copyOfRange(at, at + g1len)); at += g1len }
        repeat(g2) { blocks.add(data.copyOfRange(at, at + g2len)); at += g2len }
        val eccs = blocks.map { reedSolomon(it, ec) }

        val out = IntArray(data.size + ec * blocks.size)
        var o = 0
        for (i in 0 until g2len) {
            for (b in blocks) if (i < b.size) out[o++] = b[i]
        }
        for (i in 0 until ec) {
            for (e in eccs) out[o++] = e[i]
        }
        return out
    }

    /** GF(2^8) multiply, polynomial 0x11D. */
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

    /** Reed-Solomon remainder of [data] against the degree-[degree] generator. */
    internal fun reedSolomon(data: IntArray, degree: Int): IntArray {
        // Generator = product of (x - 2^i) for i in 0 until degree.
        val gen = IntArray(degree)
        gen[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                gen[j] = gfMul(gen[j], root)
                if (j + 1 < degree) gen[j] = gen[j] xor gen[j + 1]
            }
            root = gfMul(root, 2)
        }
        val rem = IntArray(degree)
        for (b in data) {
            val factor = b xor rem[0]
            rem.copyInto(rem, 0, 1, degree)
            rem[degree - 1] = 0
            for (j in 0 until degree) rem[j] = rem[j] xor gfMul(gen[j], factor)
        }
        return rem
    }

    // --- The matrix -----------------------------------------------------------------------------

    private fun drawFunctionPatterns(
        modules: Array<BooleanArray>,
        function: Array<BooleanArray>,
        version: Int,
    ) {
        val size = modules.size

        fun set(row: Int, col: Int, dark: Boolean) {
            modules[row][col] = dark
            function[row][col] = true
        }

        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }

        // Finders with their separators: dark except the ring at distance 2 and the ring at 4.
        for ((cr, cc) in listOf(3 to 3, 3 to size - 4, size - 4 to 3)) {
            for (dr in -4..4) for (dc in -4..4) {
                val r = cr + dr
                val c = cc + dc
                if (r in 0 until size && c in 0 until size) {
                    val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                    set(r, c, dist != 2 && dist != 4)
                }
            }
        }

        val centres = ALIGNMENT[version - 1]
        for ((i, cr) in centres.withIndex()) for ((j, cc) in centres.withIndex()) {
            // The three combinations that would sit on finders are not drawn.
            if (i == 0 && j == 0) continue
            if (i == 0 && j == centres.lastIndex) continue
            if (i == centres.lastIndex && j == 0) continue
            for (dr in -2..2) for (dc in -2..2) {
                set(cr + dr, cc + dc, maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1)
            }
        }

        // Reserve the format cells so the data walk skips them; the bits land after masking.
        for (i in 0..8) {
            if (i != 6) {
                function[8][i] = true
                function[i][8] = true
            }
            if (i < 8) {
                function[8][size - 1 - i] = true
                function[size - 1 - i][8] = true
            }
        }
        set(size - 8, 8, true)   // the module that is always dark

        if (version >= 7) {
            var bits = VERSION_BITS[version - 7]
            for (i in 0 until 18) {
                val dark = bits and 1 == 1
                bits = bits shr 1
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark)
                set(b, a, dark)
            }
        }
    }

    /** The standard zig-zag walk: column pairs right to left, skipping column six. */
    private fun drawCodewords(
        modules: Array<BooleanArray>,
        function: Array<BooleanArray>,
        codewords: IntArray,
    ) {
        val size = modules.size
        var bit = 0
        val total = codewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val col = right - j
                    val upward = (right + 1) and 2 == 0
                    val row = if (upward) size - 1 - vert else vert
                    if (!function[row][col] && bit < total) {
                        modules[row][col] =
                            codewords[bit / 8] shr (7 - bit % 8) and 1 == 1
                        bit++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask0(modules: Array<BooleanArray>, function: Array<BooleanArray>) {
        for (r in modules.indices) for (c in modules.indices) {
            if (!function[r][c] && (r + c) % 2 == 0) modules[r][c] = !modules[r][c]
        }
    }

    /** Both copies of the format word, cell for cell as the standard places them. */
    private fun drawFormatBits(modules: Array<BooleanArray>) {
        val size = modules.size
        val bits = FORMAT_L_MASK0

        fun bit(i: Int): Boolean = bits shr i and 1 == 1

        // First copy, wrapped around the top-left finder.
        for (i in 0..5) modules[i][8] = bit(i)
        modules[7][8] = bit(6)
        modules[8][8] = bit(7)
        modules[8][7] = bit(8)
        for (i in 9..14) modules[8][14 - i] = bit(i)

        // Second copy: low bits along the top-right row, high bits down the bottom-left column.
        for (i in 0..7) modules[8][size - 1 - i] = bit(i)
        for (i in 8..14) modules[size - 15 + i][8] = bit(i)
    }

    // --- Constants ------------------------------------------------------------------------------

    internal const val MAX_VERSION = 20

    /**
     * The 15-bit format word for level L, mask 0: BCH(15,5) over 0x537, XOR 0x5412. Precomputed
     * because it is the only one this encoder ever emits; [QrCodeTest] re-derives it.
     */
    internal const val FORMAT_L_MASK0 = 0x77C4

    /** Data codewords per version at level L. */
    internal val DATA_CODEWORDS = intArrayOf(
        19, 34, 55, 80, 108, 136, 156, 194, 232, 274,
        324, 370, 428, 461, 523, 589, 647, 721, 795, 861,
    )

    /** Error-correction codewords per block at level L. */
    private val EC_PER_BLOCK = intArrayOf(
        7, 10, 15, 20, 26, 18, 20, 24, 30, 18,
        20, 24, 26, 30, 22, 24, 28, 30, 28, 28,
    )

    /** Level-L block structure: group-one block count and length; group two is one byte longer. */
    private val BLOCKS_G1 = intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 4, 2, 4, 3, 5, 5, 1, 5, 3, 3)
    private val BLOCK_LEN_G1 = intArrayOf(
        19, 34, 55, 80, 108, 68, 78, 97, 116, 68,
        81, 92, 107, 115, 87, 98, 107, 120, 113, 107,
    )
    private val BLOCKS_G2 = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 2, 0, 1, 1, 1, 5, 1, 4, 5)

    /** Alignment-pattern centre coordinates per version. */
    private val ALIGNMENT = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
        intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50), intArrayOf(6, 30, 54), intArrayOf(6, 32, 58), intArrayOf(6, 34, 62),
        intArrayOf(6, 26, 46, 66), intArrayOf(6, 26, 48, 70), intArrayOf(6, 26, 50, 74),
        intArrayOf(6, 30, 54, 78), intArrayOf(6, 30, 56, 82), intArrayOf(6, 30, 58, 86),
        intArrayOf(6, 34, 62, 90),
    )

    /** The 18-bit version words for 7..20: version << 12 | BCH remainder over 0x1F25. */
    internal val VERSION_BITS = intArrayOf(
        0x07C94, 0x085BC, 0x09A99, 0x0A4D3, 0x0BBF6, 0x0C762, 0x0D847,
        0x0E60D, 0x0F928, 0x10B78, 0x1145D, 0x12A17, 0x13532, 0x149A6,
    )
}
