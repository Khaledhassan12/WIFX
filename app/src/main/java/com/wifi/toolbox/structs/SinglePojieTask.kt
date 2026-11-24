package com.wifi.toolbox.structs

data class SinglePojieTask(
    val ssid: String,
    val password: String
) {
    companion object {
        /** Connected successfully */
        const val RESULT_SUCCESS = 0

        /** Failure threshold reached */
        const val RESULT_FAILED = 1

        /** Attempt timed out (router did not respond) */
        const val RESULT_TIMEOUT = 2

        /** User cancelled the task */
        const val RESULT_CANCEL = 3

        /** Router rejected the association, etc. */
        const val RESULT_ERROR_TRANSIENT = -1

        /** Not authorized / other error */
        const val RESULT_ERROR = -2
    }
}