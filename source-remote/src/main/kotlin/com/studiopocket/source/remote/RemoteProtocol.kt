package com.studiopocket.source.remote

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Isi QR pairing. Token menjadi kunci enkripsi sesi dan hanya berlaku untuk sesi ini. */
data class PairingInfo(
    val host: String,
    val port: Int,
    val token: String,
    val hostId: String,
) {
    fun toUri(): String = buildString {
        append("streapp://pair?host=").append(URLEncoder.encode(host, "UTF-8"))
        append("&port=").append(port)
        append("&token=").append(URLEncoder.encode(token, "UTF-8"))
        append("&id=").append(URLEncoder.encode(hostId, "UTF-8"))
    }

    companion object {
        fun parse(uri: String): PairingInfo? {
            if (!uri.startsWith("streapp://pair?")) return null
            return try {
                val query = uri.substringAfter('?')
                val map = query.split('&').associate {
                    val i = it.indexOf('=')
                    URLDecoder.decode(it.substring(0, i), "UTF-8") to
                        URLDecoder.decode(it.substring(i + 1), "UTF-8")
                }
                PairingInfo(
                    host = map["host"] ?: return null,
                    port = map["port"]?.toIntOrNull() ?: return null,
                    token = map["token"] ?: return null,
                    hostId = map["id"] ?: "",
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/** Jenis pesan pada kanal. */
object RemoteFrame {
    const val HANDSHAKE = 0
    const val VIDEO_CONFIG = 1
    const val VIDEO_SAMPLE = 2
    const val AUDIO_PCM = 3
    const val CONTROL = 4
    const val PING = 5
    const val MAX = 8 * 1024 * 1024
}

data class RemoteFrameData(val type: Int, val payload: ByteArray)

/**
 * Kanal TCP terenkripsi AES-GCM. Setiap pesan memakai IV acak 12 byte; token pairing
 * diturunkan menjadi kunci 128-bit. Token yang salah membuat dekripsi gagal sehingga
 * perangkat tidak dipasangkan ditolak sebelum data apa pun diproses.
 */
class SecureChannel(
    private val input: InputStream,
    private val output: OutputStream,
    token: String,
) {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).copyOf(16),
        "AES",
    )
    private val kunciKirim = Any()

    fun send(type: Int, payload: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMASI)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val plain = ByteArray(payload.size + 1).also {
            it[0] = type.toByte()
            System.arraycopy(payload, 0, it, 1, payload.size)
        }
        val encrypted = cipher.doFinal(plain)
        val header = ByteArray(4 + iv.size)
        val total = iv.size + encrypted.size
        header[0] = (total ushr 24).toByte()
        header[1] = (total ushr 16).toByte()
        header[2] = (total ushr 8).toByte()
        header[3] = total.toByte()
        System.arraycopy(iv, 0, header, 4, iv.size)
        synchronized(kunciKirim) {
            output.write(header)
            output.write(encrypted)
            output.flush()
        }
    }

    fun receive(): RemoteFrameData? {
        val header = ByteArray(4)
        if (!bacaPenuh(header)) return null
        val total = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (total <= 12 || total > RemoteFrame.MAX) throw IllegalStateException("Bingkai tidak sah: $total")
        val iv = ByteArray(12)
        if (!bacaPenuh(iv)) return null
        val encrypted = ByteArray(total - 12)
        if (!bacaPenuh(encrypted)) return null
        val cipher = Cipher.getInstance(TRANSFORMASI)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(encrypted)
        if (plain.isEmpty()) return null
        return RemoteFrameData(plain[0].toInt() and 0xFF, plain.copyOfRange(1, plain.size))
    }

    private fun bacaPenuh(target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val n = try {
                input.read(target, offset, target.size - offset)
            } catch (e: EOFException) {
                return false
            }
            if (n < 0) return false
            offset += n
        }
        return true
    }

    companion object {
        private const val TRANSFORMASI = "AES/GCM/NoPadding"
    }
}

/** Kontrol host ke Node; semua bidang opsional. */
data class RemoteControl(
    val focusMode: String? = null,
    val focusDistance: Float? = null,
    val zoom: Float? = null,
    val exposure: Int? = null,
    val torch: Boolean? = null,
    val audioEnabled: Boolean? = null,
    val whiteBalance: String? = null,
) {
    fun toJson(): String = buildString {
        append("{")
        var pertama = true
        fun tambah(nama: String, nilai: Any) {
            if (!pertama) append(',')
            pertama = false
            append('"').append(nama).append("\":")
            if (nilai is String) append('"').append(nilai).append('"') else append(nilai)
        }
        focusMode?.let { tambah("focusMode", it) }
        focusDistance?.let { tambah("focusDistance", it) }
        zoom?.let { tambah("zoom", it) }
        exposure?.let { tambah("exposure", it) }
        torch?.let { tambah("torch", it) }
        audioEnabled?.let { tambah("audio", it) }
        whiteBalance?.let { tambah("whiteBalance", it) }
        append("}")
    }
}
