package app.t4l

import android.content.Context
import androidx.core.content.edit
import app.t4l.data.TaskRow
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TaskListMode { TOP_LEVEL, ALL, TREE }
enum class TaskSortMode { PRIORITY, NEXT_ACTION, DEADLINE }
enum class TaskDetailMode { TITLE, TITLE_COMMENT, TITLE_COMMENT_NEXT, ALL_FIELDS }

data class TaskListSettings(
    val statuses: Set<String> = setOf("active"),
    val listMode: TaskListMode = TaskListMode.ALL,
    val sortMode: TaskSortMode = TaskSortMode.NEXT_ACTION,
    val detailMode: TaskDetailMode = TaskDetailMode.TITLE,
)

data class TaskListItem(val task: TaskRow, val depth: Int)

class TaskListSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("task_list", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<TaskListSettings> = mutableState

    fun update(value: TaskListSettings) {
        preferences.edit {
            putStringSet("statuses", value.statuses)
            putString("listMode", value.listMode.name)
            putString("sortMode", value.sortMode.name)
            putString("detailMode", value.detailMode.name)
        }
        mutableState.value = value
    }

    private fun read() = TaskListSettings(
        statuses = preferences.getStringSet("statuses", setOf("active"))?.toSet().orEmpty(),
        listMode = enumValue(preferences.getString("listMode", null), TaskListMode.ALL),
        sortMode = enumValue(preferences.getString("sortMode", null), TaskSortMode.NEXT_ACTION),
        detailMode = enumValue(preferences.getString("detailMode", null), TaskDetailMode.TITLE),
    )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)
}

internal fun taskListItems(
    tasks: List<TaskRow>,
    settings: TaskListSettings,
    parentTaskId: String? = null,
    subtaskScope: Boolean = false,
): List<TaskListItem> {
    val comparator = taskComparator(settings.sortMode)
    val includedIds = if (!subtaskScope) tasks.mapTo(mutableSetOf()) { it.id } else descendantTaskIds(tasks, requireNotNull(parentTaskId))
    val scoped = tasks.filter { it.id in includedIds }
    val statusVisible: (TaskRow) -> Boolean = { it.status in settings.statuses }
    return when (settings.listMode) {
        TaskListMode.TOP_LEVEL -> scoped
            .filter { if (subtaskScope) it.parentTaskId == parentTaskId else it.parentTaskId == null }
            .filter(statusVisible)
            .sortedWith(comparator)
            .map { TaskListItem(it, 0) }
        TaskListMode.ALL -> scoped.filter(statusVisible).sortedWith(comparator).map { TaskListItem(it, 0) }
        TaskListMode.TREE -> {
            val byId = scoped.associateBy { it.id }
            val visible = scoped.filter(statusVisible)
            fun visibleParent(task: TaskRow): String? {
                var parent = task.parentTaskId
                while (parent != null && (!subtaskScope || parent != parentTaskId)) {
                    val candidate = byId[parent] ?: return if (subtaskScope) parentTaskId else null
                    if (statusVisible(candidate)) return candidate.id
                    parent = candidate.parentTaskId
                }
                return if (subtaskScope) parentTaskId else null
            }
            val children = visible.groupBy(::visibleParent)
            val result = mutableListOf<TaskListItem>()
            fun visit(parent: String?, depth: Int) {
                children[parent].orEmpty().sortedWith(comparator).forEach { task ->
                    result += TaskListItem(task, depth)
                    visit(task.id, depth + 1)
                }
            }
            visit(if (subtaskScope) parentTaskId else null, 0)
            result
        }
    }
}

internal fun descendantTaskIds(tasks: List<TaskRow>, parentTaskId: String): Set<String> {
    val children = tasks.groupBy { it.parentTaskId }
    val result = mutableSetOf<String>()
    val queue = ArrayDeque(children[parentTaskId].orEmpty())
    while (queue.isNotEmpty()) {
        val task = queue.removeFirst()
        if (result.add(task.id)) queue.addAll(children[task.id].orEmpty())
    }
    return result
}

internal fun taskComparator(mode: TaskSortMode): Comparator<TaskRow> = when (mode) {
    TaskSortMode.PRIORITY -> compareBy<TaskRow> { it.sortOrder }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    TaskSortMode.NEXT_ACTION -> compareBy<TaskRow> { it.nextActionDateEpochDay ?: Long.MAX_VALUE }
        .thenBy { it.nextActionMinuteOfDay ?: Int.MAX_VALUE }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    TaskSortMode.DEADLINE -> compareBy<TaskRow> { it.deadlineEpochMs ?: Long.MAX_VALUE }
        .thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
}

internal fun reorderedTaskIds(tasks: List<TaskRow>, id: String, targetId: String, after: Boolean): List<String> {
    require(id != targetId)
    val ordered = tasks.sortedWith(taskComparator(TaskSortMode.PRIORITY)).mapTo(mutableListOf()) { it.id }
    require(ordered.remove(id) && targetId in ordered)
    val targetIndex = ordered.indexOf(targetId)
    ordered.add((targetIndex + if (after) 1 else 0).coerceIn(0, ordered.size), id)
    return ordered
}

internal fun visibleMoveNeighbor(items: List<TaskListItem>, index: Int, mode: TaskListMode, direction: Int): TaskRow? {
    if (index !in items.indices || direction !in setOf(-1, 1)) return null
    val parentId = items[index].task.parentTaskId
    var candidate = index + direction
    while (candidate in items.indices) {
        val task = items[candidate].task
        if (mode != TaskListMode.TREE || task.parentTaskId == parentId) return task
        candidate += direction
    }
    return null
}

internal fun validPriorityTarget(source: TaskRow, target: TaskRow, mode: TaskListMode): Boolean =
    source.id != target.id && (mode != TaskListMode.TREE || source.parentTaskId == target.parentTaskId)
