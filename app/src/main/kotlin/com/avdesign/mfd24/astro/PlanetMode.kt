// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

/**
 * Celestial body the hour hand keeps time on. Plain `Int` constants rather than an enum because
 * this value is read on every frame and enum dispatch would put an object comparison in the hot path.
 */
object PlanetMode {
    const val EARTH: Int = 0
    const val MARS: Int = 1
    const val MOON: Int = 2

    const val ID_EARTH: String = "earth"
    const val ID_MARS: String = "mars"
    const val ID_MOON: String = "moon"

    fun fromOptionId(id: String): Int = when (id) {
        ID_MARS -> MARS
        ID_MOON -> MOON
        else -> EARTH
    }

    // No inverse mapping: option ids flow one way, from the style into the renderer. The editor
    // mirrors the schema's own strings, so a toOptionId sat here unused until it was removed.
}
