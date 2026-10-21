package com.skeksik.current.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/** WCAG AA in the light theme: 4.5:1 for text, 3:1 for borders and icons, against every background they sit on. */
class ContrastTest {
    private val colors = lightColors
    private val backgrounds = mapOf(
        "page" to colors.background,
        "sidebar" to colors.surface,
        "panel" to colors.surfaceVariant,
        "dialog" to colors.surfaceContainerHigh,
    )

    @Test
    fun textIsReadableOnEveryBackground() {
        val text = mapOf(
            "text" to colors.onSurface,
            "secondary text" to colors.onSurfaceVariant,
            "accent" to colors.primary,
            "error" to colors.error,
            "warning" to colors.tertiary,
        )
        for ((name, color) in text) backgrounds.forEach { (on, background) -> assertContrast(name, color, on, background, 4.5) }
    }

    @Test
    fun bordersStandOutFromEveryBackground() {
        // Material draws a text field's border in outline and an outlined button's in outlineVariant.
        val borders = mapOf("outline" to colors.outline, "outline variant" to colors.outlineVariant)
        for ((name, color) in borders) backgrounds.forEach { (on, background) -> assertContrast(name, color, on, background, 3.0) }
    }

    @Test
    fun theSelectedSidebarEntryIsReadable() {
        assertContrast("selected label", colors.onSecondaryContainer, "selected entry", colors.secondaryContainer, 4.5)
        assertContrast("selected icon", colors.primary, "selected entry", colors.secondaryContainer, 3.0)
    }

    private fun assertContrast(name: String, color: Color, on: String, background: Color, minimum: Double) {
        val ratio = contrast(color, background)
        assertTrue(ratio >= minimum, "$name on $on: ${ratio.format()}:1, needs $minimum:1")
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun Double.format() = (this * 100).toInt() / 100.0
}
