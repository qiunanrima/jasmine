package tachiyomi.domain.category

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GetCategoriesTest {
    @Test
    fun queriesAndSubscriptionsUseTheCorrectRepositoryMethods() = runTest {
        val repository = RecordingRepository()
        val query = GetCategories(repository)

        assertEquals(repository.all, query.await())
        assertEquals(repository.forManga, query.await(42))
        assertEquals(42L, repository.requestedMangaId)
        assertSame(repository.allFlow, query.subscribe())
        assertSame(repository.mangaFlow, query.subscribe(43))
        assertEquals(43L, repository.requestedMangaId)
    }

    private class RecordingRepository : CategoryRepository {
        val all = listOf(Category(1, "Favorites", 0, 0))
        val forManga = emptyList<Category>()
        val allFlow = flowOf(all)
        val mangaFlow = flowOf(forManga)
        var requestedMangaId: Long? = null

        override suspend fun getAll(): List<Category> = all

        override fun getAllAsFlow(): Flow<List<Category>> = allFlow

        override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
            requestedMangaId = mangaId
            return forManga
        }

        override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
            requestedMangaId = mangaId
            return mangaFlow
        }

        override suspend fun get(id: Long): Category? = error("Unexpected get")

        override suspend fun insert(category: Category): Unit = error("Unexpected insert")

        override suspend fun updateName(categoryId: Long, name: String): Unit = error("Unexpected updateName")

        override suspend fun updateFlags(categoryId: Long, flags: Long): Unit = error("Unexpected updateFlags")

        override suspend fun updateAllFlags(flags: Long?): Unit = error("Unexpected updateAllFlags")

        override suspend fun updateAllOrders(orderedIds: List<Long>): Unit = error("Unexpected updateAllOrders")

        override suspend fun delete(categoryId: Long): Unit = error("Unexpected delete")
    }
}
