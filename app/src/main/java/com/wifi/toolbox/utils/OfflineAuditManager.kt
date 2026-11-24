package com.wifi.toolbox.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.offline.HandshakeExtractor
import com.wifi.toolbox.offline.MaskGenerator
import com.wifi.toolbox.offline.OfflineCracker
import com.wifi.toolbox.offline.PcapReader
import com.wifi.toolbox.offline.WpaHandshake
import com.wifi.toolbox.structs.PojieResource
import com.wifi.toolbox.structs.PojieSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Backs the offline audit screen: import a capture, pick a target, run the
 * dictionary/mask cracker on all cores.
 *
 * The heavy lifting lives in the pure-Kotlin `offline` package; this class only
 * adds Android state, file access and the root-based live capture.
 */
class OfflineAuditManager(private val context: Context) {

    private val app: ToolboxApp
        get() = context.applicationContext as ToolboxApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var captureName by mutableStateOf<String?>(null)
        private set
    var handshakes by mutableStateOf<List<WpaHandshake>>(emptyList())
        private set
    var selectedIndex by mutableStateOf(0)
        private set
    var isParsing by mutableStateOf(false)
        private set
    var isCracking by mutableStateOf(false)
        private set
    var isCapturing by mutableStateOf(false)
        private set
    var tried by mutableStateOf(0L)
        private set
    var speed by mutableStateOf(0L)
        private set
    var elapsedMs by mutableStateOf(0L)
        private set
    var foundPassword by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var liveSeconds by mutableStateOf(30)

    private var crackJob: Job? = null

    @Volatile
    private var cancelRequested = false

    val selectedHandshake: WpaHandshake?
        get() = handshakes.getOrNull(selectedIndex)

    fun select(index: Int) {
        if (index in handshakes.indices) selectedIndex = index
    }

    // -------------------------------------------------------------- import ---

    fun importCapture(uri: Uri) {
        if (isParsing) return
        isParsing = true
        error = null
        message = null
        scope.launch {
            try {
                val name = queryDisplayName(uri)
                val result = withContext(Dispatchers.IO) {
                    val extractor = HandshakeExtractor()
                    var packets = 0
                    var stop = false
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        PcapReader.read(input) { packet ->
                            packets++
                            extractor.feed(packet)
                            if (packets >= MAX_PACKETS) stop = true
                            !stop
                        }
                    } ?: throw IllegalStateException("Cannot open capture")
                    extractor.handshakes()
                }
                withContext(Dispatchers.Main) {
                    captureName = name
                    handshakes = result
                    selectedIndex = 0
                    isParsing = false
                    if (result.isEmpty()) {
                        message = context.getString(com.wifi.toolbox.R.string.offline_no_handshakes)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    isParsing = false
                    error = e.message ?: e.toString()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "capture"
    }

    // -------------------------------------------------------------- crack ---

    fun startCrack(
        handshake: WpaHandshake,
        resources: List<PojieResource>,
        masks: List<String>,
        includeHistory: Boolean
    ) {
        if (isCracking) return
        val candidates = buildCandidates(resources, masks, includeHistory)
        if (candidates == null) {
            error = context.getString(com.wifi.toolbox.R.string.offline_no_candidates)
            return
        }
        cancelRequested = false
        foundPassword = null
        error = null
        message = null
        tried = 0
        speed = 0
        elapsedMs = 0
        isCracking = true

        crackJob = scope.launch {
            try {
                val result = OfflineCracker.crack(
                    handshake = handshake,
                    candidates = candidates,
                    threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
                    cancelled = { cancelRequested },
                    onProgress = { total, currentSpeed ->
                        scope.launch(Dispatchers.Main) {
                            tried = total
                            speed = currentSpeed
                            elapsedMs = System.currentTimeMillis() - startedAt
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    foundPassword = result.password
                    tried = result.tried
                    speed = result.speed
                    elapsedMs = result.elapsedMs
                    isCracking = false
                    if (result.password == null) {
                        message = context.getString(com.wifi.toolbox.R.string.offline_not_found)
                    } else {
                        // Remember it like an online success, without wiping the
                        // stored password list or progress for this SSID.
                        app.pojieHistory.recordSuccess(handshake.ssid, result.password)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    isCracking = false
                    error = e.message ?: e.toString()
                }
            }
        }
    }

    private var startedAt = 0L

    fun cancelCrack() {
        cancelRequested = true
        crackJob?.cancel()
        crackJob = null
        isCracking = false
    }

    /**
     * Builds the lazy candidate stream: previously found passwords first,
     * then dictionaries, then masks. Nothing is materialised in memory.
     */
    private fun buildCandidates(
        resources: List<PojieResource>,
        masks: List<String>,
        includeHistory: Boolean
    ): Sequence<String>? {
        val sequences = ArrayList<Sequence<String>>()

        if (includeHistory) {
            val history = app.pojieHistory.historyFlow.value
            val learned = history.mapNotNull { it.password }.distinct()
            if (learned.isNotEmpty()) sequences.add(learned.asSequence())
            val old = history.flatMap { it.passwords }.distinct()
            if (old.isNotEmpty()) sequences.add(old.asSequence())
        }

        resources.filter { it.type == 0 }.forEach { resource ->
            sequences.add(
                resource.content.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            )
        }

        masks.forEach { mask ->
            runCatching { MaskGenerator.sequence(mask) }.getOrNull()?.let { sequences.add(it) }
        }

        if (sequences.isEmpty()) return null
        startedAt = System.currentTimeMillis()
        return sequences.asSequence().flatten()
    }

    /** Total number of combinations, or -1 when unknown. */
    fun estimateCandidates(resources: List<PojieResource>, masks: List<String>): Long {
        var total = 0L
        resources.filter { it.type == 0 }.forEach { resource ->
            val lines = resource.content.count { it == '\n' } + 1
            total = safeAdd(total, lines.toLong())
        }
        masks.forEach { mask ->
            val count = runCatching { MaskGenerator.count(mask) }.getOrDefault(0L)
            total = safeAdd(total, count)
        }
        return total
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    // --------------------------------------------------------- live capture ---

    fun captureLive(ssid: String, onDone: (File?) -> Unit) {
        if (isCapturing) return
        isCapturing = true
        error = null
        message = null
        scope.launch {
            val file = try {
                val settings = PojieSettings.from(
                    context.getSharedPreferences("settings_pojie", Context.MODE_PRIVATE)
                )
                captureToFile(settings, liveSeconds.coerceIn(10, 300))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) { error = e.message ?: e.toString() }
                null
            }
            withContext(Dispatchers.Main) {
                isCapturing = false
                if (file != null && file.length() > 24) {
                    message = context.getString(
                        com.wifi.toolbox.R.string.offline_capture_done,
                        file.length() / 1024
                    )
                    importFile(file, ssid)
                } else if (error == null) {
                    error = context.getString(com.wifi.toolbox.R.string.offline_capture_failed)
                }
                onDone(file)
            }
        }
    }

    private fun importFile(file: File, ssid: String) {
        isParsing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val extractor = HandshakeExtractor()
                PcapReader.read(file) { extractor.feed(it); true }
                extractor.handshakes()
            }
            withContext(Dispatchers.Main) {
                captureName = ssid.ifEmpty { file.name }
                handshakes = result
                selectedIndex = 0
                isParsing = false
                if (result.isEmpty()) {
                    message = context.getString(com.wifi.toolbox.R.string.offline_no_handshakes)
                }
            }
        }
    }

    private suspend fun captureToFile(settings: PojieSettings, seconds: Int): File = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "live_capture.pcap")
        if (cache.exists()) cache.delete()
        val tmp = "/data/local/tmp/wifi_x_capture.pcap"
        val command = buildString {
            append("rm -f ").append(tmp).append("; ")
            append("timeout ").append(seconds)
            append(" tcpdump -i any -s 0 -w ").append(tmp)
            append(" 'ether proto 0x888e' >/dev/null 2>&1; ")
            append("cp ").append(tmp).append(" ").append(cache.absolutePath).append("; ")
            append("chmod 666 ").append(cache.absolutePath).append("; ")
            append("rm -f ").append(tmp)
        }
        runCommand(settings, command)
        cache
    }

    private fun runCommand(settings: PojieSettings, command: String) {
        when (settings.commandMethod) {
            1 -> ShizukuUtil.executeCommandSync(command)
            2 -> AidlServiceHelper.executeCommandSync(app, command)
            3 -> CommandRunner.executeCommandSync(command, true)
            else -> throw IllegalStateException(
                context.getString(com.wifi.toolbox.R.string.offline_requires_root)
            )
        }
    }

    fun clearCapture() {
        captureName = null
        handshakes = emptyList()
        selectedIndex = 0
        foundPassword = null
        message = null
        error = null
    }

    /** Quick sanity check used by the UI before starting a run. */
    fun canCrack(handshake: WpaHandshake): Boolean = handshake.isCrackable

    private companion object {
        const val MAX_PACKETS = 2_000_000
    }
}
