package com.studiopocket.app.engine

import android.content.Context
import com.studiopocket.stream.StreamKeyStore
import com.studiopocket.stream.StreamPlatform
import com.studiopocket.stream.StreamProfile
import org.json.JSONArray
import org.json.JSONObject

/** Profil siaran dan kunci API. Semua rahasia disimpan lewat [StreamKeyStore]. */
class StreamProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("stream_profiles", Context.MODE_PRIVATE)
    private val keys = StreamKeyStore(context)

    fun list(): List<StreamProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                StreamProfile(
                    id = o.getString("id"),
                    platform = StreamPlatform.valueOf(o.getString("platform")),
                    name = o.getString("name"),
                    channel = o.optString("channel"),
                    rtmpUrl = o.optString("url"),
                    ready = o.optBoolean("ready", true),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun save(profile: StreamProfile, streamKey: String? = null) {
        val daftar = list().filterNot { it.id == profile.id } + profile
        val array = JSONArray()
        for (p in daftar) {
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("platform", p.platform.name)
                    .put("name", p.name)
                    .put("channel", p.channel)
                    .put("url", p.rtmpUrl)
                    .put("ready", p.ready),
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
        if (streamKey != null && streamKey.isNotBlank()) keys.save(profile.id, streamKey)
    }

    fun delete(id: String) {
        val daftar = list().filterNot { it.id == id }
        val array = JSONArray()
        for (p in daftar) {
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("platform", p.platform.name)
                    .put("name", p.name)
                    .put("channel", p.channel)
                    .put("url", p.rtmpUrl)
                    .put("ready", p.ready),
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
        keys.remove(id)
    }

    fun streamKey(profileId: String): String? = keys.load(profileId)

    fun hasKey(profileId: String): Boolean = keys.has(profileId)

    fun saveYouTubeApiKey(key: String) {
        if (key.isBlank()) keys.remove(API_KEY_ID) else keys.save(API_KEY_ID, key)
    }

    fun youtubeApiKey(): String = keys.load(API_KEY_ID).orEmpty()

    fun hasYouTubeApiKey(): Boolean = keys.has(API_KEY_ID)

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val API_KEY_ID = "youtube-api-key"
    }
}
