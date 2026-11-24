@file:Suppress("DEPRECATION")

package com.wifi.toolbox.utils

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.*
import com.wifi.toolbox.structs.*
import rikka.shizuku.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import org.lsposed.hiddenapibypass.*
import java.util.BitSet
import android.os.WorkSource
import android.util.Log
import java.lang.reflect.Method

object ShizukuUtil {

    const val REQUEST_PERMISSION_CODE = 1001

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sharedPreferences =
                context.getSharedPreferences("settings_global", Context.MODE_PRIVATE)
            var hiddenApiBypassOption: Int
            try {
                hiddenApiBypassOption = sharedPreferences.getInt("hidden_api_bypass", 1)
            } catch (_: ClassCastException) {
                val stringValue = sharedPreferences.getString("hidden_api_bypass", null)
                hiddenApiBypassOption = stringValue?.toIntOrNull() ?: 1
            }

            when (hiddenApiBypassOption) {
                1 -> LSPass.addHiddenApiExemptions("")
                2 -> HiddenApiBypass.addHiddenApiExemptions("")
            }
        }
    }

    const val PACKAGE_NAME = "com.android.shell"

    private fun asInterface(className: String, original: IBinder): Any {
        return Class.forName("$className\$Stub").run {
            getMethod("asInterface", IBinder::class.java).invoke(
                null,
                ShizukuBinderWrapper(original)
            )!!
        }
    }

    /**
     * Miscellaneous self-checks; not used by the app, only to verify behaviour
     */

    fun lookScreen() {
        val inputManagerBinder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
        val input = asInterface("android.hardware.input.IInputManager", inputManagerBinder)
        val inject = input::class.java.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.java
        )
        val now = SystemClock.uptimeMillis()
        val injectMode = 2
        inject.invoke(
            input, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0), injectMode
        )
        inject.invoke(
            input, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER, 0), injectMode
        )
    }

    fun setMediaVolumeMax() {
        val audioManagerBinder = SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE)
        val audioService = asInterface("android.media.IAudioService", audioManagerBinder)

        val getStreamMaxVolumeMethod = audioService::class.java.getMethod(
            "getStreamMaxVolume", Int::class.java
        )
        val maxVolume =
            getStreamMaxVolumeMethod.invoke(audioService, AudioManager.STREAM_MUSIC) as Int

        val setStreamVolumeMethod = audioService::class.java.getMethod(
            "setStreamVolume", Int::class.java, Int::class.java, Int::class.java, String::class.java
        )
        setStreamVolumeMethod.invoke(
            audioService,
            AudioManager.STREAM_MUSIC,
            maxVolume,
            0,
            PACKAGE_NAME
        )
    }

    /**
     * Core Wi-Fi operations used by the app
     */

    /**
     * ### setWifiEnabled
     *
     * * **Android 7 - 16**: takes `(String packageName, boolean enable)`, returns `boolean`.
     *
     * ### removeNetwork
     *
     * * **Android 7 - 8**: takes `(int netId)`, returns `boolean`.
     * * **Android 9 - 16**: takes `(int netId, String packageName)`, returns `boolean`.
     *
     * ### addOrUpdateNetwork
     *
     * * **Android 7 - 8**: takes `(WifiConfiguration config)`, returns `int`.
     * * **Android 9 - 12**: takes `(WifiConfiguration config, String packageName)`, returns `int`.
     * * **Android 13 - 16**: takes `(WifiConfiguration config, String packageName, Bundle extras)`, returns `int`.
     *
     * ### enableNetwork
     *
     * * **Android 7 - 8**: takes `(int netId, boolean disableOthers)`, returns `boolean`.
     * * **Android 9 - 16**: takes `(int netId, boolean disableOthers, String packageName)`, returns `boolean`.
     *
     * ### disableNetwork
     *
     * * **Android 7 - 8**: takes `(int netId)`, returns `boolean`.
     * * **Android 9 - 16**: takes `(int netId, String packageName)`, returns `boolean`.
     *
     * ### getConnectionInfo
     *
     * * **Android 7**: takes `()`, returns `WifiInfo`.
     * * **Android 8 - 10**: takes `(String callingPackage)`, returns `WifiInfo`.
     * * **Android 11 - 16**: takes `(String callingPackage, String callingFeatureId)`, returns `WifiInfo`.
     *
     * ### allowAutojoin
     *
     * * **Android 7 - 10**: undefined.
     * * **Android 11 - 16**: takes `(int netId, boolean choice)`, returns `void`.
     *
     * ### disconnect
     *
     * * **Android 7 - 8**: takes `()`, returns `void`.
     * * **Android 9**: takes `(String packageName)`, returns `void`.
     * * **Android 10 - 16**: takes `(String packageName)`, returns `boolean`.
     *
     * ### startScan
     *
     * * **Android 7**: takes `(ScanSettings requested, WorkSource ws)`, returns `boolean`.
     * * **Android 8**: takes `(ScanSettings requested, WorkSource ws, String packageName)`, returns `boolean`.
     * * **Android 9 - 10**: takes `(String packageName)`, returns `boolean`.
     * * **Android 11 - 16**: takes `(String packageName, String featureId)`, returns `boolean`.
     *
     * ### getScanResults
     *
     * * **Android 7 - 10**: takes `(String callingPackage)`, returns `List<ScanResult>`.
     * * **Android 11 - 14**: takes `(String callingPackage, String callingFeatureId)`, returns `List<ScanResult>`.
     * * **Android 15 - 16**: takes `(String callingPackage, String callingFeatureId)`, returns `ParceledListSlice<ScanResult>`.
     *
     * ### getConfiguredNetworks
     *
     * * **Android 7**: takes `()`, returns `List<WifiConfiguration>`.
     * * **Android 8 - 9**: takes `()`, returns `ParceledListSlice<WifiConfiguration>`.
     * * **Android 10**: takes `(String packageName)`, returns `ParceledListSlice<WifiConfiguration>`.
     * * **Android 11**: takes `(String packageName, String featureId)`, returns `ParceledListSlice<WifiConfiguration>`.
     * * **Android 12 - 16**: takes `(String packageName, String featureId, boolean callerNetworksOnly)`, returns `ParceledListSlice<WifiConfiguration>`.
     *
     *
     * ### forget
     *
     * * **Android 7 - 10**: undefined.
     * * **Android 11**: takes `(int netId, IBinder binder, IActionListener listener, int callbackIdentifier)`, returns `void`.
     * * **Android 12 - 16**: takes `(int netId, IActionListener listener)`, returns `void`.
     */
    @SuppressLint("PrivateApi")
    fun getWifiMethod(wifiService: Any, methodName: String): Method {
        val clazz = wifiService::class.java
        val sdk = Build.VERSION.SDK_INT
        val stringClass = String::class.java
        val intType = Integer.TYPE
        val booleanType = java.lang.Boolean.TYPE
        val wifiConfigClass = Class.forName("android.net.wifi.WifiConfiguration")

        val method = when (methodName) {
            "setWifiEnabled" -> {
                try {
                    clazz.getMethod(methodName, stringClass, booleanType)
                } catch (_: Exception) {
                    clazz.getMethod(methodName, booleanType) // the AIDL signature in the source is wrong
                }
            }

            "removeNetwork" -> {
                if (sdk >= 28) clazz.getMethod(methodName, intType, stringClass)
                else clazz.getMethod(methodName, intType)
            }

            "addOrUpdateNetwork" -> {
                when {
                    sdk >= 33 -> clazz.getMethod(
                        methodName,
                        wifiConfigClass,
                        stringClass,
                        Bundle::class.java
                    )

                    sdk >= 28 -> clazz.getMethod(methodName, wifiConfigClass, stringClass)
                    else -> clazz.getMethod(methodName, wifiConfigClass)
                }
            }

            "enableNetwork" -> {
                if (sdk >= 28) clazz.getMethod(methodName, intType, booleanType, stringClass)
                else clazz.getMethod(methodName, intType, booleanType)
            }

            "getConnectionInfo" -> {
                when {
                    sdk >= 30 -> clazz.getMethod(methodName, stringClass, stringClass)
                    sdk >= 26 -> clazz.getMethod(methodName, stringClass)
                    else -> clazz.getMethod(methodName)
                }
            }

            "allowAutojoin" -> {
                if (sdk >= 30) clazz.getMethod(methodName, intType, booleanType)
                else null
            }

            "disconnect" -> {
                if (sdk >= 28) clazz.getMethod(methodName, stringClass)
                else clazz.getMethod(methodName)
            }

            "startScan" -> {
                when {
                    sdk >= 30 -> clazz.getMethod(methodName, stringClass, stringClass)
                    sdk >= 28 -> clazz.getMethod(methodName, stringClass)
                    else -> {
                        val scanSettingsClass = Class.forName("android.net.wifi.ScanSettings")
                        when {
                            sdk >= 26 -> clazz.getMethod(
                                methodName,
                                scanSettingsClass,
                                WorkSource::class.java,
                                stringClass
                            )

                            else -> clazz.getMethod(
                                methodName,
                                scanSettingsClass,
                                WorkSource::class.java
                            )
                        }
                    }
                }
            }

            "getScanResults" -> {
                if (sdk >= 30) clazz.getMethod(methodName, stringClass, stringClass)
                else clazz.getMethod(methodName, stringClass)
            }

            "getConfiguredNetworks" -> {
                when {
                    sdk >= 31 -> clazz.getMethod(methodName, stringClass, stringClass, booleanType)
                    sdk == 30 -> clazz.getMethod(methodName, stringClass)
                    sdk == 29 -> clazz.getMethod(methodName, stringClass)
                    else -> clazz.getMethod(methodName)
                }
            }

            "disableNetwork" -> {
                if (sdk >= 28) clazz.getMethod(methodName, intType, stringClass)
                else clazz.getMethod(methodName, intType)
            }

            "forget" -> {
                when {
                    sdk >= 31 -> clazz.getMethod(
                        methodName,
                        intType,
                        Class.forName("android.net.wifi.IActionListener")
                    )

                    sdk == 30 -> clazz.getMethod(
                        methodName,
                        intType,
                        IBinder::class.java,
                        Class.forName("android.net.wifi.IActionListener"),
                        intType
                    )

                    else -> null
                }
            }

            else -> null
        }
        return method ?: throw NoSuchMethodException(methodName)
    }

    fun setWifiEnabled(enabled: Boolean) {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val method = getWifiMethod(wifiService, "setWifiEnabled")

        when (method.parameterTypes.size) {
            3 -> method.invoke(wifiService, PACKAGE_NAME, enabled, Bundle())
            2 -> method.invoke(wifiService, PACKAGE_NAME, enabled)
            1 -> method.invoke(wifiService, enabled)
        }
    }

    fun forgetNetwork(netId: Int) {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val sdk = Build.VERSION.SDK_INT
        if (sdk <= 29) {
            val removeMethod = getWifiMethod(wifiService, "removeNetwork")
            when (removeMethod.parameterTypes.size) {
                2 -> removeMethod.invoke(wifiService, netId, PACKAGE_NAME)
                1 -> removeMethod.invoke(wifiService, netId)
            }
        } else {
            val forgetMethod = getWifiMethod(wifiService, "forget")
            when (forgetMethod.parameterTypes.size) {
                4 -> forgetMethod.invoke(wifiService, netId, null, null, 0)
                2 -> forgetMethod.invoke(wifiService, netId, null)
            }
        }
    }

    fun getNetIdBySsid(ssid: String): Int {
        val savedNetworks: List<WifiConfiguration> = getSavedWifiList()
        return savedNetworks.find { it.SSID.removeSurrounding("\"") == ssid }?.networkId ?: -1
    }

    fun connectToWifi(ssid: String, password: String): Int {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val addMethod = getWifiMethod(wifiService, "addOrUpdateNetwork")

        val wifiConfigurationClass = Class.forName("android.net.wifi.WifiConfiguration")
        val wifiConfig = wifiConfigurationClass.getDeclaredConstructor().newInstance()
        wifiConfigurationClass.getField("SSID").set(wifiConfig, "\"$ssid\"")

        val keyMgmtBitSet = BitSet()
        if (password.isEmpty()) {
            keyMgmtBitSet.set(0)
        } else {
            wifiConfigurationClass.getField("preSharedKey").set(wifiConfig, "\"$password\"")
            keyMgmtBitSet.set(1)
        }
        wifiConfigurationClass.getField("allowedKeyManagement").set(wifiConfig, keyMgmtBitSet)

        val netId = when (addMethod.parameterTypes.size) {
            3 -> addMethod.invoke(wifiService, wifiConfig, PACKAGE_NAME, Bundle())
            2 -> addMethod.invoke(wifiService, wifiConfig, PACKAGE_NAME)
            1 -> addMethod.invoke(wifiService, wifiConfig)
            else -> -1
        } as Int

        if (netId == -1) throw RuntimeException("addOrUpdateNetwork failed")

        enableNetwork(netId)
        return netId
    }

    fun enableNetwork(netId: Int) {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val enableMethod = getWifiMethod(wifiService, "enableNetwork")
        when (enableMethod.parameterTypes.size) {
            3 -> enableMethod.invoke(wifiService, netId, true, PACKAGE_NAME)
            2 -> enableMethod.invoke(wifiService, netId, true)
        }

    }

    /**
     * Disconnects the network cleanly
     * */
    fun disconnectWifi() {
        val wifiService = asInterface(
            "android.net.wifi.IWifiManager",
            SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        )

        val infoMethod = getWifiMethod(wifiService, "getConnectionInfo")
        val wifiInfo = when (infoMethod.parameterTypes.size) {
            2 -> infoMethod.invoke(wifiService, PACKAGE_NAME, null)
            1 -> infoMethod.invoke(wifiService, PACKAGE_NAME)
            else -> infoMethod.invoke(wifiService)
        } as? android.net.wifi.WifiInfo

        val currentNetId = wifiInfo?.networkId ?: -1
        if (currentNetId != -1) {
            try {
                val allowAutojoinMethod = getWifiMethod(wifiService, "allowAutojoin")
                allowAutojoinMethod.invoke(wifiService, currentNetId, false)
            } catch (_: NoSuchMethodException) {
                val disableMethod = getWifiMethod(wifiService, "disableNetwork")
                if (disableMethod.parameterTypes.size == 2) {
                    disableMethod.invoke(wifiService, currentNetId, PACKAGE_NAME)
                } else {
                    disableMethod.invoke(wifiService, currentNetId)
                }
            } catch (_: Exception) {
            }
        }

        val disconnectMethod = getWifiMethod(wifiService, "disconnect")
        when (disconnectMethod.parameterTypes.size) {
            1 -> disconnectMethod.invoke(wifiService, PACKAGE_NAME)
            else -> disconnectMethod.invoke(wifiService)
        }
    }

    fun startWifiScan(allowUseCommand: Boolean = false): Boolean {
        val wifiService = asInterface(
            "android.net.wifi.IWifiManager",
            SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        )

        val scanMethod = getWifiMethod(wifiService, "startScan")
        val sdk = Build.VERSION.SDK_INT

        val scanInitiated = try {
            when {
                sdk >= 30 -> scanMethod.invoke(wifiService, PACKAGE_NAME, null) as Boolean
                sdk >= 28 -> scanMethod.invoke(wifiService, PACKAGE_NAME) as Boolean
                sdk >= 26 -> scanMethod.invoke(wifiService, null, null, PACKAGE_NAME) as Boolean
                else -> {
                    scanMethod.invoke(wifiService, null, null)
                    true
                }
            }
        } catch (_: Exception) {
            false
        }

        if (!scanInitiated && allowUseCommand) {
            return executeCommandSync("cmd wifi start-scan").exitCode == 0
        }
        return scanInitiated
    }

    fun getWifiScanResults(): List<WifiInfo> {
        val wifiService = asInterface(
            "android.net.wifi.IWifiManager",
            SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        )

        val results = mutableListOf<WifiInfo>()
        val getScanResultsMethod = getWifiMethod(wifiService, "getScanResults")

        val resultObject = when (getScanResultsMethod.parameterTypes.size) {
            2 -> getScanResultsMethod.invoke(wifiService, PACKAGE_NAME, null)
            1 -> getScanResultsMethod.invoke(wifiService, PACKAGE_NAME)
            else -> null
        } ?: return results

        @Suppress("UNCHECKED_CAST")
        val scanResultsList = if (resultObject is List<*>) {
            resultObject as List<Any>
        } else {
            val getListMethod = resultObject.javaClass.getMethod("getList")
            getListMethod.invoke(resultObject) as List<Any>
        }

        if (scanResultsList.isEmpty()) {
            return results
        }

        val scanResultClass = Class.forName("android.net.wifi.ScanResult")
        val ssidField = scanResultClass.getField("SSID")
        val bssidField = scanResultClass.getField("BSSID")
        val levelField = scanResultClass.getField("level")
        val capabilitiesField = scanResultClass.getField("capabilities")

        scanResultsList.forEach { result ->
            val ssid = ssidField.get(result)?.toString() ?: ""
            val bssid = bssidField.get(result)?.toString() ?: ""
            val level = levelField.get(result) as Int
            val capabilities = capabilitiesField.get(result)?.toString() ?: ""

            results.add(WifiInfo(ssid, level, bssid, capabilities))
        }
        results.sortByDescending { it.level }
        return results
    }


    fun getSavedWifiListOld(): List<WifiConfiguration> {
        val wifiService = asInterface(
            "android.net.wifi.IWifiManager",
            SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        )

        val results = mutableListOf<WifiConfiguration>()
        val wifiConfigurationClass = Class.forName("android.net.wifi.WifiConfiguration")
        val getMethod = getWifiMethod(wifiService, "getConfiguredNetworks")

        val rawResult = when (getMethod.parameterTypes.size) {
            3 -> getMethod.invoke(wifiService, PACKAGE_NAME, null, false)
            2 -> getMethod.invoke(wifiService, PACKAGE_NAME, Bundle())
            1 -> getMethod.invoke(wifiService, PACKAGE_NAME)
            0 -> getMethod.invoke(wifiService)
            else -> null
        } ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val configuredNetworksList = if (rawResult is List<*>) {
            rawResult as List<Any>
        } else {
            val getListMethod = rawResult.javaClass.getMethod("getList")
            getListMethod.invoke(rawResult) as List<Any>
        }

        if (configuredNetworksList.isEmpty()) return results
        val networkIdField = wifiConfigurationClass.getField("networkId")
        val ssidFieldConfig = try {
            wifiConfigurationClass.getField("SSID")
        } catch (_: Exception) {
            null
        }

        val seenIds = HashSet<Int>()
        configuredNetworksList.forEach { config ->
            val networkId = networkIdField.get(config) as Int
            if (seenIds.contains(networkId)) return@forEach
            seenIds.add(networkId)
            var ssidValue = ""
            if (ssidFieldConfig != null) {
                ssidValue = try {
                    ssidFieldConfig.get(config)?.toString() ?: ""
                } catch (_: Exception) {
                    ""
                }
            }
            if (ssidValue.length >= 2 && ssidValue.startsWith("\"") && ssidValue.endsWith("\"")) {
                ssidValue = ssidValue.substring(1, ssidValue.length - 1)
            }
            results.add(config as WifiConfiguration)
        }
        return results
    }

    /**
     * Returns the saved Wi-Fi list
     * Adapted from the zacharee/WiFiList project
     * @return Saved networks as List<WifiConfiguration>
     * */
    @SuppressLint("PrivateApi")
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    fun getSavedWifiList(): List<WifiConfiguration> {
        try {
            val base = Class.forName("android.net.wifi.IWifiManager")
            val iwm = asInterface(
                "android.net.wifi.IWifiManager",
                SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
            )

            val user = when (Shizuku.getUid()) {
                0 -> "root"
                1000 -> "system"
                else -> "shell"
            }

            // This API is a mess!
            val privilegedConfigs = when {
                Build.VERSION.SDK_INT >= 33 -> {
                    val method = base.getMethod(
                        "getPrivilegedConfiguredNetworks",
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    )
                    method.invoke(iwm, user, PACKAGE_NAME, Bundle().apply {
                        putParcelable(
                            "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE",
                            AttributionSource::class.java.getConstructor(
                                Int::class.java,
                                String::class.java,
                                String::class.java,
                                Set::class.java,
                                AttributionSource::class.java
                            ).newInstance(
                                Shizuku.getUid(),
                                PACKAGE_NAME,
                                PACKAGE_NAME,
                                null as Set<String>?,
                                null
                            )
                        )
                    })
                }

                Build.VERSION.SDK_INT >= 29 -> {
                    // Note: not available on the Android 10 emulator (maybe why WiFiList requires minSdk 30)
                    try {
                        try {
                            base.getMethod("getPrivilegedConfiguredNetworks", String::class.java)
                                .invoke(iwm, PACKAGE_NAME)
                        } catch (_: NoSuchMethodException) {
                            base.getMethod(
                                "getPrivilegedConfiguredNetworks",
                                String::class.java,
                                String::class.java
                            )
                                .invoke(iwm, user, PACKAGE_NAME)
                        }
                    } catch (_: NoSuchMethodException) {
                        base.getMethod(
                            "getPrivilegedConfiguredNetworks",
                            String::class.java,
                            String::class.java,
                            Bundle::class.java
                        )
                            .invoke(iwm, user, PACKAGE_NAME, null)
                    }
                }

                else -> {
                    try {
                        base.getMethod("getPrivilegedConfiguredNetworks", String::class.java)
                            .invoke(iwm, PACKAGE_NAME)
                    } catch (_: NoSuchMethodException) {
                        try {
                            base.getMethod("getPrivilegedConfiguredNetworks").invoke(iwm)
                        } catch (_: NoSuchMethodException) {
                            base.getMethod("getConfiguredNetworks").invoke(iwm)
                        }
                    }
                }
            }

            val resultList = when (privilegedConfigs) {
                is List<*> -> privilegedConfigs as List<WifiConfiguration>
                null -> listOf()
                else -> {
                    try {
                        privilegedConfigs::class.java.getMethod("getList")
                            .invoke(privilegedConfigs) as List<WifiConfiguration>
                    } catch (_: Exception) {
                        listOf()
                    }
                }
            }
            if (resultList.isEmpty()) throw Exception("empty list")
            return resultList.distinctBy { it.networkId }
        } catch (e: Exception) {
            // Fallback: dropping the password is acceptable here
            Log.w("ShizukuUtil", e.stackTraceToString())
            return getSavedWifiListOld()
        }
    }


    /**
     * Runs a command
     * @param command Command text
     * @param onOutputReceived Callback invoked for each output line
     * @param onCommandFinished Callback invoked when the command finishes
     * @return A function that stops execution
     */
    fun executeCommand(
        command: String,
        onOutputReceived: Consumer<String>?,
        onCommandFinished: Consumer<CommandRunner.CommandResult>?
    ): Runnable {
        val isCancelled = AtomicBoolean(false)
        val isRunning = AtomicBoolean(true)

        val allOutput = StringBuilder()
        val processHolder = arrayOfNulls<Process>(1)

        val outputThread = Thread {
            var exitCode = -1
            try {
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                val cmd = CommandRunner.parseCommand(command)

                val process = newProcessMethod.invoke(null, cmd, null, "/") as Process
                processHolder[0] = process

                val inputStream = process.inputStream
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String? = null

                while (isRunning.get() && (reader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) break

                    allOutput.append(line).append("\n")
                    onOutputReceived?.accept(line ?: "")
                }

                try {
                    exitCode = process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                val errorStream = process.errorStream
                val errorReader = BufferedReader(InputStreamReader(errorStream))
                while (isRunning.get() && (errorReader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) {
                        break
                    }
                    allOutput.append(line).append("\n")
                    onOutputReceived?.accept(line ?: "")
                }

                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                if (!isCancelled.get()) {
                    onCommandFinished?.accept(
                        CommandRunner.CommandResult(
                            allOutput.toString(),
                            exitCode
                        )
                    )
                }
            } catch (e: Exception) {
                if (!isCancelled.get()) {
                    onCommandFinished?.accept(
                        CommandRunner.CommandResult(
                            e.stackTraceToString(),
                            exitCode
                        )
                    )
                }
            } finally {
                isRunning.set(false)
            }
        }

        outputThread.start()

        return Runnable {
            isCancelled.set(true)
            isRunning.set(false)

            processHolder[0]?.destroy()
            outputThread.interrupt()
        }
    }

    /**
     * Runs a command synchronously and returns its full output.
     * @param command Command text
     * @return CompletableFuture with the full command output
     */
    fun executeCommandSync(command: String): CommandRunner.CommandResult {
        val future = CompletableFuture<CommandRunner.CommandResult?>()

        executeCommand(command, null) { future.complete(it) }

        try {
            return future.get() ?: CommandRunner.CommandResult("", -1)
        } catch (e: ExecutionException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }
}