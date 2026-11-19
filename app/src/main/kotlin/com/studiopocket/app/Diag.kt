package com.studiopocket.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan langkah ke berkas dan ke layar, supaya kegagalan di perangkat bisa dibaca
 * tanpa adb. Sementara untuk T1.1; hapus setelah penyebab tutup-mendadak diketahui.
 */
internal object Diag {
    private const val TAG = "SP-DIAG"
    private const val LANGKAH = "diag.txt"
    private const val CRASH = "crash.txt"

    private var folder: File? = null

    fun mulai(context: Context) {
        folder = context.filesDir
        catat("--- mulai ---")
        catat("perangkat ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    fun catat(langkah: String, error: Throwable? = null) {
        Log.i(TAG, langkah, error)
        val teks = stempel() + " " + langkah + if (error != null) "\n" + Log.getStackTraceString(error) else ""
        runCatching { File(folder ?: return, LANGKAH).appendText(teks + "\n") }
    }

    fun simpanCrash(error: Throwable) {
        runCatching { File(folder ?: return, CRASH).writeText(Log.getStackTraceString(error)) }
    }

    /** Isi layar: crash terakhir bila ada, lalu ekor langkah terakhir. */
    fun catatanUntukLayar(): String {
        val bagian = ArrayList<String>()
        baca(CRASH)?.let { bagian.add(it.trim()) }
        baca(LANGKAH)?.let {
            bagian.add(it.lineSequence().filter { b -> b.isNotBlank() }.toList().takeLast(120).joinToString("\n"))
        }
        return bagian.joinToString("\n\n")
    }

    private fun baca(nama: String): String? =
        folder?.let { File(it, nama).takeIf(File::exists)?.readText() }

    private fun stempel(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
