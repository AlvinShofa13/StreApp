package com.studiopocket.chat

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Chat Twitch baca-saja lewat WebSocket IRC anonim. Tanpa login, tanpa mengirim pesan.
 * Sambung ulang otomatis dengan jeda bertahap; kegagalan tidak memengaruhi rekaman.
 */
class TwitchChatSource(private val channel: String) : ChatSource {
    override val name: String = "Twitch #$channel"
    override var connection: ChatConnection = ChatConnection.TERPUTUS
        private set

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var listener: ((ChatMessage) -> Unit)? = null
    private var percobaan = 0
    @Volatile private var running = false
    private var nomorPesan = 0L

    override fun start(listener: (ChatMessage) -> Unit) {
        if (running) return
        running = true
        this.listener = listener
        sambung()
    }

    private fun sambung() {
        if (!running) return
        connection = ChatConnection.MENYAMBUNG
        val request = Request.Builder()
            .url("wss://irc-ws.chat.twitch.tv:443")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val nick = "justinfan${Random.nextInt(10000, 99999)}"
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                webSocket.send("PASS SCHMOOPIIE")
                webSocket.send("NICK $nick")
                webSocket.send("JOIN #$channel")
                connection = ChatConnection.TERSAMBUNG
                percobaan = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (baris in text.split("\r\n")) {
                    when {
                        baris.startsWith("PING") -> webSocket.send("PONG :tmi.twitch.tv")
                        baris.contains(" PRIVMSG ") -> terimaPesan(baris)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connection = ChatConnection.GALAT
                jadwalkanSambungUlang()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connection = ChatConnection.TERPUTUS
                jadwalkanSambungUlang()
            }
        })
    }

    private fun jadwalkanSambungUlang() {
        if (!running) return
        percobaan++
        handler.postDelayed({ if (running) sambung() }, (2000L * percobaan).coerceAtMost(15_000L))
    }

    private fun terimaPesan(baris: String) {
        val pemisah = baris.indexOf(" PRIVMSG ")
        if (pemisah < 0) return
        val sebelum = baris.substring(0, pemisah)
        val isiMulai = baris.indexOf(':', pemisah + 9)
        if (isiMulai < 0) return
        val teks = baris.substring(isiMulai + 1)
        var author = sebelum.substringAfter(':', "").substringBefore('!').ifEmpty { "penonton" }
        var role = ChatRole.PENONTON
        if (sebelum.startsWith("@")) {
            val tags = sebelum.substring(1, sebelum.indexOf(' '))
            for (tag in tags.split(';')) {
                when {
                    tag.startsWith("display-name=") && tag.length > 13 -> author = tag.substring(13)
                    tag.startsWith("mod=") && tag.endsWith("=1") -> role = ChatRole.MODERATOR
                    tag.startsWith("badges=") && tag.contains("broadcaster/") -> role = ChatRole.PEMILIK
                    tag.startsWith("user-type=mod") -> role = ChatRole.MODERATOR
                }
            }
        }
        if (teks.isBlank()) return
        nomorPesan++
        listener?.invoke(ChatMessage("twitch-$nomorPesan", author, teks, role))
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "berhenti")
        socket = null
        connection = ChatConnection.TERPUTUS
    }
}
