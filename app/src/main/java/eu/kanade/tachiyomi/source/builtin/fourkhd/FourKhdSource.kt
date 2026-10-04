package eu.kanade.tachiyomi.source.builtin.fourkhd

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import com.fourkhd.FourKhdClient
import com.fourkhd.FourKhdConfig
import com.fourkhd.FourKhdConstants
import com.fourkhd.FourKhdPost
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.builtin.base.AlignedPageResult
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Built-in source adapter for 4KHD's WordPress REST API. */
@Inject
@SingleIn(AppScope::class)
class FourKhdSource(
    private val context: Context,
) : BaseAlignedMangaSource() {

    override val name: String = "4KHD"
    override val lang: String = "all"

    override val baseUrl: String
        get() = getSourcePreferences().getString(PREF_KEY_BASE_URL, FourKhdConstants.DEFAULT_BASE_URL)
            ?: FourKhdConstants.DEFAULT_BASE_URL

    @Volatile
    private var clientInstance: FourKhdClient? = null

    private fun getClient(): FourKhdClient {
        clientInstance?.let { return it }
        synchronized(this) {
            clientInstance?.let { return it }
            val client = FourKhdClient(
                FourKhdConfig(baseUrl = baseUrl),
                network.client,
            )
            clientInstance = client
            return client
        }
    }

    override val client: OkHttpClient
        get() = getClient().rawHttpClient

    override suspend fun fetchPopularComics(page: Int): AlignedPageResult = withContext(Dispatchers.IO) {
        parsePage(getClient().getPosts(page = page, orderBy = "modified").getOrThrow())
    }

    override suspend fun fetchLatestComics(page: Int): AlignedPageResult = withContext(Dispatchers.IO) {
        parsePage(getClient().getPosts(page = page, orderBy = "date").getOrThrow())
    }

    override suspend fun fetchSearchComics(page: Int, query: String, filters: FilterList): AlignedPageResult = withContext(Dispatchers.IO) {
        parsePage(getClient().getPosts(page = page, orderBy = "date", search = query.takeIf { it.isNotBlank() }).getOrThrow())
    }

    override suspend fun fetchMangaDetails(comicId: String): SManga = withContext(Dispatchers.IO) {
        val post = getClient().getPostBySlug(comicId).getOrThrow()
            ?: throw IllegalStateException("4KHD post not found: $comicId")
        post.toManga()
    }

    override suspend fun fetchChapterList(comicId: String): List<SChapter> = withContext(Dispatchers.IO) {
        val post = getClient().getPostBySlug(comicId).getOrThrow()
            ?: throw IllegalStateException("4KHD post not found: $comicId")
        listOf(
            SChapter.create().apply {
                url = postPath(post)
                name = "Gallery"
                chapter_number = 1f
                date_upload = parseDate(post.date)
            },
        )
    }

    override suspend fun fetchComicPages(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val slug = extractComicId(chapter.url)
        getClient().getPostImages(slug).getOrThrow().mapIndexed { index, imageUrl ->
            Page(index, url = imageUrl, imageUrl = imageUrl)
        }
    }

    override fun extractComicId(url: String): String = url.substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')
        .substringBefore(".html")

    override fun getFilterList(): FilterList = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPreference = EditTextPreference(screen.context).apply {
            key = PREF_KEY_BASE_URL
            title = "站点 / API 基础地址"
            dialogTitle = "输入 4KHD 站点地址"
            setDefaultValue(FourKhdConstants.DEFAULT_BASE_URL)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val value = newValue.toString().trim().trimEnd('/')
                if (value.isBlank()) return@setOnPreferenceChangeListener false
                clientInstance = null
                true
            }
        }
        screen.addPreference(baseUrlPreference)
    }

    private fun parsePage(page: com.fourkhd.FourKhdPage<FourKhdPost>): AlignedPageResult =
        AlignedPageResult(page.items.map { it.toManga() }, page.hasNextPage)

    private fun FourKhdPost.toManga(): SManga = SManga.create().apply {
        title = this@toManga.title
        thumbnail_url = thumbnailUrl.orEmpty()
        description = Jsoup.parse(contentHtml).text().takeIf { it.isNotBlank() }
        genre = categories.joinToString(", ").takeIf { it.isNotBlank() }
        status = SManga.COMPLETED
        url = postPath(this@toManga)
    }

    private fun postPath(post: FourKhdPost): String = runCatching {
        val path = post.link.toHttpUrl().encodedPath
        if (path.startsWith("/content/", ignoreCase = true)) path else "/content/${post.slug}.html"
    }.getOrDefault("/content/${post.slug}.html")

    private fun parseDate(value: String): Long = runCatching {
        Instant.parse(value).toEpochMilli()
    }.recoverCatching {
        LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        private const val PREF_KEY_BASE_URL = "pref_fourkhd_base_url"
    }
}
