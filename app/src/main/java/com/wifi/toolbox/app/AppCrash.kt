package com.wifi.toolbox.app

import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

class AppCrash(private val context: Context) {

    /**
     * Starts crash capture
     * @param none
     * @return none
     */
    fun startCatch() {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val msg = throwable.localizedMessage ?: "Unknown error"
            val stack = throwable.stackTraceToString()

            val intent = Intent(context, Class.forName("com.wifi.toolbox.ui.MainActivity")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("CRASH_RECOVERY", true)
                putExtra("ERROR_MESSAGE", msg)
                putExtra("ERROR_STACK", stack)
            }

            context.startActivity(intent)
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }
}