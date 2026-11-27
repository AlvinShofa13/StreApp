package com.studiopocket.debug

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import kotlin.math.roundToInt

/**
 * Menampilkan lencana FPS ringkas di layar (hanya angka FPS) agar tidak mengganggu,
 * sambil tetap mencatat metrik lengkap (CPU, memori, suhu, drop frame) ke logcat tiap 5 detik.
 */
class DebugOverlay(
    private val container: ViewGroup,
    private val stats: DebugStats,
) {
    private val tag = "SP-DEBUG"
    private val uiIntervalMs = 1000L
    private val logEveryTicks = 5
    // Status termal PowerManager, indeks = nilai currentThermalStatus.
    private val thermalNames = arrayOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "SHUTDOWN")

    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var lastJiffies = CpuTicker.jiffiesDariProcStat(bacaProcStat()) ?: 0L
    private var tickCount = 0

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val density = container.resources.displayMetrics.density
        val padH = (10 * density).roundToInt()
        val padV = (5 * density).roundToInt()
        val bg = GradientDrawable().apply {
            setColor(0xCC1F2933.toInt())
            cornerRadius = 10 * density
        }
        val tv = TextView(container.context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            background = bg
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(padH, padV, padH, padV)
            text = "-- FPS"
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins((16 * density).roundToInt(), (72 * density).roundToInt(), 0, 0)
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
            tickCount = 0
            // Bersihkan akumulasi frame lama agar pembacaan detik pertama akurat
            stats.sample(uiIntervalMs)
            show()
            handler.postDelayed(tick, uiIntervalMs)
        }
        return isShowing
    }

    fun stop() {
        handler.removeCallbacks(tick)
        hide()
    }

    private val tick = object : Runnable {
        override fun run() {
            perbarui()
            handler.postDelayed(this, uiIntervalMs)
        }
    }

    private fun perbarui() {
        tickCount++
        val samples = stats.sample(uiIntervalMs)
        val fps = samples.firstOrNull()?.outFps ?: 0.0
        view?.text = "${fps.roundToInt()} FPS"

        if (tickCount % logEveryTicks == 0) {
            val logIntervalMs = uiIntervalMs * logEveryTicks
            val now = CpuTicker.jiffiesDariProcStat(bacaProcStat())
            val cpu = if (now != null) CpuTicker.persen(lastJiffies, now, logIntervalMs) else 0.0
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

            val line = OverlayFormat.baris(stats.sessionMs(), cpu, memMb, temp, thermal, samples)
            Log.i(tag, line)
        }
    }

    private fun bacaProcStat(): String =
        runCatching { File("/proc/self/stat").readText().trim() }.getOrDefault("")
}
