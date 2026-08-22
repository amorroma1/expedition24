// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

/**
 * Maps WMO weather codes (what Open-Meteo returns) onto short METAR-style tokens, so the telemetry
 * window reads like an aviation report rather than a phone weather app.
 */
object WeatherCondition {

    const val UNKNOWN: Int = 0

    /** Indexed by the constants below; each entry is preconverted for allocation-free drawing. */
    val TOKENS: Array<CharArray> = arrayOf(
        "---".toCharArray(),   // 0  unknown
        "CLR".toCharArray(),   // 1  clear
        "FEW".toCharArray(),   // 2  mainly clear
        "SCT".toCharArray(),   // 3  partly cloudy
        "OVC".toCharArray(),   // 4  overcast
        "FG".toCharArray(),    // 5  fog
        "DZ".toCharArray(),    // 6  drizzle
        "FZDZ".toCharArray(),  // 7  freezing drizzle
        "RA".toCharArray(),    // 8  rain
        "FZRA".toCharArray(),  // 9  freezing rain
        "SN".toCharArray(),    // 10 snow
        "SG".toCharArray(),    // 11 snow grains
        "SHRA".toCharArray(),  // 12 rain showers
        "SHSN".toCharArray(),  // 13 snow showers
        "TS".toCharArray(),    // 14 thunderstorm
        "TSGR".toCharArray(),  // 15 thunderstorm with hail
        "BKN".toCharArray(),   // 16 broken cloud — METAR only; the WMO codes stop at overcast
    )

    const val BKN: Int = 16

    fun fromWmoCode(code: Int): Int = when (code) {
        0 -> 1
        1 -> 2
        2 -> 3
        3 -> 4
        45, 48 -> 5
        51, 53, 55 -> 6
        56, 57 -> 7
        61, 63, 65 -> 8
        66, 67 -> 9
        71, 73, 75 -> 10
        77 -> 11
        80, 81, 82 -> 12
        85, 86 -> 13
        95 -> 14
        96, 99 -> 15
        else -> UNKNOWN
    }

    fun token(index: Int): CharArray =
        if (index in TOKENS.indices) TOKENS[index] else TOKENS[UNKNOWN]

    /**
     * Maps a METAR's present-weather group and cloud cover onto the same tokens the WMO path
     * uses, so the row cannot tell its two sources apart.
     *
     * Weather outranks cover, the way a METAR itself reads: `-RA BKN012` is rain first. Only the
     * first wx group is taken, intensity and vicinity prefixes stripped — the row has room for
     * one token, and the first group is the reporting station's own lead.
     */
    fun fromMetar(wxString: String?, cover: String?): Int {
        val wx = wxString?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?.split(' ')?.first()
            ?.removePrefix("+")?.removePrefix("-")?.removePrefix("VC")
        if (wx != null) {
            val mapped = when {
                wx.contains("TS") -> if (wx.contains("GR")) 15 else 14
                wx.startsWith("FZ") -> if (wx.contains("DZ")) 7 else 9
                wx.startsWith("SH") -> if (wx.contains("SN")) 13 else 12
                wx.contains("SN") -> 10
                wx.contains("SG") -> 11
                wx.contains("DZ") -> 6
                wx.contains("RA") -> 8
                wx.contains("FG") || wx.contains("BR") -> 5
                else -> UNKNOWN
            }
            if (mapped != UNKNOWN) return mapped
        }
        return when (cover?.trim()) {
            "CAVOK", "CLR", "SKC", "NCD", "NSC" -> 1
            "FEW" -> 2
            "SCT" -> 3
            "BKN" -> BKN
            "OVC", "OVX", "VV" -> 4
            else -> UNKNOWN
        }
    }
}
