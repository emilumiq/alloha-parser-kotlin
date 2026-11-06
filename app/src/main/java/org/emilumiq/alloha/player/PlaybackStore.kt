package org.emilumiq.alloha.player

import android.content.Context

data class CachedCatalog(
    val rawJson: String,
    val savedAtMs: Long,
)

class PlaybackStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(snapshot: PlaybackSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, PlaybackSnapshotCodec.encode(snapshot)).apply()
    }

    fun load(): PlaybackSnapshot? =
        prefs.getString(KEY_SNAPSHOT, null)?.let(PlaybackSnapshotCodec::decode)

    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }

    fun saveQuery(token: String, kp: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_KP, kp)
            .apply()
    }

    fun loadQuery(): Pair<String, String>? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf(String::isNotBlank) ?: return null
        val kp = prefs.getString(KEY_KP, null)?.takeIf(String::isNotBlank) ?: return null
        return token to kp
    }

    fun saveCatalog(token: String, kp: String, rawJson: String) {
        prefs.edit()
            .putString(KEY_CATALOG_QUERY, "$token|$kp")
            .putString(KEY_CATALOG_JSON, rawJson)
            .putLong(KEY_CATALOG_TIME, System.currentTimeMillis())
            .apply()
    }

    fun loadCatalog(token: String, kp: String): CachedCatalog? {
        if (prefs.getString(KEY_CATALOG_QUERY, null) != "$token|$kp") return null
        val raw = prefs.getString(KEY_CATALOG_JSON, null)?.takeIf(String::isNotBlank) ?: return null
        return CachedCatalog(raw, prefs.getLong(KEY_CATALOG_TIME, 0L))
    }

    private companion object {
        const val PREFS_NAME = "alloha_playback"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_TOKEN = "query_token"
        const val KEY_KP = "query_kp"
        const val KEY_CATALOG_QUERY = "catalog_query"
        const val KEY_CATALOG_JSON = "catalog_json"
        const val KEY_CATALOG_TIME = "catalog_time"
    }
}
