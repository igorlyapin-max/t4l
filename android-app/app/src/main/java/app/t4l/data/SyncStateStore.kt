package app.t4l.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SyncPhase { IDLE, SYNCING, RETRY, SUCCESS }

data class SyncRuntimeState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val lastSuccessEpochMs: Long? = null,
    val message: String? = null,
)

class SyncStateStore {
    private val mutable = MutableStateFlow(SyncRuntimeState())
    val state: StateFlow<SyncRuntimeState> = mutable
    fun syncing() { mutable.value = mutable.value.copy(phase = SyncPhase.SYNCING, message = null) }
    fun success(now: Long = System.currentTimeMillis()) { mutable.value = SyncRuntimeState(SyncPhase.SUCCESS, now, null) }
    fun retry(message: String) { mutable.value = mutable.value.copy(phase = SyncPhase.RETRY, message = message.take(160)) }
}
