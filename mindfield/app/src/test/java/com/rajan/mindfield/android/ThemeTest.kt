package com.rajan.mindfield

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.rajan.mindfield.core.Category
import com.rajan.mindfield.core.Evidence
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every colour pair the app draws text with reads at 4.5:1 or better (WCAG AA), in both themes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }

    @Test
    fun `text on a filled colour reads well, including dark mode's pastels`() {
        for (dark in listOf(false, true)) {
            val p = if (dark) DarkPalette else LightPalette
            val fills = Category.entries.map { it.name to Palettes.accent(it, dark) } + listOf("brand" to p.brand)
            for ((name, fill) in fills) {
                val ratio = contrast(onColor(fill), fill)
                assertTrue("$name, dark=$dark: $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `every text colour reads well on the page and on cards`() {
        for (p in listOf(LightPalette, DarkPalette)) {
            val texts = listOf("ink" to p.ink, "muted" to p.muted, "faint" to p.faint, "brand" to p.brand, "good" to p.good, "bad" to p.bad) +
                Category.entries.map { it.name to Palettes.accent(it, p.dark) } +
                Evidence.entries.map { it.name to Palettes.evidence(it, p.dark) }
            for ((name, text) in texts) {
                for (background in listOf(p.bg, p.surface)) {
                    val ratio = contrast(text, background)
                    assertTrue("$name, dark=${p.dark}: $ratio", ratio >= 4.5)
                }
            }
        }
    }
}
