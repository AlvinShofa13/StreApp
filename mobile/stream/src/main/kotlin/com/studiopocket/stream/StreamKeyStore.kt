package com.studiopocket.stream

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Menyimpan stream key terenkripsi AES-GCM dengan kunci di Android Keystore.
 * Preferensi hanya berisi ciphertext dan IV; tidak pernah teks biasa.
 */
class StreamKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("stream_keys", Context.MODE_PRIVATE)

    fun save(profileId: String, key: String) {
        val cipher = Cipher.getInstance(TRANSFORMASI)
        cipher.init(Cipher.ENCRYPT_MODE, kunci())
        val iv = cipher.iv
        val data = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv-$profileId", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("ct-$profileId", Base64.encodeToString(data, Base64.NO_WRAP))
            .apply()
    }

    fun load(profileId: String): String? {
        val ivText = prefs.getString("iv-$profileId", null) ?: return null
        val dataText = prefs.getString("ct-$profileId", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMASI)
            cipher.init(
                Cipher.DECRYPT_MODE,
                kunci(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    fun remove(profileId: String) {
        prefs.edit().remove("iv-$profileId").remove("ct-$profileId").apply()
    }

    fun has(profileId: String): Boolean = prefs.contains("ct-$profileId")

    private fun kunci(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ALIAS = "streapp-stream-keys"
        private const val TRANSFORMASI = "AES/GCM/NoPadding"
    }
}
