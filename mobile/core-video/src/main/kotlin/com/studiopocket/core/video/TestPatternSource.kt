package com.studiopocket.core.video

import com.studiopocket.model.FrameSink
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus

/**
 * Sumber video pola uji sintetis (GL Color Bars).
 * Mengimplementasikan antarmuka [Source] tanpa alokasi memori CPU.
 */
class TestPatternSource(
    override val name: String = "Pola Uji",
) : Source {
    override val id: String = "test_pattern"
    override var status: SourceStatus = SourceStatus.SIAGA
        private set
    override var statusListener: Source.StatusListener? = null
    override var frameSink: FrameSink? = null

    override fun start() {
        if (status == SourceStatus.AKTIF) return
        status = SourceStatus.AKTIF
        statusListener?.onStatusChanged(this, status)
    }

    override fun stop() {
        if (status == SourceStatus.SIAGA) return
        status = SourceStatus.SIAGA
        statusListener?.onStatusChanged(this, status)
    }
}
