// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.editor

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors

/**
 * The one Compose palette every screen of this app wears — the editor and the update screen must
 * read as the same instrument, and two hand-kept copies of these hex values would drift the first
 * time one of them was tuned.
 */
internal val CockpitColors = Colors(
    primary = Color(0xFFFFB300),
    primaryVariant = Color(0xFF7A5400),
    secondary = Color(0xFFE0E6ED),
    secondaryVariant = Color(0xFF2A2E36),
    background = Color.Black,
    surface = Color(0xFF181A1E),
    error = Color(0xFFFF1E1E),
    onPrimary = Color(0xFF14100A),
    onSecondary = Color(0xFF14100A),
    onBackground = Color(0xFFE0E6ED),
    onSurface = Color(0xFFE0E6ED),
    onError = Color.Black,
)
