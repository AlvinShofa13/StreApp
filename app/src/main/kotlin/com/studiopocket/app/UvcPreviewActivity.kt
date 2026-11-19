package com.studiopocket.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.studiopocket.debug.DebugOverlay
import com.studiopocket.debug.DebugStats
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus
import com.studiopocket.source.uvc.UvcSource

class UvcPreviewActivity : Activity() {

    private val stats = DebugStats()
    private lateinit var source: UvcSource
    private lateinit var statusView: TextView
    private lateinit var debugOverlay: DebugOverlay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.catat("preview dibuat")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // uji 10 menit tanpa layar tidur
        val root = FrameLayout(this).apply { setBackgroundColor(0xFFF6F7F9.toInt()) }
        val kolom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        statusView = TextView(this).apply {
            setTextColor(0xFF5B6670.toInt())
            textSize = 16f
            text = getString(R.string.uvc_status_waiting)
        }
        val preview = PreviewView(this)
        // Warna latar di wadah: TextureView menolak background drawable.
        val previewBox = FrameLayout(this).apply {
            setBackgroundColor(0xFF1F2933.toInt())
            addView(
                preview,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        kolom.addView(statusView)
        kolom.addView(
            previewBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        kolom.addView(Button(this).apply {
            setText(R.string.debug_overlay_toggle)
            setOnClickListener { debugOverlay.toggle() }
        })
        root.addView(kolom)
        setContentView(root)

        debugOverlay = DebugOverlay(root, stats)

        source = UvcSource(this, getString(R.string.uvc_source_name))
        source.log = { Diag.catat(it) }
        stats.registerSource(source.name)
        source.statusListener = object : Source.StatusListener {
            override fun onStatusChanged(source: Source, status: SourceStatus) = tampilkanStatus()
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                source.setPreview(texture)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                source.setPreview(null)
                // false: removeSurface diproses async oleh pustaka, jangan lepas tekstur lebih dulu.
                // shortcut: tekstur menunggu GC saat view dihancurkan; perbaiki bila layar sering dibuka-tutup.
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                stats.noteFrameIn(source.name)
                stats.noteFrameOut(source.name)
            }
        }

        debugOverlay.toggle() // T1.1: fps aktual harus langsung terlihat

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Diag.catat("izin kamera sudah ada")
            mulai()
        } else {
            Diag.catat("minta izin kamera")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_KAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_KAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Diag.catat("izin kamera diberikan")
            mulai()
        } else {
            Diag.catat("izin kamera ditolak")
            statusView.setText(R.string.uvc_status_no_permission)
        }
    }

    private fun mulai() {
        Diag.catat("mulai sumber")
        try {
            source.start()
        } catch (t: Throwable) {
            Diag.catat("start gagal", t)
            statusView.setText(R.string.uvc_status_error)
        }
        tampilkanStatus()
    }

    private fun tampilkanStatus() {
        val s = source.activeSize
        statusView.text = when {
            source.status == SourceStatus.AKTIF && s != null ->
                getString(R.string.uvc_status_active, s.width, s.height, s.fps)

            source.status == SourceStatus.AKTIF -> getString(R.string.uvc_status_waiting)
            source.status == SourceStatus.TERPUTUS -> getString(R.string.uvc_status_disconnected)
            source.status == SourceStatus.GALAT -> getString(R.string.uvc_status_error)
            else -> getString(R.string.uvc_status_waiting)
        }
    }

    override fun onDestroy() {
        Diag.catat("preview ditutup")
        if (::source.isInitialized) source.stop()
        debugOverlay.stop()
        super.onDestroy()
    }

    private companion object {
        const val REQ_KAMERA = 1
    }
}

/** TextureView 16:9 sesuai keluaran capture card 720p/1080p. */
private class PreviewView(context: Context) : TextureView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lebar = MeasureSpec.getSize(widthMeasureSpec)
        val tinggi = lebar * 9 / 16
        val batas = MeasureSpec.getSize(heightMeasureSpec)
        val tinggiAkhir = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            tinggi
        } else {
            tinggi.coerceAtMost(batas)
        }
        setMeasuredDimension(lebar, tinggiAkhir)
    }
}
