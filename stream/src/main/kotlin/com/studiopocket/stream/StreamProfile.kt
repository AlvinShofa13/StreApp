package com.studiopocket.stream

/** Platform siaran yang didukung. */
enum class StreamPlatform { YOUTUBE, TWITCH, TIKTOK }

/** Profil akun siaran. Stream key tidak pernah disimpan di objek ini. */
data class StreamProfile(
    val id: String,
    val platform: StreamPlatform,
    val name: String,
    /** Nama kanal untuk chat (Twitch) atau video id (YouTube). */
    val channel: String = "",
    /** Alamat server RTMP; kosong berarti memakai template platform. */
    val rtmpUrl: String = "",
    /** False bila syarat platform belum terpenuhi; siaran diblokir. */
    val ready: Boolean = true,
) {
    fun serverUrl(): String = rtmpUrl.ifBlank { StreamTemplates.urlFor(platform) }
}

/** Template server RTMP bawaan per platform. */
object StreamTemplates {

    fun urlFor(platform: StreamPlatform): String = when (platform) {
        StreamPlatform.YOUTUBE -> "rtmp://a.rtmp.youtube.com/live2"
        StreamPlatform.TWITCH -> "rtmp://live.twitch.tv/app"
        StreamPlatform.TIKTOK -> "rtmp://push-rtmp-f5-sg01.tiktokcdn.com/stage/stream"
    }

    fun keyHint(platform: StreamPlatform): String = when (platform) {
        StreamPlatform.YOUTUBE -> "Kunci siaran YouTube"
        StreamPlatform.TWITCH -> "Kunci siaran Twitch"
        StreamPlatform.TIKTOK -> "Kunci siaran TikTok"
    }
}
