package com.studiopocket.source.uvc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.utils.UVCUtils
import com.studiopocket.model.FrameSink
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus

/**
 * Capture card UVC. Frame ditulis langsung ke SurfaceTexture milik UI, tanpa salinan CPU.
 *
 * Saat kabel dicabut helper tetap hidup dan status menjadi TERPUTUS; USBMonitor milik pustaka
 * memantau perangkat, sehingga capture card yang dicolok ulang terbuka lagi tanpa start ulang.
 */
class UvcSource(context: Context, override val name: String) : Source {
    override val id = "uvc"
    override var status: SourceStatus = SourceStatus.SIAGA
        private set
    override var statusListener: Source.StatusListener? = null
    override var frameSink: FrameSink? = null

    /** Penerima catatan langkah; untuk diagnostik. */
    var log: ((String) -> Unit)? = null

    private val appContext = context.applicationContext
    private var helper: CameraHelper? = null
    private var preview: SurfaceTexture? = null
    private var cameraOpen = false
    private var running = false
    @Volatile
    private var deviceSelected = false

    /** Ukuran yang benar-benar dipakai kamera, null bila belum terbuka. */
    var activeSize: PreviewSize? = null
        private set

    /** Ukuran preview tanpa membocorkan tipe pustaka UVC ke modul lain. */
    data class PreviewSize(val width: Int, val height: Int, val fps: Int)

    override fun start() {
        if (running) return
        val hasCameraPermission = appContext.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            catat("UVC: menunggu izin CAMERA sebelum membuka perangkat USB")
            setStatus(SourceStatus.SIAGA)
            return
        }

        running = true
        deviceSelected = false
        catat("UVC: mulai")
        UVCUtils.init(appContext)
        val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val listDev = usb.deviceList.values
        catat("UVC: perangkat USB total=${listDev.size} ${listDev.joinToString { "${it.vendorId}:${it.productId}" }}")

        val h = CameraHelper()
        helper = h
        catat("UVC: helper dibuat")
        h.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                if (!running) return
                catat("UVC: attach ${device.deviceName}")
                if (cameraOpen || deviceSelected) return
                deviceSelected = true
                setStatus(SourceStatus.SIAGA)
                try {
                    h.selectDevice(device)
                } catch (t: Throwable) {
                    deviceSelected = false
                    catat("UVC: selectDevice di onAttach gagal: $t")
                    setStatus(SourceStatus.GALAT)
                }
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                if (!running) return
                catat("UVC: device open first=$isFirstOpen")
                try {
                    h.openCamera()
                } catch (t: Throwable) {
                    deviceSelected = false
                    catat("UVC: openCamera gagal: $t")
                    setStatus(SourceStatus.GALAT)
                }
            }

            override fun onCameraOpen(device: UsbDevice) {
                if (!running) return
                try {
                    cameraOpen = true
                    val size = UvcFormat.pilih(h.supportedSizeList.orEmpty())
                    activeSize = size?.let { PreviewSize(it.width, it.height, it.fps) }
                    catat("UVC: camera open ${size ?: "ukuran bawaan"}")
                    if (size != null) h.setPreviewSize(size)
                    h.startPreview()
                    preview?.let {
                        val w = size?.width ?: 1280
                        val hPx = size?.height ?: 720
                        it.setDefaultBufferSize(w, hPx)
                        h.addSurface(it, false)
                    }
                    setStatus(SourceStatus.AKTIF)
                } catch (t: Throwable) {
                    cameraOpen = false
                    deviceSelected = false
                    catat("UVC: camera open gagal: $t\n${t.stackTraceToString()}")
                    setStatus(SourceStatus.GALAT)
                }
            }

            override fun onCameraClose(device: UsbDevice) {
                cameraOpen = false
            }

            override fun onDeviceClose(device: UsbDevice) {
                cameraOpen = false
                deviceSelected = false
            }

            override fun onDetach(device: UsbDevice) {
                cameraOpen = false
                deviceSelected = false
                activeSize = null
                catat("UVC: detach ${device.deviceName}")
                if (running) setStatus(SourceStatus.TERPUTUS)
            }

            override fun onCancel(device: UsbDevice) {
                deviceSelected = false
                catat("UVC: izin USB ditolak")
                if (running) setStatus(SourceStatus.GALAT)
            }

            override fun onError(device: UsbDevice, e: CameraException) {
                cameraOpen = false
                deviceSelected = false
                catat("UVC: error ${e.code}\n${e.stackTraceToString()}")
                if (running) setStatus(SourceStatus.GALAT)
            }
        })
        catat("UVC: callback terpasang")

        // Jika USB video capture card sudah dicolok sebelum start(), pilih perangkat UVC video
        val uvcDevice = h.deviceList.orEmpty().firstOrNull()
            ?: listDev.firstOrNull { isVideoDevice(it) }
        if (uvcDevice != null && !deviceSelected) {
            deviceSelected = true
            catat("UVC: mencoba selectDevice langsung untuk ${uvcDevice.deviceName}")
            try {
                h.selectDevice(uvcDevice)
            } catch (t: Throwable) {
                deviceSelected = false
                catat("UVC: selectDevice langsung gagal: $t")
            }
        }
    }

    /** Hentikan lalu mulai ulang helper UVC untuk memulihkan dari galat atau izin baru. */
    fun reconnect() {
        stop()
        start()
    }

    private fun isVideoDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    /** Pasang atau lepas SurfaceTexture preview. Aman dipanggil sebelum kamera terbuka. */
    fun setPreview(texture: SurfaceTexture?) {
        if (preview === texture) return
        val h = helper
        val lama = preview
        preview = texture
        val w = activeSize?.width ?: 1280
        val hPx = activeSize?.height ?: 720
        texture?.setDefaultBufferSize(w, hPx)
        catat("UVC: surface ${if (texture != null) "dipasang" else "dilepas"}")
        if (h != null && cameraOpen) {
            if (lama != null) h.removeSurface(lama)
            if (texture != null) h.addSurface(texture, false)
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        deviceSelected = false
        catat("UVC: berhenti")
        val h = helper ?: return
        helper = null
        cameraOpen = false
        activeSize = null
        try {
            preview?.let { h.removeSurface(it) }
            h.stopPreview()
            h.release()
        } catch (t: Throwable) {
            catat("UVC: galat saat stop: $t")
        }
        setStatus(SourceStatus.SIAGA)
    }

    private fun catat(pesan: String) {
        Log.i(TAG, pesan)
        log?.invoke(pesan)
    }

    private fun setStatus(nilai: SourceStatus) {
        if (status == nilai) return
        status = nilai
        statusListener?.onStatusChanged(this, nilai)
    }

    private companion object {
        const val TAG = "SP-UVC"
    }
}
