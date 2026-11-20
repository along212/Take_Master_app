package com.takemaster.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.PlaybackMode
import com.takemaster.studio.model.TakeItem
import com.takemaster.studio.model.TimelineTool
import com.takemaster.studio.model.takeColorForIndex
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val HEADER_WIDTH: Dp = 114.dp
private val RULER_HEIGHT: Dp = 36.dp
private val MASTER_LANE_HEIGHT: Dp = 68.dp
private val TAKE_LANE_HEIGHT: Dp = 90.dp

@Composable
fun TimelineEditor(
    takes: List<TakeItem>,
    segments: List<CompSegment>,
    masterDurationSec: Double,
    playheadSec: Double,
    isPlaying: Boolean,
    zoomMultiplier: Float,
    activeTool: TimelineTool,
    playbackMode: PlaybackMode,
    focusedTakeId: String?,
    selectedSegmentId: String?,
    onScrubPlayhead: (Double) -> Unit,
    onSelectSegment: (String?) -> Unit,
    onSplitAt: (masterSec: Double, targetTakeId: String?) -> Unit,
    onSwitchSegmentTake: (segmentId: String, takeId: String) -> Unit,
    onPaintRange: (startSec: Double, endSec: Double, takeId: String) -> Unit,
    onMoveCutBoundary: (leftIndex: Int, newCutSec: Double) -> Unit,
    onTrimOuterStart: (Double) -> Unit,
    onTrimOuterEnd: (Double) -> Unit,
    onBoundaryDragStarted: () -> Unit,
    onBoundaryDragEnded: () -> Unit,
    onToggleMute: (String) -> Unit,
    onToggleSolo: (String) -> Unit,
    onFocusTake: (String) -> Unit,
    onOpenSyncNudge: (TakeItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    var viewportWidthPx by remember { mutableIntStateOf(800) }
    val safeDuration = masterDurationSec.coerceAtLeast(1.0)

    // Base pixels-per-second fits the timeline cleanly at 1x, scaling smoothly up to 12x
    val basePxPerSec = remember(viewportWidthPx, safeDuration) {
        max(16f, (viewportWidthPx - 48f) / safeDuration.toFloat())
    }
    val pxPerSec = basePxPerSec * zoomMultiplier
    val canvasWidthPx = max(viewportWidthPx.toFloat(), (safeDuration * pxPerSec).toFloat() + 64f)
    val canvasWidthDp = with(density) { canvasWidthPx.toDp() }

    // Keep playhead visible during playback
    LaunchedEffect(playheadSec, isPlaying, pxPerSec) {
        if (isPlaying && viewportWidthPx > 0) {
            val playheadPx = (playheadSec * pxPerSec).toInt()
            val scrollStart = hScroll.value
            val scrollEnd = scrollStart + viewportWidthPx
            if (playheadPx > scrollEnd - 80 || playheadPx < scrollStart) {
                hScroll.scrollTo((playheadPx - viewportWidthPx / 4).coerceAtLeast(0))
            }
        }
    }

    // Active drag indicator tooltip for cut/paint operations
    var dragTooltip by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1320), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // PINNED TOP: Time Ruler + Master Comp Track
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111827))
            ) {
                // Left Sticky Master Header
                Column(
                    modifier = Modifier
                        .width(HEADER_WIDTH)
                        .height(RULER_HEIGHT + MASTER_LANE_HEIGHT)
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(RULER_HEIGHT),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = formatTimeShort(playheadSec),
                            color = Color(0xFFF43F5E),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MASTER_LANE_HEIGHT),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "MASTER",
                                color = Color(0xFFF8FAFC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        val totalCompSec = segments.sumOf { it.durationSec }
                        Text(
                            text = "${segments.size} part${if (segments.size == 1) "" else "s"} • ${formatTimeShort(totalCompSec)}",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Vertical divider between header and timeline canvas
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(RULER_HEIGHT + MASTER_LANE_HEIGHT)
                        .background(Color(0xFF1E293B))
                )

                // Right Horizontally Scrollable Ruler + Master Comp Track
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { viewportWidthPx = it.width }
                        .horizontalScroll(hScroll)
                ) {
                    Column(modifier = Modifier.width(canvasWidthDp)) {
                        TimeRulerBar(
                            durationSec = safeDuration,
                            pxPerSec = pxPerSec,
                            playheadSec = playheadSec,
                            onScrub = onScrubPlayhead,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(RULER_HEIGHT)
                        )
                        MasterCompTrack(
                            takes = takes,
                            segments = segments,
                            pxPerSec = pxPerSec,
                            playheadSec = playheadSec,
                            selectedSegmentId = selectedSegmentId,
                            activeTool = activeTool,
                            onSelectSegment = onSelectSegment,
                            onSplitAt = onSplitAt,
                            onMoveCutBoundary = onMoveCutBoundary,
                            onTrimOuterStart = onTrimOuterStart,
                            onTrimOuterEnd = onTrimOuterEnd,
                            onBoundaryDragStarted = onBoundaryDragStarted,
                            onBoundaryDragEnded = onBoundaryDragEnded,
                            onDragTooltip = { dragTooltip = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(MASTER_LANE_HEIGHT)
                        )
                    }
                }
            }

            // Divider separating pinned Master Comp from Take Lanes
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0xFF334155))
            )

            // SCROLLABLE TAKE LANES
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(vScroll)
            ) {
                // Left Sticky Take Headers
                Column(
                    modifier = Modifier
                        .width(HEADER_WIDTH)
                        .background(Color(0xFF0F172A))
                ) {
                    takes.forEach { take ->
                        TakeHeaderCard(
                            take = take,
                            isFocused = playbackMode == PlaybackMode.COMPARE_TAKES && take.id == focusedTakeId,
                            onFocusTake = { onFocusTake(take.id) },
                            onToggleMute = { onToggleMute(take.id) },
                            onToggleSolo = { onToggleSolo(take.id) },
                            onOpenSyncNudge = { onOpenSyncNudge(take) }
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFF1E293B))
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(TAKE_LANE_HEIGHT * takes.size)
                        .background(Color(0xFF1E293B))
                )

                // Right Horizontally Scrollable Take Waveform Lanes
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(hScroll)
                ) {
                    Column(modifier = Modifier.width(canvasWidthDp)) {
                        takes.forEach { take ->
                            TakeWaveformLane(
                                take = take,
                                segments = segments,
                                pxPerSec = pxPerSec,
                                playheadSec = playheadSec,
                                activeTool = activeTool,
                                onSwitchOrExtendAt = { tapSec ->
                                    val segAtTap = segments.firstOrNull {
                                        tapSec >= it.startMasterSec && tapSec <= it.endMasterSec
                                    }
                                    if (segAtTap != null) {
                                        onSwitchSegmentTake(segAtTap.id, take.id)
                                        onSelectSegment(segAtTap.id)
                                    } else if (take.coversMasterTime(tapSec)) {
                                        // Paint a 3-second highlight around tap if outside current comp
                                        val s = max(take.trackStartOnMasterSec, tapSec - 1.5)
                                        val e = min(take.trackEndOnMasterSec, tapSec + 1.5)
                                        onPaintRange(s, e, take.id)
                                    }
                                    onFocusTake(take.id)
                                    onScrubPlayhead(tapSec)
                                },
                                onPaintRange = { startSec, endSec ->
                                    onPaintRange(startSec, endSec, take.id)
                                    onFocusTake(take.id)
                                },
                                onSplitAt = { tapSec -> onSplitAt(tapSec, take.id) },
                                onDragTooltip = { dragTooltip = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(TAKE_LANE_HEIGHT)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0xFF1E293B))
                            )
                        }
                    }
                }
            }
        }

        // Floating tooltip during dragging cuts or painting ranges
        if (dragTooltip != null) {
            Surface(
                color = Color(0xFF0F172A).copy(alpha = 0.94f),
                shape = RoundedCornerShape(10.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = dragTooltip!!,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun TimeRulerBar(
    durationSec: Double,
    pxPerSec: Float,
    playheadSec: Double,
    onScrub: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val tickStepSec = remember(pxPerSec) {
        when {
            pxPerSec >= 120f -> 1
            pxPerSec >= 55f -> 2
            pxPerSec >= 24f -> 5
            pxPerSec >= 10f -> 10
            else -> 15
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF0F172A))
            .pointerInput(pxPerSec, durationSec) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onScrub((down.position.x / pxPerSec).toDouble().coerceIn(0.0, durationSec))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        onScrub((change.position.x / pxPerSec).toDouble().coerceIn(0.0, durationSec))
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxTick = ceil(durationSec).toInt()
            val labelStyle = TextStyle(color = Color(0xFF94A3B8), fontSize = 10.sp)
            for (sec in 0..maxTick step tickStepSec) {
                val x = sec * pxPerSec
                drawLine(
                    color = Color(0xFF475569),
                    start = Offset(x, size.height * 0.55f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = formatTimeCompact(sec.toDouble()),
                    topLeft = Offset(x + 6f, 4f),
                    style = labelStyle
                )
            }
            // Playhead triangle marker
            val px = (playheadSec * pxPerSec).toFloat()
            drawLine(
                color = Color(0xFFF43F5E),
                start = Offset(px, 0f),
                end = Offset(px, size.height),
                strokeWidth = 4f
            )
            drawCircle(
                color = Color(0xFFF43F5E),
                radius = with(density) { 6.dp.toPx() },
                center = Offset(px, size.height * 0.35f)
            )
        }
    }
}

@Composable
private fun MasterCompTrack(
    takes: List<TakeItem>,
    segments: List<CompSegment>,
    pxPerSec: Float,
    playheadSec: Double,
    selectedSegmentId: String?,
    activeTool: TimelineTool,
    onSelectSegment: (String?) -> Unit,
    onSplitAt: (masterSec: Double, targetTakeId: String?) -> Unit,
    onMoveCutBoundary: (leftIndex: Int, newCutSec: Double) -> Unit,
    onTrimOuterStart: (Double) -> Unit,
    onTrimOuterEnd: (Double) -> Unit,
    onBoundaryDragStarted: () -> Unit,
    onBoundaryDragEnded: () -> Unit,
    onDragTooltip: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val currentSegments by rememberUpdatedState(segments)
    val currentTakes by rememberUpdatedState(takes)
    val currentTool by rememberUpdatedState(activeTool)
    val currentPxPerSec by rememberUpdatedState(pxPerSec)
    val currentOnSelectSegment by rememberUpdatedState(onSelectSegment)
    val currentOnSplitAt by rememberUpdatedState(onSplitAt)
    val currentOnMoveCutBoundary by rememberUpdatedState(onMoveCutBoundary)
    val currentOnTrimOuterStart by rememberUpdatedState(onTrimOuterStart)
    val currentOnTrimOuterEnd by rememberUpdatedState(onTrimOuterEnd)

    Box(
        modifier = modifier
            .background(Color(0xFF141C2E))
            .pointerInput(Unit) {
                val hitSlopPx = with(density) { 22.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val segs = currentSegments
                    val pps = currentPxPerSec
                    val x = down.position.x

                    // Check interior cut boundaries first
                    var dragBoundaryIdx: Int? = null
                    var bestDist = hitSlopPx
                    for (i in 0 until segs.size - 1) {
                        val cutX = (segs[i].endMasterSec * pps).toFloat()
                        val d = abs(x - cutX)
                        if (d < bestDist) {
                            bestDist = d
                            dragBoundaryIdx = i
                        }
                    }

                    // Check outer start / end trim handles if no interior cut matched
                    var dragOuterStart = false
                    var dragOuterEnd = false
                    if (dragBoundaryIdx == null && segs.isNotEmpty()) {
                        val startX = (segs.first().startMasterSec * pps).toFloat()
                        val endX = (segs.last().endMasterSec * pps).toFloat()
                        if (abs(x - startX) < hitSlopPx) dragOuterStart = true
                        else if (abs(x - endX) < hitSlopPx) dragOuterEnd = true
                    }

                    if (dragBoundaryIdx != null || dragOuterStart || dragOuterEnd) {
                        down.consume()
                        onBoundaryDragStarted()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                onDragTooltip(null)
                                onBoundaryDragEnded()
                                break
                            }
                            change.consume()
                            val targetSec = (change.position.x / currentPxPerSec).toDouble()
                            when {
                                dragBoundaryIdx != null -> {
                                    currentOnMoveCutBoundary(dragBoundaryIdx, targetSec)
                                    onDragTooltip("Cut: ${formatTimeShort(targetSec)}")
                                }
                                dragOuterStart -> {
                                    currentOnTrimOuterStart(targetSec)
                                    onDragTooltip("Start: ${formatTimeShort(targetSec)}")
                                }
                                dragOuterEnd -> {
                                    currentOnTrimOuterEnd(targetSec)
                                    onDragTooltip("End: ${formatTimeShort(targetSec)}")
                                }
                            }
                        }
                    } else {
                        // Simple tap on a segment
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.positionChange().getDistance() > 10f) moved = true
                            if (!change.pressed) {
                                if (!moved) {
                                    val tapSec = (change.position.x / currentPxPerSec).toDouble()
                                    if (currentTool == TimelineTool.SPLIT) {
                                        currentOnSplitAt(tapSec, null)
                                    } else {
                                        val hit = currentSegments.firstOrNull {
                                            tapSec >= it.startMasterSec && tapSec <= it.endMasterSec
                                        }
                                        currentOnSelectSegment(hit?.id)
                                    }
                                }
                                break
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val takeMap = takes.associateBy { it.id }

            // Draw segments
            segments.forEachIndexed { idx, seg ->
                val take = takeMap[seg.takeId] ?: return@forEachIndexed
                val accent = takeColorForIndex(take.index)
                val isSelected = seg.id == selectedSegmentId
                val left = (seg.startMasterSec * pxPerSec).toFloat()
                val right = (seg.endMasterSec * pxPerSec).toFloat()
                val width = (right - left).coerceAtLeast(4f)

                drawRoundRect(
                    color = accent.copy(alpha = if (isSelected) 0.42f else 0.25f),
                    topLeft = Offset(left + 1f, 6f),
                    size = Size((width - 2f).coerceAtLeast(2f), size.height - 12f),
                    cornerRadius = CornerRadius(10f, 10f)
                )

                // Draw mini waveform inside Master Comp block
                drawWaveformSnippet(
                    take = take,
                    startMasterSec = seg.startMasterSec,
                    endMasterSec = seg.endMasterSec,
                    leftPx = left + 2f,
                    widthPx = (width - 4f).coerceAtLeast(1f),
                    topY = 18f,
                    heightPx = size.height - 26f,
                    color = accent.copy(alpha = 0.85f)
                )

                drawRoundRect(
                    color = if (isSelected) Color.White else accent,
                    topLeft = Offset(left + 1f, 6f),
                    size = Size((width - 2f).coerceAtLeast(2f), size.height - 12f),
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = if (isSelected) 4f else 2f)
                )

                // Interior cut handles
                if (idx < segments.size - 1) {
                    drawLine(
                        color = Color.White,
                        start = Offset(right, 2f),
                        end = Offset(right, size.height - 2f),
                        strokeWidth = 3f
                    )
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(right - 7f, size.height / 2f - 14f),
                        size = Size(14f, 28f),
                        cornerRadius = CornerRadius(7f, 7f)
                    )
                }
            }

            // Outer trim handles
            if (segments.isNotEmpty()) {
                val startX = (segments.first().startMasterSec * pxPerSec).toFloat()
                val endX = (segments.last().endMasterSec * pxPerSec).toFloat()
                drawRoundRect(
                    color = Color(0xFFFACC15),
                    topLeft = Offset(startX - 5f, 10f),
                    size = Size(10f, size.height - 20f),
                    cornerRadius = CornerRadius(5f, 5f)
                )
                drawRoundRect(
                    color = Color(0xFFFACC15),
                    topLeft = Offset(endX - 5f, 10f),
                    size = Size(10f, size.height - 20f),
                    cornerRadius = CornerRadius(5f, 5f)
                )
            }

            // Playhead line
            val px = (playheadSec * pxPerSec).toFloat()
            drawLine(
                color = Color(0xFFF43F5E),
                start = Offset(px, 0f),
                end = Offset(px, size.height),
                strokeWidth = 3.5f
            )
        }

        // Segment labels ("Take 1", "Take 2", ...)
        val takeMap = takes.associateBy { it.id }
        segments.forEach { seg ->
            val take = takeMap[seg.takeId] ?: return@forEach
            val segWidthPx = (seg.durationSec * pxPerSec).toFloat()
            if (segWidthPx > 46f) {
                val leftDp = with(density) { (seg.startMasterSec * pxPerSec).toFloat().toDp() }
                val widthDp = with(density) { segWidthPx.toDp() }
                Box(
                    modifier = Modifier
                        .offset(x = leftDp + 8.dp, y = 8.dp)
                        .width((widthDp - 16.dp).coerceAtLeast(24.dp))
                ) {
                    Text(
                        text = take.label,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TakeHeaderCard(
    take: TakeItem,
    isFocused: Boolean,
    onFocusTake: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onOpenSyncNudge: () -> Unit
) {
    val accent = takeColorForIndex(take.index)
    Column(
        modifier = Modifier
            .width(HEADER_WIDTH)
            .height(TAKE_LANE_HEIGHT)
            .background(if (isFocused) Color(0xFF1A2438) else Color(0xFF0F172A))
            .clickable(onClick = onFocusTake)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Title + Sync badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(accent, CircleShape)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = take.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // Sync offset chip (tappable to open Fine Sync Nudge)
        val syncText = when {
            !take.hasAudio -> "No Audio"
            take.index == 0 && take.manualNudgeMs == 0 -> "Reference"
            else -> {
                val totalMs = (take.effectiveOffsetSec * 1000.0).roundToInt()
                "${if (totalMs >= 0) "+" else ""}${totalMs}ms"
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E293B))
                .clickable(onClick = onOpenSyncNudge)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = syncText,
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Adjust sync",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(12.dp)
            )
        }

        // Mute & Solo Buttons (ergonomic 44dp+ wide, 32dp tall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (take.isMuted) Color(0xFFDC2626) else Color(0xFF1E293B))
                    .border(
                        1.dp,
                        if (take.isMuted) Color(0xFFFCA5A5) else Color(0xFF334155),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(onClick = onToggleMute),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (take.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (take.isMuted) "Unmute" else "Mute",
                        tint = if (take.isMuted) Color.White else Color(0xFFCBD5E1),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "M",
                        color = if (take.isMuted) Color.White else Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (take.isSolo) Color(0xFFD97706) else Color(0xFF1E293B))
                    .border(
                        1.dp,
                        if (take.isSolo) Color(0xFFFDE68A) else Color(0xFF334155),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(onClick = onToggleSolo),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Solo",
                    color = if (take.isSolo) Color.White else Color(0xFFCBD5E1),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun TakeWaveformLane(
    take: TakeItem,
    segments: List<CompSegment>,
    pxPerSec: Float,
    playheadSec: Double,
    activeTool: TimelineTool,
    onSwitchOrExtendAt: (Double) -> Unit,
    onPaintRange: (Double, Double) -> Unit,
    onSplitAt: (Double) -> Unit,
    onDragTooltip: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = takeColorForIndex(take.index)
    val currentTool by rememberUpdatedState(activeTool)
    val currentPxPerSec by rememberUpdatedState(pxPerSec)
    val currentTake by rememberUpdatedState(take)
    val currentOnSwitchOrExtendAt by rememberUpdatedState(onSwitchOrExtendAt)
    val currentOnPaintRange by rememberUpdatedState(onPaintRange)
    val currentOnSplitAt by rememberUpdatedState(onSplitAt)

    var paintAnchorSec by remember { mutableStateOf<Double?>(null) }
    var paintCurrentSec by remember { mutableStateOf<Double?>(null) }

    Box(
        modifier = modifier
            .background(Color(0xFF0B111E))
            .pointerInput(take.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pps = currentPxPerSec
                    val downSec = (down.position.x / pps).toDouble()
                        .coerceIn(currentTake.trackStartOnMasterSec, currentTake.trackEndOnMasterSec)

                    if (currentTool == TimelineTool.SWIPE_COMP) {
                        down.consume()
                        paintAnchorSec = downSec
                        paintCurrentSec = downSec
                        onDragTooltip("${currentTake.label}: swipe to select section")
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                val start = paintAnchorSec ?: downSec
                                val end = paintCurrentSec ?: downSec
                                paintAnchorSec = null
                                paintCurrentSec = null
                                onDragTooltip(null)
                                if (abs(end - start) < 0.20) {
                                    currentOnSwitchOrExtendAt(start)
                                } else {
                                    currentOnPaintRange(min(start, end), max(start, end))
                                }
                                break
                            }
                            change.consume()
                            val curSec = (change.position.x / currentPxPerSec).toDouble()
                                .coerceIn(currentTake.trackStartOnMasterSec, currentTake.trackEndOnMasterSec)
                            paintCurrentSec = curSec
                            val a = min(paintAnchorSec ?: curSec, curSec)
                            val b = max(paintAnchorSec ?: curSec, curSec)
                            onDragTooltip("${currentTake.label}: ${formatTimeShort(a)} – ${formatTimeShort(b)} (${String.format("%.1fs", b - a)})")
                        }
                    } else {
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.positionChange().getDistance() > 10f) moved = true
                            if (!change.pressed) {
                                if (!moved) {
                                    val tapSec = (change.position.x / currentPxPerSec).toDouble()
                                    if (currentTool == TimelineTool.SPLIT) {
                                        currentOnSplitAt(tapSec)
                                    } else {
                                        currentOnSwitchOrExtendAt(tapSec)
                                    }
                                }
                                break
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val leftPx = (take.trackStartOnMasterSec * pxPerSec).toFloat()
            val widthPx = (take.durationSec * pxPerSec).toFloat().coerceAtLeast(4f)

            // Base clip container showing where this take sits on Master Timeline
            drawRoundRect(
                color = Color(0xFF131C2E),
                topLeft = Offset(leftPx, 6f),
                size = Size(widthPx, size.height - 12f),
                cornerRadius = CornerRadius(8f, 8f)
            )

            // Highlight regions of this take that are active in the Master Comp
            val activeSegs = segments.filter { it.takeId == take.id }
            for (seg in activeSegs) {
                val sLeft = (seg.startMasterSec * pxPerSec).toFloat()
                val sWidth = ((seg.endMasterSec - seg.startMasterSec) * pxPerSec).toFloat().coerceAtLeast(2f)
                drawRect(
                    color = accent.copy(alpha = 0.22f),
                    topLeft = Offset(sLeft, 6f),
                    size = Size(sWidth, size.height - 12f)
                )
                drawRect(
                    color = accent,
                    topLeft = Offset(sLeft, 6f),
                    size = Size(sWidth, 4f)
                )
            }

            // Full take waveform
            val waveAlpha = if (take.isMuted) 0.24f else 0.78f
            drawWaveformSnippet(
                take = take,
                startMasterSec = take.trackStartOnMasterSec,
                endMasterSec = take.trackEndOnMasterSec,
                leftPx = leftPx,
                widthPx = widthPx,
                topY = 12f,
                heightPx = size.height - 24f,
                color = if (take.isMuted) Color.Gray.copy(alpha = waveAlpha) else accent.copy(alpha = waveAlpha)
            )

            // Cut boundary dashed guides
            for (i in 0 until segments.size - 1) {
                val cutX = (segments[i].endMasterSec * pxPerSec).toFloat()
                drawLine(
                    color = Color.White.copy(alpha = 0.25f),
                    start = Offset(cutX, 0f),
                    end = Offset(cutX, size.height),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
            }

            // Live Paint selection highlight
            val pA = paintAnchorSec
            val pB = paintCurrentSec
            if (pA != null && pB != null) {
                val selL = (min(pA, pB) * pxPerSec).toFloat()
                val selR = (max(pA, pB) * pxPerSec).toFloat()
                drawRect(
                    color = Color.White.copy(alpha = 0.25f),
                    topLeft = Offset(selL, 4f),
                    size = Size((selR - selL).coerceAtLeast(3f), size.height - 8f)
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(selL, 4f),
                    size = Size((selR - selL).coerceAtLeast(3f), size.height - 8f),
                    style = Stroke(width = 3f)
                )
            }

            // Playhead line
            val px = (playheadSec * pxPerSec).toFloat()
            drawLine(
                color = Color(0xFFF43F5E),
                start = Offset(px, 0f),
                end = Offset(px, size.height),
                strokeWidth = 3f
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveformSnippet(
    take: TakeItem,
    startMasterSec: Double,
    endMasterSec: Double,
    leftPx: Float,
    widthPx: Float,
    topY: Float,
    heightPx: Float,
    color: Color
) {
    val peaks = take.waveformPeaks
    if (peaks.isEmpty() || widthPx <= 1f) return

    val localStart = (startMasterSec - take.trackStartOnMasterSec).coerceIn(0.0, take.durationSec)
    val localEnd = (endMasterSec - take.trackStartOnMasterSec).coerceIn(localStart, take.durationSec)
    if (localEnd <= localStart) return

    val idxStart = floor(localStart * take.peaksPerSec).toInt().coerceIn(0, peaks.lastIndex)
    val idxEnd = ceil(localEnd * take.peaksPerSec).toInt().coerceIn(idxStart + 1, peaks.size)

    val maxBars = max(12, (widthPx / 3.5f).toInt())
    val sliceLen = idxEnd - idxStart
    val step = max(1, sliceLen / maxBars)
    val midY = topY + heightPx / 2f
    val barCount = max(1, sliceLen / step)
    val barWidth = widthPx / barCount.toFloat()

    for (b in 0 until barCount) {
        val pStart = idxStart + b * step
        val pEnd = min(idxEnd, pStart + step)
        var maxPeak = 0f
        for (p in pStart until pEnd) {
            if (peaks[p] > maxPeak) maxPeak = peaks[p]
        }
        val barH = (maxPeak * heightPx * 0.92f).coerceAtLeast(3f)
        drawRect(
            color = color,
            topLeft = Offset(leftPx + b * barWidth, midY - barH / 2f),
            size = Size((barWidth - 1f).coerceAtLeast(1.5f), barH)
        )
    }
}

fun formatTimeShort(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0)
    val mins = (s / 60).toInt()
    val remSec = s - mins * 60
    return String.format("%d:%04.1f", mins, remSec)
}

fun formatTimeCompact(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    val mins = total / 60
    val secs = total % 60
    return String.format("%d:%02d", mins, secs)
}
