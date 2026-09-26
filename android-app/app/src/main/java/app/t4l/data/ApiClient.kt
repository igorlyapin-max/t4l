package app.t4l.data

import app.t4l.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType
import java.io.IOException
import java.time.Instant

@Serializable
data class ApiMutation(
    val clientMutationId: String,
    val workspaceId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val baseRevision: Long,
    val payload: JsonObject,
    val atomicGroupId: String? = null,
)

@Serializable data class PushBody(val clientId: String, val mutations: List<ApiMutation>)
@Serializable data class PushResult(
    val clientMutationId: String,
    val status: String,
    val revision: Long? = null,
    val serverEntity: JsonObject? = null,
    val errorCode: String? = null,
    val canonicalEntityId: String? = null,
    val duplicate: Boolean = false,
)
@Serializable data class PushResponse(val results: List<PushResult>)
@Serializable data class ApiChange(
    val sequence: Long,
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val deleted: Boolean,
    val payload: JsonObject,
)
@Serializable data class ChangePage(val changes: List<ApiChange>, val nextCursor: Long, val hasMore: Boolean)
@Serializable data class ImportBody(val name: String, val snapshot: JsonObject)
@Serializable data class ImportResponse(val workspaceId: String, val importedEntities: Int)
@Serializable data class BootstrapWorkspace(val workspaceId: String, val name: String, val role: String)
@Serializable data class BootstrapResponse(val userId: String, val defaultWorkspaceId: String, val workspaces: List<BootstrapWorkspace>)
@Serializable data class DiagnosticEventDto(
    val timestamp: String,
    val level: String,
    val eventName: String,
    val attributes: Map<String, String>,
    val clientId: String,
    val appVersion: String,
)
@Serializable data class DiagnosticBatch(val events: List<DiagnosticEventDto>)
@Serializable data class ApiUserProfile(
    val birthDate: String? = null,
    val lifeExpectancyYears: Double? = null,
    val revision: Long,
    val avatarRevision: Long,
    val hasAvatar: Boolean,
)
@Serializable data class ProfilePatchBody(
    val clientMutationId: String,
    val baseRevision: Long,
    val changedFields: List<String>,
    val birthDate: String? = null,
    val lifeExpectancyYears: Double? = null,
)
data class ProfilePatchResult(val conflict: Boolean, val profile: ApiUserProfile)
@Serializable data class AvatarMutationResponse(val avatarRevision: Long, val hasAvatar: Boolean)
data class AvatarMutationResult(val conflict: Boolean, val response: AvatarMutationResponse)
data class AvatarDownload(val bytes: ByteArray, val contentType: String?, val revision: Long)
class HttpStatusException(val statusCode: Int) : IOException("HTTP $statusCode")

class ApiClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val baseUrlProvider: () -> String = { BuildConfig.API_BASE_URL },
) {
    suspend fun bootstrap(): BootstrapResponse = request(
        Request.Builder().url("${baseUrlProvider()}api/v1/bootstrap").get().build(),
        BootstrapResponse.serializer(),
    )

    suspend fun push(body: PushBody): PushResponse = request(
        Request.Builder()
            .url("${baseUrlProvider()}api/v1/sync/push")
            .post(json.encodeToString(PushBody.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build(),
        PushResponse.serializer(),
    )

    suspend fun changes(workspaceId: String, cursor: Long): ChangePage = request(
        Request.Builder()
            .url("${baseUrlProvider()}api/v1/sync/changes?workspaceId=$workspaceId&cursor=$cursor&limit=200")
            .get()
            .build(),
        ChangePage.serializer(),
    )

    suspend fun snapshot(workspaceId: String): String = rawRequest(
        Request.Builder().url("${baseUrlProvider()}api/v1/sync/snapshot?workspaceId=$workspaceId").get().build(),
    )

    suspend fun importWorkspace(name: String, snapshot: String): ImportResponse = request(
        Request.Builder()
            .url("${baseUrlProvider()}api/v1/imports")
            .post(json.encodeToString(
                ImportBody.serializer(),
                ImportBody(name, json.parseToJsonElement(snapshot) as JsonObject),
            ).toRequestBody(JSON_MEDIA_TYPE))
            .build(),
        ImportResponse.serializer(),
    )

    suspend fun sendDiagnostics(events: List<DiagnosticEventDto>) {
        if (events.isEmpty()) return
        rawRequest(Request.Builder()
            .url("${baseUrlProvider()}api/v1/diagnostics/events")
            .post(json.encodeToString(DiagnosticBatch.serializer(), DiagnosticBatch(events)).toRequestBody(JSON_MEDIA_TYPE))
            .build())
    }

    suspend fun profile(): ApiUserProfile = request(
        Request.Builder().url("${baseUrlProvider()}api/v1/me/profile").get().build(),
        ApiUserProfile.serializer(),
    )

    suspend fun patchProfile(body: ProfilePatchBody): ProfilePatchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrlProvider()}api/v1/me/profile")
            .patch(json.encodeToString(ProfilePatchBody.serializer(), body).toRequestBody(JSON_MEDIA_TYPE)).build()
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (response.code !in listOf(200, 409)) throw HttpStatusException(response.code)
            ProfilePatchResult(response.code == 409, json.decodeFromString(ApiUserProfile.serializer(), payload))
        }
    }

    suspend fun avatar(): AvatarDownload? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("${baseUrlProvider()}api/v1/me/profile/avatar").get().build()).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            AvatarDownload(response.body?.bytes() ?: byteArrayOf(), response.header("Content-Type"), response.header("ETag")?.trim('"')?.toLongOrNull() ?: 0)
        }
    }

    suspend fun putAvatar(mutationId: String, baseRevision: Long, contentType: String, bytes: ByteArray): AvatarMutationResult =
        avatarMutation(Request.Builder()
            .url("${baseUrlProvider()}api/v1/me/profile/avatar?clientMutationId=$mutationId&baseRevision=$baseRevision")
            .put(bytes.toRequestBody(contentType.toMediaType())).build())

    suspend fun deleteAvatar(mutationId: String, baseRevision: Long): AvatarMutationResult = avatarMutation(
        Request.Builder().url("${baseUrlProvider()}api/v1/me/profile/avatar?clientMutationId=$mutationId&baseRevision=$baseRevision").delete().build(),
    )

    private suspend fun avatarMutation(request: Request): AvatarMutationResult = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (response.code !in listOf(200, 409)) throw HttpStatusException(response.code)
            AvatarMutationResult(response.code == 409, json.decodeFromString(AvatarMutationResponse.serializer(), payload))
        }
    }

    private suspend fun <T> request(
        request: Request,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            json.decodeFromString(serializer, body)
        }
    }

    private suspend fun rawRequest(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            body
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
