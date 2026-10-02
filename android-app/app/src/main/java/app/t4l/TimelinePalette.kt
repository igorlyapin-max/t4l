package app.t4l

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.t4l.data.DashboardState
import app.t4l.data.PaletteContents
import app.t4l.data.PlanRow
import app.t4l.data.TaskRow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay

private data class PaletteAction(val key: String, val categoryId: String, val taskId: String?, val label: String)

internal class PaletteSelectionState(private val workspaceId: String, private val planId: String?, private val store: TimelineUiStore) {
    var reorderMode by mutableStateOf(false)
    var sharedId by mutableStateOf(store.sharedPalette(workspaceId))
        private set
    var overrideId by mutableStateOf(planId?.let { store.planPalette(workspaceId, it) })
        private set
    var useShared by mutableStateOf(overrideId == null)
        private set
    val selectedId: String? get() = if (planId != null && !useShared) overrideId else sharedId

    fun select(id: String) {
        if (planId != null && !useShared) {
            overrideId = id
            store.setPlanPalette(workspaceId, planId, id)
        } else {
            sharedId = id
            store.setSharedPalette(workspaceId, id)
        }
    }

    fun setShared(checked: Boolean) {
        useShared = checked
        if (checked) {
            overrideId = null
            planId?.let { store.setPlanPalette(workspaceId, it, null) }
        } else if (overrideId == null) {
            overrideId = sharedId
            planId?.let { store.setPlanPalette(workspaceId, it, sharedId) }
        }
    }
}

@Composable
internal fun rememberPaletteSelection(workspaceId: String, planId: String?, store: TimelineUiStore): PaletteSelectionState =
    remember(workspaceId, planId) { PaletteSelectionState(workspaceId, planId, store) }

@Composable
internal fun TimelinePaletteHeader(state: DashboardState, selection: PaletteSelectionState) {
    var menu by remember { mutableStateOf(false) }
    val reorderLabel = stringResource(R.string.reorder_palette)
    Row(verticalAlignment = Alignment.CenterVertically) {
    Box {
        TextButton(onClick = { menu = true }) { Text(state.palettes.firstOrNull { it.id == selection.selectedId }?.name ?: stringResource(R.string.select_palette), maxLines = 1) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            state.palettes.forEach { choice -> DropdownMenuItem(text = { Text(choice.name) }, onClick = { selection.select(choice.id); menu = false }) }
        }
    }
    IconButton(onClick = { selection.reorderMode = !selection.reorderMode }, modifier = Modifier.semantics { contentDescription = reorderLabel }) {
        Icon(Icons.Default.DragHandle, contentDescription = null, tint = if (selection.reorderMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimelinePalette(
    state: DashboardState,
    tasks: List<TaskRow>,
    vm: MainViewModel,
    selection: PaletteSelectionState,
    plan: PlanRow? = null,
    hiddenTreeIds: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    val workspaceId = vm.selectedWorkspace()
    val store = vm.timelineUiStore
    val palette = state.palettes.firstOrNull { it.id == selection.selectedId }
    val dateKey = plan?.id ?: "history"
    val zone = ZoneId.systemDefault()
    val planStartDate = plan?.let { Instant.ofEpochMilli(it.startsAtEpochMs).atZone(zone).toLocalDate() }
    var selectedEpochDay by remember(workspaceId, dateKey) { mutableStateOf(store.paletteDate(workspaceId, dateKey)) }
    var today by remember(zone) { mutableStateOf(LocalDate.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            delay(30_000)
            today = LocalDate.now(zone)
        }
    }
    val effectiveDate = selectedEpochDay?.let(LocalDate::ofEpochDay) ?: planStartDate ?: today
    LaunchedEffect(today, selectedEpochDay, plan?.id) {
        if (plan == null && selectedEpochDay != null && !LocalDate.ofEpochDay(requireNotNull(selectedEpochDay)).isBefore(today)) {
            selectedEpochDay = null
            store.setPaletteDate(workspaceId, dateKey, null)
        }
    }
    LaunchedEffect(plan?.startsAtEpochMs, plan?.endsAtEpochMs, selectedEpochDay) {
        if (plan != null && (effectiveDate.isBefore(planStartDate) ||
                !effectiveDate.atStartOfDay(zone).toInstant().toEpochMilli().let { it < plan.endsAtEpochMs })) {
            selectedEpochDay = null
            store.setPaletteDate(workspaceId, dateKey, null)
        }
    }
    val contents = palette?.let(PaletteContents::from)
    val taskById = remember(tasks) { tasks.associateBy { it.id } }
    val availableCategories = state.categories.filter { category ->
        !category.archived && category.id in (contents?.categoryColors ?: emptyMap()) &&
            state.categoryTrees.any { it.id == category.categoryTreeId }
    }
    val availableCategoryIds = availableCategories.mapTo(mutableSetOf()) { it.id }
    val eligibleTasks = tasks.filter { task ->
        task.status == "active" && task.nextActionDateEpochDay == effectiveDate.toEpochDay() &&
            effectiveTaskCategoryForPalette(task, taskById) in availableCategoryIds
    }
    val actions = availableCategories.map { PaletteAction("c:${it.id}", it.id, null, it.name) } +
        eligibleTasks.mapNotNull { task -> effectiveTaskCategoryForPalette(task, taskById)?.let { PaletteAction("t:${task.id}", it, task.id, task.title) } }
    val fullOrder = (contents?.order.orEmpty() + actions.map { it.key }).distinct()
    val sorted = actions.sortedWith(compareBy({ fullOrder.indexOf(it.key) }, { it.key }))
    var quickAction by remember { mutableStateOf<PaletteAction?>(null) }
    var chosenTime by rememberSaveable { mutableStateOf("09:00") }
    var dateText by remember(effectiveDate) { mutableStateOf(effectiveDate.toString()) }
    var draggingKey by remember(palette?.id) { mutableStateOf<String?>(null) }
    var dropTarget by remember(palette?.id) { mutableStateOf<Pair<String, Boolean>?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selection.useShared, onCheckedChange = selection::setShared)
            Text(stringResource(R.string.use_shared_palette))
        }
        val chipBounds = remember(palette?.id, sorted.map { it.key }) { mutableStateMapOf<String, Rect>() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (palette == null) Text(stringResource(R.string.choose_palette_hint))
            else if (sorted.isEmpty()) Text(stringResource(R.string.no_palette_items))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sorted.forEachIndexed { index, action ->
                    val treeId = state.categories.firstOrNull { it.id == action.categoryId }?.categoryTreeId
                    val moveUpLabel = stringResource(R.string.move_up)
                    val moveDownLabel = stringResource(R.string.move_down)
                    val chipColor = paletteCategoryColor(action.categoryId, requireNotNull(contents), state)
                    var cursor by remember(action.key) { mutableStateOf(Offset.Zero) }
                    val activate = {
                        if (treeId != null) {
                            if (plan == null) {
                                val time = LocalTime.now(zone)
                                val at = LocalDateTime.of(if (selectedEpochDay == null) LocalDate.now(zone) else effectiveDate, time)
                                    .atZone(zone).toInstant().toEpochMilli()
                                vm.addEvent(treeId, action.categoryId, at, action.taskId) { success ->
                                    if (success) Toast.makeText(context, if (treeId in hiddenTreeIds) context.getString(R.string.event_added_hidden) else context.getString(R.string.event_added), Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                chosenTime = Instant.ofEpochMilli(plan.startsAtEpochMs).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()
                                quickAction = action
                            }
                        }
                    }
                    Surface(
                        Modifier.onGloballyPositioned { chipBounds[action.key] = it.boundsInRoot() }
                            .pointerInput(palette?.id, action.key, sorted) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start -> draggingKey = action.key; cursor = (chipBounds[action.key]?.topLeft ?: Offset.Zero) + start },
                                    onDrag = { change, amount ->
                                        change.consume(); cursor += amount
                                        val target = chipBounds.entries.firstOrNull { it.key != action.key && it.value.contains(cursor) }
                                        dropTarget = target?.let { it.key to (cursor.x > it.value.center.x || cursor.y > it.value.center.y) }
                                    },
                                    onDragEnd = {
                                        dropTarget?.let { (target, after) -> vm.reorderPalette(palette!!.id, movePaletteKey(fullOrder, action.key, target, after)) }
                                        draggingKey = null; dropTarget = null
                                    },
                                    onDragCancel = { draggingKey = null; dropTarget = null },
                                )
                            }
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction(moveUpLabel) {
                                        if (index > 0) vm.reorderPalette(palette!!.id, movePaletteKey(fullOrder, action.key, sorted[index - 1].key, false)); true
                                    },
                                    CustomAccessibilityAction(moveDownLabel) {
                                        if (index < sorted.lastIndex) vm.reorderPalette(palette!!.id, movePaletteKey(fullOrder, action.key, sorted[index + 1].key, true)); true
                                    },
                                )
                            },
                        color = if (draggingKey == action.key) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (dropTarget?.first == action.key) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (dropTarget == (action.key to false)) Text("‹", color = MaterialTheme.colorScheme.primary)
                            Surface(Modifier.size(3.dp, 36.dp), color = chipColor) {}
                            Text(
                                if (action.taskId == null) action.label else stringResource(R.string.task_prefix, action.label),
                                Modifier.clickable(enabled = !selection.reorderMode) { activate() }.padding(horizontal = 8.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (selection.reorderMode) {
                                IconButton(onClick = { if (index > 0) vm.reorderPalette(palette!!.id, movePaletteKey(fullOrder, action.key, sorted[index - 1].key, false)) }, modifier = Modifier.semantics { contentDescription = moveUpLabel }) { Text("↑") }
                                IconButton(onClick = { if (index < sorted.lastIndex) vm.reorderPalette(palette!!.id, movePaletteKey(fullOrder, action.key, sorted[index + 1].key, true)) }, modifier = Modifier.semantics { contentDescription = moveDownLabel }) { Text("↓") }
                            }
                            if (dropTarget == (action.key to true)) Text("›", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (plan == null && selectedEpochDay != null) TextButton(onClick = { selectedEpochDay = null; store.setPaletteDate(workspaceId, dateKey, null) }) { Text(stringResource(R.string.palette_now)) }
            DateField(dateText, stringResource(R.string.palette_date), { text ->
                dateText = text
                val date = runCatching { LocalDate.parse(text) }.getOrNull()
                val valid = date != null && if (plan == null) !date.isAfter(today) else {
                    val from = requireNotNull(planStartDate)
                    !date.isBefore(from) && date.atStartOfDay(zone).toInstant().toEpochMilli() < plan.endsAtEpochMs
                }
                if (valid) {
                    selectedEpochDay = if (plan == null && date == today) null else date!!.toEpochDay()
                    store.setPaletteDate(workspaceId, dateKey, selectedEpochDay)
                }
            }, invalid = runCatching { LocalDate.parse(dateText) }.getOrNull()?.let { date ->
                if (plan == null) date.isAfter(today) else date.isBefore(requireNotNull(planStartDate)) ||
                    date.atStartOfDay(zone).toInstant().toEpochMilli() >= plan.endsAtEpochMs
            } ?: true, maxDate = if (plan == null) today.minusDays(1) else Instant.ofEpochMilli(plan.endsAtEpochMs - 1).atZone(zone).toLocalDate())
        }
    }
    quickAction?.let { action ->
        val category = state.categories.firstOrNull { it.id == action.categoryId }
        val selectedTime = runCatching { LocalTime.parse(chosenTime) }.getOrNull()
        val at = selectedTime?.let { LocalDateTime.of(effectiveDate, it).atZone(zone).toInstant().toEpochMilli() }
        AlertDialog(onDismissRequest = { quickAction = null }, title = { Text(stringResource(R.string.add_planned_event)) }, text = {
            TimeField(chosenTime, stringResource(R.string.field_time), { chosenTime = it }, invalid = at == null || plan == null || at < plan.startsAtEpochMs || at >= plan.endsAtEpochMs)
        }, confirmButton = { TextButton(enabled = plan != null && category != null && at != null && at >= plan.startsAtEpochMs && at < plan.endsAtEpochMs, onClick = {
            vm.addPlannedEvent(requireNotNull(plan).id, requireNotNull(category).categoryTreeId, action.categoryId, requireNotNull(at), action.taskId) { success ->
                if (success) {
                    if (category.categoryTreeId in hiddenTreeIds) Toast.makeText(context, context.getString(R.string.event_added_hidden), Toast.LENGTH_SHORT).show()
                    quickAction = null
                }
            }
        }) { Text(stringResource(R.string.add_event)) } }, dismissButton = { TextButton(onClick = { quickAction = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

private fun effectiveTaskCategoryForPalette(task: TaskRow, byId: Map<String, TaskRow>): String? {
    val visited = mutableSetOf<String>()
    var current: TaskRow? = task
    while (current != null && visited.add(current.id)) {
        current.categoryId?.let { return it }
        current = current.parentTaskId?.let(byId::get)
    }
    return null
}
