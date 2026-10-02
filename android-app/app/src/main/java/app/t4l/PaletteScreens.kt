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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
                    TextButton(onClick = { adding = true }, modifier = Modifier.semantics { contentDescription = addCategoryDescription }) { Text("+") }
                }
                val contents = PaletteContents.from(selected)
                val ids = (contents.order.filter { it.startsWith("c:") }.map { it.drop(2) } + contents.categoryColors.keys)
                    .distinct().filter { it in contents.categoryColors }
                val rowBounds = remember(selected.id, ids) { mutableStateMapOf<String, Rect>() }
                var draggingId by remember(selected.id) { mutableStateOf<String?>(null) }
                var dropTarget by remember(selected.id) { mutableStateOf<Pair<String, Boolean>?>(null) }
                ids.forEachIndexed { index, id ->
                    val category = state.categories.firstOrNull { it.id == id }
                    val tree = state.historicalCategoryTrees.firstOrNull { it.id == category?.categoryTreeId }
                    var menu by remember(id) { mutableStateOf(false) }
                    var cursor by remember(id) { mutableStateOf(Offset.Zero) }
                    if (dropTarget == (id to false)) androidx.compose.material3.HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .onGloballyPositioned { rowBounds[id] = it.boundsInRoot() }
                        .background(if (draggingId == id) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 3.dp, height = 40.dp).background(paletteCategoryColor(id, contents, state)))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(category?.name ?: stringResource(R.string.unknown_category), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tree?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.drag_to_reorder), modifier = Modifier.size(48.dp).pointerInput(selected.id, id, ids) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start -> draggingId = id; cursor = (rowBounds[id]?.topLeft ?: Offset.Zero) + start },
                                onDrag = { change, amount ->
                                    change.consume(); cursor += amount
                                    val target = rowBounds.entries.firstOrNull { it.key != id && it.value.contains(cursor) }
                                    dropTarget = target?.let { it.key to (cursor.y >= it.value.center.y) }
                                },
                                onDragEnd = {
                                    dropTarget?.let { (targetId, after) -> vm.reorderPalette(selected.id, movePaletteKey(contents.order, "c:$id", "c:$targetId", after)) }
                                    draggingId = null; dropTarget = null
                                },
                                onDragCancel = { draggingId = null; dropTarget = null },
                            )
                        })
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, enabled = index > 0, onClick = {
                                    vm.reorderPalette(selected.id, movePaletteKey(contents.order, "c:$id", "c:${ids[index - 1]}", false)); menu = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, enabled = index < ids.lastIndex, onClick = {
                                    vm.reorderPalette(selected.id, movePaletteKey(contents.order, "c:$id", "c:${ids[index + 1]}", true)); menu = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.color_label)) }, onClick = { colorCategoryId = id; menu = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { vm.removePaletteCategory(selected.id, id); menu = false })
                            }
                        }
                    }
                    if (dropTarget == (id to true)) androidx.compose.material3.HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
                    DenseRowDivider()
                }
                TextButton(onClick = { archiving = true }) { Text(stringResource(R.string.move_palette_to_deleted)) }
            }
        }
    }
    if (creating) PaletteNameDialog(stringResource(R.string.new_palette), "", { onCreatingChange(false) }) { name ->
        vm.createPalette(name) { selectedId = it }; onCreatingChange(false)
    }
    if (renaming && selected != null) PaletteNameDialog(stringResource(R.string.rename_palette), selected.name, { renaming = false }) { name -> vm.renamePalette(selected.id, name); renaming = false }
    if (adding && selected != null) {
        val available = state.categories.filter { category -> !category.archived && category.id !in PaletteContents.from(selected).categoryColors && state.categoryTrees.any { it.id == category.categoryTreeId } }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.add_category)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { available.forEach { category ->
                TextButton(onClick = { vm.addPaletteCategory(selected.id, category.id); adding = false }) {
                    Text("${category.name} (${state.categoryTrees.firstOrNull { it.id == category.categoryTreeId }?.name.orEmpty()})")
                }
            } }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } })
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
