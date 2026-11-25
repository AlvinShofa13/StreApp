package com.studiopocket.source.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.studiopocket.model.FrameSink
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus
import kotlin.math.max
import kotlin.math.min

/** Preset white balance yang aman dipakai di semua perangkat. */
enum class AwbPreset(val mode: Int) {
    AUTO(CaptureRequest.CONTROL_AWB_MODE_AUTO),
    SIANG(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    MENDUNG(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    LAMPU_PANAS(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
}

/**
 * Sumber kamera lokal (Camera2) ke SurfaceTexture kompositor tanpa salinan CPU.
 * Kontrol fokus, eksposur, white balance, zoom, cermin, dan senter diekspos untuk UI.
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
    var maxZoom: Float = 1f
        private set
    var exposeCompensationRange: IntRange = 0..0
        private set
    var supportsTorch: Boolean = false
        private set

    /** Cermin horizontal untuk kamera depan. */
    var isMirrored: Boolean = useFrontFacing

    private var zoom = 1f
    private var exposureIndex = 0
    private var afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
    private var focusDistance: Float? = null
    private var awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var torch = false
    private var activeArray: Rect? = null

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var running = false
    private var retryCount = 0
    private val main = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable {
        if (running && cameraDevice == null) bukaKamera()
    }
    private val kontrolLock = Any()

    fun getGlRotation(displayRotationDegrees: Int = 0): Int = (displayRotationDegrees) % 360

    override fun start() {
        if (running) return
        running = true
        retryCount = 0
        catat("CameraSource: mulai")
        bukaKamera()
    }

    fun setPreview(texture: SurfaceTexture?) {
        if (previewSurfaceTexture === texture) return
        previewSurfaceTexture = texture
        if (running && cameraDevice != null) buatCaptureSession()
    }

    @SuppressLint("MissingPermission")
    private fun bukaKamera() {
        if (!running || cameraDevice != null) return
        try {
            val cameraId = temukanCameraId()
            if (cameraId == null) {
                setStatus(SourceStatus.GALAT)
                return
            }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    retryCount = 0
                    catat("CameraSource: kamera terbuka")
                    buatCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    captureSession = null
                    catat("CameraSource: kamera terputus")
                    setStatus(SourceStatus.TERPUTUS)
                    jadwalkanSambungUlang()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    captureSession = null
                    catat("CameraSource: galat kamera code=$error")
                    setStatus(SourceStatus.GALAT)
                    jadwalkanSambungUlang()
                }
            }, main)
        } catch (t: Throwable) {
            catat("CameraSource: gagal membuka kamera: $t")
            setStatus(SourceStatus.GALAT)
            jadwalkanSambungUlang()
        }
    }

    private fun jadwalkanSambungUlang() {
        if (!running || retryCount >= 15) return
        retryCount++
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, 2000L)
    }

    private fun temukanCameraId(): String? {
        val targetFacing = if (useFrontFacing) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val id = cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == targetFacing
        } ?: cameraManager.cameraIdList.firstOrNull()
        if (id == null) return null
        val chars = cameraManager.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        isFrontFacing = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        isMirrored = isFrontFacing
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (range != null) exposeCompensationRange = range.lower..range.upper
        supportsTorch = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        pilihUkuran(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        return id
    }

    private fun pilihUkuran(map: StreamConfigurationMap?) {
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return
        val cocok = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        val pilihan = cocok.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: cocok.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: cocok.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.first()
        previewWidth = pilihan.width
        previewHeight = pilihan.height
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
            synchronized(kontrolLock) {
                requestBuilder = builder
                terapkanKontrol(builder)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!running) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, main)
                        catat("CameraSource: sesi aktif $previewWidth x $previewHeight")
                        setStatus(SourceStatus.AKTIF)
                    } catch (t: Throwable) {
                        catat("CameraSource: gagal setRepeatingRequest: $t")
                        setStatus(SourceStatus.GALAT)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    catat("CameraSource: konfigurasi sesi gagal")
                    setStatus(SourceStatus.GALAT)
                }
            }, main)
        } catch (t: Throwable) {
            catat("CameraSource: gagal membuat sesi: $t")
            setStatus(SourceStatus.GALAT)
        }
    }

    private fun terapkanKontrol(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        focusDistance?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        if (supportsTorch) {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
            )
        }
        val array = activeArray
        if (array != null) {
            val z = zoom.coerceIn(1f, max(1f, maxZoom))
            val w = (array.width() / z).toInt()
            val h = (array.height() / z).toInt()
            val cx = array.centerX()
            val cy = array.centerY()
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(
                    (cx - w / 2).coerceAtLeast(array.left),
                    (cy - h / 2).coerceAtLeast(array.top),
                    (cx + w / 2).coerceAtMost(array.right),
                    (cy + h / 2).coerceAtMost(array.bottom),
                ),
            )
        }
    }

    private fun terapkanUlang() {
        synchronized(kontrolLock) {
            val builder = requestBuilder ?: return
            val session = captureSession ?: return
            terapkanKontrol(builder)
            try {
                session.setRepeatingRequest(builder.build(), null, main)
            } catch (t: Throwable) {
                catat("CameraSource: gagal memperbarui kontrol: $t")
            }
        }
    }

    fun setZoom(ratio: Float) {
        zoom = ratio.coerceIn(1f, max(1f, maxZoom))
        terapkanUlang()
    }

    fun setExposureCompensation(index: Int) {
        exposureIndex = index.coerceIn(exposeCompensationRange.first, exposeCompensationRange.last)
        terapkanUlang()
    }

    fun setFocusAuto() {
        afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        focusDistance = null
        terapkanUlang()
    }

    fun setFocusLocked() {
        afMode = CaptureRequest.CONTROL_AF_MODE_AUTO
        focusDistance = null
        terapkanUlang()
    }

    /** Jarak fokus manual dalam dioptri 0..10. */
    fun setFocusDistance(diopters: Float) {
        afMode = CaptureRequest.CONTROL_AF_MODE_OFF
        focusDistance = diopters.coerceIn(0f, 10f)
        terapkanUlang()
    }

    fun setWhiteBalance(preset: AwbPreset) {
        awbMode = preset.mode
        terapkanUlang()
    }

    fun setTorch(on: Boolean) {
        if (!supportsTorch) return
        torch = on
        terapkanUlang()
    }

    override fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(retryRunnable)
        catat("CameraSource: berhenti")
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            previewSurface?.release()
            previewSurface = null
        } catch (t: Throwable) {
            catat("CameraSource: galat saat stop: $t")
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
