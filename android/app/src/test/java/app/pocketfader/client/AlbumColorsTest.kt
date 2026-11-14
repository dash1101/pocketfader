package app.pocketfader.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Album colours: the tones the app takes from a cover (the same arithmetic as Refine's). */
class AlbumColorsTest {
    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
    }
    private fun contrast(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test
    fun hslIsCss() {
        assertEquals(0xFFFF0000.toInt(), hslArgb(0f, 100f, 50f))
        assertEquals(0xFF008000.toInt(), hslArgb(120f, 100f, 25f))
        assertEquals(0xFFFFFFFF.toInt(), hslArgb(200f, 30f, 100f))
        assertEquals(0xFF000000.toInt(), hslArgb(200f, 30f, 0f))
        assertEquals(hslArgb(10f, 50f, 50f), hslArgb(370f, 50f, 50f))     // the hue wraps
        assertEquals(hslArgb(350f, 50f, 50f), hslArgb(-10f, 50f, 50f))
    }

    @Test
    fun theCoversColourIsItsMostVividHue() {
        val red = IntArray(32 * 32) { 0xFFE01010.toInt() }
        val h = dominantHue(red)
        assertNotNull(h)
        assertTrue(h!!.hue < 2f || h.hue > 358f)
        assertEquals(1f, h.vivid, 0.001f)
        // a blue spot on a grey picture: blue wins, but the picture counts as hardly colourful
        val mostlyGrey = IntArray(32 * 32) { i -> if (i < 60) 0xFF2050F0.toInt() else 0xFF808080.toInt() }
        val b = dominantHue(mostlyGrey)!!
        assertTrue(b.hue in 220f..235f)
        assertTrue(b.vivid < 0.12f)
        assertNull(dominantHue(IntArray(32 * 32) { 0xFF7F7F7F.toInt() }))   // grey: no colour at all
        assertNull(dominantHue(IntArray(16) { 0x00FF0000 }))                 // see-through pixels don't count
    }

    @Test
    fun theHueTakesTheShortWayRound() {
        assertEquals(370f, hueStep(350f, 10f), 0.001f)
        assertEquals(-10f, hueStep(10f, 350f), 0.001f)
        assertEquals(200f, hueStep(100f, 200f), 0.001f)
    }

    @Test
    fun theDarkTonesAreRefines() {
        val t = albumTones(25f, 1f, AlbumMode.Dark)
        assertEquals(hslArgb(25f, 78f, 76f), t.primary)          // Refine's accent
        assertEquals(hslArgb(25f, 62f, 15f), t.onPrimary)
        assertEquals(hslArgb(25f, 42f, 27f), t.primaryContainer)
        assertEquals(hslArgb(25f, 14f, 7f), t.background)
        assertEquals(hslArgb(25f, 15f, 13f), t.surfaceContainer)
        assertEquals(0xFF000000.toInt(), albumTones(25f, 1f, AlbumMode.Black).background)
        // a grey cover: nearly neutral tones
        val grey = albumTones(25f, 0.05f, AlbumMode.Dark)
        assertEquals(hslArgb(25f, 78f * 0.3f, 76f), grey.primary)
    }

    @Test
    fun everyHueStaysReadable() {
        for (mode in AlbumMode.values()) for (vivid in floatArrayOf(0f, 1f)) for (h in 0 until 360 step 5) {
            val t = albumTones(h.toFloat(), vivid, mode)
            val at = "hue $h, $mode, vivid $vivid"
            assertTrue("text $at", contrast(t.onBackground, t.background) >= 7.0)
            assertTrue("text on cards $at", contrast(t.onSurface, t.surfaceContainerHighest) >= 4.5)
            assertTrue("quiet text $at", contrast(t.onSurfaceVariant, t.surfaceContainerHigh) >= 4.5)
            assertTrue("accent buttons $at", contrast(t.onPrimary, t.primary) >= 4.5)
            assertTrue("chosen tab $at", contrast(t.onPrimaryContainer, t.primaryContainer) >= 4.5)
            assertTrue("tonal buttons $at", contrast(t.onSecondaryContainer, t.secondaryContainer) >= 4.5)
            assertTrue("accent text $at", contrast(t.primary, t.background) >= 3.0)
        }
    }
}
