package com.studiopocket.debug

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/**
 * Menampilkan overlay debug di atas isi layar dan mencatat angka ke logcat tiap 5 detik.
 * Overlay dipasang di dalam container tampilan (bukan window sistem) agar tidak butuh izin khusus.
 */
class DebugOverlay(
    private val container: ViewGroup,
    private val stats: DebugStats,
) {
    private val tag = "SP-DEBUG"
    private val intervalMs = 5000L
    // Status termal PowerManager, indeks = nilai currentThermalStatus.
    private val thermalNames = arrayOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "SHUTDOWN")

    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var lastJiffies = CpuTicker.jiffiesDariProcStat(bacaProcStat()) ?: 0L

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val tv = TextView(container.context).apply {
            setTextColor(0xFF1F2933.toInt()) // teks utama palet
            setBackgroundColor(0xF0FFFFFF.toInt()) // kartu putih
            textSize = 12f
            setPadding(24, 12, 24, 12)
            text = "..."
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(24, 48, 24, 24)
        }
        container.addView(tv, lp)
        tv.bringToFront()
        view = tv
    }

    fun hide() {
        view?.let { container.removeView(it) }
        view = null
    }

    fun toggle(): Boolean {
        if (isShowing) {
            hide()
            handler.removeCallbacks(tick)
        } else {
            show()
            handler.postDelayed(tick, intervalMs)
        }
        return isShowing
    }

    fun stop() {
        handler.removeCallbacks(tick)
        hide()
    }

    private val tick = object : Runnable {
        override fun run() {
            catat(intervalMs)
            handler.postDelayed(this, intervalMs)
        }
    }

    private fun catat(intervalMs: Long) {
        val now = CpuTicker.jiffiesDariProcStat(bacaProcStat())
        val cpu = if (now != null) CpuTicker.persen(lastJiffies, now, intervalMs) else 0.0
        if (now != null) lastJiffies = now

        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val memMb = info.totalPss / 1024

        val sticky = container.context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val t = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temp = if (t > 0) t / 10.0 else null

        val pm = container.context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = thermalNames.getOrNull(pm.currentThermalStatus) ?: "NONE"

        val samples = stats.sample(intervalMs)
        val line = OverlayFormat.baris(stats.sessionMs(), cpu, memMb, temp, thermal, samples)
        Log.i(tag, line)
        view?.text = line
    }

    private fun bacaProcStat(): String =
        runCatching { File("/proc/self/stat").readText().trim() }.getOrDefault("")
}
