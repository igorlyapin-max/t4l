package app.t4l

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.t4l.data.CategoryRow
import app.t4l.data.CategoryPlacement
import app.t4l.data.DashboardState
import app.t4l.data.EventRow
import app.t4l.data.PlanRow
import app.t4l.data.PlannerState
import app.t4l.data.TaskRow
import app.t4l.data.TaskState
import app.t4l.data.SyncPhase
import app.t4l.data.SyncUiState
import app.t4l.data.PersonalConflictRow
import app.t4l.data.ConflictRow
import app.t4l.ui.theme.T4LTheme
import app.t4l.domain.BudgetEngine
import app.t4l.domain.CategoryNode
import app.t4l.domain.EventPoint
import app.t4l.domain.TimeEngine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {
    private var contentShown = false
    private var authenticationRunning = false
    private var needsUnlock = false
    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (application as T4LApplication).authManager.completeAuthorization(result.data) { success, error ->
            runOnUiThread {
                if (success) {
                    (application as T4LApplication).profileRepository.markActorUnresolved()
                    (application as T4LApplication).syncScheduler.syncNow()
                    continueStartup()
                } else { Toast.makeText(this, error ?: "OIDC authorization failed", Toast.LENGTH_LONG).show(); finish() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = (application as T4LApplication).authManager
        if (auth.enabled && !auth.authorized) {
            auth.authorizationIntent { intent, error -> runOnUiThread {
                if (intent != null) authLauncher.launch(intent) else { Toast.makeText(this, error ?: "OIDC discovery failed", Toast.LENGTH_LONG).show(); finish() }
            } }
            return
        }
        continueStartup()
    }

    private fun continueStartup() {
        if (!(application as T4LApplication).appLock.enabled) showApp() else authenticate()
    }

    override fun onStart() {
        super.onStart()
        val app = application as T4LApplication
        if (contentShown && needsUnlock && app.authManager.authorized && app.appLock.enabled) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && contentShown) needsUnlock = true
    }

    private fun authenticate() {
        if (authenticationRunning) return
        authenticationRunning = true
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { authenticationRunning = false; needsUnlock = false; showApp() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish()
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.app_lock_prompt)).setAllowedAuthenticators(AppLockSettings.AUTHENTICATORS).build())
    }
    private fun showApp() { if (contentShown) return; contentShown = true; setContent { T4LTheme { T4LRoot() } } }
}

private enum class Screen(val route: String, val label: Int, val menuItem: Boolean = true) {
    HOME("home", R.string.home), TRACK("track", R.string.track), HISTORY("history", R.string.history),
    PLANNER("planner", R.string.planner), TASKS("tasks", R.string.tasks), REPORTS("reports", R.string.reports), SETTINGS("settings", R.string.settings),
    TASK_DETAILS("tasks/{taskId}", R.string.task, false),
    CATEGORY_TREE("track/{treeId}", R.string.category_tree, false),
    PROFILE("profile", R.string.profile, false), LIFE("life", R.string.life_visualization, false),
    NETWORK("settings/network", R.string.network, false), TIMER_SETTINGS("settings/tomato", R.string.pomodoro_timer, false),
    BACKUP("settings/backup", R.string.backup, false),
    SECURITY("settings/security", R.string.security, false), DIAGNOSTICS("settings/diagnostics", R.string.diagnostics, false),
    SYNC_ISSUES("settings/diagnostics/sync-issues", R.string.sync_issues, false), LANGUAGE("settings/language", R.string.language, false),
}
private enum class SettingsSection { ROOT, NETWORK, BACKUP, SECURITY, DIAGNOSTICS, SYNC_ISSUES, LANGUAGE }
private enum class PasswordPurpose { EXPORT, IMPORT }
private enum class ConflictChoice { SERVER, LOCAL }
private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal fun timelineLocalDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

@Composable
private fun TimelineDateHeader(date: LocalDate, planName: String? = null) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val label = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(if (planName == null) label else "$planName · $label", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
    }
}
private data class TaskDraft(val title: String, val categoryId: String?, val estimate: Int, val nextDate: LocalDate, val nextMinute: Int?, val deadline: Long?)
private fun taskRoute(taskId: String): String = "tasks/${Uri.encode(taskId)}"
private fun categoryTreeRoute(treeId: String): String = "track/${Uri.encode(treeId)}"

@Composable
internal fun ScreenHeading(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun T4LRoot(viewModel: MainViewModel = viewModel()) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val taskState by viewModel.taskState.collectAsStateWithLifecycle()
    val plannerState by viewModel.plannerState.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val personalConflicts by viewModel.personalConflicts.collectAsStateWithLifecycle()
    val profileActorResolved by viewModel.profileActorResolved.collectAsStateWithLifecycle()
    val pomodoro by viewModel.pomodoro.collectAsStateWithLifecycle()
    val taskListSettings by viewModel.taskListSettings.collectAsStateWithLifecycle()
    val feedback by viewModel.uiFeedback.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val screen = Screen.entries.firstOrNull { it.route == entry?.destination?.route } ?: Screen.HOME
    var menu by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val openTask: (String) -> Unit = { taskId -> navController.navigate(taskRoute(taskId)) { launchSingleTop = true } }
    val openLife: () -> Unit = {
        navController.navigate(if (profile?.birthDateEpochDay != null && profile?.lifeExpectancyYears != null) Screen.LIFE.route else Screen.PROFILE.route) {
            launchSingleTop = true
        }
    }
    feedback?.let { item ->
        val message = feedbackText(item)
        LaunchedEffect(item.id) { snackbar.showSnackbar(message); viewModel.consumeFeedback(item.id) }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(navigationIcon = {
            if (!screen.menuItem) TextButton(onClick = { navController.popBackStack() }) { Text(stringResource(R.string.back)) }
            else TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { navController.navigate(Screen.PROFILE.route) }) {
                ProfileAvatar(profile, Modifier.size(40.dp), stringResource(R.string.open_profile))
            }
        }, title = {
            HeaderLifeCountdown(profile, openLife, Modifier.fillMaxWidth())
        }, actions = {
            SyncStatusIndicator(syncState, personalConflicts.size) {
                navController.navigate(Screen.DIAGNOSTICS.route) { launchSingleTop = true }
            }
            if (screen.menuItem) {
            Box {
                IconButton(modifier = Modifier.size(48.dp), onClick = { menu = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.menu))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Screen.entries.filter { it.menuItem }.forEach { item -> DropdownMenuItem(text = { Text((if (item == screen) "✓ " else "") + stringResource(item.label)) }, onClick = {
                        navController.navigate(item.route) { popUpTo(Screen.HOME.route) { saveState = true }; launchSingleTop = true; restoreState = true }
                        menu = false
                    }) }
                }
            }
            }
        })
    }) { padding ->
        NavHost(navController, startDestination = Screen.HOME.route) {
            composable(Screen.HOME.route) { HomeScreen(pomodoro, padding) }
            composable(Screen.TRACK.route) {
                TrackScreen(dashboard, viewModel, padding) { treeId ->
                    navController.navigate(categoryTreeRoute(treeId)) { launchSingleTop = true }
                }
            }
            composable(Screen.CATEGORY_TREE.route, arguments = listOf(navArgument("treeId") { type = NavType.StringType })) { destination ->
                CategoryTreeEditorScreen(
                    treeId = destination.arguments?.getString("treeId").orEmpty(),
                    state = dashboard,
                    vm = viewModel,
                    padding = padding,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Screen.HISTORY.route) { HistoryScreen(dashboard, taskState, viewModel, padding, openTask) }
            composable(Screen.PLANNER.route) { PlannerScreen(dashboard, taskState, plannerState, viewModel, padding, openTask) }
            composable(Screen.TASKS.route) { TasksScreen(dashboard, taskState, taskListSettings, viewModel, padding, openTask) }
            composable(Screen.TASK_DETAILS.route, arguments = listOf(navArgument("taskId") { type = NavType.StringType })) { destination ->
                TaskDetailsScreen(destination.arguments?.getString("taskId").orEmpty(), dashboard, taskState, taskListSettings, viewModel, padding, openTask)
            }
            composable(Screen.REPORTS.route) { ReportsScreen(dashboard, viewModel, padding) }
            composable(Screen.SETTINGS.route) { SettingsRoot(padding) { navController.navigate(it.route) } }
            composable(Screen.NETWORK.route) { SettingsScreen(SettingsSection.NETWORK, viewModel, syncState, personalConflicts, dashboard, taskState, plannerState, workspaces, padding) { navController.navigate(it.route) } }
            composable(Screen.TIMER_SETTINGS.route) { PomodoroSettingsScreen(pomodoro, viewModel, padding) }
            composable(Screen.BACKUP.route) { SettingsScreen(SettingsSection.BACKUP, viewModel, syncState, personalConflicts, dashboard, taskState, plannerState, workspaces, padding) { navController.navigate(it.route) } }
            composable(Screen.SECURITY.route) { SettingsScreen(SettingsSection.SECURITY, viewModel, syncState, personalConflicts, dashboard, taskState, plannerState, workspaces, padding) { navController.navigate(it.route) } }
            composable(Screen.DIAGNOSTICS.route) { SettingsScreen(SettingsSection.DIAGNOSTICS, viewModel, syncState, personalConflicts, dashboard, taskState, plannerState, workspaces, padding) { navController.navigate(it.route) } }
            composable(Screen.SYNC_ISSUES.route) { SettingsScreen(SettingsSection.SYNC_ISSUES, viewModel, syncState, personalConflicts, dashboard, taskState, plannerState, workspaces, padding) { navController.navigate(it.route) } }
            composable(Screen.LANGUAGE.route) { SettingsScreen(SettingsSection.LANGUAGE, viewModel, syncState, personalConflicts, dashboard, taskState, plannerState, workspaces, padding) { navController.navigate(it.route) } }
            composable(Screen.PROFILE.route) { ProfileScreen(
                profile = profile,
                actorResolved = profileActorResolved,
                profileConflict = personalConflicts.any { it.kind == "profile" },
                avatarConflict = personalConflicts.any { it.kind == "avatar" },
                vm = viewModel,
                padding = padding,
            ) }
            composable(Screen.LIFE.route) { profile?.takeIf { it.birthDateEpochDay != null && it.lifeExpectancyYears != null }?.let { LifeVisualizationScreen(it, padding) } }
        }
    }
}

@Composable
private fun feedbackText(feedback: UiFeedback): String = when (feedback.kind) {
    UiMessageKind.VALIDATION -> stringResource(R.string.error_validation)
    UiMessageKind.OFFLINE -> stringResource(R.string.error_offline)
    UiMessageKind.AUTHORIZATION -> stringResource(R.string.error_authorization)
    UiMessageKind.CONFLICT -> stringResource(R.string.error_conflict)
    UiMessageKind.BACKUP_INVALID_PASSWORD -> stringResource(R.string.error_backup_password)
    UiMessageKind.BACKUP_INVALID_FILE -> stringResource(R.string.error_backup_file)
    UiMessageKind.BACKUP_READ_WRITE -> stringResource(R.string.error_backup_io)
    UiMessageKind.UNEXPECTED -> stringResource(R.string.error_unexpected)
    UiMessageKind.BACKUP_EXPORT_SUCCEEDED -> stringResource(R.string.backup_export_success)
    UiMessageKind.BACKUP_IMPORT_SUCCEEDED -> pluralStringResource(R.plurals.backup_import_success, feedback.count ?: 0, feedback.count ?: 0)
    UiMessageKind.CONFLICT_RESOLUTION_QUEUED -> stringResource(R.string.conflict_queued)
    UiMessageKind.POMODORO_SETTINGS_SAVED -> stringResource(R.string.timer_settings_saved)
    UiMessageKind.POMODORO_SETTINGS_SAVED_FOR_NEXT_PHASE -> stringResource(R.string.timer_settings_saved_next_phase)
}

@Composable
private fun syncSummary(state: SyncUiState, personalConflictCount: Int = 0): String = when {
    state.conflicts.isNotEmpty() || state.failed.isNotEmpty() || personalConflictCount > 0 -> stringResource(R.string.sync_issues_count, state.conflicts.size + state.failed.size + personalConflictCount)
    state.runtime.phase == SyncPhase.SYNCING -> stringResource(R.string.syncing)
    state.runtime.phase == SyncPhase.RETRY -> stringResource(R.string.sync_retry)
    state.pending.isNotEmpty() -> stringResource(R.string.sync_pending, state.pending.size)
    else -> stringResource(R.string.sync_ok)
}

@Composable
internal fun SyncStatusIndicator(state: SyncUiState, personalConflictCount: Int = 0, onClick: () -> Unit) {
    val indicator = syncIndicatorState(state, personalConflictCount)
    val summary = syncSummary(state, personalConflictCount)
    val description = stringResource(R.string.open_sync_diagnostics, summary)
    val container = if (indicator == SyncIndicatorState.HEALTHY) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
    val content = if (indicator == SyncIndicatorState.HEALTHY) Color.White else MaterialTheme.colorScheme.onError
    IconButton(
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
        onClick = onClick,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
            Text(
                if (indicator == SyncIndicatorState.HEALTHY) "✓" else "!",
                color = content,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun TrackScreen(state: DashboardState, vm: MainViewModel, padding: PaddingValues, onOpenTree: (String) -> Unit) {
    var showDeleted by rememberSaveable { mutableStateOf(false) }
    var newTree by rememberSaveable { mutableStateOf(false) }
    var renameTreeId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTreeId by rememberSaveable { mutableStateOf<String?>(null) }
    var purgeTreeId by rememberSaveable { mutableStateOf<String?>(null) }
    var revealedTreeId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionBounds by remember { mutableStateOf<Rect?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val renamedTree = state.categoryTrees.firstOrNull { it.id == renameTreeId }
    val deletedTree = state.categoryTrees.firstOrNull { it.id == deleteTreeId }
    fun closeActions() {
        revealedTreeId = null
        actionBounds = null
    }
    BackHandler(enabled = revealedTreeId != null, onBack = ::closeActions)
    Box(
        Modifier.fillMaxSize().padding(padding)
            .onGloballyPositioned { screenOrigin = it.positionInRoot() }
            .pointerInput(revealedTreeId, actionBounds) {
                if (revealedTreeId != null) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                            val rootPosition = screenOrigin + down.position
                            if (actionBounds?.contains(rootPosition) != true) {
                                closeActions()
                                down.consume()
                            }
                        }
                    }
                }
            },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenHeading(R.string.track) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showDeleted = false }, enabled = showDeleted) { Text(stringResource(R.string.active_trees)) }
                    Button(onClick = { showDeleted = true }, enabled = !showDeleted) { Text(stringResource(R.string.deleted_trees)) }
                }
            }
            if (showDeleted) {
                if (state.trashedCategoryTrees.isEmpty()) item { Text(stringResource(R.string.no_deleted_trees)) }
                items(state.trashedCategoryTrees, key = { it.id }) { tree ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tree.name, style = MaterialTheme.typography.titleMedium)
                            tree.trashedAtEpochMs?.let { trashedAt ->
                                Text(stringResource(R.string.tree_trash_expires, formatEpoch(trashedAt + 30L * 24 * 60 * 60 * 1000)), style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = tree.trashedAtEpochMs?.let { System.currentTimeMillis() - it < 30L * 24 * 60 * 60 * 1000 } == true, onClick = { vm.restoreCategoryTree(tree.id) }) { Text(stringResource(R.string.restore)) }
                                TextButton(enabled = tree.syncState == app.t4l.data.LocalSyncState.SYNCED && state.categories.none { it.categoryTreeId == tree.id && it.syncState != app.t4l.data.LocalSyncState.SYNCED }, onClick = { purgeTreeId = tree.id }) { Text(stringResource(R.string.delete_permanently)) }
                            }
                        }
                    }
                }
            } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(
                        modifier = Modifier.size(48.dp),
                        onClick = {
                            if (revealedTreeId != null) closeActions()
                            else newTree = true
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.create_category_tree))
                    }
                }
            }
            if (state.categoryTrees.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.no_category_trees))
                        Button(onClick = vm::createStarterData) { Text(stringResource(R.string.create_starter)) }
                    }
                }
            } else {
                items(state.categoryTrees, key = { it.id }) { tree ->
                    CategoryTreeListItem(
                        name = tree.name,
                        revealed = revealedTreeId == tree.id,
                        onRevealed = {
                            revealedTreeId = tree.id
                            actionBounds = null
                        },
                        onCloseActions = { if (revealedTreeId == tree.id) closeActions() },
                        onOpen = {
                            if (revealedTreeId != null) closeActions()
                            else onOpenTree(tree.id)
                        },
                        onRename = { closeActions(); renameTreeId = tree.id },
                        onDelete = { closeActions(); deleteTreeId = tree.id },
                        onActionBoundsChanged = { bounds -> if (revealedTreeId == tree.id) actionBounds = bounds },
                    )
                }
            }
            }
        }
    }
    if (newTree) NameDialog(stringResource(R.string.new_category_tree), stringResource(R.string.category_tree_name), onDismiss = { newTree = false }) { vm.createCategoryTree(it); newTree = false }
    renamedTree?.let { tree ->
        NameDialog(stringResource(R.string.rename), stringResource(R.string.category_tree_name), initial = tree.name, onDismiss = { renameTreeId = null }) {
            vm.renameCategoryTree(tree.id, it); renameTreeId = null
        }
    }
    deletedTree?.let { tree ->
        val categoryCount = state.categories.count { it.categoryTreeId == tree.id && !it.archived }
        ConfirmDialog(
            pluralStringResource(R.plurals.delete_tree_confirmation, categoryCount, tree.name, categoryCount),
            { deleteTreeId = null },
        ) {
            vm.archiveCategoryTree(tree.id); deleteTreeId = null
        }
    }
    state.trashedCategoryTrees.firstOrNull { it.id == purgeTreeId }?.let { tree ->
        ConfirmDialog(stringResource(R.string.purge_tree_message, tree.name), { purgeTreeId = null }) {
            vm.purgeCategoryTree(tree.id); purgeTreeId = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryTreeListItem(
    name: String,
    revealed: Boolean,
    onRevealed: () -> Unit,
    onCloseActions: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onActionBoundsChanged: (Rect) -> Unit = {},
) {
    var showContextMenu by rememberSaveable { mutableStateOf(false) }
    var localActionBounds by remember { mutableStateOf<Rect?>(null) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value -> value != SwipeToDismissBoxValue.StartToEnd },
    )
    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> onRevealed()
            SwipeToDismissBoxValue.Settled -> if (revealed) onCloseActions()
            SwipeToDismissBoxValue.StartToEnd -> Unit
        }
    }
    LaunchedEffect(revealed) {
        if (!revealed && dismissState.currentValue != SwipeToDismissBoxValue.Settled) dismissState.reset()
        if (revealed) localActionBounds?.let(onActionBoundsChanged)
    }
    val renameLabel = stringResource(R.string.rename_tree_named, name)
    val deleteLabel = stringResource(R.string.delete_tree_named, name)
    Box {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    Row(
                        Modifier.fillMaxHeight().onGloballyPositioned {
                            localActionBounds = it.boundsInRoot()
                            if (revealed) onActionBoundsChanged(it.boundsInRoot())
                        },
                    ) {
                        TreeSwipeAction(
                            label = stringResource(R.string.rename),
                            description = renameLabel,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        ) { onCloseActions(); onRename() }
                        TreeSwipeAction(
                            label = stringResource(R.string.delete),
                            description = deleteLabel,
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        ) { onCloseActions(); onDelete() }
                    }
                }
            },
        ) {
            Card(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(
                    onClick = onOpen,
                    onLongClick = { showContextMenu = true },
                ).semantics {
                    role = Role.Button
                    contentDescription = name
                    customActions = listOf(
                        CustomAccessibilityAction(renameLabel) { onRename(); true },
                        CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                },
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { showContextMenu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { showContextMenu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun TreeSwipeAction(
    label: String,
    description: String,
    container: Color,
    content: Color,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxHeight().width(128.dp).clickable(onClick = onClick).semantics { contentDescription = description },
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon()
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2)
        }
    }
}

@Composable
private fun CategoryTreeEditorScreen(treeId: String, state: DashboardState, vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val tree = state.categoryTrees.firstOrNull { it.id == treeId }
    if (tree == null) {
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.category_tree_unavailable), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        return
    }
    CategoryTreeEditor(
        name = tree.name,
        categories = state.categories.filter { it.categoryTreeId == tree.id && !it.archived },
        padding = padding,
        onAdd = { parent, name -> vm.addCategory(tree.id, name, parent) },
        onMoveCategory = vm::moveCategory,
        onRenameCategory = { id, value -> vm.renameCategory(id, value) },
        onDeleteCategory = vm::archiveCategory,
        archivedCount = state.categories.count { it.categoryTreeId == tree.id && it.archived && it.deletedAtEpochMs == null },
        onRestoreArchived = { vm.restoreArchivedCategories(tree.id) },
    )
}

private data class CategoryDropTarget(val placement: CategoryPlacement, val categoryId: String?)

@Composable
internal fun CategoryTreeEditor(
    name: String,
    categories: List<CategoryRow>,
    padding: PaddingValues,
    onAdd: (String?, String) -> Unit,
    onMoveCategory: (String, CategoryPlacement) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    archivedCount: Int = 0,
    onRestoreArchived: () -> Unit = {},
) {
    var confirmRestoreArchived by remember { mutableStateOf(false) }
    var selectedCategoryId by rememberSaveable(name) { mutableStateOf<String?>(null) }
    var addParent by rememberSaveable { mutableStateOf<String?>(null) }; var addDialog by rememberSaveable { mutableStateOf(false) }
    var renameCategory by remember { mutableStateOf<CategoryRow?>(null) }
    var moveCategory by remember { mutableStateOf<CategoryRow?>(null) }
    var confirmCategory by remember { mutableStateOf<CategoryRow?>(null) }
    var collapsedValue by rememberSaveable(name) { mutableStateOf("") }
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var draggingCategoryId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var dropTarget by remember { mutableStateOf<CategoryDropTarget?>(null) }
    var editorBounds by remember { mutableStateOf<Rect?>(null) }
    var rootDropBounds by remember { mutableStateOf<Rect?>(null) }
    var addButtonBounds by remember { mutableStateOf<Rect?>(null) }
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }
    val categoryNameBounds = remember { mutableStateMapOf<String, Rect>() }
    val collapsed = remember(collapsedValue) { collapsedValue.split(',').filter(String::isNotBlank).toSet() }
    val flat = remember(categories, collapsed) { flattenVisibleCategories(categories, collapsed) }
    val children = remember(categories) { categories.groupBy { it.parentId } }
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
    val invalidDropIds = remember(categories, draggingCategoryId) {
        draggingCategoryId?.let { descendantCategoryIds(categories, it) + it }.orEmpty()
    }
    val listState = rememberLazyListState()
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(categories, selectedCategoryId) {
        if (selectedCategoryId != null && selectedCategory == null) selectedCategoryId = null
    }
    LaunchedEffect(draggingCategoryId) {
        while (draggingCategoryId != null) {
            val position = dragPosition
            val bounds = editorBounds
            if (position != null && bounds != null) {
                when {
                    position.y < bounds.top + edgePx -> listState.scrollBy(-18f)
                    position.y > bounds.bottom - edgePx -> listState.scrollBy(18f)
                }
            }
            delay(16)
        }
    }

    fun clearDrag() {
        draggingCategoryId = null
        dragPosition = null
        dropTarget = null
        rootDropBounds = null
    }
    fun updateDrop(position: Offset) {
        dragPosition = position
        dropTarget = resolveCategoryDropTarget(position, rootDropBounds, rowBounds, invalidDropIds)
    }

    Box(
        Modifier.fillMaxSize().padding(padding).onGloballyPositioned { editorBounds = it.boundsInRoot() }
            .pointerInput(selectedCategoryId, addButtonBounds) {
                if (selectedCategoryId != null) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                            val rootPosition = editorBounds?.topLeft?.plus(down.position) ?: continue
                            val preservesSelection = addButtonBounds?.contains(rootPosition) == true ||
                                categoryNameBounds.values.any { it.contains(rootPosition) }
                            if (!preservesSelection) selectedCategoryId = null
                        }
                    }
                }
            },
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { selectedCategoryId = null }.padding(vertical = 8.dp),
                )
                IconButton(
                    modifier = Modifier.size(48.dp).onGloballyPositioned { addButtonBounds = it.boundsInRoot() },
                    onClick = {
                        addParent = selectedCategoryId
                        addDialog = true
                    },
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = selectedCategory?.let { stringResource(R.string.add_child_category, it.name) }
                            ?: stringResource(R.string.add_root_category),
                    )
                }
            }
            if (archivedCount > 0) TextButton(onClick = { confirmRestoreArchived = true }) {
                Text(stringResource(R.string.restore_archived_categories, archivedCount))
            }
            if (draggingCategoryId != null) {
                Surface(
                    color = if (dropTarget?.placement == CategoryPlacement.Root) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onGloballyPositioned { rootDropBounds = it.boundsInRoot() },
                ) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.move_to_root))
                    }
                }
            }
            if (categories.isEmpty()) Text(
                stringResource(R.string.no_categories_in_tree),
                modifier = Modifier.fillMaxWidth().clickable { selectedCategoryId = null }.padding(vertical = 16.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(flat, key = { it.first.id }) { (category, depth) ->
                    val hasChildren = children[category.id].orEmpty().isNotEmpty()
                    val isCollapsed = category.id in collapsed
                    CategoryEditorRow(
                        category = category,
                        depth = depth,
                        hasChildren = hasChildren,
                        isCollapsed = isCollapsed,
                        selected = selectedCategoryId == category.id,
                        dragging = draggingCategoryId == category.id,
                        validDropTarget = category.id !in invalidDropIds,
                        dropPlacement = dropTarget?.takeIf { it.categoryId == category.id }?.placement,
                        menuExpanded = actionsFor == category.id,
                        onBoundsChanged = { rowBounds[category.id] = it },
                        onNameBoundsChanged = { categoryNameBounds[category.id] = it },
                        onDisposed = {
                            rowBounds.remove(category.id)
                            categoryNameBounds.remove(category.id)
                        },
                        onToggleExpanded = {
                            selectedCategoryId = null
                            val next = collapsed.toMutableSet()
                            if (!next.add(category.id)) next.remove(category.id)
                            collapsedValue = next.sorted().joinToString(",")
                        },
                        onSelect = { selectedCategoryId = category.id },
                        onRename = { renameCategory = category },
                        onMenu = { selectedCategoryId = null; actionsFor = category.id },
                        onDismissMenu = { actionsFor = null },
                        onAddChild = { actionsFor = null; addParent = category.id; addDialog = true },
                        onMoveRequest = { actionsFor = null; moveCategory = category },
                        onDelete = { actionsFor = null; confirmCategory = category },
                        onDragStart = { position ->
                            actionsFor = null
                            selectedCategoryId = category.id
                            draggingCategoryId = category.id
                            updateDrop(position)
                        },
                        onDrag = ::updateDrop,
                        onDragEnd = {
                            val target = dropTarget
                            if (target != null) {
                                onMoveCategory(category.id, target.placement)
                                if (target.placement is CategoryPlacement.Inside) {
                                    val next = collapsed.toMutableSet().apply { remove(target.placement.targetId) }
                                    collapsedValue = next.sorted().joinToString(",")
                                }
                            }
                            clearDrag()
                        },
                        onDragCancel = ::clearDrag,
                    )
                }
                item("clear-selection-area") {
                    Spacer(Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable { selectedCategoryId = null })
                }
            }
        }
        val dragged = categories.firstOrNull { it.id == draggingCategoryId }
        if (dragged != null && dragPosition != null && editorBounds != null) {
            val descendantCount = descendantCategoryIds(categories, dragged.id).size
            Surface(
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.zIndex(2f).offset {
                    IntOffset(
                        (dragPosition!!.x - editorBounds!!.left - 80.dp.toPx()).roundToInt(),
                        (dragPosition!!.y - editorBounds!!.top - 28.dp.toPx()).roundToInt(),
                    )
                },
            ) {
                Text(
                    pluralStringResource(R.plurals.moving_category, descendantCount, dragged.name, descendantCount),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
    if (addDialog) NameDialog(stringResource(R.string.add_category), stringResource(R.string.category_name), onDismiss = { addDialog = false }) { onAdd(addParent, it); addDialog = false }
    renameCategory?.let { category -> NameDialog(stringResource(R.string.rename), stringResource(R.string.category_name), initial = category.name, onDismiss = { renameCategory = null }) { onRenameCategory(category.id, it); renameCategory = null } }
    moveCategory?.let { category -> MoveCategoryDialog(category, categories, { moveCategory = null }) { placement -> onMoveCategory(category.id, placement); moveCategory = null } }
    confirmCategory?.let { category -> ConfirmDialog(stringResource(R.string.archive_category_message, category.name), { confirmCategory = null }) { onDeleteCategory(category.id); confirmCategory = null } }
    if (confirmRestoreArchived) ConfirmDialog(stringResource(R.string.restore_archived_categories_message),
        { confirmRestoreArchived = false }) { onRestoreArchived(); confirmRestoreArchived = false }
}

@Composable
private fun CategoryEditorRow(
    category: CategoryRow,
    depth: Int,
    hasChildren: Boolean,
    isCollapsed: Boolean,
    selected: Boolean,
    dragging: Boolean,
    validDropTarget: Boolean,
    dropPlacement: CategoryPlacement?,
    menuExpanded: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onNameBoundsChanged: (Rect) -> Unit,
    onDisposed: () -> Unit,
    onToggleExpanded: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onAddChild: () -> Unit,
    onMoveRequest: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    DisposableEffect(category.id) { onDispose(onDisposed) }
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val boundedDepth = depth.coerceAtMost(10)
    val insideTarget = dropPlacement is CategoryPlacement.Inside
    val containerColor = when {
        insideTarget -> MaterialTheme.colorScheme.primaryContainer
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val expandDescription = "${category.name}: ${stringResource(if (isCollapsed) R.string.expand else R.string.collapse)}"
    val expandedState = stringResource(if (isCollapsed) R.string.collapsed else R.string.expanded)
    val levelDescription = stringResource(R.string.category_level, category.name, depth + 1)
    val selectedState = stringResource(if (selected) R.string.category_selected else R.string.category_not_selected)
    val actionsDescription = stringResource(R.string.category_actions, category.name)
    val moveDescription = stringResource(R.string.move_category_named, category.name)
    Surface(
        color = containerColor,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        shadowElevation = if (dragging) 8.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().alpha(if (dragging || validDropTarget) 1f else 0.45f)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },
    ) {
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).drawBehind {
                    repeat(boundedDepth) { level ->
                        val x = (level * 16 + 8).dp.toPx()
                        drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    }
                }.padding(start = (boundedDepth * 16).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasChildren) {
                    TextButton(
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = expandDescription
                            stateDescription = expandedState
                        },
                        onClick = onToggleExpanded,
                    ) { Text(if (isCollapsed) "▶" else "▼") }
                } else Spacer(Modifier.size(48.dp))
                Text(
                    text = category.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .onGloballyPositioned { onNameBoundsChanged(it.boundsInRoot()) }
                        .combinedClickable(onClick = onSelect, onDoubleClick = onRename)
                        .padding(vertical = 13.dp, horizontal = 4.dp)
                        .semantics {
                            contentDescription = levelDescription
                            stateDescription = selectedState
                            customActions = listOf(CustomAccessibilityAction(moveDescription) { onMoveRequest(); true })
                        },
                )
                Box {
                    CategoryActionButton(
                        categoryId = category.id,
                        description = actionsDescription,
                        moveDescription = moveDescription,
                        onClick = onMenu,
                        onMoveRequest = onMoveRequest,
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                    DropdownMenu(menuExpanded, onDismissMenu) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_category)) }, onClick = onAddChild)
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = onRename)
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_category)) }, onClick = onMoveRequest)
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = onDelete)
                    }
                }
            }
            when (dropPlacement) {
                is CategoryPlacement.Before -> HorizontalDivider(Modifier.align(Alignment.TopCenter), thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
                is CategoryPlacement.After -> HorizontalDivider(Modifier.align(Alignment.BottomCenter), thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
                else -> Unit
            }
        }
    }
}

@Composable
private fun CategoryActionButton(
    categoryId: String,
    description: String,
    moveDescription: String,
    onClick: () -> Unit,
    onMoveRequest: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentClick by rememberUpdatedState(onClick)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    val currentDragCancel by rememberUpdatedState(onDragCancel)
    Surface(
        modifier = Modifier.size(48.dp).onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(categoryId) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    firstDown.consume()
                    if (waitForUpOrCancellation() == null) return@awaitEachGesture
                    val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                        awaitFirstDown(requireUnconsumed = false)
                    }
                    if (secondDown == null) {
                        currentClick()
                        return@awaitEachGesture
                    }
                    secondDown.consume()
                    currentDragStart(origin + secondDown.position)
                    var completed = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == secondDown.id }
                            if (change == null || !change.pressed) {
                                currentDragEnd()
                                completed = true
                                break
                            }
                            change.consume()
                            currentDrag(origin + change.position)
                        }
                    } finally {
                        if (!completed) currentDragCancel()
                    }
                }
            }.semantics {
                role = Role.Button
                contentDescription = description
                onClick { currentClick(); true }
                customActions = listOf(CustomAccessibilityAction(moveDescription) { onMoveRequest(); true })
            },
        color = Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("⋮") }
    }
}

@Composable
private fun MoveCategoryDialog(
    category: CategoryRow,
    categories: List<CategoryRow>,
    onDismiss: () -> Unit,
    onMove: (CategoryPlacement) -> Unit,
) {
    val invalid = remember(categories, category.id) { descendantCategoryIds(categories, category.id) + category.id }
    val destinations = remember(categories, invalid) { listOf<String?>(null) + categories.filterNot { it.id in invalid }.map { it.id } }
    var targetId by rememberSaveable(category.id) { mutableStateOf<String?>(null) }
    var position by rememberSaveable(category.id) { mutableStateOf("inside") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_category_named, category.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionMenu(
                    label = stringResource(R.string.move_destination),
                    selected = targetId,
                    options = destinations,
                    text = { id -> id?.let { categoryPathLabel(it, categories) } ?: stringResource(R.string.move_to_root) },
                    onSelect = { targetId = it },
                )
                if (targetId != null) {
                    SelectionMenu(
                        label = stringResource(R.string.move_position),
                        selected = position,
                        options = listOf("before", "inside", "after"),
                        text = { value -> stringResource(when (value) {
                            "before" -> R.string.move_before
                            "after" -> R.string.move_after
                            else -> R.string.move_inside
                        }) },
                        onSelect = { position = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = targetId
                onMove(if (target == null) CategoryPlacement.Root else when (position) {
                    "before" -> CategoryPlacement.Before(target)
                    "after" -> CategoryPlacement.After(target)
                    else -> CategoryPlacement.Inside(target)
                })
            }) { Text(stringResource(R.string.move_category)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun resolveCategoryDropTarget(
    position: Offset,
    rootBounds: Rect?,
    rowBounds: Map<String, Rect>,
    invalidCategoryIds: Set<String>,
): CategoryDropTarget? {
    if (rootBounds?.contains(position) == true) return CategoryDropTarget(CategoryPlacement.Root, null)
    val entry = rowBounds.entries.firstOrNull { (id, bounds) -> id !in invalidCategoryIds && bounds.contains(position) } ?: return null
    val fraction = (position.y - entry.value.top) / entry.value.height
    val placement = when {
        fraction < 1f / 3f -> CategoryPlacement.Before(entry.key)
        fraction > 2f / 3f -> CategoryPlacement.After(entry.key)
        else -> CategoryPlacement.Inside(entry.key)
    }
    return CategoryDropTarget(placement, entry.key)
}

internal fun descendantCategoryIds(categories: List<CategoryRow>, categoryId: String): Set<String> {
    val children = categories.groupBy { it.parentId }
    val result = mutableSetOf<String>()
    val pending = ArrayDeque<String>().apply { add(categoryId) }
    while (pending.isNotEmpty()) {
        children[pending.removeFirst()].orEmpty().forEach { child ->
            if (result.add(child.id)) pending.add(child.id)
        }
    }
    return result
}

private fun categoryPathLabel(categoryId: String, categories: List<CategoryRow>): String {
    val byId = categories.associateBy { it.id }
    val parts = mutableListOf<String>()
    val visited = mutableSetOf<String>()
    var current = byId[categoryId]
    while (current != null && visited.add(current.id)) {
        parts += current.name
        current = current.parentId?.let(byId::get)
    }
    return parts.asReversed().joinToString(" › ")
}

internal fun flattenVisibleCategories(categories: List<CategoryRow>, collapsed: Set<String>): List<Pair<CategoryRow, Int>> {
    val children = categories.groupBy { it.parentId }; val result = mutableListOf<Pair<CategoryRow, Int>>(); val visited = mutableSetOf<String>()
    fun visit(parent: String?, depth: Int) { children[parent].orEmpty().sortedWith(compareBy<CategoryRow> { it.sortOrder }.thenBy { it.name }).forEach { item ->
        if (visited.add(item.id)) { result += item to depth; if (item.id !in collapsed) visit(item.id, depth + 1) }
    } }
    visit(null, 0); return result
}

private fun effectiveTaskCategory(id: String, tasks: List<TaskRow>): String? {
    val visited = mutableSetOf<String>(); var current = tasks.firstOrNull { it.id == id }
    while (current != null && visited.add(current.id)) {
        current.categoryId?.let { return it }; current = current.parentTaskId?.let { parent -> tasks.firstOrNull { it.id == parent } }
    }
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryScreen(state: DashboardState, tasks: TaskState, vm: MainViewModel, padding: PaddingValues, onOpenTask: (String) -> Unit) {
    var hideRedundant by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }; var editingId by rememberSaveable { mutableStateOf<String?>(null) }; var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDistribution by rememberSaveable { mutableStateOf(false) }
    var rangeStart by rememberSaveable { mutableStateOf("") }
    var rangeEnd by rememberSaveable { mutableStateOf("") }
    var capturedEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    val startDate = rangeStart.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val endDate = rangeEnd.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val zone = ZoneId.systemDefault()
    val from = startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
    val to = if (rangeEnd.isBlank()) capturedEnd else endDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.coerceAtMost(System.currentTimeMillis())
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeading(R.string.history) }
        item { Button(enabled = state.categories.isNotEmpty(), onClick = { adding = true }) { Text(stringResource(R.string.add_event)) } }
        item { TextButton(onClick = { showDistribution = !showDistribution }) { Text(stringResource(R.string.show_time_distribution)) } }
        if (showDistribution) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(rangeStart, stringResource(R.string.distribution_start), { rangeStart = it; capturedEnd = null }, invalid = rangeStart.isNotBlank() && startDate == null, presets = DatePresets.DISTRIBUTION_START)
                DateField(rangeEnd, stringResource(R.string.distribution_end), { rangeEnd = it; capturedEnd = null }, optional = true, invalid = rangeEnd.isNotBlank() && endDate == null, presets = DatePresets.DISTRIBUTION_END)
                if (rangeEnd.isBlank()) TextButton(enabled = startDate != null, onClick = { capturedEnd = System.currentTimeMillis() }) {
                    if (capturedEnd != null) { Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(4.dp)) }
                    Text(stringResource(if (capturedEnd == null) R.string.calculate_distribution else R.string.refresh_distribution))
                }
                if (from != null && to != null && to > from) {
                    TextButton(onClick = { hideRedundant = !hideRedundant }) { Text(stringResource(if (hideRedundant) R.string.show_other_categories else R.string.hide_extra_categories)) }
                    val activeTreeIds = state.categoryTrees.mapTo(mutableSetOf()) { it.id }
                    val activeCategories = state.categories.filter { it.categoryTreeId in activeTreeIds && !it.archived }
                    val distribution = TimeEngine.distribution(
                        TimeEngine.intervals(state.events.filter { it.categoryTreeId in activeTreeIds }.map { EventPoint(it.id, it.categoryTreeId, it.categoryId, it.occurredAtEpochMs, it.taskId) }, from, to, to),
                        activeCategories.map { CategoryNode(it.id, it.categoryTreeId, it.parentId) },
                    ).associateBy { it.categoryId }
                    state.categoryTrees.forEach { tree ->
                        Text(tree.name, style = MaterialTheme.typography.titleMedium)
                        val categories = activeCategories.filter { it.categoryTreeId == tree.id }
                        budgetCategoryItems(categories, emptyMap(), hideRedundant, distribution.mapNotNull { (id, value) -> id?.let { it to value.ownMillis } }.toMap()).forEach { (category, depth) ->
                            val value = distribution[category.id]
                            Text("${"  ".repeat(depth)}${category.name}: ${stringResource(R.string.own_minutes)} ${formatDuration(value?.ownMillis ?: 0)}, Σ ${formatDuration(value?.totalMillis ?: 0)}")
                        }
                    }
                }
            }
        }
        state.events.groupBy { timelineLocalDate(it.occurredAtEpochMs) }.forEach { (date, events) ->
            stickyHeader(key = "history-date-$date") { TimelineDateHeader(date) }
            items(events, key = { it.id }) { event ->
                val tree = state.historicalCategoryTrees.firstOrNull { it.id == event.categoryTreeId }?.name.orEmpty()
                val category = state.categories.firstOrNull { it.id == event.categoryId }?.name ?: stringResource(R.string.unknown)
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(event.occurredAtEpochMs)), style = MaterialTheme.typography.titleMedium)
                    Text("$tree · $category")
                    EventTaskLink(event.taskId, tasks.tasks, onOpenTask)
                    Row { TextButton(onClick = { editingId = event.id }) { Text(stringResource(R.string.edit)) }; TextButton(onClick = { deletingId = event.id }) { Text(stringResource(R.string.delete)) } }
                } }
            }
        }
    }
    if (adding) EventDialog(state, tasks.tasks, System.currentTimeMillis(), onDismiss = { adding = false }) { tree, category, task, at, done ->
        vm.addEvent(tree, category, at, task) { success -> done(success); if (success) adding = false }
    }
    state.events.firstOrNull { it.id == editingId }?.let { event ->
        EventDialog(state, tasks.tasks, event.occurredAtEpochMs, initialTreeId = event.categoryTreeId, initialCategoryId = event.categoryId,
            initialTaskId = event.taskId, editing = true, onDismiss = { editingId = null }) { tree, category, task, at, done ->
            vm.updateEvent(event.id, tree, category, task, at) { success -> done(success); if (success) editingId = null }
        }
    }
    state.events.firstOrNull { it.id == deletingId }?.let { event -> ConfirmDialog(stringResource(R.string.delete_event_message, formatter.format(Instant.ofEpochMilli(event.occurredAtEpochMs))), { deletingId = null }) { vm.deleteEvent(event.id); deletingId = null } }
}

@Composable
internal fun EventDialog(state: DashboardState, tasks: List<TaskRow>, initial: Long, range: LongRange? = null,
    initialTreeId: String? = null, initialCategoryId: String? = null, initialTaskId: String? = null, editing: Boolean = false,
    onDismiss: () -> Unit, onSave: (String, String?, String?, Long, (Boolean) -> Unit) -> Unit) {
    var tree by rememberSaveable(initialTreeId) { mutableStateOf(initialTreeId ?: state.categoryTrees.firstOrNull()?.id.orEmpty()) }
    var category by rememberSaveable(initialTreeId, initialCategoryId, editing) { mutableStateOf(initialCategoryId ?: if (editing) null else state.categories.firstOrNull { it.categoryTreeId == tree && !it.archived }?.id) }
    var taskId by rememberSaveable(initialTaskId) { mutableStateOf(initialTaskId) }
    var value by rememberSaveable(initial) { mutableStateOf(formatEpoch(initial)) }
    var saving by rememberSaveable { mutableStateOf(false) }
    val trees = state.categoryTrees.map { it.id }.let { active -> if (initialTreeId != null && initialTreeId !in active) active + initialTreeId else active }
    val choices = state.categories.filter { it.categoryTreeId == tree && (!it.archived || it.id == initialCategoryId) }
    val categoryIds = choices.map { it.id }.let { available -> if (initialCategoryId != null && initialCategoryId !in available && tree == initialTreeId) available + initialCategoryId else available }
    val selectableTasks = tasks.filter { task ->
        val categoryId = effectiveTaskCategory(task.id, tasks)
        task.id == initialTaskId || categoryId != null && state.categories.any { it.id == categoryId && !it.archived && state.categoryTrees.any { tree -> tree.id == it.categoryTreeId } }
    }
    val taskIds = selectableTasks.map { it.id }.let { available -> if (initialTaskId != null && initialTaskId !in available) available + initialTaskId else available }
    val parsed = if (editing && value == formatEpoch(initial)) initial else parseEpoch(value)
    val validTime = parsed != null && (range?.contains(parsed) ?: (parsed <= System.currentTimeMillis()))
    val historicalTarget = editing && tree == initialTreeId && category == initialCategoryId
    val activeTargetRequired = !historicalTarget || taskId != null && taskId != initialTaskId
    val validTarget = tree.isNotEmpty() && tree in trees && (!activeTargetRequired || state.categoryTrees.any { it.id == tree }) &&
        (category == null || choices.any { it.id == category && (!activeTargetRequired || !it.archived) }) &&
        (taskId == null || selectableTasks.any { it.id == taskId && effectiveTaskCategory(it.id, tasks) == category })
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(if (editing) R.string.edit_event else R.string.add_event)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionMenu(stringResource(R.string.category_tree), tree, trees, { id -> state.historicalCategoryTrees.firstOrNull { it.id == id }?.name ?: stringResource(R.string.unknown) }) { selected -> tree = selected; category = state.categories.firstOrNull { it.categoryTreeId == selected && !it.archived }?.id; taskId = null }
        SelectionMenu(stringResource(R.string.category), category, listOf<String?>(null) + categoryIds, { id -> choices.firstOrNull { it.id == id }?.name ?: stringResource(R.string.unknown) }) { selected ->
            category = selected
            if (taskId?.let { effectiveTaskCategory(it, tasks) } != selected) taskId = null
        }
        SelectionMenu(stringResource(R.string.task), taskId, listOf<String?>(null) + taskIds, { id ->
            selectableTasks.firstOrNull { it.id == id }?.let { "${it.title} · ${taskStatusLabel(it.status)}" } ?: stringResource(R.string.no_task)
        }) { selected ->
            taskId = selected
            selected?.let { id -> effectiveTaskCategory(id, tasks)?.let { categoryId -> state.categories.firstOrNull { it.id == categoryId }?.let { item -> category = item.id; tree = item.categoryTreeId } } }
        }
        if (!validTarget) Text(stringResource(R.string.event_target_unavailable), color = MaterialTheme.colorScheme.error)
        DateTimeField(value, stringResource(R.string.date_time_format), { value = it }, invalid = !validTime,
            presets = if (range == null) { if (editing) DateTimePresets.FACT_EDIT else DateTimePresets.FACT_NEW } else DateTimePresets.PLANNED_EVENT,
            reference = (range?.first ?: if (editing) initial else null)?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() },
            minValue = range?.first?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() },
            maxValue = (range?.last ?: System.currentTimeMillis()).let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() })
    } }, confirmButton = { TextButton(enabled = !saving && validTime && validTarget, onClick = { saving = true; onSave(tree, category, taskId, requireNotNull(parsed)) { saving = false } }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlannerScreen(state: DashboardState, tasks: TaskState, planner: PlannerState, vm: MainViewModel, padding: PaddingValues, onOpenTask: (String) -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var deletePlanId by rememberSaveable { mutableStateOf<String?>(null) }
    var choosePlan by rememberSaveable { mutableStateOf(false) }
    var addingPlanId by rememberSaveable { mutableStateOf<String?>(null) }
    var editEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteEventId by rememberSaveable { mutableStateOf<String?>(null) }
    val validPlans = planner.plans.filter { it.endsAtEpochMs > it.startsAtEpochMs }
    val displayedPlans = if (showArchived) planner.archivedPlans else planner.plans
    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ScreenHeading(R.string.planner) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showArchived = false }, enabled = showArchived) { Text(stringResource(R.string.active_plans)) }
                Button(onClick = { showArchived = true }, enabled = !showArchived) { Text(stringResource(R.string.archived_plans)) }
            } }
            if (!showArchived) item { Button(onClick = { creating = true }) { Text(stringResource(R.string.new_plan)) } }
            displayedPlans.forEach { plan ->
                stickyHeader(key = "plan-heading-${plan.id}") {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Text(plan.name, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
                item(key = "plan-${plan.id}") {
                    PlanCard(plan, state, planner, vm, readOnly = showArchived, onDeleteArchived = { deletePlanId = plan.id })
                }
                planner.plannedEvents.filter { it.planId == plan.id }.groupBy { timelineLocalDate(it.occurredAtEpochMs) }.forEach { (date, events) ->
                    stickyHeader(key = "plan-date-${plan.id}-$date") { TimelineDateHeader(date, plan.name) }
                    items(events, key = { "plan-event-${it.id}" }) { event ->
                        PlannedEventItem(event, plan, state, tasks.tasks, showArchived, onOpenTask,
                            onEdit = { editEventId = event.id }, onDelete = { deleteEventId = event.id })
                    }
                }
            }
            if (displayedPlans.isEmpty()) item { Text(stringResource(if (showArchived) R.string.no_archived_plans else R.string.no_plans)) }
        }
        if (!showArchived) Button(enabled = validPlans.isNotEmpty() && state.categories.any { category -> !category.archived && state.categoryTrees.any { tree -> tree.id == category.categoryTreeId } }, onClick = { choosePlan = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text(stringResource(R.string.add_planned_event)) }
    }
    if (creating) PlanDialog({ creating = false }) { name, start, end -> vm.createPlan(name, start, end); creating = false }
    if (choosePlan) AlertDialog(onDismissRequest = { choosePlan = false }, title = { Text(stringResource(R.string.choose_plan)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            validPlans.forEach { plan -> TextButton(onClick = { addingPlanId = plan.id; choosePlan = false }) {
                Text("${plan.name} · ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(plan.startsAtEpochMs))}")
            } }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { choosePlan = false }) { Text(stringResource(R.string.cancel)) } })
    validPlans.firstOrNull { it.id == addingPlanId }?.let { plan ->
        EventDialog(state, tasks.tasks, plan.startsAtEpochMs, plan.startsAtEpochMs..(plan.endsAtEpochMs - 1), onDismiss = { addingPlanId = null }) { tree, category, task, at, done ->
            vm.addPlannedEvent(plan.id, tree, category, at, task) { success -> done(success); if (success) addingPlanId = null }
        }
    }
    planner.plannedEvents.firstOrNull { it.id == editEventId }?.let { event ->
        validPlans.firstOrNull { it.id == event.planId }?.let { plan ->
            EventDialog(state, tasks.tasks, event.occurredAtEpochMs, plan.startsAtEpochMs..(plan.endsAtEpochMs - 1),
                initialTreeId = event.categoryTreeId, initialCategoryId = event.categoryId, initialTaskId = event.taskId,
                editing = true, onDismiss = { editEventId = null }) { tree, category, task, at, done ->
                vm.updatePlannedEvent(event.id, tree, category, task, at) { success -> done(success); if (success) editEventId = null }
            }
        }
    }
    planner.plannedEvents.firstOrNull { it.id == deleteEventId }?.let { event ->
        val formatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
        ConfirmDialog(stringResource(R.string.delete_planned_event_message, formatter.format(Instant.ofEpochMilli(event.occurredAtEpochMs))), { deleteEventId = null }) {
            vm.deletePlannedEvent(event.id); deleteEventId = null
        }
    }
    planner.archivedPlans.firstOrNull { it.id == deletePlanId }?.let { plan ->
        ConfirmDialog(stringResource(R.string.delete_archived_plan_message, plan.name), { deletePlanId = null }) {
            vm.deletePlan(plan.id); deletePlanId = null
        }
    }
}

@Composable
private fun PlanCard(plan: PlanRow, state: DashboardState, planner: PlannerState, vm: MainViewModel, readOnly: Boolean = false, onDeleteArchived: () -> Unit = {}) {
    var confirmArchive by rememberSaveable(plan.id) { mutableStateOf(false) }; var hideRedundant by rememberSaveable(plan.id) { mutableStateOf(false) }; var repairing by rememberSaveable(plan.id) { mutableStateOf(false) }
    var budgetExpanded by rememberSaveable(plan.id) { mutableStateOf(planner.allocations.any { it.planId == plan.id }) }
    val formatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
    val validPeriod = plan.endsAtEpochMs > plan.startsAtEpochMs
    val planEvents = planner.plannedEvents.filter { it.planId == plan.id }
    val timeline = TimeEngine.plannedDistribution(
        planEvents.map { EventPoint(it.id, it.categoryTreeId, it.categoryId, it.occurredAtEpochMs, it.taskId) },
        state.categories.map { CategoryNode(it.id, it.categoryTreeId, it.parentId) },
        plan.startsAtEpochMs,
        plan.endsAtEpochMs,
    )?.associateBy { it.categoryId }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(plan.name, style = MaterialTheme.typography.titleLarge)
            if (!readOnly) TextButton(onClick = { confirmArchive = true }) { Text(stringResource(R.string.archive)) }
        }
        if (readOnly) Row {
            TextButton(onClick = { vm.restorePlan(plan.id) }) { Text(stringResource(R.string.restore)) }
            TextButton(onClick = onDeleteArchived) { Text(stringResource(R.string.delete)) }
        }
        Text("${formatter.format(Instant.ofEpochMilli(plan.startsAtEpochMs))} — ${formatter.format(Instant.ofEpochMilli(plan.endsAtEpochMs))}")
        if (!validPeriod) {
            Text(stringResource(R.string.invalid_plan_period), color = MaterialTheme.colorScheme.error)
            if (!readOnly) TextButton(onClick = { repairing = true }) { Text(stringResource(R.string.repair_plan_period)) }
        }
        TextButton(onClick = { budgetExpanded = !budgetExpanded }) { Text("${stringResource(R.string.budget)} ${if (budgetExpanded) "▾" else "▸"}", style = MaterialTheme.typography.titleMedium) }
        if (budgetExpanded) {
            TextButton(onClick = { hideRedundant = !hideRedundant }) { Text(stringResource(if (hideRedundant) R.string.show_other_categories else R.string.hide_extra_categories)) }
            (if (readOnly) state.historicalCategoryTrees else state.categoryTrees).forEach { tree ->
                Text(tree.name, style = MaterialTheme.typography.titleMedium)
                val categories = state.categories.filter { it.categoryTreeId == tree.id && (readOnly || !it.archived) }; val own = planner.allocations.filter { it.planId == plan.id }.associate { it.categoryId to it.ownMinutes }
                val rows = budgetCategoryItems(categories, own, hideRedundant, timeline?.mapNotNull { (id, value) -> id?.let { it to value.ownMillis } }?.toMap() ?: emptyMap())
                if (rows.isEmpty()) Text(stringResource(R.string.no_budget_categories), style = MaterialTheme.typography.bodySmall)
                rows.forEach { (category, depth) -> BudgetRow(category, depth, own[category.id], totalMinutes(category.id, categories, own), timeline?.get(category.id)?.ownMillis ?: if (validPeriod) 0 else null, timeline?.get(category.id)?.totalMillis ?: if (validPeriod) 0 else null, validPeriod && !readOnly) { vm.setBudget(plan.id, category.id, it) } }
            }
        }
        Text(stringResource(R.string.timeline_plan), style = MaterialTheme.typography.titleMedium)
    } }
    if (!readOnly && repairing) PlanPeriodDialog(plan, { repairing = false }) { start, end -> vm.updatePlanPeriod(plan.id, start, end) { success -> if (success) repairing = false } }
    if (!readOnly && confirmArchive) ConfirmDialog(stringResource(R.string.archive_plan_message, plan.name), { confirmArchive = false }) { vm.archivePlan(plan.id); confirmArchive = false }
}

@Composable
private fun PlannedEventItem(event: app.t4l.data.PlannedEventRow, plan: PlanRow, state: DashboardState, tasks: List<TaskRow>, readOnly: Boolean,
    onOpenTask: (String) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val tree = state.historicalCategoryTrees.firstOrNull { it.id == event.categoryTreeId }?.name.orEmpty()
    val category = state.categories.firstOrNull { it.id == event.categoryId }?.name ?: stringResource(R.string.unknown)
    val validPeriod = plan.endsAtEpochMs > plan.startsAtEpochMs
    val withinPeriod = validPeriod && event.occurredAtEpochMs >= plan.startsAtEpochMs && event.occurredAtEpochMs < plan.endsAtEpochMs
    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(event.occurredAtEpochMs)), style = MaterialTheme.typography.titleMedium)
            Text("$tree · $category")
            EventTaskLink(event.taskId, tasks, onOpenTask)
            if (validPeriod && !withinPeriod) Text(stringResource(R.string.planned_event_outside_period), color = MaterialTheme.colorScheme.error)
        }
        if (!readOnly) Column {
            TextButton(enabled = validPeriod, onClick = onEdit) { Text(stringResource(R.string.edit)) }
            TextButton(enabled = validPeriod, onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
    } }
}

@Composable
private fun BudgetRow(category: CategoryRow, depth: Int, own: Int?, total: Int, timelineOwn: Long?, timelineTotal: Long?, enabled: Boolean, onSave: (Int) -> Unit) {
    var value by rememberSaveable(category.id, own) { mutableStateOf(own?.toString().orEmpty()) }
    val timelineLabel = "(${timelineOwn?.let(::formatDuration) ?: "—"})"
    val totalLabel = "Σ $total (${timelineTotal?.let(::formatDuration) ?: "—"})"
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(3) * 16).dp)) {
        if (maxWidth < 520.dp || density.fontScale > 1.3f) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(category.name, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.own_minutes)) }, singleLine = true, enabled = enabled)
                Text(timelineLabel)
                Text(totalLabel)
                TextButton(enabled = enabled && value.toIntOrNull() != null, onClick = { onSave(requireNotNull(value.toIntOrNull())) }) { Text(stringResource(R.string.save)) }
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(category.name, Modifier.weight(1f))
            OutlinedTextField(value, { value = it.filter(Char::isDigit) }, modifier = Modifier.width(104.dp), label = { Text(stringResource(R.string.own_minutes)) }, singleLine = true, enabled = enabled)
            Text(timelineLabel)
            Text(totalLabel)
            TextButton(enabled = enabled && value.toIntOrNull() != null, onClick = { onSave(requireNotNull(value.toIntOrNull())) }) { Text(stringResource(R.string.save)) }
        }
    }
}

private fun totalMinutes(id: String, categories: List<CategoryRow>, own: Map<String, Int>): Int = BudgetEngine.totalMinutes(id, categories.associate { it.id to it.parentId }, own)

@Composable
internal fun PlanDialog(onDismiss: () -> Unit, onSave: (String, Long, Long) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val now = LocalDateTime.now().withSecond(0).withNano(0); var start by rememberSaveable { mutableStateOf(now.format(dateTimeFormat)) }; var end by rememberSaveable { mutableStateOf(now.plusDays(1).format(dateTimeFormat)) }
    var endManuallyEdited by rememberSaveable { mutableStateOf(false) }
    val startValue = parseEpoch(start); val endValue = parseEpoch(end)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.new_plan)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.plan_name)) })
        DateTimeField(start, stringResource(R.string.plan_start), { newStart ->
            if (!endManuallyEdited) shiftedPlanEnd(start, end, newStart)?.let { end = it }
            start = newStart
        }, invalid = startValue == null, presets = DateTimePresets.PLAN_START)
        DateTimeField(end, stringResource(R.string.plan_end), { end = it; endManuallyEdited = true }, invalid = endValue == null || startValue != null && endValue <= startValue,
            presets = DateTimePresets.PLAN_END, reference = startValue?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() },
            minValue = startValue?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().plusMinutes(1) })
    } }, confirmButton = { TextButton(enabled = name.isNotBlank() && startValue != null && endValue != null && endValue > startValue, onClick = { onSave(name, requireNotNull(startValue), requireNotNull(endValue)) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun PlanPeriodDialog(plan: PlanRow, onDismiss: () -> Unit, onSave: (Long, Long) -> Unit) {
    val now = LocalDateTime.now().withSecond(0).withNano(0)
    val initialStart = if (plan.startsAtEpochMs > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(plan.startsAtEpochMs), ZoneId.systemDefault()) else now
    var start by rememberSaveable(plan.id) { mutableStateOf(initialStart.format(dateTimeFormat)) }
    var end by rememberSaveable(plan.id) { mutableStateOf(initialStart.plusDays(1).format(dateTimeFormat)) }
    var endManuallyEdited by rememberSaveable(plan.id) { mutableStateOf(false) }
    val startValue = parseEpoch(start); val endValue = parseEpoch(end)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.repair_plan_period)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.repair_plan_period_hint))
        DateTimeField(start, stringResource(R.string.plan_start), { newStart ->
            if (!endManuallyEdited) shiftedPlanEnd(start, end, newStart)?.let { end = it }
            start = newStart
        }, invalid = startValue == null, presets = DateTimePresets.PLAN_START)
        DateTimeField(end, stringResource(R.string.plan_end), { end = it; endManuallyEdited = true }, invalid = endValue == null || startValue != null && endValue <= startValue,
            presets = DateTimePresets.PLAN_END, reference = startValue?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() },
            minValue = startValue?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().plusMinutes(1) })
    } }, confirmButton = { TextButton(enabled = startValue != null && endValue != null && endValue > startValue, onClick = { onSave(requireNotNull(startValue), requireNotNull(endValue)) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun formatDuration(milliseconds: Long): String = "%.1f".format(java.util.Locale.getDefault(), milliseconds / 60000.0)

@Composable
private fun TasksScreen(state: DashboardState, taskState: TaskState, settings: TaskListSettings, vm: MainViewModel, padding: PaddingValues, onOpenTask: (String) -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var revealedId by rememberSaveable { mutableStateOf<String?>(null) }
    var moveMenuId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMoveId by rememberSaveable { mutableStateOf<String?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }
    val items = remember(taskState.tasks, settings) { taskListItems(taskState.tasks, settings) }
    val dragTarget = dragPosition?.let { point ->
        val source = taskState.tasks.firstOrNull { it.id == draggingId }
        rowBounds.entries.firstOrNull { (id, bounds) ->
            val target = taskState.tasks.firstOrNull { it.id == id }
            source != null && target != null && validPriorityTarget(source, target, settings.listMode) && bounds.contains(point)
        }?.key
    }
    Box(Modifier.fillMaxSize().padding(padding).clickable(enabled = revealedId != null) { revealedId = null }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ScreenHeading(R.string.tasks) }
            item {
                TaskListToolbar(
                    settings = settings,
                    expanded = settingsExpanded,
                    canAdd = state.categories.any { !it.archived },
                    onExpandedChange = { settingsExpanded = it; revealedId = null },
                    onSettingsChange = vm::updateTaskListSettings,
                    onAdd = { revealedId = null; creating = true },
                )
            }
            if (items.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.no_tasks_for_filters))
                    TextButton(onClick = { settingsExpanded = true }) { Text(stringResource(R.string.change_filters)) }
                }
            }
            items(items, key = { it.task.id }) { item ->
                val index = items.indexOfFirst { it.task.id == item.task.id }
                val earlier = visibleMoveNeighbor(items, index, settings.listMode, -1)
                val later = visibleMoveNeighbor(items, index, settings.listMode, 1)
                TaskListRow(
                    task = item.task,
                    depth = item.depth,
                    state = state,
                    taskState = taskState,
                    detailMode = settings.detailMode,
                    revealed = revealedId == item.task.id,
                    dragging = draggingId == item.task.id,
                    dropTarget = dragTarget == item.task.id,
                    onBounds = { rowBounds[item.task.id] = it },
                    onReveal = { revealedId = item.task.id },
                    onClose = { if (revealedId == item.task.id) revealedId = null },
                    onDismissOther = { val hadOpen = revealedId != null; revealedId = null; hadOpen },
                    onRename = { vm.renameTask(item.task.id, it) },
                    onEdit = { revealedId = null; onOpenTask(item.task.id) },
                    manualOrder = settings.sortMode == TaskSortMode.PRIORITY,
                    moveMenuExpanded = moveMenuId == item.task.id,
                    canMoveEarlier = earlier != null,
                    canMoveLater = later != null,
                    onMoveMenuDismiss = { moveMenuId = null },
                    onRequestMove = {
                        revealedId = null
                        if (settings.sortMode == TaskSortMode.PRIORITY) moveMenuId = item.task.id
                        else pendingMoveId = item.task.id
                    },
                    onStatus = { vm.setTaskStatus(item.task.id, it); revealedId = null },
                    onDragStart = { point ->
                        revealedId = null; draggingId = item.task.id; dragPosition = point
                    },
                    onDrag = { dragPosition = it },
                    onDragEnd = {
                        val target = dragTarget
                        val bounds = target?.let(rowBounds::get)
                        if (target != null && bounds != null && dragPosition != null) vm.reorderTask(item.task.id, target, dragPosition!!.y > bounds.center.y)
                        draggingId = null; dragPosition = null
                    },
                    onMoveEarlier = { if (settings.sortMode == TaskSortMode.PRIORITY && earlier != null) vm.reorderTask(item.task.id, earlier.id, false) },
                    onMoveLater = { if (settings.sortMode == TaskSortMode.PRIORITY && later != null) vm.reorderTask(item.task.id, later.id, true) },
                )
            }
        }
    }
    if (creating) TaskDialog(state, null, null, { creating = false }) { draft -> vm.createTask(draft.title, draft.categoryId, null, draft.estimate, draft.nextDate, draft.nextMinute, draft.deadline) { success -> if (success) creating = false } }
    if (pendingMoveId != null) PrioritySortDialog(
        onDismiss = { pendingMoveId = null },
        onConfirm = {
            moveMenuId = pendingMoveId
            pendingMoveId = null
            vm.updateTaskListSettings(settings.copy(sortMode = TaskSortMode.PRIORITY))
        },
    )
}

@Composable
internal fun TaskListToolbar(
    settings: TaskListSettings,
    expanded: Boolean,
    canAdd: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSettingsChange: (TaskListSettings) -> Unit,
    onAdd: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { onExpandedChange(true) }) { Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.task_list_settings)) }
            TaskListSettingsMenu(expanded, settings, { onExpandedChange(false) }, onSettingsChange)
        }
        IconButton(enabled = canAdd, onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_task)) }
    }
}

@Composable
private fun TaskListSettingsMenu(expanded: Boolean, settings: TaskListSettings, onDismiss: () -> Unit, onChange: (TaskListSettings) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.task_statuses), style = MaterialTheme.typography.titleSmall) }, enabled = false, onClick = {})
        listOf("active", "paused", "completed", "cancelled").forEach { status ->
            DropdownMenuItem(
                text = { Text(taskStatusLabel(status)) },
                trailingIcon = { Checkbox(status in settings.statuses, onCheckedChange = null) },
                onClick = {
                    val next = settings.statuses.toMutableSet().apply { if (!add(status)) remove(status) }
                    onChange(settings.copy(statuses = next))
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.task_view), style = MaterialTheme.typography.titleSmall) }, enabled = false, onClick = {})
        TaskSettingChoices(TaskListMode.entries, settings.listMode, { taskListModeLabel(it) }) { onChange(settings.copy(listMode = it)) }
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.task_sort), style = MaterialTheme.typography.titleSmall) }, enabled = false, onClick = {})
        TaskSettingChoices(TaskSortMode.entries, settings.sortMode, { taskSortModeLabel(it) }) { onChange(settings.copy(sortMode = it)) }
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.task_details), style = MaterialTheme.typography.titleSmall) }, enabled = false, onClick = {})
        TaskSettingChoices(TaskDetailMode.entries, settings.detailMode, { taskDetailModeLabel(it) }) { onChange(settings.copy(detailMode = it)) }
    }
}

@Composable
private fun <T> TaskSettingChoices(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    options.forEach { option ->
        DropdownMenuItem(text = { Text(label(option)) }, trailingIcon = { RadioButton(option == selected, onClick = null) }, onClick = { onSelect(option) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskListRow(
    task: TaskRow,
    depth: Int,
    state: DashboardState,
    taskState: TaskState,
    detailMode: TaskDetailMode,
    revealed: Boolean,
    dragging: Boolean,
    dropTarget: Boolean,
    onBounds: (Rect) -> Unit,
    onReveal: () -> Unit,
    onClose: () -> Unit,
    onDismissOther: () -> Boolean,
    onRename: (String) -> Unit,
    onEdit: () -> Unit,
    manualOrder: Boolean = true,
    moveMenuExpanded: Boolean = false,
    canMoveEarlier: Boolean = true,
    canMoveLater: Boolean = true,
    onMoveMenuDismiss: () -> Unit = {},
    onRequestMove: () -> Unit = {},
    onStatus: (String) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
) {
    var renaming by rememberSaveable(task.id) { mutableStateOf(false) }
    var title by rememberSaveable(task.id, task.title) { mutableStateOf(task.title) }
    var handleOrigin by remember { mutableStateOf(Offset.Zero) }
    val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { it != SwipeToDismissBoxValue.StartToEnd })
    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> onReveal()
            SwipeToDismissBoxValue.Settled -> if (revealed) onClose()
            else -> Unit
        }
    }
    LaunchedEffect(revealed) { if (!revealed && dismissState.currentValue != SwipeToDismissBoxValue.Settled) dismissState.reset() }
    val editLabel = stringResource(R.string.edit)
    val renameLabel = stringResource(R.string.rename)
    val completeLabel = stringResource(R.string.complete)
    val activateLabel = stringResource(R.string.activate)
    val deactivateLabel = stringResource(R.string.deactivate)
    val moveLabel = stringResource(R.string.move_task, task.title)
    val moveEarlierLabel = stringResource(R.string.move_earlier)
    val moveLaterLabel = stringResource(R.string.move_later)
    Box(Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) }) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                if (dismissState.currentValue != SwipeToDismissBoxValue.Settled || dismissState.targetValue != SwipeToDismissBoxValue.Settled || revealed) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End) {
                    TaskSwipeAction(renameLabel, MaterialTheme.colorScheme.secondaryContainer) { onClose(); renaming = true }
                    when (task.status) {
                        "active" -> {
                            TaskSwipeAction(completeLabel, MaterialTheme.colorScheme.primaryContainer) { onStatus("completed") }
                            TaskSwipeAction(deactivateLabel, MaterialTheme.colorScheme.tertiaryContainer) { onStatus("paused") }
                        }
                        "paused" -> {
                            TaskSwipeAction(activateLabel, MaterialTheme.colorScheme.primaryContainer) { onStatus("active") }
                            TaskSwipeAction(completeLabel, MaterialTheme.colorScheme.tertiaryContainer) { onStatus("completed") }
                        }
                        else -> TaskSwipeAction(activateLabel, MaterialTheme.colorScheme.primaryContainer) { onStatus("active") }
                    }
                }
            },
        ) {
            Card(
                Modifier.fillMaxWidth().then(if (dropTarget) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(editLabel) { onEdit(); true },
                            CustomAccessibilityAction(renameLabel) { renaming = true; true },
                            CustomAccessibilityAction(moveLabel) { onRequestMove(); true },
                            CustomAccessibilityAction(completeLabel) { onStatus("completed"); true },
                        )
                    },
            ) {
                Row(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(8) * 16).dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (renaming) {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.task_title)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank()) { onRename(title); renaming = false } }),
                            )
                            Row {
                                TextButton(enabled = title.isNotBlank(), onClick = { onRename(title); renaming = false }) { Text(stringResource(R.string.save)) }
                                TextButton(onClick = { title = task.title; renaming = false }) { Text(stringResource(R.string.cancel)) }
                            }
                        } else {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                    if (revealed) onClose() else if (!onDismissOther()) onEdit()
                                }.semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(renameLabel) { renaming = true; true },
                                        CustomAccessibilityAction(moveLabel) { onRequestMove(); true },
                                    )
                                }.padding(vertical = 12.dp),
                            )
                        }
                        TaskRowDetails(task, state, taskState, detailMode)
                    }
                    Box {
                    IconButton(
                        modifier = Modifier.onGloballyPositioned { handleOrigin = it.positionInRoot() }.then(if (manualOrder) Modifier.pointerInput(task.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart(handleOrigin + it) },
                                onDrag = { change, _ -> change.consume(); onDrag(handleOrigin + change.position) },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragEnd,
                            )
                        } else Modifier).semantics {
                            contentDescription = moveLabel
                            customActions = if (manualOrder) listOf(
                                CustomAccessibilityAction(moveEarlierLabel) { onMoveEarlier(); true },
                                CustomAccessibilityAction(moveLaterLabel) { onMoveLater(); true },
                            ) else emptyList()
                        },
                        onClick = onRequestMove,
                    ) { Icon(Icons.Filled.DragHandle, contentDescription = null) }
                    DropdownMenu(expanded = moveMenuExpanded, onDismissRequest = onMoveMenuDismiss) {
                        DropdownMenuItem(text = { Text(moveEarlierLabel) }, enabled = canMoveEarlier, onClick = { onMoveEarlier(); onMoveMenuDismiss() })
                        DropdownMenuItem(text = { Text(moveLaterLabel) }, enabled = canMoveLater, onClick = { onMoveLater(); onMoveMenuDismiss() })
                    }
                    }
                }
            }
        }
        if (dragging) Surface(Modifier.matchParentSize(), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) {}
    }
}

@Composable
private fun TaskSwipeAction(label: String, color: Color, action: () -> Unit) {
    Surface(color = color, modifier = Modifier.fillMaxHeight().width(96.dp).clickable(onClick = action)) {
        Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun TaskRowDetails(task: TaskRow, state: DashboardState, taskState: TaskState, mode: TaskDetailMode) {
    if (mode == TaskDetailMode.TITLE) return
    val latest = taskState.comments.firstOrNull { it.taskId == task.id }?.text
    Text(latest?.let { stringResource(R.string.latest_comment, it) } ?: stringResource(R.string.no_comment), style = MaterialTheme.typography.bodySmall)
    if (mode == TaskDetailMode.TITLE_COMMENT) return
    val next = task.nextActionDateEpochDay?.let { date -> LocalDate.ofEpochDay(date).toString() + task.nextActionMinuteOfDay?.let { " %02d:%02d".format(it / 60, it % 60) }.orEmpty() } ?: "—"
    Text("${stringResource(R.string.next_action)}: $next", style = MaterialTheme.typography.bodySmall)
    if (mode != TaskDetailMode.ALL_FIELDS) return
    val parent = task.categoryId?.let { id -> state.categories.firstOrNull { it.id == id }?.name }
        ?: task.parentTaskId?.let { id -> taskState.tasks.firstOrNull { it.id == id }?.title } ?: "—"
    Text("$parent · ${task.estimateMinutes} ${stringResource(R.string.minutes_short)} · ${taskStatusLabel(task.status)}", style = MaterialTheme.typography.bodySmall)
    Text("${stringResource(R.string.deadline_short)}: ${task.deadlineEpochMs?.let(::formatEpoch) ?: "—"} · ${stringResource(R.string.task_value)}: ${task.value} · ${stringResource(R.string.task_progress)}: ${task.progress}", style = MaterialTheme.typography.bodySmall)
    Text("${taskEnergyLabel(task.energy)} · ${stringResource(if (task.splittable) R.string.splittable else R.string.not_splittable)} · ${stringResource(R.string.priority_value, task.sortOrder)}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TaskDetailsScreen(taskId: String, state: DashboardState, taskState: TaskState, settings: TaskListSettings, vm: MainViewModel, padding: PaddingValues, onOpenTask: (String) -> Unit) {
    val task = taskState.tasks.firstOrNull { it.id == taskId }
    if (task == null) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.task_unavailable)) }
        return
    }
    var addSubtask by rememberSaveable(task.id) { mutableStateOf(false) }
    var addComment by rememberSaveable(task.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(task.id) { mutableStateOf(false) }
    var settingsExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val subtasks = remember(taskState.tasks, settings, task.id) { taskListItems(taskState.tasks, settings, task.id, subtaskScope = true) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeading(R.string.task) }
        item(key = "editor-${task.id}") {
            TaskEditor(task, state, taskState, vm, onAddComment = { addComment = true }, onDelete = { confirmDelete = true })
        }
        item(key = "subtask-toolbar") {
            Text(stringResource(R.string.subtasks), style = MaterialTheme.typography.titleLarge)
            TaskListToolbar(settings, settingsExpanded, true, { settingsExpanded = it }, vm::updateTaskListSettings) { addSubtask = true }
        }
        item(key = "subtask-list") { TaskSubtaskList(subtasks, state, taskState, settings, vm, onOpenTask) }
    }
    if (addSubtask) TaskDialog(state, task.id, null, { addSubtask = false }) { draft -> vm.createTask(draft.title, null, task.id, draft.estimate, draft.nextDate, draft.nextMinute, draft.deadline) { success -> if (success) addSubtask = false } }
    if (addComment) NameDialog(stringResource(R.string.add_comment), stringResource(R.string.comment), onDismiss = { addComment = false }) { vm.addTaskComment(task.id, it); addComment = false }
    if (confirmDelete) ConfirmDialog(stringResource(R.string.delete_task_message), { confirmDelete = false }) { vm.deleteTask(task.id); confirmDelete = false }
}

@Composable
private fun TaskSubtaskList(items: List<TaskListItem>, state: DashboardState, taskState: TaskState, settings: TaskListSettings, vm: MainViewModel, onOpenTask: (String) -> Unit) {
    var revealedId by rememberSaveable { mutableStateOf<String?>(null) }
    var moveMenuId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMoveId by rememberSaveable { mutableStateOf<String?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }
    val dragTarget = dragPosition?.let { point ->
        val source = taskState.tasks.firstOrNull { it.id == draggingId }
        rowBounds.entries.firstOrNull { (id, bounds) ->
            val target = taskState.tasks.firstOrNull { it.id == id }
            source != null && target != null && validPriorityTarget(source, target, settings.listMode) && bounds.contains(point)
        }?.key
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) Text(stringResource(R.string.no_tasks_for_filters), style = MaterialTheme.typography.bodySmall)
        items.forEachIndexed { index, item ->
            val earlier = visibleMoveNeighbor(items, index, settings.listMode, -1)
            val later = visibleMoveNeighbor(items, index, settings.listMode, 1)
            TaskListRow(
                task = item.task, depth = item.depth, state = state, taskState = taskState, detailMode = settings.detailMode,
                revealed = revealedId == item.task.id, dragging = draggingId == item.task.id, dropTarget = dragTarget == item.task.id,
                onBounds = { rowBounds[item.task.id] = it }, onReveal = { revealedId = item.task.id }, onClose = { if (revealedId == item.task.id) revealedId = null },
                onDismissOther = { val hadOpen = revealedId != null; revealedId = null; hadOpen },
                onRename = { vm.renameTask(item.task.id, it) }, onEdit = { onOpenTask(item.task.id) },
                manualOrder = settings.sortMode == TaskSortMode.PRIORITY,
                moveMenuExpanded = moveMenuId == item.task.id,
                canMoveEarlier = earlier != null,
                canMoveLater = later != null,
                onMoveMenuDismiss = { moveMenuId = null },
                onRequestMove = {
                    revealedId = null
                    if (settings.sortMode == TaskSortMode.PRIORITY) moveMenuId = item.task.id
                    else pendingMoveId = item.task.id
                },
                onStatus = { vm.setTaskStatus(item.task.id, it); revealedId = null },
                onDragStart = { point ->
                    revealedId = null; draggingId = item.task.id; dragPosition = point
                },
                onDrag = { dragPosition = it },
                onDragEnd = {
                    val target = dragTarget
                    val bounds = target?.let(rowBounds::get)
                    if (target != null && bounds != null && dragPosition != null) vm.reorderTask(item.task.id, target, dragPosition!!.y > bounds.center.y)
                    draggingId = null; dragPosition = null
                },
                onMoveEarlier = { if (settings.sortMode == TaskSortMode.PRIORITY && earlier != null) vm.reorderTask(item.task.id, earlier.id, false) },
                onMoveLater = { if (settings.sortMode == TaskSortMode.PRIORITY && later != null) vm.reorderTask(item.task.id, later.id, true) },
            )
        }
    }
    if (pendingMoveId != null) PrioritySortDialog(
        onDismiss = { pendingMoveId = null },
        onConfirm = {
            moveMenuId = pendingMoveId
            pendingMoveId = null
            vm.updateTaskListSettings(settings.copy(sortMode = TaskSortMode.PRIORITY))
        },
    )
}

@Composable
private fun PrioritySortDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.switch_to_priority)) },
        text = { Text(stringResource(R.string.switch_to_priority_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.switch_to_priority)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TaskEditor(task: TaskRow, state: DashboardState, taskState: TaskState, vm: MainViewModel, onAddComment: () -> Unit, onDelete: () -> Unit) {
    var title by rememberSaveable(task.id, task.title) { mutableStateOf(task.title) }
    var estimate by rememberSaveable(task.id, task.estimateMinutes) { mutableStateOf(task.estimateMinutes.toString()) }
    var nextDate by rememberSaveable(task.id, task.nextActionDateEpochDay) { mutableStateOf(task.nextActionDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
    var nextTime by rememberSaveable(task.id, task.nextActionMinuteOfDay) { mutableStateOf(task.nextActionMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty()) }
    var deadline by rememberSaveable(task.id, task.deadlineEpochMs) { mutableStateOf(task.deadlineEpochMs?.let(::formatEpoch).orEmpty()) }
    var value by rememberSaveable(task.id, task.value) { mutableStateOf(task.value.toString()) }
    var progress by rememberSaveable(task.id, task.progress) { mutableStateOf(task.progress.toString()) }
    var energy by rememberSaveable(task.id, task.energy) { mutableStateOf(task.energy) }
    var status by rememberSaveable(task.id, task.status) { mutableStateOf(task.status) }
    var splittable by rememberSaveable(task.id, task.splittable) { mutableStateOf(task.splittable) }
    val forbiddenParents = remember(taskState.tasks, task.id) { descendantTaskIds(taskState.tasks, task.id) + task.id }
    val parentOptions = state.categories.filter { !it.archived }.map { "category:${it.id}" } + taskState.tasks.filter { it.id !in forbiddenParents }.map { "task:${it.id}" }
    var parent by rememberSaveable(task.id, task.categoryId, task.parentTaskId) { mutableStateOf(task.categoryId?.let { "category:$it" } ?: "task:${task.parentTaskId}") }
    val parsedDate = nextDate.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val parsedTime = nextTime.takeIf(String::isNotBlank)?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
    val parsedDeadline = deadline.takeIf(String::isNotBlank)?.let(::parseEpoch)
    val estimateValue = estimate.toIntOrNull(); val taskValue = value.toIntOrNull(); val progressValue = progress.toIntOrNull()
    val valid = title.isNotBlank() && parent in parentOptions && estimateValue != null && taskValue in 0..100 && progressValue in 0..100 &&
        (status != "active" || parsedDate != null) && (nextTime.isBlank() || parsedTime != null) && (deadline.isBlank() || parsedDeadline != null)
    var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id) { while (true) { delay(30_000); clockNow = System.currentTimeMillis() } }
    val firstEventAt = state.events.minOfOrNull { it.occurredAtEpochMs }
    val spent = if (firstEventAt != null && firstEventAt < clockNow) TimeEngine.taskSpentMillis(
        TimeEngine.intervals(state.events.map { EventPoint(it.id, it.categoryTreeId, it.categoryId, it.occurredAtEpochMs, it.taskId) }, firstEventAt, clockNow, clockNow), task.id,
    ) else 0L
    val deviation = spent - task.estimateMinutes * 60_000L
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.task_section_main), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.task_title)) })
            SelectionMenu(stringResource(R.string.task_parent), parent, parentOptions, { option ->
                val (type, id) = option.split(':', limit = 2)
                if (type == "category") state.categories.firstOrNull { it.id == id }?.name.orEmpty() else taskState.tasks.firstOrNull { it.id == id }?.title.orEmpty()
            }) { parent = it }
            Text(stringResource(R.string.task_section_schedule), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(estimate, { estimate = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.estimate_minutes)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("${stringResource(R.string.spent_time)}: ${formatDuration(spent)} ${stringResource(R.string.minutes_short)}")
            Text("${stringResource(R.string.estimate_deviation)}: ${if (deviation >= 0) "+" else ""}${formatDuration(deviation)} ${stringResource(R.string.minutes_short)}")
            DateField(nextDate, stringResource(R.string.next_action_date), { nextDate = it }, Modifier.fillMaxWidth(), optional = status != "active", invalid = status == "active" && parsedDate == null, presets = DatePresets.NEXT_ACTION)
            TimeField(nextTime, stringResource(R.string.next_action_time), { nextTime = it }, Modifier.fillMaxWidth(), optional = true, invalid = nextTime.isNotBlank() && parsedTime == null)
            DateTimeField(deadline, stringResource(R.string.deadline), { deadline = it }, Modifier.fillMaxWidth(), optional = true, invalid = deadline.isNotBlank() && parsedDeadline == null, presets = DateTimePresets.DEADLINE)
            Text(stringResource(R.string.task_section_more), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value, { value = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.task_value)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(progress, { progress = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.task_progress)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            SelectionMenu(stringResource(R.string.task_energy), energy, listOf("low", "medium", "high"), { taskEnergyLabel(it) }) { energy = it }
            SelectionMenu(stringResource(R.string.field_status), status, listOf("active", "paused", "completed", "cancelled"), { taskStatusLabel(it) }) { status = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(splittable, { splittable = it }); Text(stringResource(R.string.splittable)) }
            Button(modifier = Modifier.fillMaxWidth(), enabled = valid, onClick = {
                val categoryId = parent.removePrefix("category:").takeIf { parent.startsWith("category:") }
                val parentId = parent.removePrefix("task:").takeIf { parent.startsWith("task:") }
                vm.updateTaskDetails(task.id, title, categoryId, parentId, requireNotNull(estimateValue), parsedDate, parsedTime?.let { it.hour * 60 + it.minute }, parsedDeadline, requireNotNull(taskValue), energy, requireNotNull(progressValue), status, splittable)
            }) { Text(stringResource(R.string.save)) }
            HorizontalDivider()
            TextButton(onClick = onAddComment) { Text(stringResource(R.string.add_comment)) }
            taskState.comments.filter { it.taskId == task.id }.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun EventTaskLink(taskId: String?, tasks: List<TaskRow>, onOpenTask: (String) -> Unit) {
    if (taskId == null) return
    val task = tasks.firstOrNull { it.id == taskId }
    if (task == null) Text(stringResource(R.string.task_unavailable), style = MaterialTheme.typography.bodySmall)
    else TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { onOpenTask(task.id) }) { Text(stringResource(R.string.event_task, task.title)) }
}

@Composable
internal fun SubtaskSection(parentTaskId: String, tasks: List<TaskRow>, onOpenTask: (String) -> Unit) {
    val subtasks = directSubtasks(parentTaskId, tasks)
    if (subtasks.isEmpty()) return
    var expanded by rememberSaveable(parentTaskId) { mutableStateOf(false) }
    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.hide_subtasks else R.string.view_subtasks))
    }
    if (expanded) Column {
        subtasks.forEach { subtask ->
            val status = taskStatusLabel(subtask.status)
            TextButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = status },
                onClick = { onOpenTask(subtask.id) },
            ) { Text(subtask.title, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun TaskDialog(state: DashboardState, parentTaskId: String?, existing: TaskRow?, onDismiss: () -> Unit, onSave: (TaskDraft) -> Unit) {
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }; val choices = state.categories.filter { !it.archived }; var category by rememberSaveable { mutableStateOf(existing?.categoryId ?: choices.firstOrNull()?.id) }; var estimate by rememberSaveable { mutableStateOf((existing?.estimateMinutes ?: 60).toString()) }
    var date by rememberSaveable { mutableStateOf(existing?.nextActionDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: LocalDate.now().toString()) }
    var nextTime by rememberSaveable { mutableStateOf(existing?.nextActionMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty()) }
    var deadline by rememberSaveable { mutableStateOf(existing?.deadlineEpochMs?.let(::formatEpoch).orEmpty()) }
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull(); val parsedNextTime = nextTime.takeIf(String::isNotBlank)?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }; val parsedDeadline = deadline.takeIf(String::isNotBlank)?.let(::parseEpoch)
    val timesValid = (nextTime.isBlank() || parsedNextTime != null) && (deadline.isBlank() || parsedDeadline != null)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (parentTaskId == null) R.string.new_task else R.string.add_subtask)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.task_title)) })
        if (parentTaskId == null) SelectionMenu(stringResource(R.string.category), category, choices.map { it.id }, { id -> choices.first { it.id == id }.name }) { category = it }
        OutlinedTextField(estimate, { estimate = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.estimate_minutes)) })
        DateField(date, stringResource(R.string.next_action_date), { date = it }, invalid = parsedDate == null, presets = DatePresets.NEXT_ACTION)
        TimeField(nextTime, stringResource(R.string.next_action_time), { nextTime = it }, optional = true, invalid = nextTime.isNotBlank() && parsedNextTime == null)
        DateTimeField(deadline, stringResource(R.string.deadline), { deadline = it }, optional = true, invalid = deadline.isNotBlank() && parsedDeadline == null, presets = DateTimePresets.DEADLINE)
    } }, confirmButton = { TextButton(enabled = title.isNotBlank() && estimate.toIntOrNull() != null && parsedDate != null && timesValid && (parentTaskId != null || category != null), onClick = { onSave(TaskDraft(title, category, estimate.toInt(), requireNotNull(parsedDate), parsedNextTime?.let { it.hour * 60 + it.minute }, parsedDeadline)) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ReportsScreen(state: DashboardState, vm: MainViewModel, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
        item { ScreenHeading(R.string.reports) }
        items(vm.reportToday()) { row ->
        val tree = state.categoryTrees.firstOrNull { it.id == row.categoryTreeId }?.name.orEmpty(); val category = state.categories.firstOrNull { it.id == row.categoryId }?.name ?: stringResource(R.string.unknown)
        Text("$tree · $category — ${row.seconds / 60} ${stringResource(R.string.minutes_short)}", Modifier.padding(vertical = 8.dp))
    } }
}

@Composable
private fun NameDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) { var value by rememberSaveable(initial) { mutableStateOf(initial) }; AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text(label) }) }, confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onSave(value) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }) }
@Composable private fun ConfirmDialog(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.confirm_action)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })

@Composable
private fun SettingsRoot(padding: PaddingValues, navigate: (Screen) -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeading(R.string.settings)
        SettingsButton(R.string.network) { navigate(Screen.NETWORK) }
        SettingsButton(R.string.pomodoro_timer) { navigate(Screen.TIMER_SETTINGS) }
        SettingsButton(R.string.backup) { navigate(Screen.BACKUP) }
        SettingsButton(R.string.security) { navigate(Screen.SECURITY) }
        SettingsButton(R.string.diagnostics) { navigate(Screen.DIAGNOSTICS) }
        SettingsButton(R.string.language) { navigate(Screen.LANGUAGE) }
    }
}

@Composable
private fun SettingsScreen(section: SettingsSection, vm: MainViewModel, syncState: SyncUiState, personalConflicts: List<PersonalConflictRow>, dashboard: DashboardState, tasks: TaskState,
    planner: PlannerState, workspaces: List<app.t4l.data.WorkspaceOption>, padding: PaddingValues, navigate: (Screen) -> Unit) {
    var level by rememberSaveable { mutableStateOf(vm.diagnosticLevel()) }; var appLock by rememberSaveable { mutableStateOf(vm.appLockEnabled()) }
    var serverUrl by rememberSaveable { mutableStateOf(vm.serverBaseUrl()) }; var syncDelay by rememberSaveable { mutableStateOf(vm.syncDelay()) }
    var valid by rememberSaveable { mutableStateOf(true) }; var passwordDialog by rememberSaveable { mutableStateOf(false) }; var password by rememberSaveable { mutableStateOf("") }
    var passwordPurpose by rememberSaveable { mutableStateOf(PasswordPurpose.EXPORT) }; var exportEncrypted by rememberSaveable { mutableStateOf(false) }
    var pendingExportUri by rememberSaveable { mutableStateOf<String?>(null) }
    val backupState by vm.backupState.collectAsStateWithLifecycle()
    val context = LocalContext.current; val configuration = LocalConfiguration.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && exportEncrypted) { pendingExportUri = uri.toString(); passwordPurpose = PasswordPurpose.EXPORT; passwordDialog = true }
        else if (uri != null) vm.exportBackup(uri)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.inspectBackup(uri) }
    LaunchedEffect(backupState) {
        if (backupState == BackupUiState.PasswordRequired) { passwordPurpose = PasswordPurpose.IMPORT; passwordDialog = true }
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeading(when (section) {
            SettingsSection.ROOT -> R.string.settings
            SettingsSection.NETWORK -> R.string.network
            SettingsSection.BACKUP -> R.string.backup
            SettingsSection.SECURITY -> R.string.security
            SettingsSection.DIAGNOSTICS -> R.string.diagnostics
            SettingsSection.SYNC_ISSUES -> R.string.sync_issues
            SettingsSection.LANGUAGE -> R.string.language
        })
        when (section) {
            SettingsSection.ROOT -> Unit
            SettingsSection.NETWORK -> { OutlinedTextField(serverUrl, { serverUrl = it; valid = true }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.server_url)) }, isError = !valid); if (workspaces.isNotEmpty()) SelectionMenu(stringResource(R.string.workspace), vm.selectedWorkspace(), workspaces.map { it.id }, { id -> workspaces.firstOrNull { it.id == id }?.let { "${it.name} (${it.role})" } ?: id }) { vm.selectWorkspace(it) }; SelectionMenu(stringResource(R.string.sync_delay), syncDelay, SyncDelay.entries, { delayLabel(it) }) { syncDelay = it }; Button(modifier = Modifier.fillMaxWidth(), onClick = { valid = vm.updateNetworkSettings(serverUrl, syncDelay) }) { Text(stringResource(R.string.apply)) }; Button(modifier = Modifier.fillMaxWidth(), onClick = vm::syncNow) { Text(stringResource(R.string.sync_now)) } }
            SettingsSection.BACKUP -> { val busy = backupState is BackupUiState.Exporting || backupState is BackupUiState.Importing; if (busy) CircularProgressIndicator(); Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { exportEncrypted = false; export.launch("t4l-backup-v2.json") }) { Text(stringResource(R.string.export_plain)) }; Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { exportEncrypted = true; export.launch("t4l-backup-v2.t4lbackup") }) { Text(stringResource(R.string.export_encrypted)) }; Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { import.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text(stringResource(R.string.import_backup)) } }
            SettingsSection.SECURITY -> { Button(modifier = Modifier.fillMaxWidth(), onClick = { val next = !appLock; if (vm.setAppLockEnabled(next)) appLock = next }) { Text("${stringResource(R.string.app_lock)}: ${stringResource(if (appLock) R.string.on else R.string.off)}") }; if (vm.oidcEnabled()) Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.signOut { (context as? AppCompatActivity)?.recreate() } }) { Text(stringResource(R.string.sign_out)) } }
            SettingsSection.DIAGNOSTICS -> { Text(syncSummary(syncState, personalConflicts.size)); DiagnosticLevel.entries.forEach { candidate -> Button(modifier = Modifier.fillMaxWidth(), onClick = { level = candidate; vm.setDiagnosticLevel(candidate) }) { Text((if (candidate == level) "✓ " else "") + diagnosticLabel(candidate)) } }; Button(modifier = Modifier.fillMaxWidth(), onClick = { navigate(Screen.SYNC_ISSUES) }) { Text(stringResource(R.string.sync_issues_count, syncState.conflicts.size + syncState.failed.size + personalConflicts.size)) } }
            SettingsSection.SYNC_ISSUES -> SyncIssues(syncState, personalConflicts, dashboard, tasks, planner, vm)
            SettingsSection.LANGUAGE -> { val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').takeIf { it in setOf("en", "ru") } ?: if (configuration.locales[0].language == "ru") "ru" else "en"; SelectionMenu(stringResource(R.string.language), current, listOf("en", "ru"), { if (it == "ru") stringResource(R.string.russian) else stringResource(R.string.english) }) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) } }
        }
    }
    if (passwordDialog) AlertDialog(onDismissRequest = { password = ""; passwordDialog = false; if (passwordPurpose == PasswordPurpose.IMPORT) vm.cancelBackupPassword() }, title = { Text(stringResource(R.string.backup_password)) }, text = { OutlinedTextField(password, { password = it }, visualTransformation = PasswordVisualTransformation()) }, confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = {
        val chars = password.toCharArray(); password = ""; passwordDialog = false
        if (passwordPurpose == PasswordPurpose.IMPORT) vm.importPendingBackup(chars) else pendingExportUri?.let { vm.exportBackup(it.toUri(), chars) }
        pendingExportUri = null
    }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { password = ""; passwordDialog = false; pendingExportUri = null; if (passwordPurpose == PasswordPurpose.IMPORT) vm.cancelBackupPassword() }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SyncIssues(state: SyncUiState, personalConflicts: List<PersonalConflictRow>, dashboard: DashboardState, tasks: TaskState, planner: PlannerState, vm: MainViewModel) {
    var editingConflict by rememberSaveable { mutableStateOf<String?>(null) }
    var editingRejected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingChoice by rememberSaveable { mutableStateOf(ConflictChoice.SERVER) }
    var payload by rememberSaveable { mutableStateOf("") }
    if (state.conflicts.isEmpty() && state.failed.isEmpty() && personalConflicts.isEmpty()) Text(stringResource(R.string.no_sync_issues))
    personalConflicts.forEach { issue -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (issue.kind == "profile") R.string.personal_profile_conflict else R.string.personal_avatar_conflict), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.personal_conflict_explanation), style = MaterialTheme.typography.bodySmall)
        if (issue.kind == "profile") {
            val presentation = remember(issue) { ConflictPresenter.present(ConflictRow(issue.clientMutationId, "", "profile", issue.userId, issue.localPayloadJson, issue.serverPayloadJson, issue.serverRevision, issue.createdAtEpochMs)) }
            presentation.fields.forEach { field -> Text("${field.key}: ${field.localValue} ↔ ${field.serverValue}") }
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.resolvePersonalConflictUseServer(issue.clientMutationId) }) { Text(stringResource(R.string.keep_server)) }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.resolvePersonalConflictUseLocal(issue.clientMutationId) }) { Text(stringResource(R.string.send_local)) }
    } } }
    state.conflicts.forEach { issue -> val presentation = remember(issue) { ConflictPresenter.present(issue) }; Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${conflictEntityLabel(presentation.entityType)} · ${presentation.title}", style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.conflict_explanation), style = MaterialTheme.typography.bodySmall)
        if (presentation.fields.isEmpty()) Text(stringResource(R.string.no_visible_differences))
        presentation.fields.forEach { field ->
            Text(conflictFieldLabel(field.key), style = MaterialTheme.typography.labelLarge)
            Text("${stringResource(R.string.this_device)}: ${resolveConflictValue(field.key, field.localValue, dashboard, tasks, planner)}")
            Text("${stringResource(R.string.server)}: ${resolveConflictValue(field.key, field.serverValue, dashboard, tasks, planner)}")
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { confirmingId = issue.clientMutationId; confirmingChoice = ConflictChoice.SERVER }) { Text(stringResource(R.string.keep_server)) }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { confirmingId = issue.clientMutationId; confirmingChoice = ConflictChoice.LOCAL }) { Text(stringResource(R.string.send_local)) }
        TextButton(onClick = { editingConflict = issue.clientMutationId; payload = issue.localPayloadJson }) { Text(stringResource(R.string.edit_and_retry)) }
    } } }
    state.failed.forEach { issue -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${conflictEntityLabel(issue.entityType)} · ${stringResource(R.string.sync_rejected)}", style = MaterialTheme.typography.titleSmall)
        Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.retryRejected(issue.clientMutationId) }) { Text(stringResource(R.string.retry)) }
        TextButton(onClick = { editingRejected = issue.clientMutationId; payload = issue.payloadJson }) { Text(stringResource(R.string.edit_and_retry)) }
    } } }
    confirmingId?.let { id -> AlertDialog(onDismissRequest = { confirmingId = null }, title = { Text(stringResource(R.string.confirm_action)) }, text = {
        Text(stringResource(if (confirmingChoice == ConflictChoice.SERVER) R.string.confirm_keep_server else R.string.confirm_send_local))
    }, confirmButton = { TextButton(onClick = { if (confirmingChoice == ConflictChoice.SERVER) vm.resolveConflictUseServer(id) else vm.resolveConflictUseLocal(id); confirmingId = null }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = { confirmingId = null }) { Text(stringResource(R.string.cancel)) } }) }
    val editedId = editingConflict ?: editingRejected
    val payloadValid = ConflictPresenter.isJsonObject(payload)
    if (editedId != null) AlertDialog(onDismissRequest = { editingConflict = null; editingRejected = null }, title = { Text(stringResource(R.string.edit_payload)) }, text = {
        OutlinedTextField(payload, { payload = it }, modifier = Modifier.fillMaxWidth(), minLines = 8, label = { Text("JSON") }, isError = !payloadValid, supportingText = { if (!payloadValid) Text(stringResource(R.string.invalid_json)) })
    }, confirmButton = { TextButton(enabled = payloadValid, onClick = { if (editingConflict != null) vm.resolveConflictUseLocal(editedId, payload) else vm.retryRejected(editedId, payload); editingConflict = null; editingRejected = null }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { editingConflict = null; editingRejected = null }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun conflictEntityLabel(value: String) = stringResource(when (value.lowercase()) {
    "categorytree" -> R.string.category_tree; "category" -> R.string.category; "event" -> R.string.event
    "task" -> R.string.task; "taskcomment" -> R.string.comment; "plan" -> R.string.plan
    "budgetallocation" -> R.string.budget; "plannedevent" -> R.string.planned_event; else -> R.string.sync_item
})

@Composable private fun conflictFieldLabel(value: String): String = when (value) {
    "name" -> stringResource(R.string.field_name); "title" -> stringResource(R.string.field_title); "text" -> stringResource(R.string.comment)
    "categoryTreeId" -> stringResource(R.string.category_tree); "categoryId" -> stringResource(R.string.category)
    "taskId", "parentTaskId" -> stringResource(R.string.task); "planId" -> stringResource(R.string.plan)
    "occurredAt" -> stringResource(R.string.field_time); "startsAt" -> stringResource(R.string.plan_start); "endsAt" -> stringResource(R.string.plan_end)
    "estimateMinutes" -> stringResource(R.string.estimate_minutes); "ownMinutes" -> stringResource(R.string.own_minutes); "sortOrder" -> stringResource(R.string.sort_priority)
    "deadline" -> stringResource(R.string.deadline); "nextActionDate" -> stringResource(R.string.next_action_date)
    "status" -> stringResource(R.string.field_status); "archived" -> stringResource(R.string.field_archived)
    else -> value
}

@Composable private fun resolveConflictValue(key: String, raw: String, dashboard: DashboardState, tasks: TaskState, planner: PlannerState): String {
    if (raw == "—") return raw
    return when (key) {
        "categoryTreeId" -> dashboard.categoryTrees.firstOrNull { it.id == raw }?.name ?: raw
        "categoryId" -> dashboard.categories.firstOrNull { it.id == raw }?.name ?: raw
        "taskId", "parentTaskId" -> tasks.tasks.firstOrNull { it.id == raw }?.title ?: raw
        "planId" -> planner.plans.firstOrNull { it.id == raw }?.name ?: raw
        "archived", "splittable" -> stringResource(if (raw == "true") R.string.yes else R.string.no)
        else -> raw
    }
}

@Composable private fun SettingsButton(label: Int, action: () -> Unit) = Button(modifier = Modifier.fillMaxWidth(), onClick = action) { Text(stringResource(label)) }
@Composable private fun BackTitle(label: Int, action: () -> Unit) { Row { TextButton(onClick = action) { Text(stringResource(R.string.back)) }; Text(stringResource(label), style = MaterialTheme.typography.headlineSmall) } }
@Composable internal fun <T> SelectionMenu(label: String, selected: T, options: List<T>, text: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val validSelection = selected in options
    LaunchedEffect(selected, options) { if (!validSelection && options.isNotEmpty()) onSelect(options.first()) }
    Box(Modifier.fillMaxWidth()) {
        Button(modifier = Modifier.fillMaxWidth(), enabled = options.isNotEmpty(), onClick = { expanded = true }) { Text("$label: ${if (validSelection) text(selected) else "—"}") }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { item -> DropdownMenuItem(text = { Text(text(item)) }, onClick = { expanded = false; onSelect(item) }) } }
    }
}
@Composable private fun delayLabel(value: SyncDelay) = stringResource(when (value) { SyncDelay.IMMEDIATELY -> R.string.delay_immediately; SyncDelay.FIVE_SECONDS -> R.string.delay_5_seconds; SyncDelay.FIFTEEN_SECONDS -> R.string.delay_15_seconds; SyncDelay.THIRTY_SECONDS -> R.string.delay_30_seconds; SyncDelay.ONE_MINUTE -> R.string.delay_1_minute; SyncDelay.FIVE_MINUTES -> R.string.delay_5_minutes })
@Composable private fun diagnosticLabel(value: DiagnosticLevel) = stringResource(when (value) { DiagnosticLevel.OFF -> R.string.diagnostic_off; DiagnosticLevel.BASIC -> R.string.diagnostic_basic; DiagnosticLevel.VERBOSE -> R.string.diagnostic_verbose })
@Composable private fun taskStatusLabel(value: String) = stringResource(when (value) { "active" -> R.string.status_active; "paused" -> R.string.status_paused; "completed" -> R.string.status_completed; else -> R.string.status_cancelled })
@Composable private fun taskListModeLabel(value: TaskListMode) = stringResource(when (value) { TaskListMode.TOP_LEVEL -> R.string.task_view_top; TaskListMode.ALL -> R.string.task_view_all; TaskListMode.TREE -> R.string.task_view_tree })
@Composable private fun taskSortModeLabel(value: TaskSortMode) = stringResource(when (value) { TaskSortMode.PRIORITY -> R.string.sort_priority; TaskSortMode.NEXT_ACTION -> R.string.sort_next_action; TaskSortMode.DEADLINE -> R.string.sort_deadline })
@Composable private fun taskDetailModeLabel(value: TaskDetailMode) = stringResource(when (value) { TaskDetailMode.TITLE -> R.string.details_title; TaskDetailMode.TITLE_COMMENT -> R.string.details_comment; TaskDetailMode.TITLE_COMMENT_NEXT -> R.string.details_next; TaskDetailMode.ALL_FIELDS -> R.string.details_all })
@Composable private fun taskEnergyLabel(value: String) = stringResource(when (value) { "low" -> R.string.energy_low; "high" -> R.string.energy_high; else -> R.string.energy_medium })
private fun formatEpoch(value: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault()).format(dateTimeFormat)
private fun parseEpoch(value: String): Long? = runCatching { LocalDateTime.parse(value, dateTimeFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
