package dev.web2app.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Builds a nav icon from vector path data carried in config.json.
 *
 * This is why no icon library ships in the generated app: the build runner resolves
 * the chosen Material Symbols name (or a customer's own single-path SVG) to path
 * data, and only the handful of icons actually used cost any bytes. Any of the
 * ~3,500 symbols works, and nothing has to be enumerated in Kotlin.
 *
 * Path data is untrusted input — it reaches here from a config that a remote-config
 * update can also write — so a malformed path falls back instead of crashing the
 * app on its first frame.
 */
internal fun navIcon(icon: AppConfig.NavIcon): ImageVector {
    val nodes = runCatching { PathParser().parsePathString(icon.path ?: FALLBACK_PATH).toNodes() }
        .getOrElse { runCatching { PathParser().parsePathString(FALLBACK_PATH).toNodes() }.getOrDefault(emptyList()) }

    // An SVG viewBox may start anywhere; Material Symbols use [0,-960,960,960], so
    // this group translation is what stops every icon rendering off-screen.
    return ImageVector.Builder(
        name = icon.name,
        defaultWidth = ICON_SIZE_DP.dp,
        defaultHeight = ICON_SIZE_DP.dp,
        viewportWidth = icon.viewportWidth,
        viewportHeight = icon.viewportHeight,
    )
        .addGroup(name = icon.name, translationX = -icon.minX, translationY = -icon.minY)
        // Black is a placeholder: Icon() tints with LocalContentColor, so the nav
        // bar's selected/unselected colours still apply.
        .addPath(pathData = nodes, fill = SolidColor(Color.Black))
        .clearGroup()
        .build()
}

/** Plain circle, used when an icon is missing or its path does not parse. */
private const val FALLBACK_PATH =
    "M480-480m-280,0a280,280 0 1,0 560,0a280,280 0 1,0 -560,0"

private const val ICON_SIZE_DP = 24
