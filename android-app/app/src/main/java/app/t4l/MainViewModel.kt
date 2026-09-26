package app.t4l

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.t4l.data.DashboardState
import app.t4l.data.CategoryPlacement
import app.t4l.data.LocalReportRow
import app.t4l.data.PlannerState
import app.t4l.data.T4LRepository
import app.t4l.data.TaskState
import app.t4l.data.SyncUiState
import app.t4l.data.WorkspaceOption
import app.t4l.data.UserProfileRow
import app.t4l.data.PersonalConflictRow
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
    val profile: StateFlow<UserProfileRow?> = app.profileRepository.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val personalConflicts: StateFlow<List<PersonalConflictRow>> = app.profileRepository.personalConflicts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val profileActorResolved: StateFlow<Boolean> = app.profileRepository.actorResolved
    val pomodoro: StateFlow<PomodoroState> = app.pomodoroStore.state
    val taskListSettings: StateFlow<TaskListSettings> = app.taskListSettings.state
    val uiFeedback = MutableStateFlow<UiFeedback?>(null)
    val backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    private var pendingEncryptedBackup: String? = null

    fun createStarterData() = mutate { repository.createStarterData() }
    fun createCategoryTree(name: String) = mutate { repository.createCategoryTree(name) }
    fun addCategory(treeId: String, name: String, parentId: String? = null) = mutate { repository.addCategory(treeId, name, parentId) }
    fun moveCategory(id: String, placement: CategoryPlacement) = mutate { repository.moveCategory(id, placement) }
    fun renameCategory(id: String, name: String) = mutate { repository.renameCategory(id, name) }
    fun renameCategoryTree(id: String, name: String) = mutate { repository.renameCategoryTree(id, name) }
    fun archiveCategory(id: String) = mutate { repository.archiveCategory(id) }
    fun archiveCategoryTree(id: String) = mutate { repository.archiveCategoryTree(id) }
    fun restoreCategoryTree(id: String) = mutate { repository.restoreCategoryTree(id) }
    fun restoreArchivedCategories(treeId: String) = mutate { repository.restoreArchivedCategories(treeId) }
    fun purgeCategoryTree(id: String) = mutate { repository.purgeCategoryTree(id) }
    fun switch(treeId: String, categoryId: String?) = mutate { repository.switchCategory(treeId, categoryId) }
    fun addEvent(treeId: String, categoryId: String?, at: Long, taskId: String? = null, onComplete: ((Boolean) -> Unit)? = null) = mutate(onComplete) { repository.addEvent(treeId, categoryId, at, taskId) }
    fun updateEvent(id: String, treeId: String, categoryId: String?, taskId: String?, at: Long, onComplete: ((Boolean) -> Unit)? = null) =
        mutate(onComplete) { repository.updateEvent(id, treeId, categoryId, taskId, at) }
    fun deleteEvent(id: String) = mutate { repository.deleteEvent(id) }
    fun createTask(title: String, categoryId: String?, parentTaskId: String?, estimate: Int, nextAction: LocalDate, nextMinute: Int?, deadline: Long?, onComplete: ((Boolean) -> Unit)? = null) =
        mutate(onComplete) { repository.createTask(title, categoryId, parentTaskId, estimate, nextAction, nextMinute, deadline) }
    fun updateTask(id: String, title: String, categoryId: String?, estimate: Int, nextAction: LocalDate, nextMinute: Int?, deadline: Long?, onComplete: ((Boolean) -> Unit)? = null) =
        mutate(onComplete) { repository.updateTask(id, title, categoryId, estimate, nextAction, nextMinute, deadline) }
    fun renameTask(id: String, title: String) = mutate { repository.renameTask(id, title) }
    fun updateTaskDetails(id: String, title: String, categoryId: String?, parentTaskId: String?, estimate: Int,
        nextAction: LocalDate?, nextMinute: Int?, deadline: Long?, value: Int, energy: String, progress: Int, status: String,
        splittable: Boolean, onComplete: ((Boolean) -> Unit)? = null) = mutate(onComplete) {
        repository.updateTaskDetails(id, title, categoryId, parentTaskId, estimate, nextAction, nextMinute, deadline, value, energy, progress, status, splittable)
    }
    fun reorderTask(id: String, targetId: String, after: Boolean) = mutate { repository.reorderTask(id, targetId, after) }
    fun updateTaskListSettings(value: TaskListSettings) = app.taskListSettings.update(value)
    fun setTaskStatus(id: String, status: String) = mutate { repository.setTaskStatus(id, status) }
    fun deleteTask(id: String) = mutate { repository.deleteTask(id) }
    fun addTaskComment(id: String, text: String) = mutate { repository.addTaskComment(id, text) }
    fun startTask(id: String) = mutate { repository.startTask(id) }
    fun createPlan(name: String, startsAt: Long, endsAt: Long) = mutate { repository.createPlan(name, startsAt, endsAt) }
    fun updatePlanPeriod(id: String, startsAt: Long, endsAt: Long, onComplete: ((Boolean) -> Unit)? = null) =
        mutate(onComplete) { repository.updatePlanPeriod(id, startsAt, endsAt) }
    fun archivePlan(id: String) = mutate { repository.archivePlan(id) }
    fun restorePlan(id: String) = mutate { repository.restorePlan(id) }
    fun deletePlan(id: String) = mutate { repository.deletePlan(id) }
    fun setBudget(planId: String, categoryId: String, minutes: Int) = mutate { repository.setBudget(planId, categoryId, minutes) }
    fun addPlannedEvent(planId: String, treeId: String, categoryId: String?, at: Long, taskId: String? = null, onComplete: ((Boolean) -> Unit)? = null) = mutate(onComplete) { repository.addPlannedEvent(planId, treeId, categoryId, at, taskId) }
    fun updatePlannedEvent(id: String, treeId: String, categoryId: String?, taskId: String?, at: Long, onComplete: ((Boolean) -> Unit)? = null) =
        mutate(onComplete) { repository.updatePlannedEvent(id, treeId, categoryId, taskId, at) }
    fun deletePlannedEvent(id: String) = mutate { repository.deletePlannedEvent(id) }

    fun reportToday(now: Long = System.currentTimeMillis()): List<LocalReportRow> {
        val zone = ZoneId.systemDefault(); val from = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return repository.report(dashboard.value, from, LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
    }
    private fun mutate(onComplete: ((Boolean) -> Unit)? = null, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { app.syncScheduler.scheduleAfterChange(); onComplete?.invoke(true) }
            .onFailure { error -> showFailure(error); app.logger.event("ui_action_failed", mapOf("errorType" to error.javaClass.simpleName)); onComplete?.invoke(false) }
    }
    fun consumeFeedback(id: Long) { if (uiFeedback.value?.id == id) uiFeedback.value = null }
    fun resolveConflictUseServer(id: String) = resolveConflict(id) { repository.resolveConflictUseServer(id) }
    fun resolveConflictUseLocal(id: String, payload: String? = null) = resolveConflict(id) { repository.resolveConflictUseLocal(id, payload) }
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
        withContext(Dispatchers.IO) { app.profileRepository.clearLocalFiles(); app.database.clearAllTables(); app.authManager.signOut() }
        onComplete()
    }
    fun saveProfile(birthDate: LocalDate?, lifeExpectancyYears: Double?) = mutate { app.profileRepository.saveProfile(birthDate, lifeExpectancyYears) }
    fun saveAvatar(bytes: ByteArray) = mutate { app.profileRepository.saveAvatar(bytes) }
    fun removeAvatar() = mutate { app.profileRepository.removeAvatar() }
    fun resolvePersonalConflictUseServer(id: String) = resolveConflict(id) { app.profileRepository.resolveConflictUseServer(id) }
    fun resolvePersonalConflictUseLocal(id: String) = resolveConflict(id) { app.profileRepository.resolveConflictUseLocal(id) }
    fun configurePomodoro(mode: PomodoroMode, work: Int, shortBreak: Int, longBreak: Int) {
        val appliesNextPhase = app.pomodoroStore.state.value.status != PomodoroStatus.IDLE
        runCatching { app.pomodoroStore.configure(mode, work, shortBreak, longBreak) }
            .onSuccess {
                uiFeedback.value = UiFeedback(
                    kind = if (appliesNextPhase) UiMessageKind.POMODORO_SETTINGS_SAVED_FOR_NEXT_PHASE else UiMessageKind.POMODORO_SETTINGS_SAVED,
                )
                app.logger.event("pomodoro_settings_saved", mapOf("status" to if (appliesNextPhase) "active" else "idle"))
            }
            .onFailure(::showFailure)
    }

    fun exportBackup(destination: Uri, password: CharArray? = null) = viewModelScope.launch {
        backupState.value = BackupUiState.Exporting
        try {
            val content = withContext(Dispatchers.IO) {
                val snapshot = app.apiClient.snapshot(repository.workspaceId)
                if (password == null) snapshot else BackupCrypto.encrypt(snapshot, password)
            }
            withContext(Dispatchers.IO) {
                val stream = requireNotNull(app.contentResolver.openOutputStream(destination))
                stream.bufferedWriter().use { it.write(content) }
            }
            uiFeedback.value = UiFeedback(kind = UiMessageKind.BACKUP_EXPORT_SUCCEEDED)
            app.logger.event("backup_export_succeeded")
        } catch (error: Throwable) {
            password?.fill('\u0000')
            showFailure(error, backup = true)
            app.logger.event("backup_export_failed", mapOf("errorType" to error.javaClass.simpleName))
        } finally {
            backupState.value = BackupUiState.Idle
        }
    }

    fun inspectBackup(source: Uri) = viewModelScope.launch {
        backupState.value = BackupUiState.Importing
        try {
            val content = withContext(Dispatchers.IO) {
                val stream = requireNotNull(app.contentResolver.openInputStream(source))
                stream.bufferedReader().use { it.readText() }
            }
            if (BackupCrypto.isEncrypted(content)) {
                pendingEncryptedBackup = content
                backupState.value = BackupUiState.PasswordRequired
            } else {
                importBackupContent(content, null)
            }
        } catch (error: Throwable) {
            showFailure(error, backup = true)
            app.logger.event("backup_import_failed", mapOf("errorType" to error.javaClass.simpleName))
            backupState.value = BackupUiState.Idle
        }
    }

    fun importPendingBackup(password: CharArray) {
        val content = pendingEncryptedBackup
        if (content == null) {
            password.fill('\u0000')
            showFailure(IllegalStateException("No pending encrypted backup."), backup = true)
            backupState.value = BackupUiState.Idle
            return
        }
        pendingEncryptedBackup = null
        viewModelScope.launch { importBackupContent(content, password) }
    }

    fun cancelBackupPassword() {
        pendingEncryptedBackup = null
        backupState.value = BackupUiState.Idle
    }

    private suspend fun importBackupContent(container: String, password: CharArray?) {
        backupState.value = BackupUiState.Importing
        try {
            val count = withContext(Dispatchers.IO) {
                val snapshot = if (BackupCrypto.isEncrypted(container)) BackupCrypto.decrypt(container, requireNotNull(password)) else container
                val result = app.apiClient.importWorkspace("Imported workspace", snapshot)
                repository.selectWorkspace(result.workspaceId)
                result.importedEntities
            }
            syncNow()
            uiFeedback.value = UiFeedback(kind = UiMessageKind.BACKUP_IMPORT_SUCCEEDED, count = count)
            app.logger.event("backup_import_succeeded")
        } catch (error: Throwable) {
            password?.fill('\u0000')
            showFailure(error, backup = true)
            app.logger.event("backup_import_failed", mapOf("errorType" to error.javaClass.simpleName))
        } finally {
            backupState.value = BackupUiState.Idle
        }
    }

    private fun resolveConflict(id: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess {
                app.syncScheduler.scheduleAfterChange()
                uiFeedback.value = UiFeedback(kind = UiMessageKind.CONFLICT_RESOLUTION_QUEUED)
                app.logger.event("sync_conflict_resolution_queued")
            }
            .onFailure(::showFailure)
    }

    private fun showFailure(error: Throwable, backup: Boolean = false) {
        uiFeedback.value = UiFeedback(kind = UiErrorMapper.map(error, backup))
    }
}
