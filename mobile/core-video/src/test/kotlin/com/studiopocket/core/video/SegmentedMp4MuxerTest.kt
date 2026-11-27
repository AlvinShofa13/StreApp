package com.studiopocket.core.video

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentedMp4MuxerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSegmentNaming() {
        val folder = tempFolder.newFolder()
        val muxer = SegmentedMp4Muxer(
            baseDir = folder,
            baseName = "StreApp_Test",
            segmentIntervalUs = 10_000_000L,
        )

        val files = muxer.stop()
        // Sebelum dimulainya track, completedFiles masih kosong
        assertEquals(0, files.size)
    }
}
