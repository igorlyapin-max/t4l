package app.t4l

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerSelectionMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
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
import app.t4l.data.PaletteLayoutRow
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
    val actionByKey = actions.associateBy { it.key }
    val missingTasks = eligibleTasks.map { "t:${it.id}" }.filterNot { it in contents?.order.orEmpty() }
    LaunchedEffect(palette?.id, selection.reorderMode, missingTasks) {
        if (selection.reorderMode && palette != null && missingTasks.isNotEmpty()) vm.ensurePaletteTaskRows(palette.id, missingTasks)
    }
    val displayedRows = contents?.rows.orEmpty() + missingTasks.map { key -> PaletteLayoutRow("dynamic:$key", listOf(key)) }
    var quickAction by remember { mutableStateOf<PaletteAction?>(null) }
    var quickSaving by remember { mutableStateOf(false) }
    var quickError by remember { mutableStateOf(false) }
    var dateText by remember(effectiveDate) { mutableStateOf(effectiveDate.toString()) }
    var draggingKey by remember(palette?.id) { mutableStateOf<String?>(null) }
    var dropTarget by remember(palette?.id) { mutableStateOf<Pair<String, Int>?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val chipBounds = remember(palette?.id, displayedRows) { mutableStateMapOf<Pair<String, Int>, Rect>() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (palette == null) Text(stringResource(R.string.choose_palette_hint))
            else if (displayedRows.isEmpty()) Text(stringResource(R.string.no_palette_items))
            displayedRows.forEach { line ->
              Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 52.dp),
                  horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                line.slots.forEachIndexed { slotIndex, key ->
                    val action = key?.let(actionByKey::get)
                    val slotRef = line.id to slotIndex
                    if (action == null) {
                        Surface(Modifier.size(width = 52.dp, height = 48.dp)
                            .onGloballyPositioned { chipBounds[slotRef] = it.boundsInRoot() },
                            border = if (dropTarget == slotRef) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
                        return@forEachIndexed
                    }
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
                                quickError = false
                                quickAction = action
                            }
                        }
                    }
                    Surface(
                        Modifier.onGloballyPositioned { chipBounds[slotRef] = it.boundsInRoot() }
                            .pointerInput(palette?.id, action.key, selection.reorderMode, displayedRows) {
                                if (!selection.reorderMode || line.id.startsWith("dynamic:")) return@pointerInput
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start -> draggingKey = action.key; cursor = (chipBounds[slotRef]?.topLeft ?: Offset.Zero) + start },
                                    onDrag = { change, amount ->
                                        change.consume(); cursor += amount
                                        dropTarget = chipBounds.entries.firstOrNull { it.key != slotRef && it.value.contains(cursor) }?.key
                                    },
                                    onDragEnd = {
                                        dropTarget?.let { (targetRow, targetSlot) -> vm.movePaletteItem(palette!!.id, action.key, targetRow, targetSlot) }
                                        draggingKey = null; dropTarget = null
                                    },
                                    onDragCancel = { draggingKey = null; dropTarget = null },
                                )
                            }
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction(moveUpLabel) {
                                        val previous = displayedRows.getOrNull(displayedRows.indexOf(line) - 1)
                                        if (previous != null && !previous.id.startsWith("dynamic:")) vm.movePaletteItem(palette!!.id, action.key, previous.id, previous.slots.size)
                                        true
                                    },
                                    CustomAccessibilityAction(moveDownLabel) {
                                        val next = displayedRows.getOrNull(displayedRows.indexOf(line) + 1)
                                        if (next != null && !next.id.startsWith("dynamic:")) vm.movePaletteItem(palette!!.id, action.key, next.id, next.slots.size)
                                        true
                                    },
                                )
                            },
                        color = if (draggingKey == action.key) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (dropTarget == slotRef) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(3.dp, 36.dp), color = chipColor) {}
                            Text(
                                if (action.taskId == null) action.label else stringResource(R.string.task_prefix, action.label),
                                Modifier.clickable(enabled = !selection.reorderMode) { activate() }.padding(horizontal = 8.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (selection.reorderMode && !line.id.startsWith("dynamic:") && line.slots.all { it == null })
                    TextButton(onClick = { vm.removeEmptyPaletteRow(palette!!.id, line.id) }) { Text(stringResource(R.string.delete)) }
              }
            }
            if (selection.reorderMode && palette != null) TextButton(onClick = { vm.addPaletteRow(palette.id) }) { Text(stringResource(R.string.add_palette_row)) }
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
        val currentPlan = plan
        QuickClockDialog(
            initialTime = Instant.ofEpochMilli(requireNotNull(currentPlan).startsAtEpochMs).atZone(zone).toLocalTime(),
            error = quickError,
            saving = quickSaving,
            onCancel = { if (!quickSaving) quickAction = null },
            onSelected = { hour, minute ->
                val at = quickPlannedEpoch(effectiveDate, hour, minute, zone, currentPlan)
                if (at == null || category == null) quickError = true
                else if (!quickSaving) {
                    quickError = false
                    quickSaving = true
                    vm.addPlannedEvent(currentPlan.id, category.categoryTreeId, action.categoryId, at, action.taskId) { success ->
                        quickSaving = false
                        if (success) {
                            if (category.categoryTreeId in hiddenTreeIds) Toast.makeText(context, context.getString(R.string.event_added_hidden), Toast.LENGTH_SHORT).show()
                            quickAction = null
                        } else quickError = true
                    }
                }
            },
        )
    }
}

internal fun quickPlannedEpoch(date: LocalDate, hour: Int, minute: Int, zone: ZoneId, plan: PlanRow): Long? {
    val local = LocalDateTime.of(date, LocalTime.of(hour, minute))
    val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
    return local.toInstant(offset).toEpochMilli().takeIf { it >= plan.startsAtEpochMs && it < plan.endsAtEpochMs }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickClockDialog(initialTime: LocalTime, error: Boolean, saving: Boolean, onCancel: () -> Unit, onSelected: (Int, Int) -> Unit) {
    val picker = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = true)
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.field_time), style = MaterialTheme.typography.titleMedium)
                val confirmDescription = stringResource(R.string.quick_clock_confirm)
                Box(Modifier.testTag("quick_clock").semantics {
                    customActions = listOf(CustomAccessibilityAction(confirmDescription) {
                        if (!saving) onSelected(picker.hour, picker.minute)
                        true
                    })
                }.onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Enter && !saving) {
                        onSelected(picker.hour, picker.minute); true
                    } else false
                }.focusable().pointerInput(picker, saving) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val selectingMinutes = picker.selection == TimePickerSelectionMode.Minute
                        var released = false
                        while (!released) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            released = event.changes.none { it.pressed }
                        }
                        if (selectingMinutes && !saving) onSelected(picker.hour, picker.minute)
                    }
                }) { TimePicker(state = picker) }
                if (error) Text(stringResource(R.string.quick_time_invalid), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
            }
        }
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
