package eu.kanade.tachiyomi.source.builtin.base

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

data class AlignedSourceCategory(
    val name: String,
    val filters: FilterList,
)

internal fun buildSourceCategories(
    defaultFilters: () -> FilterList,
    categoryFilter: () -> Filter.Select<String>,
): List<AlignedSourceCategory> = categoryFilter().values.mapIndexedNotNull { index, name ->
    if (index == 0 || name.isBlank()) return@mapIndexedNotNull null
    val selectedFilter = categoryFilter().apply { state = index }
    val filters = FilterList(defaultFilters().map {
        if (it.javaClass == selectedFilter.javaClass) selectedFilter else it
    })
    AlignedSourceCategory(name, filters)
}
