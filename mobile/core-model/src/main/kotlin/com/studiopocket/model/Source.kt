package com.studiopocket.model

/** Status siklus hidup sumber. */
enum class SourceStatus { SIAGA, AKTIF, TERPUTUS, GALAT }

/**
 * Tujuan frame dari sumber, dipasang oleh compositor.
 * Implementasi nyata (SurfaceTexture/Surface) ada di core-video, sehingga core-model tetap Kotlin murni.
 */
interface FrameSink

/**
 * Antarmuka yang diimplementasikan semua sumber.
 *
 * Frame tidak disalin ke CPU: sumber menulis langsung ke [frameSink] milik compositor.
 * Antarmuka ini mengurus siklus hidup, status, dan penyediaan frame.
 */
interface Source {
    val id: String
    val name: String
    val status: SourceStatus

    var statusListener: StatusListener?

    /** Null bila compositor belum memasang tujuan frame. */
    var frameSink: FrameSink?

    fun start()
    fun stop()

    interface StatusListener {
        fun onStatusChanged(source: Source, status: SourceStatus)
    }
}
