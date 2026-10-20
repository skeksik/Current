package com.skeksik.current.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The app's own line icons on a 24-unit grid, stroked so they tint with `LocalContentColor`. Drawn here: no icon library. */
object CurrentIcons {
    val Home: ImageVector by lazy {
        icon("Home") {
            moveTo(4f, 10.5f); lineTo(12f, 4f); lineTo(20f, 10.5f)
            moveTo(6f, 9f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 9f)
            moveTo(10f, 20f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20f)
        }
    }

    /** Three sliders: settings. */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            moveTo(4f, 6f); lineTo(20f, 6f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            moveTo(4f, 18f); lineTo(20f, 18f)
            circle(9f, 6f); circle(15f, 12f); circle(7f, 18f)
        }
    }

    /** Two stacked units: a server. */
    val Server: ImageVector by lazy {
        icon("Server") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 11f); lineTo(4f, 11f); close()
            moveTo(4f, 13f); lineTo(20f, 13f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(7f, 8f); lineTo(8f, 8f)
            moveTo(7f, 16f); lineTo(8f, 16f)
        }
    }

    val Add: ImageVector by lazy {
        icon("Add") {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(19f, 12f)
        }
    }

    /** A circle with an exclamation mark: errors and warnings. */
    val Alert: ImageVector by lazy {
        icon("Alert") {
            circle(12f, 12f, 8.5f)
            moveTo(12f, 7.5f); lineTo(12f, 13f)
            moveTo(12f, 16.2f); lineTo(12f, 16.4f)
        }
    }

    private fun icon(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = draw,
    ).build()

    private fun PathBuilder.circle(x: Float, y: Float, r: Float = 2f) {
        moveTo(x - r, y)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = x + r, y1 = y)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = x - r, y1 = y)
        close()
    }
}
