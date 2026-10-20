package com.topjohnwu.magisk.ui

import androidx.compose.ui.graphics.Color

/**
 * Kitsune colour palettes. [light] / [dark] are the seed colours used to
 * generate the Material 3 scheme in light and dark mode respectively.
 * The ordinal is persisted in Config.themeOrdinal.
 */
enum class ThemePalette(val label: String, val light: Color, val dark: Color) {
    KITSUNE_TEAL("Kitsune (Teal)", Color(0xFF00897B), Color(0xFF4DB6AC)),
    SILVER("Silver", Color(0xFF607D8B), Color(0xFFB0BEC5)),
    AMOLED("AMOLED", Color(0xFF6750A4), Color(0xFFD0BCFF)),
    RAYQUAZA("Rayquaza", Color(0xFF2E7D32), Color(0xFF66BB6A)),
    ZAPDOS("Zapdos", Color(0xFFF57C00), Color(0xFFFFB74D)),
    CHARMELEON("Charmeleon", Color(0xFFD32F2F), Color(0xFFEF5350)),
    MEW("Mew", Color(0xFFAD1457), Color(0xFFF06292)),
    SALAMENCE("Salamence", Color(0xFF1976D2), Color(0xFF64B5F6)),
    CARAMEL("Caramel", Color(0xFF8D4E3C), Color(0xFFFFB59B)),
    MOCHA("Mocha", Color(0xFF6F4E37), Color(0xFFD7C4A8)),
    RAMADAN("Ramadan", Color(0xFFC6922A), Color(0xFFFFD54F)),
    LEGACY("Legacy", Color(0xFF00897B), Color(0xFF4DB6AC));

    companion object {
        fun fromOrdinal(i: Int) = entries.getOrNull(i) ?: KITSUNE_TEAL
    }
}
