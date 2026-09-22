package app.t4l

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    PROFILE("profile", R.string.profile, false), LIFE("life", R.string.life_visualization, false),
    NETWORK("settings/network", R.string.network, false), BACKUP("settings/backup", R.string.backup, false),
    SECURITY("settings/security", R.string.security, false), DIAGNOSTICS("settings/diagnostics", R.string.diagnostics, false),
    SYNC_ISSUES("settings/diagnostics/sync-issues", R.string.sync_issues, false), LANGUAGE("settings/language", R.string.language, false),
}
private enum class SettingsSection { ROOT, NETWORK, BACKUP, SECURITY, DIAGNOSTICS, SYNC_ISSUES, LANGUAGE }
private enum class PasswordPurpose { EXPORT, IMPORT }
private enum class ConflictChoice { SERVER, LOCAL }
private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private data class TaskDraft(val title: String, val categoryId: String?, val estimate: Int, val nextDate: LocalDate, val nextMinute: Int?, val deadline: Long?)
private fun taskRoute(taskId: String): String = "tasks/${Uri.encode(taskId)}"

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
    val feedback by viewModel.uiFeedback.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val screen = Screen.entries.firstOrNull { it.route == entry?.destination?.route } ?: Screen.HOME
    var menu by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val openTask: (String) -> Unit = { taskId -> navController.navigate(taskRoute(taskId)) { launchSingleTop = true } }
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
        }, title = { Column { Text("T4L · ${stringResource(screen.label)}"); Text(syncSummary(syncState, personalConflicts.size), style = MaterialTheme.typography.labelSmall) } }, actions = {
            if (screen.menuItem) {
            Box {
                TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { menu = true }) { Text(stringResource(R.string.menu)) }
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
            composable(Screen.HOME.route) { HomeScreen(profile, pomodoro, viewModel, padding) { navController.navigate(if (profile?.birthDateEpochDay != null && profile?.lifeExpectancyYears != null) Screen.LIFE.route else Screen.PROFILE.route) } }
            composable(Screen.TRACK.route) { TrackScreen(dashboard, viewModel, padding) }
            composable(Screen.HISTORY.route) { HistoryScreen(dashboard, taskState, viewModel, padding, openTask) }
            composable(Screen.PLANNER.route) { PlannerScreen(dashboard, taskState, plannerState, viewModel, padding, openTask) }
            composable(Screen.TASKS.route) { TasksScreen(dashboard, taskState, viewModel, padding, openTask) }
            composable(Screen.TASK_DETAILS.route, arguments = listOf(navArgument("taskId") { type = NavType.StringType })) { destination ->
                TaskDetailsScreen(destination.arguments?.getString("taskId").orEmpty(), dashboard, taskState, viewModel, padding, openTask)
            }
            composable(Screen.REPORTS.route) { ReportsScreen(dashboard, viewModel, padding) }
            composable(Screen.SETTINGS.route) { SettingsRoot(padding) { navController.navigate(it.route) } }
            composable(Screen.NETWORK.route) { SettingsScreen(SettingsSection.NETWORK, viewModel, syncState, personalConflicts, dashboard, taskState, plannerState, workspaces, padding) { navController.navigate(it.route) } }
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
private fun TrackScreen(state: DashboardState, vm: MainViewModel, padding: PaddingValues) {
    var newTree by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { newTree = true }) { Text(stringResource(R.string.new_category_tree)) }
                if (state.categoryTrees.isEmpty()) Button(onClick = vm::createStarterData) { Text(stringResource(R.string.create_starter)) }
            }
        }
        items(state.categoryTrees, key = { it.id }) { tree ->
            CategoryTreeCard(tree.id, tree.name, state.categories.filter { it.categoryTreeId == tree.id && !it.archived }, state.currentEvent(tree.id)?.categoryId,
                onSwitch = { vm.switch(tree.id, it) }, onAdd = { parent, name -> vm.addCategory(tree.id, name, parent) },
                onRenameCategory = { id, value -> vm.renameCategory(id, value) }, onRenameTree = { vm.renameCategoryTree(tree.id, it) },
                onDeleteCategory = vm::archiveCategory, onDeleteTree = { vm.archiveCategoryTree(tree.id) })
        }
    }
    if (newTree) NameDialog(stringResource(R.string.new_category_tree), stringResource(R.string.category_tree_name), onDismiss = { newTree = false }) { vm.createCategoryTree(it); newTree = false }
}

@Composable
private fun CategoryTreeCard(treeId: String, name: String, categories: List<CategoryRow>, current: String?, onSwitch: (String?) -> Unit,
    onAdd: (String?, String) -> Unit, onRenameCategory: (String, String) -> Unit, onRenameTree: (String) -> Unit,
    onDeleteCategory: (String) -> Unit, onDeleteTree: () -> Unit) {
    var addParent by rememberSaveable { mutableStateOf<String?>(null) }; var addDialog by rememberSaveable { mutableStateOf(false) }
    var renameCategory by remember { mutableStateOf<CategoryRow?>(null) }; var renameTree by remember { mutableStateOf(false) }
    var confirmCategory by remember { mutableStateOf<CategoryRow?>(null) }; var confirmTree by remember { mutableStateOf(false) }
    var collapsedValue by rememberSaveable(treeId) { mutableStateOf("") }
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    val collapsed = remember(collapsedValue) { collapsedValue.split(',').filter(String::isNotBlank).toSet() }
    val flat = remember(categories, collapsed) { flattenVisibleCategories(categories, collapsed) }
    val children = remember(categories) { categories.groupBy { it.parentId } }
    val viewport = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }.minus(64.dp).coerceAtLeast(280.dp)
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Row { TextButton(onClick = { renameTree = true }) { Text(stringResource(R.string.rename)) }; TextButton(onClick = { confirmTree = true }) { Text(stringResource(R.string.delete_tree)) } }
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) { flat.forEach { (category, depth) ->
            val hasChildren = children[category.id].orEmpty().isNotEmpty(); val isCollapsed = category.id in collapsed
            val expandDescription = "${category.name}: ${stringResource(if (isCollapsed) R.string.expand else R.string.collapse)}"
            val expandedState = stringResource(if (isCollapsed) R.string.collapsed else R.string.expanded)
            val levelDescription = stringResource(R.string.category_level, category.name, depth + 1)
            val actionsDescription = stringResource(R.string.category_actions, category.name)
            Row(Modifier.requiredWidth(viewport + (depth * 16).dp).padding(start = (depth * 16).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasChildren) TextButton(modifier = Modifier.size(48.dp).semantics {
                    contentDescription = expandDescription
                    stateDescription = expandedState
                }, onClick = {
                    val next = collapsed.toMutableSet(); if (!next.add(category.id)) next.remove(category.id); collapsedValue = next.sorted().joinToString(",")
                }) { Text(if (isCollapsed) "▶" else "▼") } else Spacer(Modifier.size(48.dp))
                TextButton(modifier = Modifier.weight(1f).semantics { contentDescription = levelDescription }, onClick = { onSwitch(category.id) }) { Text((if (category.id == current) "✓ " else "") + category.name) }
                Box {
                    TextButton(modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = actionsDescription }, onClick = { actionsFor = category.id }) { Text("⋮") }
                    DropdownMenu(actionsFor == category.id, { actionsFor = null }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_category)) }, onClick = { actionsFor = null; addParent = category.id; addDialog = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { actionsFor = null; renameCategory = category })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { actionsFor = null; confirmCategory = category })
                    }
                }
            }
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { addParent = null; addDialog = true }) { Text(stringResource(R.string.add_root_category)) }
            TextButton(onClick = { onSwitch(null) }) { Text(stringResource(R.string.unknown)) }
        }
    } }
    if (addDialog) NameDialog(stringResource(R.string.add_category), stringResource(R.string.category_name), onDismiss = { addDialog = false }) { onAdd(addParent, it); addDialog = false }
    if (renameTree) NameDialog(stringResource(R.string.rename), stringResource(R.string.category_tree_name), initial = name, onDismiss = { renameTree = false }) { onRenameTree(it); renameTree = false }
    renameCategory?.let { category -> NameDialog(stringResource(R.string.rename), stringResource(R.string.category_name), initial = category.name, onDismiss = { renameCategory = null }) { onRenameCategory(category.id, it); renameCategory = null } }
    confirmCategory?.let { category -> ConfirmDialog(stringResource(R.string.archive_category_message, category.name), { confirmCategory = null }) { onDeleteCategory(category.id); confirmCategory = null } }
    if (confirmTree) ConfirmDialog(pluralStringResource(R.plurals.archive_tree_message, categories.size, categories.size), { confirmTree = false }) { onDeleteTree(); confirmTree = false }
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

@Composable
private fun HistoryScreen(state: DashboardState, tasks: TaskState, vm: MainViewModel, padding: PaddingValues, onOpenTask: (String) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }; var editingId by rememberSaveable { mutableStateOf<String?>(null) }; var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Button(enabled = state.categories.isNotEmpty(), onClick = { adding = true }) { Text(stringResource(R.string.add_event)) } }
        items(state.events, key = { it.id }) { event ->
            val tree = state.categoryTrees.firstOrNull { it.id == event.categoryTreeId }?.name.orEmpty(); val category = state.categories.firstOrNull { it.id == event.categoryId }?.name ?: stringResource(R.string.unknown)
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${formatter.format(Instant.ofEpochMilli(event.occurredAtEpochMs))} · $tree · $category")
                EventTaskLink(event.taskId, tasks.tasks, onOpenTask)
                Row { TextButton(onClick = { editingId = event.id }) { Text(stringResource(R.string.edit_time)) }; TextButton(onClick = { deletingId = event.id }) { Text(stringResource(R.string.delete)) } }
            } }
        }
    }
    if (adding) EventDialog(state, tasks.tasks, System.currentTimeMillis(), onDismiss = { adding = false }) { tree, category, task, at -> vm.addEvent(tree, category, at, task) { success -> if (success) adding = false } }
    state.events.firstOrNull { it.id == editingId }?.let { event -> TimeDialog(event.occurredAtEpochMs, onDismiss = { editingId = null }) { vm.updateEvent(event.id, it); editingId = null } }
    state.events.firstOrNull { it.id == deletingId }?.let { event -> ConfirmDialog(stringResource(R.string.delete_event_message, formatter.format(Instant.ofEpochMilli(event.occurredAtEpochMs))), { deletingId = null }) { vm.deleteEvent(event.id); deletingId = null } }
}

@Composable
private fun EventDialog(state: DashboardState, tasks: List<TaskRow>, initial: Long, range: LongRange? = null, onDismiss: () -> Unit, onSave: (String, String?, String?, Long) -> Unit) {
    var tree by rememberSaveable { mutableStateOf(state.categoryTrees.firstOrNull()?.id.orEmpty()) }
    val choices = state.categories.filter { it.categoryTreeId == tree && !it.archived }; var category by rememberSaveable { mutableStateOf(choices.firstOrNull()?.id) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }; var value by rememberSaveable { mutableStateOf(formatEpoch(initial)) }; val parsed = parseEpoch(value); val validTime = parsed != null && (range?.contains(parsed) ?: (parsed <= System.currentTimeMillis()))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.add_event)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionMenu(stringResource(R.string.category_tree), tree, state.categoryTrees.map { it.id }, { id -> state.categoryTrees.first { it.id == id }.name }) { selected -> tree = selected; category = state.categories.firstOrNull { it.categoryTreeId == selected && !it.archived }?.id; taskId = null }
        SelectionMenu(stringResource(R.string.category), category, listOf<String?>(null) + choices.map { it.id }, { id -> choices.firstOrNull { it.id == id }?.name ?: stringResource(R.string.unknown) }) { selected ->
            category = selected
            if (taskId?.let { effectiveTaskCategory(it, tasks) } != selected) taskId = null
        }
        val activeTasks = tasks.filter { it.status == "active" }
        SelectionMenu(stringResource(R.string.task), taskId, listOf<String?>(null) + activeTasks.map { it.id }, { id -> activeTasks.firstOrNull { it.id == id }?.title ?: stringResource(R.string.no_task) }) { selected ->
            taskId = selected
            selected?.let { id -> effectiveTaskCategory(id, tasks)?.let { categoryId -> state.categories.firstOrNull { it.id == categoryId }?.let { item -> category = item.id; tree = item.categoryTreeId } } }
        }
        OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.date_time_format)) }, isError = parsed == null)
    } }, confirmButton = { TextButton(enabled = tree.isNotEmpty() && validTime, onClick = { onSave(tree, category, taskId, requireNotNull(parsed)) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun TimeDialog(initial: Long, range: LongRange? = null, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var value by rememberSaveable { mutableStateOf(formatEpoch(initial)) }; val parsed = parseEpoch(value); val valid = parsed != null && (range?.contains(parsed) ?: (parsed <= System.currentTimeMillis()))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit_time)) }, text = { OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.date_time_format)) }, isError = !valid) }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(requireNotNull(parsed)) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PlannerScreen(state: DashboardState, tasks: TaskState, planner: PlannerState, vm: MainViewModel, padding: PaddingValues, onOpenTask: (String) -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button(onClick = { creating = true }) { Text(stringResource(R.string.new_plan)) } }
        items(planner.plans, key = { it.id }) { plan -> PlanCard(plan, state, tasks, planner, vm, onOpenTask) }
        if (planner.plans.isEmpty()) item { Text(stringResource(R.string.no_plans)) }
    }
    if (creating) PlanDialog({ creating = false }) { name, kind, start, end -> vm.createPlan(name, kind, start, end); creating = false }
}

@Composable
private fun PlanCard(plan: PlanRow, state: DashboardState, tasks: TaskState, planner: PlannerState, vm: MainViewModel, onOpenTask: (String) -> Unit) {
    var addEvent by rememberSaveable { mutableStateOf(false) }; var editEventId by rememberSaveable { mutableStateOf<String?>(null) }; var deleteEventId by rememberSaveable { mutableStateOf<String?>(null) }; var confirmArchive by rememberSaveable { mutableStateOf(false) }; var hideRedundant by rememberSaveable(plan.id) { mutableStateOf(false) }; val formatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${plan.name} · ${stringResource(if (plan.kind == "budget") R.string.budget else R.string.timeline_plan)}", style = MaterialTheme.typography.titleLarge); TextButton(onClick = { confirmArchive = true }) { Text(stringResource(R.string.archive)) } }
        Text("${formatter.format(Instant.ofEpochMilli(plan.startsAtEpochMs))} — ${formatter.format(Instant.ofEpochMilli(plan.endsAtEpochMs))}")
        if (plan.kind == "budget") {
            TextButton(onClick = { hideRedundant = !hideRedundant }) { Text(stringResource(if (hideRedundant) R.string.show_other_categories else R.string.hide_extra_categories)) }
            state.categoryTrees.forEach { tree ->
                Text(tree.name, style = MaterialTheme.typography.titleMedium)
                val categories = state.categories.filter { it.categoryTreeId == tree.id && !it.archived }; val own = planner.allocations.filter { it.planId == plan.id }.associate { it.categoryId to it.ownMinutes }
                val rows = budgetCategoryItems(categories, own, hideRedundant)
                if (rows.isEmpty()) Text(stringResource(R.string.no_budget_categories), style = MaterialTheme.typography.bodySmall)
                rows.forEach { (category, depth) -> BudgetRow(category, depth, own[category.id] ?: 0, totalMinutes(category.id, categories, own)) { vm.setBudget(plan.id, category.id, it) } }
            }
        } else {
            TextButton(enabled = state.categories.isNotEmpty(), onClick = { addEvent = true }) { Text(stringResource(R.string.add_planned_event)) }
            planner.plannedEvents.filter { it.planId == plan.id }.forEach { event ->
                val category = state.categories.firstOrNull { it.id == event.categoryId }?.name ?: stringResource(R.string.unknown)
                Row { Column(Modifier.weight(1f)) { Text("${formatter.format(Instant.ofEpochMilli(event.occurredAtEpochMs))} · $category"); EventTaskLink(event.taskId, tasks.tasks, onOpenTask) }; Column { TextButton(onClick = { editEventId = event.id }) { Text(stringResource(R.string.edit)) }; TextButton(onClick = { deleteEventId = event.id }) { Text(stringResource(R.string.delete)) } } }
            }
        }
    } }
    if (addEvent) EventDialog(state, tasks.tasks, plan.startsAtEpochMs, plan.startsAtEpochMs..(plan.endsAtEpochMs - 1), { addEvent = false }) { tree, category, task, at -> vm.addPlannedEvent(plan.id, tree, category, at, task) { success -> if (success) addEvent = false } }
    planner.plannedEvents.firstOrNull { it.id == editEventId }?.let { event -> TimeDialog(event.occurredAtEpochMs, plan.startsAtEpochMs..(plan.endsAtEpochMs - 1), { editEventId = null }) { vm.updatePlannedEvent(event.id, it); editEventId = null } }
    planner.plannedEvents.firstOrNull { it.id == deleteEventId }?.let { event -> ConfirmDialog(stringResource(R.string.delete_planned_event_message, formatter.format(Instant.ofEpochMilli(event.occurredAtEpochMs))), { deleteEventId = null }) { vm.deletePlannedEvent(event.id); deleteEventId = null } }
    if (confirmArchive) ConfirmDialog(stringResource(R.string.archive_plan_message, plan.name), { confirmArchive = false }) { vm.archivePlan(plan.id); confirmArchive = false }
}

@Composable
private fun BudgetRow(category: CategoryRow, depth: Int, own: Int, total: Int, onSave: (Int) -> Unit) {
    var value by rememberSaveable(category.id, own) { mutableStateOf(own.toString()) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(3) * 16).dp)) {
        if (maxWidth < 520.dp || density.fontScale > 1.3f) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(category.name, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.own_minutes)) }, singleLine = true)
                Text("Σ $total")
                TextButton(onClick = { onSave(value.toIntOrNull() ?: 0) }) { Text(stringResource(R.string.save)) }
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(category.name, Modifier.weight(1f))
            OutlinedTextField(value, { value = it.filter(Char::isDigit) }, modifier = Modifier.width(104.dp), label = { Text(stringResource(R.string.own_minutes)) }, singleLine = true)
            Text("Σ $total")
            TextButton(onClick = { onSave(value.toIntOrNull() ?: 0) }) { Text(stringResource(R.string.save)) }
        }
    }
}

private fun totalMinutes(id: String, categories: List<CategoryRow>, own: Map<String, Int>): Int = BudgetEngine.totalMinutes(id, categories.associate { it.id to it.parentId }, own)

@Composable
private fun PlanDialog(onDismiss: () -> Unit, onSave: (String, String, Long, Long) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }; var kind by rememberSaveable { mutableStateOf("budget") }
    val now = LocalDateTime.now().withSecond(0).withNano(0); var start by rememberSaveable { mutableStateOf(now.format(dateTimeFormat)) }; var end by rememberSaveable { mutableStateOf(now.plusDays(1).format(dateTimeFormat)) }
    val startValue = parseEpoch(start); val endValue = parseEpoch(end)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.new_plan)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.plan_name)) })
        SelectionMenu(stringResource(R.string.plan_type), kind, listOf("budget", "timeline"), { stringResource(if (it == "budget") R.string.budget else R.string.timeline_plan) }) { kind = it }
        OutlinedTextField(start, { start = it }, label = { Text(stringResource(R.string.plan_start)) }); OutlinedTextField(end, { end = it }, label = { Text(stringResource(R.string.plan_end)) })
    } }, confirmButton = { TextButton(enabled = name.isNotBlank() && startValue != null && endValue != null && endValue > startValue, onClick = { onSave(name, kind, requireNotNull(startValue), requireNotNull(endValue)) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun TasksScreen(state: DashboardState, taskState: TaskState, vm: MainViewModel, padding: PaddingValues, onOpenTask: (String) -> Unit) {
    var active by rememberSaveable { mutableStateOf(true) }; var sortDeadline by rememberSaveable { mutableStateOf(false) }; var creating by rememberSaveable { mutableStateOf(false) }
    val visible = taskState.tasks.filter { (it.status == "active") == active }.let { list -> if (sortDeadline) list.sortedWith(compareBy<TaskRow> { it.deadlineEpochMs ?: Long.MAX_VALUE }.thenBy { it.title }) else list.sortedWith(compareBy<TaskRow> { it.nextActionDateEpochDay ?: Long.MAX_VALUE }.thenBy { it.nextActionMinuteOfDay ?: Int.MAX_VALUE }) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { active = true }) { Text((if (active) "✓ " else "") + stringResource(R.string.active_tasks)) }
            Button(onClick = { active = false }) { Text((if (!active) "✓ " else "") + stringResource(R.string.inactive_tasks)) }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { creating = true }, enabled = state.categories.any { !it.archived }) { Text(stringResource(R.string.new_task)) }; TextButton(onClick = { sortDeadline = !sortDeadline }) { Text(stringResource(if (sortDeadline) R.string.sort_deadline else R.string.sort_next_action)) } } }
        items(visible, key = { it.id }) { task -> TaskCard(task, state, taskState, vm, onOpenTask) }
    }
    if (creating) TaskDialog(state, null, null, { creating = false }) { draft -> vm.createTask(draft.title, draft.categoryId, null, draft.estimate, draft.nextDate, draft.nextMinute, draft.deadline) { success -> if (success) creating = false } }
}

@Composable
private fun TaskCard(task: TaskRow, state: DashboardState, taskState: TaskState, vm: MainViewModel, onOpenTask: (String) -> Unit) {
    var addSubtask by rememberSaveable { mutableStateOf(false) }; var comment by rememberSaveable { mutableStateOf(false) }; var editing by rememberSaveable { mutableStateOf(false) }; var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val category = task.categoryId?.let { id -> state.categories.firstOrNull { it.id == id }?.name } ?: task.parentTaskId?.let { id -> taskState.tasks.firstOrNull { it.id == id }?.title }.orEmpty()
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val nextAction = task.nextActionDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "—"
        val deadline = task.deadlineEpochMs?.let(::formatEpoch) ?: "—"
        Text(task.title, style = MaterialTheme.typography.titleMedium)
        Text("$category · ${task.estimateMinutes} ${stringResource(R.string.minutes_short)} · ${taskStatusLabel(task.status)}")
        Text("${stringResource(R.string.next_action)}: $nextAction · ${stringResource(R.string.deadline_short)}: $deadline", style = MaterialTheme.typography.bodySmall)
        taskState.comments.filter { it.taskId == task.id }.take(3).forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
        SubtaskSection(task.id, taskState.tasks, onOpenTask)
        Column {
            if (task.status == "active") { TextButton(onClick = { vm.startTask(task.id) }) { Text(stringResource(R.string.start)) }; TextButton(onClick = { vm.setTaskStatus(task.id, "paused") }) { Text(stringResource(R.string.pause)) }; TextButton(onClick = { vm.setTaskStatus(task.id, "completed") }) { Text(stringResource(R.string.complete)) } }
            else TextButton(onClick = { vm.setTaskStatus(task.id, "active") }) { Text(stringResource(R.string.activate)) }
            if (task.status == "active" || task.status == "paused") TextButton(onClick = { vm.setTaskStatus(task.id, "cancelled") }) { Text(stringResource(R.string.cancel_task)) }
        }
        Column { TextButton(onClick = { editing = true }) { Text(stringResource(R.string.edit)) }; TextButton(onClick = { addSubtask = true }) { Text(stringResource(R.string.add_subtask)) }; TextButton(onClick = { comment = true }) { Text(stringResource(R.string.add_comment)) }; TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) } }
    } }
    if (editing) TaskDialog(state, task.parentTaskId, task, { editing = false }) { draft -> vm.updateTask(task.id, draft.title, draft.categoryId, draft.estimate, draft.nextDate, draft.nextMinute, draft.deadline) { success -> if (success) editing = false } }
    if (addSubtask) TaskDialog(state, task.id, null, { addSubtask = false }) { draft -> vm.createTask(draft.title, null, task.id, draft.estimate, draft.nextDate, draft.nextMinute, draft.deadline) { success -> if (success) addSubtask = false } }
    if (comment) NameDialog(stringResource(R.string.add_comment), stringResource(R.string.comment), onDismiss = { comment = false }) { vm.addTaskComment(task.id, it); comment = false }
    if (confirmDelete) ConfirmDialog(stringResource(R.string.delete_task_message), { confirmDelete = false }) { vm.deleteTask(task.id); confirmDelete = false }
}

@Composable
private fun TaskDetailsScreen(taskId: String, state: DashboardState, taskState: TaskState, vm: MainViewModel, padding: PaddingValues, onOpenTask: (String) -> Unit) {
    val task = taskState.tasks.firstOrNull { it.id == taskId }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (task == null) item { Text(stringResource(R.string.task_unavailable)) }
        else item(key = task.id) { TaskCard(task, state, taskState, vm, onOpenTask) }
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
        OutlinedTextField(estimate, { estimate = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.estimate_minutes)) }); OutlinedTextField(date, { date = it }, label = { Text(stringResource(R.string.next_action_date)) }, isError = parsedDate == null)
        OutlinedTextField(nextTime, { nextTime = it }, label = { Text(stringResource(R.string.next_action_time)) }, isError = nextTime.isNotBlank() && parsedNextTime == null)
        OutlinedTextField(deadline, { deadline = it }, label = { Text(stringResource(R.string.deadline)) }, isError = deadline.isNotBlank() && parsedDeadline == null)
    } }, confirmButton = { TextButton(enabled = title.isNotBlank() && estimate.toIntOrNull() != null && parsedDate != null && timesValid && (parentTaskId != null || category != null), onClick = { onSave(TaskDraft(title, category, estimate.toInt(), requireNotNull(parsedDate), parsedNextTime?.let { it.hour * 60 + it.minute }, parsedDeadline)) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ReportsScreen(state: DashboardState, vm: MainViewModel, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) { items(vm.reportToday()) { row ->
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
        SettingsButton(R.string.network) { navigate(Screen.NETWORK) }
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
    "estimateMinutes" -> stringResource(R.string.estimate_minutes); "ownMinutes" -> stringResource(R.string.own_minutes)
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
private fun formatEpoch(value: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault()).format(dateTimeFormat)
private fun parseEpoch(value: String): Long? = runCatching { LocalDateTime.parse(value, dateTimeFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
