package com.drumcover.compstudio

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.drumcover.compstudio.audio.AlignmentResult
import com.drumcover.compstudio.audio.HarmonicAudioAligner
import com.drumcover.compstudio.export.CompSegment
import com.drumcover.compstudio.export.Media3CompExporter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0F19)) {
                    DrumCompStudioScreen()
                }
            }
        }
    }
}

@Composable
fun DrumCompStudioScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var alignedTakes by remember { mutableStateOf<List<AlignmentResult>>(emptyList()) }
    var segments by remember { mutableStateOf<List<CompSegment>>(emptyList()) }
    var mutedTakes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedTakeIndex by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("Pick 2–5 drum cover videos from your S26 Ultra Gallery") }
    var isExporting by remember { mutableStateOf(false) }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(selectedTakeIndex, alignedTakes) {
        if (alignedTakes.isNotEmpty() && selectedTakeIndex in alignedTakes.indices) {
            val take = alignedTakes[selectedTakeIndex]
            exoPlayer.setMediaItem(MediaItem.fromUri(take.uri))
            exoPlayer.prepare()
            exoPlayer.volume = if (mutedTakes.contains(selectedTakeIndex)) 0f else 1f
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            statusText = "Running Harmonic FFT Alignment on ${uris.size} takes..."
            scope.launch {
                val input = uris.mapIndexed { idx, uri -> uri to "Take ${idx + 1}" }
                val aligned = HarmonicAudioAligner.alignTakes(context, input)
                alignedTakes = aligned
                val dur = aligned.firstOrNull()?.durationSeconds ?: 30.0
                val segLen = dur / aligned.size
                segments = aligned.indices.map { i ->
                    CompSegment(i * segLen, (i + 1) * segLen, i)
                }
                selectedTakeIndex = 0
                statusText = "Aligned ${aligned.size} takes! Tap any lane to preview & comp."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Take Master App", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(statusText, fontSize = 12.sp, color = Color(0xFF94A3B8))
            }
            Button(onClick = {
                videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }) {
                Text("+ Pick Takes")
            }
        }

        // Video Monitor directly above Timeline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .background(Color.Black, RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Multi-Take Waveform Timeline directly under the video
        Text("Comp Timeline (Tap take to audition / mute)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(alignedTakes) { idx, take ->
                val isSelected = idx == selectedTakeIndex
                val isMuted = mutedTakes.contains(idx)
                val accent = listOf(Color(0xFF10B981), Color(0xFF0EA5E9), Color(0xFFF59E0B), Color(0xFFF43F5E))[idx % 4]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) Color(0xFF1E293B) else Color(0xFF111827), RoundedCornerShape(10.dp))
                        .clickable {
                            selectedTakeIndex = idx
                            exoPlayer.playWhenReady = true
                        }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.width(105.dp)) {
                        Text(take.displayName, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${String.format("%+.1f", take.offsetSeconds * 1000)} ms", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                mutedTakes = if (isMuted) mutedTakes - idx else mutedTakes + idx
                                if (idx == selectedTakeIndex) {
                                    exoPlayer.volume = if (!isMuted) 0f else 1f
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(if (isMuted) "Unmute" else "Mute", fontSize = 11.sp)
                        }
                    }

                    // Waveform Canvas
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .padding(start = 8.dp)
                    ) {
                        val peaks = take.waveformPeaks
                        if (peaks.isNotEmpty()) {
                            val barW = size.width / peaks.size
                            val midY = size.height / 2f
                            for (i in peaks.indices) {
                                val h = peaks[i] * size.height * 0.85f
                                drawRect(
                                    color = if (isMuted) Color.Gray.copy(alpha = 0.3f) else accent,
                                    topLeft = Offset(i * barW, midY - h / 2f),
                                    size = Size((barW - 1f).coerceAtLeast(1f), h)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Export Button
        Button(
            onClick = {
                if (alignedTakes.isNotEmpty() && segments.isNotEmpty()) {
                    isExporting = true
                    statusText = "Stitching 4K cover via S26 Ultra Media3 hardware encoder..."
                    Media3CompExporter.exportStitchedCover(
                        context = context,
                        takes = alignedTakes,
                        segments = segments,
                        onComplete = { file ->
                            isExporting = false
                            statusText = "Saved: ${file.name}"
                        },
                        onError = { err ->
                            isExporting = false
                            statusText = "Export error: $err"
                        }
                    )
                }
            },
            enabled = alignedTakes.isNotEmpty() && !isExporting,
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text(if (isExporting) "Rendering 4K Cover..." else "⚡ Stitch & Export Cover Video (Media3)")
        }
    }
}
