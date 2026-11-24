package com.wifi.toolbox.offline

/**
 * Collects WPA handshakes while a capture is streamed through [feed].
 *
 * The extractor is stateful on purpose: a capture can contain dozens of
 * networks and several re-associations of the same client, and we want the
 * best (first complete) handshake for each AP/client pair.
 */
class HandshakeExtractor {

    private data class M1(
        val apMac: ByteArray,
        val staMac: ByteArray,
        val anonce: ByteArray,
        val replayCounter: Long,
        val keyVersion: Int,
        val pmkid: ByteArray?
    )

    private data class PmkidRecord(
        val apMac: ByteArray,
        val staMac: ByteArray,
        val pmkid: ByteArray
    )

    private val ssidByBssid = HashMap<String, String>()
    private val m1ByPair = HashMap<String, M1>()
    private val pmkidByPair = HashMap<String, PmkidRecord>()
    private val fourWayByPair = HashMap<String, WpaHandshake>()

    var packetCount: Int = 0
        private set

    var handshakeCount: Int = 0
        private set

    /** @return true when this packet completed a new handshake */
    fun feed(packet: PcapReader.Packet): Boolean {
        packetCount++
        var found = false

        Dot11.parseSsid(packet.data, packet.linkType)?.let { info ->
            val key = Crypto.toHex(info.bssid)
            if (key !in ssidByBssid) ssidByBssid[key] = info.ssid
        }

        Dot11.parseRsnPmkids(packet.data, packet.linkType)?.let { info ->
            val key = Crypto.toHex(info.bssid) + "|" + Crypto.toHex(info.sta)
            info.pmkids.forEach { pmkid ->
                pmkidByPair.putIfAbsent(key, PmkidRecord(info.bssid, info.sta, pmkid))
            }
        }

        val key = Dot11.parseEapolKey(packet.data, packet.linkType) ?: return found
        val pairKey = Crypto.toHex(key.apMac) + "|" + Crypto.toHex(key.staMac)

        when (key.messageNumber) {
            1 -> {
                m1ByPair[pairKey] = M1(
                    apMac = key.apMac,
                    staMac = key.staMac,
                    anonce = key.nonce,
                    replayCounter = key.replayCounter,
                    keyVersion = key.keyVersion,
                    pmkid = key.pmkid
                )
                if (key.pmkid != null) {
                    pmkidByPair[pairKey] = PmkidRecord(key.apMac, key.staMac, key.pmkid)
                }
            }

            2 -> {
                val m1 = m1ByPair[pairKey] ?: return found
                if (m1.replayCounter != key.replayCounter) return found
                val existing = fourWayByPair[pairKey]
                if (existing != null) return found

                val handshake = build(
                    ssid = ssidByBssid[Crypto.toHex(key.apMac)].orEmpty(),
                    apMac = key.apMac,
                    staMac = key.staMac,
                    anonce = m1.anonce,
                    snonce = key.nonce,
                    mic = key.mic,
                    eapol = key.eapol,
                    keyVersion = m1.keyVersion,
                    replayCounter = key.replayCounter,
                    pmkid = m1.pmkid ?: pmkidByPair[pairKey]?.pmkid,
                    hasFourWay = true,
                    hasPmkid = (m1.pmkid ?: pmkidByPair[pairKey]?.pmkid) != null
                )
                fourWayByPair[pairKey] = handshake
                handshakeCount++
                found = true
            }
        }
        return found
    }

    /**
     * Final list, with SSIDs resolved from any beacon seen later in the capture.
     */
    fun handshakes(): List<WpaHandshake> {
        val result = ArrayList<WpaHandshake>()
        val seen = HashSet<String>()
        val apsWithFourWay = HashSet<String>()

        fourWayByPair.forEach { (pairKey, handshake) ->
            val ssid = handshake.ssid.ifEmpty {
                ssidByBssid[Crypto.toHex(Crypto.macToBytes(handshake.apMac))].orEmpty()
            }
            if (ssid.isEmpty()) return@forEach
            if (!seen.add(pairKey)) return@forEach
            apsWithFourWay.add(handshake.apMac)
            result.add(handshake.copy(ssid = ssid))
        }

        // PMKID-only records (message 1 without a matching message 2)
        pmkidByPair.forEach { (pairKey, record) ->
            if (fourWayByPair.containsKey(pairKey)) return@forEach
            if (!seen.add(pairKey)) return@forEach
            val ssid = ssidByBssid[Crypto.toHex(record.apMac)].orEmpty()
            if (ssid.isEmpty()) return@forEach
            // One record per AP is enough; a 4-way handshake for the same AP
            // is always preferred over a lone PMKID.
            if (Crypto.toMac(record.apMac) in apsWithFourWay) return@forEach
            result.add(
                build(
                    ssid = ssid,
                    apMac = record.apMac,
                    staMac = record.staMac,
                    anonce = ByteArray(32),
                    snonce = ByteArray(32),
                    mic = ByteArray(16),
                    eapol = ByteArray(0),
                    keyVersion = 2,
                    replayCounter = 0,
                    pmkid = record.pmkid,
                    hasFourWay = false,
                    hasPmkid = true
                )
            )
        }

        return result.sortedWith(
            compareByDescending<WpaHandshake> { it.hasFourWay }
                .thenByDescending { it.hasPmkid }
                .thenBy { it.ssid }
        )
    }

    private fun build(
        ssid: String,
        apMac: ByteArray,
        staMac: ByteArray,
        anonce: ByteArray,
        snonce: ByteArray,
        mic: ByteArray,
        eapol: ByteArray,
        keyVersion: Int,
        replayCounter: Long,
        pmkid: ByteArray?,
        hasFourWay: Boolean,
        hasPmkid: Boolean
    ) = WpaHandshake(
        ssid = ssid,
        apMac = Crypto.toMac(apMac),
        staMac = Crypto.toMac(staMac),
        anonce = anonce,
        snonce = snonce,
        mic = mic,
        eapol = eapol,
        keyVersion = keyVersion,
        replayCounter = replayCounter,
        pmkid = pmkid,
        hasFourWay = hasFourWay,
        hasPmkid = hasPmkid,
        pmkidApMac = if (hasPmkid) apMac else null,
        pmkidStaMac = if (hasPmkid) staMac else null
    )

    companion object {
        /** Convenience helper used by tests and by the import screen. */
        fun extract(packets: List<PcapReader.Packet>): List<WpaHandshake> =
            HandshakeExtractor().apply { packets.forEach { feed(it) } }.handshakes()
    }
}
