package com.studiopocket.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Foreground service perekaman: menjaga compositor, encoder, dan audio tetap berjalan
 * saat layar mati atau aplikasi di latar. Notifikasi menampilkan durasi dan lokasi berkas.
 */
class RecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var filePath: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            val engine = AppContainer.engine(this@RecordingService)
            if (engine.state.value.recording) {
                notifikasi(engine.state.value.recordingMs)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        buatChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppContainer.engine(this).stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                filePath = intent?.getStringExtra(EXTRA_FILE)
                val types = intent?.getIntExtra(EXTRA_TYPES, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                val notification = notifikasi(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, notification, types)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        val engine = AppContainer.engine(this)
        if (engine.state.value.recording) engine.stopRecording()
        super.onDestroy()
    }

    private fun notifikasi(ms: Long): Notification {
        val buka = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val berhenti = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detik = ms / 1000
        val durasi = String.format(java.util.Locale.US, "%02d:%02d:%02d", detik / 3600, (detik % 3600) / 60, detik % 60)
        val nama = filePath?.let { File(it).name } ?: ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(getString(R.string.notif_recording_text, durasi, nama))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_recording_text, durasi, nama)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(buka)
            .addAction(0, getString(R.string.notif_stop), berhenti)
            .build()
    }

    private fun buatChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.notif_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_recording_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "streapp-recording"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.studiopocket.app.STOP_RECORDING"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_TYPES = "types"

        fun start(context: Context, file: String, types: Int) {
            val intent = Intent(context, RecordingService::class.java)
                .putExtra(EXTRA_FILE, file)
                .putExtra(EXTRA_TYPES, types)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
