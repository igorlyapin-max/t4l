package app.t4l

import android.content.Context
import androidx.core.content.edit

/** Device-local presentation state; no timeline or category data is stored here. */
class TimelineUiStore(context: Context) {
    private val preferences = context.getSharedPreferences("timeline_ui", Context.MODE_PRIVATE)

    fun selectedPlan(workspaceId: String): String? = preferences.getString("$workspaceId.plan", null)
    fun selectPlan(workspaceId: String, planId: String?) {
        preferences.edit { if (planId == null) remove("$workspaceId.plan") else putString("$workspaceId.plan", planId) }
    }

    private fun migratePanel(workspaceId: String, screen: String) {
        val prefix = "$workspaceId.$screen"
        if (preferences.contains("$prefix.panelFraction")) return
        val previous = preferences.getInt("$prefix.panel", 1).coerceIn(0, 2)
        preferences.edit {
            putFloat("$prefix.panelFraction", if (previous == 2) 0.8f else 0.5f)
            putBoolean("$prefix.panelCollapsed", previous == 0)
            remove("$prefix.panel")
        }
    }

    fun panelFraction(workspaceId: String, screen: String): Float {
        migratePanel(workspaceId, screen)
        return preferences.getFloat("$workspaceId.$screen.panelFraction", 0.5f).coerceIn(0.1f, 0.9f)
    }
    fun setPanelFraction(workspaceId: String, screen: String, value: Float) {
        preferences.edit { putFloat("$workspaceId.$screen.panelFraction", value.coerceIn(0.1f, 0.9f)) }
    }
    fun panelCollapsed(workspaceId: String, screen: String): Boolean {
        migratePanel(workspaceId, screen)
        return preferences.getBoolean("$workspaceId.$screen.panelCollapsed", false)
    }
    fun setPanelCollapsed(workspaceId: String, screen: String, value: Boolean) {
        preferences.edit { putBoolean("$workspaceId.$screen.panelCollapsed", value) }
    }

    fun categorizationSplit(workspaceId: String): Float = preferences.getFloat("$workspaceId.categorizationSplit", 0.42f).coerceIn(0.1f, 0.9f)
    fun setCategorizationSplit(workspaceId: String, value: Float) {
        preferences.edit { putFloat("$workspaceId.categorizationSplit", value.coerceIn(0.1f, 0.9f)) }
    }

    fun hideExtra(workspaceId: String, screen: String): Boolean = preferences.getBoolean("$workspaceId.$screen.hideExtra", false)
    fun setHideExtra(workspaceId: String, screen: String, value: Boolean) {
        preferences.edit { putBoolean("$workspaceId.$screen.hideExtra", value) }
    }

    fun hiddenTrees(workspaceId: String): Set<String> = preferences.getStringSet("$workspaceId.hiddenTrees", emptySet())?.toSet().orEmpty()
    fun setHiddenTrees(workspaceId: String, ids: Set<String>) {
        preferences.edit { putStringSet("$workspaceId.hiddenTrees", ids.toSet()) }
    }

    fun hideTreeNames(workspaceId: String): Boolean = preferences.getBoolean("$workspaceId.hideTreeNames", false)
    fun setHideTreeNames(workspaceId: String, value: Boolean) {
        preferences.edit { putBoolean("$workspaceId.hideTreeNames", value) }
    }

    fun sharedPalette(workspaceId: String): String? = preferences.getString("$workspaceId.palette", null)
    fun setSharedPalette(workspaceId: String, id: String?) {
        preferences.edit { if (id == null) remove("$workspaceId.palette") else putString("$workspaceId.palette", id) }
    }

    fun planPalette(workspaceId: String, planId: String): String? = preferences.getString("$workspaceId.plan.$planId.palette", null)
    fun setPlanPalette(workspaceId: String, planId: String, id: String?) {
        preferences.edit { if (id == null) remove("$workspaceId.plan.$planId.palette") else putString("$workspaceId.plan.$planId.palette", id) }
    }

    fun paletteDate(workspaceId: String, screenKey: String): Long? =
        preferences.getLong("$workspaceId.$screenKey.paletteDate", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }
    fun setPaletteDate(workspaceId: String, screenKey: String, epochDay: Long?) {
        preferences.edit {
            if (epochDay == null) remove("$workspaceId.$screenKey.paletteDate") else putLong("$workspaceId.$screenKey.paletteDate", epochDay)
        }
    }
}
