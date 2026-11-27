package com.studiopocket.app

import android.os.Handler
import android.os.Looper
import com.studiopocket.chat.ChatConnection
import com.studiopocket.chat.ChatMessage
import com.studiopocket.chat.ChatSource
import com.studiopocket.chat.ChatStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mengelompokkan pembaruan chat maksimal empat kali per detik agar panel tetap lancar
 * saat 20 pesan/detik. Riwayat dibatasi [ChatStore.maxMessages].
 */
class ChatController {
    private val handler = Handler(Looper.getMainLooper())
    private val store = ChatStore()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _connection = MutableStateFlow(ChatConnection.TERPUTUS)
    val connection: StateFlow<ChatConnection> = _connection

    private var source: ChatSource? = null
    private var pending = false
    private var paused = false

    private val ticker = object : Runnable {
        override fun run() {
            _connection.value = source?.connection ?: ChatConnection.TERPUTUS
            if (source != null) handler.postDelayed(this, 1000)
        }
    }

    var filterBots: Boolean
        get() = store.filterBots
        set(value) {
            store.filterBots = value
            flush()
        }

    var filterCommands: Boolean
        get() = store.filterCommands
        set(value) {
            store.filterCommands = value
            flush()
        }

    fun start(newSource: ChatSource) {
        stop()
        store.bersihkan()
        _messages.value = emptyList()
        source = newSource
        paused = false
        newSource.start { message ->
            if (store.tambah(message)) jadwalkan()
        }
        handler.postDelayed(ticker, 1000)
        jadwalkan()
    }

    fun stop() {
        source?.stop()
        source = null
        handler.removeCallbacksAndMessages(null)
        pending = false
        _connection.value = ChatConnection.TERPUTUS
    }

    /** Layar mati atau aplikasi ke latar: berhenti memperbarui UI, data tetap dibatasi. */
    fun pause() {
        paused = true
        handler.removeCallbacksAndMessages(null)
        pending = false
    }

    fun resume() {
        paused = false
        flush()
        if (source != null) handler.postDelayed(ticker, 1000)
    }

    private fun jadwalkan() {
        if (pending || paused) return
        pending = true
        handler.postDelayed({ pending = false; flush() }, 250)
    }

    private fun flush() {
        _messages.value = store.semua()
        _connection.value = source?.connection ?: ChatConnection.TERPUTUS
    }
}
