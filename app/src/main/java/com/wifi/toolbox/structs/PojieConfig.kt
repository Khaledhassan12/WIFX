package com.wifi.toolbox.structs

import android.content.SharedPreferences
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PojieConfig(
    val maxTryTime: Int = 5000,
    val failureFlag: Int = 2,
    val timeout: Int = 1500,
    val maxHandshakeCount: Int = 1,
    val retryCountType: Int = 6,
    val doublingBase: Int = 0
) : Parcelable {

    /**
     * Persists to SharedPreferences
     * Stored in the same prefs file as PojieSettings (settings_pojie)
     */
    fun saveTo(prefs: SharedPreferences) {
        prefs.edit()
            .putInt(KEY_MAX_TRY_TIME, maxTryTime)
            .putInt(KEY_FAILURE_FLAG, failureFlag)
            .putInt(KEY_TIMEOUT, timeout)
            .putInt(KEY_MAX_HANDSHAKE_COUNT, maxHandshakeCount)
            .putInt(KEY_RETRY_COUNT_TYPE, retryCountType)
            .putInt(KEY_DOUBLING_BASE, doublingBase)
            .apply()
    }

    companion object {
        private const val KEY_MAX_TRY_TIME = "cfg_max_try_time"
        private const val KEY_FAILURE_FLAG = "cfg_failure_flag"
        private const val KEY_TIMEOUT = "cfg_timeout"
        private const val KEY_MAX_HANDSHAKE_COUNT = "cfg_max_handshake_count"
        private const val KEY_RETRY_COUNT_TYPE = "cfg_retry_count_type"
        private const val KEY_DOUBLING_BASE = "cfg_doubling_base"

        fun from(prefs: SharedPreferences): PojieConfig {
            val defaults = PojieConfig()
            return PojieConfig(
                maxTryTime = prefs.getInt(KEY_MAX_TRY_TIME, defaults.maxTryTime),
                failureFlag = prefs.getInt(KEY_FAILURE_FLAG, defaults.failureFlag),
                timeout = prefs.getInt(KEY_TIMEOUT, defaults.timeout),
                maxHandshakeCount = prefs.getInt(
                    KEY_MAX_HANDSHAKE_COUNT,
                    defaults.maxHandshakeCount
                ),
                retryCountType = prefs.getInt(KEY_RETRY_COUNT_TYPE, defaults.retryCountType),
                doublingBase = prefs.getInt(KEY_DOUBLING_BASE, defaults.doublingBase)
            )
        }
    }
}
