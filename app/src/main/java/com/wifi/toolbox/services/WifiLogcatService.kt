package com.wifi.toolbox.services

import android.content.Context
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.structs.WifiLogData
import com.wifi.toolbox.utils.AidlServiceHelper
import com.wifi.toolbox.utils.CommandRunner
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.function.Consumer

class WifiLogcatService(
    private val context: Context, private val pojieSettings: PojieSettings
) : AutoCloseable {
    var stopFunc: Runnable? = null

    /**
     * Unbounded Channel + receiveAsFlow: success/failure events are never dropped
     */
    private val events = Channel<WifiLogData>(Channel.UNLIMITED)
    val logFlow = events.receiveAsFlow()

    private val parser = WifiLogParser()

    /** Drops events left over from the previous attempt */
    fun drainPendingEvents() {
        while (events.tryReceive().isSuccess) {
            // drain
        }
    }

    /** New attempt: resets handshake counters, keeps the SSID */
    fun startAttempt() = parser.startAttempt()

    override fun close() {
        stopFunc?.run()
        stopFunc = null
    }

    private fun executeCommand(
        command: String,
        onOutputReceived: Consumer<String>?,
        onCommandFinished: Consumer<CommandRunner.CommandResult>?
    ): Runnable {
        val app = context.applicationContext as ToolboxApp
        return when (val method = pojieSettings.commandMethod) {
            0 -> throw Exception(context.getString(R.string.command_method_empty))
            1 -> ShizukuUtil.executeCommand(command, onOutputReceived, onCommandFinished)
            2 -> AidlServiceHelper.executeCommand(app, command, onOutputReceived, onCommandFinished)
            3 -> CommandRunner.executeCommand(command, true, onOutputReceived, onCommandFinished)
            else -> throw Exception(context.getString(R.string.tip_not_completed) + "(commandMethod=$method)")
        }
    }

    fun executeCommandSync(command: String): CommandRunner.CommandResult {
        val app = context.applicationContext as ToolboxApp
        return when (val method = pojieSettings.commandMethod) {
            0 -> throw Exception(context.getString(R.string.command_method_empty))
            1 -> ShizukuUtil.executeCommandSync(command)
            2 -> AidlServiceHelper.executeCommandSync(app, command)
            3 -> CommandRunner.executeCommandSync(command, true)
            else -> throw Exception(context.getString(R.string.tip_not_completed) + "(commandMethod=$method)")
        }
    }

    init {
        executeCommandSync("logcat -c")
        stopFunc = executeCommand(
            command = "logcat -s WifiService:D wpa_supplicant:D DhcpClient:D",
            onOutputReceived = { line ->
                val event = parser.parse(line)
                if (event != null) {
                    events.trySend(event)
                }
            },
            onCommandFinished = {
                // Note: this should normally never run
            })
    }

}
