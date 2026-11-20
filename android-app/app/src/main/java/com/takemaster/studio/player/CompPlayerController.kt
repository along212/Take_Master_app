package com.takemaster.studio.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.ExportAudioMode
import com.takemaster.studio.model.PlaybackMode
import com.takemaster.studio.model.TakeItem
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Synchronized multi-take player controller coordinating:
 * - CompPcmAudioEngine: Continuous 48kHz AudioTrack PCM mixer/crossfader (0ms cut silence gap,
 *   sample-accurate equal-power crossfades live, zero inter-take audio drift).
 * - Video-only ExoPlayer pool: Keeps up to 4 concurrent hardware video decoders (audio disabled)
 *   running continuously in sync so switching camera angles at any cut is instantaneous.
 */
class CompPlayerController(
    private val context: Context,
    var onPlayheadChanged: (Double) -> Unit = {},
    var onPlayingChanged: (Boolean) -> Unit = {}
) {
    private val videoPlayers = mutableMapOf<String, ExoPlayer>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Compose-observed counter incremented whenever [videoPlayers] adds or removes players
     * so PlayerViews rebind immediately after async clip load / updateTimeline.
     */
    var playersRevision by mutableIntStateOf(0)
        private set

    private var currentMode: PlaybackMode = PlaybackMode.MASTER_COMP
    private var currentTakes: List<TakeItem> = emptyList()
    private var currentSegments: List<CompSegment> = emptyList()
    private var currentFocusedTakeId: String? = null
    private var currentAuditionTakeId: String? = null
    private var currentAudioMode: ExportAudioMode = ExportAudioMode.FOLLOW_ACTIVE_TAKE
    private var currentLockedTakeId: String? = null
    private var currentMultiAngleView: Boolean = false
    private var currentCrossfadeMs: Int = 12
    private var currentMasterDurationSec: Double = 0.0
    private var currentPlayheadSec: Double = 0.0
    private var prevTickMasterSec: Double = 0.0
    private var lastDriftCheckMs: Long = 0L

    private val audioEngine = CompPcmAudioEngine(
        onPlayheadChanged = { sec ->
            currentPlayheadSec = sec
            onPlayheadChanged(sec)
        },
        onPlayingChanged = { playing ->
            onPlayingChanged(playing)
            if (playing) startTicker() else stopTicker()
        },
        onEnded = { endSec ->
            pauseVideoPlayersAt(endSec)
        }
    )

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!audioEngine.isPlaying()) return
            val sec = audioEngine.currentMasterSec()
            val jumped = abs(sec - prevTickMasterSec) > 0.15
            prevTickMasterSec = sec
            currentPlayheadSec = sec
            onPlayheadChanged(sec)
            if (jumped) {
                seekVideoPlayersTo(sec, shouldPlay = true)
            } else {
                syncVideoPlayersDuringPlayback(sec)
            }
            handler.postDelayed(this, 16L)
        }
    }

    /**
     * Returns the video-only ExoPlayer for [takeId] (null if [takeId] has no video or exceeds decoder cap).
     */
    fun playerForTake(takeId: String): ExoPlayer? = videoPlayers[takeId]

    fun updateTimeline(
        takes: List<TakeItem>,
        segments: List<CompSegment>,
        masterDurationSec: Double,
        mode: PlaybackMode,
        focusedTakeId: String?,
        audioMode: ExportAudioMode,
        lockedTakeId: String?,
        isMultiAngleView: Boolean = false,
        auditionTakeId: String? = null,
        crossfadeMs: Int = 12
    ) {
        val prevTakeSignature = currentTakes.joinToString(",") { "${it.id}:${it.trackStartOnMasterSec}" }
        val nextTakeSignature = takes.joinToString(",") { "${it.id}:${it.trackStartOnMasterSec}" }

        currentTakes = takes
        currentSegments = segments
        currentMasterDurationSec = masterDurationSec
        currentMode = mode
        currentFocusedTakeId = focusedTakeId ?: takes.firstOrNull()?.id
        currentAuditionTakeId = auditionTakeId?.takeIf { id -> takes.any { it.id == id } }
        currentAudioMode = audioMode
        currentLockedTakeId = lockedTakeId
        currentMultiAngleView = isMultiAngleView
        currentCrossfadeMs = crossfadeMs

        if (takes.isEmpty()) {
            pause()
            releaseVideoPlayers()
            audioEngine.updateState(
                takes = emptyList(),
                segments = emptyList(),
                masterDurationSec = 0.0,
                playbackMode = mode,
                audioMode = audioMode,
                lockedTakeId = lockedTakeId,
                auditionTakeId = null,
                crossfadeMs = crossfadeMs
            )
            return
        }

        audioEngine.updateState(
            takes = takes,
            segments = segments,
            masterDurationSec = masterDurationSec,
            playbackMode = mode,
            audioMode = audioMode,
            lockedTakeId = lockedTakeId,
            auditionTakeId = currentAuditionTakeId,
            crossfadeMs = crossfadeMs
        )

        val videoTakesChanged = syncVideoPlayerPool(takes)
        configureDecoderSlots(takes, segments)
        if (videoTakesChanged || prevTakeSignature != nextTakeSignature) {
            seekVideoPlayersTo(currentPlayheadSec, shouldPlay = audioEngine.isPlaying())
        }
    }

    fun seekTo(masterSec: Double) {
        val clamped = masterSec.coerceIn(0.0, currentMasterDurationSec.coerceAtLeast(0.0))
        currentPlayheadSec = clamped
        prevTickMasterSec = clamped
        audioEngine.seekTo(clamped)
        seekVideoPlayersTo(clamped, shouldPlay = audioEngine.isPlaying())
    }

    fun play() {
        if (currentTakes.isEmpty()) return
        audioEngine.play()
        val activeSec = audioEngine.currentMasterSec()
        currentPlayheadSec = activeSec
        prevTickMasterSec = activeSec
        seekVideoPlayersTo(activeSec, shouldPlay = true)
        startTicker()
    }

    fun pause() {
        stopTicker()
        audioEngine.pause()
        pauseAllVideoPlayers()
    }

    fun togglePlayPause() {
        if (audioEngine.isPlaying()) pause() else play()
    }

    fun release() {
        stopTicker()
        audioEngine.release()
        releaseVideoPlayers()
    }

    private fun syncVideoPlayerPool(takes: List<TakeItem>): Boolean {
        val videoTakeIds = takes.filter { it.hasVideo }.map { it.id }.toSet()
        var changed = false

        val toRemove = videoPlayers.keys.filter { it !in videoTakeIds }
        for (id in toRemove) {
            videoPlayers.remove(id)?.release()
            changed = true
        }

        for (take in takes) {
            if (!take.hasVideo) continue
            if (!videoPlayers.containsKey(take.id)) {
                val player = ExoPlayer.Builder(context).build().apply {
                    // Disable audio track permanently on ExoPlayers: CompPcmAudioEngine owns audio output.
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(take.uri))
                    prepare()
                }
                videoPlayers[take.id] = player
                changed = true
            }
        }
        if (changed) {
            playersRevision++
        }
        return changed
    }

    private fun configureDecoderSlots(takes: List<TakeItem>, segments: List<CompSegment>) {
        // Keep up to MAX_MULTI_ANGLE_TILES (4) hardware video decoders enabled simultaneously.
        val priorityOrder = LinkedHashSet<String>()
        currentAuditionTakeId?.let { priorityOrder.add(it) }
        currentFocusedTakeId?.let { priorityOrder.add(it) }
        segments.map { it.takeId }.forEach { priorityOrder.add(it) }
        takes.filter { it.hasVideo }.forEach { priorityOrder.add(it.id) }

        val enabledIds = priorityOrder
            .filter { videoPlayers.containsKey(it) }
            .take(MAX_MULTI_ANGLE_TILES)
            .toSet()

        for ((id, player) in videoPlayers) {
            val disableVideo = id !in enabledIds
            val currentlyDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
            if (currentlyDisabled != disableVideo) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideo)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
        }
    }

    private fun seekVideoPlayersTo(masterSec: Double, shouldPlay: Boolean) {
        for (take in currentTakes) {
            if (!take.hasVideo) continue
            val player = videoPlayers[take.id] ?: continue
            val localSec = take.masterToTakeSec(masterSec)
            if (localSec < 0.0 || localSec >= take.durationSec - 0.04) {
                player.playWhenReady = false
                player.pause()
                val clampedMs = (localSec.coerceIn(0.0, take.durationSec) * 1000.0).roundToLong()
                player.seekTo(clampedMs)
            } else {
                player.seekTo((localSec * 1000.0).roundToLong())
                player.playWhenReady = shouldPlay
            }
        }
    }

    private fun syncVideoPlayersDuringPlayback(masterSec: Double) {
        val now = System.currentTimeMillis()
        val checkDrift = (now - lastDriftCheckMs) >= 240L
        if (checkDrift) lastDriftCheckMs = now

        for (take in currentTakes) {
            if (!take.hasVideo) continue
            val player = videoPlayers[take.id] ?: continue
            val localSec = take.masterToTakeSec(masterSec)

            if (localSec < 0.0 || localSec >= take.durationSec - 0.04) {
                if (player.playWhenReady) {
                    player.playWhenReady = false
                    player.pause()
                }
                continue
            }

            if (!player.playWhenReady) {
                player.seekTo((localSec * 1000.0).roundToLong())
                player.playWhenReady = true
            } else if (checkDrift) {
                val expectedMs = (localSec * 1000.0).roundToLong()
                val actualMs = player.currentPosition
                if (abs(actualMs - expectedMs) > 85L) {
                    player.seekTo(expectedMs)
                }
            }
        }
    }

    private fun pauseVideoPlayersAt(masterSec: Double) {
        for (take in currentTakes) {
            if (!take.hasVideo) continue
            val player = videoPlayers[take.id] ?: continue
            player.playWhenReady = false
            player.pause()
            val localSec = take.masterToTakeSec(masterSec).coerceIn(0.0, take.durationSec)
            player.seekTo((localSec * 1000.0).roundToLong())
        }
    }

    private fun pauseAllVideoPlayers() {
        videoPlayers.values.forEach {
            it.playWhenReady = false
            it.pause()
        }
    }

    private fun releaseVideoPlayers() {
        val hadPlayers = videoPlayers.isNotEmpty()
        videoPlayers.values.forEach { it.release() }
        videoPlayers.clear()
        if (hadPlayers) {
            playersRevision++
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun stopTicker() {
        handler.removeCallbacks(tickRunnable)
    }

    companion object {
        const val MAX_MULTI_ANGLE_TILES = 4
    }
}
