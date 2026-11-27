package com.studiopocket.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4SegmentMergerTest {

    @Test
    fun testMonotonicPtsAcrossSegments() {
        // Segmen 1 dimulai di rawPts = 100_000 us
        val seg1First = 100_000L
        val pts1 = Mp4SegmentMerger.computeMonotonicPts(100_000L, seg1First, 0L, -1L)
        val pts2 = Mp4SegmentMerger.computeMonotonicPts(133_333L, seg1First, 0L, pts1)
        assertEquals(0L, pts1)
        assertEquals(33_333L, pts2)

        // Segmen 2 dimulai di rawPts = 30_000_000 us, base baru = pts2 + SEGMENT_GAP_US
        val nextBase = pts2 + Mp4SegmentMerger.SEGMENT_GAP_US
        val pts3 = Mp4SegmentMerger.computeMonotonicPts(30_000_000L, 30_000_000L, nextBase, pts2)
        val pts4 = Mp4SegmentMerger.computeMonotonicPts(30_033_333L, 30_000_000L, nextBase, pts3)
        assertTrue(pts3 > pts2)
        assertTrue(pts4 > pts3)
    }

    @Test
    fun testOutOfOrderPtsClampedMonotonically() {
        val last = 500_000L
        val clamped = Mp4SegmentMerger.computeMonotonicPts(10_000L, 10_000L, 400_000L, last)
        assertEquals(last + 1_000L, clamped)
    }
}
