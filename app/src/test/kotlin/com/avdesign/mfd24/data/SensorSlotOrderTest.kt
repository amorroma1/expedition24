// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [SensorSlots.Kind] ordinals, because they are a wire format, not an implementation
 * detail: the renderer hands them to [TelemetryLayer][com.avdesign.mfd24.render.TelemetryLayer]
 * as plain ints, and that file keeps its own `SLOT_*` copies so the drawing path never touches an
 * enum. Reorder the enum and every pictogram silently swaps; this fails instead.
 */
class SensorSlotOrderTest {

    @Test
    fun `the kind ordinals are the slot protocol`() {
        assertEquals(0, SensorSlots.Kind.OFF.ordinal)
        assertEquals(1, SensorSlots.Kind.HEART_RATE.ordinal)
        assertEquals(2, SensorSlots.Kind.STEPS.ordinal)
        assertEquals(3, SensorSlots.Kind.PRESSURE.ordinal)
    }
}
