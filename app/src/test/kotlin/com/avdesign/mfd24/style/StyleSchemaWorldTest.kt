// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.style

import com.avdesign.mfd24.astro.PlanetMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-world settings split, pinned as data. [StyleSchema.create] builds from exactly the
 * list under test, so what passes here is what the schema ships — and because a schema change
 * resets every stored style on update, an accidental drift in either list is a user-visible
 * incident, not a refactor.
 */
class StyleSchemaWorldTest {

    @Test
    fun `earth keeps its seventeen settings, unchanged`() {
        val ids = StyleSchema.settingIds(PlanetMode.EARTH).map { it.toString() }
        assertEquals(
            listOf(
                "lume_palette", "temp_unit", "pressure_unit", "midnight_label", "dial_top",
                "nadir", "solar_mark", "lunar_mark", "weather", "vigilance", "vigil_interval",
                "vibe_strength", "sos_sound", "log_heart_rate", "sensor_left", "sensor_right",
                "ambient_density",
            ).sorted(),
            ids.sorted(),
        )
    }

    @Test
    fun `mars trades weather, the sky marks and units for the rover and the relays`() {
        val ids = StyleSchema.settingIds(PlanetMode.MARS).map { it.toString() }
        assertTrue(ids.contains("rover"))
        assertTrue(ids.containsAll(listOf("relay_mro", "relay_odyssey", "relay_maven", "relay_tgo")))
        // No solar mark either: on a mean-time dial the only sun that touches the band's edges
        // at the physical sunrise and sunset is the hour hand itself.
        for (removed in listOf(
            "weather", "solar_mark", "lunar_mark", "temp_unit", "pressure_unit",
        )) {
            assertFalse("$removed should be absent on mars", ids.contains(removed))
        }
        // What both worlds share is the instrument itself.
        assertTrue(
            ids.containsAll(
                listOf(
                    "lume_palette", "midnight_label", "dial_top", "nadir",
                    "vigilance", "vigil_interval", "vibe_strength", "sos_sound",
                    "log_heart_rate", "sensor_left", "sensor_right", "ambient_density",
                )
            )
        )
    }

    @Test
    fun `no world repeats a setting id`() {
        for (mode in intArrayOf(PlanetMode.EARTH, PlanetMode.MARS)) {
            val ids = StyleSchema.settingIds(mode)
            assertEquals(ids.size, ids.map { it.toString() }.toSet().size)
        }
    }
}
