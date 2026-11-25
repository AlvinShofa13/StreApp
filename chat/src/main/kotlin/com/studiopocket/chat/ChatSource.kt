package com.studiopocket.chat

/** Peran penulis pesan; ditampilkan sebagai label teks, bukan warna saja. */
enum class ChatRole { PENONTON, MODERATOR, PEMILIK, BOT }

data class ChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val role: ChatRole = ChatRole.PENONTON,
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class ChatConnection { TERPUTUS, MENYAMBUNG, TERSAMBUNG, GALAT }

/** Antarmuka baca-saja untuk semua platform. Tidak ada jalur untuk mengirim pesan. */
interface ChatSource {
    val name: String
    val connection: ChatConnection

    /** Listener dipanggil dari thread jaringan; pemanggil mengelompokkan pembaruan UI. */
    fun start(listener: (ChatMessage) -> Unit)
    fun stop()
}

/**
 * Penyaring dan pembatas pesan. Murni, tanpa Android, agar bisa diuji unit.
 * Pesan disimpan terbaru di akhir; kapasitas dijaga di [maxMessages].
 */
class ChatStore(
    val maxMessages: Int = 200,
    var filterBots: Boolean = false,
    var filterCommands: Boolean = true,
) {
    private val pesan = ArrayDeque<ChatMessage>()

    val size: Int get() = pesan.size

    fun tambah(message: ChatMessage): Boolean {
        if (filterBots && (message.role == ChatRole.BOT || message.author.endsWith("bot", ignoreCase = true))) {
            return false
        }
        if (filterCommands && message.text.trimStart().startsWith("!")) return false
        pesan.addLast(message)
        while (pesan.size > maxMessages) pesan.removeFirst()
        return true
    }

    fun semua(): List<ChatMessage> = pesan.toList()

    fun terbaru(jumlah: Int): List<ChatMessage> =
        if (jumlah >= pesan.size) pesan.toList() else pesan.toList().takeLast(jumlah)

    fun bersihkan() = pesan.clear()
}
