package com.studiopocket.core.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Penggabung segmen MP4 tanpa re-encode (lossless remux) memakai MediaExtractor + MediaMuxer.
 * Mempertahankan ketahanan pemulihan saat crash (SegmentedMp4Muxer) selama merekam,
 * lalu menyatukan seluruh segmen menjadi satu file MP4 berdurasi penuh saat rekaman berhenti.
 */
object Mp4SegmentMerger {
    private const val TAG = "SP-Mp4Merger"
    private const val BUFFER_BYTES = 1024 * 1024
    internal const val SEGMENT_GAP_US = 33_333L

    /**
     * Menghitung PTS keluaran yang monoton saat berpindah antar segmen.
     * Dipisah sebagai fungsi murni agar dapat diuji langsung lewat unit test JVM.
     */
    internal fun computeMonotonicPts(
        rawPtsUs: Long,
        segFirstPtsUs: Long,
        basePtsUs: Long,
        lastTrackPtsUs: Long,
    ): Long {
        val relPts = (rawPtsUs - segFirstPtsUs).coerceAtLeast(0L)
        var outPts = basePtsUs + relPts
        if (lastTrackPtsUs >= 0L && outPts <= lastTrackPtsUs) {
            outPts = lastTrackPtsUs + 1_000L
        }
        return outPts
    }

    /**
     * Menggabungkan daftar [segments] secara lossless ke [targetFile].
     * Menghapus file segmen sementara hanya jika file hasil gabungan terverifikasi valid.
     */
    fun merge(segments: List<File>, targetFile: File): File {
        val valid = segments.filter { it.exists() && it.length() > 0L }
        if (valid.isEmpty()) return targetFile
        if (valid.size == 1) {
            val single = valid.first()
            if (single.absolutePath != targetFile.absolutePath) {
                if (targetFile.exists()) targetFile.delete()
                if (single.renameTo(targetFile)) return targetFile
            }
            return single
        }

        val parent = targetFile.parentFile ?: valid.first().parentFile
        val tempOut = File(parent, "${targetFile.nameWithoutExtension}_merged.tmp.mp4")
        if (tempOut.exists()) tempOut.delete()

        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(tempOut.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var outVideoTrack = -1
            var outAudioTrack = -1

            // Cari format track video dan audio dari segmen awal yang valid
            for (seg in valid) {
                if (outVideoTrack >= 0 && outAudioTrack >= 0) break
                val probe = MediaExtractor()
                try {
                    probe.setDataSource(seg.absolutePath)
                    for (i in 0 until probe.trackCount) {
                        val fmt = probe.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("video/") && outVideoTrack < 0) {
                            outVideoTrack = muxer.addTrack(fmt)
                        } else if (mime.startsWith("audio/") && outAudioTrack < 0) {
                            outAudioTrack = muxer.addTrack(fmt)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Segmen ${seg.name} dilewati saat membaca format: ${t.message}")
                } finally {
                    probe.release()
                }
            }

            if (outVideoTrack < 0 && outAudioTrack < 0) {
                Log.w(TAG, "Tidak ada track valid untuk digabungkan.")
                return valid.last()
            }

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var nextBasePtsUs = 0L
            var lastWrittenVideoPts = -1L
            var lastWrittenAudioPts = -1L
            var writtenSamples = 0

            for (seg in valid) {
                val ex = MediaExtractor()
                var segFirstPtsUs = Long.MAX_VALUE
                var segMaxWrittenPtsUs = nextBasePtsUs
                try {
                    ex.setDataSource(seg.absolutePath)
                    var inVideo = -1
                    var inAudio = -1
                    for (i in 0 until ex.trackCount) {
                        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("video/") && inVideo < 0) inVideo = i
                        if (mime.startsWith("audio/") && inAudio < 0) inAudio = i
                    }
                    if (inVideo >= 0 && outVideoTrack >= 0) ex.selectTrack(inVideo)
                    if (inAudio >= 0 && outAudioTrack >= 0) ex.selectTrack(inAudio)
                    if (inVideo < 0 && inAudio < 0) continue

                    while (true) {
                        buffer.clear()
                        val sampleSize = ex.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        val trackIdx = ex.sampleTrackIndex
                        val rawPts = ex.sampleTime
                        if (rawPts < 0L) {
                            ex.advance()
                            continue
                        }
                        if (segFirstPtsUs == Long.MAX_VALUE) {
                            segFirstPtsUs = rawPts
                        }

                        val outTrack: Int
                        val outPts: Long
                        when (trackIdx) {
                            inVideo -> {
                                outTrack = outVideoTrack
                                outPts = computeMonotonicPts(rawPts, segFirstPtsUs, nextBasePtsUs, lastWrittenVideoPts)
                                lastWrittenVideoPts = outPts
                            }
                            inAudio -> {
                                outTrack = outAudioTrack
                                outPts = computeMonotonicPts(rawPts, segFirstPtsUs, nextBasePtsUs, lastWrittenAudioPts)
                                lastWrittenAudioPts = outPts
                            }
                            else -> {
                                ex.advance()
                                continue
                            }
                        }

                        if (outTrack >= 0) {
                            info.offset = 0
                            info.size = sampleSize
                            info.presentationTimeUs = outPts
                            val sampleFlags = ex.sampleFlags
                            var codecFlags = 0
                            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                            }
                            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                            }
                            info.flags = codecFlags
                            muxer.writeSampleData(outTrack, buffer, info)
                            writtenSamples++
                            if (outPts > segMaxWrittenPtsUs) {
                                segMaxWrittenPtsUs = outPts
                            }
                        }
                        ex.advance()
                    }
                    if (segFirstPtsUs != Long.MAX_VALUE) {
                        nextBasePtsUs = segMaxWrittenPtsUs + SEGMENT_GAP_US
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Gagal membaca segmen ${seg.name}, melanjutkan segmen berikutnya: ${t.message}")
                } finally {
                    ex.release()
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (writtenSamples > 0 && tempOut.exists() && tempOut.length() > 0L) {
                for (seg in valid) {
                    if (seg.absolutePath != tempOut.absolutePath) {
                        seg.delete()
                    }
                }
                if (targetFile.exists() && targetFile.absolutePath != tempOut.absolutePath) {
                    targetFile.delete()
                }
                val finalOut = if (tempOut.renameTo(targetFile)) targetFile else tempOut
                Log.i(TAG, "Berhasil menggabungkan ${valid.size} segmen MP4 ($writtenSamples sampel) ke ${finalOut.name}")
                return finalOut
            } else {
                tempOut.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal menggabungkan segmen MP4", t)
            tempOut.delete()
        } finally {
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
        }

        // Cadangan aman: jangan hapus segmen bila gagal; kembalikan segmen terbesar yang ada
        return valid.maxByOrNull { it.length() } ?: valid.last()
    }
}
