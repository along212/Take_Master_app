package com.takemaster.studio.model

import android.net.Uri
import androidx.compose.ui.graphics.Color

val TakeAccentColors = listOf(
    Color(0xFF10B981), // Take 1: Emerald
    Color(0xFF38BDF8), // Take 2: Sky Blue
    Color(0xFFF59E0B), // Take 3: Amber
    Color(0xFFFB7185), // Take 4: Rose
    Color(0xFFA78BFA), // Take 5: Violet
    Color(0xFF2DD4BF), // Take 6: Teal
)

fun takeColorForIndex(index: Int): Color = TakeAccentColors[index % TakeAccentColors.size]

data class TakeItem(
    val id: String,
    val index: Int,
    val label: String,
    val sourceName: String,
    val uriString: String,
    val pcmPath: String? = null,
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true,
    val isDemo: Boolean = false,
    val autoOffsetSec: Double,
    val manualNudgeMs: Int = 0,
    val confidencePct: Double,
    val waveformPeaks: FloatArray,
    val peaksPerSec: Double,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val trackStartOnMasterSec: Double = 0.0
) {
    val uri: Uri
        get() = Uri.parse(uriString)

    val effectiveOffsetSec: Double
        get() = autoOffsetSec + (manualNudgeMs / 1000.0)

    val trackEndOnMasterSec: Double
        get() = trackStartOnMasterSec + durationSec

    fun masterToTakeSec(masterSec: Double): Double = masterSec - trackStartOnMasterSec

    fun coversMasterTime(masterSec: Double): Boolean =
        masterSec >= trackStartOnMasterSec - 1e-4 && masterSec <= trackEndOnMasterSec + 1e-4

    val displayWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    val displayHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
}

data class CompSegment(
    val id: String,
    val startMasterSec: Double,
    val endMasterSec: Double,
    val takeId: String
) {
    val durationSec: Double
        get() = (endMasterSec - startMasterSec).coerceAtLeast(0.0)
}

enum class PlaybackMode {
    MASTER_COMP,
    COMPARE_TAKES
}

enum class TimelineTool {
    SWIPE_COMP,
    PAN_SCROLL,
    SPLIT
}

enum class ExportAudioMode {
    FOLLOW_ACTIVE_TAKE,
    LOCK_TO_TAKE
}

enum class ExportQuality(val label: String, val maxShortSide: Int?) {
    ORIGINAL("Original Resolution", null),
    FHD_1080P("1080p HD (Faster)", 1080)
}

data class ExportConfig(
    val audioMode: ExportAudioMode = ExportAudioMode.FOLLOW_ACTIVE_TAKE,
    val lockedTakeId: String? = null,
    val crossfadeMs: Int = 12,
    val snapToQuietValley: Boolean = true,
    val quality: ExportQuality = ExportQuality.ORIGINAL
)

data class ExportOutput(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long
)

data class SegmentMapping(
    val segmentIndex: Int,
    val segment: CompSegment,
    val localOffsetMs: Long
)
