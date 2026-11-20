package com.takemaster.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.ExportAudioMode
import com.takemaster.studio.model.ExportConfig
import com.takemaster.studio.model.ExportOutput
import com.takemaster.studio.model.ExportQuality
import com.takemaster.studio.model.TakeItem
import com.takemaster.studio.model.takeColorForIndex
import kotlin.math.roundToInt

@Composable
fun SyncNudgeDialog(
    take: TakeItem,
    canRemove: Boolean,
    onAdjustNudgeMs: (Int) -> Unit,
    onResetNudge: () -> Unit,
    onRemoveTake: () -> Unit,
    onDismiss: () -> Unit
) {
    val accent = takeColorForIndex(take.index)
    val autoMs = (take.autoOffsetSec * 1000.0).roundToInt()
    val totalMs = (take.effectiveOffsetSec * 1000.0).roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121826),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${take.label} Sync & Info", color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        text = take.sourceName,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = Color(0xFF1A2234),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Resolution: ${take.displayWidth}×${take.displayHeight} • Duration: ${formatTimeShort(take.durationSec)}",
                            fontSize = 12.sp,
                            color = Color(0xFFCBD5E1)
                        )
                        Text(
                            text = if (take.hasAudio) {
                                "Auto Sync: ${if (autoMs >= 0) "+" else ""}$autoMs ms (${String.format("%.1f", take.confidencePct)}% match)"
                            } else {
                                "No audio track detected (use manual nudge)"
                            },
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = "Manual Nudge: ${if (take.manualNudgeMs >= 0) "+" else ""}${take.manualNudgeMs} ms  (Total: ${if (totalMs >= 0) "+" else ""}$totalMs ms)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Text("Fine Sync Adjustment", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(-20, -5, -1).forEach { delta ->
                        OutlinedButton(
                            onClick = { onAdjustNudgeMs(delta) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("${delta}ms", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 5, 20).forEach { delta ->
                        OutlinedButton(
                            onClick = { onAdjustNudgeMs(delta) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("+${delta}ms", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onResetNudge,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Text("Reset Nudge", fontSize = 12.sp)
                    }
                    if (canRemove) {
                        Button(
                            onClick = onRemoveTake,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B)),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Remove", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.height(46.dp)
            ) {
                Text("Done")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(
    takes: List<TakeItem>,
    segments: List<CompSegment>,
    config: ExportConfig,
    isExporting: Boolean,
    exportProgressPct: Int,
    exportError: String?,
    exportOutput: ExportOutput?,
    onUpdateConfig: (ExportConfig) -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onOpenVideo: (ExportOutput) -> Unit,
    onShareVideo: (ExportOutput) -> Unit,
    onDismiss: () -> Unit
) {
    val totalCompDuration = segments.sumOf { it.durationSec }
    val isDemoOnly = takes.any { it.isDemo } || takes.none { it.hasVideo }

    ModalBottomSheet(
        onDismissRequest = { if (!isExporting) onDismiss() },
        containerColor = Color(0xFF121826),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Export Master Video", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        text = "${segments.size} section${if (segments.size == 1) "" else "s"} • ${formatTimeShort(totalCompDuration)} • Saves to Movies/TakeMaster",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                if (!isExporting) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                    }
                }
            }

            if (exportOutput != null) {
                Surface(
                    color = Color(0xFF064E3B).copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF10B981), RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Saved to Gallery!", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        val sizeMb = exportOutput.sizeBytes / (1024.0 * 1024.0)
                        Text(
                            text = "${exportOutput.relativePath} (${String.format("%.1f", sizeMb)} MB)",
                            fontSize = 13.sp,
                            color = Color(0xFFD1FAE5)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onOpenVideo(exportOutput) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Play Video", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onShareVideo(exportOutput) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Share", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (exportError != null) {
                Surface(
                    color = Color(0xFF7F1D1D).copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = exportError,
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (isExporting) {
                Surface(
                    color = Color(0xFF1A2234),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Stitching & encoding video... $exportProgressPct%",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        LinearProgressIndicator(
                            progress = { (exportProgressPct / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        OutlinedButton(
                            onClick = onCancelExport,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text("Cancel Export", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Audio Mix Mode
                Text("Audio Stitching Mode", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChoiceChipBox(
                        title = "Active Take Audio",
                        subtitle = "Follows each cut",
                        selected = config.audioMode == ExportAudioMode.FOLLOW_ACTIVE_TAKE,
                        onClick = { onUpdateConfig(config.copy(audioMode = ExportAudioMode.FOLLOW_ACTIVE_TAKE)) },
                        modifier = Modifier.weight(1f)
                    )
                    ChoiceChipBox(
                        title = "Lock Audio Track",
                        subtitle = "Uncut audio + video cuts",
                        selected = config.audioMode == ExportAudioMode.LOCK_TO_TAKE,
                        onClick = {
                            val defaultLock = config.lockedTakeId ?: takes.firstOrNull { it.hasAudio }?.id
                            onUpdateConfig(config.copy(audioMode = ExportAudioMode.LOCK_TO_TAKE, lockedTakeId = defaultLock))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (config.audioMode == ExportAudioMode.FOLLOW_ACTIVE_TAKE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Equal-Power Cross-Fade (2–50 ms)",
                            fontSize = 13.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Surface(
                            color = Color(0xFF1E3A8A),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${config.crossfadeMs} ms",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Slider(
                        value = config.crossfadeMs.toFloat().coerceIn(2f, 50f),
                        onValueChange = { ms ->
                            onUpdateConfig(config.copy(crossfadeMs = ms.roundToInt().coerceIn(2, 50)))
                        },
                        valueRange = 2f..50f,
                        steps = 47
                    )
                } else {
                    Text("Select Continuous Audio Source", fontSize = 13.sp, color = Color(0xFF94A3B8))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        takes.filter { it.hasAudio }.forEach { take ->
                            val selected = config.lockedTakeId == take.id
                            val accent = takeColorForIndex(take.index)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) accent.copy(alpha = 0.28f) else Color(0xFF1A2234))
                                    .border(1.dp, if (selected) accent else Color(0xFF334155), RoundedCornerShape(10.dp))
                                    .clickable { onUpdateConfig(config.copy(lockedTakeId = take.id)) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(take.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    val locked = takes.find { it.id == config.lockedTakeId }
                    if (locked != null && segments.isNotEmpty()) {
                        val compStart = segments.first().startMasterSec
                        val compEnd = segments.last().endMasterSec
                        if (locked.trackStartOnMasterSec > compStart + 0.1 || locked.trackEndOnMasterSec < compEnd - 0.1) {
                            Text(
                                text = "Note: ${locked.label} covers ${formatTimeShort(locked.trackStartOnMasterSec)}–${formatTimeShort(locked.trackEndOnMasterSec)}.",
                                color = Color(0xFFFBBF24),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Smart transient valley snapping toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A2234))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                        .clickable { onUpdateConfig(config.copy(snapToQuietValley = !config.snapToQuietValley)) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Snap cuts to quietest valley",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Shifts splits & swipes into nearby quiet frames so transients stay intact",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = config.snapToQuietValley,
                        onCheckedChange = { checked ->
                            onUpdateConfig(config.copy(snapToQuietValley = checked))
                        }
                    )
                }

                // Resolution
                Text("Output Quality", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportQuality.entries.forEach { q ->
                        val selected = config.quality == q
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Color(0xFF2563EB) else Color(0xFF1A2234))
                                .border(1.dp, if (selected) Color(0xFF60A5FA) else Color(0xFF334155), RoundedCornerShape(10.dp))
                                .clickable { onUpdateConfig(config.copy(quality = q)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(q.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (isDemoOnly) {
                    Surface(
                        color = Color(0xFF78350F).copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            text = "Demo session takes contain synthesized multi-track audio only. Tap \"New\" and select 2–6 camera videos from your phone to export a stitched MP4 to Gallery.",
                            color = Color(0xFFFDE68A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = onStartExport,
                    enabled = !isDemoOnly && segments.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = when {
                            isDemoOnly -> "Load Camera Videos to Export MP4"
                            exportOutput == null -> "Save Video to Gallery"
                            else -> "Export Again"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceChipBox(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF1E3A8A).copy(alpha = 0.6f) else Color(0xFF1A2234))
            .border(1.dp, if (selected) Color(0xFF60A5FA) else Color(0xFF334155), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp)
    }
}
