package app.t4l.domain

data class PlanningTask(
    val id: String,
    val remainingMinutes: Int,
    val value: Int,
    val energy: String,
    val deadlineEpochMs: Long?,
    val splittable: Boolean,
)

data class PlanningWindow(val startsAtEpochMs: Long, val endsAtEpochMs: Long) {
    val durationMinutes: Int get() = ((endsAtEpochMs - startsAtEpochMs) / 60_000).toInt().coerceAtLeast(0)
}

data class Recommendation(val taskId: String, val suggestedMinutes: Int, val score: Double)

object PlanningEngine {
    fun freeWindows(availability: List<PlanningWindow>, occupied: List<PlanningWindow>): List<PlanningWindow> =
        availability.sortedBy { it.startsAtEpochMs }.flatMap { available ->
            val result = mutableListOf<PlanningWindow>()
            var cursor = available.startsAtEpochMs
            occupied.sortedBy { it.startsAtEpochMs }
                .filter { it.endsAtEpochMs > available.startsAtEpochMs && it.startsAtEpochMs < available.endsAtEpochMs }
                .forEach { block ->
                    if (block.startsAtEpochMs > cursor) {
                        result += PlanningWindow(cursor, minOf(block.startsAtEpochMs, available.endsAtEpochMs))
                    }
                    cursor = maxOf(cursor, block.endsAtEpochMs)
                }
            if (cursor < available.endsAtEpochMs) result += PlanningWindow(cursor, available.endsAtEpochMs)
            result
        }.filter { it.durationMinutes > 0 }

    fun recommend(
        tasks: List<PlanningTask>,
        windowMinutes: Int,
        energy: String,
        nowEpochMs: Long,
        planningFactor: Double = 1.0,
    ): List<Recommendation> = tasks.mapNotNull { task ->
        val adjusted = kotlin.math.ceil(task.remainingMinutes * planningFactor.coerceAtLeast(0.01)).toInt().coerceAtLeast(1)
        if (adjusted > windowMinutes && !task.splittable) return@mapNotNull null
        val urgency = task.deadlineEpochMs?.let { (30.0 - (it - nowEpochMs) / 86_400_000.0).coerceAtLeast(0.0) } ?: 0.0
        val score = task.value + urgency +
            (if (task.energy == energy) 25 else 0) +
            (if (adjusted <= windowMinutes) 10 else 0)
        Recommendation(task.id, minOf(adjusted, windowMinutes), score)
    }.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.suggestedMinutes }.thenBy { it.taskId })

    fun planningFactor(samples: List<Pair<Int, Int>>): Double {
        val ratios = samples.filter { it.first > 0 && it.second >= 0 }.map { it.second.toDouble() / it.first }.sorted()
        if (ratios.size < 3) return 1.0
        val middle = ratios.size / 2
        return if (ratios.size % 2 == 1) ratios[middle] else (ratios[middle - 1] + ratios[middle]) / 2
    }
}

object BudgetEngine {
    fun totalMinutes(categoryId: String, parents: Map<String, String?>, own: Map<String, Int>): Int {
        val visiting = mutableSetOf<String>()
        fun total(id: String): Int {
            require(visiting.add(id)) { "Category cycle detected." }
            val value = (own[id] ?: 0) + parents.filterValues { it == id }.keys.sumOf(::total)
            visiting.remove(id)
            return value
        }
        return total(categoryId)
    }
}
