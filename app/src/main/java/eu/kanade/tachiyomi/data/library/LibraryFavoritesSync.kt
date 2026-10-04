package eu.kanade.tachiyomi.data.library

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import kotlinx.coroutines.Dispatchers
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

@Inject
class LibraryFavoritesSync(
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

    /**
     * 每次打开收藏页时调用：加载各登录账号的远程收藏漫画，加入本地书架，并自动按 API 名分类。
     */
    suspend fun syncRemoteFavorites(): Unit = withContext(Dispatchers.IO) {
        // 1. 先确保本地现有漫画都归入对应 API 分类
        categorizeUncategorizedManga()

        // 2. 遍历已对齐且支持收藏并已登录的图源
        val sources = sourceManager.getOnlineSources()
        for (source in sources) {
            if (source is BaseAlignedMangaSource && source.supportsFavorites && source.isUserLoggedIn) {
                try {
                    val category = getOrCreateCategory(source.name)
                    var page = 1
                    var hasNext = true
                    val maxPages = 5 // 打开书架时默认预加载前 5 页收藏
                    while (hasNext && page <= maxPages) {
                        val result = source.fetchFavoriteComics(page)
                        for (sManga in result.mangas) {
                            try {
                                val domainManga = sManga.toDomainManga(source.id)
                                val localManga = networkToLocalManga(domainManga)
                                // Existing favorites keep their date added and user-assigned categories.
                                if (!localManga.favorite) {
                                    updateManga.awaitUpdateFavorite(localManga.id, true)
                                    if (category != null) {
                                        setMangaCategories.await(localManga.id, listOf(category.id))
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "Failed to sync remote favorite to library: ${sManga.title}" }
                            }
                        }
                        hasNext = result.hasNextPage
                        page++
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to fetch remote favorites for ${source.name}" }
                }
            }
        }
    }

    /**
     * 一键全量双向同步：
     * 1. 将本地书架属于该 API 的漫画上传推送到远程收藏夹；
     * 2. 将远程收藏夹中的漫画下载保存到本地书架并按 API 分类。
     */
    suspend fun syncAll(sourceId: Long? = null): SyncResult = withContext(Dispatchers.IO) {
        var uploadedCount = 0
        var downloadedCount = 0

        val sources = if (sourceId != null) {
            listOfNotNull(sourceManager.get(sourceId))
        } else {
            sourceManager.getOnlineSources()
        }

        val libraryManga = getLibraryManga.await()

        for (source in sources) {
            if (source is BaseAlignedMangaSource && source.supportsFavorites && source.isUserLoggedIn) {
                val category = getOrCreateCategory(source.name)

                // 阶段 A：将本地属于该 API 的漫画推送同步到远程收藏
                val localOfSource = libraryManga.filter { it.manga.source == source.id }
                for (item in localOfSource) {
                    try {
                        val res = source.addFavoriteComic(item.manga.url)
                        if (res.isSuccess) {
                            uploadedCount++
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Failed to push favorite to remote: ${item.manga.title}" }
                    }
                }

                // 阶段 B：从远程收藏全量拉取并同步到本地书架
                var page = 1
                var hasNext = true
                while (hasNext && page <= 20) {
                    try {
                        val result = source.fetchFavoriteComics(page)
                        for (sManga in result.mangas) {
                            try {
                                val domainManga = sManga.toDomainManga(source.id)
                                val localManga = networkToLocalManga(domainManga)
                                if (!localManga.favorite) {
                                    updateManga.awaitUpdateFavorite(localManga.id, true)
                                    downloadedCount++
                                    if (category != null) {
                                        setMangaCategories.await(localManga.id, listOf(category.id))
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "Failed to pull remote favorite: ${sManga.title}" }
                            }
                        }
                        hasNext = result.hasNextPage
                        page++
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Error fetching remote favorites page $page" }
                        break
                    }
                }
            }
        }

        categorizeUncategorizedManga()
        SyncResult(uploaded = uploadedCount, downloaded = downloadedCount)
    }

    data class SyncResult(val uploaded: Int, val downloaded: Int)
}
