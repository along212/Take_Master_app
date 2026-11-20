package com.takemaster.studio.export

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sample-accurate micro-fade processor applied at segment boundaries so concatenated audio
 * transitions smoothly without clicks or zero-crossing pops.
 */
@OptIn(UnstableApi::class)
class EdgeFadeAudioProcessor(
    private val fadeInMs: Int,
    private val fadeOutMs: Int,
    private val segmentDurationUs: Long
) : BaseAudioProcessor() {

    private var fadeInFrames = 0L
    private var fadeOutFrames = 0L
    private var totalEstimatedFrames = 0L
    private var processedFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val sr = inputAudioFormat.sampleRate.toDouble()
        fadeInFrames = if (fadeInMs > 0) ((sr * fadeInMs) / 1000.0).roundToLong().coerceAtLeast(1L) else 0L
        fadeOutFrames = if (fadeOutMs > 0) ((sr * fadeOutMs) / 1000.0).roundToLong().coerceAtLeast(1L) else 0L
        totalEstimatedFrames = ((sr * segmentDurationUs) / 1_000_000.0).roundToLong().coerceAtLeast(1L)
        processedFrames = 0L
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        return super.isActive() && (fadeInMs > 0 || fadeOutMs > 0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        if (bytesPerFrame <= 0) return
        val frameCount = remaining / bytesPerFrame
        val channels = inputAudioFormat.channelCount

        val out = replaceOutputBuffer(remaining)
        val fadeOutStartFrame = (totalEstimatedFrames - fadeOutFrames).coerceAtLeast(fadeInFrames)

        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            for (f in 0 until frameCount) {
                val frameIdx = processedFrames + f
                val gain = gainForFrame(frameIdx, fadeOutStartFrame)
                for (c in 0 until channels) {
                    val sample = inputBuffer.getFloat()
                    out.putFloat(sample * gain)
                }
            }
        } else {
            for (f in 0 until frameCount) {
                val frameIdx = processedFrames + f
                val gain = gainForFrame(frameIdx, fadeOutStartFrame)
                for (c in 0 until channels) {
                    val sample = inputBuffer.getShort()
                    if (gain >= 0.9999f) {
                        out.putShort(sample)
                    } else {
                        val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        out.putShort(scaled.toShort())
                    }
                }
            }
        }

        processedFrames += frameCount
        out.flip()
    }

    private fun gainForFrame(frameIdx: Long, fadeOutStartFrame: Long): Float {
        var gain = 1.0f
        if (fadeInFrames > 0L && frameIdx < fadeInFrames) {
            val t = (frameIdx.toDouble() / fadeInFrames.toDouble()).coerceIn(0.0, 1.0)
            gain = sin(0.5 * PI * t).toFloat()
        }
        if (fadeOutFrames > 0L && frameIdx >= fadeOutStartFrame) {
            val t = ((frameIdx - fadeOutStartFrame).toDouble() / fadeOutFrames.toDouble()).coerceIn(0.0, 1.0)
            val outGain = cos(0.5 * PI * t).toFloat()
            gain = minOf(gain, outGain)
        }
        return gain
    }

    override fun onFlush() {
        processedFrames = 0L
    }

    override fun onReset() {
        processedFrames = 0L
        fadeInFrames = 0L
        fadeOutFrames = 0L
        totalEstimatedFrames = 0L
    }
}
