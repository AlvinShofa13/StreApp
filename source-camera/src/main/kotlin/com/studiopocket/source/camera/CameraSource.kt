package com.studiopocket.source.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.view.Surface
import com.studiopocket.model.FrameSink
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus

/**
 * Sumber kamera lokal (kamera depan / belakang ponsel via Camera2 API).
 * Mengalirkan frame langsung ke [SurfaceTexture] milik GL Compositor tanpa salinan CPU.
 */
class CameraSource(
    context: Context,
    override val name: String = "Kamera Depan",
    private val useFrontFacing: Boolean = true,
) : Source {
    override val id: String = if (useFrontFacing) "camera_front" else "camera_back"
    override var status: SourceStatus = SourceStatus.SIAGA
        private set
    override var statusListener: Source.StatusListener? = null
    override var frameSink: FrameSink? = null

    var sensorOrientation: Int = 270
        private set
    var isFrontFacing: Boolean = true
        private set
    var previewWidth: Int = 1280
        private set
    var previewHeight: Int = 720
        private set

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var running = false

    fun getGlRotation(displayRotationDegrees: Int = 0): Int {
        return if (isFrontFacing) {
            (360 - sensorOrientation + displayRotationDegrees) % 360
        } else {
            (sensorOrientation - displayRotationDegrees + 360) % 360
        }
    }

    override fun start() {
        if (running) return
        running = true
        catat("CameraSource: mulai")
        bukaKamera()
    }

    fun setPreview(texture: SurfaceTexture?) {
        if (previewSurfaceTexture === texture) return
        previewSurfaceTexture = texture
        if (running && cameraDevice != null) {
            buatCaptureSession()
        }
    }

    @SuppressLint("MissingPermission")
    private fun bukaKamera() {
        try {
            val cameraId = temukanCameraId()
            if (cameraId == null) {
                catat("CameraSource: Kamera tidak ditemukan")
                setStatus(SourceStatus.GALAT)
                return
            }
            catat("CameraSource: Membuka kamera ID $cameraId (orientation=$sensorOrientation, front=$isFrontFacing)")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    catat("CameraSource: Kamera terbuka")
                    setStatus(SourceStatus.SIAGA)
                    buatCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    catat("CameraSource: Kamera terputus")
                    setStatus(SourceStatus.TERPUTUS)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    catat("CameraSource: Galat kamera code=$error")
                    setStatus(SourceStatus.GALAT)
                }
            }, null)
        } catch (t: Throwable) {
            catat("CameraSource: Gagal membuka kamera: $t")
            setStatus(SourceStatus.GALAT)
        }
    }

    private fun temukanCameraId(): String? {
        val targetFacing = if (useFrontFacing) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == targetFacing) {
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
                isFrontFacing = (facing == CameraCharacteristics.LENS_FACING_FRONT)
                return id
            }
        }
        val fallbackId = cameraManager.cameraIdList.firstOrNull()
        if (fallbackId != null) {
            val chars = cameraManager.getCameraCharacteristics(fallbackId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            isFrontFacing = (facing == CameraCharacteristics.LENS_FACING_FRONT)
        }
        return fallbackId
    }

    private fun buatCaptureSession() {
        val camera = cameraDevice ?: return
        val texture = previewSurfaceTexture ?: return

        try {
            captureSession?.close()
            captureSession = null
            previewSurface?.release()

            texture.setDefaultBufferSize(previewWidth, previewHeight)
            val surface = Surface(texture)
            previewSurface = surface

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!running) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, null)
                        catat("CameraSource: Capture session aktif")
                        setStatus(SourceStatus.AKTIF)
                    } catch (t: Throwable) {
                        catat("CameraSource: Gagal setRepeatingRequest: $t")
                        setStatus(SourceStatus.GALAT)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    catat("CameraSource: Konfigurasi capture session gagal")
                    setStatus(SourceStatus.GALAT)
                }
            }, null)
        } catch (t: Throwable) {
            catat("CameraSource: Gagal buat capture session: $t")
            setStatus(SourceStatus.GALAT)
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        catat("CameraSource: berhenti")
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            previewSurface?.release()
            previewSurface = null
        } catch (t: Throwable) {
            catat("CameraSource: Galat saat stop: $t")
        }
        setStatus(SourceStatus.SIAGA)
    }

    private fun setStatus(nilai: SourceStatus) {
        if (status == nilai) return
        status = nilai
        statusListener?.onStatusChanged(this, nilai)
    }

    private fun catat(pesan: String) {
        Log.i(TAG, pesan)
    }

    companion object {
        private const val TAG = "SP-CameraSource"
    }
}
