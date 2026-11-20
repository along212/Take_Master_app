package com.takemaster.studio.timeline

import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.SegmentMapping
import com.takemaster.studio.model.TakeItem
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure timeline state engine for Take Master.
 *
 * Coordinate convention:
 * - Each take's alignment offset is stored relative to Take 1:
 *     effectiveOffsetSec = targetTime - refTime
 *   (so positive offset means the take started recording earlier than Take 1).
 * - On the unified Master Timeline (starting at 0.0s for the earliest take):
 *     trackStartOnMasterSec_i = (-effectiveOffsetSec_i) - min_j(-effectiveOffsetSec_j)
 *   so at any Master time M in [trackStart, trackEnd],
 *     takeLocalTime = M - trackStartOnMasterSec_i
 *   represents the exact same musical moment across all takes.
 */
object CompTimelineEngine {

    const val MIN_SEGMENT_SEC = 0.15
    private const val EPS = 1e-4

    fun newSegmentId(): String = UUID.randomUUID().toString()

    fun recomputeMasterLayout(takes: List<TakeItem>): Pair<List<TakeItem>, Double> {
        if (takes.isEmpty()) return emptyList<TakeItem>() to 0.0
        val minRelStart = takes.minOf { -it.effectiveOffsetSec }
        val updated = takes.map { take ->
            val start = ((-take.effectiveOffsetSec) - minRelStart).coerceAtLeast(0.0)
            take.copy(trackStartOnMasterSec = start)
        }
        val masterDuration = updated.maxOf { it.trackEndOnMasterSec }.coerceAtLeast(0.1)
        return updated to masterDuration
    }

    /**
     * Creates an initial single-segment Master Comp covering Take 1.
     */
    fun buildInitialSegments(takes: List<TakeItem>): List<CompSegment> {
        if (takes.isEmpty()) return emptyList()
        val first = takes.first()
        val start = first.trackStartOnMasterSec
        val end = first.trackEndOnMasterSec
        if (end - start < MIN_SEGMENT_SEC) return emptyList()
        return listOf(
            CompSegment(
                id = newSegmentId(),
                startMasterSec = start,
                endMasterSec = end,
                takeId = first.id
            )
        )
    }

    /**
     * Splits the common overlapping span evenly across all takes so the user can
     * immediately preview cuts across Take 1, Take 2, Take 3...
     */
    fun buildAutoSplitSegments(
        takes: List<TakeItem>,
        snapToValley: Boolean = true
    ): List<CompSegment> {
        if (takes.isEmpty()) return emptyList()
        if (takes.size == 1) return buildInitialSegments(takes)

        val overlapStart = takes.maxOf { it.trackStartOnMasterSec }
        val overlapEnd = takes.minOf { it.trackEndOnMasterSec }
        if (overlapEnd - overlapStart < MIN_SEGMENT_SEC * takes.size) {
            return buildInitialSegments(takes)
        }

        val slice = (overlapEnd - overlapStart) / takes.size.toDouble()
        val boundaries = mutableListOf(overlapStart)
        for (i in 1 until takes.size) {
            val raw = overlapStart + i * slice
            val cut = if (snapToValley) {
                findQuietValleySec(takes.first(), raw).coerceIn(
                    boundaries.last() + MIN_SEGMENT_SEC,
                    overlapEnd - MIN_SEGMENT_SEC * (takes.size - i)
                )
            } else raw
            boundaries.add(cut)
        }
        boundaries.add(overlapEnd)

        return (0 until takes.size).map { i ->
            CompSegment(
                id = newSegmentId(),
                startMasterSec = boundaries[i],
                endMasterSec = boundaries[i + 1],
                takeId = takes[i].id
            )
        }
    }

    /**
     * Searches +/- [searchRadiusSec] around [rawMasterSec] for the quietest waveform valley
     * so cuts don't slice mid-transient (e.g., snare hit or vocal syllable).
     */
    fun findQuietValleySec(
        take: TakeItem?,
        rawMasterSec: Double,
        searchRadiusSec: Double = 0.045
    ): Double {
        if (take == null || take.waveformPeaks.isEmpty() || take.peaksPerSec <= 0.0) {
            return rawMasterSec
        }
        val localSec = take.masterToTakeSec(rawMasterSec)
        if (localSec <= 0.0 || localSec >= take.durationSec) return rawMasterSec

        val peaks = take.waveformPeaks
        val centerIdx = floor(localSec * take.peaksPerSec).toInt().coerceIn(0, peaks.lastIndex)
        val radiusBuckets = (searchRadiusSec * take.peaksPerSec).toInt().coerceAtLeast(1)
        val minIdx = (centerIdx - radiusBuckets).coerceAtLeast(0)
        val maxIdx = (centerIdx + radiusBuckets).coerceAtMost(peaks.lastIndex)

        var bestIdx = centerIdx
        var minPeak = Float.MAX_VALUE
        for (i in minIdx..maxIdx) {
            if (peaks[i] < minPeak) {
                minPeak = peaks[i]
                bestIdx = i
            }
        }
        val snappedLocal = (bestIdx + 0.5) / take.peaksPerSec
        return (take.trackStartOnMasterSec + snappedLocal).coerceIn(
            take.trackStartOnMasterSec,
            take.trackEndOnMasterSec
        )
    }

    /**
     * Repairs segments after a manual sync nudge or take removal so every segment stays
     * strictly inside its assigned take's [trackStartOnMasterSec, trackEndOnMasterSec].
     */
    fun repairSegmentsForTakes(
        segments: List<CompSegment>,
        takes: List<TakeItem>
    ): List<CompSegment> {
        if (takes.isEmpty()) return emptyList()
        val takeMap = takes.associateBy { it.id }
        val fallback = takes.first()

        val repaired = mutableListOf<CompSegment>()
        for (seg in segments) {
            val take = takeMap[seg.takeId] ?: fallback
            val s = max(seg.startMasterSec, take.trackStartOnMasterSec)
            val e = min(seg.endMasterSec, take.trackEndOnMasterSec)
            if (e - s >= MIN_SEGMENT_SEC) {
                repaired.add(seg.copy(startMasterSec = s, endMasterSec = e, takeId = take.id))
            }
        }
        if (repaired.isEmpty()) {
            return buildInitialSegments(takes)
        }
        return normalizeSegments(repaired)
    }

    /**
     * Splits the segment containing [rawMasterSec] into two segments.
     * Optionally snaps the cut point to the quietest transient valley.
     */
    fun splitAt(
        segments: List<CompSegment>,
        rawMasterSec: Double,
        takes: List<TakeItem> = emptyList(),
        snapToValley: Boolean = false
    ): List<CompSegment> =
        splitInternal(segments, rawMasterSec, takes, snapToValley)?.first ?: segments

    /**
     * Splits at [rawMasterSec] and assigns the right-side segment from the cut onward:
     * - When [targetTakeId] is non-null (tap on a specific take lane), switches the right side to that take.
     * - When [targetTakeId] is null (tap on the Master Comp track), cycles to the next covering take
     *   in [takes] (`Take 1 -> Take 2`, `Take 2 -> Take 3`, or `Take 2 -> Take 1` when there are two takes).
     */
    fun splitAndSwitchTake(
        segments: List<CompSegment>,
        takes: List<TakeItem>,
        rawMasterSec: Double,
        targetTakeId: String? = null,
        snapToValley: Boolean = false
    ): List<CompSegment> {
        val (splitList, rightIdx) = splitInternal(
            segments = segments,
            rawMasterSec = rawMasterSec,
            takes = takes,
            snapToValley = snapToValley
        ) ?: return segments

        val right = splitList[rightIdx]
        val nextTakeId = resolveSplitTargetTakeId(takes, right, targetTakeId)
            ?: return splitList
        if (nextTakeId == right.takeId) return splitList

        return assignSegmentToTake(splitList, takes, right.id, nextTakeId)
    }

    private fun splitInternal(
        segments: List<CompSegment>,
        rawMasterSec: Double,
        takes: List<TakeItem>,
        snapToValley: Boolean
    ): Pair<List<CompSegment>, Int>? {
        val preIdx = segments.indexOfFirst {
            rawMasterSec > it.startMasterSec + MIN_SEGMENT_SEC &&
                rawMasterSec < it.endMasterSec - MIN_SEGMENT_SEC
        }
        if (preIdx == -1) return null

        val target = segments[preIdx]
        val cutSec = if (snapToValley) {
            val take = takes.find { it.id == target.takeId }
            findQuietValleySec(take, rawMasterSec).coerceIn(
                target.startMasterSec + MIN_SEGMENT_SEC,
                target.endMasterSec - MIN_SEGMENT_SEC
            )
        } else {
            rawMasterSec
        }

        val left = target.copy(endMasterSec = cutSec)
        val right = CompSegment(
            id = newSegmentId(),
            startMasterSec = cutSec,
            endMasterSec = target.endMasterSec,
            takeId = target.takeId
        )

        val result = segments.toMutableList()
        result.removeAt(preIdx)
        result.add(preIdx, left)
        result.add(preIdx + 1, right)
        return result to (preIdx + 1)
    }

    private fun resolveSplitTargetTakeId(
        takes: List<TakeItem>,
        rightSegment: CompSegment,
        targetTakeId: String?
    ): String? {
        if (takes.isEmpty()) return null
        if (targetTakeId != null) {
            val chosen = takes.find { it.id == targetTakeId } ?: return null
            return if (coversSegmentStart(chosen, rightSegment)) chosen.id else null
        }

        if (takes.size <= 1) return null
        val curIdx = takes.indexOfFirst { it.id == rightSegment.takeId }
        if (curIdx == -1) {
            return takes.firstOrNull { coversSegmentStart(it, rightSegment) }?.id
        }
        for (step in 1 until takes.size) {
            val candidate = takes[(curIdx + step) % takes.size]
            if (coversSegmentStart(candidate, rightSegment)) {
                return candidate.id
            }
        }
        return null
    }

    private fun coversSegmentStart(take: TakeItem, segment: CompSegment): Boolean {
        val coveredEnd = min(segment.endMasterSec, take.trackEndOnMasterSec)
        return take.trackStartOnMasterSec - EPS <= segment.startMasterSec &&
            coveredEnd - segment.startMasterSec >= MIN_SEGMENT_SEC
    }

    fun assignSegmentToTake(
        segments: List<CompSegment>,
        takes: List<TakeItem>,
        segmentId: String,
        takeId: String
    ): List<CompSegment> {
        val take = takes.find { it.id == takeId } ?: return segments
        val idx = segments.indexOfFirst { it.id == segmentId }
        if (idx == -1) return segments
        val seg = segments[idx]

        val overlapStart = max(seg.startMasterSec, take.trackStartOnMasterSec)
        val overlapEnd = min(seg.endMasterSec, take.trackEndOnMasterSec)
        if (overlapEnd - overlapStart < MIN_SEGMENT_SEC) return segments

        val replacement = mutableListOf<CompSegment>()
        if (overlapStart - seg.startMasterSec >= MIN_SEGMENT_SEC) {
            replacement.add(
                CompSegment(
                    id = newSegmentId(),
                    startMasterSec = seg.startMasterSec,
                    endMasterSec = overlapStart,
                    takeId = seg.takeId
                )
            )
        }
        replacement.add(
            seg.copy(
                startMasterSec = overlapStart,
                endMasterSec = overlapEnd,
                takeId = takeId
            )
        )
        if (seg.endMasterSec - overlapEnd >= MIN_SEGMENT_SEC) {
            replacement.add(
                CompSegment(
                    id = newSegmentId(),
                    startMasterSec = overlapEnd,
                    endMasterSec = seg.endMasterSec,
                    takeId = seg.takeId
                )
            )
        }

        val out = segments.toMutableList()
        out.removeAt(idx)
        out.addAll(idx, replacement)
        return normalizeSegments(out)
    }

    /**
     * Swipe-to-Comp: assigns [rawStartSec, rawEndSec] on the Master Timeline to [takeId],
     * optionally snapping boundaries to quiet transient valleys.
     */
    fun paintRangeToTake(
        segments: List<CompSegment>,
        takes: List<TakeItem>,
        rawStartSec: Double,
        rawEndSec: Double,
        takeId: String,
        snapToValley: Boolean = false
    ): List<CompSegment> {
        val take = takes.find { it.id == takeId } ?: return segments
        val reqStart = min(rawStartSec, rawEndSec)
        val reqEnd = max(rawStartSec, rawEndSec)

        var paintStart = max(reqStart, take.trackStartOnMasterSec)
        var paintEnd = min(reqEnd, take.trackEndOnMasterSec)
        if (snapToValley && paintEnd - paintStart >= MIN_SEGMENT_SEC * 2) {
            if (paintStart > take.trackStartOnMasterSec + 0.1) {
                paintStart = findQuietValleySec(take, paintStart)
            }
            if (paintEnd < take.trackEndOnMasterSec - 0.1) {
                paintEnd = findQuietValleySec(take, paintEnd)
            }
        }
        if (paintEnd - paintStart < MIN_SEGMENT_SEC) return segments

        if (segments.isEmpty()) {
            return listOf(CompSegment(newSegmentId(), paintStart, paintEnd, takeId))
        }

        val next = mutableListOf<CompSegment>()
        for (seg in segments) {
            when {
                seg.endMasterSec <= paintStart + EPS || seg.startMasterSec >= paintEnd - EPS -> {
                    next.add(seg)
                }
                else -> {
                    if (paintStart - seg.startMasterSec >= MIN_SEGMENT_SEC) {
                        next.add(
                            CompSegment(
                                id = newSegmentId(),
                                startMasterSec = seg.startMasterSec,
                                endMasterSec = paintStart,
                                takeId = seg.takeId
                            )
                        )
                    }
                    if (seg.endMasterSec - paintEnd >= MIN_SEGMENT_SEC) {
                        next.add(
                            CompSegment(
                                id = newSegmentId(),
                                startMasterSec = paintEnd,
                                endMasterSec = seg.endMasterSec,
                                takeId = seg.takeId
                            )
                        )
                    }
                }
            }
        }

        next.add(
            CompSegment(
                id = newSegmentId(),
                startMasterSec = paintStart,
                endMasterSec = paintEnd,
                takeId = takeId
            )
        )

        return sortAndCoalesceSameTake(next)
    }

    fun moveCutBoundary(
        segments: List<CompSegment>,
        takes: List<TakeItem>,
        leftIndex: Int,
        desiredCutSec: Double
    ): List<CompSegment> {
        if (leftIndex < 0 || leftIndex >= segments.size - 1) return segments
        val left = segments[leftIndex]
        val right = segments[leftIndex + 1]
        if (abs(left.endMasterSec - right.startMasterSec) > 0.05) return segments

        val takeMap = takes.associateBy { it.id }
        val leftTake = takeMap[left.takeId] ?: return segments
        val rightTake = takeMap[right.takeId] ?: return segments

        val minCut = maxOf(
            left.startMasterSec + MIN_SEGMENT_SEC,
            rightTake.trackStartOnMasterSec
        )
        val maxCut = minOf(
            right.endMasterSec - MIN_SEGMENT_SEC,
            leftTake.trackEndOnMasterSec
        )
        if (minCut > maxCut) return segments

        val clamped = desiredCutSec.coerceIn(minCut, maxCut)
        val out = segments.toMutableList()
        out[leftIndex] = left.copy(endMasterSec = clamped)
        out[leftIndex + 1] = right.copy(startMasterSec = clamped)
        return out
    }

    fun trimOuterStart(
        segments: List<CompSegment>,
        takes: List<TakeItem>,
        desiredStartSec: Double
    ): List<CompSegment> {
        if (segments.isEmpty()) return segments
        val first = segments.first()
        val take = takes.find { it.id == first.takeId } ?: return segments
        val minStart = take.trackStartOnMasterSec
        val maxStart = first.endMasterSec - MIN_SEGMENT_SEC
        if (minStart > maxStart) return segments
        val clamped = desiredStartSec.coerceIn(minStart, maxStart)
        val out = segments.toMutableList()
        out[0] = first.copy(startMasterSec = clamped)
        return out
    }

    fun trimOuterEnd(
        segments: List<CompSegment>,
        takes: List<TakeItem>,
        desiredEndSec: Double
    ): List<CompSegment> {
        if (segments.isEmpty()) return segments
        val lastIdx = segments.lastIndex
        val last = segments[lastIdx]
        val take = takes.find { it.id == last.takeId } ?: return segments
        val minEnd = last.startMasterSec + MIN_SEGMENT_SEC
        val maxEnd = take.trackEndOnMasterSec
        if (minEnd > maxEnd) return segments
        val clamped = desiredEndSec.coerceIn(minEnd, maxEnd)
        val out = segments.toMutableList()
        out[lastIdx] = last.copy(endMasterSec = clamped)
        return out
    }

    fun mergeSegmentWithPrevious(
        segments: List<CompSegment>,
        takes: List<TakeItem>,
        segmentId: String
    ): List<CompSegment> {
        val idx = segments.indexOfFirst { it.id == segmentId }
        if (idx <= 0) return segments
        val prev = segments[idx - 1]
        val curr = segments[idx]
        val prevTake = takes.find { it.id == prev.takeId } ?: return segments
        val merged = when {
            prevTake.trackEndOnMasterSec + EPS >= curr.endMasterSec ->
                prev.copy(endMasterSec = curr.endMasterSec)
            else -> {
                val currTake = takes.find { it.id == curr.takeId } ?: return segments
                if (currTake.trackStartOnMasterSec - EPS <= prev.startMasterSec) {
                    curr.copy(startMasterSec = prev.startMasterSec)
                } else {
                    return segments
                }
            }
        }
        val out = segments.toMutableList()
        out[idx - 1] = merged
        out.removeAt(idx)
        return out
    }

    fun deleteSegment(segments: List<CompSegment>, segmentId: String): List<CompSegment> {
        if (segments.size <= 1) return segments
        val idx = segments.indexOfFirst { it.id == segmentId }
        if (idx == -1) return segments
        val target = segments[idx]
        val out = segments.toMutableList()
        out.removeAt(idx)
        if (idx > 0 && idx <= out.size - 1) {
            out[idx - 1] = out[idx - 1].copy(endMasterSec = target.endMasterSec)
        }
        return normalizeSegments(out)
    }

    fun findSegmentAt(segments: List<CompSegment>, masterSec: Double): CompSegment? {
        if (segments.isEmpty()) return null
        val halfOpenIdx = segments.indices.firstOrNull { idx ->
            val seg = segments[idx]
            val afterStart = masterSec >= seg.startMasterSec - EPS
            val beforeEnd = if (idx == segments.lastIndex) {
                masterSec <= seg.endMasterSec + EPS
            } else {
                masterSec < seg.endMasterSec - EPS
            }
            afterStart && beforeEnd
        }
        if (halfOpenIdx != null) return segments[halfOpenIdx]
        return segments.firstOrNull { masterSec >= it.startMasterSec - EPS && masterSec <= it.endMasterSec + EPS }
    }

    fun masterTimeToCompPosition(
        segments: List<CompSegment>,
        masterSec: Double
    ): SegmentMapping? {
        if (segments.isEmpty()) return null
        val clamped = masterSec.coerceIn(segments.first().startMasterSec, segments.last().endMasterSec)
        for (i in segments.indices) {
            val seg = segments[i]
            if (clamped <= seg.endMasterSec + EPS || i == segments.lastIndex) {
                val offsetMs = ((clamped - seg.startMasterSec).coerceIn(0.0, seg.durationSec) * 1000.0).toLong()
                return SegmentMapping(i, seg, offsetMs)
            }
        }
        return null
    }

    fun compPositionToMasterTime(
        segments: List<CompSegment>,
        segmentIndex: Int,
        localOffsetMs: Long
    ): Double {
        val seg = segments.getOrNull(segmentIndex) ?: return 0.0
        return (seg.startMasterSec + (localOffsetMs / 1000.0)).coerceIn(seg.startMasterSec, seg.endMasterSec)
    }

    private fun normalizeSegments(segments: List<CompSegment>): List<CompSegment> {
        val sorted = segments.filter { it.durationSec >= MIN_SEGMENT_SEC }
            .sortedBy { it.startMasterSec }
        if (sorted.isEmpty()) return emptyList()
        val result = mutableListOf(sorted.first())
        for (i in 1 until sorted.size) {
            val prev = result.last()
            val curr = sorted[i]
            val start = max(prev.endMasterSec, curr.startMasterSec)
            if (curr.endMasterSec - start >= MIN_SEGMENT_SEC) {
                result.add(curr.copy(startMasterSec = start))
            }
        }
        return result
    }

    private fun sortAndCoalesceSameTake(segments: List<CompSegment>): List<CompSegment> {
        val normalized = normalizeSegments(segments)
        if (normalized.size <= 1) return normalized
        val coalesced = mutableListOf(normalized.first())
        for (i in 1 until normalized.size) {
            val prev = coalesced.last()
            val curr = normalized[i]
            if (prev.takeId == curr.takeId && abs(prev.endMasterSec - curr.startMasterSec) < 0.02) {
                coalesced[coalesced.lastIndex] = prev.copy(endMasterSec = curr.endMasterSec)
            } else {
                coalesced.add(curr)
            }
        }
        return coalesced
    }
}
