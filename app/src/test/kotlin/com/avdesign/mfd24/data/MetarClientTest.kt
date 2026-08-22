// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The METAR path, held to a real payload: the JSON below is what aviationweather.gov actually
 * returned for KJFK, trimmed to the fields read here. The rules that matter are the refusals —
 * stale, futureless, temperature-less — because every refusal is a silent fall-back to
 * Open-Meteo, and a refusal that fires wrongly turns the authoritative source off without a
 * trace.
 */
class MetarClientTest {

    private val kjfk = """[{"icaoId":"KJFK","obsTime":1787255460,"temp":25.6,"dewp":21.1,
        "wdir":140,"wspd":6,"visib":"10+","altim":1014,"metarType":"METAR",
        "cover":"OVC","clouds":[{"cover":"FEW","base":5000},{"cover":"OVC","base":10000}]}]"""

    private val fetched = 1_787_255_460_000L + 10 * 60_000L   // ten minutes after observation

    @Test
    fun `a fresh report becomes the row's own units`() {
        val sample = MetarClient.parse(kjfk, fetched)!!
        assertEquals(256, sample.temperatureDeciC)
        assertEquals(10140, sample.pressureDeciHpa)
        assertEquals(4, sample.conditionIndex)              // OVC
        // The cache ages from the fetch, not from the observation: a METAR is up to an hour old
        // at issue, and a cache keyed to that would refetch on every screen-on half of the time.
        assertEquals(fetched, sample.observedAt)
    }

    @Test
    fun `stale, empty and temperature-less reports fall through to the model`() {
        assertNull(MetarClient.parse(kjfk, fetched + MetarClient.MAX_AGE_MILLIS))
        assertNull(MetarClient.parse("[]", fetched))
        assertNull(
            MetarClient.parse("""[{"icaoId":"KJFK","obsTime":1787255460,"altim":1014}]""", fetched)
        )
    }

    @Test
    fun `present weather outranks cover, and both map onto the shared tokens`() {
        // A METAR reads weather first: -RA under a broken deck is rain, not cloud.
        assertEquals(8, WeatherCondition.fromMetar("-RA", "BKN"))
        assertEquals(14, WeatherCondition.fromMetar("+TSRA", "OVC"))
        assertEquals(15, WeatherCondition.fromMetar("TSGR", null))
        assertEquals(9, WeatherCondition.fromMetar("FZRA", "OVC"))
        assertEquals(12, WeatherCondition.fromMetar("VCSH SHRA", "SCT"))
        assertEquals(5, WeatherCondition.fromMetar("BR", "FEW"))
        // No weather group: the cover speaks, including the METAR-only broken deck.
        assertEquals(WeatherCondition.BKN, WeatherCondition.fromMetar(null, "BKN"))
        assertEquals(1, WeatherCondition.fromMetar("", "CAVOK"))
        assertEquals(WeatherCondition.UNKNOWN, WeatherCondition.fromMetar(null, null))
    }
}
