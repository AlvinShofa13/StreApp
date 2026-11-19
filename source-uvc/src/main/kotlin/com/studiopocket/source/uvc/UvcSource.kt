package com.studiopocket.source.uvc

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
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

    private val appContext = context.applicationContext
    private var helper: CameraHelper? = null
    private var preview: SurfaceTexture? = null
    private var cameraOpen = false
    private var running = false

    /** Ukuran yang benar-benar dipakai kamera, null bila belum terbuka. */
    var activeSize: PreviewSize? = null
        private set

    /** Ukuran preview tanpa membocorkan tipe pustaka UVC ke modul lain. */
    data class PreviewSize(val width: Int, val height: Int, val fps: Int)

    override fun start() {
        if (running) return
        running = true
        UVCUtils.init(appContext)
        val h = CameraHelper()
        helper = h
        h.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                if (!running) return
                Log.i(TAG, "attach ${device.deviceName}")
                setStatus(SourceStatus.SIAGA)
                h.selectDevice(device)
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                if (!running) return
                Log.i(TAG, "device open first=$isFirstOpen")
                h.openCamera()
            }

            override fun onCameraOpen(device: UsbDevice) {
                if (!running) return
                cameraOpen = true
                val size = UvcFormat.pilih(h.supportedSizeList.orEmpty())
                activeSize = size?.let { PreviewSize(it.width, it.height, it.fps) }
                if (size != null) h.setPreviewSize(size)
                h.startPreview()
                preview?.let { h.addSurface(it, false) }
                Log.i(TAG, "camera open size=${size ?: "default"}")
                setStatus(SourceStatus.AKTIF)
            }

            override fun onCameraClose(device: UsbDevice) {
                cameraOpen = false
            }

            override fun onDeviceClose(device: UsbDevice) {
                cameraOpen = false
            }

            override fun onDetach(device: UsbDevice) {
                cameraOpen = false
                activeSize = null
                Log.w(TAG, "detach ${device.deviceName}")
                if (running) setStatus(SourceStatus.TERPUTUS)
            }

            override fun onCancel(device: UsbDevice) {
                Log.w(TAG, "USB permission denied")
                if (running) setStatus(SourceStatus.GALAT)
            }

            override fun onError(device: UsbDevice, e: CameraException) {
                cameraOpen = false
                Log.e(TAG, "camera error ${e.code}", e)
                if (running) setStatus(SourceStatus.GALAT)
            }
        })
    }

    /** Pasang atau lepas SurfaceTexture preview. Aman dipanggil sebelum kamera terbuka. */
    fun setPreview(texture: SurfaceTexture?) {
        if (preview === texture) return
        val h = helper
        val lama = preview
        preview = texture
        if (h != null && cameraOpen) {
            if (lama != null) h.removeSurface(lama)
            if (texture != null) h.addSurface(texture, false)
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        val h = helper ?: return
        helper = null
        cameraOpen = false
        activeSize = null
        preview?.let { h.removeSurface(it) }
        h.stopPreview()
        h.release()
        setStatus(SourceStatus.SIAGA)
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
