package com.wifi.toolbox.offline

/**
 * Chooses an attack plan from the security capabilities of a network.
 *
 * The online loop behaves very differently per security type, and using the
 * wrong settings wastes most of the attempts:
 *  - WPA3/SAE handshakes are slower and often run in transition mode, so a
 *    longer per-attempt timeout is required or every attempt is a false negative.
 *  - WPA2-PSK with a strong signal is the sweet spot for the fast handshake-limit
 *    failure flag.
 *  - WEP/open networks do not need a password list at all.
 *  - Enterprise (EAP) networks cannot be attacked with a PSK list.
 *
 * Pure Kotlin on purpose: the UI maps [Advice.notes] to localized strings and
 * the unit tests pin the decision table.
 */
object StrategyAdvisor {

    enum class Mode {
        /** No password needed. */
        OPEN,

        /** PSK list against the live AP (the classic loop). */
        ONLINE,

        /** Better captured and cracked offline (WPA2/PMKID friendly). */
        OFFLINE_PREFERRED,

        /** SAE / Enterprise: dictionary attacks are not applicable. */
        UNSUPPORTED
    }

    enum class Note {
        OPEN_NETWORK,
        WEP_LEGACY,
        WPA3_SAE_SLOW,
        WPA3_TRANSITION,
        ENTERPRISE_UNSUPPORTED,
        WEAK_SIGNAL,
        OFFLINE_RECOMMENDED,
        LONG_PASSWORDS_LIKELY,
        FAST_PROFILE
    }

    data class SecurityProfile(
        val open: Boolean = false,
        val wep: Boolean = false,
        val wpa: Boolean = false,
        val wpa2: Boolean = false,
        val wpa3: Boolean = false,
        val enterprise: Boolean = false,
        /** RSSI in dBm, e.g. -45 (strong) .. -90 (weak). */
        val signalLevel: Int = -55
    )

    data class Advice(
        val mode: Mode,
        val maxTryTime: Int,
        val timeout: Int,
        val failureFlag: Int,
        val notes: List<Note>,
        val preferLongPasswords: Boolean,
        val preferNumeric: Boolean
    )

    /**
     * @param current the user's current settings, used as the baseline so we
     *                only override what the security type actually requires
     */
    fun advise(
        profile: SecurityProfile,
        currentMaxTryTime: Int = 5000,
        currentTimeout: Int = 1500,
        currentFailureFlag: Int = 2
    ): Advice {
        val notes = ArrayList<Note>()
        var mode = Mode.ONLINE
        var maxTryTime = currentMaxTryTime
        var timeout = currentTimeout
        var failureFlag = currentFailureFlag
        var preferLong = false
        var preferNumeric = false

        if (profile.open) {
            notes += Note.OPEN_NETWORK
            mode = Mode.OPEN
        }
        if (profile.wep) {
            notes += Note.WEP_LEGACY
            mode = Mode.OFFLINE_PREFERRED
        }
        if (profile.enterprise) {
            notes += Note.ENTERPRISE_UNSUPPORTED
            mode = Mode.UNSUPPORTED
        }
        if (profile.wpa3) {
            notes += Note.WPA3_SAE_SLOW
            mode = Mode.UNSUPPORTED
        }
        if (profile.wpa2 && profile.wpa3) {
            notes += Note.WPA3_TRANSITION
            // Transition mode still accepts PSK, so keep the online loop but
            // give the AP time to fall back to WPA2.
            mode = Mode.ONLINE
            maxTryTime = maxOf(maxTryTime, 8000)
            timeout = maxOf(timeout, 2500)
            failureFlag = 1
        }

        if (profile.signalLevel < -75) {
            notes += Note.WEAK_SIGNAL
            maxTryTime = maxOf(maxTryTime, 8000)
            timeout = maxOf(timeout, 2000)
            failureFlag = minOf(failureFlag, 1)
        }

        if (profile.wpa2 && !profile.wpa3) {
            notes += Note.FAST_PROFILE
            // Stable and fast: the handshake-limit flag is reliable on WPA2.
            failureFlag = 2
        }

        if (profile.wpa2 || profile.wpa3) {
            notes += Note.OFFLINE_RECOMMENDED
            notes += Note.LONG_PASSWORDS_LIKELY
            preferLong = true
        }

        if (profile.wpa && !profile.wpa2) {
            // Old WPA/TKIP routers usually ship numeric or short defaults.
            preferNumeric = true
        }

        return Advice(
            mode = mode,
            maxTryTime = maxTryTime,
            timeout = timeout,
            failureFlag = failureFlag,
            notes = notes.distinct(),
            preferLongPasswords = preferLong,
            preferNumeric = preferNumeric
        )
    }

    /** Parses Android's `capabilities` string, e.g. `[WPA2-PSK-CCMP][ESS]`. */
    fun profileFromCapabilities(capabilities: String, signalLevel: Int = -55): SecurityProfile {
        val tokens = Regex("\\[(.*?)]")
            .findAll(capabilities)
            .flatMap { it.groupValues[1].split("-").asSequence() }
            .map { it.uppercase() }
            .toList()

        val open = "ESS" in tokens && tokens.none {
            it in setOf("WPA", "WPA2", "WPA3", "RSN", "PSK", "EAP", "SAE")
        }
        val sae = "SAE" in tokens || "WPA3" in tokens
        val psk = "PSK" in tokens
        return SecurityProfile(
            open = open,
            wep = "WEP" in tokens,
            wpa = "WPA" in tokens,
            // "RSN" on its own means WPA2-PSK; with SAE it is WPA3, and only
            // counts as WPA2 too when the AP also advertises PSK (transition).
            wpa2 = "WPA2" in tokens || (!sae && "RSN" in tokens) || (sae && psk),
            wpa3 = sae,
            enterprise = "EAP" in tokens || "802.1X" in tokens,
            signalLevel = signalLevel
        )
    }
}
