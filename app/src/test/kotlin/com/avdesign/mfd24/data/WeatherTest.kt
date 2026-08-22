// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTest {

    @Test
    fun `wmo codes map onto METAR-style tokens`() {
        assertEquals("CLR", token(0))
        assertEquals("FEW", token(1))
        assertEquals("SCT", token(2))
        assertEquals("OVC", token(3))
        assertEquals("FG", token(45))
        assertEquals("FG", token(48))
        assertEquals("DZ", token(53))
        assertEquals("FZDZ", token(56))
        assertEquals("RA", token(63))
        assertEquals("FZRA", token(67))
        assertEquals("SN", token(73))
        assertEquals("SG", token(77))
        assertEquals("SHRA", token(81))
        assertEquals("SHSN", token(86))
        assertEquals("TS", token(95))
        assertEquals("TSGR", token(99))
    }

    @Test
    fun `unknown codes fall back to dashes`() {
        assertEquals("---", token(-1))
        assertEquals("---", token(4))
        assertEquals("---", token(1000))
        assertEquals("---", String(WeatherCondition.token(999)))
    }

    @Test
    fun `every declared condition index has a token`() {
        for (index in WeatherCondition.TOKENS.indices) {
            assertNotNull(WeatherCondition.token(index))
        }
    }

    @Test
    fun `parses an Open-Meteo current block`() {
        val body = """
            {"latitude":55.75,"longitude":37.62,"timezone":"GMT",
             "current":{"time":"2026-08-17T18:00","interval":900,
                        "temperature_2m":-3.7,"weather_code":3,"pressure_msl":1013.2}}
        """.trimIndent()

        val sample = OpenMeteoClient.parse(body, nowMillis = 1_000L)
        assertNotNull(sample)
        assertEquals(-37, sample!!.temperatureDeciC)
        assertEquals(10132, sample.pressureDeciHpa)
        assertEquals(WeatherCondition.fromWmoCode(3), sample.conditionIndex)
        assertEquals(1_000L, sample.observedAt)
    }

    @Test
    fun `tolerates a missing pressure field`() {
        val body = """{"current":{"temperature_2m":12.0,"weather_code":0}}"""
        val sample = OpenMeteoClient.parse(body, nowMillis = 0L)
        assertNotNull(sample)
        assertEquals(120, sample!!.temperatureDeciC)
        assertEquals(0, sample.pressureDeciHpa)
    }

    @Test
    fun `rejects a payload with no observation`() {
        assertNull(OpenMeteoClient.parse("""{"error":true}""", 0L))
        assertNull(OpenMeteoClient.parse("""{"current":{"interval":900}}""", 0L))
    }

    private fun token(wmoCode: Int): String =
        String(WeatherCondition.token(WeatherCondition.fromWmoCode(wmoCode)))
}
