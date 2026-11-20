package com.takemaster.studio.ui

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.ui.PlayerView
import com.takemaster.studio.R
import com.takemaster.studio.audio.AudioAligner
import com.takemaster.studio.audio.DemoSessionGenerator
import com.takemaster.studio.export.ExportHandle
import com.takemaster.studio.export.Media3CompExporter
import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.ExportConfig
import com.takemaster.studio.model.ExportOutput
import com.takemaster.studio.model.PlaybackMode
import com.takemaster.studio.model.TakeItem
import com.takemaster.studio.model.TimelineTool
import com.takemaster.studio.model.takeColorForIndex
import com.takemaster.studio.player.CompPlayerController
import com.takemaster.studio.timeline.CompTimelineEngine
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

@Composable
fun StudioScreen(
    playerController: CompPlayerController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var takes by remember { mutableStateOf<List<TakeItem>>(emptyList()) }
    var segments by remember { mutableStateOf<List<CompSegment>>(emptyList()) }
    var undoStack by remember { mutableStateOf<List<List<CompSegment>>>(emptyList()) }
    var masterDurationSec by remember { mutableDoubleStateOf(0.0) }

    var playheadSec by remember { mutableDoubleStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackMode by remember { mutableStateOf(PlaybackMode.MASTER_COMP) }
    var focusedTakeId by remember { mutableStateOf<String?>(null) }
    var auditionTakeId by remember { mutableStateOf<String?>(null) }
    var isMultiAngleView by remember { mutableStateOf(false) }
    var selectedSegmentId by remember { mutableStateOf<String?>(null) }
    var activeTool by remember { mutableStateOf(TimelineTool.PAN_SCROLL) }
    var zoomMultiplier by remember { mutableFloatStateOf(1f) }
    var isDraggingBoundary by remember { mutableStateOf(false) }

    var isLoadingTakes by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var errorBanner by remember { mutableStateOf<String?>(null) }

    var syncNudgeTakeId by remember { mutableStateOf<String?>(null) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showUserGuide by remember { mutableStateOf(false) }
    var exportConfig by remember { mutableStateOf(ExportConfig()) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgressPct by remember { mutableIntStateOf(0) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportOutput by remember { mutableStateOf<ExportOutput?>(null) }
    var activeExportHandle by remember { mutableStateOf<ExportHandle?>(null) }

    var lastScrubSeekMs by remember { mutableLongStateOf(0L) }
    var sessionGeneration by remember { mutableIntStateOf(0) }
    var lastSyncedSessionGen by remember { mutableIntStateOf(0) }

    DisposableEffect(playerController) {
        playerController.onPlayheadChanged = { sec -> playheadSec = sec }
        playerController.onPlayingChanged = { playing -> isPlaying = playing }
        onDispose {
            playerController.onPlayheadChanged = {}
            playerController.onPlayingChanged = {}
        }
    }

    // Keep PlayerController synced with state without interrupting continuous PCM playback
    LaunchedEffect(
        takes,
        segments,
        masterDurationSec,
        playbackMode,
        focusedTakeId,
        auditionTakeId,
        isMultiAngleView,
        exportConfig.audioMode,
        exportConfig.lockedTakeId,
        exportConfig.crossfadeMs,
        isDraggingBoundary,
        sessionGeneration
    ) {
        if (!isDraggingBoundary) {
            playerController.updateTimeline(
                takes = takes,
                segments = segments,
                masterDurationSec = masterDurationSec,
                mode = playbackMode,
                focusedTakeId = focusedTakeId,
                audioMode = exportConfig.audioMode,
                lockedTakeId = exportConfig.lockedTakeId,
                isMultiAngleView = isMultiAngleView,
                auditionTakeId = auditionTakeId,
                crossfadeMs = exportConfig.crossfadeMs
            )
            if (lastSyncedSessionGen != sessionGeneration) {
                lastSyncedSessionGen = sessionGeneration
                playerController.seekTo(playheadSec)
            }
        }
    }

    fun pushUndo(snapshot: List<CompSegment>) {
        undoStack = (undoStack + listOf(snapshot)).takeLast(25)
    }

    fun updateSegmentsWithUndo(next: List<CompSegment>) {
        if (next != segments) {
            pushUndo(segments)
            segments = next
        }
    }

    fun handlePickedUris(uris: List<Uri>, append: Boolean) {
        if (uris.isEmpty()) return
        playerController.pause()
        isLoadingTakes = true
        errorBanner = null
        loadingMessage = "Preparing ${uris.size} video${if (uris.size == 1) "" else "s"}..."
        scope.launch {
            try {
                val baseTakes = if (append) takes else emptyList()
                val alignedNew = AudioAligner.alignTakes(
                    context = context,
                    uris = uris,
                    startIndex = baseTakes.size,
                    referenceTake = baseTakes.firstOrNull(),
                    onProgress = { _, _, msg -> loadingMessage = msg }
                )
                val mergedTakes = baseTakes + alignedNew
                val (layoutTakes, dur) = CompTimelineEngine.recomputeMasterLayout(mergedTakes)
                takes = layoutTakes
                masterDurationSec = dur
                AudioAligner.cleanupUnusedPcmCache(context, layoutTakes.mapNotNull { it.pcmPath }.toSet())

                if (!append || segments.isEmpty()) {
                    val initSegs = CompTimelineEngine.buildInitialSegments(layoutTakes)
                    segments = initSegs
                    undoStack = emptyList()
                    selectedSegmentId = initSegs.firstOrNull()?.id
                    focusedTakeId = layoutTakes.firstOrNull()?.id
                    auditionTakeId = null
                    isMultiAngleView = false
                    playbackMode = PlaybackMode.MASTER_COMP
                    exportOutput = null
                    exportError = null
                    playheadSec = initSegs.firstOrNull()?.startMasterSec ?: 0.0
                    zoomMultiplier = 1f
                    sessionGeneration++
                } else {
                    segments = CompTimelineEngine.repairSegmentsForTakes(segments, layoutTakes)
                }
            } catch (e: Exception) {
                errorBanner = "Could not load videos: ${e.localizedMessage ?: "unknown error"}"
            } finally {
                isLoadingTakes = false
                loadingMessage = ""
            }
        }
    }

    fun loadDemoSession() {
        playerController.pause()
        isLoadingTakes = true
        errorBanner = null
        loadingMessage = "Synthesizing & aligning 3-take demo session..."
        scope.launch {
            try {
                val demoTakes = DemoSessionGenerator.createDemoTakes(context)
                val (layoutTakes, dur) = CompTimelineEngine.recomputeMasterLayout(demoTakes)
                takes = layoutTakes
                masterDurationSec = dur
                AudioAligner.cleanupUnusedPcmCache(context, layoutTakes.mapNotNull { it.pcmPath }.toSet())
                val autoSegs = CompTimelineEngine.buildAutoSplitSegments(
                    layoutTakes,
                    snapToValley = exportConfig.snapToQuietValley
                )
                segments = autoSegs
                undoStack = emptyList()
                selectedSegmentId = autoSegs.firstOrNull()?.id
                focusedTakeId = layoutTakes.firstOrNull()?.id
                auditionTakeId = null
                isMultiAngleView = false
                exportOutput = null
                exportError = null
                playbackMode = PlaybackMode.MASTER_COMP
                playheadSec = autoSegs.firstOrNull()?.startMasterSec ?: 0.0
                zoomMultiplier = 1f
                sessionGeneration++
            } catch (e: Exception) {
                errorBanner = "Could not load demo session: ${e.localizedMessage ?: "unknown error"}"
            } finally {
                isLoadingTakes = false
                loadingMessage = ""
            }
        }
    }

    val replacePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(6)
    ) { uris -> handlePickedUris(uris, append = false) }

    val appendPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(6)
    ) { uris -> handlePickedUris(uris, append = true) }

    fun scrubTo(sec: Double) {
        playheadSec = sec.coerceIn(0.0, masterDurationSec)
        val now = System.currentTimeMillis()
        if (now - lastScrubSeekMs >= 45L) {
            lastScrubSeekMs = now
            playerController.seekTo(playheadSec)
        }
    }

    fun seekExact(sec: Double) {
        val clamped = sec.coerceIn(0.0, masterDurationSec)
        playheadSec = clamped
        playerController.seekTo(clamped)
    }

    val activeCompSeg = CompTimelineEngine.findSegmentAt(segments, playheadSec)
    val activeTake = when {
        playbackMode == PlaybackMode.MASTER_COMP && auditionTakeId != null ->
            takes.find { it.id == auditionTakeId } ?: takes.firstOrNull()
        playbackMode == PlaybackMode.MASTER_COMP ->
            takes.find { it.id == activeCompSeg?.takeId } ?: takes.firstOrNull()
        else ->
            takes.find { it.id == focusedTakeId } ?: takes.firstOrNull()
    }

    val transportBarContent = @Composable {
        TransportAndModeBar(
            isPlaying = isPlaying,
            playbackMode = playbackMode,
            onTogglePlay = { playerController.togglePlayPause() },
            onPrevCut = {
                val prevCut = segments.map { it.startMasterSec }
                    .filter { it < playheadSec - 0.12 }
                    .maxOrNull() ?: 0.0
                seekExact(prevCut)
            },
            onNextCut = {
                val boundaries = (segments.map { it.startMasterSec } + segments.map { it.endMasterSec }).distinct().sorted()
                val nextCut = boundaries.firstOrNull { it > playheadSec + 0.12 } ?: masterDurationSec
                seekExact(nextCut)
            },
            onChangeMode = { mode ->
                playbackMode = mode
                if (mode == PlaybackMode.COMPARE_TAKES) {
                    auditionTakeId = null
                    if (focusedTakeId == null) {
                        focusedTakeId = activeTake?.id ?: takes.firstOrNull()?.id
                    }
                }
            }
        )
    }

    val quickAuditionContent = @Composable {
        QuickAuditionStrip(
            takes = takes,
            playbackMode = playbackMode,
            auditionTakeId = auditionTakeId,
            focusedTakeId = focusedTakeId,
            activeCompTakeId = activeCompSeg?.takeId,
            onSelectAuditionTake = { takeId ->
                if (playbackMode == PlaybackMode.COMPARE_TAKES) {
                    focusedTakeId = takeId
                } else {
                    auditionTakeId = if (auditionTakeId == takeId) null else takeId
                }
            },
            onClearAudition = { auditionTakeId = null }
        )
    }

    val timelineSectionContent: @Composable (Modifier) -> Unit = { containerMod ->
        Column(
            modifier = containerMod,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TimelineToolbar(
                activeTool = activeTool,
                zoomMultiplier = zoomMultiplier,
                canUndo = undoStack.isNotEmpty(),
                hasMultiTakes = takes.size > 1,
                onSelectTool = { activeTool = it },
                onSplitAtPlayhead = {
                    updateSegmentsWithUndo(
                        CompTimelineEngine.splitAt(
                            segments = segments,
                            rawMasterSec = playheadSec,
                            takes = takes,
                            snapToValley = exportConfig.snapToQuietValley
                        )
                    )
                },
                onAutoSplit = {
                    val autoSegs = CompTimelineEngine.buildAutoSplitSegments(
                        takes = takes,
                        snapToValley = exportConfig.snapToQuietValley
                    )
                    updateSegmentsWithUndo(autoSegs)
                    selectedSegmentId = autoSegs.firstOrNull()?.id
                },
                onResetToTake1 = {
                    val initSegs = CompTimelineEngine.buildInitialSegments(takes)
                    updateSegmentsWithUndo(initSegs)
                    selectedSegmentId = initSegs.firstOrNull()?.id
                },
                onUndo = {
                    if (undoStack.isNotEmpty()) {
                        segments = undoStack.last()
                        undoStack = undoStack.dropLast(1)
                    }
                },
                onZoomOut = { zoomMultiplier = (zoomMultiplier / 1.5f).coerceAtLeast(1f) },
                onZoomIn = { zoomMultiplier = (zoomMultiplier * 1.5f).coerceAtMost(12f) }
            )

            TimelineEditor(
                takes = takes,
                segments = segments,
                masterDurationSec = masterDurationSec,
                playheadSec = playheadSec,
                isPlaying = isPlaying,
                zoomMultiplier = zoomMultiplier,
                activeTool = activeTool,
                playbackMode = playbackMode,
                focusedTakeId = focusedTakeId,
                selectedSegmentId = selectedSegmentId,
                onScrubPlayhead = { scrubTo(it) },
                onSelectSegment = { selectedSegmentId = it },
                onSplitAt = { sec, targetTakeId ->
                    seekExact(sec)
                    val updated = CompTimelineEngine.splitAndSwitchTake(
                        segments = segments,
                        takes = takes,
                        rawMasterSec = sec,
                        targetTakeId = targetTakeId,
                        snapToValley = exportConfig.snapToQuietValley
                    )
                    updateSegmentsWithUndo(updated)
                    selectedSegmentId = updated.firstOrNull {
                        it.endMasterSec > sec + CompTimelineEngine.MIN_SEGMENT_SEC * 0.5 &&
                            it.startMasterSec <= sec + CompTimelineEngine.MIN_SEGMENT_SEC
                    }?.id ?: CompTimelineEngine.findSegmentAt(updated, sec)?.id
                },
                onSwitchSegmentTake = { segId, takeId ->
                    updateSegmentsWithUndo(
                        CompTimelineEngine.assignSegmentToTake(segments, takes, segId, takeId)
                    )
                },
                onPaintRange = { s, e, takeId ->
                    updateSegmentsWithUndo(
                        CompTimelineEngine.paintRangeToTake(
                            segments = segments,
                            takes = takes,
                            rawStartSec = s,
                            rawEndSec = e,
                            takeId = takeId,
                            snapToValley = exportConfig.snapToQuietValley
                        )
                    )
                },
                onMoveCutBoundary = { leftIdx, cutSec ->
                    segments = CompTimelineEngine.moveCutBoundary(segments, takes, leftIdx, cutSec)
                },
                onTrimOuterStart = { startSec ->
                    segments = CompTimelineEngine.trimOuterStart(segments, takes, startSec)
                },
                onTrimOuterEnd = { endSec ->
                    segments = CompTimelineEngine.trimOuterEnd(segments, takes, endSec)
                },
                onBoundaryDragStarted = {
                    isDraggingBoundary = true
                    pushUndo(segments)
                },
                onBoundaryDragEnded = {
                    isDraggingBoundary = false
                },
                onToggleMute = { takeId ->
                    takes = takes.map { t ->
                        if (t.id == takeId) t.copy(isMuted = !t.isMuted) else t
                    }
                    if (playbackMode == PlaybackMode.MASTER_COMP) {
                        focusedTakeId = takeId
                        playbackMode = PlaybackMode.COMPARE_TAKES
                    }
                },
                onToggleSolo = { takeId ->
                    takes = takes.map { t ->
                        if (t.id == takeId) t.copy(isSolo = !t.isSolo) else t
                    }
                    focusedTakeId = takeId
                    playbackMode = PlaybackMode.COMPARE_TAKES
                },
                onFocusTake = { takeId ->
                    focusedTakeId = takeId
                    if (playbackMode == PlaybackMode.COMPARE_TAKES) {
                        playerController.seekTo(playheadSec)
                    }
                },
                onOpenSyncNudge = { take -> syncNudgeTakeId = take.id },
                modifier = Modifier.weight(1f)
            )

            val selectedSeg = segments.find { it.id == selectedSegmentId }
            if (selectedSeg != null) {
                SelectedSegmentBar(
                    segment = selectedSeg,
                    segmentIndex = segments.indexOf(selectedSeg),
                    totalSegments = segments.size,
                    takes = takes,
                    onSwitchTake = { takeId ->
                        updateSegmentsWithUndo(
                            CompTimelineEngine.assignSegmentToTake(segments, takes, selectedSeg.id, takeId)
                        )
                    },
                    onPlaySegment = {
                        playbackMode = PlaybackMode.MASTER_COMP
                        auditionTakeId = null
                        seekExact(selectedSeg.startMasterSec)
                        playerController.play()
                    },
                    onMergePrev = {
                        updateSegmentsWithUndo(
                            CompTimelineEngine.mergeSegmentWithPrevious(segments, takes, selectedSeg.id)
                        )
                    },
                    onDelete = {
                        updateSegmentsWithUndo(
                            CompTimelineEngine.deleteSegment(segments, selectedSeg.id)
                        )
                        selectedSegmentId = null
                    }
                )
            }

            if (segments.isNotEmpty()) {
                CutDecisionListBar(
                    segments = segments,
                    takes = takes,
                    selectedSegmentId = selectedSegmentId,
                    playheadSec = playheadSec,
                    onSelectCut = { seg ->
                        selectedSegmentId = seg.id
                        seekExact(seg.startMasterSec)
                    }
                )
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E17))
            .systemBarsPadding(),
        color = Color(0xFF0A0E17)
    ) {
        if (takes.isEmpty() && !isLoadingTakes) {
            EmptyStudioLanding(
                errorBanner = errorBanner,
                onPickTakes = {
                    replacePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                onLoadDemo = { loadDemoSession() },
                onOpenUserGuide = { showUserGuide = true }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StudioTopBar(
                    takesCount = takes.size,
                    masterDurationSec = masterDurationSec,
                    canAddMore = takes.size < 6,
                    onNewProject = {
                        replacePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    onAddTake = {
                        appendPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    onOpenExport = {
                        playerController.pause()
                        exportError = null
                        showExportSheet = true
                    },
                    onOpenUserGuide = { showUserGuide = true }
                )

                if (errorBanner != null) {
                    Surface(
                        color = Color(0xFF7F1D1D).copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorBanner!!,
                            color = Color(0xFFFCA5A5),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.38f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            VideoPreviewCard(
                                playerController = playerController,
                                takes = takes,
                                segments = segments,
                                activeTake = activeTake,
                                playbackMode = playbackMode,
                                isMultiAngleView = isMultiAngleView,
                                auditionTakeId = auditionTakeId,
                                crossfadeMs = exportConfig.crossfadeMs,
                                playheadSec = playheadSec,
                                masterDurationSec = masterDurationSec,
                                isPlaying = isPlaying,
                                onToggleMultiAngle = { isMultiAngleView = it },
                                onSelectTile = { tileTakeId ->
                                    if (playbackMode == PlaybackMode.COMPARE_TAKES) {
                                        focusedTakeId = tileTakeId
                                    } else {
                                        auditionTakeId = if (auditionTakeId == tileTakeId) null else tileTakeId
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                            transportBarContent()
                            if (takes.size > 1) {
                                quickAuditionContent()
                            }
                        }

                        timelineSectionContent(
                            Modifier
                                .weight(0.62f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    VideoPreviewCard(
                        playerController = playerController,
                        takes = takes,
                        segments = segments,
                        activeTake = activeTake,
                        playbackMode = playbackMode,
                        isMultiAngleView = isMultiAngleView,
                        auditionTakeId = auditionTakeId,
                        crossfadeMs = exportConfig.crossfadeMs,
                        playheadSec = playheadSec,
                        masterDurationSec = masterDurationSec,
                        isPlaying = isPlaying,
                        onToggleMultiAngle = { isMultiAngleView = it },
                        onSelectTile = { tileTakeId ->
                            if (playbackMode == PlaybackMode.COMPARE_TAKES) {
                                focusedTakeId = tileTakeId
                            } else {
                                auditionTakeId = if (auditionTakeId == tileTakeId) null else tileTakeId
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                    transportBarContent()
                    if (takes.size > 1) {
                        quickAuditionContent()
                    }
                    timelineSectionContent(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }

        if (isLoadingTakes) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0E17).copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0xFF121826),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .padding(24.dp)
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                        Text(
                            text = "Analyzing & Syncing Takes",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = loadingMessage,
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    val nudgeTarget = takes.find { it.id == syncNudgeTakeId }
    if (nudgeTarget != null) {
        SyncNudgeDialog(
            take = nudgeTarget,
            canRemove = takes.size > 1,
            onAdjustNudgeMs = { delta ->
                val updated = takes.map { t ->
                    if (t.id == nudgeTarget.id) t.copy(manualNudgeMs = t.manualNudgeMs + delta) else t
                }
                val (layoutTakes, dur) = CompTimelineEngine.recomputeMasterLayout(updated)
                takes = layoutTakes
                masterDurationSec = dur
                segments = CompTimelineEngine.repairSegmentsForTakes(segments, layoutTakes)
            },
            onResetNudge = {
                val updated = takes.map { t ->
                    if (t.id == nudgeTarget.id) t.copy(manualNudgeMs = 0) else t
                }
                val (layoutTakes, dur) = CompTimelineEngine.recomputeMasterLayout(updated)
                takes = layoutTakes
                masterDurationSec = dur
                segments = CompTimelineEngine.repairSegmentsForTakes(segments, layoutTakes)
            },
            onRemoveTake = {
                val remaining = takes.filterNot { it.id == nudgeTarget.id }.mapIndexed { i, t ->
                    t.copy(index = i, label = "Take ${i + 1}")
                }
                val (layoutTakes, dur) = CompTimelineEngine.recomputeMasterLayout(remaining)
                takes = layoutTakes
                masterDurationSec = dur
                segments = CompTimelineEngine.repairSegmentsForTakes(segments, layoutTakes)
                AudioAligner.cleanupUnusedPcmCache(context, layoutTakes.mapNotNull { it.pcmPath }.toSet())
                if (focusedTakeId == nudgeTarget.id) focusedTakeId = layoutTakes.firstOrNull()?.id
                if (auditionTakeId == nudgeTarget.id) auditionTakeId = null
                syncNudgeTakeId = null
            },
            onDismiss = { syncNudgeTakeId = null }
        )
    }

    if (showExportSheet) {
        ExportBottomSheet(
            takes = takes,
            segments = segments,
            config = exportConfig,
            isExporting = isExporting,
            exportProgressPct = exportProgressPct,
            exportError = exportError,
            exportOutput = exportOutput,
            onUpdateConfig = { exportConfig = it },
            onStartExport = {
                isExporting = true
                exportProgressPct = 0
                exportError = null
                exportOutput = null
                activeExportHandle = Media3CompExporter.exportComp(
                    context = context,
                    takes = takes,
                    segments = segments,
                    config = exportConfig,
                    onProgress = { pct -> exportProgressPct = pct },
                    onComplete = { out ->
                        isExporting = false
                        exportProgressPct = 100
                        exportOutput = out
                    },
                    onError = { err ->
                        isExporting = false
                        exportError = err
                    }
                )
            },
            onCancelExport = {
                activeExportHandle?.cancel()
                isExporting = false
            },
            onOpenVideo = { out -> Media3CompExporter.openVideo(context, out.uri) },
            onShareVideo = { out -> Media3CompExporter.shareVideo(context, out.uri) },
            onDismiss = { showExportSheet = false }
        )
    }

    if (showUserGuide) {
        UserGuideDialog(
            onDismiss = { showUserGuide = false }
        )
    }
}

@Composable
private fun EmptyStudioLanding(
    errorBanner: String?,
    onPickTakes: () -> Unit,
    onLoadDemo: () -> Unit,
    onOpenUserGuide: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = Color(0xFF1E293B),
            shape = CircleShape,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Take Master", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Multi-take audio sync & video comping studio for music, vocals, instruments, acting, and performances.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(22.dp))

        val steps = listOf(
            "1. Auto Audio Alignment" to "Overlay Take 1, Take 2, Take 3… aligned to sub-millisecond sync.",
            "2. Audition & Mute / Solo" to "Compare takes side-by-side at any moment in the timeline.",
            "3. Master Comp Timeline" to "Paint, split, and fine-tune cuts, then save directly to Gallery."
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            steps.forEach { (title, desc) ->
                Surface(
                    color = Color(0xFF121826),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(desc, color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }
        }

        if (errorBanner != null) {
            Spacer(Modifier.height(14.dp))
            Text(errorBanner, color = Color(0xFFFCA5A5), fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onPickTakes,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Select Video Takes (2–6)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onLoadDemo,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF38BDF8))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Try 3-Take Demo Session",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E8F0)
            )
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onOpenUserGuide,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = Color(0xFF38BDF8))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "User Guide",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E8F0)
            )
        }
    }
}

@Composable
private fun StudioTopBar(
    takesCount: Int,
    masterDurationSec: Double,
    canAddMore: Boolean,
    onNewProject: () -> Unit,
    onAddTake: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenUserGuide: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Take Master",
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$takesCount take${if (takesCount == 1) "" else "s"} • ${formatTimeCompact(masterDurationSec)}",
                fontSize = 11.5.sp,
                color = Color(0xFF94A3B8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onNewProject,
                modifier = Modifier.height(42.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Pick new takes", modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("New", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }

            if (canAddMore) {
                OutlinedButton(
                    onClick = onAddTake,
                    modifier = Modifier.height(42.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add take", modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Take", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Button(
                onClick = onOpenExport,
                modifier = Modifier.height(42.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.SaveAlt, contentDescription = "Export video", modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF121826))
                    .border(1.dp, Color(0xFF334155), CircleShape)
                    .clickable(onClickLabel = "Open User Guide", onClick = onOpenUserGuide),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    color = Color(0xFF38BDF8),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

private fun createTexturePlayerView(
    context: Context,
    parent: FrameLayout? = null
): PlayerView = (LayoutInflater.from(context).inflate(
    R.layout.texture_player_view,
    parent ?: FrameLayout(context),
    false
) as PlayerView).apply {
    useController = false
    setBackgroundColor(0xFF05080F.toInt())
}

@Composable
private fun VideoPreviewCard(
    playerController: CompPlayerController,
    takes: List<TakeItem>,
    segments: List<CompSegment>,
    activeTake: TakeItem?,
    playbackMode: PlaybackMode,
    isMultiAngleView: Boolean,
    auditionTakeId: String?,
    crossfadeMs: Int,
    playheadSec: Double,
    masterDurationSec: Double,
    isPlaying: Boolean,
    onToggleMultiAngle: (Boolean) -> Unit,
    onSelectTile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = activeTake?.let { takeColorForIndex(it.index) } ?: Color(0xFF38BDF8)
    val playersRevision = playerController.playersRevision
    val videoTakes = remember(takes) { takes.filter { it.hasVideo } }
    val boundPlayers = remember(videoTakes, playersRevision) {
        videoTakes.map { it.id to playerController.playerForTake(it.id) }
    }
    val activeId = activeTake?.id
    val nearInteriorCut = !isMultiAngleView &&
        playbackMode == PlaybackMode.MASTER_COMP &&
        auditionTakeId == null &&
        (0 until segments.size - 1).any { idx ->
            abs(playheadSec - segments[idx].endMasterSec) <= 0.12
        }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF05080F))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .clickable { playerController.togglePlayPause() }
    ) {
        if (isMultiAngleView && takes.size >= 2) {
            MultiAngleSplitGrid(
                playerController = playerController,
                visibleTakes = takes.take(CompPlayerController.MAX_MULTI_ANGLE_TILES),
                activeTake = activeTake,
                auditionTakeId = auditionTakeId,
                playheadSec = playheadSec,
                isPlaying = isPlaying,
                onSelectTile = onSelectTile,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Host video-take PlayerViews inside a single native FrameLayout so every TextureView
            // initializes its hardware SurfaceTexture immediately, players rebind whenever
            // playersRevision updates, and activeTake swaps cleanly via bringToFront().
            if (videoTakes.isNotEmpty()) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            setBackgroundColor(0xFF05080F.toInt())
                        }
                    },
                    update = { container ->
                        val validIds = boundPlayers.mapTo(HashSet()) { it.first }
                        for (i in container.childCount - 1 downTo 0) {
                            val child = container.getChildAt(i) as? PlayerView
                            val tagId = child?.tag as? String
                            if (tagId == null || tagId !in validIds) {
                                child?.player = null
                                container.removeViewAt(i)
                            }
                        }

                        var activePv: PlayerView? = null
                        boundPlayers.forEach { (takeId, targetPlayer) ->
                            var pv: PlayerView? = null
                            for (i in 0 until container.childCount) {
                                val c = container.getChildAt(i) as? PlayerView
                                if (c?.tag == takeId) {
                                    pv = c
                                    break
                                }
                            }
                            if (pv == null) {
                                pv = createTexturePlayerView(container.context, container).apply {
                                    tag = takeId
                                }
                                container.addView(
                                    pv,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                            if (pv.player !== targetPlayer) {
                                pv.player = targetPlayer
                            }
                            pv.visibility = View.VISIBLE
                            pv.alpha = 1f
                            pv.translationZ = if (takeId == activeId) 1f else 0f
                            if (takeId == activeId) {
                                activePv = pv
                            }
                        }

                        if (activePv != null && container.getChildAt(container.childCount - 1) !== activePv) {
                            activePv!!.bringToFront()
                            container.invalidate()
                        }
                    },
                    onRelease = { container ->
                        for (i in 0 until container.childCount) {
                            (container.getChildAt(i) as? PlayerView)?.player = null
                        }
                        container.removeAllViews()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (activeTake == null || !activeTake.hasVideo) {
                DemoStageCanvas(
                    take = activeTake,
                    playheadSec = playheadSec,
                    isPlaying = isPlaying,
                    isCompact = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f)
                )
            }
        }

        // Top-Left Active Mode & Take Badge
        val badgeText = when {
            isMultiAngleView -> "MULTI-ANGLE • ${activeTake?.label ?: "Sync"}"
            playbackMode == PlaybackMode.MASTER_COMP && auditionTakeId != null ->
                "AUDITION LOCK • ${activeTake?.label ?: ""}"
            playbackMode == PlaybackMode.MASTER_COMP -> "COMP • ${activeTake?.label ?: ""}"
            else -> "COMPARE • ${activeTake?.label ?: ""}"
        }
        Surface(
            color = Color(0xFF0A0E17).copy(alpha = 0.84f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(3f)
                .padding(8.dp)
                .border(
                    1.dp,
                    if (auditionTakeId != null) Color(0xFFF59E0B) else accent,
                    RoundedCornerShape(8.dp)
                )
        ) {
            Text(
                text = badgeText,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }

        // Amber Cross-Fade Indicator near cut transitions
        if (nearInteriorCut) {
            Surface(
                color = Color(0xFF92400E).copy(alpha = 0.92f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .zIndex(3f)
                    .padding(8.dp)
                    .border(1.dp, Color(0xFFFBBF24), RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = "CROSSFADE ${crossfadeMs}ms",
                    color = Color(0xFFFEF3C7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }

        // Top-Right Playhead Timestamp
        Surface(
            color = Color(0xFF0A0E17).copy(alpha = 0.82f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(3f)
                .padding(8.dp)
        ) {
            Text(
                text = "${formatTimeShort(playheadSec)} / ${formatTimeCompact(masterDurationSec)}",
                color = Color(0xFFE2E8F0),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }

        // Bottom-Right Multi-Angle / Single Toggle
        if (takes.size >= 2) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .zIndex(3f)
                    .padding(8.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isMultiAngleView) Color(0xFF1D4ED8).copy(alpha = 0.92f)
                        else Color(0xFF0F172A).copy(alpha = 0.88f)
                    )
                    .border(
                        1.dp,
                        if (isMultiAngleView) Color(0xFF60A5FA) else Color(0xFF334155),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onToggleMultiAngle(!isMultiAngleView) }
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isMultiAngleView) Icons.Default.Videocam else Icons.Default.GridView,
                    contentDescription = "Toggle multi-angle view",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (isMultiAngleView) "Follow Comp" else "Multi-Angle",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (!isPlaying && !isMultiAngleView) {
            Surface(
                color = Color(0xFF0A0E17).copy(alpha = 0.58f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(3f)
                    .size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MultiAngleSplitGrid(
    playerController: CompPlayerController,
    visibleTakes: List<TakeItem>,
    activeTake: TakeItem?,
    auditionTakeId: String?,
    playheadSec: Double,
    isPlaying: Boolean,
    onSelectTile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val playersRevision = playerController.playersRevision
    val rows: List<List<TakeItem>> = when {
        visibleTakes.size <= 2 -> listOf(visibleTakes)
        else -> listOf(visibleTakes.take(2), visibleTakes.drop(2).take(2))
    }

    Column(
        modifier = modifier
            .background(Color(0xFF05080F))
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        rows.forEach { rowTakes ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                rowTakes.forEach { take ->
                    val isActive = activeTake?.id == take.id
                    val accent = takeColorForIndex(take.index)
                    val targetPlayer = remember(take.id, playersRevision) {
                        playerController.playerForTake(take.id)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0B111E))
                            .border(
                                width = if (isActive) 2.5.dp else 1.dp,
                                color = if (isActive) accent else Color(0xFF334155),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectTile(take.id) }
                    ) {
                        if (take.hasVideo) {
                            key(take.id) {
                                AndroidView(
                                    factory = { ctx ->
                                        createTexturePlayerView(ctx).apply {
                                            player = targetPlayer
                                        }
                                    },
                                    update = { pv ->
                                        if (pv.player !== targetPlayer) pv.player = targetPlayer
                                    },
                                    onRelease = { pv -> pv.player = null },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            DemoStageCanvas(
                                take = take,
                                playheadSec = playheadSec,
                                isPlaying = isPlaying,
                                isCompact = true,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Surface(
                            color = Color(0xFF0A0E17).copy(alpha = 0.80f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(5.dp)
                        ) {
                            val tag = buildString {
                                append(take.label)
                                if (auditionTakeId == take.id) append(" • LOCK")
                                else if (isActive) append(" • ON")
                                if (take.isMuted) append(" (M)")
                            }
                            Text(
                                text = tag,
                                color = if (isActive) accent else Color(0xFFE2E8F0),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (rowTakes.size == 1 && rows.size > 1) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tap any angle\nto audition",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoStageCanvas(
    take: TakeItem?,
    playheadSec: Double,
    isPlaying: Boolean,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = take?.let { takeColorForIndex(it.index) } ?: Color(0xFF38BDF8)
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(Color(0xFF0B1324), Color(0xFF080D18))
            )
        )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            // Sample instant energy from waveform peaks around current playhead
            val level = if (take != null && take.waveformPeaks.isNotEmpty() && take.coversMasterTime(playheadSec)) {
                val localSec = take.masterToTakeSec(playheadSec)
                val idx = floor(localSec * take.peaksPerSec).toInt().coerceIn(0, take.waveformPeaks.lastIndex)
                take.waveformPeaks[idx].coerceIn(0.05f, 1.0f)
            } else {
                0.08f
            }

            val baseRadius = min(w, h) * (if (isCompact) 0.24f else 0.26f)
            val pulseRadius = baseRadius * (1f + 0.35f * level)
            drawCircle(
                color = accent.copy(alpha = 0.14f + 0.14f * level),
                radius = pulseRadius,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = accent.copy(alpha = 0.65f),
                radius = baseRadius,
                center = Offset(cx, cy),
                style = Stroke(width = if (isCompact) 3f else 4f)
            )

            // Rotating phase marker synchronized to take's playhead
            val angle = (playheadSec * 2.2 + (take?.index ?: 0) * 1.1)
            val dotX = cx + (baseRadius * 0.72f) * cos(angle).toFloat()
            val dotY = cy + (baseRadius * 0.72f) * sin(angle).toFloat()
            drawCircle(
                color = accent,
                radius = if (isCompact) 5f else 7f,
                center = Offset(dotX, dotY)
            )

            // Symmetrical live reactive spectrum bars
            val barCount = if (isCompact) 14 else 24
            val barAreaWidth = w * 0.72f
            val startX = (w - barAreaWidth) / 2f
            val barW = (barAreaWidth / barCount) * 0.6f
            val stepX = barAreaWidth / barCount
            val maxBarH = h * (if (isCompact) 0.34f else 0.42f)

            for (i in 0 until barCount) {
                val sampleT = playheadSec - 0.18 + (i.toDouble() / barCount) * 0.36
                val peak = if (take != null && take.waveformPeaks.isNotEmpty() && take.coversMasterTime(sampleT)) {
                    val loc = take.masterToTakeSec(sampleT)
                    val pIdx = floor(loc * take.peaksPerSec).toInt().coerceIn(0, take.waveformPeaks.lastIndex)
                    take.waveformPeaks[pIdx]
                } else {
                    0.06f
                }
                val barH = (peak * maxBarH).coerceAtLeast(4f)
                val x = startX + i * stepX + (stepX - barW) / 2f
                drawRoundRect(
                    color = accent.copy(alpha = if (isPlaying) 0.42f else 0.25f),
                    topLeft = Offset(x, cy - barH / 2f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }
        }

        if (!isCompact && take != null) {
            Text(
                text = if (take.isDemo) "Demo Synth Stage • ${take.label}" else "Audio Track Only • ${take.label}",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun QuickAuditionStrip(
    takes: List<TakeItem>,
    playbackMode: PlaybackMode,
    auditionTakeId: String?,
    focusedTakeId: String?,
    activeCompTakeId: String?,
    onSelectAuditionTake: (String) -> Unit,
    onClearAudition: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (playbackMode == PlaybackMode.COMPARE_TAKES) "Focus:" else "Audition:",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        if (playbackMode == PlaybackMode.MASTER_COMP) {
            val isAuto = auditionTakeId == null
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isAuto) Color(0xFF2563EB) else Color(0xFF121826))
                    .border(
                        1.dp,
                        if (isAuto) Color(0xFF60A5FA) else Color(0xFF334155),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(onClick = onClearAudition)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Auto Comp",
                    color = if (isAuto) Color.White else Color(0xFFCBD5E1),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        takes.forEach { take ->
            val accent = takeColorForIndex(take.index)
            val isSelected = if (playbackMode == PlaybackMode.COMPARE_TAKES) {
                focusedTakeId == take.id
            } else {
                auditionTakeId == take.id
            }
            val isLiveInComp = playbackMode == PlaybackMode.MASTER_COMP &&
                auditionTakeId == null &&
                activeCompTakeId == take.id

            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            isSelected -> accent.copy(alpha = 0.34f)
                            isLiveInComp -> accent.copy(alpha = 0.14f)
                            else -> Color(0xFF121826)
                        }
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = when {
                            isSelected -> accent
                            isLiveInComp -> accent.copy(alpha = 0.6f)
                            else -> Color(0xFF334155)
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelectAuditionTake(take.id) }
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSelected && playbackMode == PlaybackMode.MASTER_COMP) "${take.label} (Lock)" else take.label,
                    color = if (isSelected || isLiveInComp) Color.White else Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TransportAndModeBar(
    isPlaying: Boolean,
    playbackMode: PlaybackMode,
    onTogglePlay: () -> Unit,
    onPrevCut: () -> Unit,
    onNextCut: () -> Unit,
    onChangeMode: (PlaybackMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(
            onClick = onPrevCut,
            modifier = Modifier.size(46.dp)
        ) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous cut")
        }

        Button(
            onClick = onTogglePlay,
            modifier = Modifier.height(46.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play"
            )
            Spacer(Modifier.width(4.dp))
            Text(if (isPlaying) "Pause" else "Play", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        FilledTonalIconButton(
            onClick = onNextCut,
            modifier = Modifier.size(46.dp)
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next cut")
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF121826))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModePill(
                label = "Comp",
                selected = playbackMode == PlaybackMode.MASTER_COMP,
                onClick = { onChangeMode(PlaybackMode.MASTER_COMP) }
            )
            ModePill(
                label = "Compare",
                selected = playbackMode == PlaybackMode.COMPARE_TAKES,
                onClick = { onChangeMode(PlaybackMode.COMPARE_TAKES) }
            )
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color(0xFF2563EB) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF94A3B8),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TimelineToolbar(
    activeTool: TimelineTool,
    zoomMultiplier: Float,
    canUndo: Boolean,
    hasMultiTakes: Boolean,
    onSelectTool: (TimelineTool) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onAutoSplit: () -> Unit,
    onResetToTake1: () -> Unit,
    onUndo: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (canUndo) {
            OutlinedButton(
                onClick = onUndo,
                modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Undo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        ToolChip(
            icon = Icons.Default.PanTool,
            label = "Pan",
            selected = activeTool == TimelineTool.PAN_SCROLL,
            onClick = { onSelectTool(TimelineTool.PAN_SCROLL) }
        )
        ToolChip(
            icon = Icons.Default.Brush,
            label = "Swipe Comp",
            selected = activeTool == TimelineTool.SWIPE_COMP,
            onClick = { onSelectTool(TimelineTool.SWIPE_COMP) }
        )
        ToolChip(
            icon = Icons.Default.ContentCut,
            label = "Split Tap",
            selected = activeTool == TimelineTool.SPLIT,
            onClick = { onSelectTool(TimelineTool.SPLIT) }
        )

        FilledTonalButton(
            onClick = onSplitAtPlayhead,
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("Split Here", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        if (hasMultiTakes) {
            OutlinedButton(
                onClick = onAutoSplit,
                modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Auto-Split", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        OutlinedButton(
            onClick = onResetToTake1,
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Reset", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF121826))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onZoomOut,
                enabled = zoomMultiplier > 1.05f,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Text(
                text = "${String.format("%.1f", zoomMultiplier)}x",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = onZoomIn,
                enabled = zoomMultiplier < 11.5f,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ToolChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF1E3A8A) else Color(0xFF121826))
            .border(
                1.dp,
                if (selected) Color(0xFF60A5FA) else Color(0xFF334155),
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Color.White else Color(0xFF94A3B8),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFFCBD5E1),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SelectedSegmentBar(
    segment: CompSegment,
    segmentIndex: Int,
    totalSegments: Int,
    takes: List<TakeItem>,
    onSwitchTake: (String) -> Unit,
    onPlaySegment: () -> Unit,
    onMergePrev: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = Color(0xFF121826),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.padding(end = 4.dp)) {
                Text(
                    text = "Part ${segmentIndex + 1}/$totalSegments",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${formatTimeShort(segment.startMasterSec)}–${formatTimeShort(segment.endMasterSec)}",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }

            takes.forEach { take ->
                val active = take.id == segment.takeId
                val accent = takeColorForIndex(take.index)
                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) accent.copy(alpha = 0.30f) else Color(0xFF1A2234))
                        .border(1.5.dp, if (active) accent else Color(0xFF334155), RoundedCornerShape(10.dp))
                        .clickable { onSwitchTake(take.id) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = take.label,
                        color = if (active) Color.White else Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            FilledTonalButton(
                onClick = onPlaySegment,
                modifier = Modifier.height(42.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Play Part", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            if (segmentIndex > 0) {
                OutlinedButton(
                    onClick = onMergePrev,
                    modifier = Modifier.height(42.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Merge Left", fontSize = 12.sp)
                }
            }

            if (totalSegments > 1) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.height(42.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Remove", color = Color(0xFFFCA5A5), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CutDecisionListBar(
    segments: List<CompSegment>,
    takes: List<TakeItem>,
    selectedSegmentId: String?,
    playheadSec: Double,
    onSelectCut: (CompSegment) -> Unit
) {
    val takeMap = takes.associateBy { it.id }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Cuts (${segments.size}):",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 2.dp)
        )
        segments.forEachIndexed { idx, seg ->
            val take = takeMap[seg.takeId]
            val accent = take?.let { takeColorForIndex(it.index) } ?: Color(0xFF64748B)
            val isSelected = seg.id == selectedSegmentId
            val isPlayingHere = playheadSec >= seg.startMasterSec && playheadSec <= seg.endMasterSec

            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            isSelected -> accent.copy(alpha = 0.28f)
                            isPlayingHere -> Color(0xFF1E293B)
                            else -> Color(0xFF121826)
                        }
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected || isPlayingHere) accent else Color(0xFF334155),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelectCut(seg) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(accent, CircleShape)
                )
                Text(
                    text = "#${idx + 1} ${formatTimeShort(seg.startMasterSec)}→${formatTimeShort(seg.endMasterSec)} • ${take?.label ?: "Take"}",
                    color = if (isSelected || isPlayingHere) Color.White else Color(0xFFCBD5E1),
                    fontSize = 11.sp,
                    fontWeight = if (isSelected || isPlayingHere) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
