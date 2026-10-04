package eu.kanade.tachiyomi.source.builtin.base

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlignedSourceCategoryTest {
    private class CategoryFilter(values: Array<String>) : Filter.Select<String>("Category", values)
    private class SortFilter : Filter.Select<String>("Sort", arrayOf("Newest", "Popular"))

    @Test
    fun `API categories replace defaults and preserve other filters`() {
        val categories = buildSourceCategories(
            defaultFilters = { FilterList(SortFilter(), CategoryFilter(arrayOf("All", "Old"))) },
            categoryFilter = { CategoryFilter(arrayOf("All", "First", "Second")) },
        )

        assertEquals(listOf("First", "Second"), categories.map { it.name })
        categories.forEachIndexed { index, category ->
            assertEquals(2, category.filters.size)
            val filter = category.filters.filterIsInstance<CategoryFilter>().single()
            assertEquals(index + 1, filter.state)
            assertEquals(category.name, filter.values[filter.state])
            assertEquals(0, category.filters.filterIsInstance<SortFilter>().single().state)
        }
        assertNotSame(categories[0].filters[0], categories[1].filters[0])
        assertNotSame(categories[0].filters[1], categories[1].filters[1])
        (categories[0].filters[0] as SortFilter).state = 1
        assertEquals(0, (categories[1].filters[0] as SortFilter).state)
    }

    @Test
    fun `empty and blank categories produce no options`() {
        assertTrue(
            buildSourceCategories(
                defaultFilters = { FilterList(CategoryFilter(arrayOf("All"))) },
                categoryFilter = { CategoryFilter(arrayOf("All", "")) },
            ).isEmpty(),
        )
    }
}
