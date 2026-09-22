package app.t4l

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

enum class DiagnosticLevel { OFF, BASIC, VERBOSE }

@Serializable data class PendingDiagnosticEvent(val timestamp: String, val level: String, val eventName: String, val attributes: Map<String, String>)

class StructuredLogger(private val context: Context) {
    private val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    val level: DiagnosticLevel
        get() = runCatching {
            DiagnosticLevel.valueOf(preferences.getString("level", DiagnosticLevel.OFF.name).orEmpty())
        }.getOrDefault(DiagnosticLevel.OFF)

    fun setLevel(value: DiagnosticLevel) {
        preferences.edit { putString("level", value.name) }
        event("diagnostic_level_changed", mapOf("level" to value.name))
    }

    fun event(name: String, fields: Map<String, String> = emptyMap(), minimumLevel: DiagnosticLevel = DiagnosticLevel.BASIC) {
        val activeLevel = level
        if (activeLevel == DiagnosticLevel.OFF || minimumLevel == DiagnosticLevel.VERBOSE && activeLevel != DiagnosticLevel.VERBOSE) return
        if (name !in EVENT_NAMES || fields.keys.any { it !in VERBOSE_FIELDS }) {
            Log.w("T4L", "diagnostic_event_rejected")
            return
        }
        val allowed = if (minimumLevel == DiagnosticLevel.VERBOSE) VERBOSE_FIELDS else BASIC_FIELDS
        val safeFields = fields.filterKeys(allowed::contains).mapValues { (key, value) -> redact(key, value) }
        val line = Json.encodeToString(PendingDiagnosticEvent(Instant.now().toString(), minimumLevel.name.lowercase(), name.take(80), safeFields))
        Log.i("T4L", line)
        writeRotatingFile(line)
        DiagnosticUploadScheduler.schedule(context)
    }

    fun verboseEvent(name: String, fields: Map<String, String> = emptyMap()) = event(name, fields, DiagnosticLevel.VERBOSE)

    private fun redact(key: String, value: String): String = when (key) {
        "errorType" -> value.takeIf(ERROR_TYPES::contains) ?: "Other"
        "durationMs", "pendingCount", "conflictCount" -> value.toLongOrNull()?.coerceAtLeast(0)?.toString() ?: "0"
        "level" -> value.takeIf { it in setOf("OFF", "BASIC", "VERBOSE") } ?: "OFF"
        "outcome" -> value.takeIf { it in setOf("success", "failure", "denied", "queued") } ?: "failure"
        "phase" -> value.takeIf { it == "sync" } ?: "sync"
        "operation", "status" -> value.take(40).filter { it.isLetterOrDigit() || it in "_-." }
        else -> "[redacted]"
    }

    private fun writeRotatingFile(line: String) {
        val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val file = File(directory, "t4l.ndjson")
        if (file.exists() && file.length() > MAX_BYTES) {
            val previous = File(directory, "t4l.previous.ndjson")
            if (previous.exists()) previous.delete()
            file.renameTo(previous)
        }
        file.appendText(line + "\n")
    }

    @Synchronized fun pending(limit: Int = 100): List<PendingDiagnosticEvent> {
        val file = File(context.filesDir, "diagnostics/t4l.ndjson")
        if (!file.exists()) return emptyList()
        return file.useLines { lines -> lines.take(limit).mapNotNull { runCatching { Json.decodeFromString<PendingDiagnosticEvent>(it) }.getOrNull() }.toList() }
    }

    @Synchronized fun acknowledge(count: Int) {
        if (count <= 0) return
        val file = File(context.filesDir, "diagnostics/t4l.ndjson")
        if (!file.exists()) return
        val remaining = file.readLines().drop(count)
        file.writeText(remaining.joinToString(separator = "\n", postfix = if (remaining.isEmpty()) "" else "\n"))
    }

    private companion object {
        const val MAX_BYTES = 512 * 1024L
        val BASIC_FIELDS = setOf("durationMs", "outcome", "errorType", "pendingCount", "conflictCount", "level")
        val VERBOSE_FIELDS = BASIC_FIELDS + setOf("phase", "operation", "status")
        val EVENT_NAMES = setOf(
            "application_started", "diagnostic_level_changed", "sync_started", "sync_succeeded", "sync_retry",
            "ui_action_failed", "backup_export_succeeded", "backup_export_failed", "backup_import_succeeded",
            "backup_import_failed", "sync_conflict_resolution_queued", "pomodoro_notification_denied", "profile_actor_rebound",
        )
        val ERROR_TYPES = setOf("IOException", "ConnectException", "UnknownHostException", "SocketTimeoutException", "IllegalArgumentException", "IllegalStateException", "SerializationException", "AEADBadTagException", "SecurityException", "Other")
    }
}
