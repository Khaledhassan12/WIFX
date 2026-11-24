package com.wifi.toolbox.offline

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 802.11 / EAPOL parsing needed to pull WPA handshakes out of a capture.
 *
 * The parser is deliberately tolerant: captures come from many drivers and
 * link types, so anything that does not look like a valid frame is ignored
 * instead of throwing.
 */
object Dot11 {

    const val ETHERTYPE_EAPOL = 0x888E

    data class EapolKey(
        val apMac: ByteArray,
        val staMac: ByteArray,
        val nonce: ByteArray,
        val mic: ByteArray,
        /** EAPOL frame with the MIC field zeroed, ready for HMAC verification. */
        val eapol: ByteArray,
        val replayCounter: Long,
        val messageNumber: Int,
        val keyVersion: Int,
        val pmkid: ByteArray?
    )

    data class SsidInfo(val bssid: ByteArray, val ssid: String)

    /**
     * Extracts the SSID from beacon / probe response / association frames.
     */
    fun parseSsid(data: ByteArray, linkType: Int): SsidInfo? {
        val frame = stripLinkLayer(data, linkType) ?: return null
        if (frame.size < 24) return null
        val fc = frame[0].toInt() and 0xff
        val type = (fc ushr 2) and 0x3
        val subtype = (fc ushr 4) and 0xf
        if (type != 0) return null

        val bssid: ByteArray
        val ieOffset: Int
        when (subtype) {
            8, 5 -> { // beacon, probe response
                bssid = frame.copyOfRange(16, 22)
                ieOffset = 36
            }
            0, 2 -> { // association request, reassociation request
                bssid = frame.copyOfRange(4, 10)
                ieOffset = if (subtype == 0) 28 else 34
            }
            else -> return null
        }
        if (frame.size <= ieOffset) return null

        val ssid = findIe(frame, ieOffset, 0) ?: return null
        if (ssid.isEmpty() || ssid.size > 32) return null
        return SsidInfo(bssid, String(ssid, Charsets.UTF_8))
    }

    data class RsnPmkid(
        val bssid: ByteArray,
        val sta: ByteArray,
        val pmkids: List<ByteArray>
    )

    /** PMKID list advertised in an RSN information element (association frames). */
    fun parseRsnPmkids(data: ByteArray, linkType: Int): RsnPmkid? {
        val frame = stripLinkLayer(data, linkType) ?: return null
        if (frame.size < 24) return null
        val fc = frame[0].toInt() and 0xff
        val type = (fc ushr 2) and 0x3
        val subtype = (fc ushr 4) and 0xf
        if (type != 0 || subtype !in intArrayOf(0, 2)) return null
        val bssid = frame.copyOfRange(4, 10)
        val sta = frame.copyOfRange(10, 16)
        val ieOffset = if (subtype == 0) 28 else 34
        val rsn = findIe(frame, ieOffset, 48) ?: return null

        var p = 2 + 4 // version + group cipher
        if (p + 2 > rsn.size) return null
        val pairwiseCount = u16(rsn, p)
        p += 2 + pairwiseCount * 4
        if (p + 2 > rsn.size) return null
        val akmCount = u16(rsn, p)
        p += 2 + akmCount * 4
        p += 2 // RSN capabilities
        if (p + 2 > rsn.size) return null
        val pmkidCount = u16(rsn, p)
        p += 2
        val list = ArrayList<ByteArray>()
        repeat(pmkidCount) {
            if (p + 16 > rsn.size) return@repeat
            list.add(rsn.copyOfRange(p, p + 16))
            p += 16
        }
        return if (list.isEmpty()) null else RsnPmkid(bssid, sta, list)
    }

    /** Parses an EAPOL-Key frame (1/4 .. 4/4), or null for anything else. */
    fun parseEapolKey(data: ByteArray, linkType: Int): EapolKey? {
        val frame = stripLinkLayer(data, linkType) ?: return null
        val eapolOffset = eapolOffset(frame, linkType) ?: return null
        if (frame.size < eapolOffset + 99) return null
        if ((frame[eapolOffset + 1].toInt() and 0xff) != 3) return null // EAPOL-Key
        val length = u16(frame, eapolOffset + 2)
        if (length < 95 || eapolOffset + 4 + length > frame.size) return null
        if ((frame[eapolOffset + 4].toInt() and 0xff) != 2) return null // RSN descriptor

        val eapolLength = 4 + length
        val eapol = frame.copyOfRange(eapolOffset, eapolOffset + eapolLength)
        val keyInfo = u16(eapol, 5)
        val replay = ByteBuffer.wrap(eapol, 9, 8).order(ByteOrder.BIG_ENDIAN).long
        val nonce = eapol.copyOfRange(17, 49)
        val mic = eapol.copyOfRange(81, 97)
        val keyVersion = keyInfo and 0x7

        val ack = (keyInfo ushr 7) and 1 == 1
        val micFlag = (keyInfo ushr 8) and 1 == 1
        val secure = (keyInfo ushr 9) and 1 == 1
        val messageNumber = when {
            ack && !micFlag -> 1
            !ack && micFlag && !secure -> 2
            ack && micFlag -> 3
            !ack && micFlag && secure -> 4
            else -> 0
        }
        if (messageNumber == 0) return null

        // MAC direction: M1/M3 come from the AP, M2/M4 from the station.
        val src: ByteArray
        val dst: ByteArray
        when (linkType) {
            PcapReader.LINKTYPE_ETHERNET -> {
                dst = frame.copyOfRange(0, 6)
                src = frame.copyOfRange(6, 12)
            }

            PcapReader.LINKTYPE_LINUX_SLL -> {
                dst = ByteArray(6)
                src = frame.copyOfRange(6, 12)
            }

            PcapReader.LINKTYPE_LINUX_SLL2 -> {
                dst = ByteArray(6)
                src = frame.copyOfRange(12, 18)
            }

            else -> {
                // 802.11: addr1 = receiver, addr2 = transmitter
                dst = frame.copyOfRange(4, 10)
                src = frame.copyOfRange(10, 16)
            }
        }
        val fromAp = messageNumber == 1 || messageNumber == 3
        val apMac = if (fromAp) src else dst
        val staMac = if (fromAp) dst else src

        // Zero the MIC so the frame can be fed straight into HMAC-SHA1.
        java.util.Arrays.fill(eapol, 81, 97, 0)

        val keyDataLength = u16(eapol, 97)
        val pmkid = if (messageNumber == 1 && keyDataLength > 0 && 99 + keyDataLength <= eapol.size) {
            findPmkidKde(eapol, 99, 99 + keyDataLength)
        } else null

        return EapolKey(
            apMac = apMac,
            staMac = staMac,
            nonce = nonce,
            mic = mic,
            eapol = eapol,
            replayCounter = replay,
            messageNumber = messageNumber,
            keyVersion = keyVersion,
            pmkid = pmkid
        )
    }

    // ------------------------------------------------------------- helpers ---

    private fun stripLinkLayer(data: ByteArray, linkType: Int): ByteArray? = when (linkType) {
        PcapReader.LINKTYPE_ETHERNET,
        PcapReader.LINKTYPE_NULL,
        PcapReader.LINKTYPE_LINUX_SLL,
        PcapReader.LINKTYPE_LINUX_SLL2 -> data

        PcapReader.LINKTYPE_IEEE802_11 -> data

        PcapReader.LINKTYPE_IEEE802_11_RADIOTAP -> {
            if (data.size < 4) null
            else {
                // radiotap length is little endian
                val headerLength = u16le(data, 2)
                if (headerLength <= 0 || headerLength >= data.size) null
                else data.copyOfRange(headerLength, data.size)
            }
        }

        PcapReader.LINKTYPE_PPI -> {
            if (data.size < 8) null
            else {
                val headerLength = u16le(data, 2)
                if (headerLength <= 0 || headerLength >= data.size) null
                else data.copyOfRange(headerLength, data.size)
            }
        }

        PcapReader.LINKTYPE_IEEE802_11_AVS -> {
            // 64 byte AVS header on most drivers
            if (data.size <= 64) null else data.copyOfRange(64, data.size)
        }

        else -> null
    }

    /** Offset of the EAPOL frame inside [frame]. */
    private fun eapolOffset(frame: ByteArray, linkType: Int): Int? = when (linkType) {
        PcapReader.LINKTYPE_ETHERNET -> {
            if (frame.size < 14) null
            else {
                val etherType = u16(frame, 12)
                when {
                    etherType == ETHERTYPE_EAPOL -> 14
                    etherType == 0x8100 && frame.size >= 18 &&
                        u16(frame, 16) == ETHERTYPE_EAPOL -> 18
                    else -> null
                }
            }
        }

        PcapReader.LINKTYPE_LINUX_SLL -> {
            if (frame.size < 16 || u16(frame, 14) != ETHERTYPE_EAPOL) null else 16
        }

        PcapReader.LINKTYPE_LINUX_SLL2 -> {
            if (frame.size < 20 || u16(frame, 0) != ETHERTYPE_EAPOL) null else 20
        }

        PcapReader.LINKTYPE_NULL -> null

        PcapReader.LINKTYPE_IEEE802_11,
        PcapReader.LINKTYPE_IEEE802_11_RADIOTAP,
        PcapReader.LINKTYPE_PPI,
        PcapReader.LINKTYPE_IEEE802_11_AVS -> {
            val headerLength = macHeaderLength(frame)
            if (headerLength == null || frame.size < headerLength + 8) null
            else {
                val snapOk = frame[headerLength].toInt() == 0xAA.toByte().toInt() &&
                    frame[headerLength + 1].toInt() == 0xAA.toByte().toInt() &&
                    frame[headerLength + 2].toInt() == 0x03.toByte().toInt() &&
                    u16(frame, headerLength + 6) == ETHERTYPE_EAPOL
                if (snapOk) headerLength + 8 else null
            }
        }

        else -> null
    }

    private fun macHeaderLength(frame: ByteArray): Int? {
        if (frame.size < 24) return null
        val fc = frame[0].toInt() and 0xff
        val type = (fc ushr 2) and 0x3
        if (type != 2) return null // not a data frame
        val toDs = (fc and 0x01) == 1
        val fromDs = (fc and 0x02) == 2
        var length = 24
        if (toDs && fromDs) length += 6
        if ((fc ushr 4) and 0xf == 0xc) length += 8 // QoS data
        if (length > frame.size) return null
        return length
    }

    private fun findIe(frame: ByteArray, start: Int, targetId: Int): ByteArray? {
        var p = start
        while (p + 2 <= frame.size) {
            val id = frame[p].toInt() and 0xff
            val len = frame[p + 1].toInt() and 0xff
            if (p + 2 + len > frame.size) return null
            if (id == targetId) return frame.copyOfRange(p + 2, p + 2 + len)
            p += 2 + len
        }
        return null
    }

    private fun findPmkidKde(data: ByteArray, start: Int, end: Int): ByteArray? {
        var p = start
        while (p + 2 <= end) {
            val id = data[p].toInt() and 0xff
            val len = data[p + 1].toInt() and 0xff
            if (p + 2 + len > end) return null
            if (id == 0xdd && len >= 20 &&
                data[p + 2].toInt() == 0x00 &&
                data[p + 3].toInt() == 0x0f &&
                data[p + 4].toInt() == 0xac.toByte().toInt() &&
                data[p + 5].toInt() == 0x04
            ) {
                return data.copyOfRange(p + 6, p + 22)
            }
            p += 2 + len
        }
        return null
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
}
