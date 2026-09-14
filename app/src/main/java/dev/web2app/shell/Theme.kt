package dev.web2app.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Theme built from config at runtime.
 *
 * This is why the shell is Compose: in Views, a user-chosen primary colour would
 * have to be written into a generated colors.xml on every build. Here it is just a
 * value, so nothing about the user's configuration touches the build inputs.
 */
@Composable
fun Web2AppTheme(theme: AppConfig.ThemeConfig, content: @Composable () -> Unit) {
    val dark = when (theme.mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val primary = parseHexColor(theme.primaryColor, fallback = Color(0xFF2563EB))
    val background = parseHexColor(theme.backgroundColor, fallback = if (dark) Color.Black else Color.White)

    val scheme = if (dark) {
        darkColorScheme(primary = primary, background = background, surface = background)
    } else {
        lightColorScheme(primary = primary, background = background, surface = background)
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * The schema already rejects anything that is not #rrggbb, but this runs against a
 * file on disk that a remote-config update could also write, so it validates rather
 * than trusting the producer.
 */
internal fun parseHexColor(value: String?, fallback: Color): Color {
    val hex = value?.removePrefix("#") ?: return fallback
    if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return fallback
    val rgb = hex.toLongOrNull(16) ?: return fallback
    return Color(rgb or 0xFF000000L)
}
