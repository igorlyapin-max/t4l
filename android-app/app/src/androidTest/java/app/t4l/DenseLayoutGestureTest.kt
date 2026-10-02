package app.t4l

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DenseLayoutGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun edgePullCommitsSplitForNextScreenInstance() {
        val workspaceId = UUID.randomUUID().toString()
        val store = TimelineUiStore(compose.activity)
        var split = 0.42f
        lateinit var connection: NestedScrollConnection
        compose.setContent {
            connection = rememberEdgeBoundaryConnection(-1, true, { split = 0.62f },
                { store.setCategorizationSplit(workspaceId, split) })
        }
        compose.runOnIdle {
            connection.onPostScroll(Offset.Zero, Offset(0f, -100f), NestedScrollSource.UserInput)
            runBlocking { connection.onPostFling(Velocity.Zero, Velocity.Zero) }
            assertEquals(0.62f, TimelineUiStore(compose.activity).categorizationSplit(workspaceId), 0.001f)
        }
    }
}
