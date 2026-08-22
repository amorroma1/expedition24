// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import com.avdesign.mfd24.geo.PoiFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pictogram choice is a priority order over two flags and three types, which is small enough to
 * enumerate and easy enough to get subtly wrong.
 */
class SiteGlyphTest {

    private val military = PoiFormat.FLAG_MILITARY
    private val helipad = PoiFormat.FLAG_HELIPAD

    @Test
    fun `airfields split civil from military`() {
        assertEquals(SiteGlyph.AIRLINER, SiteGlyph.forSite(PoiFormat.TYPE_AIRPORT, 0))
        assertEquals(SiteGlyph.FIGHTER, SiteGlyph.forSite(PoiFormat.TYPE_AIRPORT, military))
    }

    @Test
    fun `ports split merchant from naval`() {
        assertEquals(SiteGlyph.MERCHANT_SHIP, SiteGlyph.forSite(PoiFormat.TYPE_PORT, 0))
        assertEquals(SiteGlyph.WARSHIP, SiteGlyph.forSite(PoiFormat.TYPE_PORT, military))
    }

    @Test
    fun `a helipad stays a rotorcraft even when it is military`() {
        assertEquals(SiteGlyph.HELICOPTER, SiteGlyph.forSite(PoiFormat.TYPE_AIRPORT, helipad))
        // Armed, but still never a fast jet: the family is decided by the pad, not the owner.
        assertEquals(
            SiteGlyph.HELICOPTER_MILITARY,
            SiteGlyph.forSite(PoiFormat.TYPE_AIRPORT, helipad or military),
        )
    }

    @Test
    fun `military is carried by colour for every type, not only the ones with two silhouettes`() {
        assertTrue(SiteGlyph.isMilitary(military))
        assertTrue(SiteGlyph.isMilitary(military or helipad))
        assertFalse(SiteGlyph.isMilitary(0))
        assertFalse(SiteGlyph.isMilitary(helipad))
        // A spaceport draws one silhouette either way, so colour is the only thing telling a
        // military range from a civil one.
        assertEquals(
            SiteGlyph.forSite(PoiFormat.TYPE_SPACEPORT, 0),
            SiteGlyph.forSite(PoiFormat.TYPE_SPACEPORT, military),
        )
        assertTrue(SiteGlyph.isMilitary(military))
    }

    @Test
    fun `a spaceport is a rocket whoever owns the range`() {
        assertEquals(SiteGlyph.ROCKET, SiteGlyph.forSite(PoiFormat.TYPE_SPACEPORT, 0))
        assertEquals(SiteGlyph.ROCKET, SiteGlyph.forSite(PoiFormat.TYPE_SPACEPORT, military))
        // The helipad bit must not leak across types: a launch site is never a helicopter.
        assertEquals(SiteGlyph.ROCKET, SiteGlyph.forSite(PoiFormat.TYPE_SPACEPORT, helipad))
    }

    @Test
    fun `unknown flag bits are ignored rather than changing the glyph`() {
        assertEquals(SiteGlyph.AIRLINER, SiteGlyph.forSite(PoiFormat.TYPE_AIRPORT, 0b1111_1100))
        assertEquals(SiteGlyph.MERCHANT_SHIP, SiteGlyph.forSite(PoiFormat.TYPE_PORT, 0b1111_1100))
    }
}
