// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-face settings split, pinned as data. [StyleSchema.create] builds from exactly the list
 * under test, so what passes here is what the schema ships — and because a schema change resets
 * every stored style on update, an accidental drift in either list is a user-visible incident
 * rather than a refactor.
 */
class StyleSchemaWorldTest {

    @Test
    fun `the duty face keeps its own settings, and gains none of the wellness rows`() {
        val ids = StyleSchema.settingIds("earth").map { it.toString() }
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
    fun `the wellness face trades its units for the recorder, and keeps its slots`() {
        val ids = StyleSchema.settingIds(StyleSchema.WORLD_VITAL).map { it.toString() }
        assertTrue(
            ids.containsAll(
                listOf("vital_record", "vital_interval", "alarm_mark", "calendar_marks")
            )
        )
        // The units serve a weather row it does not print. The slots stay: the rings estimate a
        // day, and an estimate is exactly when a wearer wants the actual reading beside it.
        for (removed in listOf("temp_unit", "pressure_unit")) {
            assertFalse("$removed should be absent on the wellness face", ids.contains(removed))
        }
        assertTrue(ids.containsAll(listOf("sensor_left", "sensor_right")))
        // Vigilance survives whole: the function is kept, only its prominence changes.
        assertTrue(
            ids.containsAll(
                listOf(
                    "lume_palette", "midnight_label", "dial_top", "nadir", "solar_mark",
                    "lunar_mark", "weather", "vigilance", "vigil_interval", "vibe_strength",
                    "sos_sound", "log_heart_rate", "ambient_density",
                )
            )
        )
    }

    @Test
    fun `no face repeats a setting id`() {
        for (world in listOf("earth", StyleSchema.WORLD_VITAL)) {
            val ids = StyleSchema.settingIds(world)
            assertEquals(ids.size, ids.map { it.toString() }.toSet().size)
        }
    }

    @Test
    fun `the sampling interval reads as minutes, and an unknown id falls to ten`() {
        assertEquals(5 * 60_000L, StyleSchema.recordIntervalMillis("5"))
        assertEquals(15 * 60_000L, StyleSchema.recordIntervalMillis("15"))
        assertEquals(10 * 60_000L, StyleSchema.recordIntervalMillis("sometimes"))
    }
}
