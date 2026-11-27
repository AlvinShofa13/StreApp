package com.studiopocket.core.audio

import kotlin.math.min

/**
 * Ring buffer PCM 16-bit interleaved, aman dipakai lintas thread.
 * Kapasitas dinyatakan dalam frame. Saat penuh, frame tertua dibuang agar latensi tidak menumpuk.
 */
class PcmRing(
    val channels: Int,
    val frameCapacity: Int,
) {
    private val data = ShortArray(frameCapacity * channels)
    private val lock = Any()
    private var readFrame = 0
    private var count = 0
    private var droppedFrames = 0L

    val dropped: Long get() = synchronized(lock) { droppedFrames }

    fun write(samples: ShortArray, frames: Int) {
        if (frames <= 0) return
        val n = min(frames, frameCapacity)
        val start = frames - n
        synchronized(lock) {
            if (n > frameCapacity - count) {
                val buang = n - (frameCapacity - count)
                readFrame = (readFrame + buang) % frameCapacity
                count -= buang
                droppedFrames += buang
            }
            val writeFrame = (readFrame + count) % frameCapacity
            val pertama = min(n, frameCapacity - writeFrame)
            System.arraycopy(samples, start * channels, data, writeFrame * channels, pertama * channels)
            if (n > pertama) {
                System.arraycopy(samples, (start + pertama) * channels, data, 0, (n - pertama) * channels)
            }
            count += n
        }
    }

    fun read(dest: ShortArray, frames: Int): Int {
        if (frames <= 0) return 0
        synchronized(lock) {
            val n = min(frames, count)
            if (n == 0) return 0
            val pertama = min(n, frameCapacity - readFrame)
            System.arraycopy(data, readFrame * channels, dest, 0, pertama * channels)
            if (n > pertama) {
                System.arraycopy(data, 0, dest, pertama * channels, (n - pertama) * channels)
            }
            readFrame = (readFrame + n) % frameCapacity
            count -= n
            return n
        }
    }

    fun available(): Int = synchronized(lock) { count }

    fun clear() {
        synchronized(lock) {
            readFrame = 0
            count = 0
        }
    }
}
