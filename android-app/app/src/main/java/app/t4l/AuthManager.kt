package app.t4l

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

class AuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val service = AuthorizationService(appContext)
    private val preferences = EncryptedSharedPreferences.create(
        appContext,
        "oidc",
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    @Volatile private var state: AuthState = preferences.getString(KEY_STATE, null)
        ?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() } ?: AuthState()

    val enabled: Boolean get() = BuildConfig.OIDC_ISSUER.isNotBlank()
    val authorized: Boolean get() = !enabled || state.isAuthorized

    fun authorizationIntent(callback: (Intent?, String?) -> Unit) {
        if (!enabled) return callback(null, null)
        AuthorizationServiceConfiguration.fetchFromIssuer(BuildConfig.OIDC_ISSUER.toUri()) { config, error ->
            if (config == null) return@fetchFromIssuer callback(null, error?.errorDescription ?: "oidc_discovery_failed")
            val request = AuthorizationRequest.Builder(
                config,
                BuildConfig.OIDC_CLIENT_ID,
                ResponseTypeValues.CODE,
                BuildConfig.OIDC_REDIRECT_URI.toUri(),
            ).setScope("openid profile offline_access").build()
            callback(service.getAuthorizationRequestIntent(request), null)
        }
    }

    fun completeAuthorization(data: Intent?, callback: (Boolean, String?) -> Unit) {
        if (data == null) return callback(false, "oidc_response_missing")
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        state.update(response, exception)
        save()
        if (response == null) return callback(false, exception?.errorDescription ?: "oidc_authorization_failed")
        service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenError ->
            state.update(tokenResponse, tokenError)
            save()
            callback(state.isAuthorized, tokenError?.errorDescription)
        }
    }

    fun freshAccessToken(): String? {
        if (!enabled) return null
        val latch = CountDownLatch(1)
        var token: String? = null
        state.performActionWithFreshTokens(service) { accessToken, _, exception ->
            if (exception == null) token = accessToken
            save()
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
        return token
    }

    fun signOut() {
        state = AuthState()
        save()
    }

    private fun save() { preferences.edit { putString(KEY_STATE, state.jsonSerializeString()) } }

    private companion object { const val KEY_STATE = "auth_state" }
}
