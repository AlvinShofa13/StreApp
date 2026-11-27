package com.studiopocket.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.studiopocket.app.R
import com.studiopocket.app.RecordingService
import com.studiopocket.core.audio.AudioEngine
import com.studiopocket.core.audio.AudioInputKind
import com.studiopocket.core.audio.MonitorMode
import com.studiopocket.core.audio.RemoteAudioInput
import com.studiopocket.core.thermal.ThermalAction
import com.studiopocket.core.thermal.ThermalLevel
import com.studiopocket.core.thermal.ThermalMonitor
import com.studiopocket.core.video.EncodedSink
import com.studiopocket.core.video.GlCompositor
import com.studiopocket.core.video.LayerRect
import com.studiopocket.core.video.LayerShape as VideoShape
import com.studiopocket.core.video.Mp4SegmentMerger
import com.studiopocket.core.video.RecordingPipeline
import com.studiopocket.core.video.TestPatternSource
import com.studiopocket.core.video.VideoOverlaySource
import com.studiopocket.model.LayerShape
import com.studiopocket.model.Preset
import com.studiopocket.model.Project
import com.studiopocket.model.QualityPreset
import com.studiopocket.model.Scene
import com.studiopocket.model.SceneSource
import com.studiopocket.model.SceneTemplate
import com.studiopocket.model.SceneTemplates
import com.studiopocket.model.SceneTransition
import com.studiopocket.model.SourceStatus
import com.studiopocket.model.SourceType
import com.studiopocket.model.Transform
import com.studiopocket.source.camera.CameraSource
import com.studiopocket.source.remote.PairingInfo
import com.studiopocket.source.remote.RemoteHostServer
import com.studiopocket.source.remote.RemoteNodeConnection
import com.studiopocket.source.remote.RemoteNodeStatus
import com.studiopocket.source.remote.RemoteSource
import com.studiopocket.source.screen.ScreenSource
import com.studiopocket.source.uvc.UvcSource
import com.studiopocket.stream.RtmpPublisher
import com.studiopocket.stream.StreamProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mesin aplikasi: satu compositor, satu encoder, satu mixer untuk rekaman dan siaran.
 * Semua sumber yang tidak terlihat tidak dirender, tetapi objeknya tetap hidup agar
 * perpindahan scene cepat. Kegagalan sumber tidak menghentikan pipeline.
 */
class AppEngine(val context: Context) {
    private val projectStore = ProjectStore(context)
    private val defaultSceneName = context.getString(R.string.scene_default)
    private val mutableState = MutableStateFlow(EngineState(project = projectStore.load(defaultSceneName)))
    val state: StateFlow<EngineState> = mutableState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtimeLock = Any()
    private val runtimes = LinkedHashMap<String, VideoRuntime>()
    private var compositor: GlCompositor? = null
    private var audioEngine: AudioEngine? = null
    private var pipeline: RecordingPipeline? = null
    private var publisher: RtmpPublisher? = null
    private var tap: PipelineTap? = null
    private var thermalMonitor: ThermalMonitor? = null
    private var remoteServer: RemoteHostServer? = null
    private var projection: MediaProjection? = null
    private var recordingStartMs = 0L
    private var markerFile: File? = null
    private val markers = ArrayList<Pair<Long, String>>()
    private var streamJob: Job? = null
    private var lastThermal: ThermalLevel = ThermalLevel.NORMAL

    /** Callback tiap frame kompositor selesai dirender (untuk DebugStats). */
    var onCompositorFrame: (() -> Unit)? = null
        set(value) {
            field = value
            compositor?.onFrameRendered = value
        }

    private class VideoRuntime(
        val modelId: String,
        val source: com.studiopocket.model.Source?,
        var layerId: String? = null,
        var remoteAudio: RemoteAudioInput? = null,
        var overlayHash: Int = 0,
        var remoteWidth: Int = 1280,
        var remoteHeight: Int = 720,
    )

    private inner class PipelineTap : EncodedSink {
        @Volatile var videoFormat: MediaFormat? = null
        @Volatile var audioFormat: MediaFormat? = null
        @Volatile var target: RtmpPublisher? = null

        override fun onVideoFormat(format: MediaFormat) {
            videoFormat = format
            target?.setVideoFormat(format)
        }

        override fun onVideoSample(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
            target?.writeVideo(buffer, info)
        }

        override fun onAudioFormat(format: MediaFormat) {
            audioFormat = format
            target?.setAudioFormat(format)
        }

        override fun onAudioSample(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
            target?.writeAudio(buffer, info)
        }

        fun attach(p: RtmpPublisher) {
            target = p
            videoFormat?.let { p.setVideoFormat(it) }
            audioFormat?.let { p.setAudioFormat(it) }
        }

        fun detach() {
            target = null
        }
    }

    init {
        scope.launch { tickLoop() }
    }

    // ---------------------------------------------------------------- lifecycle

    fun ensureStarted() {
        if (compositor != null) return
        val s = mutableState.value
        val c = GlCompositor(canvasWidth = s.outputWidth, canvasHeight = s.outputHeight)
        c.onFrameRendered = onCompositorFrame
        c.encoderFps = s.recordingResolution.fps
        c.previewFps = if (s.previewLowPower) 15 else 30
        c.previewEnabled = s.previewEnabled
        c.start()
        compositor = c
        thermalMonitor = ThermalMonitor(context).also {
            it.listener = ::terapkanTermal
            it.start()
        }
        ensureAudio()
        syncProject()
    }

    fun attachPreview(surface: android.view.Surface, width: Int, height: Int) {
        ensureStarted()
        compositor?.setOutputSurface(surface, width, height)
    }

    fun detachPreview() {
        compositor?.setOutputSurface(null, 0, 0)
    }

    fun shutdown() {
        stopRecording()
        stopStream()
        stopRemoteHost()
        compositor?.stop()
        compositor = null
        audioEngine?.stop()
        audioEngine = null
        thermalMonitor?.stop()
        thermalMonitor = null
    }

    // ---------------------------------------------------------------- project

    private fun simpan(project: Project) {
        mutableState.update { it.copy(project = project) }
        scope.launch(Dispatchers.IO) { projectStore.save(project) }
    }

    fun syncProject() {
        ensureStarted()
        val project = mutableState.value.project
        val scene = project.activeScene ?: return
        val semua = project.scenes.flatMap { it.sources }.associateBy { it.id }

        synchronized(runtimeLock) {
            val hapus = runtimes.keys.filter { it !in semua.keys }
            for (id in hapus) {
                val r = runtimes.remove(id) ?: continue
                r.source?.stop()
                r.layerId?.let { compositor?.removeLayer(it) }
                r.remoteAudio?.stop()
            }
            for (s in semua.values) ensureRuntime(s)
        }

        for (s in scene.sources) {
            val r = synchronized(runtimeLock) { runtimes[s.id] } ?: continue
            val rect = rectDari(s.transform)
            val z = scene.sources.indexOf(s)
            r.layerId?.let { id ->
                compositor?.updateLayerRect(id, rect)
                compositor?.updateLayerZIndex(id, z)
                compositor?.setLayerVisible(id, s.visible)
                compositor?.updateLayerShape(id, videoShape(s.shape))
                when (val src = r.source) {
                    is CameraSource -> compositor?.updateLayerTransform(
                        id = id,
                        rotationDegrees = s.rotationDegrees,
                        isMirrored = src.isFrontFacing,
                        sourceWidth = src.previewWidth,
                        sourceHeight = src.previewHeight,
                    )
                    is RemoteSource -> compositor?.updateLayerTransform(
                        id = id,
                        rotationDegrees = s.rotationDegrees,
                        isMirrored = false,
                        sourceWidth = r.remoteWidth,
                        sourceHeight = r.remoteHeight,
                    )
                    is VideoOverlaySource -> compositor?.updateLayerTransform(
                        id = id,
                        rotationDegrees = s.rotationDegrees,
                        isMirrored = false,
                        sourceWidth = r.remoteWidth,
                        sourceHeight = r.remoteHeight,
                    )
                    else -> Unit
                }
            }
            if (s.visible) r.source?.start()
        }
        for ((id, r) in synchronized(runtimeLock) { runtimes.toMap() }) {
            if (scene.sources.none { it.id == id }) {
                r.layerId?.let { compositor?.setLayerVisible(it, false) }
            }
        }
        val statuses = HashMap<String, SourceStatus>()
        for ((id, r) in runtimes) {
            r.source?.let { statuses[id] = it.status }
        }
        mutableState.update { it.copy(sourceStatus = statuses) }
    }

    private fun ensureRuntime(model: SceneSource) {
        if (runtimes.containsKey(model.id)) return
        val c = compositor ?: return
        val runtime = when (model.type) {
            SourceType.UVC -> {
                val source = UvcSource(context, model.name)
                pasangListener(source)
                c.addOesLayer(model.id, rectDari(model.transform), zIndex = 0, shape = videoShape(model.shape)) { texture ->
                    source.setPreview(texture)
                }
                VideoRuntime(model.id, source, model.id)
            }

            SourceType.CAMERA -> {
                val front = model.deviceKey != "back"
                val source = CameraSource(context, model.name, useFrontFacing = front)
                pasangListener(source)
                source.statusListener = object : com.studiopocket.model.Source.StatusListener {
                    override fun onStatusChanged(src: com.studiopocket.model.Source, status: SourceStatus) {
                        pasangListenerStatus(model.id, status)
                        if (status == SourceStatus.AKTIF) {
                            val currentRot = mutableState.value.project.scenes
                                .asSequence()
                                .flatMap { it.sources.asSequence() }
                                .firstOrNull { it.id == model.id }
                                ?.rotationDegrees ?: model.rotationDegrees
                            c.updateLayerTransform(
                                model.id,
                                rotationDegrees = currentRot,
                                isMirrored = source.isFrontFacing,
                                sourceWidth = source.previewWidth,
                                sourceHeight = source.previewHeight,
                            )
                        }
                    }
                }
                c.addOesLayer(
                    model.id,
                    rectDari(model.transform),
                    zIndex = 0,
                    rotationDegrees = model.rotationDegrees,
                    isMirrored = source.isFrontFacing,
                    sourceWidth = source.previewWidth,
                    sourceHeight = source.previewHeight,
                    shape = videoShape(model.shape),
                ) { texture -> source.setPreview(texture) }
                VideoRuntime(model.id, source, model.id)
            }

            SourceType.SCREEN -> {
                val source = ScreenSource(context, model.name)
                pasangListener(source)
                projection?.let { source.attachProjection(it) }
                c.addOesLayer(model.id, rectDari(model.transform), zIndex = 0, shape = videoShape(model.shape)) { texture ->
                    source.setPreview(texture)
                }
                VideoRuntime(model.id, source, model.id)
            }

            SourceType.TEST -> {
                val source = TestPatternSource(model.name)
                pasangListener(source)
                c.addColorBarLayer(model.id, rectDari(model.transform), zIndex = 0, shape = videoShape(model.shape))
                VideoRuntime(model.id, source, model.id)
            }

            SourceType.REMOTE -> {
                val source = RemoteSource(model.id, model.name) { pcm, frames ->
                    synchronized(runtimeLock) { runtimes[model.id]?.remoteAudio }?.push(pcm, frames)
                }
                pasangListener(source)
                c.addOesLayer(
                    model.id,
                    rectDari(model.transform),
                    zIndex = 0,
                    rotationDegrees = model.rotationDegrees,
                    shape = videoShape(model.shape),
                ) { texture ->
                    source.setPreview(texture)
                }
                VideoRuntime(model.id, source, model.id)
            }

            SourceType.VIDEO -> {
                val source = VideoOverlaySource(
                    context = context,
                    id = model.id,
                    name = model.name,
                    uriString = model.content.orEmpty(),
                    onVideoSizeChanged = { w, h ->
                        synchronized(runtimeLock) {
                            runtimes[model.id]?.let {
                                it.remoteWidth = w
                                it.remoteHeight = h
                            }
                        }
                        val currentRot = mutableState.value.project.scenes
                            .asSequence()
                            .flatMap { it.sources.asSequence() }
                            .firstOrNull { it.id == model.id }
                            ?.rotationDegrees ?: model.rotationDegrees
                        c.updateLayerTransform(
                            id = model.id,
                            rotationDegrees = currentRot,
                            isMirrored = false,
                            sourceWidth = w,
                            sourceHeight = h,
                        )
                    },
                )
                pasangListener(source)
                c.addOesLayer(
                    model.id,
                    rectDari(model.transform),
                    zIndex = 0,
                    rotationDegrees = model.rotationDegrees,
                    shape = videoShape(model.shape),
                ) { texture ->
                    source.setPreview(texture)
                }
                VideoRuntime(model.id, source, model.id)
            }

            SourceType.TEXT, SourceType.IMAGE -> {
                val bitmap = overlayBitmap(model)
                if (bitmap != null) {
                    c.addBitmapLayer(model.id, bitmap, rectDari(model.transform), zIndex = 0, shape = videoShape(model.shape))
                    VideoRuntime(model.id, null, model.id, overlayHash = model.content.hashCode())
                } else {
                    VideoRuntime(model.id, null)
                }
            }
        }
        runtimes[model.id] = runtime
        runtime.layerId?.let {
            c.updateLayerZIndex(it, 0)
            c.setLayerVisible(it, model.visible)
        }
        if (model.visible) runtime.source?.start()
    }

    private fun pasangListener(source: com.studiopocket.model.Source) {
        source.statusListener = object : com.studiopocket.model.Source.StatusListener {
            override fun onStatusChanged(src: com.studiopocket.model.Source, status: SourceStatus) {
                pasangListenerStatus(src.id, status)
            }
        }
    }

    private fun pasangListenerStatus(id: String, status: SourceStatus) {
        mutableState.update { it.copy(sourceStatus = it.sourceStatus + (id to status)) }
    }

    private fun overlayBitmap(model: SceneSource): Bitmap? {
        return when (model.type) {
            SourceType.TEXT -> {
                val teks = model.content?.ifBlank { model.name } ?: model.name
                val width = 1280
                val height = 240
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = (model.textSize * 720f).coerceIn(18f, 160f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setShadowLayer(6f, 0f, 2f, Color.BLACK)
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val lebar = paint.measureText(teks)
                canvas.drawText(teks, (width - lebar) / 2f, height * 0.62f, paint)
                bitmap
            }

            SourceType.IMAGE -> {
                val uri = model.content ?: return null
                try {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    var sample = 1
                    while (options.outWidth / sample > 1920 || options.outHeight / sample > 1080) sample *= 2
                    val decode = BitmapFactory.Options().apply { inSampleSize = sample }
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                        BitmapFactory.decodeStream(it, null, decode)
                    }
                } catch (_: Throwable) {
                    null
                }
            }

            else -> null
        }
    }

    fun setActiveScene(id: String) {
        val project = mutableState.value.project
        if (project.activeSceneId == id) return
        val duration = project.transitionMs
        if (project.transition == SceneTransition.FADE) {
            compositor?.startFade(duration)
            scope.launch {
                delay((duration / 2).toLong().coerceAtLeast(1))
                simpan(project.setActiveScene(id))
                syncProject()
            }
        } else {
            simpan(project.setActiveScene(id))
            syncProject()
        }
    }

    fun addScene(name: String) {
        val scene = Scene(Project.newId(), name)
        simpan(mutableState.value.project.addScene(scene))
        syncProject()
    }

    fun duplicateScene(id: String, newName: String) {
        simpan(mutableState.value.project.duplicateScene(id, newName))
        syncProject()
    }

    fun renameScene(id: String, name: String) {
        simpan(mutableState.value.project.renameScene(id, name))
    }

    fun removeScene(id: String) {
        simpan(mutableState.value.project.removeScene(id))
        syncProject()
    }

    fun moveScene(id: String, toIndex: Int) {
        simpan(mutableState.value.project.moveScene(id, toIndex))
    }

    fun addSource(type: SourceType, name: String, deviceKey: String? = null, content: String? = null): String? {
        val scene = mutableState.value.project.activeScene ?: return null
        val source = SceneSource(
            id = Project.newId(),
            type = type,
            name = name,
            deviceKey = deviceKey,
            content = content,
            transform = Transform.PENUH,
        )
        simpan(mutableState.value.project.addSource(scene.id, source))
        syncProject()
        return source.id
    }

    fun updateSource(source: SceneSource) {
        val sceneId = mutableState.value.project.activeSceneId ?: return
        simpan(mutableState.value.project.updateSource(sceneId, source))
        syncProject()
        val r = synchronized(runtimeLock) { runtimes[source.id] }
        if (r != null && source.isOverlay) {
            val hash = (source.content ?: "").hashCode()
            if (hash != r.overlayHash) {
                r.overlayHash = hash
                overlayBitmap(source)?.let { compositor?.updateBitmapLayer(source.id, it) }
            }
        }
    }

    fun removeSource(id: String) {
        val sceneId = mutableState.value.project.activeSceneId ?: return
        simpan(mutableState.value.project.removeSource(sceneId, id))
        syncProject()
    }

    fun moveSource(id: String, up: Boolean) {
        val scene = mutableState.value.project.activeScene ?: return
        val index = scene.sources.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = if (up) index + 1 else index - 1
        if (target !in scene.sources.indices) return
        simpan(mutableState.value.project.moveSource(scene.id, id, target))
        syncProject()
    }

    fun setTransform(id: String, transform: Transform) {
        val scene = mutableState.value.project.activeScene ?: return
        val source = scene.source(id) ?: return
        val baru = transform.terkunci()
        updateSource(source.copy(transform = baru))
    }

    fun setShape(id: String, shape: LayerShape) {
        val scene = mutableState.value.project.activeScene ?: return
        val source = scene.source(id) ?: return
        updateSource(source.copy(shape = shape))
    }

    fun setSourceRotation(id: String, degrees: Int) {
        val scene = mutableState.value.project.activeScene ?: return
        val source = scene.source(id) ?: return
        val norm = ((degrees % 360) + 360) % 360
        updateSource(source.copy(rotationDegrees = norm))
    }

    fun setVisible(id: String, visible: Boolean) {
        val scene = mutableState.value.project.activeScene ?: return
        val source = scene.source(id) ?: return
        updateSource(source.copy(visible = visible))
    }

    fun selectSource(id: String?) {
        mutableState.update { it.copy(selectedSourceId = id) }
    }

    fun resetSourceFullScreen(id: String) {
        setTransform(id, Transform.PENUH)
    }

    fun centerSource(id: String) {
        val scene = mutableState.value.project.activeScene ?: return
        val source = scene.source(id) ?: return
        val t = source.transform
        setTransform(id, t.copy(x = (1f - t.width) / 2f, y = (1f - t.height) / 2f))
    }

    fun setPreset(id: String) {
        val project = mutableState.value.project.setPreset(id)
        simpan(project)
        val enc = project.activePreset.encoder
        val matchedRes = when {
            enc.width <= 1280 && enc.fps <= 30 -> RecordingResolution.RES_720P_30
            enc.width <= 1280 && enc.fps >= 60 -> RecordingResolution.RES_720P_60
            enc.width >= 1920 && enc.fps >= 60 -> RecordingResolution.RES_1080P_60
            else -> RecordingResolution.RES_1080P_30
        }
        setRecordingResolution(matchedRes)
        pipeline?.setBitrate(enc.videoBitrateKbps * 1000)
    }

    fun setTransition(transition: SceneTransition, ms: Int) {
        simpan(mutableState.value.project.setTransition(transition, ms))
    }

    fun applyTemplate(template: SceneTemplate) {
        val scene = mutableState.value.project.activeScene ?: return
        val sources = SceneTemplates.susun(template, scene.sources)
        simpan(mutableState.value.project.updateScene(scene.copy(sources = sources)))
        syncProject()
    }

    fun cameraSource(id: String): CameraSource? =
        synchronized(runtimeLock) { runtimes[id]?.source as? CameraSource }

    /** Dipanggil setelah izin CAMERA / RECORD_AUDIO disetujui agar UVC, kamera, dan audio langsung aktif. */
    fun onPermissionsGranted() {
        ensureStarted()
        ensureAudio()
        val scene = mutableState.value.project.activeScene ?: return
        synchronized(runtimeLock) {
            for (s in scene.sources) {
                if (!s.visible) continue
                val src = runtimes[s.id]?.source ?: continue
                if (src.status != SourceStatus.AKTIF) {
                    when (src) {
                        is UvcSource -> src.reconnect()
                        else -> src.start()
                    }
                }
            }
        }
        segarkanAudio()
    }

    /** Coba sambung ulang sumber tertentu (misal UVC atau kamera yang galat/terputus). */
    fun reconnectSource(id: String) {
        val src = synchronized(runtimeLock) { runtimes[id]?.source } ?: return
        when (src) {
            is UvcSource -> src.reconnect()
            else -> {
                src.stop()
                src.start()
            }
        }
    }

    // ---------------------------------------------------------------- audio

    fun ensureAudio() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (audioEngine == null) {
            val engine = AudioEngine(context)
            engine.onPcm = { pcm, frames, ns ->
                pipeline?.feedAudio(pcm, frames, ns)
            }
            engine.onError = { _, pesan -> Log.w(TAG, "Audio: $pesan") }
            engine.onDeviceChanged = {
                scope.launch {
                    autoAttachUsbAudio(engine)
                    mutableState.update { s ->
                        s.copy(
                            headphones = engine.hasHeadphones(),
                            audioChannels = engine.channels(),
                        )
                    }
                }
            }
            engine.start()
            engine.ensureDefaultMic(context.getString(R.string.audio_mic))
            autoAttachUsbAudio(engine)
            audioEngine = engine
            mutableState.update { it.copy(headphones = engine.hasHeadphones()) }
        } else {
            audioEngine?.let { autoAttachUsbAudio(it) }
        }
        segarkanAudio()
    }

    /** Tambahkan audio USB capture card otomatis bila terdeteksi dan belum menjadi saluran aktif. */
    private fun autoAttachUsbAudio(engine: AudioEngine) {
        val usbInputs = engine.inputDevices().filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        val activeIds = engine.channels().mapNotNull { it.deviceId }.toSet()
        for (dev in usbInputs) {
            if (dev.id !in activeIds) {
                val label = dev.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.audio_usb_capture)
                engine.addInputDevice(dev, label)
            }
        }
    }

    fun availableAudioInputDevices(): List<AudioDeviceInfo> =
        audioEngine?.inputDevices().orEmpty()

    fun audioEngine(): AudioEngine? = audioEngine

    fun addUsbAudio(device: AudioDeviceInfo) {
        audioEngine?.addInputDevice(device, device.productName?.toString() ?: "USB")
        segarkanAudio()
    }

    fun addInternalAudio() {
        val p = projection ?: return
        audioEngine?.addPlaybackCapture(p, context.getString(R.string.audio_internal))
        segarkanAudio()
    }

    fun addMicInput() {
        audioEngine?.addMic(null, context.getString(R.string.audio_mic))
        segarkanAudio()
    }

    fun removeAudioChannel(id: String) {
        audioEngine?.removeChannel(id)
        segarkanAudio()
    }

    fun setGain(id: String, gain: Float) {
        audioEngine?.setGain(id, gain)
        segarkanAudio()
    }

    fun setMuted(id: String, muted: Boolean) {
        audioEngine?.setMuted(id, muted)
        segarkanAudio()
    }

    fun setMonitor(id: String, mode: MonitorMode) {
        audioEngine?.setMonitor(id, mode)
        segarkanAudio()
    }

    private fun segarkanAudio() {
        val channels = audioEngine?.channels() ?: emptyList()
        mutableState.update {
            it.copy(
                levels = audioEngine?.levels() ?: emptyMap(),
                audioChannels = channels,
            )
        }
        audioChannels = channels
    }

    @Volatile
    var audioChannels: List<AudioEngine.ChannelInfo> = emptyList()
        private set

    // ---------------------------------------------------------------- layar

    fun attachProjection(mediaProjection: MediaProjection) {
        projection = mediaProjection
        synchronized(runtimeLock) {
            for (r in runtimes.values) (r.source as? ScreenSource)?.attachProjection(mediaProjection)
        }
        mutableState.update { it.copy(hasScreen = true) }
    }

    fun hasProjection(): Boolean = projection != null

    fun setStorageOption(option: StorageOption) {
        mutableState.update { it.copy(storageOption = option) }
    }

    fun setSourceMode(mode: SourceMode) {
        mutableState.update { it.copy(sourceMode = mode) }
        compositor?.let { c ->
            val w = c.canvasWidth.toFloat()
            val h = c.canvasHeight.toFloat()
            synchronized(runtimeLock) {
                for (runtime in runtimes.values) {
                    val layerId = runtime.layerId ?: continue
                    val src = runtime.source ?: continue

                    when (src) {
                        is CameraSource -> {
                            when (mode) {
                                SourceMode.CAMERA_ONLY -> {
                                    src.start()
                                    c.setLayerVisible(layerId, true)
                                    c.updateLayerRect(layerId, LayerRect(0f, 0f, w, h))
                                    c.updateLayerTransform(
                                        layerId,
                                        rotationDegrees = src.getGlRotation(),
                                        isMirrored = src.isFrontFacing,
                                        sourceWidth = src.previewWidth,
                                        sourceHeight = src.previewHeight,
                                    )
                                }
                                SourceMode.ALL -> {
                                    src.start()
                                    c.setLayerVisible(layerId, true)
                                    c.updateLayerRect(layerId, LayerRect(w * 0.67f, h * 0.61f, w * 0.3f, h * 0.33f))
                                    c.updateLayerTransform(
                                        layerId,
                                        rotationDegrees = src.getGlRotation(),
                                        isMirrored = src.isFrontFacing,
                                        sourceWidth = src.previewWidth,
                                        sourceHeight = src.previewHeight,
                                    )
                                }
                                else -> {
                                    c.setLayerVisible(layerId, false)
                                }
                            }
                        }

                        is UvcSource -> {
                            when (mode) {
                                SourceMode.UVC_ONLY, SourceMode.ALL -> {
                                    src.start()
                                    c.setLayerVisible(layerId, true)
                                    c.updateLayerRect(layerId, LayerRect(0f, 0f, w, h))
                                }
                                else -> {
                                    c.setLayerVisible(layerId, false)
                                }
                            }
                        }

                        is TestPatternSource -> {
                            when (mode) {
                                SourceMode.TEST_PATTERN_ONLY -> {
                                    src.start()
                                    c.setLayerVisible(layerId, true)
                                    c.updateLayerRect(layerId, LayerRect(0f, 0f, w, h))
                                }
                                else -> {
                                    c.setLayerVisible(layerId, false)
                                }
                            }
                        }

                        else -> {
                            c.setLayerVisible(layerId, mode == SourceMode.ALL)
                        }
                    }
                }
            }
        }
    }

    fun setRecordingResolution(res: RecordingResolution) {
        mutableState.update { it.copy(recordingResolution = res) }
        val s = mutableState.value
        compositor?.let { c ->
            c.encoderFps = res.fps
            // PRD 9.2 #5: Pratinjau tetap hemat (<= 30 fps) agar tidak membebani GPU/UI saat encoder 60 fps.
            c.previewFps = if (s.previewLowPower) 15 else 30
            if (pipeline == null) {
                c.resizeCanvas(s.outputWidth, s.outputHeight)
                syncProject()
            }
        }
    }

    fun setCanvasPortrait(portrait: Boolean) {
        if (pipeline != null) return
        mutableState.update { it.copy(canvasPortrait = portrait) }
        val s = mutableState.value
        compositor?.let { c ->
            c.resizeCanvas(s.outputWidth, s.outputHeight)
            syncProject()
        }
    }

    fun setPreviewEnabled(enabled: Boolean) {
        mutableState.update { it.copy(previewEnabled = enabled) }
        compositor?.previewEnabled = enabled
    }

    fun setPreviewLowPower(lowPower: Boolean) {
        mutableState.update { it.copy(previewLowPower = lowPower) }
        compositor?.previewFps = if (lowPower) 15 else 30
    }

    // ---------------------------------------------------------------- rekaman

    fun startRecording(): Boolean {
        ensureStarted()
        ensureAudio()
        if (pipeline != null) return true
        val c = compositor ?: return false
        val stateVal = mutableState.value
        val res = stateVal.recordingResolution

        val dir = when (stateVal.storageOption) {
            StorageOption.MOVIES -> context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            StorageOption.DOWNLOADS -> context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            StorageOption.DCIM -> context.getExternalFilesDir(Environment.DIRECTORY_DCIM) ?: context.filesDir
            StorageOption.INTERNAL -> context.filesDir
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "StreApp_$stamp.mp4")
        val storageMb = ruangTersisaMb()
        val menitPerkiraan = res.bitrateKbps / 8 / 1024 * 60
        if (storageMb < 200 || storageMb < menitPerkiraan) {
            mutableState.update { it.copy(errorRes = R.string.error_storage_full) }
            return false
        }
        val p = RecordingPipeline(
            context = context,
            compositor = c,
            outputFile = file,
            segmentIntervalUs = 30_000_000L,
            frameRate = res.fps,
            videoBitrate = res.bitrateKbps * 1000,
            useBuiltinMic = (audioEngine == null),
        )
        val pipelineTap = tap ?: PipelineTap().also { tap = it }
        p.sink = pipelineTap
        p.start()
        pipeline = p
        recordingStartMs = SystemClock.elapsedRealtime()
        markerFile = File(dir, file.nameWithoutExtension + ".markers.json")
        markers.clear()
        mutableState.update {
            it.copy(
                recording = true,
                recordingMs = 0,
                recordingFile = file.absolutePath,
                markers = 0,
                lastFile = file,
            )
        }
        RecordingService.start(context, file.absolutePath, jenisService())
        return true
    }

    private fun jenisService(): Int {
        var types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val scene = mutableState.value.project.activeScene
        if (scene != null && scene.sources.any { it.type == SourceType.CAMERA }) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (projection != null) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return types
    }

    fun stopRecording() {
        val p = pipeline ?: return
        pipeline = null
        RecordingService.stop(context)
        tulisMarker()
        mutableState.update {
            it.copy(
                recording = false,
                savingRecording = true,
                recordingMs = 0,
            )
        }
        if (publisher != null) stopStream()

        scope.launch(Dispatchers.IO) {
            val files = p.stop()
            val finalFile = if (files.size > 1) {
                Mp4SegmentMerger.merge(files, p.outputFile)
            } else {
                files.firstOrNull() ?: p.outputFile
            }
            mutableState.update {
                it.copy(
                    savingRecording = false,
                    lastFile = finalFile,
                    reviewFile = finalFile,
                )
            }
        }
    }

    fun clearReviewFile() {
        mutableState.update { it.copy(reviewFile = null) }
    }

    fun addMarker(label: String = "marker") {
        if (!mutableState.value.recording) return
        markers.add(SystemClock.elapsedRealtime() - recordingStartMs to label)
        mutableState.update { it.copy(markers = markers.size) }
        tulisMarker()
    }

    private fun tulisMarker() {
        val file = markerFile ?: return
        try {
            val array = JSONArray()
            for ((ms, label) in markers) {
                array.put(JSONObject().put("ms", ms).put("label", label))
            }
            file.writeText(array.toString())
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- siaran

    fun startStream(profile: StreamProfile, key: String): Boolean {
        if (!profile.ready) {
            mutableState.update { it.copy(errorRes = R.string.error_stream_not_ready) }
            return false
        }
        if (key.isBlank()) {
            mutableState.update { it.copy(errorRes = R.string.error_stream_no_key) }
            return false
        }
        if (publisher != null) return true
        ensureStarted()
        val p = RtmpPublisher(profile.serverUrl(), key)
        p.listener = { status, message ->
            scope.launch {
                mutableState.update { s ->
                    s.copy(stream = s.stream.copy(status = status, message = message))
                }
                if (status == RtmpPublisher.Status.SIARAN) pipeline?.requestKeyFrame()
            }
        }
        p.onStats = { kbps ->
            scope.launch {
                mutableState.update { s -> s.copy(stream = s.stream.copy(kbps = kbps)) }
            }
        }
        val pipelineTap = tap ?: PipelineTap().also { tap = it }
        pipelineTap.attach(p)
        p.start()
        publisher = p
        mutableState.update {
            it.copy(stream = StreamUi(profileName = profile.name, status = RtmpPublisher.Status.MENYAMBUNG))
        }
        if (pipeline == null) {
            if (!startRecording()) {
                p.stop()
                pipelineTap.detach()
                publisher = null
                return false
            }
        }
        pipeline?.sink = pipelineTap
        mulaiAdaptifBitrate()
        return true
    }

    fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        val p = publisher ?: return
        publisher = null
        p.stop()
        tap?.detach()
        pipeline?.setBitrate(mutableState.value.project.activePreset.encoder.videoBitrateKbps * 1000)
        mutableState.update { it.copy(stream = StreamUi()) }
    }

    private fun mulaiAdaptifBitrate() {
        streamJob?.cancel()
        streamJob = scope.launch {
            while (isActive) {
                delay(5000)
                val saran = publisher?.suggestedBitrateKbps ?: 0
                val preset = mutableState.value.project.activePreset.encoder.videoBitrateKbps
                if (saran > 0) {
                    val target = (saran * 0.9f).roundToInt().coerceIn(800, preset)
                    pipeline?.setBitrate(target * 1000)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Camera Node

    fun startRemoteHost(): PairingInfo? {
        val existing = remoteServer
        if (existing != null) {
            val p = existing.pairing
            mutableState.update { it.copy(remotePairing = p) }
            return p
        }
        // Pastikan ada minimal satu sumber REMOTE di scene aktif agar Node langsung tampil saat tersambung
        val scene = mutableState.value.project.activeScene
        if (scene != null && scene.sources.none { it.type == SourceType.REMOTE }) {
            val id = addSource(SourceType.REMOTE, context.getString(R.string.source_type_remote))
            if (id != null) {
                setTransform(id, Transform(0.64f, 0.05f, 0.32f, 0.34f))
            }
        }
        mutableState.update { it.copy(remoteErrorDetail = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val server = RemoteHostServer(
                    hostName = context.getString(R.string.app_name),
                    appContext = context.applicationContext,
                )
                server.connectionFactory = { nodeId, nodeName, socket, token ->
                    val runtime = synchronized(runtimeLock) {
                        runtimes.values.firstOrNull {
                            it.source is RemoteSource && (it.source as RemoteSource).connection == null
                        } ?: runtimes.values.firstOrNull { it.source is RemoteSource }
                    }
                    val source = runtime?.source as? RemoteSource
                        ?: throw IllegalStateException("Belum ada sumber Camera Node di scene")
                    val remoteAudio = audioEngine?.addRemoteInput(nodeName)
                    synchronized(runtimeLock) { runtime.remoteAudio = remoteAudio }
                    RemoteNodeConnection(
                        nodeId = nodeId,
                        nodeName = nodeName,
                        socket = socket,
                        token = token,
                        textureProvider = { source.previewTexture },
                        onPcm = { pcm, frames -> remoteAudio?.push(pcm, frames) },
                        onStatus = { status ->
                            if (status == RemoteNodeStatus.TERPUTUS) source.onConnectionLost()
                            scope.launch {
                                mutableState.update {
                                    val nodes = if (status == RemoteNodeStatus.TERPUTUS) {
                                        it.remoteNodes - nodeName
                                    } else {
                                        (it.remoteNodes + nodeName).distinct()
                                    }
                                    it.copy(remoteNodes = nodes)
                                }
                            }
                        },
                        onVideoSizeChanged = { width, height ->
                            synchronized(runtimeLock) {
                                runtime.remoteWidth = width
                                runtime.remoteHeight = height
                            }
                            val currentRot = mutableState.value.project.scenes
                                .asSequence()
                                .flatMap { it.sources.asSequence() }
                                .firstOrNull { it.id == runtime.modelId }
                                ?.rotationDegrees ?: 0
                            runtime.layerId?.let { layerId ->
                                compositor?.updateLayerTransform(
                                    id = layerId,
                                    rotationDegrees = currentRot,
                                    isMirrored = false,
                                    sourceWidth = width,
                                    sourceHeight = height,
                                )
                            }
                        },
                    ).also { source.attach(it) }
                }
                server.onPairingRejected = {
                    scope.launch { mutableState.update { s -> s.copy(errorRes = R.string.error_node_rejected) } }
                }
                val pairing = server.start()
                remoteServer = server
                mutableState.update {
                    it.copy(
                        remotePairing = pairing,
                        remoteEndpoints = server.availableEndpoints,
                        remoteErrorDetail = null,
                        errorRes = null,
                        errorDetail = null,
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal memulai Host Camera Node", t)
                val detail = "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
                mutableState.update {
                    it.copy(
                        remoteErrorDetail = detail,
                        errorRes = R.string.remote_error_start,
                        errorDetail = detail,
                    )
                }
            }
        }
        return remoteServer?.pairing
    }

    fun rotateRemoteToken(): PairingInfo? {
        scope.launch(Dispatchers.IO) {
            val srv = remoteServer ?: return@launch
            val pairing = srv.rotateToken()
            mutableState.update {
                it.copy(
                    remotePairing = pairing,
                    remoteEndpoints = srv.availableEndpoints,
                )
            }
        }
        return remoteServer?.pairing
    }

    fun selectRemoteHostIp(ip: String) {
        val srv = remoteServer ?: return
        val pairing = srv.selectHostIp(ip)
        mutableState.update { it.copy(remotePairing = pairing) }
    }

    fun stopRemoteHost() {
        val srv = remoteServer
        remoteServer = null
        mutableState.update {
            it.copy(
                remotePairing = null,
                remoteEndpoints = emptyList(),
                remoteNodes = emptyList(),
                remoteErrorDetail = null,
            )
        }
        if (srv != null) {
            scope.launch(Dispatchers.IO) { srv.stop() }
        }
    }

    // ---------------------------------------------------------------- termal & status

    private fun terapkanTermal(action: ThermalAction) {
        if (action.level == lastThermal && action.level == ThermalLevel.NORMAL) return
        lastThermal = action.level
        scope.launch {
            val s = mutableState.value
            val targetEncFps = s.recordingResolution.fps
            val targetPrevFps = if (s.previewLowPower) 15 else 30
            compositor?.previewFps = minOf(targetPrevFps, action.previewFps)
            compositor?.encoderFps = if (action.level == ThermalLevel.NORMAL) {
                targetEncFps
            } else {
                minOf(targetEncFps, action.encoderFps)
            }
            mutableState.update { it.copy(thermal = action.level) }
            val bitrateBps = (s.recordingResolution.bitrateKbps * action.bitrateScale * 1000).toInt()
            pipeline?.setBitrate(bitrateBps)
            if (action.stopRecording && pipeline != null) {
                stopRecording()
                mutableState.update { it.copy(errorRes = R.string.error_thermal_stop) }
            }
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            try {
                val s = mutableState.value
                val durasi = if (pipeline != null) SystemClock.elapsedRealtime() - recordingStartMs else 0L
                val streamDetik = if (publisher != null) s.stream.seconds + 1 else 0L
                val chs = audioEngine?.channels() ?: emptyList()
                mutableState.update {
                    it.copy(
                        recordingMs = durasi,
                        stream = it.stream.copy(seconds = streamDetik),
                        batteryPercent = thermalMonitor?.batteryPercent() ?: it.batteryPercent,
                        batteryTempC = thermalMonitor?.batteryTemperatureC(),
                        storageFreeMb = ruangTersisaMb(),
                        levels = audioEngine?.levels() ?: emptyMap(),
                        audioChannels = chs,
                        headphones = audioEngine?.hasHeadphones() ?: false,
                        thermal = thermalMonitor?.current()?.level ?: it.thermal,
                    )
                }
                audioChannels = chs
            } catch (t: Throwable) {
                Log.w(TAG, "Tick gagal: ${t.message}")
            }
            delay(1000)
        }
    }

    private fun ruangTersisaMb(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
        } catch (_: Throwable) {
            0
        }
    }

    fun consumeError() {
        mutableState.update { it.copy(errorRes = null, errorDetail = null) }
    }

    // ---------------------------------------------------------------- util

    private fun rectDari(t: Transform): LayerRect {
        val c = compositor
        val w = (c?.canvasWidth ?: 1280).toFloat()
        val h = (c?.canvasHeight ?: 720).toFloat()
        return LayerRect(t.x * w, t.y * h, t.width * w, t.height * h)
    }

    private fun videoShape(shape: LayerShape): VideoShape = when (shape) {
        LayerShape.KOTAK -> VideoShape.RECT
        LayerShape.SUDUT_MEMBULAT -> VideoShape.ROUNDED
        LayerShape.BULAT -> VideoShape.CIRCLE
    }

    companion object {
        private const val TAG = "SP-AppEngine"
    }
}
