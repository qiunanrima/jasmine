package eu.kanade.tachiyomi.data.library

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.manga.model.Manga

@Inject
@SingleIn(AppScope::class)
class LibraryFavoritesSync(
    context: Context,
    private val sourceManager: SourceManager,
    private val getCategories: GetCategories,
    private val createCategoryWithName: CreateCategoryWithName,
    private val setMangaCategories: SetMangaCategories,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateManga: UpdateManga,
    private val getLibraryManga: GetLibraryManga,
) {

    /**
     * 获取或创建指定名称的分类。
     */
    suspend fun getOrCreateCategory(name: String): Category? {
        val cleanName = name.trim().ifBlank { "本地" }
        val existing = getCategories.await().firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        if (existing != null) return existing

        createCategoryWithName.await(cleanName)
        return getCategories.await().firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
    }

    /**
     * 将书架中未归类或仅在默认分类下的漫画，按照其对应的 API/图源名称自动归类。
     */
    suspend fun categorizeUncategorizedManga() = withContext(Dispatchers.IO) {
        val allLibrary = getLibraryManga.await()

        for (item in allLibrary) {
            val manga = item.manga
            if (item.categories.isEmpty() || item.categories.all { it == 0L }) {
                val source = sourceManager.get(manga.source)
                val sourceName = source?.name?.trim()?.ifBlank { "本地" } ?: "本地"
                val category = getOrCreateCategory(sourceName)
                if (category != null) {
                    setMangaCategories.await(manga.id, listOf(category.id))
                }
            }
        }
    }

    private val mutex = Mutex()
    private val pending = context.getSharedPreferences("pending_remote_favorites", Context.MODE_PRIVATE)

    private fun prefix(source: BaseAlignedMangaSource, account: String) = "${source.id}:${account.length}:$account:"

    suspend fun setLibraryFavorite(source: Source?, manga: Manga, desired: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val aligned = (source as? BaseAlignedMangaSource)?.takeIf { it.supportsFavorites }
            val account = aligned?.favoriteAccountId
            if (!updateManga.awaitUpdateFavorite(manga.id, desired)) return@withLock false
            if (aligned != null && account != null) {
                pending.edit().putBoolean(prefix(aligned, account) + manga.url, desired).apply()
            }
            true
        }
    }

    suspend fun syncRemoteFavorites() {
        sync(full = false)
    }

    suspend fun syncAll(sourceId: Long? = null): SyncResult = sync(full = true, sourceId = sourceId)

    private suspend fun sync(full: Boolean, sourceId: Long? = null): SyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            var uploaded = 0
            var downloaded = 0
            val failures = mutableListOf<String>()
            val sources = if (sourceId == null) sourceManager.getOnlineSources() else listOfNotNull(sourceManager.get(sourceId))
            for (source in sources.filterIsInstance<BaseAlignedMangaSource>()) {
                if (!source.supportsFavorites) continue
                val account = source.favoriteAccountId
                if (account == null) {
                    if (full && source.savedAccount != null) failures.add("${source.name}需要登录")
                    continue
                }
                val prefix = prefix(source, account)
                val changes = pending.all.filterKeys { it.startsWith(prefix) }
                var page = 1
                try {
                    val category = getOrCreateCategory(source.name)
                    val remoteIds = mutableSetOf<String>()
                    var complete: Boolean
                    while (true) {
                        check(source.favoriteAccountId == account) { "账号已切换" }
                        val result = source.fetchFavoriteComics(page)
                        check(source.favoriteAccountId == account) { "账号已切换" }
                        val newItems = result.mangas.filter { remoteIds.add(source.extractComicId(it.url)) }
                        check(!result.hasNextPage || newItems.isNotEmpty()) { "服务端重复返回同一页" }
                        for (manga in newItems) {
                            val id = source.extractComicId(manga.url)
                            if (changes.any { (key, desired) -> desired == false && source.extractComicId(key.removePrefix(prefix)) == id }) continue
                            val local = networkToLocalManga(manga.toDomainManga(source.id))
                            if (!local.favorite && updateManga.awaitUpdateFavorite(local.id, true)) {
                                downloaded++
                                if (category != null) setMangaCategories.await(local.id, listOf(category.id))
                            }
                        }
                        complete = !result.hasNextPage
                        if (complete || (!full && page == 5)) break
                        page++
                    }
                    for ((key, value) in changes) {
                        val desired = value as? Boolean ?: continue
                        val url = key.removePrefix(prefix)
                        val exists = source.extractComicId(url) in remoteIds
                        if (!complete && !exists) continue
                        try {
                            check(source.favoriteAccountId == account) { "账号已切换" }
                            if (exists != desired) {
                                val result = if (desired) source.addFavoriteComic(url) else source.removeFavoriteComic(url)
                                result.getOrThrow()
                                check(source.favoriteAccountId == account) { "账号已切换" }
                                if (desired) uploaded++
                            }
                            pending.edit().remove(key).apply()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            failures.add("${source.name}收藏变更失败")
                            logcat(LogPriority.WARN, e) { "Failed to apply favorite change" }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failures.add("${source.name}第 ${page} 页失败")
                    logcat(LogPriority.WARN, e) { "Failed to load remote favorites" }
                }
            }
            categorizeUncategorizedManga()
            SyncResult(uploaded, downloaded, failures.distinct())
        }
    }

    data class SyncResult(val uploaded: Int, val downloaded: Int, val failures: List<String>)
}
