package dev.web2app.shell

import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nav icon path data actually parses.
 *
 * This is the seam where the dashboard and the app meet. The dashboard writes
 * Material Symbols path data into config.json; NavIcon.kt feeds it to Compose's
 * PathParser and silently falls back to a plain circle if it throws. That
 * fallback is the right runtime behaviour and a terrible way to find out the
 * format is wrong — the app does not crash, it just shows a grey dot on every
 * tab, which is exactly what shipped before anything filled this field in.
 *
 * So the paths are parsed here, with the same parser, at build time.
 *
 * The samples are copied verbatim from packages/config-schema/nav-icons.ts. If
 * that table is regenerated with a different icon set, a failure here means the
 * new data is not in a form the app can draw.
 */
class NavIconPathTest {

    /** Verbatim from the committed table: home, shopping_cart, search, person. */
    private val samples = mapOf(
        "home" to
            "M240-200h120v-240h240v240h120v-360L480-740 240-560v360Zm-80 80v-480l320-240 320 240v480H520v-240h-80v240H160Zm320-350Z",
        "search" to
            "M784-120 532-372q-30 24-69 38t-83 14q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l252 252-56 56ZM380-400q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z",
    )

    @Test
    fun `committed path data parses into nodes`() {
        for ((name, path) in samples) {
            val nodes = PathParser().parsePathString(path).toNodes()
            assertTrue("$name produced no path nodes", nodes.isNotEmpty())
        }
    }

    @Test
    fun `navIcon builds a vector from real path data`() {
        val icon = AppConfig.NavIcon(
            name = "home",
            path = samples.getValue("home"),
            minX = 0f,
            minY = -960f,
            viewportWidth = 960f,
            viewportHeight = 960f,
        )

        val vector = navIcon(icon)

        // Material Symbols draw from the baseline up, so the group translation
        // is what stops every icon rendering off-screen. Getting the viewport
        // wrong produces a blank tab rather than an error.
        assertTrue("viewport width should match the symbol grid", vector.viewportWidth == 960f)
        assertTrue("viewport height should match the symbol grid", vector.viewportHeight == 960f)
        assertTrue("the vector should carry the icon's name", vector.name == "home")
    }

    @Test
    fun `a malformed path falls back instead of throwing`() {
        // Path data reaches the app from a config a remote update can also
        // write, so it is untrusted. A bad path must degrade to the fallback
        // circle, never take the app down on its first frame.
        val icon = AppConfig.NavIcon(
            name = "broken",
            path = "this is not a path",
            minX = 0f,
            minY = -960f,
            viewportWidth = 960f,
            viewportHeight = 960f,
        )

        val vector = navIcon(icon)
        assertTrue("a malformed path must still produce a drawable vector", vector.name == "broken")
    }
}
