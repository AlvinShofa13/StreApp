package com.studiopocket.chat

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Chat YouTube lewat YouTube Live Streaming API (liveChatMessages.list).
 * Butuh kunci API atau token OAuth; keduanya disimpan terenkripsi oleh lapisan aplikasi.
 * Kuota dihitung 5 unit per permintaan dan ditampilkan di pengaturan.
 */
class YouTubeChatSource(
    private val liveChatId: String,
    private val apiKey: String,
) : ChatSource {
    override val name: String = "YouTube"
    override var connection: ChatConnection = ChatConnection.TERPUTUS
        private set

    /** Kuota yang sudah dipakai sesi ini. */
    @Volatile
    var quotaUsed: Int = 0
        private set

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var pageToken: String? = null
    private var listener: ((ChatMessage) -> Unit)? = null
    private var nomorPesan = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun start(listener: (ChatMessage) -> Unit) {
        if (running) return
        running = true
        this.listener = listener
        if (apiKey.isBlank()) {
            connection = ChatConnection.GALAT
            return
        }
        connection = ChatConnection.MENYAMBUNG
        thread = Thread({ loop() }, "SP-ChatYouTube").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        var jeda = 5000L
        while (running) {
            try {
                val hasil = ambil()
                if (hasil == null) {
                    connection = ChatConnection.GALAT
                    jeda = 5000
                } else {
                    connection = ChatConnection.TERSAMBUNG
                    for (m in hasil.messages) listener?.invoke(m)
                    pageToken = hasil.nextPageToken
                    jeda = hasil.pollingIntervalMs.coerceIn(1000L, 15000L)
                    if (hasil.offline) running = false
                }
            } catch (_: Throwable) {
                connection = ChatConnection.GALAT
                jeda = 5000
            }
            try {
                Thread.sleep(jeda)
            } catch (_: InterruptedException) {
                break
            }
        }
        connection = ChatConnection.TERPUTUS
    }

    private class Hasil(
        val messages: List<ChatMessage>,
        val nextPageToken: String?,
        val pollingIntervalMs: Long,
        val offline: Boolean,
    )

    private fun ambil(): Hasil? {
        val url = StringBuilder("https://www.googleapis.com/youtube/v3/liveChat/messages")
            .append("?liveChatId=").append(liveChatId)
            .append("&part=snippet,authorDetails")
            .append("&maxResults=200")
            .append("&key=").append(apiKey)
        pageToken?.let { url.append("&pageToken=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        val request = Request.Builder().url(url.toString()).get().build()
        client.newCall(request).execute().use { response ->
            quotaUsed += 5
            if (!response.isSuccessful) return null
            val body = JSONObject(response.body?.string() ?: return null)
            val daftar = ArrayList<ChatMessage>()
            val items = body.optJSONArray("items") ?: return null
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val snippet = item.optJSONObject("snippet") ?: continue
                val author = item.optJSONObject("authorDetails")
                val teks = snippet.optString("displayMessage")
                if (teks.isBlank()) continue
                val role = when {
                    author?.optBoolean("isChatOwner") == true -> ChatRole.PEMILIK
                    author?.optBoolean("isChatModerator") == true -> ChatRole.MODERATOR
                    else -> ChatRole.PENONTON
                }
                nomorPesan++
                daftar.add(
                    ChatMessage(
                        id = item.optString("id", "yt-$nomorPesan"),
                        author = author?.optString("displayName")?.ifEmpty { "Penonton" } ?: "Penonton",
                        text = teks,
                        role = role,
                    ),
                )
            }
            return Hasil(
                messages = daftar,
                nextPageToken = if (body.has("nextPageToken") && !body.isNull("nextPageToken")) {
                    body.getString("nextPageToken")
                } else {
                    null
                },
                pollingIntervalMs = body.optLong("pollingIntervalMillis", 5000L),
                offline = !body.isNull("offlineAt"),
            )
        }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        connection = ChatConnection.TERPUTUS
    }
}
