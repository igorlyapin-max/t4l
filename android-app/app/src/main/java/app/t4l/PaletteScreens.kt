package app.t4l

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import app.t4l.data.CategoryRow
import app.t4l.data.DashboardState
import app.t4l.data.PaletteContents
import app.t4l.data.validHexColor

@Composable
internal fun PaletteCollectionPane(
    state: DashboardState,
    vm: MainViewModel,
    creating: Boolean,
    active: Boolean,
    onCreatingChange: (Boolean) -> Unit,
    onContentHeight: (Int) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var colorCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var archiving by rememberSaveable { mutableStateOf(false) }
    val selected = state.palettes.firstOrNull { it.id == selectedId }
    val addCategoryDescription = stringResource(R.string.add_category)
    BackHandler(selected != null) { selectedId = null }
    androidx.compose.runtime.LaunchedEffect(selected != null) { onEditingChange(selected != null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().onSizeChanged { onContentHeight(it.height) }) {
            if (selected == null) {
                Text(stringResource(R.string.palettes_label), Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (state.palettes.isEmpty()) Text(stringResource(R.string.no_palettes), Modifier.padding(8.dp))
                state.palettes.forEach { palette ->
                    Text(palette.name, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { selectedId = palette.id }.padding(horizontal = 8.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DenseRowDivider()
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedId = null }) { Text("‹") }
                    Text(selected.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.rename)) }
                }
                val contents = PaletteContents.from(selected)
                val rowBounds = remember(selected.id) { mutableStateMapOf<String, Rect>() }
                val cellBounds = remember(selected.id) { mutableStateMapOf<Pair<String, Int>, Rect>() }
                val handleBounds = remember(selected.id) { mutableStateMapOf<String, Rect>() }
                var draggingKey by remember(selected.id) { mutableStateOf<String?>(null) }
                var dropTarget by remember(selected.id) { mutableStateOf<Pair<String, Int>?>(null) }
                var cursor by remember(selected.id) { mutableStateOf(Offset.Zero) }
                contents.rows.forEach { line ->
                    key(line.id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState())
                            .onGloballyPositioned { rowBounds[line.id] = it.boundsInRoot() }
                            .background(if (dropTarget?.first == line.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically) {
                            line.slots.forEachIndexed { slotIndex, itemKey ->
                                if (itemKey == null) {
                                    Text("—", Modifier.width(180.dp).onGloballyPositioned { cellBounds[line.id to slotIndex] = it.boundsInRoot() }
                                        .padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else key(itemKey) {
                                    val id = itemKey.removePrefix("c:")
                                    val category = state.categories.firstOrNull { it.id == id }
                                    var menu by remember(itemKey) { mutableStateOf(false) }
                                    Row(Modifier.width(180.dp).heightIn(min = 48.dp)
                                        .onGloballyPositioned { cellBounds[line.id to slotIndex] = it.boundsInRoot() }
                                        .background(if (draggingKey == itemKey) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(width = 3.dp, height = 40.dp).background(paletteCategoryColor(id, contents, state)))
                                        Text(category?.name ?: itemKey, Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.drag_to_reorder),
                                            modifier = Modifier.size(48.dp).onGloballyPositioned { handleBounds[itemKey] = it.boundsInRoot() }
                                                .pointerInput(selected.id, itemKey, contents.rows) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { start -> draggingKey = itemKey; cursor = (handleBounds[itemKey]?.topLeft ?: Offset.Zero) + start },
                                                    onDrag = { change, amount ->
                                                        change.consume(); cursor += amount
                                                        dropTarget = cellBounds.entries.firstOrNull { it.value.contains(cursor) }?.key
                                                            ?: contents.rows.firstOrNull { rowBounds[it.id]?.contains(cursor) == true }?.let { it.id to it.slots.size }
                                                    },
                                                    onDragEnd = {
                                                        dropTarget?.let { (rowId, slot) -> vm.movePaletteItem(selected.id, itemKey, rowId, slot) }
                                                        draggingKey = null; dropTarget = null
                                                    },
                                                    onDragCancel = { draggingKey = null; dropTarget = null },
                                                )
                                            })
                                        if (itemKey.startsWith("c:")) Box {
                                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu)) }
                                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.color_label)) }, onClick = { colorCategoryId = id; menu = false })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { vm.removePaletteCategory(selected.id, id); menu = false })
                                                contents.rows.forEachIndexed { index, target ->
                                                    if (target.id != line.id) DropdownMenuItem(text = { Text(stringResource(R.string.move_to_palette_row, index + 1)) },
                                                        onClick = { vm.movePaletteItem(selected.id, itemKey, target.id, target.slots.size); menu = false })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (line.slots.all { it == null }) TextButton(onClick = { vm.removeEmptyPaletteRow(selected.id, line.id) }) {
                                Text(stringResource(R.string.remove_empty_palette_row))
                            }
                        }
                        DenseRowDivider()
                    }
                }
                TextButton(onClick = { vm.addPaletteRow(selected.id) }) { Text(stringResource(R.string.add_empty_palette_row)) }
                TextButton(onClick = { archiving = true }) { Text(stringResource(R.string.move_palette_to_deleted)) }
            }
        }
    }
    if (creating && selected != null) androidx.compose.runtime.LaunchedEffect(creating, selected.id) {
        adding = true
        onCreatingChange(false)
    }
    if (creating && selected == null) PaletteNameDialog(stringResource(R.string.new_palette), "", { onCreatingChange(false) }) { name ->
        vm.createPalette(name) { selectedId = it }; onCreatingChange(false)
    }
    if (renaming && selected != null) PaletteNameDialog(stringResource(R.string.rename_palette), selected.name, { renaming = false }) { name -> vm.renamePalette(selected.id, name); renaming = false }
    if (adding && selected != null) {
        val processed = remember(selected.id, adding) { mutableStateListOf<String>() }
        val available = state.categories.filter { category -> !category.archived && category.id !in PaletteContents.from(selected).categoryColors && category.id !in processed && state.categoryTrees.any { it.id == category.categoryTreeId } }
        androidx.compose.runtime.LaunchedEffect(available.isEmpty()) { if (available.isEmpty()) adding = false }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.add_category)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.categoryTrees.forEach { tree ->
                    val treeItems = flattenVisibleCategories(state.categories.filter { it.categoryTreeId == tree.id && !it.archived }, emptySet())
                        .filter { (category, _) -> available.any { it.id == category.id } }
                    if (treeItems.isNotEmpty()) Text(tree.name, style = MaterialTheme.typography.titleSmall)
                    treeItems.forEach { (category, depth) ->
                        key(category.id) {
                        val swipeHint = stringResource(R.string.palette_swipe_hint, tree.name, category.name)
                        val skipLabel = stringResource(R.string.skip)
                        val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { direction ->
                            when (direction) {
                                SwipeToDismissBoxValue.StartToEnd -> vm.addPaletteCategory(selected.id, category.id)
                                SwipeToDismissBoxValue.EndToStart -> Unit
                                SwipeToDismissBoxValue.Settled -> return@rememberSwipeToDismissBoxState false
                            }
                            processed.add(category.id)
                            false
                        })
                        SwipeToDismissBox(state = dismiss, backgroundContent = {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp)) {
                                Text(stringResource(if (dismiss.dismissDirection == SwipeToDismissBoxValue.StartToEnd) R.string.add_category else R.string.skip))
                            }
                        }) {
                            Text(category.name, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                .heightIn(min = 48.dp).clickable { vm.addPaletteCategory(selected.id, category.id); processed.add(category.id) }
                                .padding(start = (depth * 16 + 8).dp, top = 12.dp, bottom = 12.dp)
                                .semantics {
                                    contentDescription = swipeHint
                                    customActions = listOf(CustomAccessibilityAction(skipLabel) { processed.add(category.id); true })
                                })
                        }
                        DenseRowDivider()
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.done)) } })
    }
    if (colorCategoryId != null && selected != null) {
        val id = requireNotNull(colorCategoryId)
        HexColorDialog(PaletteContents.from(selected).categoryColors[id], { colorCategoryId = null }, allowInherit = true) { hex ->
            vm.setPaletteCategoryColor(selected.id, id, hex); colorCategoryId = null
        }
    }
    if (archiving && selected != null) AlertDialog(onDismissRequest = { archiving = false }, title = { Text(stringResource(R.string.move_palette_confirmation)) },
        text = { Text(stringResource(R.string.palette_restore_hint)) }, confirmButton = { TextButton(onClick = { vm.archivePalette(selected.id); selectedId = null; archiving = false }) { Text(stringResource(R.string.move_action)) } },
        dismissButton = { TextButton(onClick = { archiving = false }) { Text(stringResource(R.string.cancel)) } })
}

internal fun movePaletteKey(order: List<String>, moving: String, target: String, after: Boolean): List<String> {
    if (moving == target || moving !in order || target !in order) return order
    val result = order.toMutableList(); result.remove(moving)
    result.add(result.indexOf(target) + if (after) 1 else 0, moving)
    return result
}

internal fun paletteCategoryColor(categoryId: String, contents: PaletteContents, state: DashboardState): Color {
    val byId = state.categories.associateBy { it.id }
    val visited = mutableSetOf<String>()
    var category: CategoryRow? = byId[categoryId]
    while (category != null && visited.add(category.id)) {
        contents.categoryColors[category.id]?.let { return hexToColor(it) }
        if (category.parentId == null) break
        category = byId[category.parentId]
    }
    val treeId = byId[categoryId]?.categoryTreeId
    return state.treeAppearances.firstOrNull { it.categoryTreeId == treeId }?.colorHex?.let(::hexToColor) ?: Color.Gray
}

internal fun hexToColor(value: String): Color = runCatching { Color(value.toColorInt()) }.getOrDefault(Color.Gray)

@Composable
private fun PaletteNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && name.length <= 120, onClick = { onSave(name.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HexColorDialog(initial: String?, onDismiss: () -> Unit, allowInherit: Boolean = false, onSave: (String?) -> Unit) {
    var value by rememberSaveable(initial) { mutableStateOf(initial ?: "#808080") }
    val swatches = listOf("#D32F2F", "#F57C00", "#FBC02D", "#388E3C", "#1976D2", "#7B1FA2", "#455A64")
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.color_label)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 4) {
            swatches.forEach { swatch -> Surface(
                Modifier.size(48.dp).selectable(selected = value.equals(swatch, ignoreCase = true), role = Role.RadioButton,
                    onClick = { value = swatch }).semantics { contentDescription = swatch },
                color = hexToColor(swatch)) {} }
        }
        OutlinedTextField(value, { value = it }, label = { Text("#RRGGBB") }, singleLine = true)
        if (allowInherit) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.inherited_color)) }
    } }, confirmButton = { TextButton(enabled = validHexColor(value), onClick = { onSave(value.uppercase()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
