package com.skeksik.current.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens: the values every Compose screen is built from. The theme maps them onto Material 3; screens read spacing and sizes
 * from here rather than writing numbers. M4 moves them to a format SwiftUI reads too (roadmap).
 */
object CurrentTokens {
    /** A deep blue-green accent on neutral greys; dark is the default look of a media app, light follows the system. */
    object Palette {
        val accent = Color(0xFF2BB3A6)
        val accentDark = Color(0xFF00796F)
        val onAccent = Color(0xFF00201D)
        val danger = Color(0xFFE5484D)
        val dangerDark = Color(0xFFB3261E)
        val warning = Color(0xFFF5A524)
        /** Amber dark enough for text on light surfaces (about 5:1 on white). */
        val warningDark = Color(0xFF8A5A00)

        val ink = Color(0xFF0E1113)
        val surfaceDark = Color(0xFF15191C)
        val surfaceRaisedDark = Color(0xFF1D2226)
        val outlineDark = Color(0xFF3A4247)
        val textDark = Color(0xFFE7ECEE)
        val textMutedDark = Color(0xFF9AA5AB)

        /**
         * Light pairs meet WCAG AA against every light background (ContrastTest): text 4.5:1, borders 3:1. The page is a shade darker
         * than the white sidebar and panels so they stand apart, and panels add [panelEdgeLight].
         */
        val paper = Color(0xFFEBEFF2)
        val surfaceLight = Color(0xFFFFFFFF)
        val outlineLight = Color(0xFF7E8A91)
        /** The edge of a white panel on [paper]; decoration, not a control's border, so it stays light. */
        val panelEdgeLight = Color(0xFFD3DADE)
        /** The selected sidebar entry: [accentDark] at about a quarter on white. */
        val selectedLight = Color(0xFFC2DFDC)
        val textLight = Color(0xFF14191C)
        val textMutedLight = Color(0xFF4E5B63)
    }

    object Spacing {
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }

    object Radius {
        val s = 6.dp
        val m = 10.dp
        val l = 16.dp
    }

    object Size {
        val sidebarWidth = 232.dp
        val sidebarItemHeight = 44.dp
        val icon = 20.dp
        /** Forms and text blocks stay readable on wide windows. */
        val contentMaxWidth = 640.dp
        val focusRing = 2.dp
        /** Space between an element and its focus ring, so the ring shows on a fill of the same colour. */
        val focusRingGap = 2.dp
    }

    object Type {
        val display = 32.sp
        val title = 22.sp
        val heading = 17.sp
        val body = 15.sp
        val label = 14.sp
        val caption = 12.sp
    }
}
