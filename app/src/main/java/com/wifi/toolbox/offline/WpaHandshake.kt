package com.wifi.toolbox.offline

/**
 * A single crackable WPA/WPA2 target extracted from a capture.
 *
 * Two kinds exist and both can be present on the same record:
 *  - [hasFourWay]: a complete 4-way handshake (M1 + M2) with the MIC of M2
 *  - [hasPmkid]: a PMKID leaked in message 1 (or in an association frame)
 *
 * The PMKID variant needs a single frame, which makes it the only offline
 * attack that can be captured from the phone's own association attempt.
 */
data class WpaHandshake(
    val ssid: String,
    val apMac: String,
    val staMac: String,
    val anonce: ByteArray,
    val snonce: ByteArray,
    val mic: ByteArray,
    /** EAPOL frame of M2 with the MIC field zeroed. */
    val eapol: ByteArray,
    val keyVersion: Int,
    val replayCounter: Long,
    val pmkid: ByteArray? = null,
    val hasFourWay: Boolean = false,
    val hasPmkid: Boolean = false,
    val pmkidApMac: ByteArray? = null,
    val pmkidStaMac: ByteArray? = null,
    val source: String = ""
) {
    val displayType: String
        get() = when {
            hasFourWay && hasPmkid -> "4-way + PMKID"
            hasFourWay -> "4-way handshake"
            hasPmkid -> "PMKID"
            else -> "unknown"
        }

    val isCrackable: Boolean
        get() = ssid.isNotEmpty() && (hasFourWay || hasPmkid)

    fun summary(): String =
        "$ssid  ($displayType)  AP=$apMac  STA=$staMac"

    override fun equals(other: Any?): Boolean =
        other is WpaHandshake && other.apMac == apMac && other.staMac == staMac &&
            Crypto.toHex(other.mic) == Crypto.toHex(mic) &&
            Crypto.toHex(other.pmkid ?: ByteArray(0)) == Crypto.toHex(pmkid ?: ByteArray(0))

    override fun hashCode(): Int =
        (apMac.hashCode() * 31 + staMac.hashCode()) * 31 + Crypto.toHex(mic).hashCode()
}
