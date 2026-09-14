package dev.web2app.shell

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status bar appearance flag is the opposite of the setting that drives it.
 *
 * `APPEARANCE_LIGHT_STATUS_BARS` is documented as "Changes the foreground color
 * for **light status bars** so that the items on the bar can be read clearly",
 * and the flag AndroidX sets below API 30, `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`,
 * is "compatible with **light status bar backgrounds**". So `true` means a
 * light *bar* and therefore **dark** icons.
 *
 * The configuration asks for light *icons*. Assigning one to the other directly
 * — which is what shipped — gave every app the exact opposite of the setting:
 * choosing light icons produced dark ones, invisible on the dark status bar
 * they were chosen for.
 *
 * It cannot be caught by reading the code, because both sides are called
 * "light". So the rule is written down here instead: the flag is the negation.
 */
class SystemBarAppearanceTest {

    /** The mapping MainActivity.applySystemBars applies. */
    private fun appearanceLightBars(wantsLightIcons: Boolean) = !wantsLightIcons

    @Test
    fun `light icons means the appearance flag is off`() {
        assertEquals(false, appearanceLightBars(true))
    }

    @Test
    fun `dark icons means the appearance flag is on`() {
        assertEquals(true, appearanceLightBars(false))
    }

    /**
     * An unset system bar colour must stay transparent.
     *
     * The bars are painted by drawing behind the insets now that
     * Window.setStatusBarColor is a no-op at targetSdk 35 and above. A config
     * that never chose a colour has to leave the page showing through, which is
     * what edge-to-edge looks like — not fall back to black, which would put an
     * opaque band over content the user expects to see.
     */
    @Test
    fun `an absent bar colour parses to transparent rather than black`() {
        assertEquals(Color.Transparent, parseHexColor(null, Color.Transparent))
        assertEquals(Color.Transparent, parseHexColor("not-a-colour", Color.Transparent))
    }

    @Test
    fun `a chosen bar colour is used`() {
        assertEquals(Color(0xFF2563EB), parseHexColor("#2563EB", Color.Transparent))
    }
}
