package com.studiopocket.core.thermal

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Satu-satunya pembaca status termal. Perubahan diteruskan lewat [listener] dalam
 * bentuk [ThermalAction], sehingga modul UI/video tidak membaca suhu sendiri.
 */
class ThermalMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var level = ThermalLevel.NORMAL

    /** Dipanggil di thread utama. */
    var listener: ((ThermalAction) -> Unit)? = null

    private val statusListener = PowerManager.OnThermalStatusChangedListener { status ->
        val baru = ThermalPolicy.levelFromStatus(status)
        val berubah = baru != level
        level = baru
        if (berubah || baru == ThermalLevel.NORMAL) {
            listener?.invoke(ThermalPolicy.action(baru))
        }
    }

    fun start() {
        if (power.currentThermalStatus >= 0) {
            statusListener.onThermalStatusChanged(power.currentThermalStatus)
        }
        power.addThermalStatusListener(appContext.mainExecutor, statusListener)
    }

    fun stop() {
        try {
            power.removeThermalStatusListener(statusListener)
        } catch (_: Throwable) {
        }
    }

    fun current(): ThermalAction = ThermalPolicy.action(level)

    /** Suhu baterai dalam Celsius, null bila tidak tersedia. */
    fun batteryTemperatureC(): Double? {
        val intent = appContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (temp > 0) temp / 10.0 else null
    }

    fun batteryPercent(): Int {
        val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }
}
