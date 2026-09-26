package app.t4l

import android.app.Application
import app.t4l.data.ApiClient
import app.t4l.data.DeviceIdentity
import app.t4l.data.T4LDatabase
import app.t4l.data.T4LRepository
import app.t4l.data.ProfileRepository
import app.t4l.data.SyncStateStore
import okhttp3.OkHttpClient

class T4LApplication : Application() {
    lateinit var database: T4LDatabase
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var serverSettings: ServerSettings
        private set
    lateinit var syncScheduler: SyncScheduler
        private set
    lateinit var repository: T4LRepository
        private set
    lateinit var profileRepository: ProfileRepository
        private set
    lateinit var logger: StructuredLogger
        private set
    lateinit var appLock: AppLockSettings
        private set
    lateinit var syncStateStore: SyncStateStore
        private set
    lateinit var authManager: AuthManager
        private set
    lateinit var pomodoroStore: PomodoroStore
        private set
    lateinit var taskListSettings: TaskListSettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        database = T4LDatabase.create(this)
        serverSettings = ServerSettings(this)
        authManager = AuthManager(this)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val token = authManager.freshAccessToken()
            val request = if (token == null) chain.request() else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            chain.proceed(request)
        }.build()
        apiClient = ApiClient(http = http, baseUrlProvider = { serverSettings.baseUrl })
        syncStateStore = SyncStateStore()
        pomodoroStore = PomodoroStore(this)
        taskListSettings = TaskListSettingsStore(this)
        val identity = DeviceIdentity(this)
        repository = T4LRepository(database, identity, syncStateStore)
        profileRepository = ProfileRepository(this, database, apiClient, identity, actorInitiallyResolved = !authManager.enabled)
        logger = StructuredLogger(this)
        appLock = AppLockSettings(this)
        syncScheduler = SyncScheduler(this, serverSettings)
        logger.event("application_started")
        syncScheduler.cancelObsoleteWork()
        if (!authManager.enabled || authManager.authorized) syncScheduler.scheduleColdStart()
    }
}
