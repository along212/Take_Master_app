package com.takemaster.studio.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.takemaster.studio.model.TakeItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

object AudioAlignmentMath {

    const val COARSE_RATE = 2000
    const val FINE_RATE = 16000
    const val PEAKS_PER_SEC = 50.0

    data class OffsetEstimate(
        val offsetSeconds: Double,
        val confidencePct: Double
    )

    fun transientSuppressionFilter(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val hp = FloatArray(samples.size)
        var prev = 0f
        var activeSqSum = 0.0
        var activeCount = 0
        for (i in samples.indices) {
            val v = samples[i] - 0.85f * prev
            prev = samples[i]
            hp[i] = v
            if (abs(v) > 0.002f) {
                activeSqSum += v * v
                activeCount++
            }
        }
        val rms = if (activeCount > 0) {
            sqrt(activeSqSum / activeCount).toFloat().coerceAtLeast(0.01f)
        } else {
            0.02f
        }
        val inv = 1.0f / (1.35f * rms)
        for (i in hp.indices) {
            hp[i] = tanh(hp[i] * inv)
        }
        return hp
    }

    /**
     * Two-stage audio offset estimator:
     * Stage 1: Global FFT cross-correlation at 2kHz across ±60s with overlap-length normalization.
     * Stage 2: High-precision time-domain refinement at 16kHz + parabolic interpolation (< 0.1ms).
     *
     * Sign convention: offsetSeconds > 0 means [target] started earlier than [ref]
     * (so target[t + offsetSeconds] aligns with ref[t]).
     */
    fun estimateOffset(
        refCoarse: FloatArray,
        targetCoarse: FloatArray,
        refFine: FloatArray,
        targetFine: FloatArray,
        coarseRate: Int = COARSE_RATE,
        fineRate: Int = FINE_RATE
    ): OffsetEstimate {
        if (refCoarse.size < coarseRate || targetCoarse.size < coarseRate) {
            return OffsetEstimate(0.0, 50.0)
        }

        val x = transientSuppressionFilter(refCoarse)
        val y = transientSuppressionFilter(targetCoarse)

        var fftSize = 1
        val needed = x.size + y.size
        while (fftSize < needed) fftSize = fftSize shl 1

        val xRe = DoubleArray(fftSize)
        val xIm = DoubleArray(fftSize)
        val yRe = DoubleArray(fftSize)
        val yIm = DoubleArray(fftSize)

        for (i in x.indices) xRe[i] = x[i].toDouble()
        for (i in y.indices) yRe[i] = y[i].toDouble()

        radix2FFT(xRe, xIm, invert = false)
        radix2FFT(yRe, yIm, invert = false)

        // IFFT(conj(X) * Y) -> corr[lag] = sum_n x[n] * y[n + lag]
        for (i in 0 until fftSize) {
            val aRe = xRe[i]
            val aIm = -xIm[i]
            val bRe = yRe[i]
            val bIm = yIm[i]
            yRe[i] = aRe * bRe - aIm * bIm
            yIm[i] = aRe * bIm + aIm * bRe
        }
        radix2FFT(yRe, yIm, invert = true)

        val maxSearchLag = min(
            min(x.size, y.size) - coarseRate,
            60 * coarseRate
        ).coerceAtLeast(1)
        val minOverlap = (min(x.size, y.size) * 0.30).toInt().coerceAtLeast(coarseRate)

        // Prefix energy sums for exact Normalized Cross-Correlation (NCC) so quiet/loud sections never bias lag
        val prefX2 = DoubleArray(x.size + 1)
        for (i in x.indices) {
            val v = x[i].toDouble()
            prefX2[i + 1] = prefX2[i] + v * v
        }
        val prefY2 = DoubleArray(y.size + 1)
        for (i in y.indices) {
            val v = y[i].toDouble()
            prefY2[i + 1] = prefY2[i] + v * v
        }

        var bestLag = 0
        var maxScore = Double.NEGATIVE_INFINITY
        var sumAbs = 0.0
        var count = 0

        for (lag in -maxSearchLag..maxSearchLag) {
            val xStart = max(0, -lag)
            val xEnd = min(x.size, y.size - lag)
            val overlap = xEnd - xStart
            if (overlap < minOverlap) continue

            val yStart = xStart + lag
            val yEnd = xEnd + lag
            val energyX = prefX2[xEnd] - prefX2[xStart]
            val energyY = prefY2[yEnd] - prefY2[yStart]
            val denomEnergy = sqrt(energyX * energyY)
            if (denomEnergy < 1e-6) continue

            val idx = (lag + fftSize) and (fftSize - 1)
            val normCorr = yRe[idx] / denomEnergy
            sumAbs += abs(normCorr)
            count++
            if (normCorr > maxScore) {
                maxScore = normCorr
                bestLag = lag
            }
        }

        if (count == 0) return OffsetEstimate(0.0, 50.0)

        val idxPrev = (bestLag - 1 + fftSize) and (fftSize - 1)
        val idxNext = (bestLag + 1 + fftSize) and (fftSize - 1)
        val fracCoarse = parabolicFraction(yRe[idxPrev], yRe[(bestLag + fftSize) and (fftSize - 1)], yRe[idxNext])
        var refinedOffsetSec = (bestLag + fracCoarse) / coarseRate.toDouble()

        val meanAbs = (sumAbs / count).coerceAtLeast(1e-6)
        val snr = maxScore / meanAbs
        val confidence = min(99.9, max(65.0, 68.0 + min(31.9, snr * 2.1)))

        // Stage 2: 16kHz fine refinement if fine signals overlap sufficiently
        if (refFine.size >= fineRate * 2 && targetFine.size >= fineRate * 2) {
            val fineRefined = refineFineOffset(refFine, targetFine, refinedOffsetSec, fineRate)
            if (fineRefined != null) {
                refinedOffsetSec = fineRefined
            }
        }

        return OffsetEstimate(refinedOffsetSec, confidence)
    }

    private fun refineFineOffset(
        refFine: FloatArray,
        targetFine: FloatArray,
        coarseOffsetSec: Double,
        fineRate: Int
    ): Double? {
        val coarseLag = Math.round(coarseOffsetSec * fineRate).toInt()
        val windowLen = min(10 * fineRate, min(refFine.size, targetFine.size) / 2)
        if (windowLen < fineRate) return null

        val searchRadius = (0.060 * fineRate).toInt() // ±60 ms

        // Choose an active 10s region in ref that also falls inside target even across ±searchRadius
        val validRefStartMin = max(0, -coarseLag + searchRadius)
        val validRefStartMax = min(
            refFine.size - windowLen,
            targetFine.size - windowLen - coarseLag - searchRadius
        )
        if (validRefStartMax <= validRefStartMin) return null

        val refStart = findHighestEnergyStart(refFine, validRefStartMin, validRefStartMax, windowLen, fineRate)

        val refSlice = transientSuppressionFilter(refFine.copyOfRange(refStart, refStart + windowLen))
        val targetWinStart = (refStart + coarseLag - searchRadius - 16).coerceAtLeast(0)
        val targetWinEnd = (refStart + coarseLag + searchRadius + windowLen + 16).coerceAtMost(targetFine.size)
        val targetSlice = transientSuppressionFilter(targetFine.copyOfRange(targetWinStart, targetWinEnd))

        // Step 2a: search every 4 samples over ±60ms
        var bestRoughLag = coarseLag
        var bestRoughScore = Double.NEGATIVE_INFINITY
        var lag = coarseLag - searchRadius
        while (lag <= coarseLag + searchRadius) {
            val relStart = (refStart + lag) - targetWinStart
            if (relStart >= 0 && relStart + windowLen <= targetSlice.size) {
                var sum = 0.0
                var i = 0
                while (i < windowLen) {
                    sum += refSlice[i] * targetSlice[relStart + i]
                    i += 4
                }
                if (sum > bestRoughScore) {
                    bestRoughScore = sum
                    bestRoughLag = lag
                }
            }
            lag += 4
        }

        // Step 2b: single-sample search over ±8 samples around bestRoughLag
        val fineRadius = 8
        val scores = DoubleArray(fineRadius * 2 + 1)
        var bestIdx = fineRadius
        var bestFineScore = Double.NEGATIVE_INFINITY
        for (d in -fineRadius..fineRadius) {
            val candidateLag = bestRoughLag + d
            val relStart = (refStart + candidateLag) - targetWinStart
            if (relStart < 0 || relStart + windowLen > targetSlice.size) {
                scores[d + fineRadius] = Double.NEGATIVE_INFINITY
                continue
            }
            var sum = 0.0
            for (i in 0 until windowLen) {
                sum += refSlice[i] * targetSlice[relStart + i]
            }
            scores[d + fineRadius] = sum
            if (sum > bestFineScore) {
                bestFineScore = sum
                bestIdx = d + fineRadius
            }
        }

        val frac = if (bestIdx > 0 && bestIdx < scores.lastIndex &&
            scores[bestIdx - 1].isFinite() && scores[bestIdx + 1].isFinite()
        ) {
            parabolicFraction(scores[bestIdx - 1], scores[bestIdx], scores[bestIdx + 1])
        } else 0.0

        val exactLag = bestRoughLag + (bestIdx - fineRadius) + frac
        return exactLag / fineRate.toDouble()
    }

    private fun findHighestEnergyStart(
        samples: FloatArray,
        minStart: Int,
        maxStart: Int,
        windowLen: Int,
        fineRate: Int
    ): Int {
        var bestStart = minStart
        var bestEnergy = -1.0
        val step = max(fineRate, (maxStart - minStart) / 12)
        var start = minStart
        while (start <= maxStart) {
            var energy = 0.0
            var i = start
            val end = start + windowLen
            while (i < end) {
                val s = samples[i]
                energy += s * s
                i += 16
            }
            if (energy > bestEnergy) {
                bestEnergy = energy
                bestStart = start
            }
            start += step
        }
        return bestStart
    }

    private fun parabolicFraction(y1: Double, y2: Double, y3: Double): Double {
        val denom = y1 - 2.0 * y2 + y3
        return if (abs(denom) > 1e-9) {
            (0.5 * (y1 - y3) / denom).coerceIn(-0.5, 0.5)
        } else 0.0
    }

    fun radix2FFT(re: DoubleArray, im: DoubleArray, invert: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * if (invert) -1 else 1
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)
            for (i in 0 until n step len) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                    val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextWRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextWRe
                }
            }
            len = len shl 1
        }
        if (invert) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}

object AudioAligner {

    private const val MAX_COARSE_SECONDS = 120.0
    private const val MAX_FINE_SECONDS = 75.0

    private data class DecodedAudio(
        val hasAudio: Boolean,
        val hasVideo: Boolean,
        val durationSec: Double,
        val coarse2k: FloatArray,
        val fine16k: FloatArray,
        val waveformPeaks: FloatArray
    )

    private data class VideoMeta(
        val displayName: String,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val durationSec: Double,
        val hasVideo: Boolean
    )

    suspend fun alignTakes(
        context: Context,
        uris: List<Uri>,
        startIndex: Int = 0,
        referenceTake: TakeItem? = null,
        onProgress: (index: Int, total: Int, message: String) -> Unit = { _, _, _ -> }
    ): List<TakeItem> = withContext(Dispatchers.Default) {
        if (uris.isEmpty()) return@withContext emptyList()

        val pcmDir = File(context.cacheDir, "pcm_cache").apply { mkdirs() }
        val decodedList = mutableListOf<Triple<String, Pair<Uri, File>, Pair<VideoMeta, DecodedAudio>>>()
        for ((i, uri) in uris.withIndex()) {
            coroutineContext.ensureActive()
            val takeNumber = startIndex + i + 1
            onProgress(i, uris.size, "Decoding audio & waveform for Take $takeNumber...")
            val takeId = UUID.randomUUID().toString()
            val pcmFile = File(pcmDir, "$takeId.pcm")
            val meta = readVideoMeta(context, uri)
            val audio = extractAudioAndPeaks(context, uri, meta.durationSec, pcmFile)
            decodedList.add(Triple(takeId, uri to pcmFile, meta to audio))
        }

        // If appending to an existing project, re-extract coarse+fine reference audio from referenceTake
        val refAudio: DecodedAudio = if (referenceTake != null) {
            onProgress(uris.size, uris.size, "Loading Take 1 sync reference...")
            extractAudioForAlignmentOnly(context, referenceTake.uri, referenceTake.durationSec)
        } else {
            decodedList.first().third.second
        }

        val results = mutableListOf<TakeItem>()
        for ((i, item) in decodedList.withIndex()) {
            coroutineContext.ensureActive()
            val (takeId, uriAndPcm, metaAndAudio) = item
            val (uri, pcmFile) = uriAndPcm
            val (meta, audio) = metaAndAudio
            val takeIdx = startIndex + i
            val takeNumber = takeIdx + 1

            val estimate = if (referenceTake == null && i == 0) {
                AudioAlignmentMath.OffsetEstimate(0.0, 100.0)
            } else if (!refAudio.hasAudio || !audio.hasAudio) {
                AudioAlignmentMath.OffsetEstimate(0.0, 0.0)
            } else {
                onProgress(i + 1, uris.size, "Aligning Take $takeNumber...")
                AudioAlignmentMath.estimateOffset(
                    refCoarse = refAudio.coarse2k,
                    targetCoarse = audio.coarse2k,
                    refFine = refAudio.fine16k,
                    targetFine = audio.fine16k
                )
            }

            if (!audio.hasAudio && pcmFile.exists()) {
                pcmFile.delete()
            }

            results.add(
                TakeItem(
                    id = takeId,
                    index = takeIdx,
                    label = "Take $takeNumber",
                    sourceName = meta.displayName,
                    uriString = uri.toString(),
                    pcmPath = if (audio.hasAudio && pcmFile.exists()) pcmFile.absolutePath else null,
                    durationSec = audio.durationSec.coerceAtLeast(meta.durationSec),
                    width = meta.width,
                    height = meta.height,
                    rotationDegrees = meta.rotationDegrees,
                    hasVideo = meta.hasVideo || audio.hasVideo,
                    hasAudio = audio.hasAudio,
                    isDemo = false,
                    autoOffsetSec = estimate.offsetSeconds,
                    manualNudgeMs = 0,
                    confidencePct = estimate.confidencePct,
                    waveformPeaks = audio.waveformPeaks,
                    peaksPerSec = AudioAlignmentMath.PEAKS_PER_SEC
                )
            )
        }
        results
    }

    fun ensurePcmCacheFile(context: Context, take: TakeItem): File? {
        if (!take.hasAudio) return null
        val existing = take.pcmPath?.let { File(it) }
        if (existing != null && existing.exists() && existing.length() > 0L) {
            return existing
        }
        val pcmDir = File(context.cacheDir, "pcm_cache").apply { mkdirs() }
        val target = File(pcmDir, "${take.id}.pcm")
        val decoded = extractAudioAndPeaks(context, take.uri, take.durationSec, target)
        return if (decoded.hasAudio && target.exists() && target.length() > 0L) target else null
    }

    fun cleanupUnusedPcmCache(context: Context, activePaths: Set<String>) {
        val pcmDir = File(context.cacheDir, "pcm_cache")
        if (!pcmDir.exists()) return
        pcmDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in activePaths) {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun readVideoMeta(context: Context, uri: Uri): VideoMeta {
        var displayName = "Video"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        displayName = cursor.getString(idx) ?: displayName
                    }
                }
            }
        } catch (_: Exception) {
        }

        var w = 1280
        var h = 720
        var rot = 0
        var durSec = 0.0
        var hasVideo = false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val hasVidStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val parsedW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val parsedH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            hasVideo = hasVidStr?.equals("yes", ignoreCase = true) == true ||
                ((parsedW ?: 0) > 0 && (parsedH ?: 0) > 0)
            w = parsedW ?: w
            h = parsedH ?: h
            rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durSec = durMs / 1000.0
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        return VideoMeta(displayName, w, h, rot, durSec, hasVideo)
    }

    private fun extractAudioForAlignmentOnly(context: Context, uri: Uri, fallbackDurationSec: Double): DecodedAudio {
        return extractAudioInternal(context, uri, fallbackDurationSec, stopAfterAlignmentWindow = true, pcmOutFile = null)
    }

    private fun extractAudioAndPeaks(
        context: Context,
        uri: Uri,
        fallbackDurationSec: Double,
        pcmOutFile: File? = null
    ): DecodedAudio {
        return extractAudioInternal(context, uri, fallbackDurationSec, stopAfterAlignmentWindow = false, pcmOutFile = pcmOutFile)
    }

    private class Stereo48kPcmSink(file: File) : AutoCloseable {
        private val stream = BufferedOutputStream(FileOutputStream(file), 65536)
        private val outBuf = ByteArray(16384)
        private var outPos = 0

        private var hasPrev = false
        private var prevL = 0f
        private var prevR = 0f
        private var srcIndex = 0L
        private var phaseInSrc = 0.0

        fun pushFrame(l: Float, r: Float, srcSampleRate: Int) {
            if (srcSampleRate <= 0) return
            if (!hasPrev) {
                prevL = l
                prevR = r
                hasPrev = true
                srcIndex = 0L
                phaseInSrc = 0.0
                return
            }
            srcIndex++
            val step = srcSampleRate.toDouble() / CompAudioStitcher.SAMPLE_RATE.toDouble()
            while (phaseInSrc <= srcIndex.toDouble()) {
                val frac = (phaseInSrc - (srcIndex - 1L).toDouble()).coerceIn(0.0, 1.0).toFloat()
                val outL = prevL + (l - prevL) * frac
                val outR = prevR + (r - prevR) * frac
                writeSample(outL)
                writeSample(outR)
                phaseInSrc += step
            }
            prevL = l
            prevR = r
        }

        private fun writeSample(sample: Float) {
            val s = (sample * 32767f).roundToInt().coerceIn(-32767, 32767)
            if (outPos + 2 > outBuf.size) {
                stream.write(outBuf, 0, outPos)
                outPos = 0
            }
            outBuf[outPos++] = (s and 0xFF).toByte()
            outBuf[outPos++] = ((s shr 8) and 0xFF).toByte()
        }

        override fun close() {
            try {
                if (hasPrev && phaseInSrc < srcIndex.toDouble() + 0.5) {
                    writeSample(prevL)
                    writeSample(prevR)
                }
                if (outPos > 0) {
                    stream.write(outBuf, 0, outPos)
                    outPos = 0
                }
                stream.flush()
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun extractAudioInternal(
        context: Context,
        uri: Uri,
        fallbackDurationSec: Double,
        stopAfterAlignmentWindow: Boolean,
        pcmOutFile: File?
    ): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var pcmSink: Stereo48kPcmSink? = null
        try {
            extractor.setDataSource(context, uri, null)

            var audioTrackIdx = -1
            var format: MediaFormat? = null
            var hasVideoTrack = false
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    hasVideoTrack = true
                } else if (mime.startsWith("audio/") && audioTrackIdx == -1) {
                    audioTrackIdx = i
                    format = fmt
                }
            }

            if (audioTrackIdx == -1 || format == null) {
                val safeDur = fallbackDurationSec.coerceAtLeast(1.0)
                val emptyPeaks = FloatArray((safeDur * AudioAlignmentMath.PEAKS_PER_SEC).toInt().coerceAtLeast(1))
                return DecodedAudio(false, hasVideoTrack, safeDur, FloatArray(0), FloatArray(0), emptyPeaks)
            }

            extractor.selectTrack(audioTrackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            val durationSecFromTrack = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            } else {
                fallbackDurationSec
            }.coerceAtLeast(fallbackDurationSec).coerceAtLeast(0.5)

            val maxCoarseSamples = (MAX_COARSE_SECONDS * AudioAlignmentMath.COARSE_RATE).toInt()
            val maxFineSamples = (MAX_FINE_SECONDS * AudioAlignmentMath.FINE_RATE).toInt()

            val coarseList = ArrayList<Float>(min(maxCoarseSamples, (durationSecFromTrack * AudioAlignmentMath.COARSE_RATE).toInt() + 100))
            val fineList = ArrayList<Float>(min(maxFineSamples, (durationSecFromTrack * AudioAlignmentMath.FINE_RATE).toInt() + 100))
            val peakBuckets = ArrayList<Float>((durationSecFromTrack * AudioAlignmentMath.PEAKS_PER_SEC).toInt() + 16)

            var coarseAccum = 0.0
            var coarseCount = 0
            var fineAccum = 0.0
            var fineCount = 0
            var peakMax = 0f
            var peakCount = 0
            var totalMonoFrames = 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            if (pcmOutFile != null && !stopAfterAlignmentWindow) {
                pcmOutFile.parentFile?.mkdirs()
                pcmSink = Stereo48kPcmSink(pcmOutFile)
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFmt = codec.outputFormat
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    outBuf.order(ByteOrder.LITTLE_ENDIAN)

                    val coarseStep = max(1, (srcSampleRate.toDouble() / AudioAlignmentMath.COARSE_RATE).toInt())
                    val fineStep = max(1, (srcSampleRate.toDouble() / AudioAlignmentMath.FINE_RATE).toInt())
                    val peakStep = max(1, (srcSampleRate.toDouble() / AudioAlignmentMath.PEAKS_PER_SEC).toInt())

                    if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        val floatBuf = outBuf.asFloatBuffer()
                        while (floatBuf.remaining() >= channels) {
                            var s0 = 0f
                            var s1 = 0f
                            var sum = 0f
                            for (c in 0 until channels) {
                                val v = floatBuf.get()
                                if (c == 0) s0 = v
                                if (c == 1) s1 = v
                                sum += v
                            }
                            if (channels == 1) s1 = s0
                            pcmSink?.pushFrame(s0, s1, srcSampleRate)
                            val mono = sum / channels
                            totalMonoFrames++

                            if (coarseList.size < maxCoarseSamples) {
                                coarseAccum += mono
                                coarseCount++
                                if (coarseCount >= coarseStep) {
                                    coarseList.add((coarseAccum / coarseCount).toFloat())
                                    coarseAccum = 0.0
                                    coarseCount = 0
                                }
                            }
                            if (fineList.size < maxFineSamples) {
                                fineAccum += mono
                                fineCount++
                                if (fineCount >= fineStep) {
                                    fineList.add((fineAccum / fineCount).toFloat())
                                    fineAccum = 0.0
                                    fineCount = 0
                                }
                            }
                            if (!stopAfterAlignmentWindow) {
                                val a = abs(mono)
                                if (a > peakMax) peakMax = a
                                peakCount++
                                if (peakCount >= peakStep) {
                                    peakBuckets.add(peakMax)
                                    peakMax = 0f
                                    peakCount = 0
                                }
                            }
                        }
                    } else {
                        val shortBuf = outBuf.asShortBuffer()
                        while (shortBuf.remaining() >= channels) {
                            var s0 = 0f
                            var s1 = 0f
                            var sum = 0f
                            for (c in 0 until channels) {
                                val v = shortBuf.get() / 32768f
                                if (c == 0) s0 = v
                                if (c == 1) s1 = v
                                sum += v
                            }
                            if (channels == 1) s1 = s0
                            pcmSink?.pushFrame(s0, s1, srcSampleRate)
                            val mono = sum / channels
                            totalMonoFrames++

                            if (coarseList.size < maxCoarseSamples) {
                                coarseAccum += mono
                                coarseCount++
                                if (coarseCount >= coarseStep) {
                                    coarseList.add((coarseAccum / coarseCount).toFloat())
                                    coarseAccum = 0.0
                                    coarseCount = 0
                                }
                            }
                            if (fineList.size < maxFineSamples) {
                                fineAccum += mono
                                fineCount++
                                if (fineCount >= fineStep) {
                                    fineList.add((fineAccum / fineCount).toFloat())
                                    fineAccum = 0.0
                                    fineCount = 0
                                }
                            }
                            if (!stopAfterAlignmentWindow) {
                                val a = abs(mono)
                                if (a > peakMax) peakMax = a
                                peakCount++
                                if (peakCount >= peakStep) {
                                    peakBuckets.add(peakMax)
                                    peakMax = 0f
                                    peakCount = 0
                                }
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    } else if (stopAfterAlignmentWindow &&
                        coarseList.size >= maxCoarseSamples &&
                        fineList.size >= maxFineSamples
                    ) {
                        outputDone = true
                    }
                }
            }

            if (!stopAfterAlignmentWindow && peakCount > 0) {
                peakBuckets.add(peakMax)
            }

            // Normalize peaks using 98th percentile so occasional spikes don't squash quiet sections
            val peaksArray = peakBuckets.toFloatArray()
            if (peaksArray.isNotEmpty()) {
                val sorted = peaksArray.copyOf()
                sorted.sort()
                val p98 = sorted[((sorted.size - 1) * 0.98).toInt()].coerceAtLeast(0.05f)
                for (i in peaksArray.indices) {
                    peaksArray[i] = (peaksArray[i] / p98).coerceIn(0.03f, 1.0f)
                }
            }

            val decodedDur = if (srcSampleRate > 0 && totalMonoFrames > 0 && !stopAfterAlignmentWindow) {
                totalMonoFrames.toDouble() / srcSampleRate.toDouble()
            } else {
                durationSecFromTrack
            }

            return DecodedAudio(
                hasAudio = true,
                hasVideo = hasVideoTrack,
                durationSec = max(durationSecFromTrack, decodedDur),
                coarse2k = coarseList.toFloatArray(),
                fine16k = fineList.toFloatArray(),
                waveformPeaks = peaksArray
            )
        } finally {
            try {
                pcmSink?.close()
            } catch (_: Exception) {
            }
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Generates an instant 3-take demo multi-track session (with realistic start-time offsets
 * and distinct performance nuances per take) so users can test timeline editing, comp cuts,
 * cross-fades, and multi-take alignment immediately without loading local video files.
 */
object DemoSessionGenerator {

    private const val SAMPLE_RATE = CompAudioStitcher.SAMPLE_RATE

    private data class DemoSpec(
        val label: String,
        val durationSec: Double,
        val trueOffsetSec: Double,
        val accentVariant: Int
    )

    suspend fun createDemoTakes(context: Context): List<TakeItem> = withContext(Dispatchers.Default) {
        val dir = File(context.cacheDir, "pcm_cache").apply { mkdirs() }
        val specs = listOf(
            DemoSpec("Take 1", durationSec = 20.0, trueOffsetSec = 0.0, accentVariant = 0),
            DemoSpec("Take 2", durationSec = 21.5, trueOffsetSec = 1.342, accentVariant = 1),
            DemoSpec("Take 3", durationSec = 20.8, trueOffsetSec = 0.618, accentVariant = 2)
        )

        val synthesized = specs.map { spec ->
            coroutineContext.ensureActive()
            val takeId = UUID.randomUUID().toString()
            val file = File(dir, "$takeId.pcm")
            val analysis = synthesizeAndWritePcm(file, spec.durationSec, spec.trueOffsetSec, spec.accentVariant)
            Triple(takeId, spec to file, analysis)
        }

        val refCoarse = synthesized.first().third.coarse2k
        val refFine = synthesized.first().third.fine16k

        synthesized.mapIndexed { idx, (takeId, specAndFile, data) ->
            val (spec, file) = specAndFile
            val estimate = if (idx == 0) {
                AudioAlignmentMath.OffsetEstimate(0.0, 100.0)
            } else {
                AudioAlignmentMath.estimateOffset(
                    refCoarse = refCoarse,
                    targetCoarse = data.coarse2k,
                    refFine = refFine,
                    targetFine = data.fine16k
                )
            }
            TakeItem(
                id = takeId,
                index = idx,
                label = spec.label,
                sourceName = "Demo Session • ${spec.label}",
                uriString = Uri.fromFile(file).toString(),
                pcmPath = file.absolutePath,
                durationSec = spec.durationSec,
                width = 1280,
                height = 720,
                rotationDegrees = 0,
                hasVideo = false,
                hasAudio = true,
                isDemo = true,
                autoOffsetSec = estimate.offsetSeconds,
                manualNudgeMs = 0,
                confidencePct = estimate.confidencePct,
                waveformPeaks = data.peaks,
                peaksPerSec = AudioAlignmentMath.PEAKS_PER_SEC
            )
        }
    }

    private class SynthResult(
        val coarse2k: FloatArray,
        val fine16k: FloatArray,
        val peaks: FloatArray
    )

    private fun synthesizeAndWritePcm(
        outFile: File,
        durationSec: Double,
        shiftSec: Double,
        variant: Int
    ): SynthResult {
        val totalFrames = (durationSec * SAMPLE_RATE).toInt()
        val coarseStep = max(1, SAMPLE_RATE / AudioAlignmentMath.COARSE_RATE)
        val fineStep = max(1, SAMPLE_RATE / AudioAlignmentMath.FINE_RATE)
        val peakStep = max(1, (SAMPLE_RATE / AudioAlignmentMath.PEAKS_PER_SEC).toInt())

        val coarseList = ArrayList<Float>(totalFrames / coarseStep + 8)
        val fineList = ArrayList<Float>(totalFrames / fineStep + 8)
        val peakList = ArrayList<Float>(totalFrames / peakStep + 8)

        var cSum = 0.0
        var cCount = 0
        var fSum = 0.0
        var fCount = 0
        var pMax = 0f
        var pCount = 0

        val chordRoots = doubleArrayOf(110.0, 130.81, 146.83, 123.47, 98.0) // A2, C3, D3, B2, G2
        val beatDur = 0.5 // 120 BPM
        val outBuf = ByteArray(16384)
        var outPos = 0

        BufferedOutputStream(FileOutputStream(outFile), 65536).use { out ->
            for (i in 0 until totalFrames) {
                val tLocal = i.toDouble() / SAMPLE_RATE
                val tSong = tLocal - shiftSec

                var left = 0.0
                var right = 0.0
                if (tSong >= 0.0 && tSong <= 19.6) {
                    val bar = ((tSong / 4.0).toInt()).coerceIn(0, chordRoots.size - 1)
                    val root = chordRoots[bar]
                    val beatPos = (tSong % beatDur) / beatDur

                    // Continuous shared backing progression + sweeping FM shimmer so alignment is unmistakable
                    val vibrato = 1.5 * sin(2.0 * PI * 0.13 * tSong)
                    val backing = 0.24 * sin(2.0 * PI * (root + vibrato) * tSong) +
                        0.18 * sin(2.0 * PI * (root * 1.498) * tSong) +
                        0.14 * sin(2.0 * PI * (root * 2.0 + 4.0 * sin(2.0 * PI * 0.21 * tSong)) * tSong)

                    // Rhythmic pulse with quiet gaps between beats for valley snapping
                    val pulseEnv = when {
                        beatPos < 0.12 -> (1.0 - beatPos / 0.12) * 0.45
                        beatPos in 0.48..0.60 -> (1.0 - (beatPos - 0.48) / 0.12) * 0.28
                        else -> 0.04
                    }

                    // Performance timbre nuance per take (subtle stereo spread & harmonic overtone)
                    val perfFreq = when (variant) {
                        1 -> root * 3.0 // brighter upper harmony in Take 2
                        2 -> root * 2.5 // fifth overtone in Take 3
                        else -> root * 2.0
                    }
                    val perfAmp = if (variant == 1 && bar in 1..2) 0.24 else if (variant == 2 && bar >= 3) 0.24 else 0.16
                    val perfSignal = perfAmp * pulseEnv * sin(2.0 * PI * perfFreq * tSong)

                    val pan = when (variant) {
                        1 -> 0.88 to 1.05
                        2 -> 1.05 to 0.88
                        else -> 0.96 to 0.96
                    }
                    left = (backing + perfSignal) * pan.first
                    right = (backing + perfSignal) * pan.second
                }

                // Fade edges to avoid hard buffer clicks
                val edgeGain = min(min(tLocal / 0.02, (durationSec - tLocal) / 0.02), 1.0).coerceAtLeast(0.0)
                val lShort = ((left * edgeGain).coerceIn(-0.98, 0.98) * 32767.0).roundToInt()
                val rShort = ((right * edgeGain).coerceIn(-0.98, 0.98) * 32767.0).roundToInt()
                if (outPos + 4 > outBuf.size) {
                    out.write(outBuf, 0, outPos)
                    outPos = 0
                }
                outBuf[outPos++] = (lShort and 0xFF).toByte()
                outBuf[outPos++] = ((lShort shr 8) and 0xFF).toByte()
                outBuf[outPos++] = (rShort and 0xFF).toByte()
                outBuf[outPos++] = ((rShort shr 8) and 0xFF).toByte()

                val mono = ((lShort + rShort) * (0.5f / 32768f))
                cSum += mono
                cCount++
                if (cCount >= coarseStep) {
                    coarseList.add((cSum / cCount).toFloat())
                    cSum = 0.0
                    cCount = 0
                }
                fSum += mono
                fCount++
                if (fCount >= fineStep) {
                    fineList.add((fSum / fCount).toFloat())
                    fSum = 0.0
                    fCount = 0
                }
                val a = abs(mono)
                if (a > pMax) pMax = a
                pCount++
                if (pCount >= peakStep) {
                    peakList.add(pMax)
                    pMax = 0f
                    pCount = 0
                }
            }
            if (outPos > 0) out.write(outBuf, 0, outPos)
            out.flush()
        }
        if (pCount > 0) peakList.add(pMax)

        val peaksArray = peakList.toFloatArray()
        if (peaksArray.isNotEmpty()) {
            val sorted = peaksArray.copyOf().apply { sort() }
            val p98 = sorted[((sorted.size - 1) * 0.98).toInt()].coerceAtLeast(0.05f)
            for (i in peaksArray.indices) {
                peaksArray[i] = (peaksArray[i] / p98).coerceIn(0.04f, 1.0f)
            }
        }

        return SynthResult(
            coarse2k = coarseList.toFloatArray(),
            fine16k = fineList.toFloatArray(),
            peaks = peaksArray
        )
    }
}
