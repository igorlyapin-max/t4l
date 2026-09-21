package app.t4l

import android.content.Context
import androidx.core.content.edit
import java.net.URI

enum class SyncDelay(val seconds: Long) {
    IMMEDIATELY(0),
    FIVE_SECONDS(5),
    FIFTEEN_SECONDS(15),
    THIRTY_SECONDS(30),
    ONE_MINUTE(60),
    FIVE_MINUTES(300),
}

internal val DEFAULT_SYNC_DELAY = SyncDelay.THIRTY_SECONDS

object ServerUrlPolicy {
    fun normalize(candidate: String, debug: Boolean): String? {
        val normalized = candidate.trim().trimEnd('/') + "/"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val debugHttp = debug && uri.scheme.equals("http", ignoreCase = true) &&
            isPrivateOrLoopback(uri.host.lowercase())
        return normalized.takeIf { secure || debugHttp }
    }

    private fun isPrivateOrLoopback(host: String): Boolean {
        if (host == "localhost") return true
        val parts = host.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || parts[0] == 127 ||
            parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31
    }
}

class ServerSettings(context: Context) {
    private val preferences = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    val baseUrl: String
        get() = preferences.getString(KEY_BASE_URL, null) ?: BuildConfig.API_BASE_URL

    val syncDelay: SyncDelay
        get() = SyncDelay.entries.firstOrNull {
            it.seconds == preferences.getLong(KEY_SYNC_DELAY_SECONDS, DEFAULT_SYNC_DELAY.seconds)
        } ?: DEFAULT_SYNC_DELAY

    fun update(candidate: String, delay: SyncDelay): Boolean {
        val normalized = ServerUrlPolicy.normalize(candidate, BuildConfig.DEBUG) ?: return false
        preferences.edit {
            putString(KEY_BASE_URL, normalized)
            putLong(KEY_SYNC_DELAY_SECONDS, delay.seconds)
        }
        return true
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_SYNC_DELAY_SECONDS = "sync_delay_seconds"
    }
}
