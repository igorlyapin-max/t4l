package app.t4l

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.t4l.data.DashboardState
import app.t4l.data.LocalReportRow
import app.t4l.data.PlannerState
import app.t4l.data.T4LRepository
import app.t4l.data.TaskState
import app.t4l.data.SyncUiState
import app.t4l.data.WorkspaceOption
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as T4LApplication
    private val repository = app.repository
    val dashboard: StateFlow<DashboardState> = repository.dashboard.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())
    val taskState: StateFlow<TaskState> = repository.taskState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskState())
    val plannerState: StateFlow<PlannerState> = repository.plannerState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlannerState())
    val syncState: StateFlow<SyncUiState> = repository.syncState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())
    val workspaces: StateFlow<List<WorkspaceOption>> = repository.availableWorkspaces.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val uiError = MutableStateFlow<String?>(null)

    fun createStarterData() = mutate { repository.createStarterData() }
    fun createCategoryTree(name: String) = mutate { repository.createCategoryTree(name) }
    fun addCategory(treeId: String, name: String, parentId: String? = null) = mutate { repository.addCategory(treeId, name, parentId) }
    fun renameCategory(id: String, name: String) = mutate { repository.renameCategory(id, name) }
    fun renameCategoryTree(id: String, name: String) = mutate { repository.renameCategoryTree(id, name) }
    fun archiveCategory(id: String) = mutate { repository.archiveCategory(id) }
    fun archiveCategoryTree(id: String) = mutate { repository.archiveCategoryTree(id) }
    fun switch(treeId: String, categoryId: String?) = mutate { repository.switchCategory(treeId, categoryId) }
    fun addEvent(treeId: String, categoryId: String?, at: Long, taskId: String? = null) = mutate { repository.addEvent(treeId, categoryId, at, taskId) }
    fun updateEvent(id: String, at: Long) = mutate { repository.updateEvent(id, at) }
    fun deleteEvent(id: String) = mutate { repository.deleteEvent(id) }
    fun createTask(title: String, categoryId: String?, parentTaskId: String?, estimate: Int, nextAction: LocalDate, nextMinute: Int?, deadline: Long?) =
        mutate { repository.createTask(title, categoryId, parentTaskId, estimate, nextAction, nextMinute, deadline) }
    fun updateTask(id: String, title: String, categoryId: String?, estimate: Int, nextAction: LocalDate, nextMinute: Int?, deadline: Long?) =
        mutate { repository.updateTask(id, title, categoryId, estimate, nextAction, nextMinute, deadline) }
    fun setTaskStatus(id: String, status: String) = mutate { repository.setTaskStatus(id, status) }
    fun deleteTask(id: String) = mutate { repository.deleteTask(id) }
    fun addTaskComment(id: String, text: String) = mutate { repository.addTaskComment(id, text) }
    fun startTask(id: String) = mutate { repository.startTask(id) }
    fun createPlan(name: String, kind: String, startsAt: Long, endsAt: Long) = mutate { repository.createPlan(name, kind, startsAt, endsAt) }
    fun archivePlan(id: String) = mutate { repository.archivePlan(id) }
    fun setBudget(planId: String, categoryId: String, minutes: Int) = mutate { repository.setBudget(planId, categoryId, minutes) }
    fun addPlannedEvent(planId: String, treeId: String, categoryId: String?, at: Long, taskId: String? = null) = mutate { repository.addPlannedEvent(planId, treeId, categoryId, at, taskId) }
    fun updatePlannedEvent(id: String, at: Long) = mutate { repository.updatePlannedEvent(id, at) }
    fun deletePlannedEvent(id: String) = mutate { repository.deletePlannedEvent(id) }

    fun reportToday(now: Long = System.currentTimeMillis()): List<LocalReportRow> {
        val zone = ZoneId.systemDefault(); val from = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return repository.report(dashboard.value, from, LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
    }
    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { uiError.value = null; app.syncScheduler.scheduleAfterChange() }
            .onFailure { error -> uiError.value = error.message ?: error.javaClass.simpleName; app.logger.event("ui_action_failed", mapOf("errorType" to error.javaClass.simpleName)) }
    }
    fun clearError() { uiError.value = null }
    fun resolveConflictUseServer(id: String) = mutate { repository.resolveConflictUseServer(id) }
    fun resolveConflictUseLocal(id: String, payload: String? = null) = mutate { repository.resolveConflictUseLocal(id, payload) }
    fun retryRejected(id: String, payload: String? = null) = mutate { repository.retryRejected(id, payload) }
    fun syncNow() = app.syncScheduler.syncNow()
    fun diagnosticLevel(): DiagnosticLevel = app.logger.level
    fun setDiagnosticLevel(level: DiagnosticLevel) = app.logger.setLevel(level)
    fun serverBaseUrl(): String = app.serverSettings.baseUrl
    fun syncDelay(): SyncDelay = app.serverSettings.syncDelay
    fun updateNetworkSettings(value: String, delay: SyncDelay): Boolean = app.serverSettings.update(value, delay)
    fun selectedWorkspace(): String = repository.workspaceId
    fun selectWorkspace(id: String) { repository.selectWorkspace(id); syncNow() }
    fun appLockEnabled(): Boolean = app.appLock.enabled
    fun setAppLockEnabled(enabled: Boolean): Boolean = app.appLock.setEnabled(enabled)
    fun oidcEnabled(): Boolean = app.authManager.enabled
    fun signOut(onComplete: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.database.clearAllTables(); app.authManager.signOut() }
        onComplete()
    }
    suspend fun exportBackup(password: CharArray? = null): String = withContext(Dispatchers.IO) {
        val snapshot = app.apiClient.snapshot(repository.workspaceId); if (password == null) snapshot else BackupCrypto.encrypt(snapshot, password)
    }
    suspend fun importBackup(container: String, password: CharArray? = null): Int = withContext(Dispatchers.IO) {
        val snapshot = if (BackupCrypto.isEncrypted(container)) BackupCrypto.decrypt(container, requireNotNull(password)) else container
        val result = app.apiClient.importWorkspace("Imported workspace", snapshot); repository.selectWorkspace(result.workspaceId); syncNow(); result.importedEntities
    }
}
