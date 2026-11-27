package com.studiopocket.stream

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Encoder/decoder AMF0 minimal untuk perintah RTMP. */
object Amf0 {
    private const val NUMBER = 0x00
    private const val BOOLEAN = 0x01
    private const val STRING = 0x02
    private const val OBJECT = 0x03
    private const val NULL = 0x05
    private const val OBJECT_END = 0x09

    fun writeString(out: ByteArrayOutputStream, value: String) {
        out.write(STRING)
        writeUtf8(out, value)
    }

    fun writeNumber(out: ByteArrayOutputStream, value: Double) {
        out.write(NUMBER)
        writeDouble(out, value)
    }

    fun writeBoolean(out: ByteArrayOutputStream, value: Boolean) {
        out.write(BOOLEAN)
        out.write(if (value) 1 else 0)
    }

    fun writeNull(out: ByteArrayOutputStream) {
        out.write(NULL)
    }

    fun writeObject(out: ByteArrayOutputStream, values: Map<String, Any?>) {
        out.write(OBJECT)
        for ((key, value) in values) {
            writeUtf8(out, key)
            when (value) {
                null -> out.write(NULL)
                is String -> writeString(out, value)
                is Boolean -> writeBoolean(out, value)
                is Int -> writeNumber(out, value.toDouble())
                is Double -> writeNumber(out, value)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    writeObject(out, value as Map<String, Any?>)
                }

                else -> writeString(out, value.toString())
            }
        }
        out.write(0)
        out.write(OBJECT_END)
    }

    fun readValue(input: DataInputStream): Any? {
        return when (val marker = input.readUnsignedByte()) {
            NUMBER -> input.readDouble()
            BOOLEAN -> input.readUnsignedByte() != 0
            STRING -> readUtf8(input)
            OBJECT -> {
                val map = LinkedHashMap<String, Any?>()
                while (true) {
                    val name = readUtf8(input)
                    if (name.isEmpty()) {
                        input.readUnsignedByte() // object end
                        break
                    }
                    map[name] = readValue(input)
                }
                map
            }

            NULL -> null
            else -> throw IllegalArgumentException("AMF0 tidak didukung: $marker")
        }
    }

    fun writeUtf8(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write((bytes.size shr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    fun readUtf8(input: InputStream): String {
        val length = (input.read() shl 8) or input.read()
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        DataInputStream(input).readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeDouble(out: ByteArrayOutputStream, value: Double) {
        val bits = value.toRawBits()
        for (i in 7 downTo 0) out.write(((bits shr (i * 8)) and 0xFF).toInt())
    }
}

/** Pesan protokol RTMP yang siap dikirim. */
internal class RtmpMessage(
    val csid: Int,
    val type: Int,
    val streamId: Int,
    val timestamp: Int,
    val payload: ByteArray,
)

internal fun readFully(input: InputStream, size: Int): ByteArray {
    val data = ByteArray(size)
    DataInputStream(input).readFully(data)
    return data
}

internal fun eof(error: Throwable): Boolean = error is EOFException
