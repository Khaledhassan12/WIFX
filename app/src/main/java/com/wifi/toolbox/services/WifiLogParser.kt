package com.wifi.toolbox.services

import com.wifi.toolbox.structs.WifiLogData

/**
 * logcat parser.
 *
 * Pure logic extracted from [WifiLogcatService]; no Android dependencies, easy to unit test.
 * One instance covers a run (many attempts); call [startAttempt] before each attempt to reset handshake state.
 */
class WifiLogParser(
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val ssidPattern = Regex("SSID '(.*?)'")

    var currentSsid: String? = null
        private set

    var connectStartTime: Long = 0L
        private set

    var handshakeStartTime: Long = 0L
        private set

    var handshakeCount: Int = 0
        private set

    /** New attempt: clears handshake timers and counters, keeps SSID/start time */
    fun startAttempt() {
        handshakeStartTime = 0L
        handshakeCount = 0
    }

    fun reset() {
        currentSsid = null
        connectStartTime = 0L
        startAttempt()
    }

    /**
     * Parses one log line.
     * @return Event to report, or null
     */
    fun parse(line: String): WifiLogData? = when {
        line.contains(KEY_ASSOCIATE) -> {
            ssidPattern.find(line)?.let { match ->
                currentSsid = decodeHexSsid(match.groupValues[1])
                connectStartTime = clock()
                startAttempt()
            }
            null
        }

        line.contains(KEY_ENABLE_NETWORK) -> {
            connectStartTime = clock()
            startAttempt()
            null
        }

        line.contains(KEY_HANDSHAKE_START) -> {
            if (handshakeStartTime == 0L) handshakeStartTime = clock()
            null
        }

        line.contains(KEY_HANDSHAKE_OK) -> event(WifiLogData.EVENT_WIFI_CONNECTED)

        line.contains(KEY_HANDSHAKE_SEND) -> {
            handshakeCount++
            val useTime = if (handshakeStartTime > 0L) {
                (clock() - handshakeStartTime).toInt()
            } else 0
            WifiLogData(
                WifiLogData.EVENT_HANDSHAKE,
                connectStartTime,
                currentSsid,
                useTime,
                handshakeCount
            )
        }

        line.contains(KEY_HANDSHAKE_FAILED) -> event(WifiLogData.EVENT_CONNECT_FAILED)

        // Router rejected the association / temporarily blocked the SSID: retryable
        line.contains(KEY_ASSOC_REJECT) ||
            line.contains(KEY_SSID_TEMP_DISABLED) -> event(WifiLogData.EVENT_CONNECT_ERROR)

        else -> null
    }

    private fun event(eventId: Int) = WifiLogData(
        eventId,
        connectStartTime,
        currentSsid
    )

    private fun decodeHexSsid(hexSsid: String): String {
        if (!hexSsid.contains("\\x")) return hexSsid
        return try {
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < hexSsid.length) {
                if (hexSsid[i] == '\\' && i + 3 < hexSsid.length && hexSsid[i + 1] == 'x') {
                    val hex = hexSsid.substring(i + 2, i + 4)
                    bytes.write(hex.toInt(16))
                    i += 4
                } else {
                    bytes.write(hexSsid[i].code)
                    i++
                }
            }
            bytes.toString("UTF-8")
        } catch (_: Exception) {
            hexSsid
        }
    }

    private companion object {
        const val KEY_ASSOCIATE = "Trying to associate with SSID"
        const val KEY_ENABLE_NETWORK = "WifiService: enableNetwork"
        const val KEY_HANDSHAKE_START = "WPA: RX message 1 of 4-Way Handshake from"
        const val KEY_HANDSHAKE_OK = "WPA: Key negotiation completed with"
        const val KEY_HANDSHAKE_SEND = "Sending EAPOL-Key 2/4"
        const val KEY_HANDSHAKE_FAILED = "WPA: 4-Way Handshake failed"
        const val KEY_ASSOC_REJECT = "CTRL-EVENT-ASSOC-REJECT"
        const val KEY_SSID_TEMP_DISABLED = "CTRL-EVENT-SSID-TEMP-DISABLED"
    }
}
