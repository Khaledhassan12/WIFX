package com.wifi.toolbox.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskGeneratorTest {

    @Test
    fun `counts digit masks`() {
        assertEquals(100_000_000L, MaskGenerator.count("?d?d?d?d?d?d?d?d"))
        assertEquals(26L * 26 * 10, MaskGenerator.count("?l?l?d"))
        assertEquals(1L, MaskGenerator.count("test"))
    }

    @Test
    fun `generates in hashcat order`() {
        val values = MaskGenerator.sequence("a?d").toList()
        assertEquals(listOf("a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9"), values)
    }

    @Test
    fun `custom charsets and literal question mark`() {
        val mask = MaskGenerator.parse("?1?1??", customCharsets = listOf("xy"))
        assertEquals(listOf("xx?", "xy?", "yx?", "yy?"), MaskGenerator.sequence(mask).toList())
    }

    @Test
    fun `rejects invalid masks`() {
        assertThrows(MaskGenerator.MaskException::class.java) { MaskGenerator.parse("") }
        assertThrows(MaskGenerator.MaskException::class.java) { MaskGenerator.parse("abc?") }
        assertThrows(MaskGenerator.MaskException::class.java) { MaskGenerator.parse("?z") }
        assertThrows(MaskGenerator.MaskException::class.java) { MaskGenerator.parse("?1") }
    }

    @Test
    fun `presets include ssid based masks`() {
        val presets = MaskGenerator.presets("TP-LINK_5G")
        assertTrue(presets.any { it.mask.contains("link") })
        assertTrue(presets.any { it.label == "8 digits" })
    }

    @Test
    fun `format count is readable`() {
        assertEquals("100.0M", MaskGenerator.formatCount(100_000_000))
        assertEquals("1.5K", MaskGenerator.formatCount(1500))
        assertFalse(MaskGenerator.formatCount(10).contains("K"))
    }
}
