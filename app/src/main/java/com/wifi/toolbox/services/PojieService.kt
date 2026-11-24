package com.wifi.toolbox.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.services.pojie.*
import com.wifi.toolbox.structs.PojieSettings
import kotlinx.coroutines.*

@Suppress("DEPRECATION")
class PojieService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var sharedPreferences: SharedPreferences
    private var _pojieSettings: PojieSettings? = null

    private val pojieSettings: PojieSettings
        get() = _pojieSettings ?: PojieSettings.from(sharedPreferences).also { _pojieSettings = it }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        _pojieSettings = PojieSettings.from(prefs)
    }

    private lateinit var connectionWorker: ConnectWorker
    private lateinit var taskDispatcher: PojieTaskManager
    @Volatile
    private var startRequested = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Holds WakeLock and WifiLock while running so Doze/screen-off cannot sleep
     * Wi-Fi, drop handshake logs or time out attempts.
     */
    private fun acquireRunLocks() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wifiToolbox:pojie"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "wifiToolbox:pojie"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseRunLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("settings_pojie", MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefsListener)
        connectionWorker = ConnectWorker(this)
        taskDispatcher = PojieTaskManager(this, serviceScope, connectionWorker)
    }

    fun log(log: String, allowEdit: Boolean = false) {
        (applicationContext as ToolboxApp).logState.addLog(log, allowEdit)
    }

    fun stopAllTasks() {
        val app = applicationContext as ToolboxApp
        app.runningPojieTasks.clear()
        app.pojieTask.clearPersistedQueue()
        taskDispatcher.cancelCurrentJob()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as ToolboxApp
        try {
            PojieNotification.createChannel(this)
            val notification = PojieNotification.buildForeground(this)
            startForeground(1, notification)

            // After the process is killed (START_STICKY restart) the in-memory queue is
            // empty, so tasks are restored from persisted metadata plus the history DB.
            // Password lists can be large, so they are loaded from a suspending main-thread call.
            if (!taskDispatcher.isWorking() && !startRequested) {
                startRequested = true
                app.logState.clear()
                log(getAsciiArt())

                acquireRunLocks()
                connectionWorker.initLogServices(pojieSettings)
                serviceScope.launch {
                    try {
                        val restored = withContext(Dispatchers.Main) {
                            if (app.runningPojieTasks.isEmpty() && app.pojieTask.hasPersistedQueue()) {
                                app.pojieTask.restoreQueue { ssid ->
                                    app.pojieHistory.loadPasswords(ssid)
                                }
                            } else 0
                        }
                        if (restored > 0) {
                            app.runningPojieTasks.forEach {
                                app.pojieHistory.updateProgress(it.ssid, it.tryIndex)
                            }
                            log(getString(R.string.queue_restored, restored))
                        }
                        taskDispatcher.startLoop(pojieSettings)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        log(e.toString())
                    } finally {
                        startRequested = false
                    }
                }
            }
            return START_STICKY
        } catch (e: Exception) {
            stopAllTasks()
            releaseRunLocks()
            log(getString(R.string.service_start_failed))
            log(e.toString())
            stop()
            return START_NOT_STICKY
        }
    }

    private fun getAsciiArt(): String {
        return """      _      __                 _        
     | |___ / _|_ __ ___  _   _| |_ __ _ 
  _  | / __| |_| '_ ` _ \| | | | __/ _` |
 | |_| \__ \  _| | | | | | |_| | || (_| |
  \___/|___/_| |_| |_| |_|\__, |\__\__, |
                          |___/    |___/ 
==========================================
${getString(R.string.pojie_service_description)}
"""
    }

    fun stop() {
        log(getString(R.string.run_finished))
        taskDispatcher.cancelCurrentJob()
        connectionWorker.closeServices()
        releaseRunLocks()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseRunLocks()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
    }
}