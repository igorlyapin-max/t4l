package app.t4l

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.t4l.data.CategoryTreeRow
import kotlin.math.abs

internal class TimelinePanelState(fraction: Float, collapsed: Boolean) {
    var fraction by mutableFloatStateOf(fraction)
    var collapsed by mutableStateOf(collapsed)
}

@Composable
internal fun rememberTimelinePanelState(workspaceId: String, screen: String, store: TimelineUiStore): TimelinePanelState =
    remember(workspaceId, screen) { TimelinePanelState(store.panelFraction(workspaceId, screen), store.panelCollapsed(workspaceId, screen)) }

internal fun timelinePanelHeight(maxHeight: Dp, state: TimelinePanelState): Dp {
    if (state.collapsed) return 48.dp
    val available = (maxHeight - 72.dp).coerceAtLeast(0.dp)
    return (maxHeight * state.fraction).coerceIn(128.dp.coerceAtMost(available), available)
}

internal fun adjustTimelinePanelFraction(state: TimelinePanelState, deltaPx: Float, availablePx: Float, minPanelPx: Float, minTimelinePx: Float): Float {
    val maxPanel = (availablePx - minTimelinePx).coerceAtLeast(0f)
    val minPanel = minPanelPx.coerceAtMost(maxPanel)
    state.fraction = ((availablePx * state.fraction - deltaPx).coerceIn(minPanel, maxPanel) / availablePx.coerceAtLeast(1f)).coerceIn(0.1f, 0.9f)
    return state.fraction
}

@Composable
internal fun TimelineBottomPanel(
    workspaceId: String,
    screenKey: String,
    maxHeight: Dp,
    store: TimelineUiStore,
    panelState: TimelinePanelState,
    trees: List<CategoryTreeRow>,
    hiddenTreeIds: Set<String>,
    onHiddenTreeIds: (Set<String>) -> Unit,
    hideTreeNames: Boolean,
    onHideTreeNames: (Boolean) -> Unit,
    distributionLabel: Int = R.string.distribution_label,
    distributionActions: @Composable () -> Unit = {},
    paletteHeader: @Composable () -> Unit = {},
    localSettings: @Composable () -> Unit = {},
    distribution: @Composable () -> Unit,
    palette: (@Composable () -> Unit)? = null,
    allowContentSwipe: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable(workspaceId, screenKey) { mutableIntStateOf(0) }
    val tabs = if (palette == null) listOf(0, 2) else listOf(0, 1, 2)
    val height = timelinePanelHeight(maxHeight, panelState)
    val minHeight = 128.dp.coerceAtMost((maxHeight - 72.dp).coerceAtLeast(0.dp))
    val maximumHeight = (maxHeight - 72.dp).coerceAtLeast(minHeight)
    val density = LocalDensity.current
    val settingsDescription = stringResource(R.string.local_settings)
    val expandDescription = stringResource(R.string.expand)
    val collapseDescription = stringResource(R.string.collapse)
    fun changeHeight(deltaPx: Float) {
        val availablePx = with(density) { maxHeight.toPx() }
        if (availablePx <= 0f) return
        val proposed = availablePx * panelState.fraction - deltaPx
        val bounded = proposed.coerceIn(with(density) { minHeight.toPx() }, with(density) { maximumHeight.toPx() })
        panelState.fraction = (bounded / availablePx).coerceIn(0.1f, 0.9f)
    }
    fun commitHeight() { store.setPanelFraction(workspaceId, screenKey, panelState.fraction) }
    Surface(modifier.fillMaxWidth().height(height), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            if (!panelState.collapsed) VerticalSplitHandle(
                enabled = maximumHeight > minHeight,
                fraction = panelState.fraction,
                stateLabel = stringResource(R.string.panel_height),
                growLabel = expandDescription,
                shrinkLabel = collapseDescription,
                onDrag = ::changeHeight,
                onDragEnd = ::commitHeight,
                onStep = { step -> changeHeight(-with(density) { (maxHeight * step).toPx() }); commitHeight() },
            )
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { tab = 0 }) { Text((if (tab == 0) "• " else "") + stringResource(distributionLabel)) }
                    if (palette != null) TextButton(onClick = { tab = 1 }) { Text((if (tab == 1) "• " else "") + stringResource(R.string.palette_label)) }
                    TextButton(onClick = { tab = 2 }, modifier = Modifier.semantics { contentDescription = settingsDescription }) { Text(if (tab == 2) "• ⚙" else "⚙") }
                    if (tab == 1 && palette != null) paletteHeader()
                }
                if (tab == 0 && !panelState.collapsed) distributionActions()
                IconButton(onClick = {
                    panelState.collapsed = !panelState.collapsed
                    store.setPanelCollapsed(workspaceId, screenKey, panelState.collapsed)
                }, modifier = Modifier.semantics { contentDescription = if (panelState.collapsed) expandDescription else collapseDescription }) {
                    Text(if (panelState.collapsed) "⌃" else "⌄", style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (!panelState.collapsed) {
                HorizontalDivider()
                Box(Modifier.fillMaxSize().pointerInput(tab, tabs, allowContentSwipe) {
                    if (!allowContentSwipe && tab == 1) return@pointerInput
                    var swipeDistance = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount -> swipeDistance += amount; change.consume() },
                        onDragEnd = {
                            if (abs(swipeDistance) > 64.dp.toPx()) {
                                val index = tabs.indexOf(tab)
                                tab = tabs[(index + if (swipeDistance < 0f) 1 else -1).coerceIn(0, tabs.lastIndex)]
                            }
                            swipeDistance = 0f
                        },
                        onDragCancel = { swipeDistance = 0f },
                    )
                }) {
                    if (tab == 1 && palette != null) Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) { palette() }
                    else Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        when (tab) {
                            0 -> distribution()
                            2 -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                localSettings()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = hideTreeNames, onCheckedChange = onHideTreeNames)
                                    Text(stringResource(R.string.hide_tree_names))
                                }
                                trees.forEach { tree ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = tree.id !in hiddenTreeIds, onCheckedChange = { checked ->
                                            onHiddenTreeIds(if (checked) hiddenTreeIds - tree.id else hiddenTreeIds + tree.id)
                                        })
                                        Text(tree.name)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
