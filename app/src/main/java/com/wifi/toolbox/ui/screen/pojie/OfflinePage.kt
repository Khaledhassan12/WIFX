package com.wifi.toolbox.ui.screen.pojie

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.offline.MaskGenerator
import com.wifi.toolbox.utils.OfflineAuditManager
import com.wifi.toolbox.utils.PojieStore

/**
 * Offline WPA audit: import a capture, choose a target, then run a dictionary
 * and/or mask attack locally on all cores.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OfflinePage(manager: OfflineAuditManager) {
    val context = LocalContext.current
    val resources = remember { PojieStore.getAll(context) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var includeHistory by remember { mutableStateOf(true) }
    var masks by remember { mutableStateOf(listOf<String>()) }
    var maskInput by remember { mutableStateOf("") }
    var showPresets by remember { mutableStateOf(false) }
    var secondsInput by remember { mutableStateOf(manager.liveSeconds.toString()) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(manager::importCapture) }

    val handshake = manager.selectedHandshake
    val selectedResources = remember(selectedIds, resources) {
        resources.filter { it.id in selectedIds }
    }
    val estimated = remember(selectedIds, masks, resources) {
        manager.estimateCandidates(selectedResources, masks)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------------------------------------------------------- header ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    stringResource(R.string.offline_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.offline_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------------------------------------------------------- import ---
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(Icons.Outlined.FolderOpen, stringResource(R.string.offline_import))
                Text(
                    stringResource(R.string.offline_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !manager.isParsing
                ) {
                    if (manager.isParsing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.offline_parsing))
                    } else {
                        Icon(Icons.Filled.UploadFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.offline_import))
                    }
                }
                manager.captureName?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.offline_capture_file, it),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = manager::clearCapture) {
                            Text(stringResource(R.string.offline_clear))
                        }
                    }
                }
                if (manager.handshakes.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.offline_handshake_count,
                            manager.handshakes.size
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    manager.handshakes.forEachIndexed { index, item ->
                        HandshakeRow(
                            selected = index == manager.selectedIndex,
                            ssid = item.ssid,
                            type = item.displayType,
                            detail = "AP ${item.apMac}  ·  ${item.staMac}",
                            onClick = { manager.select(index) }
                        )
                    }
                }
                manager.error?.let { ErrorText(it) }
            }
        }

        // ------------------------------------------------------ candidates ---
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(Icons.Outlined.Key, stringResource(R.string.offline_candidates))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(checked = includeHistory, onCheckedChange = { includeHistory = it })
                    Text(
                        stringResource(R.string.offline_use_history),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    stringResource(R.string.offline_dictionaries),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                resources.forEach { res ->
                    val checked = res.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIds = if (checked) selectedIds - res.id
                                else selectedIds + res.id
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { value ->
                                selectedIds = if (value) selectedIds + res.id
                                else selectedIds - res.id
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                res.name ?: res.id,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (res.type != 0) {
                                Text(
                                    stringResource(R.string.offline_scripts_skipped),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.offline_masks),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { showPresets = true }) {
                            Text(stringResource(R.string.offline_mask_add))
                        }
                        DropdownMenu(
                            expanded = showPresets,
                            onDismissRequest = { showPresets = false }
                        ) {
                            MaskGenerator.presets(handshake?.ssid.orEmpty()).forEach { preset ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${preset.label}  ·  ${MaskGenerator.formatCount(preset.count)}"
                                        )
                                    },
                                    onClick = {
                                        if (preset.mask !in masks) masks = masks + preset.mask
                                        showPresets = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = maskInput,
                        onValueChange = { maskInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.offline_mask_hint)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            val mask = maskInput.trim()
                            if (mask.isNotEmpty() && runCatching { MaskGenerator.count(mask) }.isSuccess) {
                                if (mask !in masks) masks = masks + mask
                                maskInput = ""
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Add, null)
                    }
                }

                if (masks.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        masks.forEach { mask ->
                            InputChip(
                                selected = false,
                                onClick = { masks = masks - mask },
                                label = {
                                    Text(
                                        "${mask}  ·  ${MaskGenerator.formatCount(
                                            runCatching { MaskGenerator.count(mask) }.getOrDefault(0L)
                                        )}"
                                    )
                                },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }

                if (estimated > 0) {
                    Text(
                        stringResource(
                            R.string.offline_mask_count,
                            MaskGenerator.formatCount(estimated)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (estimated > 5_000_000_000L) {
                        Text(
                            stringResource(R.string.offline_mask_too_big),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------- run ---
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(Icons.Outlined.Bolt, stringResource(R.string.offline_start))
                if (manager.isCracking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.offline_tried, formatNumber(manager.tried)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.offline_speed, formatNumber(manager.speed)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            handshake?.let {
                                manager.startCrack(
                                    handshake = it,
                                    resources = selectedResources,
                                    masks = masks,
                                    includeHistory = includeHistory
                                )
                            }
                        },
                        enabled = !manager.isCracking && handshake != null &&
                            (selectedResources.any { it.type == 0 } || masks.isNotEmpty() ||
                                includeHistory),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (manager.isCracking) stringResource(R.string.offline_running)
                            else stringResource(R.string.offline_start)
                        )
                    }
                    if (manager.isCracking) {
                        OutlinedButton(onClick = manager::cancelCrack) {
                            Text(stringResource(R.string.offline_stop))
                        }
                    }
                }

                manager.foundPassword?.let { password ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Key, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.offline_found, password),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("password", password)
                                )
                            }) {
                                Icon(Icons.Filled.ContentCopy, stringResource(R.string.offline_copy))
                            }
                        }
                    }
                }

                manager.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------------------------------------------------- live capture ---
        OutlinedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(Icons.Outlined.Radar, stringResource(R.string.offline_live))
                Text(
                    stringResource(R.string.offline_live_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = secondsInput,
                        onValueChange = { value ->
                            secondsInput = value
                            value.toIntOrNull()?.let { manager.liveSeconds = it.coerceIn(10, 300) }
                        },
                        label = { Text(stringResource(R.string.offline_seconds)) },
                        singleLine = true,
                        modifier = Modifier.width(130.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { manager.captureLive(handshake?.ssid.orEmpty()) {} },
                        enabled = !manager.isCapturing
                    ) {
                        if (manager.isCapturing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.offline_capture_running))
                        } else {
                            Icon(Icons.Filled.FiberManualRecord, null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    R.string.offline_live_start,
                                    manager.liveSeconds
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HandshakeRow(
    selected: Boolean,
    ssid: String,
    type: String,
    detail: String,
    onClick: () -> Unit
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(ssid, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AssistChip(onClick = onClick, label = { Text(type) })
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}

private fun formatNumber(value: Long): String = when {
    value >= 1_000_000_000L -> "%.1fB".format(value / 1_000_000_000.0)
    value >= 1_000_000L -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000L -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
}
