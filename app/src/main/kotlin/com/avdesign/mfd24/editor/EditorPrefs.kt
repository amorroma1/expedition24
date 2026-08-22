// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.editor

import android.content.Context

/**
 * What the settings list remembers about itself.
 *
 * Not the style schema: nothing here changes a pixel of the dial, and a schema change resets every
 * stored style on update — a preference about the *menu* has no business costing somebody their
 * palette.
 */
object EditorPrefs {

    /**
     * Whether the explanatory lines under the rows are shown.
     *
     * On for a first-time reader, and one tap from off for everybody else. The hints exist because
     * half of this list controls things whose consequences are invisible until they matter —
     * why `AUTO` will not thin the face during a night watch, why the log covers one watch, what
     * the platform will and will not let an update do. Read once, they are exactly what makes a
     * dense list navigable. Read the ninth time, they are what you scroll past to reach the row
     * you came for.
     *
     * Only the explanations go. Anything that carries a *reading* — a version, an incident, the
     * watch the log belongs to, "None on record" — stays: that is data, and switching off the
     * commentary must never switch off the record.
     */
    fun hintsShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HINTS, true)

    fun setHintsShown(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean(KEY_HINTS, shown).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "mfd24_editor"
    private const val KEY_HINTS = "hints_shown"
}
