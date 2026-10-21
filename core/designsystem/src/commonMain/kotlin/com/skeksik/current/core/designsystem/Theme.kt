package com.skeksik.current.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.skeksik.current.core.designsystem.CurrentTokens.Palette
import com.skeksik.current.core.designsystem.CurrentTokens.Type

internal val darkColors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    secondary = Palette.accent,
    onSecondary = Palette.onAccent,
    // The selected sidebar entry: the accent at 16% over the sidebar.
    secondaryContainer = Palette.accent.copy(alpha = 0.16f).compositeOver(Palette.surfaceDark),
    onSecondaryContainer = Palette.textDark,
    background = Palette.ink,
    onBackground = Palette.textDark,
    surface = Palette.surfaceDark,
    onSurface = Palette.textDark,
    surfaceVariant = Palette.surfaceRaisedDark,
    onSurfaceVariant = Palette.textMutedDark,
    surfaceContainer = Palette.surfaceDark,
    surfaceContainerHigh = Palette.surfaceRaisedDark,
    outline = Palette.outlineDark,
    outlineVariant = Palette.outlineDark,
    error = Palette.danger,
    tertiary = Palette.warning,
)

internal val lightColors = lightColorScheme(
    primary = Palette.accentDark,
    onPrimary = Palette.surfaceLight,
    secondary = Palette.accentDark,
    onSecondary = Palette.surfaceLight,
    secondaryContainer = Palette.selectedLight,
    onSecondaryContainer = Palette.textLight,
    background = Palette.paper,
    onBackground = Palette.textLight,
    surface = Palette.surfaceLight,
    onSurface = Palette.textLight,
    surfaceVariant = Palette.surfaceLight,
    onSurfaceVariant = Palette.textMutedLight,
    surfaceContainer = Palette.surfaceLight,
    surfaceContainerHigh = Palette.surfaceLight,
    outline = Palette.outlineLight,
    outlineVariant = Palette.outlineLight,
    error = Palette.dangerDark,
    tertiary = Palette.warningDark,
)

private val typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontSize = Type.display, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontSize = Type.title, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = Type.heading, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = Type.body),
        bodyMedium = bodyMedium.copy(fontSize = Type.label),
        labelLarge = labelLarge.copy(fontSize = Type.label, fontWeight = FontWeight.Medium),
        bodySmall = bodySmall.copy(fontSize = Type.caption),
    )
}

/** The edge of a [Panel], or null for none: a white panel on a pale page needs one, a dark panel stands out by its fill. */
internal val LocalPanelEdge = staticCompositionLocalOf<Color?> { null }

/** Material 3 built from [CurrentTokens]; dark or light with the system. */
@Composable
fun CurrentTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPanelEdge provides if (dark) null else Palette.panelEdgeLight) {
        MaterialTheme(colorScheme = if (dark) darkColors else lightColors, typography = typography, content = content)
    }
}

/** A monospaced, spaced-out style for codes the user reads aloud or types elsewhere, such as a Quick Connect code. */
val codeStyle: TextStyle
    @Composable get() = MaterialTheme.typography.displaySmall.copy(
        fontFamily = FontFamily.Monospace,
        letterSpacing = MaterialTheme.typography.displaySmall.fontSize * 0.3f,
    )
