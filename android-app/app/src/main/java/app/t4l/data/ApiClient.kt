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

    private suspend fun <T> request(
        request: Request,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${body.take(300)}")
            json.decodeFromString(serializer, body)
        }
    }

    private suspend fun rawRequest(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${body.take(300)}")
            body
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
