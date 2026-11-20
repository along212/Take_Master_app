package com.takemaster.studio.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.takemaster.studio.audio.CompAudioStitcher
import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.ExportAudioMode
import com.takemaster.studio.model.PlaybackMode
import com.takemaster.studio.model.TakeItem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Dedicated low-latency 48kHz stereo AudioTrack playback engine fed by CompAudioStitcher.
 *
 * Guarantees 0ms silence gaps across cut boundaries, live equal-power crossfades, and zero
 * inter-track drift in Compare mode, independent of hardware video decoder switching.
 */
class CompPcmAudioEngine(
    private val onPlayheadChanged: (Double) -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit,
    private val onEnded: (Double) -> Unit
) {
    data class MixSnapshot(
        val takes: List<TakeItem> = emptyList(),
        val frameMap: List<CompAudioStitcher.SegFrameRange> = emptyList(),
        val masterDurationSec: Double = 0.0,
        val playbackMode: PlaybackMode = PlaybackMode.MASTER_COMP,
        val audioMode: ExportAudioMode = ExportAudioMode.FOLLOW_ACTIVE_TAKE,
        val lockedTakeId: String? = null,
        val auditionTakeId: String? = null,
        val crossfadeMs: Int = 12
    ) {
        val isLinearMasterClock: Boolean
            get() = playbackMode == PlaybackMode.COMPARE_TAKES ||
                (playbackMode == PlaybackMode.MASTER_COMP && auditionTakeId != null)
    }

    private val sampleRate = CompAudioStitcher.SAMPLE_RATE
    private val channels = CompAudioStitcher.CHANNELS
    private val chunkFrames = 1024

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sourceLock = Any()
    private val sources = mutableMapOf<String, CompAudioStitcher.FilePcmSource>()
    private val sourcePaths = mutableMapOf<String, String>()

    @Volatile
    private var snapshot = MixSnapshot()

    @Volatile
    private var isPlayingFlag = false

    private val generationCounter = AtomicLong(0L)
    private val stopWorkerFlag = AtomicBoolean(false)

    // Current frame position & hardware baseline for tight playhead tracking
    @Volatile
    private var pausedMasterSec: Double = 0.0

    @Volatile
    private var anchorFrame: Long = 0L

    @Volatile
    private var anchorIsLinearMaster: Boolean = false

    @Volatile
    private var writtenFramesSinceAnchor: Long = 0L

    @Volatile
    private var headBaselineAtFlush: Long = 0L

    private var audioTrack: AudioTrack? = null
    private var workerThread: Thread? = null

    fun isPlaying(): Boolean = isPlayingFlag

    fun updateState(
        takes: List<TakeItem>,
        segments: List<CompSegment>,
        masterDurationSec: Double,
        playbackMode: PlaybackMode,
        audioMode: ExportAudioMode,
        lockedTakeId: String?,
        auditionTakeId: String?,
        crossfadeMs: Int
    ) {
        syncSources(takes)
        val prev = snapshot
        val frameMap = CompAudioStitcher.buildFrameMap(segments, takes, sampleRate)
        val next = MixSnapshot(
            takes = takes,
            frameMap = frameMap,
            masterDurationSec = masterDurationSec,
            playbackMode = playbackMode,
            audioMode = audioMode,
            lockedTakeId = lockedTakeId,
            auditionTakeId = auditionTakeId,
            crossfadeMs = crossfadeMs
        )
        val clockKindChanged = prev.isLinearMasterClock != next.isLinearMasterClock
        snapshot = next

        // If switching between linear Master clock (Compare / Audition) and Comp clock while paused or playing,
        // re-anchor cleanly at the current musical Master time without stopping AudioTrack.
        if (clockKindChanged) {
            val curMaster = currentMasterSec()
            if (isPlayingFlag) {
                seekTo(curMaster)
            } else {
                pausedMasterSec = curMaster
            }
        }
    }

    private fun syncSources(takes: List<TakeItem>) {
        synchronized(sourceLock) {
            val validIds = takes.map { it.id }.toSet()
            val toRemove = sources.keys.filter { it !in validIds }
            for (id in toRemove) {
                sources.remove(id)?.close()
                sourcePaths.remove(id)
            }
            for (take in takes) {
                val path = take.pcmPath
                if (path == null || !take.hasAudio) {
                    sources.remove(take.id)?.close()
                    sourcePaths.remove(take.id)
                    continue
                }
                if (sourcePaths[take.id] != path) {
                    sources.remove(take.id)?.close()
                    val file = File(path)
                    if (file.exists() && file.length() > 0L) {
                        sources[take.id] = CompAudioStitcher.FilePcmSource(file)
                        sourcePaths[take.id] = path
                    } else {
                        sourcePaths.remove(take.id)
                    }
                }
            }
        }
    }

    fun currentMasterSec(): Double {
        val snap = snapshot
        if (snap.takes.isEmpty()) return 0.0
        if (!isPlayingFlag) {
            return pausedMasterSec.coerceIn(0.0, snap.masterDurationSec.coerceAtLeast(0.0))
        }
        val track = audioTrack ?: return pausedMasterSec
        val written = writtenFramesSinceAnchor
        val rawHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (rawHead < headBaselineAtFlush) {
            headBaselineAtFlush = 0L
        }
        val playedFrames = (rawHead - headBaselineAtFlush).coerceIn(0L, written)
        val curFrame = anchorFrame + playedFrames

        return if (anchorIsLinearMaster) {
            (curFrame.toDouble() / sampleRate.toDouble()).coerceIn(0.0, snap.masterDurationSec)
        } else {
            CompAudioStitcher.outputFrameToMasterSec(snap.frameMap, curFrame, sampleRate)
        }
    }

    fun seekTo(masterSec: Double) {
        val snap = snapshot
        val clamped = masterSec.coerceIn(0.0, snap.masterDurationSec.coerceAtLeast(0.0))
        pausedMasterSec = clamped
        val targetFrame = if (snap.isLinearMasterClock) {
            (clamped * sampleRate).roundToLong()
        } else {
            CompAudioStitcher.masterSecToOutputFrame(snap.frameMap, clamped, sampleRate)
        }

        if (isPlayingFlag) {
            restartWorkerAtFrame(targetFrame, snap.isLinearMasterClock)
        } else {
            anchorFrame = targetFrame
            anchorIsLinearMaster = snap.isLinearMasterClock
            writtenFramesSinceAnchor = 0L
        }
        onPlayheadChanged(clamped)
    }

    fun play() {
        val snap = snapshot
        if (snap.takes.isEmpty()) return
        if (isPlayingFlag) return

        var startSec = pausedMasterSec
        if (!snap.isLinearMasterClock && snap.frameMap.isNotEmpty()) {
            val compStart = snap.frameMap.first().masterStartSec
            val compEnd = snap.frameMap.last().masterEndSec
            if (startSec < compStart || startSec >= compEnd - 0.04) {
                startSec = compStart
            }
        } else if (startSec >= snap.masterDurationSec - 0.04) {
            startSec = 0.0
        }
        pausedMasterSec = startSec

        val startFrame = if (snap.isLinearMasterClock) {
            (startSec * sampleRate).roundToLong()
        } else {
            CompAudioStitcher.masterSecToOutputFrame(snap.frameMap, startSec, sampleRate)
        }

        isPlayingFlag = true
        onPlayingChanged(true)
        restartWorkerAtFrame(startFrame, snap.isLinearMasterClock)
    }

    fun pause() {
        if (!isPlayingFlag) return
        val curSec = currentMasterSec()
        pausedMasterSec = curSec
        isPlayingFlag = false
        stopWorkerOnly()
        onPlayingChanged(false)
        onPlayheadChanged(curSec)
    }

    fun release() {
        isPlayingFlag = false
        stopWorkerOnly()
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        synchronized(sourceLock) {
            sources.values.forEach { it.close() }
            sources.clear()
            sourcePaths.clear()
        }
    }

    private fun ensureTrack(): AudioTrack {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) {
            return existing
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val bufBytes = max(minBuf * 2, chunkFrames * channels * 2 * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        return track
    }

    private fun stopWorkerOnly() {
        generationCounter.incrementAndGet()
        stopWorkerFlag.set(true)
        val t = workerThread
        workerThread = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(250L)
            } catch (_: Exception) {
            }
        }
    }

    private fun restartWorkerAtFrame(startFrame: Long, isLinearMaster: Boolean) {
        stopWorkerOnly()
        val gen = generationCounter.incrementAndGet()
        stopWorkerFlag.set(false)

        val track = ensureTrack()
        try {
            track.pause()
            track.flush()
            headBaselineAtFlush = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (_: Exception) {
            headBaselineAtFlush = 0L
        }

        anchorFrame = startFrame
        anchorIsLinearMaster = isLinearMaster
        writtenFramesSinceAnchor = 0L

        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val shortBuf = ShortArray(chunkFrames * channels)
            val scratch = CompAudioStitcher.MixScratch(chunkFrames)
            var frameCursor = startFrame
            var trackStarted = false

            while (!stopWorkerFlag.get() && isPlayingFlag && generationCounter.get() == gen) {
                val snap = snapshot
                val totalTargetFrames = if (isLinearMaster) {
                    (snap.masterDurationSec * sampleRate).roundToLong()
                } else {
                    CompAudioStitcher.totalOutputFrames(snap.frameMap)
                }

                if (frameCursor >= totalTargetFrames) {
                    // Drain remainder in hardware buffer before signaling end
                    while (!stopWorkerFlag.get() && isPlayingFlag && generationCounter.get() == gen) {
                        val rawHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        val played = (rawHead - if (rawHead < headBaselineAtFlush) 0L else headBaselineAtFlush)
                        if (played >= writtenFramesSinceAnchor - 256L) break
                        try {
                            Thread.sleep(15L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (!stopWorkerFlag.get() && isPlayingFlag && generationCounter.get() == gen) {
                        val endSec = if (isLinearMaster) {
                            snap.masterDurationSec
                        } else {
                            snap.frameMap.lastOrNull()?.masterEndSec ?: snap.masterDurationSec
                        }
                        mainHandler.post {
                            if (generationCounter.get() == gen && isPlayingFlag) {
                                pausedMasterSec = endSec
                                isPlayingFlag = false
                                try {
                                    track.pause()
                                    track.flush()
                                } catch (_: Exception) {
                                }
                                onPlayingChanged(false)
                                onPlayheadChanged(endSec)
                                onEnded(endSec)
                            }
                        }
                    }
                    break
                }

                val count = min(chunkFrames.toLong(), totalTargetFrames - frameCursor).toInt()
                synchronized(sourceLock) {
                    CompAudioStitcher.renderMixBlock(
                        outInterleaved = shortBuf,
                        frameCount = count,
                        outputFrameStart = frameCursor,
                        compareMasterFrameStart = frameCursor,
                        takes = snap.takes,
                        frameMap = snap.frameMap,
                        sources = sources,
                        playbackMode = snap.playbackMode,
                        audioMode = snap.audioMode,
                        lockedTakeId = snap.lockedTakeId,
                        auditionTakeId = snap.auditionTakeId,
                        crossfadeMs = snap.crossfadeMs,
                        sampleRate = sampleRate,
                        scratch = scratch
                    )
                }

                if (stopWorkerFlag.get() || generationCounter.get() != gen) break
                val written = track.write(shortBuf, 0, count * channels, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break

                if (!trackStarted) {
                    try {
                        track.play()
                    } catch (_: Exception) {
                    }
                    trackStarted = true
                }

                val framesWrittenNow = (written / channels).toLong()
                frameCursor += framesWrittenNow
                writtenFramesSinceAnchor += framesWrittenNow
            }
        }, "TakeMaster-AudioThread")
        workerThread = thread
        thread.start()
    }
}
