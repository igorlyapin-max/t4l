package app.t4l

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

internal fun boundedSplitPx(requested: Float, available: Float, minimumFirst: Float, minimumSecond: Float): Float {
    val usable = available.coerceAtLeast(0f)
    val first = minimumFirst.coerceAtMost(usable / 2f)
    val second = minimumSecond.coerceAtMost(usable - first)
    return requested.coerceIn(first, usable - second)
}

/** Transfers only unconsumed edge scrolling to the divider, after a deliberate second pull. */
@Composable
internal fun rememberEdgeBoundaryConnection(direction: Int, enabled: Boolean, onDelta: (Float) -> Unit, onEnd: () -> Unit = {}): NestedScrollConnection {
    val callback by rememberUpdatedState(onDelta)
    val endCallback by rememberUpdatedState(onEnd)
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    return remember(direction, enabled, threshold) {
        object : NestedScrollConnection {
            private var overscroll = 0f
            private var moved = false

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val amount = available.y
                if (!enabled || source != NestedScrollSource.UserInput || amount * direction <= 0f) {
                    overscroll = 0f
                    return Offset.Zero
                }
                overscroll += amount
                if (abs(overscroll) < threshold) return Offset.Zero
                callback(amount)
                moved = true
                return Offset(0f, amount)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                overscroll = 0f
                if (moved) endCallback()
                moved = false
                return Velocity.Zero
            }
        }
    }
}

/** Allows a second pull past either timeline edge to move the attached bottom panel. */
@Composable
internal fun rememberTimelineBoundaryConnection(enabled: Boolean, onDelta: (Float) -> Unit): NestedScrollConnection {
    val callback by rememberUpdatedState(onDelta)
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    return remember(enabled, threshold) {
        object : NestedScrollConnection {
            private var overscroll = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val amount = available.y
                if (!enabled || source != NestedScrollSource.UserInput || amount == 0f) {
                    overscroll = 0f
                    return Offset.Zero
                }
                if (overscroll * amount < 0f) overscroll = 0f
                overscroll += amount
                if (abs(overscroll) < threshold) return Offset.Zero
                callback(amount)
                return Offset(0f, amount)
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                overscroll = 0f
                return Velocity.Zero
            }
        }
    }
}

@Composable
internal fun DenseRowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
}

@Composable
internal fun VerticalSplitHandle(
    enabled: Boolean,
    fraction: Float,
    stateLabel: String,
    growLabel: String,
    shrinkLabel: String,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onStep: (Float) -> Unit,
) {
    val dragState = rememberDraggableState { delta -> onDrag(delta) }
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .draggable(dragState, Orientation.Vertical, enabled = enabled, onDragStopped = { onDragEnd() })
            .semantics {
                contentDescription = stateLabel
                stateDescription = "$stateLabel ${(fraction * 100).toInt()}%"
                customActions = listOf(
                    CustomAccessibilityAction(growLabel) { onStep(0.1f); true },
                    CustomAccessibilityAction(shrinkLabel) { onStep(-0.1f); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(Modifier.width(44.dp), thickness = 3.dp, color = MaterialTheme.colorScheme.outline)
    }
}
