package com.wifi.toolbox.offline

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds synthetic 802.11 captures so the offline parser can be tested without
 * shipping a real (and legally sensitive) capture file in the repository.
 */
object TestCaptureBuilder {

    val AP = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)
    val STA = byteArrayOf(
        0x02,
        0xaa.toByte(),
        0xbb.toByte(),
        0xcc.toByte(),
        0xdd.toByte(),
        0xee.toByte()
    )
    val ANONCE = ByteArray(32) { (it + 1).toByte() }
    val SNONCE = ByteArray(32) { (it + 101).toByte() }
    const val SSID = "TestNet"
    const val PASSWORD = "12345678"

    fun pmk(password: String = PASSWORD, ssid: String = SSID): ByteArray =
        Crypto.pbkdf2Sha1(password, ssid.toByteArray(Charsets.UTF_8))

    fun pmkid(password: String = PASSWORD): ByteArray {
        val pmk = pmk(password)
        val message = ByteArray(20)
        System.arraycopy("PMK Name".toByteArray(Charsets.US_ASCII), 0, message, 0, 8)
        System.arraycopy(AP, 0, message, 8, 6)
        System.arraycopy(STA, 0, message, 14, 6)
        return Crypto.hmacSha1(pmk, message).copyOfRange(0, 16)
    }

    /** Full 4-way M2 frame (radiotap + 802.11 + LLC + EAPOL) for [password]. */
    fun m2Frame(password: String = PASSWORD, keyVersion: Int = 2): ByteArray {
        val pmk = pmk(password)
        val data = ByteArray(76)
        val firstMac = if (compare(AP, STA) <= 0) AP else STA
        val secondMac = if (firstMac === AP) STA else AP
        System.arraycopy(firstMac, 0, data, 0, 6)
        System.arraycopy(secondMac, 0, data, 6, 6)
        val firstNonce = if (compare(ANONCE, SNONCE) <= 0) ANONCE else SNONCE
        val secondNonce = if (firstNonce === ANONCE) SNONCE else ANONCE
        System.arraycopy(firstNonce, 0, data, 12, 32)
        System.arraycopy(secondNonce, 0, data, 44, 32)
        val ptk = Crypto.prf(
            pmk,
            "Pairwise key expansion",
            data,
            if (keyVersion == 1) 64 else 48
        )
        val eapol = eapolKey(
            messageNumber = 2,
            nonce = SNONCE,
            mic = ByteArray(16),
            replay = 7L,
            keyVersion = keyVersion
        )
        val mic = Crypto.hmacSha1(ptk.copyOfRange(0, 16), eapol).copyOfRange(0, 16)
        val withMic = eapolKey(
            messageNumber = 2,
            nonce = SNONCE,
            mic = mic,
            replay = 7L,
            keyVersion = keyVersion
        )
        return dataFrame(withMic)
    }

    fun m1Frame(withPmkid: Boolean = false, keyVersion: Int = 2): ByteArray {
        val keyData = if (withPmkid) {
            byteArrayOf(
                0xdd.toByte(), 0x14, 0x00, 0x0f, 0xac.toByte(), 0x04
            ) + pmkid()
        } else ByteArray(0)
        return dataFrame(
            eapolKey(
                messageNumber = 1,
                nonce = ANONCE,
                mic = ByteArray(16),
                replay = 7L,
                keyVersion = keyVersion,
                keyData = keyData
            ),
            fromAp = true
        )
    }

    fun beaconFrame(ssid: String = SSID): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(0x80, broadcast, AP, AP))
        out.write(ByteArray(8)) // timestamp
        out.write(byteArrayOf(0x64, 0x00)) // beacon interval
        out.write(byteArrayOf(0x11, 0x04)) // capability
        val name = ssid.toByteArray(Charsets.UTF_8)
        out.write(byteArrayOf(0x00, name.size.toByte()))
        out.write(name)
        out.write(byteArrayOf(0x01, 0x04, 0x82.toByte(), 0x84.toByte(), 0x8b.toByte(), 0x96.toByte()))
        return radiotap(out.toByteArray())
    }

    fun eapolKey(
        messageNumber: Int,
        nonce: ByteArray,
        mic: ByteArray,
        replay: Long,
        keyVersion: Int,
        keyData: ByteArray = ByteArray(0)
    ): ByteArray {
        val keyInfo = when (messageNumber) {
            1 -> 0x0080 or 0x0008 or keyVersion
            2 -> 0x0100 or 0x0008 or keyVersion
            3 -> 0x0140 or 0x0008 or keyVersion
            else -> 0x0100 or 0x0008 or 0x0200 or keyVersion
        }
        val eapol = ByteArray(99 + keyData.size)
        eapol[0] = 2
        eapol[1] = 3
        eapol[2] = ((95 + keyData.size) ushr 8).toByte()
        eapol[3] = ((95 + keyData.size) and 0xff).toByte()
        eapol[4] = 2
        eapol[5] = (keyInfo ushr 8).toByte()
        eapol[6] = (keyInfo and 0xff).toByte()
        ByteBuffer.wrap(eapol, 9, 8).order(ByteOrder.BIG_ENDIAN).putLong(replay)
        System.arraycopy(nonce, 0, eapol, 17, 32)
        System.arraycopy(mic, 0, eapol, 81, 16)
        eapol[97] = (keyData.size ushr 8).toByte()
        eapol[98] = (keyData.size and 0xff).toByte()
        System.arraycopy(keyData, 0, eapol, 99, keyData.size)
        return eapol
    }

    fun dataFrame(eapol: ByteArray, fromAp: Boolean = false): ByteArray {
        val fc = if (fromAp) 0x0208 else 0x0108
        val out = ByteArrayOutputStream()
        val addr1 = if (fromAp) STA else AP
        val addr2 = if (fromAp) AP else STA
        val addr3 = if (fromAp) STA else AP
        out.write(header(fc, addr1, addr2, addr3))
        out.write(byteArrayOf(0xaa.toByte(), 0xaa.toByte(), 0x03, 0, 0, 0, 0x88.toByte(), 0x8e.toByte()))
        out.write(eapol)
        return radiotap(out.toByteArray())
    }

    private fun header(fc: Int, addr1: ByteArray, addr2: ByteArray, addr3: ByteArray): ByteArray {
        val header = ByteArray(24)
        header[0] = (fc and 0xff).toByte()
        header[1] = ((fc ushr 8) and 0xff).toByte()
        System.arraycopy(addr1, 0, header, 4, 6)
        System.arraycopy(addr2, 0, header, 10, 6)
        System.arraycopy(addr3, 0, header, 16, 6)
        return header
    }

    private fun radiotap(frame: ByteArray): ByteArray {
        // 8 byte radiotap header (version 0, length 8)
        val rt = byteArrayOf(0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)
        return rt + frame
    }

    /** Classic little-endian pcap file with the given packets. */
    fun pcap(packets: List<ByteArray>, linkType: Int = PcapReader.LINKTYPE_IEEE802_11_RADIOTAP): ByteArray {
        val out = ByteArrayOutputStream()
        val global = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        global.putInt(0xA1B2C3D4.toInt())
        global.putShort(2)
        global.putShort(4)
        global.putInt(0)
        global.putInt(0)
        global.putInt(65535)
        global.putInt(linkType)
        out.write(global.array())
        var ts = 1_700_000_000L
        packets.forEach { packet ->
            val record = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            record.putInt(ts.toInt())
            record.putInt(0)
            record.putInt(packet.size)
            record.putInt(packet.size)
            out.write(record.array())
            out.write(packet)
            ts++
        }
        return out.toByteArray()
    }

    private val broadcast = ByteArray(6) { 0xff.toByte() }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return a.size - b.size
    }
}
