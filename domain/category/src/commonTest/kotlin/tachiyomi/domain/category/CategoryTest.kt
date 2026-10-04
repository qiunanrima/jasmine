package tachiyomi.domain.category

import tachiyomi.domain.category.model.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CategoryTest {
    @Test
    fun onlyUncategorizedIsSystemCategory() {
        assertTrue(Category(Category.UNCATEGORIZED_ID, "", 0, 0).isSystemCategory)
        assertFalse(Category(1, "Favorites", 0, 0).isSystemCategory)
    }

    @Test
    fun copyPreservesCategoryFields() {
        val original = Category(1, "Favorites", 2, 3)
        val renamed = original.copy(name = "Reading")

        assertEquals(Category(1, "Reading", 2, 3), renamed)
        assertEquals("Favorites", original.name)
    }
}
