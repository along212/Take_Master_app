package com.takemaster.studio

import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.TakeItem
import com.takemaster.studio.timeline.CompTimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompTimelineEngineTest {

    private fun makeTake(
        id: String,
        index: Int,
        durationSec: Double,
        autoOffsetSec: Double,
        manualNudgeMs: Int = 0
    ): TakeItem {
        return TakeItem(
            id = id,
            index = index,
            label = "Take ${index + 1}",
            sourceName = "$id.mp4",
            uriString = "content://test/$id",
            durationSec = durationSec,
            width = 1920,
            height = 1080,
            rotationDegrees = 0,
            hasAudio = true,
            autoOffsetSec = autoOffsetSec,
            manualNudgeMs = manualNudgeMs,
            confidencePct = 99.0,
            waveformPeaks = FloatArray(100) { 0.5f },
            peaksPerSec = 10.0
        )
    }

    @Test
    fun recomputeMasterLayout_normalizesEarliestTakeToZero() {
        // Take 1 starts at rel=0, Take 2 started 3s earlier (+3.0), Take 3 started 2s later (-2.0)
        val raw = listOf(
            makeTake("t1", 0, 60.0, autoOffsetSec = 0.0),
            makeTake("t2", 1, 65.0, autoOffsetSec = 3.0),
            makeTake("t3", 2, 55.0, autoOffsetSec = -2.0)
        )

        val (aligned, masterDuration) = CompTimelineEngine.recomputeMasterLayout(raw)
        assertEquals(3.0, aligned[0].trackStartOnMasterSec, 1e-4)
        assertEquals(0.0, aligned[1].trackStartOnMasterSec, 1e-4)
        assertEquals(5.0, aligned[2].trackStartOnMasterSec, 1e-4)
        assertEquals(65.0, masterDuration, 1e-4) // max(3+60, 0+65, 5+55) = 65.0

        // Verify musical moment M=10.0 maps to identical song moment across takes
        assertEquals(7.0, aligned[0].masterToTakeSec(10.0), 1e-4)
        assertEquals(10.0, aligned[1].masterToTakeSec(10.0), 1e-4)
        assertEquals(5.0, aligned[2].masterToTakeSec(10.0), 1e-4)
    }

    @Test
    fun splitAndPaintRange_slicesAndCoalescesCleanly() {
        val raw = listOf(
            makeTake("t1", 0, 60.0, 0.0),
            makeTake("t2", 1, 60.0, 0.0)
        )
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(raw)
        var segs = CompTimelineEngine.buildInitialSegments(takes)
        assertEquals(1, segs.size)

        segs = CompTimelineEngine.paintRangeToTake(segs, takes, 15.0, 30.0, "t2")
        assertEquals(3, segs.size)
        assertEquals("t1", segs[0].takeId)
        assertEquals(0.0, segs[0].startMasterSec, 1e-4)
        assertEquals(15.0, segs[0].endMasterSec, 1e-4)

        assertEquals("t2", segs[1].takeId)
        assertEquals(15.0, segs[1].startMasterSec, 1e-4)
        assertEquals(30.0, segs[1].endMasterSec, 1e-4)

        assertEquals("t1", segs[2].takeId)
        assertEquals(30.0, segs[2].startMasterSec, 1e-4)
        assertEquals(60.0, segs[2].endMasterSec, 1e-4)

        // Painting t1 back over 15..30 coalesces back to 1 segment
        segs = CompTimelineEngine.paintRangeToTake(segs, takes, 15.0, 30.0, "t1")
        assertEquals(1, segs.size)
        assertEquals("t1", segs[0].takeId)
        assertEquals(0.0, segs[0].startMasterSec, 1e-4)
        assertEquals(60.0, segs[0].endMasterSec, 1e-4)
    }

    @Test
    fun assignSegmentRespectsTakeBounds() {
        val raw = listOf(
            makeTake("t1", 0, 60.0, 0.0),
            makeTake("t2", 1, 40.0, -10.0) // Take 2 starts 10s later (10..50 on master)
        )
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(raw)
        val initial = CompTimelineEngine.buildInitialSegments(takes)

        val assigned = CompTimelineEngine.assignSegmentToTake(initial, takes, initial[0].id, "t2")
        assertEquals(3, assigned.size)
        assertEquals("t1", assigned[0].takeId)
        assertEquals(0.0, assigned[0].startMasterSec, 1e-4)
        assertEquals(10.0, assigned[0].endMasterSec, 1e-4)

        assertEquals("t2", assigned[1].takeId)
        assertEquals(10.0, assigned[1].startMasterSec, 1e-4)
        assertEquals(50.0, assigned[1].endMasterSec, 1e-4)

        assertEquals("t1", assigned[2].takeId)
        assertEquals(50.0, assigned[2].startMasterSec, 1e-4)
        assertEquals(60.0, assigned[2].endMasterSec, 1e-4)
    }

    @Test
    fun moveCutBoundary_clampsToTakeCoverageAndMinSegment() {
        val raw = listOf(
            makeTake("t1", 0, 60.0, 0.0),
            makeTake("t2", 1, 40.0, -10.0) // covers 10..50
        )
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(raw)
        val segs = listOf(
            CompSegment("s1", 0.0, 15.0, "t1"),
            CompSegment("s2", 15.0, 45.0, "t2")
        )

        // Try moving boundary to 5.0 (before t2 starts at 10.0) -> clamps to 10.0
        val movedLeft = CompTimelineEngine.moveCutBoundary(segs, takes, 0, 5.0)
        assertEquals(10.0, movedLeft[0].endMasterSec, 1e-4)
        assertEquals(10.0, movedLeft[1].startMasterSec, 1e-4)

        // Try moving boundary past rightEnd - MIN -> clamps cleanly
        val movedRight = CompTimelineEngine.moveCutBoundary(segs, takes, 0, 50.0)
        assertTrue(movedRight[1].durationSec >= CompTimelineEngine.MIN_SEGMENT_SEC - 1e-4)
    }

    @Test
    fun masterAndCompPositionRoundTrip() {
        val segs = listOf(
            CompSegment("s1", 2.0, 12.0, "t1"),
            CompSegment("s2", 12.0, 27.0, "t2")
        )
        val mapping = CompTimelineEngine.masterTimeToCompPosition(segs, 18.5)
        assertNotNull(mapping)
        assertEquals(1, mapping!!.segmentIndex)
        assertEquals(6500L, mapping.localOffsetMs)

        val roundTrip = CompTimelineEngine.compPositionToMasterTime(segs, mapping.segmentIndex, mapping.localOffsetMs)
        assertEquals(18.5, roundTrip, 1e-3)
    }

    @Test
    fun findQuietValleySec_snapsIntoQuietGapWithinSearchRadius() {
        // 50 peaks/sec -> 20ms per bucket. Put a quiet valley at 10.02s (bucket 501).
        val peaks = FloatArray(1000) { 0.8f }
        peaks[501] = 0.02f
        val take = makeTake("t1", 0, 20.0, 0.0).copy(
            waveformPeaks = peaks,
            peaksPerSec = 50.0
        )
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(listOf(take))

        val snapped = CompTimelineEngine.findQuietValleySec(takes.first(), 10.0, searchRadiusSec = 0.05)
        // Bucket 501 center = (501 + 0.5) / 50.0 = 10.03s
        assertEquals(10.03, snapped, 1e-4)
    }

    @Test
    fun buildAutoSplitSegments_distributesOverlapAcrossAllTakes() {
        val raw = listOf(
            makeTake("t1", 0, 30.0, 0.0),
            makeTake("t2", 1, 32.0, 2.0),   // master 0..32
            makeTake("t3", 2, 28.0, -2.0)   // master 4..32
        )
        // Common overlap is 4.0..32.0 (28.0s) across Take 1 (2..32), Take 2 (0..32), Take 3 (4..32)
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(raw)
        val segs = CompTimelineEngine.buildAutoSplitSegments(takes, snapToValley = false)

        assertEquals(3, segs.size)
        assertEquals("t1", segs[0].takeId)
        assertEquals("t2", segs[1].takeId)
        assertEquals("t3", segs[2].takeId)
        assertEquals(4.0, segs.first().startMasterSec, 1e-4)
        assertEquals(32.0, segs.last().endMasterSec, 1e-4)
        for (i in 0 until segs.size - 1) {
            assertEquals(segs[i].endMasterSec, segs[i + 1].startMasterSec, 1e-4)
        }
    }

    @Test
    fun splitAtAndPaintRange_snapToValleyAvoidsLoudTransient() {
        val peaks = FloatArray(1000) { 0.75f }
        // Quiet valleys near 5.02s (idx 251) and 10.02s (idx 501)
        peaks[251] = 0.03f
        peaks[501] = 0.02f

        val t1 = makeTake("t1", 0, 20.0, 0.0).copy(waveformPeaks = peaks, peaksPerSec = 50.0)
        val t2 = makeTake("t2", 1, 20.0, 0.0).copy(waveformPeaks = peaks, peaksPerSec = 50.0)
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(listOf(t1, t2))

        val initial = CompTimelineEngine.buildInitialSegments(takes)
        val split = CompTimelineEngine.splitAt(initial, rawMasterSec = 10.0, takes = takes, snapToValley = true)
        assertEquals(2, split.size)
        assertEquals(10.03, split[0].endMasterSec, 1e-4)

        val painted = CompTimelineEngine.paintRangeToTake(
            segments = initial,
            takes = takes,
            rawStartSec = 5.0,
            rawEndSec = 10.0,
            takeId = "t2",
            snapToValley = true
        )
        assertEquals(3, painted.size)
        assertEquals(5.03, painted[1].startMasterSec, 1e-4)
        assertEquals(10.03, painted[1].endMasterSec, 1e-4)
    }

    @Test
    fun splitAndSwitchTake_onSpecificTake_switchesRightSideToTappedTake() {
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(
            listOf(
                makeTake("t1", 0, 30.0, 0.0),
                makeTake("t2", 1, 30.0, 0.0),
                makeTake("t3", 2, 30.0, 0.0)
            )
        )
        val initial = CompTimelineEngine.buildInitialSegments(takes)

        val afterTapTake3 = CompTimelineEngine.splitAndSwitchTake(
            segments = initial,
            takes = takes,
            rawMasterSec = 10.0,
            targetTakeId = "t3"
        )
        assertEquals(2, afterTapTake3.size)
        assertEquals("t1", afterTapTake3[0].takeId)
        assertEquals(10.0, afterTapTake3[0].endMasterSec, 1e-4)
        assertEquals("t3", afterTapTake3[1].takeId)
        assertEquals(10.0, afterTapTake3[1].startMasterSec, 1e-4)
        assertEquals(30.0, afterTapTake3[1].endMasterSec, 1e-4)

        val afterTapTake2 = CompTimelineEngine.splitAndSwitchTake(
            segments = afterTapTake3,
            takes = takes,
            rawMasterSec = 20.0,
            targetTakeId = "t2"
        )
        assertEquals(3, afterTapTake2.size)
        assertEquals(listOf("t1", "t3", "t2"), afterTapTake2.map { it.takeId })

        // Tapping the currently active take's lane still creates a split point
        val afterTapSameTake = CompTimelineEngine.splitAndSwitchTake(
            segments = afterTapTake2,
            takes = takes,
            rawMasterSec = 5.0,
            targetTakeId = "t1"
        )
        assertEquals(4, afterTapSameTake.size)
        assertEquals(listOf("t1", "t1", "t3", "t2"), afterTapSameTake.map { it.takeId })
    }

    @Test
    fun splitAndSwitchTake_onMasterTrack_cyclesTwoTakesTake1AndTake2() {
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(
            listOf(
                makeTake("t1", 0, 30.0, 0.0),
                makeTake("t2", 1, 30.0, 0.0)
            )
        )
        val initial = CompTimelineEngine.buildInitialSegments(takes)

        // Take 1 -> Take 2
        val split1 = CompTimelineEngine.splitAndSwitchTake(
            segments = initial,
            takes = takes,
            rawMasterSec = 10.0,
            targetTakeId = null
        )
        assertEquals(listOf("t1", "t2"), split1.map { it.takeId })

        // Take 2 -> Take 1 when only two takes exist
        val split2 = CompTimelineEngine.splitAndSwitchTake(
            segments = split1,
            takes = takes,
            rawMasterSec = 20.0,
            targetTakeId = null
        )
        assertEquals(listOf("t1", "t2", "t1"), split2.map { it.takeId })
    }

    @Test
    fun splitAndSwitchTake_onMasterTrack_cyclesThreeTakesAndRespectsCoverage() {
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(
            listOf(
                makeTake("t1", 0, 30.0, 0.0),
                makeTake("t2", 1, 30.0, 0.0),
                makeTake("t3", 2, 30.0, 0.0)
            )
        )
        val initial = CompTimelineEngine.buildInitialSegments(takes)

        val split1 = CompTimelineEngine.splitAndSwitchTake(initial, takes, 7.0, targetTakeId = null)
        assertEquals(listOf("t1", "t2"), split1.map { it.takeId })

        val split2 = CompTimelineEngine.splitAndSwitchTake(split1, takes, 15.0, targetTakeId = null)
        assertEquals(listOf("t1", "t2", "t3"), split2.map { it.takeId })

        val split3 = CompTimelineEngine.splitAndSwitchTake(split2, takes, 23.0, targetTakeId = null)
        assertEquals(listOf("t1", "t2", "t3", "t1"), split3.map { it.takeId })

        // Partial coverage check: if t2 ends before split point, Master skips t2 -> t3 and lane tap keeps safe fallback
        val (staggeredTakes, _) = CompTimelineEngine.recomputeMasterLayout(
            listOf(
                makeTake("t1", 0, 30.0, 0.0),
                makeTake("t2", 1, 12.0, 0.0),
                makeTake("t3", 2, 30.0, 0.0)
            )
        )
        val staggeredInit = CompTimelineEngine.buildInitialSegments(staggeredTakes)
        val masterSkip = CompTimelineEngine.splitAndSwitchTake(
            staggeredInit, staggeredTakes, 18.0, targetTakeId = null
        )
        assertEquals(listOf("t1", "t3"), masterSkip.map { it.takeId })

        val outOfBoundsTakeTap = CompTimelineEngine.splitAndSwitchTake(
            staggeredInit, staggeredTakes, 18.0, targetTakeId = "t2"
        )
        assertEquals(listOf("t1", "t1"), outOfBoundsTakeTap.map { it.takeId })
    }

    @Test
    fun findSegmentAt_exactCutBoundarySelectsIncomingRightSegment() {
        val (takes, _) = CompTimelineEngine.recomputeMasterLayout(
            listOf(
                makeTake("t1", 0, 30.0, 0.0),
                makeTake("t2", 1, 30.0, 0.0)
            )
        )
        val segs = CompTimelineEngine.splitAndSwitchTake(
            segments = CompTimelineEngine.buildInitialSegments(takes),
            takes = takes,
            rawMasterSec = 10.0,
            targetTakeId = "t2"
        )
        assertEquals("t1", CompTimelineEngine.findSegmentAt(segs, 0.0)?.takeId)
        assertEquals("t1", CompTimelineEngine.findSegmentAt(segs, 9.99)?.takeId)
        assertEquals("t2", CompTimelineEngine.findSegmentAt(segs, 10.0)?.takeId)
        assertEquals("t2", CompTimelineEngine.findSegmentAt(segs, 30.0)?.takeId)
    }
}
