package com.takemaster.studio.audio

import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.ExportAudioMode
import com.takemaster.studio.model.PlaybackMode
import com.takemaster.studio.model.TakeItem
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sample-accurate 48kHz stereo PCM audio stitcher & crossfade engine.
 *
 * Ensures zero silence gaps and phase-coherent equal-power (sin/cos) crossfades at every cut
 * transition for both live preview playback (CompPcmAudioEngine) and MP4 export (Media3CompExporter).
 */
object CompAudioStitcher {

    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE

    interface PcmSource : Closeable {
        val totalFrames: Long

        /**
         * Reads up to [frameCount] stereo frames starting at [startFrame] into [destInterleaved]
         * (length >= frameCount * 2). Out-of-bounds frames are filled with zeros.
         */
        fun readFrames(startFrame: Long, destInterleaved: ShortArray, frameCount: Int)
    }

    class FilePcmSource(private val file: File) : PcmSource {
        private val raf = RandomAccessFile(file, "r")
        override val totalFrames: Long = file.length() / BYTES_PER_FRAME
        private var byteScratch = ByteArray(0)

        @Synchronized
        override fun readFrames(startFrame: Long, destInterleaved: ShortArray, frameCount: Int) {
            val neededShorts = frameCount * CHANNELS
            if (frameCount <= 0) return
            java.util.Arrays.fill(destInterleaved, 0, min(neededShorts, destInterleaved.size), 0.toShort())

            val validStart = max(0L, startFrame)
            val validEnd = min(totalFrames, startFrame + frameCount)
            if (validEnd <= validStart) return

            val skipFrames = (validStart - startFrame).toInt()
            val framesToRead = (validEnd - validStart).toInt()
            val bytesNeeded = framesToRead * BYTES_PER_FRAME
            if (byteScratch.size < bytesNeeded) {
                byteScratch = ByteArray(bytesNeeded)
            }

            raf.seek(validStart * BYTES_PER_FRAME)
            var totalRead = 0
            while (totalRead < bytesNeeded) {
                val n = raf.read(byteScratch, totalRead, bytesNeeded - totalRead)
                if (n <= 0) break
                totalRead += n
            }

            val shortsRead = (totalRead / BYTES_PER_SAMPLE)
            val bb = ByteBuffer.wrap(byteScratch, 0, shortsRead * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
            var destIdx = skipFrames * CHANNELS
            for (i in 0 until shortsRead) {
                if (destIdx >= destInterleaved.size) break
                destInterleaved[destIdx++] = bb.short
            }
        }

        @Synchronized
        override fun close() {
            try {
                raf.close()
            } catch (_: Exception) {
            }
        }
    }

    class MemoryPcmSource(private val interleavedStereo: ShortArray) : PcmSource {
        override val totalFrames: Long = (interleavedStereo.size / CHANNELS).toLong()

        override fun readFrames(startFrame: Long, destInterleaved: ShortArray, frameCount: Int) {
            if (frameCount <= 0) return
            java.util.Arrays.fill(destInterleaved, 0, min(frameCount * CHANNELS, destInterleaved.size), 0.toShort())
            val validStart = max(0L, startFrame)
            val validEnd = min(totalFrames, startFrame + frameCount)
            if (validEnd <= validStart) return

            val skipFrames = (validStart - startFrame).toInt()
            val framesToCopy = (validEnd - validStart).toInt()
            val srcOffset = (validStart * CHANNELS).toInt()
            val destOffset = skipFrames * CHANNELS
            System.arraycopy(interleavedStereo, srcOffset, destInterleaved, destOffset, framesToCopy * CHANNELS)
        }

        override fun close() {}
    }

    data class SegFrameRange(
        val segIndex: Int,
        val takeId: String,
        val masterStartSec: Double,
        val masterEndSec: Double,
        val outputStartFrame: Long,
        val outputEndFrame: Long,
        val takeStartFrameAtSegStart: Long
    ) {
        val outputFrameCount: Long
            get() = outputEndFrame - outputStartFrame
    }

    fun buildFrameMap(
        segments: List<CompSegment>,
        takes: List<TakeItem>,
        sampleRate: Int = SAMPLE_RATE
    ): List<SegFrameRange> {
        if (segments.isEmpty()) return emptyList()
        val takeMap = takes.associateBy { it.id }
        val result = ArrayList<SegFrameRange>(segments.size)
        var outCursor = 0L

        segments.forEachIndexed { idx, seg ->
            val take = takeMap[seg.takeId] ?: return@forEachIndexed
            val localStartSec = (seg.startMasterSec - take.trackStartOnMasterSec).coerceIn(0.0, take.durationSec)
            val localEndSec = (seg.endMasterSec - take.trackStartOnMasterSec).coerceIn(localStartSec, take.durationSec)
            val startMs = (localStartSec * 1000.0).roundToLong()
            val endMs = (localEndSec * 1000.0).roundToLong()
            val segFrames = (((endMs - startMs).coerceAtLeast(0L) / 1000.0) * sampleRate).roundToLong()
            if (segFrames <= 0L) return@forEachIndexed

            val takeStartFrame = ((startMs / 1000.0) * sampleRate).roundToLong()
            result.add(
                SegFrameRange(
                    segIndex = idx,
                    takeId = take.id,
                    masterStartSec = seg.startMasterSec,
                    masterEndSec = seg.endMasterSec,
                    outputStartFrame = outCursor,
                    outputEndFrame = outCursor + segFrames,
                    takeStartFrameAtSegStart = takeStartFrame
                )
            )
            outCursor += segFrames
        }
        return result
    }

    fun totalOutputFrames(frameMap: List<SegFrameRange>): Long =
        frameMap.lastOrNull()?.outputEndFrame ?: 0L

    fun outputFrameToMasterSec(
        frameMap: List<SegFrameRange>,
        outputFrame: Long,
        sampleRate: Int = SAMPLE_RATE
    ): Double {
        if (frameMap.isEmpty()) return 0.0
        val clamped = outputFrame.coerceIn(0L, totalOutputFrames(frameMap))
        val seg = frameMap.firstOrNull { clamped < it.outputEndFrame } ?: frameMap.last()
        val offsetFrames = (clamped - seg.outputStartFrame).coerceIn(0L, seg.outputFrameCount)
        return (seg.masterStartSec + offsetFrames.toDouble() / sampleRate.toDouble())
            .coerceIn(seg.masterStartSec, seg.masterEndSec)
    }

    fun masterSecToOutputFrame(
        frameMap: List<SegFrameRange>,
        masterSec: Double,
        sampleRate: Int = SAMPLE_RATE
    ): Long {
        if (frameMap.isEmpty()) return 0L
        val seg = frameMap.firstOrNull { masterSec <= it.masterEndSec + 1e-4 } ?: frameMap.last()
        val localSec = (masterSec - seg.masterStartSec).coerceIn(0.0, seg.masterEndSec - seg.masterStartSec)
        val offsetFrames = (localSec * sampleRate).roundToLong().coerceIn(0L, seg.outputFrameCount)
        return seg.outputStartFrame + offsetFrames
    }

    class MixScratch(maxBlockFrames: Int = 4096) {
        var accum = FloatArray(maxBlockFrames * CHANNELS)
        var readBuf = ShortArray(maxBlockFrames * CHANNELS)

        fun ensureCapacity(frames: Int) {
            val needed = frames * CHANNELS
            if (accum.size < needed) accum = FloatArray(needed)
            if (readBuf.size < needed) readBuf = ShortArray(needed)
        }
    }

    /**
     * Renders [frameCount] interleaved stereo 16-bit frames into [outInterleaved] starting at:
     * - [outputFrameStart] in MASTER_COMP output timeline (for MASTER_COMP & LOCK_TO_TAKE & audition)
     * - [compareMasterFrameStart] in master timeline (for COMPARE_TAKES)
     */
    fun renderMixBlock(
        outInterleaved: ShortArray,
        frameCount: Int,
        outputFrameStart: Long,
        compareMasterFrameStart: Long,
        takes: List<TakeItem>,
        frameMap: List<SegFrameRange>,
        sources: Map<String, PcmSource>,
        playbackMode: PlaybackMode,
        audioMode: ExportAudioMode,
        lockedTakeId: String?,
        auditionTakeId: String?,
        crossfadeMs: Int,
        sampleRate: Int = SAMPLE_RATE,
        scratch: MixScratch = MixScratch(frameCount)
    ) {
        if (frameCount <= 0) return
        scratch.ensureCapacity(frameCount)
        val accum = scratch.accum
        val readBuf = scratch.readBuf
        java.util.Arrays.fill(accum, 0, frameCount * CHANNELS, 0f)

        val takeMap = takes.associateBy { it.id }

        when {
            playbackMode == PlaybackMode.COMPARE_TAKES -> {
                val anySolo = takes.any { it.isSolo }
                val audibleTakes = takes.filter { if (anySolo) it.isSolo else !it.isMuted }
                if (audibleTakes.isNotEmpty()) {
                    val norm = 1.0f / sqrt(audibleTakes.size.toFloat())
                    for (take in audibleTakes) {
                        val src = sources[take.id] ?: continue
                        val takeStartFrame = compareMasterFrameStart - (take.trackStartOnMasterSec * sampleRate).roundToLong()
                        src.readFrames(takeStartFrame, readBuf, frameCount)
                        for (i in 0 until frameCount * CHANNELS) {
                            accum[i] += readBuf[i].toFloat() * norm
                        }
                    }
                }
            }

            auditionTakeId != null -> {
                val audTake = takeMap[auditionTakeId]
                val src = audTake?.let { sources[it.id] }
                if (audTake != null && src != null) {
                    val takeStartFrame = compareMasterFrameStart - (audTake.trackStartOnMasterSec * sampleRate).roundToLong()
                    src.readFrames(takeStartFrame, readBuf, frameCount)
                    for (i in 0 until frameCount * CHANNELS) {
                        accum[i] = readBuf[i].toFloat()
                    }
                }
            }

            audioMode == ExportAudioMode.LOCK_TO_TAKE -> {
                val locked = takeMap[lockedTakeId] ?: takes.firstOrNull { it.hasAudio }
                val src = locked?.let { sources[it.id] }
                if (locked != null && src != null) {
                    // Preserve exact lock start synced to first comp segment
                    val firstMasterFrame = ((frameMap.firstOrNull()?.masterStartSec ?: 0.0) * sampleRate).roundToLong()
                    val takeOffsetFromCompStart = firstMasterFrame - (locked.trackStartOnMasterSec * sampleRate).roundToLong()
                    val takeStartFrame = takeOffsetFromCompStart + outputFrameStart
                    src.readFrames(takeStartFrame, readBuf, frameCount)
                    val fadeFrames = ((min(crossfadeMs, 10) / 1000.0) * sampleRate).roundToInt().coerceAtLeast(0)
                    val totalCompFrames = totalOutputFrames(frameMap)
                    for (f in 0 until frameCount) {
                        val globalF = outputFrameStart + f
                        var g = 1.0f
                        if (fadeFrames > 0) {
                            if (globalF < fadeFrames) {
                                val p = globalF.toDouble() / fadeFrames.toDouble()
                                g = sin(p * 0.5 * PI).toFloat()
                            } else if (globalF >= totalCompFrames - fadeFrames && globalF < totalCompFrames) {
                                val p = (globalF - (totalCompFrames - fadeFrames)).toDouble() / fadeFrames.toDouble()
                                g = cos(p * 0.5 * PI).toFloat()
                            } else if (globalF >= totalCompFrames) {
                                g = 0f
                            }
                        }
                        val base = f * CHANNELS
                        accum[base] = readBuf[base].toFloat() * g
                        accum[base + 1] = readBuf[base + 1].toFloat() * g
                    }
                }
            }

            else -> {
                // FOLLOW_ACTIVE_TAKE: Sample-accurate comp stitching with true overlapping equal-power crossfade
                val totalCompFrames = totalOutputFrames(frameMap)
                if (frameMap.isNotEmpty() && totalCompFrames > 0L) {
                    val requestedHalfXfade = ((crossfadeMs.coerceIn(0, 50) / 1000.0) * sampleRate * 0.5).roundToLong()
                    val blockEnd = outputFrameStart + frameCount

                    for (idx in frameMap.indices) {
                        val seg = frameMap[idx]
                        val src = sources[seg.takeId] ?: continue

                        // Cap half-xfade cleanly so adjacent boundaries never collide on tiny segments
                        val maxHalfLeft = seg.outputFrameCount / 3L
                        val halfIn = if (idx > 0 && requestedHalfXfade > 0L) {
                            val prevLen = frameMap[idx - 1].outputFrameCount / 3L
                            val availPre = seg.takeStartFrameAtSegStart.coerceAtLeast(0L)
                            min(requestedHalfXfade, min(min(maxHalfLeft, prevLen), availPre))
                        } else 0L

                        val halfOut = if (idx < frameMap.lastIndex && requestedHalfXfade > 0L) {
                            val nextLen = frameMap[idx + 1].outputFrameCount / 3L
                            val endInTake = seg.takeStartFrameAtSegStart + seg.outputFrameCount
                            val availPost = (src.totalFrames - endInTake).coerceAtLeast(0L)
                            min(requestedHalfXfade, min(min(maxHalfLeft, nextLen), availPost))
                        } else 0L

                        // Mirror symmetric window across shared boundaries: left tail == right head
                        val prevBoundaryHalf = if (idx > 0 && requestedHalfXfade > 0L) {
                            val prevSrc = sources[frameMap[idx - 1].takeId]
                            val prevEndInTake = frameMap[idx - 1].takeStartFrameAtSegStart + frameMap[idx - 1].outputFrameCount
                            val prevPost = ((prevSrc?.totalFrames ?: 0L) - prevEndInTake).coerceAtLeast(0L)
                            min(halfIn, prevPost)
                        } else 0L

                        val nextBoundaryHalf = if (idx < frameMap.lastIndex && requestedHalfXfade > 0L) {
                            val nextPre = frameMap[idx + 1].takeStartFrameAtSegStart.coerceAtLeast(0L)
                            min(halfOut, nextPre)
                        } else 0L

                        val segActiveStart = (seg.outputStartFrame - prevBoundaryHalf).coerceAtLeast(0L)
                        val segActiveEnd = (seg.outputEndFrame + nextBoundaryHalf).coerceAtMost(totalCompFrames)
                        if (segActiveEnd <= outputFrameStart || segActiveStart >= blockEnd) continue

                        val overlapStart = max(outputFrameStart, segActiveStart)
                        val overlapEnd = min(blockEnd, segActiveEnd)
                        val readCount = (overlapEnd - overlapStart).toInt()
                        if (readCount <= 0) continue

                        val takeReadStart = seg.takeStartFrameAtSegStart + (overlapStart - seg.outputStartFrame)
                        src.readFrames(takeReadStart, readBuf, readCount)

                        val inWinStart = seg.outputStartFrame - prevBoundaryHalf
                        val inWinLen = (2L * prevBoundaryHalf).coerceAtLeast(1L)
                        val outWinStart = seg.outputEndFrame - nextBoundaryHalf
                        val outWinLen = (2L * nextBoundaryHalf).coerceAtLeast(1L)

                        val outerInFrames = if (idx == 0) min((0.004 * sampleRate).roundToLong(), seg.outputFrameCount / 4L) else 0L
                        val outerOutFrames = if (idx == frameMap.lastIndex) min((0.006 * sampleRate).roundToLong(), seg.outputFrameCount / 4L) else 0L

                        for (k in 0 until readCount) {
                            val outFrame = overlapStart + k
                            var gain = 1.0f

                            if (prevBoundaryHalf > 0L && outFrame >= inWinStart && outFrame < inWinStart + inWinLen) {
                                val phase = (outFrame - inWinStart).toDouble() / inWinLen.toDouble()
                                gain *= sin(phase * 0.5 * PI).toFloat()
                            } else if (idx == 0 && outerInFrames > 0L && outFrame < outerInFrames) {
                                val phase = outFrame.toDouble() / outerInFrames.toDouble()
                                gain *= sin(phase * 0.5 * PI).toFloat()
                            }

                            if (nextBoundaryHalf > 0L && outFrame >= outWinStart && outFrame < outWinStart + outWinLen) {
                                val phase = (outFrame - outWinStart).toDouble() / outWinLen.toDouble()
                                gain *= cos(phase * 0.5 * PI).toFloat()
                            } else if (idx == frameMap.lastIndex && outerOutFrames > 0L && outFrame >= totalCompFrames - outerOutFrames) {
                                val phase = (outFrame - (totalCompFrames - outerOutFrames)).toDouble() / outerOutFrames.toDouble()
                                gain *= cos(phase * 0.5 * PI).toFloat()
                            }

                            val destFrame = (outFrame - outputFrameStart).toInt()
                            val destBase = destFrame * CHANNELS
                            val srcBase = k * CHANNELS
                            accum[destBase] += readBuf[srcBase].toFloat() * gain
                            accum[destBase + 1] += readBuf[srcBase + 1].toFloat() * gain
                        }
                    }
                }
            }
        }

        val totalShorts = frameCount * CHANNELS
        for (i in 0 until totalShorts) {
            outInterleaved[i] = accum[i].roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /**
     * Renders a complete gapless 48kHz 16-bit stereo WAV file with overlapping equal-power
     * crossfades (or locked continuous take audio) ready for Media3 Transformer export.
     */
    fun writeExportWav(
        outWavFile: File,
        takes: List<TakeItem>,
        frameMap: List<SegFrameRange>,
        sources: Map<String, PcmSource>,
        audioMode: ExportAudioMode,
        lockedTakeId: String?,
        crossfadeMs: Int,
        sampleRate: Int = SAMPLE_RATE
    ) {
        val totalFrames = totalOutputFrames(frameMap).coerceAtLeast(1L)
        val pcmDataBytes = totalFrames * BYTES_PER_FRAME
        val blockFrames = 4096
        val shortBlock = ShortArray(blockFrames * CHANNELS)
        val byteBlock = ByteArray(blockFrames * BYTES_PER_FRAME)
        val scratch = MixScratch(blockFrames)

        BufferedOutputStream(FileOutputStream(outWavFile), 65536).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36L + pcmDataBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(CHANNELS.toShort())
                putInt(sampleRate)
                putInt(sampleRate * BYTES_PER_FRAME)
                putShort(BYTES_PER_FRAME.toShort())
                putShort(16) // 16-bit
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmDataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            out.write(header.array())

            var cursor = 0L
            while (cursor < totalFrames) {
                val count = min(blockFrames.toLong(), totalFrames - cursor).toInt()
                renderMixBlock(
                    outInterleaved = shortBlock,
                    frameCount = count,
                    outputFrameStart = cursor,
                    compareMasterFrameStart = cursor,
                    takes = takes,
                    frameMap = frameMap,
                    sources = sources,
                    playbackMode = PlaybackMode.MASTER_COMP,
                    audioMode = audioMode,
                    lockedTakeId = lockedTakeId,
                    auditionTakeId = null,
                    crossfadeMs = crossfadeMs,
                    sampleRate = sampleRate,
                    scratch = scratch
                )
                val bb = ByteBuffer.wrap(byteBlock, 0, count * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count * CHANNELS) {
                    bb.putShort(shortBlock[i])
                }
                out.write(byteBlock, 0, count * BYTES_PER_FRAME)
                cursor += count
            }
            out.flush()
        }
    }
}
