package com.takemaster.studio

import com.takemaster.studio.audio.AudioAlignmentMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioAlignmentMathTest {

    private fun songSampleAt(tSong: Double, transientAtSec: Double?): Float {
        if (tSong < 0.0) return 0f
        // Non-periodic chord & tempo progression simulating a real performance backing track
        val baseFreq = 110.0 + 35.0 * sin(2.0 * PI * 0.07 * tSong) + 18.0 * sin(2.0 * PI * 0.19 * tSong)
        val env = 0.55 + 0.40 * sin(2.0 * PI * 0.31 * tSong + 0.3 * tSong)
        val signal = env * (
            0.40 * sin(2.0 * PI * baseFreq * tSong) +
                0.30 * sin(2.0 * PI * (baseFreq * 1.498) * tSong) +
                0.25 * sin(2.0 * PI * (baseFreq * 2.0 + 12.0 * sin(tSong)) * tSong)
            )
        var s = signal.toFloat()
        if (transientAtSec != null && tSong >= transientAtSec && tSong < transientAtSec + 0.04) {
            val k = (tSong - transientAtSec) / 0.04
            s += (1.8 * (1.0 - k)).toFloat()
        }
        return s
    }

    private fun synthesizeShifted(
        durationSec: Double,
        shiftSec: Double,
        sampleRate: Int,
        transientAtSec: Double? = null
    ): FloatArray {
        val n = (durationSec * sampleRate).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val tSong = i.toDouble() / sampleRate - shiftSec
            out[i] = songSampleAt(tSong, transientAtSec)
        }
        return out
    }

    @Test
    fun estimateOffset_recoversPositiveSubMillisecondOffsetDespiteTransients() {
        val trueOffset = 2.3474
        val ref2k = synthesizeShifted(24.0, 0.0, AudioAlignmentMath.COARSE_RATE, transientAtSec = 6.0)
        val ref16k = synthesizeShifted(24.0, 0.0, AudioAlignmentMath.FINE_RATE, transientAtSec = 6.0)

        val tgt2k = synthesizeShifted(26.0, trueOffset, AudioAlignmentMath.COARSE_RATE, transientAtSec = 11.2)
        val tgt16k = synthesizeShifted(26.0, trueOffset, AudioAlignmentMath.FINE_RATE, transientAtSec = 11.2)

        val est = AudioAlignmentMath.estimateOffset(ref2k, tgt2k, ref16k, tgt16k)
        assertEquals(trueOffset, est.offsetSeconds, 0.0008)
        assertTrue(est.confidencePct >= 80.0)
    }

    @Test
    fun estimateOffset_recoversNegativeOffsetAccurately() {
        val trueOffset = -1.7823
        val ref2k = synthesizeShifted(24.0, 0.0, AudioAlignmentMath.COARSE_RATE)
        val ref16k = synthesizeShifted(24.0, 0.0, AudioAlignmentMath.FINE_RATE)

        val tgt2k = synthesizeShifted(24.0, trueOffset, AudioAlignmentMath.COARSE_RATE)
        val tgt16k = synthesizeShifted(24.0, trueOffset, AudioAlignmentMath.FINE_RATE)

        val est = AudioAlignmentMath.estimateOffset(ref2k, tgt2k, ref16k, tgt16k)
        assertEquals(trueOffset, est.offsetSeconds, 0.0008)
        assertTrue(est.confidencePct >= 80.0)
    }
}
