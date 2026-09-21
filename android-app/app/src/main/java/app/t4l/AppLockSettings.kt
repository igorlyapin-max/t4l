package app.t4l

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.core.content.edit

class AppLockSettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    val enabled: Boolean get() = preferences.getBoolean("enabled", false)

    fun canEnable(): Boolean = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
        BiometricManager.BIOMETRIC_SUCCESS

    fun setEnabled(value: Boolean): Boolean {
        if (value && !canEnable()) return false
        preferences.edit { putBoolean("enabled", value) }
        return true
    }

    companion object {
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
