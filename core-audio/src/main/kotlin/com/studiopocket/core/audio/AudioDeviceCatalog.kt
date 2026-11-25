package com.studiopocket.core.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class AudioInputKind { MIC, USB, BLUETOOTH, INTERNAL, REMOTE }

/** Pemetaan jenis perangkat audio Android ke kebutuhan aplikasi. */
object AudioDeviceCatalog {

    fun inputDevices(manager: AudioManager): List<AudioDeviceInfo> =
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }

    fun outputDevices(manager: AudioManager): List<AudioDeviceInfo> =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    /** Headset kabel diutamakan, lalu USB (capture card), lalu Bluetooth, terakhir mic ponsel. */
    fun bestMic(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        fun cari(vararg types: Int): AudioDeviceInfo? = devices.firstOrNull { it.type in types }
        return cari(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
        )
    }

    fun kindOf(type: Int): AudioInputKind = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> AudioInputKind.USB

        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioInputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInputKind.MIC
        else -> AudioInputKind.MIC
    }

    fun isHeadphoneOutput(type: Int): Boolean = type in setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    fun hasHeadphones(manager: AudioManager): Boolean =
        outputDevices(manager).any { isHeadphoneOutput(it.type) }

    fun outputName(manager: AudioManager): String? =
        outputDevices(manager).mapNotNull { it.productName?.toString() }.firstOrNull()
}
