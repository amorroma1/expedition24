// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The callsign is an issued identity, so it is pinned like one: golden values for a fixed device
 * id, computed independently, so a refactor that quietly re-derives every callsign in the field
 * fails the build instead of re-issuing them.
 */
class CallsignTest {

    @Test
    fun `a known device id keeps its issued callsign`() {
        // SHA-256("MFD24" + id), first four bytes — computed outside this codebase.
        assertEquals("RAVEN-42", Callsign.of("89ab4f2c1d3e5a67"))
        assertEquals("10396EB9", Callsign.shortId("89ab4f2c1d3e5a67"))
    }

    @Test
    fun `every callsign fits the PREFIX-NN shape`() {
        val shape = Regex("[A-Z]+-\\d{2}")
        for (i in 0 until 200) {
            val sign = Callsign.of("device-$i")
            assertTrue(sign, shape.matches(sign))
            val number = sign.substringAfter('-').toInt()
            assertTrue(sign, number in 1..99)
        }
    }

    @Test
    fun `different devices usually differ, the same device never does`() {
        assertEquals(Callsign.of("one"), Callsign.of("one"))
        // 200 devices over 1980 callsigns: collisions are allowed, monoculture is not.
        val distinct = (0 until 200).map { Callsign.of("device-$it") }.distinct().size
        assertTrue("only $distinct distinct callsigns in 200", distinct > 150)
    }
}
