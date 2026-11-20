package com.takemaster.studio

import com.takemaster.studio.audio.CompAudioStitcher
import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.ExportAudioMode
import com.takemaster.studio.model.PlaybackMode
import com.takemaster.studio.model.TakeItem
import com.takemaster.studio.timeline.CompTimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class CompAudioStitcherTest {

    private fun makeSineTake(
        id: String,
        index: Int,
        durationSec: Double,
        autoOffsetSec: Double,
        freqHz: Double = 220.0,
        amp: Double = 0.5
    ): Pair<TakeItem, ShortArray> {
        val sr = CompAudioStitcher.SAMPLE_RATE
        val totalFrames = (durationSec * sr).toInt()
        val interleaved = ShortArray(totalFrames * 2)
        for (i in 0 until totalFrames) {
            val tLocal = i.toDouble() / sr
            val tSong = tLocal - autoOffsetSec
            val v = if (tSong >= 0.0) {
                (amp * sin(2.0 * PI * freqHz * tSong) * 32767.0).toInt().toShort()
            } else 0
            interleaved[i * 2] = v
            interleaved[i * 2 + 1] = v
        }
        val take = TakeItem(
            id = id,
            index = index,
            label = "Take ${index + 1}",
            sourceName = "$id.mp4",
            uriString = "file:///tmp/$id.mp4",
            durationSec = durationSec,
            width = 1280,
            height = 720,
            rotationDegrees = 0,
            hasVideo = true,
            hasAudio = true,
            autoOffsetSec = autoOffsetSec,
            confidencePct = 99.0,
            waveformPeaks = FloatArray(100) { 0.5f },
            peaksPerSec = 50.0
        )
        return take to interleaved
    }

    @Test
    fun renderMixBlock_crossfadesAcrossCutWithZeroSilenceDip() {
        val sr = CompAudioStitcher.SAMPLE_RATE
        // Take 1 starts at 0.0s; Take 2 started 0.5s earlier (+0.5s) -> aligned on Master Timeline
        val (rawT1, pcm1) = makeSineTake("t1", 0, durationSec = 4.0, autoOffsetSec = 0.0)
        val (rawT2, pcm2) = makeSineTake("t2", 1, durationSec = 4.5, autoOffsetSec = 0.5)
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(listOf(rawT1, rawT2))

        val sources = mapOf(
            "t1" to CompAudioStitcher.MemoryPcmSource(pcm1),
            "t2" to CompAudioStitcher.MemoryPcmSource(pcm2)
        )

        val segs = listOf(
            CompSegment("s1", 0.5, 2.0, "t1"),
            CompSegment("s2", 2.0, 3.5, "t2")
        )
        val frameMap = CompAudioStitcher.buildFrameMap(segs, takes, sr)
        val totalFrames = CompAudioStitcher.totalOutputFrames(frameMap).toInt()
        val out = ShortArray(totalFrames * 2)

        CompAudioStitcher.renderMixBlock(
            outInterleaved = out,
            frameCount = totalFrames,
            outputFrameStart = 0L,
            compareMasterFrameStart = 0L,
            takes = takes,
            frameMap = frameMap,
            sources = sources,
            playbackMode = PlaybackMode.MASTER_COMP,
            audioMode = ExportAudioMode.FOLLOW_ACTIVE_TAKE,
            lockedTakeId = null,
            auditionTakeId = null,
            crossfadeMs = 20,
            sampleRate = sr
        )

        // Check windowed RMS right across the cut boundary (frameMap[1].outputStartFrame = 1.5s = 72000 frames)
        val cutFrame = frameMap[1].outputStartFrame.toInt()
        val windowFrames = 240 // 5ms windows across +/- 25ms of cut
        for (offset in -1200..1200 step windowFrames) {
            val start = (cutFrame + offset).coerceIn(0, totalFrames - windowFrames)
            var sq = 0.0
            for (f in start until start + windowFrames) {
                val sample = out[f * 2] / 32768.0
                sq += sample * sample
            }
            val rms = sqrt(sq / windowFrames)
            // Expected RMS of 0.5 amplitude sine = 0.5 / sqrt(2) ~= 0.353; must never dip to silence!
            assertTrue("RMS dropped across cut at offset $offset: $rms", rms > 0.30)
        }
    }

    @Test
    fun renderMixBlock_compareModeHonorsSoloAndMuteInSync() {
        val sr = CompAudioStitcher.SAMPLE_RATE
        val (rawT1, pcm1) = makeSineTake("t1", 0, durationSec = 3.0, autoOffsetSec = 0.0, amp = 0.4)
        val (rawT2, pcm2) = makeSineTake("t2", 1, durationSec = 3.0, autoOffsetSec = 0.0, amp = 0.8)
        val (baseTakes, _) = CompTimelineEngine.recomputeMasterLayout(listOf(rawT1.copy(isMuted = true), rawT2.copy(isSolo = true)))

        val sources = mapOf(
            "t1" to CompAudioStitcher.MemoryPcmSource(pcm1),
            "t2" to CompAudioStitcher.MemoryPcmSource(pcm2)
        )
        val frameMap = CompAudioStitcher.buildFrameMap(CompTimelineEngine.buildInitialSegments(baseTakes), baseTakes, sr)

        val frames = 2048
        val out = ShortArray(frames * 2)
        CompAudioStitcher.renderMixBlock(
            outInterleaved = out,
            frameCount = frames,
            outputFrameStart = 0L,
            compareMasterFrameStart = 4800L, // start at 0.1s
            takes = baseTakes,
            frameMap = frameMap,
            sources = sources,
            playbackMode = PlaybackMode.COMPARE_TAKES,
            audioMode = ExportAudioMode.FOLLOW_ACTIVE_TAKE,
            lockedTakeId = null,
            auditionTakeId = null,
            crossfadeMs = 12,
            sampleRate = sr
        )

        // Only t2 (solo, amp 0.8) should play cleanly with exact phase match
        var maxDiff = 0
        for (f in 0 until frames) {
            val expected = pcm2[(4800 + f) * 2].toInt()
            val actual = out[f * 2].toInt()
            maxDiff = maxOf(maxDiff, abs(expected - actual))
        }
        assertTrue("Expected soloed t2 signal directly, maxDiff=$maxDiff", maxDiff <= 2)
    }

    @Test
    fun writeExportWav_createsValidRiffWavAndExactDuration() {
        val sr = CompAudioStitcher.SAMPLE_RATE
        val (rawT1, pcm1) = makeSineTake("t1", 0, durationSec = 2.0, autoOffsetSec = 0.0)
        val (rawT2, pcm2) = makeSineTake("t2", 1, durationSec = 2.0, autoOffsetSec = 0.0)
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(listOf(rawT1, rawT2))
        val sources = mapOf(
            "t1" to CompAudioStitcher.MemoryPcmSource(pcm1),
            "t2" to CompAudioStitcher.MemoryPcmSource(pcm2)
        )
        val segs = listOf(
            CompSegment("s1", 0.0, 1.0, "t1"),
            CompSegment("s2", 1.0, 2.0, "t2")
        )
        val frameMap = CompAudioStitcher.buildFrameMap(segs, takes, sr)
        val tmpWav = File.createTempFile("comp_export_test_", ".wav")
        try {
            CompAudioStitcher.writeExportWav(
                outWavFile = tmpWav,
                takes = takes,
                frameMap = frameMap,
                sources = sources,
                audioMode = ExportAudioMode.FOLLOW_ACTIVE_TAKE,
                lockedTakeId = null,
                crossfadeMs = 12,
                sampleRate = sr
            )
            val bytes = tmpWav.readBytes()
            val expectedFrames = CompAudioStitcher.totalOutputFrames(frameMap).toInt()
            assertEquals(44 + expectedFrames * 4, bytes.size)
            assertEquals("RIFF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes.copyOfRange(8, 12), Charsets.US_ASCII))
            val bb = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(expectedFrames * 4, bb.int)
        } finally {
            tmpWav.delete()
        }
    }
}
