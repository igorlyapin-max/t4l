package app.t4l

import app.t4l.data.ConflictRow
import app.t4l.data.CategoryRow
import app.t4l.data.TaskRow
import app.t4l.data.HttpStatusException
import java.io.IOException
import java.util.Locale
import javax.crypto.AEADBadTagException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class UiMessageKind {
    VALIDATION,
    OFFLINE,
    AUTHORIZATION,
    CONFLICT,
    BACKUP_INVALID_PASSWORD,
    BACKUP_INVALID_FILE,
    BACKUP_READ_WRITE,
    UNEXPECTED,
    BACKUP_EXPORT_SUCCEEDED,
    BACKUP_IMPORT_SUCCEEDED,
    CONFLICT_RESOLUTION_QUEUED,
}

data class UiFeedback(val id: Long = System.nanoTime(), val kind: UiMessageKind, val count: Int? = null)

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Exporting : BackupUiState
    data object Importing : BackupUiState
    data object PasswordRequired : BackupUiState
}

enum class NotificationCapability { GRANTED, DENIED, NOT_REQUIRED }

internal object UiErrorMapper {
    fun map(error: Throwable, backup: Boolean = false): UiMessageKind {
        val root = generateSequence(error) { it.cause }.last()
        return when {
            root is AEADBadTagException -> UiMessageKind.BACKUP_INVALID_PASSWORD
            backup && (error is IllegalArgumentException || root is IllegalArgumentException || root is SerializationException || root is ClassCastException) -> UiMessageKind.BACKUP_INVALID_FILE
            error is HttpStatusException && error.statusCode in setOf(401, 403) -> UiMessageKind.AUTHORIZATION
            error is HttpStatusException && error.statusCode == 409 -> UiMessageKind.CONFLICT
            error is IOException && backup -> UiMessageKind.BACKUP_READ_WRITE
            error is IOException -> UiMessageKind.OFFLINE
            error is IllegalArgumentException || error is IllegalStateException -> UiMessageKind.VALIDATION
            else -> UiMessageKind.UNEXPECTED
        }
    }
}

data class ConflictField(val key: String, val localValue: String, val serverValue: String)

data class ConflictPresentation(
    val entityType: String,
    val title: String,
    val operation: String,
    val fields: List<ConflictField>,
    val localPayload: String,
    val serverPayload: String,
)

internal object ConflictPresenter {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val hiddenFields = setOf("id", "workspaceid", "revision", "updatedat", "deletedat")

    fun present(row: ConflictRow): ConflictPresentation {
        val local = parseObject(row.localPayloadJson)
        val server = parseObject(row.serverPayloadJson)
        val candidateKeys = if (local.isEmpty()) server.keys else local.keys
        val fields = candidateKeys
            .filterNot { it.lowercase() in hiddenFields }
            .filter { key -> display(local[key]) != display(server[key]) }
            .sorted()
            .map { key -> ConflictField(key, display(local[key]), display(server[key])) }
        val title = listOf("name", "title", "text")
            .firstNotNullOfOrNull { key -> local[key].asText() ?: server[key].asText() }
            ?.take(80)
            ?: row.entityId.take(8)
        return ConflictPresentation(row.entityType, title, row.operation, fields, row.localPayloadJson, row.serverPayloadJson)
    }

    fun isJsonObject(value: String): Boolean = runCatching { json.parseToJsonElement(value) is JsonObject }.getOrDefault(false)

    private fun parseObject(value: String): JsonObject = runCatching {
        if (value.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(value) as JsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    private fun display(value: kotlinx.serialization.json.JsonElement?): String = when (value) {
        null, JsonNull -> "—"
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }

    private fun kotlinx.serialization.json.JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.takeUnless { it.booleanOrNull != null }?.contentOrNull
}

data class BudgetCategoryItem(val category: CategoryRow, val depth: Int)

internal fun budgetCategoryItems(
    categories: List<CategoryRow>,
    ownMinutes: Map<String, Int>,
    hideRedundant: Boolean,
): List<BudgetCategoryItem> {
    val children = categories.groupBy { it.parentId }
    val result = mutableListOf<BudgetCategoryItem>()
    val visited = mutableSetOf<String>()

    fun visit(parentId: String?, depth: Int) {
        children[parentId].orEmpty()
            .sortedWith(compareBy<CategoryRow> { it.sortOrder }.thenBy { it.name })
            .forEach { category ->
                if (!visited.add(category.id)) return@forEach
                val visible = !hideRedundant || (ownMinutes[category.id] ?: 0) > 0 || children[category.id].orEmpty().size > 1
                if (visible) result += BudgetCategoryItem(category, depth)
                visit(category.id, if (visible) depth + 1 else depth)
            }
    }

    visit(null, 0)
    return result
}

internal fun directSubtasks(parentTaskId: String, tasks: List<TaskRow>): List<TaskRow> {
    val statusOrder = mapOf("active" to 0, "paused" to 1, "completed" to 2, "cancelled" to 3)
    return tasks.filter { it.parentTaskId == parentTaskId }
        .sortedWith(
            compareBy<TaskRow> { statusOrder[it.status] ?: Int.MAX_VALUE }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.id },
        )
}
