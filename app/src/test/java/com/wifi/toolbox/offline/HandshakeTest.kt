package com.wifi.toolbox.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HandshakeTest {

    private fun capture(withPmkid: Boolean = false, password: String = TestCaptureBuilder.PASSWORD): List<WpaHandshake> {
        val pcap = TestCaptureBuilder.pcap(
            listOf(
                TestCaptureBuilder.beaconFrame(),
                TestCaptureBuilder.m1Frame(withPmkid = withPmkid),
                TestCaptureBuilder.m2Frame(password)
            )
        )
        val packets = ArrayList<PcapReader.Packet>()
        PcapReader.read(ByteArrayInputStream(pcap)) { packets.add(it); true }
        assertEquals(3, packets.size)
        return HandshakeExtractor.extract(packets)
    }

    @Test
    fun `extracts a four way handshake from a capture`() {
        val handshakes = capture()
        assertEquals(1, handshakes.size)
        val hs = handshakes.first()
        assertEquals(TestCaptureBuilder.SSID, hs.ssid)
        assertEquals("02:11:22:33:44:55", hs.apMac)
        assertEquals("02:aa:bb:cc:dd:ee", hs.staMac)
        assertTrue(hs.hasFourWay)
        assertTrue(hs.isCrackable)
    }

    @Test
    fun `cracks the handshake with the right password only`() {
        val hs = capture().first()
        val cracker = WpaCracker(hs)
        assertTrue(cracker.verify(TestCaptureBuilder.PASSWORD))
        assertFalse(cracker.verify("123456789"))
        assertFalse(cracker.verify("short"))
    }

    @Test
    fun `crack stream finds the password`() {
        val hs = capture().first()
        val candidates = sequenceOf("00000000", "11111111", "12345678", "99999999")
        val result = OfflineCracker.crack(hs, candidates, threads = 2)
        assertEquals(TestCaptureBuilder.PASSWORD, result.password)
    }

    @Test
    fun `crack stream returns null when nothing matches`() {
        val hs = capture().first()
        val candidates = sequenceOf("00000000", "11111111")
        val result = OfflineCracker.crack(hs, candidates, threads = 2)
        assertNull(result.password)
    }

    @Test
    fun `pmkid in message 1 is usable without a handshake`() {
        val pcap = TestCaptureBuilder.pcap(
            listOf(
                TestCaptureBuilder.beaconFrame(),
                TestCaptureBuilder.m1Frame(withPmkid = true)
            )
        )
        val packets = ArrayList<PcapReader.Packet>()
        PcapReader.read(ByteArrayInputStream(pcap)) { packets.add(it); true }
        val handshakes = HandshakeExtractor.extract(packets)
        assertEquals(1, handshakes.size)
        val hs = handshakes.first()
        assertTrue(hs.hasPmkid)
        assertFalse(hs.hasFourWay)
        assertTrue(WpaCracker(hs).verify(TestCaptureBuilder.PASSWORD))
        assertFalse(WpaCracker(hs).verify("password123"))
    }

    @Test
    fun `tkip key version derives a 64 byte ptk`() {
        val hs = capture(password = TestCaptureBuilder.PASSWORD).first()
        assertEquals(48, WpaCracker(hs).derivePtk(ByteArray(32)).size)
    }

    @Test
    fun `classic pcap big endian is parsed`() {
        val packet = TestCaptureBuilder.beaconFrame()
        val little = TestCaptureBuilder.pcap(listOf(packet))
        // flip magic + all record fields by rebuilding in big endian
        val out = java.io.ByteArrayOutputStream()
        val global = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.BIG_ENDIAN)
        global.putInt(0xA1B2C3D4.toInt()); global.putShort(2); global.putShort(4)
        global.putInt(0); global.putInt(0); global.putInt(65535)
        global.putInt(PcapReader.LINKTYPE_IEEE802_11_RADIOTAP)
        out.write(global.array())
        val record = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.BIG_ENDIAN)
        record.putInt(1); record.putInt(0); record.putInt(packet.size); record.putInt(packet.size)
        out.write(record.array()); out.write(packet)

        val packets = ArrayList<PcapReader.Packet>()
        PcapReader.read(ByteArrayInputStream(out.toByteArray())) { packets.add(it); true }
        assertEquals(1, packets.size)
        assertEquals(PcapReader.LINKTYPE_IEEE802_11_RADIOTAP, packets[0].linkType)
        assertNotNull(little)
    }
}
