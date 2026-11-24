package com.wifi.toolbox.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyAdvisorTest {

    @Test
    fun `open network needs no password list`() {
        val advice = StrategyAdvisor.advise(
            StrategyAdvisor.profileFromCapabilities("[ESS]")
        )
        assertEquals(StrategyAdvisor.Mode.OPEN, advice.mode)
        assertTrue(advice.notes.contains(StrategyAdvisor.Note.OPEN_NETWORK))
    }

    @Test
    fun `wpa2 psk uses the fast failure flag`() {
        val advice = StrategyAdvisor.advise(
            StrategyAdvisor.profileFromCapabilities("[WPA2-PSK-CCMP][ESS]", -50)
        )
        assertEquals(StrategyAdvisor.Mode.ONLINE, advice.mode)
        assertEquals(2, advice.failureFlag)
        assertTrue(advice.notes.contains(StrategyAdvisor.Note.FAST_PROFILE))
        assertTrue(advice.preferLongPasswords)
    }

    @Test
    fun `wpa3 only is unsupported`() {
        val advice = StrategyAdvisor.advise(
            StrategyAdvisor.profileFromCapabilities("[RSN-SAE-CCMP][ESS]")
        )
        assertEquals(StrategyAdvisor.Mode.UNSUPPORTED, advice.mode)
        assertTrue(advice.notes.contains(StrategyAdvisor.Note.WPA3_SAE_SLOW))
    }

    @Test
    fun `wpa3 transition keeps online with longer timeouts`() {
        val advice = StrategyAdvisor.advise(
            StrategyAdvisor.profileFromCapabilities("[RSN-SAE-CCMP][RSN-PSK-CCMP][ESS]"),
            currentMaxTryTime = 5000,
            currentTimeout = 1500
        )
        assertEquals(StrategyAdvisor.Mode.ONLINE, advice.mode)
        assertEquals(8000, advice.maxTryTime)
        assertEquals(2500, advice.timeout)
        assertTrue(advice.notes.contains(StrategyAdvisor.Note.WPA3_TRANSITION))
    }

    @Test
    fun `weak signal raises the timeout`() {
        val advice = StrategyAdvisor.advise(
            StrategyAdvisor.profileFromCapabilities("[WPA2-PSK-CCMP][ESS]", -85)
        )
        assertTrue(advice.timeout >= 2000)
        assertTrue(advice.notes.contains(StrategyAdvisor.Note.WEAK_SIGNAL))
    }

    @Test
    fun `enterprise is rejected`() {
        val advice = StrategyAdvisor.advise(
            StrategyAdvisor.profileFromCapabilities("[WPA2-EAP-CCMP][ESS]")
        )
        assertEquals(StrategyAdvisor.Mode.UNSUPPORTED, advice.mode)
        assertTrue(advice.notes.contains(StrategyAdvisor.Note.ENTERPRISE_UNSUPPORTED))
    }

    @Test
    fun `wep is marked legacy`() {
        val advice = StrategyAdvisor.advise(
            StrategyAdvisor.profileFromCapabilities("[WEP][ESS]")
        )
        assertTrue(advice.notes.contains(StrategyAdvisor.Note.WEP_LEGACY))
        assertFalse(advice.mode == StrategyAdvisor.Mode.UNSUPPORTED)
    }

    @Test
    fun `old wpa prefers numeric defaults`() {
        val advice = StrategyAdvisor.advise(
            StrategyAdvisor.profileFromCapabilities("[WPA-PSK-TKIP][ESS]")
        )
        assertTrue(advice.preferNumeric)
    }
}
