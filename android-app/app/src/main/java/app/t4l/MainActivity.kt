package app.t4l

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.t4l.data.CategoryRow
import app.t4l.data.DashboardState
import app.t4l.data.EventRow
import app.t4l.data.PlanRow
import app.t4l.data.PlannerState
import app.t4l.data.TaskRow
import app.t4l.data.TaskState
import app.t4l.data.SyncPhase
import app.t4l.data.SyncUiState
import app.t4l.ui.theme.T4LTheme
import app.t4l.domain.BudgetEngine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var contentShown = false
    private var authenticationRunning = false
    private var needsUnlock = false
    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (application as T4LApplication).authManager.completeAuthorization(result.data) { success, error ->
            runOnUiThread {
                if (success) continueStartup() else { Toast.makeText(this, error ?: "OIDC authorization failed", Toast.LENGTH_LONG).show(); finish() }
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

private enum class Screen(val label: Int) { TRACK(R.string.track), HISTORY(R.string.history), PLANNER(R.string.planner), TASKS(R.string.tasks), REPORTS(R.string.reports), SETTINGS(R.string.settings) }
private enum class SettingsSection { ROOT, NETWORK, BACKUP, SECURITY, DIAGNOSTICS, SYNC_ISSUES, LANGUAGE }
private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private data class TaskDraft(val title: String, val categoryId: String?, val estimate: Int, val nextDate: LocalDate, val nextMinute: Int?, val deadline: Long?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun T4LRoot(viewModel: MainViewModel = viewModel()) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val taskState by viewModel.taskState.collectAsStateWithLifecycle()
    val plannerState by viewModel.plannerState.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val uiError by viewModel.uiError.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.TRACK) }; var menu by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("T4L · ${stringResource(screen.label)}"); Text(syncSummary(syncState), style = MaterialTheme.typography.labelSmall) } }, actions = {
            Box {
                TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { menu = true }) { Text(stringResource(R.string.menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Screen.entries.forEach { item -> DropdownMenuItem(text = { Text((if (item == screen) "✓ " else "") + stringResource(item.label)) }, onClick = { screen = item; menu = false }) }
                }
            }
        })
    }) { padding ->
        when (screen) {
            Screen.TRACK -> TrackScreen(dashboard, viewModel, padding)
            Screen.HISTORY -> HistoryScreen(dashboard, taskState, viewModel, padding)
            Screen.PLANNER -> PlannerScreen(dashboard, taskState, plannerState, viewModel, padding)
            Screen.TASKS -> TasksScreen(dashboard, taskState, viewModel, padding)
            Screen.REPORTS -> ReportsScreen(dashboard, viewModel, padding)
            Screen.SETTINGS -> SettingsScreen(viewModel, syncState, workspaces, padding)
        }
    }
    uiError?.let { message -> AlertDialog(onDismissRequest = viewModel::clearError, title = { Text(stringResource(R.string.error)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.ok)) } }) }
}

@Composable
private fun syncSummary(state: SyncUiState): String = when {
    state.conflicts.isNotEmpty() || state.failed.isNotEmpty() -> stringResource(R.string.sync_issues_count, state.conflicts.size + state.failed.size)
    state.runtime.phase == SyncPhase.SYNCING -> stringResource(R.string.syncing)
    state.runtime.phase == SyncPhase.RETRY -> stringResource(R.string.sync_retry)
    state.pending.isNotEmpty() -> stringResource(R.string.sync_pending, state.pending.size)
    else -> stringResource(R.string.sync_ok)
}

@Composable
private fun TrackScreen(state: DashboardState, vm: MainViewModel, padding: PaddingValues) {
    var newTree by remember { mutableStateOf(false) }
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
    var addParent by remember { mutableStateOf<String?>(null) }; var addDialog by remember { mutableStateOf(false) }
    var renameCategory by remember { mutableStateOf<CategoryRow?>(null) }; var renameTree by remember { mutableStateOf(false) }
    var confirmCategory by remember { mutableStateOf<CategoryRow?>(null) }; var confirmTree by remember { mutableStateOf(false) }
    val flat = remember(categories) { flattenCategories(categories) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Row { TextButton(onClick = { renameTree = true }) { Text(stringResource(R.string.rename)) }; TextButton(onClick = { confirmTree = true }) { Text(stringResource(R.string.delete_tree)) } }
        }
        flat.forEach { (category, depth) ->
            Row(Modifier.fillMaxWidth().padding(start = (minOf(depth, 3) * 12).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(modifier = Modifier.weight(1f), onClick = { onSwitch(category.id) }) { Text((if (category.id == current) "✓ " else "") + category.name) }
                Column { TextButton(onClick = { addParent = category.id; addDialog = true }) { Text(stringResource(R.string.add_category)) }; TextButton(onClick = { renameCategory = category }) { Text(stringResource(R.string.rename)) }; TextButton(onClick = { confirmCategory = category }) { Text(stringResource(R.string.delete)) } }
            }
        }
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

private fun flattenCategories(categories: List<CategoryRow>): List<Pair<CategoryRow, Int>> {
    val children = categories.groupBy { it.parentId }; val result = mutableListOf<Pair<CategoryRow, Int>>(); val visited = mutableSetOf<String>()
    fun visit(parent: String?, depth: Int) { children[parent].orEmpty().sortedWith(compareBy<CategoryRow> { it.sortOrder }.thenBy { it.name }).forEach { if (visited.add(it.id)) { result += it to depth; visit(it.id, depth + 1) } } }
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
private fun HistoryScreen(state: DashboardState, tasks: TaskState, vm: MainViewModel, padding: PaddingValues) {
    var adding by remember { mutableStateOf(false) }; var editing by remember { mutableStateOf<EventRow?>(null) }
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Button(enabled = state.categories.isNotEmpty(), onClick = { adding = true }) { Text(stringResource(R.string.add_event)) } }
        items(state.events, key = { it.id }) { event ->
            val tree = state.categoryTrees.firstOrNull { it.id == event.categoryTreeId }?.name.orEmpty(); val category = state.categories.firstOrNull { it.id == event.categoryId }?.name ?: stringResource(R.string.unknown)
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${formatter.format(Instant.ofEpochMilli(event.occurredAtEpochMs))} · $tree · $category")
                Row { TextButton(onClick = { editing = event }) { Text(stringResource(R.string.edit_time)) }; TextButton(onClick = { vm.deleteEvent(event.id) }) { Text(stringResource(R.string.delete)) } }
            } }
        }
    }
    if (adding) EventDialog(state, tasks.tasks, System.currentTimeMillis(), onDismiss = { adding = false }) { tree, category, task, at -> vm.addEvent(tree, category, at, task); adding = false }
    editing?.let { event -> TimeDialog(event.occurredAtEpochMs, onDismiss = { editing = null }) { vm.updateEvent(event.id, it); editing = null } }
}

@Composable
private fun EventDialog(state: DashboardState, tasks: List<TaskRow>, initial: Long, range: LongRange? = null, onDismiss: () -> Unit, onSave: (String, String?, String?, Long) -> Unit) {
    var tree by remember { mutableStateOf(state.categoryTrees.firstOrNull()?.id.orEmpty()) }
    val choices = state.categories.filter { it.categoryTreeId == tree && !it.archived }; var category by remember { mutableStateOf(choices.firstOrNull()?.id) }
    var taskId by remember { mutableStateOf<String?>(null) }; var value by remember { mutableStateOf(formatEpoch(initial)) }; val parsed = parseEpoch(value); val validTime = parsed != null && (range?.contains(parsed) ?: (parsed <= System.currentTimeMillis()))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.add_event)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionMenu(stringResource(R.string.category_tree), tree, state.categoryTrees.map { it.id }, { id -> state.categoryTrees.first { it.id == id }.name }) { selected -> tree = selected; category = state.categories.firstOrNull { it.categoryTreeId == selected && !it.archived }?.id; taskId = null }
        SelectionMenu(stringResource(R.string.category), category, listOf<String?>(null) + choices.map { it.id }, { id -> choices.firstOrNull { it.id == id }?.name ?: stringResource(R.string.unknown) }) { category = it }
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
    var value by remember { mutableStateOf(formatEpoch(initial)) }; val parsed = parseEpoch(value); val valid = parsed != null && (range?.contains(parsed) ?: (parsed <= System.currentTimeMillis()))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit_time)) }, text = { OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.date_time_format)) }, isError = !valid) }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(requireNotNull(parsed)) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PlannerScreen(state: DashboardState, tasks: TaskState, planner: PlannerState, vm: MainViewModel, padding: PaddingValues) {
    var creating by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button(onClick = { creating = true }) { Text(stringResource(R.string.new_plan)) } }
        items(planner.plans, key = { it.id }) { plan -> PlanCard(plan, state, tasks, planner, vm) }
        if (planner.plans.isEmpty()) item { Text(stringResource(R.string.no_plans)) }
    }
    if (creating) PlanDialog({ creating = false }) { name, kind, start, end -> vm.createPlan(name, kind, start, end); creating = false }
}

@Composable
private fun PlanCard(plan: PlanRow, state: DashboardState, tasks: TaskState, planner: PlannerState, vm: MainViewModel) {
    var addEvent by remember { mutableStateOf(false) }; var editEvent by remember { mutableStateOf<app.t4l.data.PlannedEventRow?>(null) }; val formatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${plan.name} · ${stringResource(if (plan.kind == "budget") R.string.budget else R.string.timeline_plan)}", style = MaterialTheme.typography.titleLarge); TextButton(onClick = { vm.archivePlan(plan.id) }) { Text(stringResource(R.string.archive)) } }
        Text("${formatter.format(Instant.ofEpochMilli(plan.startsAtEpochMs))} — ${formatter.format(Instant.ofEpochMilli(plan.endsAtEpochMs))}")
        if (plan.kind == "budget") {
            state.categoryTrees.forEach { tree ->
                Text(tree.name, style = MaterialTheme.typography.titleMedium)
                val categories = state.categories.filter { it.categoryTreeId == tree.id && !it.archived }; val own = planner.allocations.filter { it.planId == plan.id }.associate { it.categoryId to it.ownMinutes }
                flattenCategories(categories).forEach { (category, depth) -> BudgetRow(category, depth, own[category.id] ?: 0, totalMinutes(category.id, categories, own)) { vm.setBudget(plan.id, category.id, it) } }
            }
        } else {
            TextButton(enabled = state.categories.isNotEmpty(), onClick = { addEvent = true }) { Text(stringResource(R.string.add_planned_event)) }
            planner.plannedEvents.filter { it.planId == plan.id }.forEach { event ->
                val category = state.categories.firstOrNull { it.id == event.categoryId }?.name ?: stringResource(R.string.unknown)
                Row { Text("${formatter.format(Instant.ofEpochMilli(event.occurredAtEpochMs))} · $category", Modifier.weight(1f)); Column { TextButton(onClick = { editEvent = event }) { Text(stringResource(R.string.edit)) }; TextButton(onClick = { vm.deletePlannedEvent(event.id) }) { Text(stringResource(R.string.delete)) } } }
            }
        }
    } }
    if (addEvent) EventDialog(state, tasks.tasks, plan.startsAtEpochMs, plan.startsAtEpochMs..(plan.endsAtEpochMs - 1), { addEvent = false }) { tree, category, task, at -> vm.addPlannedEvent(plan.id, tree, category, at, task); addEvent = false }
    editEvent?.let { event -> TimeDialog(event.occurredAtEpochMs, plan.startsAtEpochMs..(plan.endsAtEpochMs - 1), { editEvent = null }) { vm.updatePlannedEvent(event.id, it); editEvent = null } }
}

@Composable
private fun BudgetRow(category: CategoryRow, depth: Int, own: Int, total: Int, onSave: (Int) -> Unit) {
    var value by remember(category.id, own) { mutableStateOf(own.toString()) }
    Row(Modifier.fillMaxWidth().padding(start = (depth * 16).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(category.name, Modifier.weight(1f)); OutlinedTextField(value, { value = it.filter(Char::isDigit) }, modifier = Modifier.width(88.dp), label = { Text(stringResource(R.string.own_minutes)) }, singleLine = true)
        Text("Σ $total"); TextButton(onClick = { onSave(value.toIntOrNull() ?: 0) }) { Text(stringResource(R.string.save)) }
    }
}

private fun totalMinutes(id: String, categories: List<CategoryRow>, own: Map<String, Int>): Int = BudgetEngine.totalMinutes(id, categories.associate { it.id to it.parentId }, own)

@Composable
private fun PlanDialog(onDismiss: () -> Unit, onSave: (String, String, Long, Long) -> Unit) {
    var name by remember { mutableStateOf("") }; var kind by remember { mutableStateOf("budget") }
    val now = LocalDateTime.now().withSecond(0).withNano(0); var start by remember { mutableStateOf(now.format(dateTimeFormat)) }; var end by remember { mutableStateOf(now.plusDays(1).format(dateTimeFormat)) }
    val startValue = parseEpoch(start); val endValue = parseEpoch(end)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.new_plan)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.plan_name)) })
        SelectionMenu(stringResource(R.string.plan_type), kind, listOf("budget", "timeline"), { stringResource(if (it == "budget") R.string.budget else R.string.timeline_plan) }) { kind = it }
        OutlinedTextField(start, { start = it }, label = { Text(stringResource(R.string.plan_start)) }); OutlinedTextField(end, { end = it }, label = { Text(stringResource(R.string.plan_end)) })
    } }, confirmButton = { TextButton(enabled = name.isNotBlank() && startValue != null && endValue != null && endValue > startValue, onClick = { onSave(name, kind, requireNotNull(startValue), requireNotNull(endValue)) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun TasksScreen(state: DashboardState, taskState: TaskState, vm: MainViewModel, padding: PaddingValues) {
    var active by remember { mutableStateOf(true) }; var sortDeadline by remember { mutableStateOf(false) }; var creating by remember { mutableStateOf(false) }
    val visible = taskState.tasks.filter { (it.status == "active") == active }.let { list -> if (sortDeadline) list.sortedWith(compareBy<TaskRow> { it.deadlineEpochMs ?: Long.MAX_VALUE }.thenBy { it.title }) else list.sortedWith(compareBy<TaskRow> { it.nextActionDateEpochDay ?: Long.MAX_VALUE }.thenBy { it.nextActionMinuteOfDay ?: Int.MAX_VALUE }) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { active = true }) { Text((if (active) "✓ " else "") + stringResource(R.string.active_tasks)) }
            Button(onClick = { active = false }) { Text((if (!active) "✓ " else "") + stringResource(R.string.inactive_tasks)) }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { creating = true }, enabled = state.categories.any { !it.archived }) { Text(stringResource(R.string.new_task)) }; TextButton(onClick = { sortDeadline = !sortDeadline }) { Text(stringResource(if (sortDeadline) R.string.sort_deadline else R.string.sort_next_action)) } } }
        items(visible, key = { it.id }) { task -> TaskCard(task, state, taskState, vm) }
    }
    if (creating) TaskDialog(state, null, null, { creating = false }) { draft -> vm.createTask(draft.title, draft.categoryId, null, draft.estimate, draft.nextDate, draft.nextMinute, draft.deadline); creating = false }
}

@Composable
private fun TaskCard(task: TaskRow, state: DashboardState, taskState: TaskState, vm: MainViewModel) {
    var addSubtask by remember { mutableStateOf(false) }; var comment by remember { mutableStateOf(false) }; var editing by remember { mutableStateOf(false) }; var confirmDelete by remember { mutableStateOf(false) }
    val category = task.categoryId?.let { id -> state.categories.firstOrNull { it.id == id }?.name } ?: task.parentTaskId?.let { id -> taskState.tasks.firstOrNull { it.id == id }?.title }.orEmpty()
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val nextAction = task.nextActionDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "—"
        val deadline = task.deadlineEpochMs?.let(::formatEpoch) ?: "—"
        Text(task.title, style = MaterialTheme.typography.titleMedium)
        Text("$category · ${task.estimateMinutes} ${stringResource(R.string.minutes_short)} · ${taskStatusLabel(task.status)}")
        Text("${stringResource(R.string.next_action)}: $nextAction · ${stringResource(R.string.deadline_short)}: $deadline", style = MaterialTheme.typography.bodySmall)
        taskState.comments.filter { it.taskId == task.id }.take(3).forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
        Column {
            if (task.status == "active") { TextButton(onClick = { vm.startTask(task.id) }) { Text(stringResource(R.string.start)) }; TextButton(onClick = { vm.setTaskStatus(task.id, "paused") }) { Text(stringResource(R.string.pause)) }; TextButton(onClick = { vm.setTaskStatus(task.id, "completed") }) { Text(stringResource(R.string.complete)) } }
            else TextButton(onClick = { vm.setTaskStatus(task.id, "active") }) { Text(stringResource(R.string.activate)) }
            if (task.status == "active" || task.status == "paused") TextButton(onClick = { vm.setTaskStatus(task.id, "cancelled") }) { Text(stringResource(R.string.cancel_task)) }
        }
        Column { TextButton(onClick = { editing = true }) { Text(stringResource(R.string.edit)) }; TextButton(onClick = { addSubtask = true }) { Text(stringResource(R.string.add_subtask)) }; TextButton(onClick = { comment = true }) { Text(stringResource(R.string.add_comment)) }; TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) } }
    } }
    if (editing) TaskDialog(state, task.parentTaskId, task, { editing = false }) { draft -> vm.updateTask(task.id, draft.title, draft.categoryId, draft.estimate, draft.nextDate, draft.nextMinute, draft.deadline); editing = false }
    if (addSubtask) TaskDialog(state, task.id, null, { addSubtask = false }) { draft -> vm.createTask(draft.title, null, task.id, draft.estimate, draft.nextDate, draft.nextMinute, draft.deadline); addSubtask = false }
    if (comment) NameDialog(stringResource(R.string.add_comment), stringResource(R.string.comment), onDismiss = { comment = false }) { vm.addTaskComment(task.id, it); comment = false }
    if (confirmDelete) ConfirmDialog(stringResource(R.string.delete_task_message), { confirmDelete = false }) { vm.deleteTask(task.id); confirmDelete = false }
}

@Composable
private fun TaskDialog(state: DashboardState, parentTaskId: String?, existing: TaskRow?, onDismiss: () -> Unit, onSave: (TaskDraft) -> Unit) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }; val choices = state.categories.filter { !it.archived }; var category by remember { mutableStateOf(existing?.categoryId ?: choices.firstOrNull()?.id) }; var estimate by remember { mutableStateOf((existing?.estimateMinutes ?: 60).toString()) }
    var date by remember { mutableStateOf(existing?.nextActionDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: LocalDate.now().toString()) }
    var nextTime by remember { mutableStateOf(existing?.nextActionMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty()) }
    var deadline by remember { mutableStateOf(existing?.deadlineEpochMs?.let(::formatEpoch).orEmpty()) }
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
private fun NameDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) { var value by remember(initial) { mutableStateOf(initial) }; AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text(label) }) }, confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onSave(value) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }) }
@Composable private fun ConfirmDialog(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.confirm_action)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })

@Composable
private fun SettingsScreen(vm: MainViewModel, syncState: SyncUiState, workspaces: List<app.t4l.data.WorkspaceOption>, padding: PaddingValues) {
    var section by remember { mutableStateOf(SettingsSection.ROOT) }; var level by remember { mutableStateOf(vm.diagnosticLevel()) }; var appLock by remember { mutableStateOf(vm.appLockEnabled()) }; var serverUrl by remember { mutableStateOf(vm.serverBaseUrl()) }; var syncDelay by remember { mutableStateOf(vm.syncDelay()) }; var valid by remember { mutableStateOf(true) }; var passwordDialog by remember { mutableStateOf(false) }; var password by remember { mutableStateOf("") }; var pendingPassword by remember { mutableStateOf<CharArray?>(null) }; var pendingImport by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current; val configuration = LocalConfiguration.current; val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(vm.exportBackup(pendingPassword)) }; pendingPassword = null }
        else { pendingPassword?.fill('\u0000'); pendingPassword = null }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch { val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty(); if (BackupCrypto.isEncrypted(content)) { pendingImport = content; passwordDialog = true } else vm.importBackup(content) } }
    BackHandler(section != SettingsSection.ROOT) { section = SettingsSection.ROOT }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (section) {
            SettingsSection.ROOT -> { SettingsButton(R.string.network) { section = SettingsSection.NETWORK }; SettingsButton(R.string.backup) { section = SettingsSection.BACKUP }; SettingsButton(R.string.security) { section = SettingsSection.SECURITY }; SettingsButton(R.string.diagnostics) { section = SettingsSection.DIAGNOSTICS }; SettingsButton(R.string.language) { section = SettingsSection.LANGUAGE } }
            SettingsSection.NETWORK -> { BackTitle(R.string.network) { section = SettingsSection.ROOT }; OutlinedTextField(serverUrl, { serverUrl = it; valid = true }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.server_url)) }, isError = !valid); if (workspaces.isNotEmpty()) SelectionMenu(stringResource(R.string.workspace), vm.selectedWorkspace(), workspaces.map { it.id }, { id -> workspaces.firstOrNull { it.id == id }?.let { "${it.name} (${it.role})" } ?: id }) { vm.selectWorkspace(it) }; SelectionMenu(stringResource(R.string.sync_delay), syncDelay, SyncDelay.entries, { delayLabel(it) }) { syncDelay = it }; Button(modifier = Modifier.fillMaxWidth(), onClick = { valid = vm.updateNetworkSettings(serverUrl, syncDelay) }) { Text(stringResource(R.string.apply)) }; Button(modifier = Modifier.fillMaxWidth(), onClick = vm::syncNow) { Text(stringResource(R.string.sync_now)) } }
            SettingsSection.BACKUP -> { BackTitle(R.string.backup) { section = SettingsSection.ROOT }; Button(modifier = Modifier.fillMaxWidth(), onClick = { pendingPassword = null; export.launch("t4l-backup-v2.json") }) { Text(stringResource(R.string.export_plain)) }; Button(modifier = Modifier.fillMaxWidth(), onClick = { pendingImport = null; passwordDialog = true }) { Text(stringResource(R.string.export_encrypted)) }; Button(modifier = Modifier.fillMaxWidth(), onClick = { import.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text(stringResource(R.string.import_backup)) } }
            SettingsSection.SECURITY -> { BackTitle(R.string.security) { section = SettingsSection.ROOT }; Button(modifier = Modifier.fillMaxWidth(), onClick = { val next = !appLock; if (vm.setAppLockEnabled(next)) appLock = next }) { Text("${stringResource(R.string.app_lock)}: ${stringResource(if (appLock) R.string.on else R.string.off)}") }; if (vm.oidcEnabled()) Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.signOut { (context as? AppCompatActivity)?.recreate() } }) { Text(stringResource(R.string.sign_out)) } }
            SettingsSection.DIAGNOSTICS -> { BackTitle(R.string.diagnostics) { section = SettingsSection.ROOT }; Text(syncSummary(syncState)); DiagnosticLevel.entries.forEach { candidate -> Button(modifier = Modifier.fillMaxWidth(), onClick = { level = candidate; vm.setDiagnosticLevel(candidate) }) { Text((if (candidate == level) "✓ " else "") + diagnosticLabel(candidate)) } }; Button(modifier = Modifier.fillMaxWidth(), onClick = { section = SettingsSection.SYNC_ISSUES }) { Text(stringResource(R.string.sync_issues_count, syncState.conflicts.size + syncState.failed.size)) } }
            SettingsSection.SYNC_ISSUES -> { BackTitle(R.string.sync_issues) { section = SettingsSection.DIAGNOSTICS }; SyncIssues(syncState, vm) }
            SettingsSection.LANGUAGE -> { BackTitle(R.string.language) { section = SettingsSection.ROOT }; val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').takeIf { it in setOf("en", "ru") } ?: if (configuration.locales[0].language == "ru") "ru" else "en"; SelectionMenu(stringResource(R.string.language), current, listOf("en", "ru"), { if (it == "ru") stringResource(R.string.russian) else stringResource(R.string.english) }) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) } }
        }
    }
    if (passwordDialog) AlertDialog(onDismissRequest = { password = ""; passwordDialog = false }, title = { Text(stringResource(R.string.backup_password)) }, text = { OutlinedTextField(password, { password = it }, visualTransformation = PasswordVisualTransformation()) }, confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = { val chars = password.toCharArray(); password = ""; passwordDialog = false; pendingImport?.let { content -> pendingImport = null; scope.launch { vm.importBackup(content, chars) } } ?: run { pendingPassword = chars; export.launch("t4l-backup-v2.t4lbackup") } }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { password = ""; passwordDialog = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SyncIssues(state: SyncUiState, vm: MainViewModel) {
    var editingConflict by remember { mutableStateOf<String?>(null) }
    var editingRejected by remember { mutableStateOf<String?>(null) }
    var payload by remember { mutableStateOf("") }
    if (state.conflicts.isEmpty() && state.failed.isEmpty()) Text(stringResource(R.string.no_sync_issues))
    state.conflicts.forEach { issue -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${issue.entityType} · ${issue.errorCode ?: "conflict"}", style = MaterialTheme.typography.titleSmall)
        Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.resolveConflictUseServer(issue.clientMutationId) }) { Text(stringResource(R.string.use_server)) }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.resolveConflictUseLocal(issue.clientMutationId) }) { Text(stringResource(R.string.use_local)) }
        TextButton(onClick = { editingConflict = issue.clientMutationId; payload = issue.localPayloadJson }) { Text(stringResource(R.string.edit_and_retry)) }
    } } }
    state.failed.forEach { issue -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${issue.entityType} · ${issue.lastError}", style = MaterialTheme.typography.titleSmall)
        Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.retryRejected(issue.clientMutationId) }) { Text(stringResource(R.string.retry)) }
        TextButton(onClick = { editingRejected = issue.clientMutationId; payload = issue.payloadJson }) { Text(stringResource(R.string.edit_and_retry)) }
    } } }
    val editedId = editingConflict ?: editingRejected
    if (editedId != null) AlertDialog(onDismissRequest = { editingConflict = null; editingRejected = null }, title = { Text(stringResource(R.string.edit_payload)) }, text = {
        OutlinedTextField(payload, { payload = it }, modifier = Modifier.fillMaxWidth(), minLines = 8, label = { Text("JSON") })
    }, confirmButton = { TextButton(onClick = { if (editingConflict != null) vm.resolveConflictUseLocal(editedId, payload) else vm.retryRejected(editedId, payload); editingConflict = null; editingRejected = null }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { editingConflict = null; editingRejected = null }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun SettingsButton(label: Int, action: () -> Unit) = Button(modifier = Modifier.fillMaxWidth(), onClick = action) { Text(stringResource(label)) }
@Composable private fun BackTitle(label: Int, action: () -> Unit) { Row { TextButton(onClick = action) { Text(stringResource(R.string.back)) }; Text(stringResource(label), style = MaterialTheme.typography.headlineSmall) } }
@Composable private fun <T> SelectionMenu(label: String, selected: T, options: List<T>, text: @Composable (T) -> String, onSelect: (T) -> Unit) { var expanded by remember { mutableStateOf(false) }; Box(Modifier.fillMaxWidth()) { Button(modifier = Modifier.fillMaxWidth(), onClick = { expanded = true }) { Text("$label: ${text(selected)}") }; DropdownMenu(expanded, { expanded = false }) { options.forEach { item -> DropdownMenuItem(text = { Text(text(item)) }, onClick = { expanded = false; onSelect(item) }) } } } }
@Composable private fun delayLabel(value: SyncDelay) = stringResource(when (value) { SyncDelay.IMMEDIATELY -> R.string.delay_immediately; SyncDelay.FIVE_SECONDS -> R.string.delay_5_seconds; SyncDelay.FIFTEEN_SECONDS -> R.string.delay_15_seconds; SyncDelay.THIRTY_SECONDS -> R.string.delay_30_seconds; SyncDelay.ONE_MINUTE -> R.string.delay_1_minute; SyncDelay.FIVE_MINUTES -> R.string.delay_5_minutes })
@Composable private fun diagnosticLabel(value: DiagnosticLevel) = stringResource(when (value) { DiagnosticLevel.OFF -> R.string.diagnostic_off; DiagnosticLevel.BASIC -> R.string.diagnostic_basic; DiagnosticLevel.VERBOSE -> R.string.diagnostic_verbose })
@Composable private fun taskStatusLabel(value: String) = stringResource(when (value) { "active" -> R.string.status_active; "paused" -> R.string.status_paused; "completed" -> R.string.status_completed; else -> R.string.status_cancelled })
private fun formatEpoch(value: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault()).format(dateTimeFormat)
private fun parseEpoch(value: String): Long? = runCatching { LocalDateTime.parse(value, dateTimeFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
