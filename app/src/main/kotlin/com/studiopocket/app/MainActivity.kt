package com.studiopocket.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.studiopocket.debug.DebugOverlay
import com.studiopocket.debug.DebugStats

class MainActivity : Activity() {
    private lateinit var debugOverlay: DebugOverlay

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
        root.addView(kolom)
        debugOverlay = DebugOverlay(root, DebugStats())
        setContentView(root)
    }

    override fun onDestroy() {
        debugOverlay.stop()
        super.onDestroy()
    }
}
