package app.t4l.data

sealed interface CategoryPlacement {
    data object Root : CategoryPlacement
    data class Before(val targetId: String) : CategoryPlacement
    data class Inside(val targetId: String) : CategoryPlacement
    data class After(val targetId: String) : CategoryPlacement
}

internal fun moveCategoryRows(
    categories: List<CategoryRow>,
    categoryId: String,
    placement: CategoryPlacement,
    now: Long,
): List<CategoryRow> {
    val active = categories.filter { !it.archived && it.deletedAtEpochMs == null }
    val byId = active.associateBy { it.id }
    val moving = requireNotNull(byId[categoryId])
    val targetId = when (placement) {
        CategoryPlacement.Root -> null
        is CategoryPlacement.Before -> placement.targetId
        is CategoryPlacement.Inside -> placement.targetId
        is CategoryPlacement.After -> placement.targetId
    }
    val target = targetId?.let { requireNotNull(byId[it]) }
    require(target == null || target.categoryTreeId == moving.categoryTreeId)

    val descendantIds = mutableSetOf<String>()
    val pending = ArrayDeque<String>().apply { add(categoryId) }
    while (pending.isNotEmpty()) {
        val parentId = pending.removeFirst()
        active.filter { it.parentId == parentId }.forEach { child ->
            if (descendantIds.add(child.id)) pending.add(child.id)
        }
    }
    require(targetId != categoryId && targetId !in descendantIds) { "A category cannot be moved into its own subtree." }

    val destinationParentId = when (placement) {
        CategoryPlacement.Root -> null
        is CategoryPlacement.Inside -> placement.targetId
        is CategoryPlacement.Before -> requireNotNull(target).parentId
        is CategoryPlacement.After -> requireNotNull(target).parentId
    }
    val sortedGroups = active
        .filter { it.categoryTreeId == moving.categoryTreeId }
        .groupBy { it.parentId }
        .mapValues { (_, rows) -> rows.sortedWith(compareBy<CategoryRow> { it.sortOrder }.thenBy { it.name }.thenBy { it.id }) }

    val destination = sortedGroups[destinationParentId].orEmpty().filterNot { it.id == categoryId }.toMutableList()
    val insertAt = when (placement) {
        CategoryPlacement.Root, is CategoryPlacement.Inside -> destination.size
        is CategoryPlacement.Before -> destination.indexOfFirst { it.id == placement.targetId }.also { require(it >= 0) }
        is CategoryPlacement.After -> destination.indexOfFirst { it.id == placement.targetId }.also { require(it >= 0) } + 1
    }
    destination.add(insertAt, moving)

    val finalGroups = buildMap<String?, List<CategoryRow>> {
        if (moving.parentId != destinationParentId) {
            put(moving.parentId, sortedGroups[moving.parentId].orEmpty().filterNot { it.id == categoryId })
        }
        put(destinationParentId, destination)
    }

    return finalGroups.flatMap { (parentId, rows) ->
        rows.mapIndexedNotNull { index, row ->
            val nextSortOrder = index * 10
            if (row.parentId == parentId && row.sortOrder == nextSortOrder) null
            else row.copy(
                parentId = parentId,
                sortOrder = nextSortOrder,
                updatedAtEpochMs = now,
                syncState = LocalSyncState.PENDING,
            )
        }
    }
}
