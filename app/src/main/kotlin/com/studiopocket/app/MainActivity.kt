package com.studiopocket.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.studiopocket.debug.DebugOverlay
import com.studiopocket.debug.DebugStats

class MainActivity : Activity() {
    private lateinit var debugOverlay: DebugOverlay
    private lateinit var catatan: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFFF6F7F9.toInt()) }
        val kolom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 96, 64, 64)
        }
        kolom.addView(Button(this).apply {
            setText(R.string.debug_overlay_toggle)
            setOnClickListener { debugOverlay.toggle() }
        })
        kolom.addView(Button(this).apply {
            setText(R.string.uvc_preview_open)
            setOnClickListener { startActivity(Intent(this@MainActivity, UvcPreviewActivity::class.java)) }
        })
        kolom.addView(TextView(this).apply {
            setText(R.string.diagnostic_title)
            setTextColor(0xFF5B6670.toInt())
            textSize = 12f
            setPadding(0, 32, 0, 8)
        })
        catatan = TextView(this).apply {
            setTextColor(0xFF1F2933.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        kolom.addView(
            ScrollView(this).apply { addView(catatan) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(kolom)
        debugOverlay = DebugOverlay(root, DebugStats())
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        catatan.text = Diag.catatanUntukLayar() // diperbarui juga saat kembali dari layar preview
    }

    override fun onDestroy() {
        debugOverlay.stop()
        super.onDestroy()
    }
}
