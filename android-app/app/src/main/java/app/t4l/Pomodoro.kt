package app.t4l

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PomodoroMode { SIMPLE, CLASSIC }
enum class PomodoroPhase { WORK, SHORT_BREAK, LONG_BREAK }
enum class PomodoroStatus { IDLE, RUNNING, PAUSED }

data class PomodoroState(
    val mode: PomodoroMode = PomodoroMode.SIMPLE,
    val workMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val phase: PomodoroPhase = PomodoroPhase.WORK,
    val status: PomodoroStatus = PomodoroStatus.IDLE,
    val workRound: Int = 1,
    val endsAtEpochMs: Long = 0,
    val pausedRemainingMs: Long = 25 * 60_000L,
) {
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        if (status == PomodoroStatus.RUNNING) (endsAtEpochMs - now).coerceAtLeast(0) else pausedRemainingMs
}

class PomodoroStore(context: Context) {
    private val preferences = context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<PomodoroState> = mutableState

    @Synchronized fun configure(mode: PomodoroMode, work: Int, shortBreak: Int, longBreak: Int) {
        save(PomodoroEngine.configure(mutableState.value, mode, work, shortBreak, longBreak))
    }

    @Synchronized fun start(now: Long = System.currentTimeMillis()): PomodoroState {
        val current = mutableState.value
        if (current.status == PomodoroStatus.RUNNING) return current
        val remaining = current.pausedRemainingMs.coerceAtLeast(1_000L)
        return current.copy(status = PomodoroStatus.RUNNING, endsAtEpochMs = now + remaining).also(::save)
    }

    @Synchronized fun pause(now: Long = System.currentTimeMillis()): PomodoroState {
        val current = mutableState.value
        if (current.status != PomodoroStatus.RUNNING) return current
        return current.copy(status = PomodoroStatus.PAUSED, pausedRemainingMs = current.remainingMs(now), endsAtEpochMs = 0).also(::save)
    }

    @Synchronized fun reset(): PomodoroState {
        val current = mutableState.value
        return current.copy(phase = PomodoroPhase.WORK, status = PomodoroStatus.IDLE, workRound = 1,
            endsAtEpochMs = 0, pausedRemainingMs = current.workMinutes * 60_000L).also(::save)
    }

    @Synchronized fun skip(): PomodoroState = PomodoroEngine.next(mutableState.value).also(::save)

    @Synchronized fun completeIfDue(now: Long = System.currentTimeMillis()): Boolean {
        val current = mutableState.value
        if (current.status != PomodoroStatus.RUNNING || current.endsAtEpochMs > now) return false
        save(PomodoroEngine.next(current))
        return true
    }

    private fun save(value: PomodoroState) {
        preferences.edit(commit = true) {
            putString("mode", value.mode.name); putInt("work", value.workMinutes); putInt("short", value.shortBreakMinutes)
            putInt("long", value.longBreakMinutes); putString("phase", value.phase.name); putString("status", value.status.name)
            putInt("round", value.workRound); putLong("ends", value.endsAtEpochMs); putLong("remaining", value.pausedRemainingMs)
        }
        mutableState.value = value
    }

    private fun read(): PomodoroState = PomodoroState(
        mode = enumValue(preferences.getString("mode", null), PomodoroMode.SIMPLE),
        workMinutes = preferences.getInt("work", 25), shortBreakMinutes = preferences.getInt("short", 5),
        longBreakMinutes = preferences.getInt("long", 15), phase = enumValue(preferences.getString("phase", null), PomodoroPhase.WORK),
        status = enumValue(preferences.getString("status", null), PomodoroStatus.IDLE), workRound = preferences.getInt("round", 1),
        endsAtEpochMs = preferences.getLong("ends", 0), pausedRemainingMs = preferences.getLong("remaining", 25 * 60_000L),
    )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)
}

internal object PomodoroEngine {
    fun configure(current: PomodoroState, mode: PomodoroMode, work: Int, shortBreak: Int, longBreak: Int): PomodoroState {
        require(work in 1..180 && shortBreak in 1..60 && longBreak in 1..120)
        val configured = current.copy(
            mode = mode,
            workMinutes = work,
            shortBreakMinutes = shortBreak,
            longBreakMinutes = longBreak,
        )
        return if (current.status == PomodoroStatus.IDLE) {
            configured.copy(
                phase = PomodoroPhase.WORK,
                workRound = 1,
                endsAtEpochMs = 0,
                pausedRemainingMs = work * 60_000L,
            )
        } else configured
    }

    fun next(current: PomodoroState): PomodoroState {
        val nextPhase: PomodoroPhase
        val nextRound: Int
        when (current.phase) {
            PomodoroPhase.WORK -> {
                nextPhase = if (current.mode == PomodoroMode.CLASSIC && current.workRound == 4) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK
                nextRound = current.workRound
            }
            PomodoroPhase.SHORT_BREAK -> { nextPhase = PomodoroPhase.WORK; nextRound = if (current.mode == PomodoroMode.CLASSIC) current.workRound + 1 else 1 }
            PomodoroPhase.LONG_BREAK -> { nextPhase = PomodoroPhase.WORK; nextRound = 1 }
        }
        val minutes = when (nextPhase) {
            PomodoroPhase.WORK -> current.workMinutes
            PomodoroPhase.SHORT_BREAK -> current.shortBreakMinutes
            PomodoroPhase.LONG_BREAK -> current.longBreakMinutes
        }
        return current.copy(phase = nextPhase, workRound = nextRound, status = PomodoroStatus.PAUSED,
            endsAtEpochMs = 0, pausedRemainingMs = minutes * 60_000L)
    }
}

object PomodoroRuntime {
    const val ACTION_START = "app.t4l.pomodoro.START"
    const val ACTION_PAUSE = "app.t4l.pomodoro.PAUSE"
    const val ACTION_STOP = "app.t4l.pomodoro.STOP"
    private const val ALARM_REQUEST = 401

    fun start(context: Context) {
        val state = (context.applicationContext as T4LApplication).pomodoroStore.start()
        scheduleAlarm(context, state.endsAtEpochMs)
        ContextCompat.startForegroundService(context, Intent(context, PomodoroService::class.java).setAction(ACTION_START))
    }
    fun pause(context: Context) {
        context.startService(Intent(context, PomodoroService::class.java).setAction(ACTION_PAUSE))
    }
    fun reset(context: Context) { cancelAlarm(context); PomodoroNotifications.cancelRunning(context); val serviceIntent = Intent(context, PomodoroService::class.java); context.stopService(serviceIntent); (context.applicationContext as T4LApplication).pomodoroStore.reset() }
    fun skip(context: Context) { cancelAlarm(context); PomodoroNotifications.cancelRunning(context); val serviceIntent = Intent(context, PomodoroService::class.java); context.stopService(serviceIntent); (context.applicationContext as T4LApplication).pomodoroStore.skip() }
    fun canScheduleExact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    @RequiresApi(31)
    fun exactAlarmSettings(context: Context) = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
    fun notificationSettings(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun scheduleAlarm(context: Context, at: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, ALARM_REQUEST, Intent(context, PomodoroAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (canScheduleExact(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }
    fun cancelAlarm(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(
        PendingIntent.getBroadcast(context, ALARM_REQUEST, Intent(context, PomodoroAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
}

class PomodoroService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { update(); handler.postDelayed(this, 1_000) } }
    override fun onCreate() { super.onCreate(); PomodoroNotifications.channels(this) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PomodoroRuntime.ACTION_PAUSE -> {
                (application as T4LApplication).pomodoroStore.pause(); PomodoroRuntime.cancelAlarm(this)
                stopForeground(STOP_FOREGROUND_REMOVE); PomodoroNotifications.paused(this); stopSelf(); return START_NOT_STICKY
            }
            PomodoroRuntime.ACTION_STOP -> {
                PomodoroRuntime.cancelAlarm(this); (application as T4LApplication).pomodoroStore.reset()
                stopForeground(STOP_FOREGROUND_REMOVE); PomodoroNotifications.cancelRunning(this); stopSelf(); return START_NOT_STICKY
            }
            PomodoroRuntime.ACTION_START -> {
                val store = (application as T4LApplication).pomodoroStore
                if (store.state.value.status != PomodoroStatus.RUNNING) {
                    val state = store.start(); PomodoroRuntime.scheduleAlarm(this, state.endsAtEpochMs)
                }
            }
        }
        startForeground(PomodoroNotifications.RUNNING_ID, PomodoroNotifications.running(this))
        handler.removeCallbacks(tick); handler.post(tick)
        return START_NOT_STICKY
    }
    private fun update() {
        val store = (application as T4LApplication).pomodoroStore
        if (store.completeIfDue()) { PomodoroNotifications.completed(this); stopSelf(); return }
        getSystemService(NotificationManager::class.java).notify(PomodoroNotifications.RUNNING_ID, PomodoroNotifications.running(this))
    }
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

class PomodoroAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if ((context.applicationContext as T4LApplication).pomodoroStore.completeIfDue()) PomodoroNotifications.completed(context)
        val serviceIntent = Intent(context, PomodoroService::class.java); context.stopService(serviceIntent)
    }
}

class PomodoroBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val state = (context.applicationContext as T4LApplication).pomodoroStore.state.value
        if (state.status == PomodoroStatus.RUNNING) {
            if (state.endsAtEpochMs <= System.currentTimeMillis()) {
                if ((context.applicationContext as T4LApplication).pomodoroStore.completeIfDue()) PomodoroNotifications.completed(context)
            } else PomodoroRuntime.scheduleAlarm(context, state.endsAtEpochMs)
        }
    }
}

private object PomodoroNotifications {
    const val RUNNING_ID = 6101
    private const val COMPLETE_ID = 6102
    private const val CHANNEL_RUNNING = "pomodoro_running"
    private const val CHANNEL_COMPLETE = "pomodoro_complete"
    fun channels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_RUNNING, context.getString(R.string.pomodoro_timer), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_COMPLETE, context.getString(R.string.pomodoro_complete), NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) },
        ))
    }
    fun running(context: Context): android.app.Notification {
        channels(context)
        val state = (context.applicationContext as T4LApplication).pomodoroStore.state.value
        val remaining = state.remainingMs(); val text = "%02d:%02d".format(remaining / 60_000, remaining / 1_000 % 60)
        val pause = PendingIntent.getService(context, 1, Intent(context, PomodoroService::class.java).setAction(PomodoroRuntime.ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(context, 2, Intent(context, PomodoroService::class.java).setAction(PomodoroRuntime.ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_RUNNING).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.pomodoro_phase_title, state.phase.localized(context)))
            .setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(0, context.getString(R.string.pause), pause).addAction(0, context.getString(R.string.reset), stop).build()
    }
    fun paused(context: Context) {
        channels(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val state = (context.applicationContext as T4LApplication).pomodoroStore.state.value
        val remaining = state.remainingMs(); val text = "%02d:%02d".format(remaining / 60_000, remaining / 1_000 % 60)
        val resume = PendingIntent.getService(context, 3, Intent(context, PomodoroService::class.java).setAction(PomodoroRuntime.ACTION_START), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(context, 4, Intent(context, PomodoroService::class.java).setAction(PomodoroRuntime.ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getManager(context).notify(RUNNING_ID, NotificationCompat.Builder(context, CHANNEL_RUNNING).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.pomodoro_phase_title, state.phase.localized(context))).setContentText(text)
            .addAction(0, context.getString(R.string.resume), resume).addAction(0, context.getString(R.string.reset), stop).setAutoCancel(false).build())
    }
    fun cancelRunning(context: Context) = getManager(context).cancel(RUNNING_ID)
    fun completed(context: Context) {
        channels(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val state = (context.applicationContext as T4LApplication).pomodoroStore.state.value
        getManager(context).notify(COMPLETE_ID, NotificationCompat.Builder(context, CHANNEL_COMPLETE).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.pomodoro_complete)).setContentText(context.getString(R.string.next_phase_ready, state.phase.localized(context)))
            .setAutoCancel(true).setVibrate(longArrayOf(0, 250, 150, 250)).build())
    }
    private fun getManager(context: Context) = context.getSystemService(NotificationManager::class.java)
    private fun PomodoroPhase.localized(context: Context) = context.getString(when (this) {
        PomodoroPhase.WORK -> R.string.work_phase; PomodoroPhase.SHORT_BREAK -> R.string.short_break; PomodoroPhase.LONG_BREAK -> R.string.long_break
    })
}
