package com.studiopocket.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.studiopocket.app.ui.theme.Latar
import com.studiopocket.app.ui.theme.StreAppTheme
import com.studiopocket.app.ui.theme.Teks

/**
 * Jembatan WebRTC bawaan di dalam aplikasi Android StreApp:
 * Saat pengguna memindai QR Code dari StreApp Web (Host di Laptop/Browser),
 * halaman Mode Kamera Pengirim Wi-Fi terbuka langsung dengan izin kamera/mic otomatis
 * dan langsung menampilkan QR Answer untuk dipindai balik oleh StreApp Web.
 */
class WebBridgeActivity : ComponentActivity() {
    private var webView: WebView? = null

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        setContent {
            StreAppTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(R.string.remote_web_bridge_title),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Teks,
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back),
                                        tint = Teks,
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Latar),
                        )
                    },
                    containerColor = Latar,
                ) { padding ->
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                webViewClient = WebViewClient()
                                webChromeClient = object : WebChromeClient() {
                                    override fun onPermissionRequest(request: PermissionRequest) {
                                        runOnUiThread {
                                            request.grant(request.resources)
                                        }
                                    }
                                }
                                if (url.isNotBlank()) {
                                    loadUrl(url)
                                }
                                webView = this
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_web_bridge_url"
    }
}
