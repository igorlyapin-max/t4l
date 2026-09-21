package app.t4l.data

import android.content.Context
import androidx.core.content.edit
import app.t4l.domain.Uuid7

class DeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    val clientId: String = preferences.getString("client_id", null) ?: Uuid7.new().also { generated ->
        preferences.edit { putString("client_id", generated) }
    }
    var workspaceId: String
        get() = preferences.getString("workspace_id", T4LRepository.DEFAULT_WORKSPACE_ID)
            ?: T4LRepository.DEFAULT_WORKSPACE_ID
        set(value) = preferences.edit { putString("workspace_id", value) }
    var userId: String
        get() = preferences.getString("user_id", T4LRepository.DEVELOPMENT_USER_ID)
            ?: T4LRepository.DEVELOPMENT_USER_ID
        set(value) = preferences.edit { putString("user_id", value) }
}
