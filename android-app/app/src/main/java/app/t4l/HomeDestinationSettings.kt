package app.t4l

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class HomeDestination(val route: String, val label: Int) {
    TOMATO("tomato", R.string.pomodoro_timer),
    TRACK("track", R.string.track),
    HISTORY("history", R.string.history),
    PLANNER("planner", R.string.planner),
    TASKS("tasks", R.string.tasks),
    REPORTS("reports", R.string.reports);

    companion object {
        fun fromStored(value: String?): HomeDestination = entries.firstOrNull { it.route == value } ?: TOMATO
    }
}

internal fun isVisibleMainMenuRoute(route: String, homeDestination: HomeDestination): Boolean =
    route != homeDestination.route

class HomeDestinationStore(context: Context) {
    private val preferences = context.getSharedPreferences("home_destination", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(HomeDestination.fromStored(preferences.getString("route", null)))
    val state: StateFlow<HomeDestination> = mutableState

    fun update(value: HomeDestination) {
        preferences.edit { putString("route", value.route) }
        mutableState.value = value
    }
}
