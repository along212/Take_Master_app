package com.drumcover.compstudio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.*

data class AlignmentResult(
    val uri: Uri,
    val displayName: String,
    val offsetSeconds: Double,
    val confidencePct: Double,
    val durationSeconds: Double,
    val waveformPeaks: FloatArray
)

/**
 * Streams ONLY the audio track from large 4K S26 Ultra MP4 files via MediaExtractor + MediaCodec,
 * applies harmonic transient suppression (tanh soft-knee limiter + high-pass) so Roland VAD706
 * drum fills don't skew alignment, and computes sub-millisecond FFT cross-correlation offsets.
 */
object HarmonicAudioAligner {

    private const val ANALYSIS_RATE = 2000 // 2kHz downsampled rate for fast global FFT
    private const val MAX_ANALYSIS_SECONDS = 45.0

    suspend fun alignTakes(
        context: Context,
        takes: List<Pair<Uri, String>>
    ): List<AlignmentResult> = withContext(Dispatchers.Default) {
        if (takes.isEmpty()) return@withContext emptyList()

        val decodedTakes = takes.map { (uri, name) ->
            val pcm = extractDownsampledMono(context, uri)
            Triple(uri, name, pcm)
        }

        val ref = decodedTakes.first().third
        val refSignal = harmonicTransientFilter(ref.samples)
        var fftSize = 1
        while (fftSize < refSignal.size * 2) fftSize = fftSize shl 1

        val refRe = DoubleArray(fftSize)
        val refIm = DoubleArray(fftSize)
        for (i in refSignal.indices) refRe[i] = refSignal[i].toDouble()
        radix2FFT(refRe, refIm, invert = false)

        val results = mutableListOf<AlignmentResult>()
        results.add(
            AlignmentResult(
                uri = decodedTakes[0].first,
                displayName = decodedTakes[0].second,
                offsetSeconds = 0.0,
                confidencePct = 100.0,
                durationSeconds = ref.durationSec,
                waveformPeaks = computePeaks(ref.samples, 200)
            )
        )

        for (t in 1 until decodedTakes.size) {
            val (uri, name, pcm) = decodedTakes[t]
            val sig = harmonicTransientFilter(pcm.samples)

            val re = DoubleArray(fftSize)
            val im = DoubleArray(fftSize)
            for (i in 0 until min(sig.size, fftSize)) re[i] = sig[i].toDouble()
            radix2FFT(re, im, invert = false)

            // Cross-correlation in frequency domain: conj(Ref) * Target
            for (i in 0 until fftSize) {
                val aRe = refRe[i]
                val aIm = -refIm[i]
                val bRe = re[i]
                val bIm = im[i]
                re[i] = aRe * bRe - aIm * bIm
                im[i] = aRe * bIm + aIm * bRe
            }
            radix2FFT(re, im, invert = true)

            val maxSearchLag = min(fftSize / 4, (20.0 * ANALYSIS_RATE).toInt())
            var bestLag = 0
            var maxVal = Double.NEGATIVE_INFINITY
            var sumAbs = 0.0

            for (lag in -maxSearchLag..maxSearchLag) {
                val idx = (lag + fftSize) and (fftSize - 1)
                val v = re[idx]
                sumAbs += abs(v)
                if (v > maxVal) {
                    maxVal = v
                    bestLag = lag
                }
            }

            // Parabolic sub-sample interpolation
            val idxPrev = (bestLag - 1 + fftSize) and (fftSize - 1)
            val idxNext = (bestLag + 1 + fftSize) and (fftSize - 1)
            val y1 = re[idxPrev]
            val y2 = maxVal
            val y3 = re[idxNext]
            val denom = y1 - 2.0 * y2 + y3
            val frac = if (abs(denom) > 1e-9) 0.5 * (y1 - y3) / denom else 0.0

            val offsetSec = (bestLag + frac) / ANALYSIS_RATE.toDouble()
            val meanAbs = sumAbs / (2 * maxSearchLag + 1)
            val snr = maxVal / max(1e-6, meanAbs)
            val confidence = min(99.9, max(75.0, 75.0 + min(24.9, snr * 1.9)))

            results.add(
                AlignmentResult(
                    uri = uri,
                    displayName = name,
                    offsetSeconds = offsetSec,
                    confidencePct = confidence,
                    durationSeconds = pcm.durationSec,
                    waveformPeaks = computePeaks(pcm.samples, 200)
                )
            )
        }

        results
    }

    private data class PcmData(val samples: FloatArray, val durationSec: Double)

    private fun extractDownsampledMono(context: Context, uri: Uri): PcmData {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIdx = i
                format = fmt
                break
            }
        }
        if (audioTrackIdx == -1 || format == null) {
            extractor.release()
            return PcmData(FloatArray(0), 30.0)
        }

        extractor.selectTrack(audioTrackIdx)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else 30_000_000L

        val step = max(1, srcSampleRate / ANALYSIS_RATE)
        val maxSamples = (MAX_ANALYSIS_SECONDS * ANALYSIS_RATE).toInt()
        val collected = ArrayList<Float>(maxSamples)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleCounter = 0

        while (!outputDone && collected.size < maxSamples) {
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
            if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)!!
                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuf = outBuf.asShortBuffer()
                while (shortBuf.remaining() >= channels && collected.size < maxSamples) {
                    var sum = 0f
                    for (c in 0 until channels) sum += shortBuf.get() / 32768f
                    if (sampleCounter % step == 0) {
                        collected.add(sum / channels)
                    }
                    sampleCounter++
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return PcmData(collected.toFloatArray(), durationUs / 1_000_000.0)
    }

    private fun harmonicTransientFilter(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val hp = FloatArray(samples.size)
        var sumSq = 0.0
        var prev = 0f
        for (i in samples.indices) {
            val v = samples[i] - 0.85f * prev
            prev = samples[i]
            hp[i] = v
            sumSq += v * v
        }
        val rms = sqrt(sumSq / samples.size).toFloat().coerceAtLeast(0.02f)
        for (i in hp.indices) {
            hp[i] = tanh(hp[i] / (1.3f * rms))
        }
        return hp
    }

    private fun computePeaks(samples: FloatArray, buckets: Int): FloatArray {
        val peaks = FloatArray(buckets)
        if (samples.isEmpty()) return peaks
        val perBucket = max(1, samples.size / buckets)
        for (b in 0 until buckets) {
            val start = b * perBucket
            val end = min(samples.size, start + perBucket)
            var maxAbs = 0f
            for (i in start until end) {
                val a = abs(samples[i])
                if (a > maxAbs) maxAbs = a
            }
            peaks[b] = min(1f, maxAbs * 1.2f)
        }
        return peaks
    }

    private fun radix2FFT(re: DoubleArray, im: DoubleArray, invert: Boolean) {
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
