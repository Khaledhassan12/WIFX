package com.wifi.toolbox.services

import com.wifi.toolbox.structs.WifiLogData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class WifiLogParserTest {

    private fun parser(clock: AtomicLong) = WifiLogParser { clock.get() }

    @Test
    fun `parses ssid and handshake failure timing`() {
        val clock = AtomicLong(1_000L)
        val parser = parser(clock)

        assertNull(parser.parse("I wpa_supplicant: Trying to associate with SSID 'MyNet'"))
        assertEquals("MyNet", parser.currentSsid)

        clock.set(1_500L)
        assertNull(parser.parse("D wpa_supplicant: WPA: RX message 1 of 4-Way Handshake from aa:bb"))

        clock.set(1_700L)
        val handshake = parser.parse("D wpa_supplicant: Sending EAPOL-Key 2/4")
        assertEquals(WifiLogData.EVENT_HANDSHAKE, handshake?.event)
        assertEquals(200, handshake?.handshakeUseTime)
        assertEquals(1, handshake?.handshakeCount)

        clock.set(2_000L)
        val connected = parser.parse("D wpa_supplicant: WPA: Key negotiation completed with aa:bb")
        assertEquals(WifiLogData.EVENT_WIFI_CONNECTED, connected?.event)
        assertEquals("MyNet", connected?.ssid)
    }

    @Test
    fun `decodes hex escaped ssid`() {
        val parser = parser(AtomicLong())
        parser.parse("Trying to associate with SSID '\\x54\\x65\\x73\\x74'")
        assertEquals("Test", parser.currentSsid)
    }

    @Test
    fun `failure and lockout events`() {
        val clock = AtomicLong(10L)
        val parser = parser(clock)
        parser.parse("Trying to associate with SSID 'Net'")

        assertEquals(
            WifiLogData.EVENT_CONNECT_FAILED,
            parser.parse("WPA: 4-Way Handshake failed - pre-shared key may be incorrect")?.event
        )
        assertEquals(
            WifiLogData.EVENT_CONNECT_ERROR,
            parser.parse("CTRL-EVENT-ASSOC-REJECT status_code=1")?.event
        )
        assertEquals(
            WifiLogData.EVENT_CONNECT_ERROR,
            parser.parse("CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"Net\"")?.event
        )
    }

    @Test
    fun `startAttempt resets handshake counters`() {
        val clock = AtomicLong(1L)
        val parser = parser(clock)
        parser.parse("Trying to associate with SSID 'Net'")
        parser.parse("Sending EAPOL-Key 2/4")

        parser.startAttempt()

        assertEquals(0, parser.handshakeCount)
        assertEquals(0L, parser.handshakeStartTime)
        assertEquals("Net", parser.currentSsid)
    }

    @Test
    fun `unrelated lines are ignored`() {
        val parser = parser(AtomicLong())
        assertNull(parser.parse("I ActivityManager: Start proc com.example"))
    }
}
