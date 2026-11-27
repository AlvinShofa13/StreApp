package com.studiopocket.debug

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Kumpulan angka mentah overlay. Sumber frame memanggil note* dari thread mana pun. */
class DebugStats(val startedAtMs: Long = System.currentTimeMillis()) {

    data class Sample(val name: String, val inFps: Double, val outFps: Double, val drops: Long)

    internal class Source {
        val inCount = AtomicLong()
        val outCount = AtomicLong()
        val drops = AtomicLong()
    }

    private val sources = LinkedHashMap<String, Source>()

    fun registerSource(name: String) {
        synchronized(sources) { sources.getOrPut(name) { Source() } }
    }

    fun noteFrameIn(name: String) {
        synchronized(sources) { sources[name] }?.inCount?.incrementAndGet()
    }

    fun noteFrameOut(name: String) {
        synchronized(sources) { sources[name] }?.outCount?.incrementAndGet()
    }

    fun noteDrop(name: String) {
        synchronized(sources) { sources[name] }?.drops?.incrementAndGet()
    }

    /** Hitung fps sejak sampel terakhir, lalu reset pencacah. */
    fun sample(intervalMs: Long): List<Sample> {
        val result = ArrayList<Sample>()
        synchronized(sources) {
            for ((name, s) in sources) {
                val i = s.inCount.getAndSet(0).toDouble()
                val o = s.outCount.getAndSet(0).toDouble()
                result.add(Sample(name, i * 1000.0 / intervalMs, o * 1000.0 / intervalMs, s.drops.get()))
            }
        }
        return result
    }

    fun sessionMs(): Long = System.currentTimeMillis() - startedAtMs
}

/** Formatter log berformat tetap. Murni, tanpa Android. */
object OverlayFormat {
    fun baris(
        sessionMs: Long,
        cpuPercent: Double,
        memoryMb: Int,
        batteryTempC: Double?,
        thermalStatus: String,
        samples: List<DebugStats.Sample>,
    ): String {
        val sb = StringBuilder("t=")
        sb.append(hms(sessionMs))
        sb.append(" cpu=").append(desimal(cpuPercent)).append('%')
        sb.append(" mem=").append(memoryMb).append("MB")
        sb.append(" temp=")
        if (batteryTempC == null) sb.append('?') else sb.append(desimal(batteryTempC)).append('C')
        sb.append(" thermal=").append(thermalStatus)
        sb.append(" srcs=").append(samples.size)
        for (s in samples) {
            sb.append(" | ").append(s.name).append(" in=").append(desimal(s.inFps))
                .append(" out=").append(desimal(s.outFps)).append(" drop=").append(s.drops)
        }
        return sb.toString()
    }

    private fun hms(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    private fun desimal(v: Double): String = String.format(Locale.US, "%.1f", v)
}

/** Hitung pemakaian CPU proses dari delta /proc/self/stat. Murni, tanpa Android. */
object CpuTicker {
    /** Masukkan baris pertama /proc/self/stat; kembalikan jumlah jiffies utime+stime. */
    fun jiffiesDariProcStat(line: String): Long? {
        val close = line.lastIndexOf(')')
        if (close < 0) return null
        val f = line.substring(close + 2).split(' ').filter { it.isNotEmpty() }
        if (f.size < 13) return null
        val utime = f[11].toLongOrNull() ?: return null
        val stime = f[12].toLongOrNull() ?: return null
        return utime + stime
    }

    /** Persen CPU: (delta jiffies) / (tick per detik x detik) x 100. */
    fun persen(prevJiffies: Long, nowJiffies: Long, intervalMs: Long, ticksPerSecond: Long = 100): Double =
        if (intervalMs <= 0) 0.0 else (nowJiffies - prevJiffies) * 100_000.0 / (ticksPerSecond * intervalMs)
}
