package com.galileo.mobileconnect.design

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two rules scripts/gen_tokens.py enforces, checked again on the output the app actually compiles (MAPP-LAY-030, MAPP-LAY-040). */
class TokensTest {
    private fun channel(argb: Long, shift: Int) = ((argb shr shift) and 0xFF).toInt()

    private fun luminance(argb: Long): Double {
        fun linear(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * linear(channel(argb, 16)) + 0.7152 * linear(channel(argb, 8)) + 0.0722 * linear(channel(argb, 0))
    }

    private fun contrast(a: Long, b: Long): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    private val all = mapOf("light" to ThemeTokens.light, "dark" to ThemeTokens.dark, "night" to ThemeTokens.night)

    @Test
    fun `text is readable on the page and on a card in every theme`() {
        for ((name, t) in all) {
            assertTrue(contrast(t.text, t.background) >= 4.5, "$name text/background")
            assertTrue(contrast(t.text, t.surface) >= 4.5, "$name text/surface")
            assertTrue(contrast(t.textSecondary, t.surface) >= 4.5, "$name secondary/surface")
            assertTrue(contrast(t.onAccent, t.accent) >= 4.5, "$name onAccent/accent")
            assertTrue(contrast(t.onDanger, t.danger) >= 4.5, "$name onDanger/danger")
        }
    }

    @Test
    fun `Night has red and nothing else`() {
        val n = ThemeTokens.night
        for (v in listOf(n.background, n.surface, n.raised, n.border, n.text, n.textSecondary, n.accent, n.onAccent, n.danger,
            n.onDanger, n.warning, n.good, n.imageTint, n.horizonFill, n.landNear, n.landFar)) {
            assertEquals(0, channel(v, 8), "green in ${v.toString(16)}")
            assertEquals(0, channel(v, 0), "blue in ${v.toString(16)}")
        }
    }

    @Test
    fun `targets are gloved-hand sized`() {
        assertTrue(Layout.minimumTarget >= 56f)
    }
}
