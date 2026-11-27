package com.studiopocket.chat

import android.os.SystemClock
import kotlin.random.Random

/**
 * Generator pesan tiruan untuk menguji panel tanpa platform.
 * Interval minimum 50 ms; 20 pesan/detik dipakai pada uji beban.
 */
class FakeChatSource(
    private val messagesPerSecond: Int = 5,
    private val seed: Long = 42,
) : ChatSource {
    override val name: String = "Tiruan"
    override var connection: ChatConnection = ChatConnection.TERPUTUS
        private set

    private var thread: Thread? = null
    @Volatile private var running = false

    private val penonton = listOf("Rina", "Budi", "Sari", "Dimas", "Putri", "Agus", "Maya", "Fajar", "Nadia", "Yoga")
    private val isi = listOf(
        "Halo, gambarnya jernih",
        "Kamera depannya bagus",
        "Suara terdengar jelas",
        "Lanjutkan, seru sekali",
        "Dari tadi menunggu sesi ini",
        "Chat terlihat rapi",
        "Bisa tampilkan layar?",
        "Semangat terus",
        "Kualitas videonya stabil",
        "Salam dari Surabaya",
    )

    override fun start(listener: (ChatMessage) -> Unit) {
        if (running) return
        running = true
        connection = ChatConnection.TERSAMBUNG
        val interval = (1000L / messagesPerSecond.coerceIn(1, 20)).coerceAtLeast(50L)
        thread = Thread {
            val random = Random(seed)
            var nomor = 0
            while (running) {
                SystemClock.sleep(interval)
                if (!running) break
                nomor++
                val author = penonton[random.nextInt(penonton.size)]
                val teks = isi[random.nextInt(isi.size)]
                listener(
                    ChatMessage(
                        id = "fake-$nomor",
                        author = author,
                        text = teks,
                        role = if (nomor % 17 == 0) ChatRole.MODERATOR else ChatRole.PENONTON,
                    ),
                )
            }
        }.apply {
            name = "SP-ChatFake"
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        connection = ChatConnection.TERPUTUS
    }
}
