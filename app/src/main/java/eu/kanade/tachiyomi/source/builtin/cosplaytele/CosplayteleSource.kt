package eu.kanade.tachiyomi.source.builtin.cosplaytele

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.ctapi.CTClient
import com.ctapi.CTConfig
import com.ctapi.CTConstants
import com.ctapi.CTPostQuery
import com.ctapi.models.CTCategory
import com.ctapi.models.CTPageList
import com.ctapi.models.CTPost
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.builtin.base.AlignedPageResult
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.Instant

/**
 * CosplayTele 内置图源适配器。
 * Built-in source adapter for CosplayTele.
 */
@Inject
@SingleIn(AppScope::class)
class CosplayteleSource(
    private val context: Context,
) : BaseAlignedMangaSource() {

    override val name: String = "CosplayTele"
    override val lang: String = "all"

    override val baseUrl: String
        get() = getSourcePreferences().getString(PREF_KEY_BASE_URL, CTConstants.DEFAULT_BASE_URL)
            ?: CTConstants.DEFAULT_BASE_URL

    @Volatile
    private var _ctClient: CTClient? = null

    private fun getClient(): CTClient {
        _ctClient?.let { return it }
        synchronized(this) {
            _ctClient?.let { return it }
            val prefs = getSourcePreferences()
            val customBaseUrl = baseUrl
            val config = CTConfig.builder()
                .baseUrl(customBaseUrl)
                .build()
            val client = CTClient(config)
            _ctClient = client
            return client
        }
    }

    override val requiresLogin: Boolean = false
    override val supportsCategories: Boolean = true

    override suspend fun fetchCategories() = withContext(Dispatchers.IO) {
        val categories = getClient().getCategories().getOrThrow()
            .filter { it.name.isNotBlank() && it.slug.isNotBlank() }
            .distinctBy { it.slug }
            .map { it.name to it.slug }
        categoryOptions { CategoryFilter(listOf("全部 (All)" to "") + categories) }
    }
    override val isUserLoggedIn: Boolean = true

    override val client: OkHttpClient
        get() = getClient().rawHttpClient

    override suspend fun fetchPopularComics(page: Int): AlignedPageResult = withContext(Dispatchers.IO) {
        val result = getClient().getPopularPosts(page = page, range = CTConstants.RANGE_LAST_7_DAYS).getOrThrow()
        parsePostList(result)
    }

    override suspend fun fetchLatestComics(page: Int): AlignedPageResult = withContext(Dispatchers.IO) {
        val result = getClient().getLatestPosts(page = page).getOrThrow()
        parsePostList(result)
    }

    override suspend fun fetchSearchComics(page: Int, query: String, filters: FilterList): AlignedPageResult = withContext(Dispatchers.IO) {
        var categorySlug: String? = null
        var popularRange: String? = null

        for (filter in filters) {
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state > 0) {
                        categorySlug = filter.selectedSlug()
                    }
                }
                is PopularRangeFilter -> {
                    if (filter.state > 0) {
                        popularRange = filter.toRangeParam()
                    }
                }
                else -> Unit
            }
        }

        val client = getClient()
        val result = when {
            query.isNotBlank() -> {
                if (!categorySlug.isNullOrBlank()) {
                    client.searchPosts(query = query.trim(), category = categorySlug, page = page).getOrThrow()
                } else {
                    client.searchPosts(query = query.trim(), page = page).getOrThrow()
                }
            }
            !categorySlug.isNullOrBlank() -> {
                client.getPostsByCategory(categorySlug = categorySlug, page = page).getOrThrow()
            }
            !popularRange.isNullOrBlank() -> {
                client.getPopularPosts(page = page, range = popularRange).getOrThrow()
            }
            else -> {
                client.getLatestPosts(page = page).getOrThrow()
            }
        }

        parsePostList(result)
    }

    override suspend fun fetchMangaDetails(comicId: String): SManga = withContext(Dispatchers.IO) {
        val detail = getClient().getPostDetail(comicId).getOrThrow()
        SManga.create().apply {
            this.url = "/${detail.slug.trim('/')}"
            this.title = detail.title
            this.author = detail.cosplayer.takeIf { !it.isNullOrBlank() } ?: detail.author.orEmpty()
            this.artist = detail.appearIn.takeIf { !it.isNullOrBlank() } ?: ""
            this.description = buildString {
                detail.cosplayer?.takeIf { it.isNotBlank() }?.let { append("Coser: ").append(it).append("\n") }
                detail.character?.takeIf { it.isNotBlank() }?.let { append("角色: ").append(it).append("\n") }
                detail.appearIn?.takeIf { it.isNotBlank() }?.let { append("出处: ").append(it).append("\n") }
                detail.photosCount?.let { append("照片数: ").append(it).append("P\n") }
                detail.videosCount?.takeIf { it > 0 }?.let { append("视频数: ").append(it).append("\n") }
                detail.fileSize?.takeIf { it.isNotBlank() }?.let { append("压缩包大小: ").append(it).append("\n") }
                detail.unzipPassword?.takeIf { it.isNotBlank() }?.let { append("解压密码: ").append(it).append("\n") }
                detail.publishedDate?.takeIf { it.isNotBlank() }?.let { append("发布时间: ").append(it).append("\n") }
                if (detail.tags.isNotEmpty()) {
                    append("\n标签: ").append(detail.tags.joinToString(", "))
                }
            }.trim()

            val genres = mutableListOf<String>()
            detail.categories.forEach { genres.add(it.name) }
            genres.addAll(detail.tags)
            this.genre = genres.distinct().joinToString(", ")
            this.status = SManga.COMPLETED
            this.thumbnail_url = detail.coverImageUrl?.takeIf { it.isNotBlank() } ?: detail.thumbnailUrl.orEmpty()
        }
    }

    override suspend fun fetchChapterList(comicId: String): List<SChapter> = withContext(Dispatchers.IO) {
        val detail = getClient().getPostDetail(comicId).getOrThrow()
        val count = detail.photosCount ?: detail.images.size
        val chapter = SChapter.create().apply {
            this.url = "/${detail.slug.trim('/')}"
            this.name = if (count > 0) "全套图集 (${count}P)" else "全套图集"
            this.chapter_number = 1f
            this.date_upload = parseIsoDate(detail.publishedDate)
        }
        listOf(chapter)
    }

    override suspend fun fetchComicPages(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val comicId = extractComicId(chapter.url)
        val detail = getClient().getPostDetail(comicId).getOrThrow()
        detail.images.mapIndexed { index, img ->
            Page(index, url = img.url, imageUrl = img.url)
        }
    }

    override fun extractComicId(url: String): String {
        return url.substringBefore('?').trim('/')
    }

    override fun getFilterList(): FilterList = FilterList(
        PopularRangeFilter(),
        CategoryFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_BASE_URL
            title = "API / 网站基础域名"
            setDefaultValue(CTConstants.DEFAULT_BASE_URL)
            summary = "%s"
            dialogTitle = "输入 CosplayTele 基础域名"
            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue.toString().trim()
                val normalized = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
                getClient().updateConfig(getClient().config.copy(baseUrl = normalized))
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    private fun parsePostList(pageList: CTPageList<CTPost>): AlignedPageResult {
        val mangas = pageList.items.map { post ->
            SManga.create().apply {
                this.url = "/${post.slug.trim('/')}"
                this.title = post.title
                this.thumbnail_url = post.thumbnailUrl.orEmpty()
                this.author = post.cosplayer.takeIf { !it.isNullOrBlank() } ?: "CosplayTele"
                this.genre = post.categories.joinToString(", ") { it.name }
                this.status = SManga.COMPLETED
            }
        }
        return AlignedPageResult(mangas, pageList.hasNextPage)
    }

    private fun parseIsoDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            Instant.parse(dateStr).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    // region Filters --------------------------------------------------------------------------------

    private class CategoryFilter(
        private val categories: List<Pair<String, String>> = CATEGORIES,
    ) : Filter.Select<String>("分类筛选", categories.map { it.first }.toTypedArray()) {
        fun selectedSlug(): String? {
            val idx = state
            return if (idx in categories.indices && categories[idx].second.isNotBlank()) {
                categories[idx].second
            } else {
                null
            }
        }

        companion object {
            private val CATEGORIES = listOf(
                "全部 (All)" to "",
                "Cosplay" to CTConstants.CATEGORY_COSPLAY,
                "Cosplay Nude" to CTConstants.CATEGORY_COSPLAY_NUDE,
                "Cosplay Ero" to CTConstants.CATEGORY_COSPLAY_ERO,
                "Video Cosplay" to CTConstants.CATEGORY_VIDEO_COSPLAY,
                "Best Cosplayer" to CTConstants.CATEGORY_BEST_COSPLAYER,
                "Top Search" to CTConstants.CATEGORY_TOP_SEARCH,
            )
            val CATEGORY_NAMES = CATEGORIES.map { it.first }.toTypedArray()
            val CATEGORY_SLUGS = CATEGORIES.map { it.second }.toTypedArray()
        }
    }

    private class PopularRangeFilter : Filter.Select<String>("热门榜单统计周期 (空搜有效)", RANGE_NAMES) {
        fun toRangeParam(): String? {
            val idx = state
            return if (idx in RANGE_VALUES.indices && RANGE_VALUES[idx].isNotBlank()) {
                RANGE_VALUES[idx]
            } else {
                null
            }
        }

        companion object {
            private val RANGES = listOf(
                "默认 (最新发布)" to "",
                "最近 24 小时热门" to CTConstants.RANGE_LAST_24_HOURS,
                "最近 7 天热门" to CTConstants.RANGE_LAST_7_DAYS,
                "最近 30 天热门" to CTConstants.RANGE_LAST_30_DAYS,
                "历史全站总热门" to CTConstants.RANGE_ALL,
            )
            val RANGE_NAMES = RANGES.map { it.first }.toTypedArray()
            val RANGE_VALUES = RANGES.map { it.second }.toTypedArray()
        }
    }

    companion object {
        private const val PREF_KEY_BASE_URL = "pref_ct_base_url"
    }
}
