package com.wifi.toolbox.app

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.services.PojieService
import com.wifi.toolbox.structs.PojieConfig
import com.wifi.toolbox.structs.PojieHistoryItem
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.utils.LogState
import org.json.JSONArray
import org.json.JSONObject

class AppPojieTask(private val context: Context) {

    private val prefs =
        context.getSharedPreferences("settings_pojie", Context.MODE_PRIVATE)

    // Queue metadata lives in its own file so progress updates do not fire the settings listener
    private val queuePrefs =
        context.getSharedPreferences("pojie_queue", Context.MODE_PRIVATE)

    var pojieConfig by mutableStateOf(PojieConfig.from(prefs))
        private set

    var logState by mutableStateOf(LogState())
        private set

    val TaskRunList = mutableStateListOf<PojieRunInfo>()
    val TaskEndTip = mutableStateMapOf<String, String>()

    /**
     * Updates the configuration
     * @param info New configuration
     * @return none
     */
    fun update(info: PojieConfig) {
        pojieConfig = info
        info.saveTo(prefs)
    }

    /**
     * Starts a task
     * @param info Task information
     * @return none
     */
    fun start(info: PojieRunInfo) {
        if (TaskRunList.none { it.ssid == info.ssid }) {
            if (TaskRunList.isEmpty()) {
                val intent = Intent(context, PojieService::class.java)
                context.startService(intent)
            }
            TaskRunList.add(info)
            TaskEndTip.remove(info.ssid)
            persistQueue()
            // Write one full snapshot when the task starts, then only update progress
            (context as? ToolboxApp)?.pojieHistory?.saveSnapshot(
                PojieHistoryItem(info.ssid, info.tryList, info.tryIndex)
            )
        }
    }

    /**
     * Stops a task
     * @param name Network name (SSID)
     * @return none
     */
    fun stop(name: String) {
        TaskRunList.removeIf { it.ssid == name }
        persistQueue()
    }

    /**
     * Updates a task
     * @param name Network name (SSID)
     * @param change Transform function
     * @return none
     */
    fun edit(name: String, change: (PojieRunInfo) -> PojieRunInfo) {
        val index = TaskRunList.indexOfFirst { it.ssid == name }
        if (index != -1) {
            val before = TaskRunList[index]
            val after = change(before)
            TaskRunList[index] = after
            // Persist only on progress/retry changes; display-only fields like textTip are skipped
            if (after.tryIndex != before.tryIndex || after.retryCount != before.retryCount) {
                persistQueue()
            }
        }
    }

    // --- Queue persistence ---
    // Only task metadata is stored (SSID, progress, retries); the password list itself
    // lives in PojieHistoryManager, so we never put 100k passwords into SharedPreferences.

    private fun persistQueue() {
        val array = JSONArray()
        TaskRunList.forEach { task ->
            array.put(
                JSONObject().apply {
                    put(KEY_SSID, task.ssid)
                    put(KEY_TRY_INDEX, task.tryIndex)
                    put(KEY_LAST_TRY_TIME, task.lastTryTime)
                    put(KEY_RETRY_COUNT, task.retryCount)
                }
            )
        }
        queuePrefs.edit().putString(KEY_PENDING_TASKS, array.toString()).apply()
    }

    fun clearPersistedQueue() {
        queuePrefs.edit().remove(KEY_PENDING_TASKS).apply()
    }

    fun hasPersistedQueue(): Boolean =
        !queuePrefs.getString(KEY_PENDING_TASKS, null).isNullOrBlank()

    /**
     * Restores the queue after the process was killed.
     * Password lists are reloaded from the history DB by SSID; tasks without one are dropped.
     *
     * @param loadPasswords Suspending loader that reads the password list for an SSID
     * @return Number of restored tasks
     */
    suspend fun restoreQueue(loadPasswords: suspend (String) -> List<String>?): Int {
        if (TaskRunList.isNotEmpty()) return 0
        val raw = queuePrefs.getString(KEY_PENDING_TASKS, null) ?: return 0
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            clearPersistedQueue()
            return 0
        }

        var restored = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val ssid = obj.optString(KEY_SSID).takeIf { it.isNotEmpty() } ?: continue
            val passwords = try {
                loadPasswords(ssid)
            } catch (_: Exception) {
                null
            } ?: continue
            if (passwords.isEmpty()) continue

            val index = obj.optInt(KEY_TRY_INDEX, 0)
            // Finished tasks are not restored
            if (index >= passwords.size) continue

            TaskRunList.add(
                PojieRunInfo(
                    ssid = ssid,
                    tryList = passwords,
                    tryIndex = index.coerceAtLeast(0),
                    lastTryTime = obj.optLong(KEY_LAST_TRY_TIME, 0L),
                    retryCount = obj.optInt(KEY_RETRY_COUNT, 0)
                )
            )
            restored++
        }

        if (restored == 0) clearPersistedQueue()
        return restored
    }

    private companion object {
        const val KEY_PENDING_TASKS = "pojie_pending_tasks"
        const val KEY_SSID = "ssid"
        const val KEY_TRY_INDEX = "tryIndex"
        const val KEY_LAST_TRY_TIME = "lastTryTime"
        const val KEY_RETRY_COUNT = "retryCount"
    }
}
