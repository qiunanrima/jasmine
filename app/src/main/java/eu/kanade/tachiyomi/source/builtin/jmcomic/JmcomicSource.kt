package eu.kanade.tachiyomi.source.builtin.jmcomic

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.builtin.base.AlignedComment
import eu.kanade.tachiyomi.source.builtin.base.AlignedCommentPage
import eu.kanade.tachiyomi.source.builtin.base.AlignedPageResult
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.github.jukomu.jmcomic.android.support.AndroidImageProcessor
import io.github.jukomu.jmcomic.api.enums.Category
import io.github.jukomu.jmcomic.api.enums.ClientType
import io.github.jukomu.jmcomic.api.enums.OrderBy
import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.api.model.JmSearchPage
import io.github.jukomu.jmcomic.api.model.SearchQuery
import io.github.jukomu.jmcomic.core.client.JmComicClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.crypto.JmImageTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody

@Inject
@SingleIn(AppScope::class)
class JmcomicSource(
    private val context: Context,
) : BaseAlignedMangaSource() {

    override val name: String = "JMComic"
    override val baseUrl: String get() = getSourcePreferences().getString(PREF_KEY_BASE_URL, DEFAULT_DOMAIN)
        ?: DEFAULT_DOMAIN

    private val imageProcessor by lazy { AndroidImageProcessor() }

    @Volatile
    private var _jmClient: JmComicClient? = null

    @Volatile
    private var authenticatedAccount: String? = null

    private fun getClient(): JmComicClient {
        _jmClient?.let { return it }
        synchronized(this) {
            _jmClient?.let { return it }
            val prefs = getSourcePreferences()
            val clientTypeStr = prefs.getString(PREF_KEY_CLIENT_TYPE, "HTML") ?: "HTML"
            val clientType = if (clientTypeStr == "API") ClientType.API else ClientType.HTML

            val builder = JmConfiguration.builder()
                .clientType(clientType)
                .retryTimes(2)
                .domainProbeTimeoutMs(2500)

            val customDomain = prefs.getString(PREF_KEY_BASE_URL, null)
            val domains = if (!customDomain.isNullOrBlank()) {
                val clean = customDomain.removePrefix("https://").removePrefix("http://").trim('/')
                listOf(clean)
            } else {
                listOf("www.cdnaspa.vip", "www.cdnplaystation6.org", "www.cdnaspa.club")
            }
            builder.apiDomains(domains)
            builder.htmlDomains(domains)

            val proxyHost = prefs.getString(PREF_KEY_PROXY_HOST, null)
            val proxyPort = prefs.getString(PREF_KEY_PROXY_PORT, null)?.toIntOrNull() ?: 0
            if (!proxyHost.isNullOrBlank() && proxyPort > 0) {
                builder.proxy(proxyHost.trim(), proxyPort)
            }

            return try {
                val client = JmComicClient.create(builder.build())
                _jmClient = client
                client
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "JMComic 初始化失败（节点探测超时）。请在【图源设置】中配置本地代理（如 127.0.0.1:7890）或切换为【网页端(HTML)】模式。",
                    e,
                )
            }
        }
    }

    override val requiresLogin: Boolean = false

    override val isUserLoggedIn: Boolean
        get() = !savedAccount.isNullOrBlank()

    override val savedAccount: String?
        get() = getSourcePreferences().getString(PREF_KEY_USERNAME, null)

    override suspend fun login(account: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val res = getClient().login(account.trim(), password.trim())
            if (res.isSuccess) {
                authenticatedAccount = account.trim()
                getSourcePreferences().edit()
                    .putString(PREF_KEY_USERNAME, account.trim())
                    .putString(PREF_KEY_PASSWORD, password.trim())
                    .apply()
            } else {
                throw res.exceptionOrNull() ?: IllegalStateException("登录失败，请检查账号密码")
            }
        }
    }

    override fun logout() {
        authenticatedAccount = null
        getSourcePreferences().edit()
            .remove(PREF_KEY_USERNAME)
            .remove(PREF_KEY_PASSWORD)
            .apply()
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val url = request.url.toString()
                if (url.contains("scrambleId=") && response.isSuccessful) {
                    val fragment = url.substringAfter('#', "")
                    val params = fragment.split('&').associate {
                        val parts = it.split('=', limit = 2)
                        parts[0] to parts.getOrElse(1) { "" }
                    }
                    val scrambleIdStr = params["scrambleId"] ?: ""
                    val photoIdStr = params["photoId"] ?: ""
                    val filename = params["filename"] ?: ""
                    val scrambleId = scrambleIdStr.toLongOrNull() ?: 0L
                    val photoId = photoIdStr.toLongOrNull() ?: 0L
                    val filenameWithoutSuffix = filename.substringBeforeLast('.')
                    val numSegments = JmImageTool.calculateNumSegments(scrambleId, photoId, filenameWithoutSuffix)
                    if (numSegments > 0) {
                        val body = response.body
                        val rawBytes = body.bytes()
                        val dummyImage = JmImage(photoIdStr, scrambleIdStr, filename, url.substringBefore('#'), "", 0)
                        val decrypted = imageProcessor.decryptImage(rawBytes, dummyImage)
                        val mediaType = (response.header("Content-Type") ?: "image/jpeg").toMediaType()
                        return@addInterceptor response.newBuilder()
                            .body(decrypted.toResponseBody(mediaType))
                            .build()
                    }
                }
                response
            }
            .build()
    }

    override suspend fun fetchPopularComics(page: Int): AlignedPageResult {
        ensureLoggedIn()
        return fetchChunkedComics("popular", page) { sPage ->
            val query = SearchQuery.builder()
                .orderBy(OrderBy.MOST_LIKED)
                .page(sPage)
                .build()
            getClient().getCategories(query).getOrThrow()
        }
    }

    override suspend fun fetchLatestComics(page: Int): AlignedPageResult {
        ensureLoggedIn()
        return fetchChunkedComics("latest", page) { sPage ->
            getClient().getLatest(sPage).getOrThrow()
        }
    }

    override val supportsFavorites: Boolean get() = isUserLoggedIn

    override suspend fun fetchFavoriteComics(page: Int): AlignedPageResult {
        ensureLoggedIn()
        val favoritePage = getClient().getFavouriteComics(page).getOrThrow()
        val list = favoritePage.content() ?: emptyList()
        val mangas = list.map { albumMeta ->
            SManga.create().apply {
                this.url = "/album/${albumMeta.id()}"
                this.title = albumMeta.title().orEmpty()
                this.author = albumMeta.authors()?.joinToString(", ").orEmpty()
                this.thumbnail_url = albumMeta.image()
            }
        }
        val hasNext = favoritePage.currentPage() < favoritePage.totalPages()
        return AlignedPageResult(mangas, hasNext)
    }

    override suspend fun addFavoriteComic(comicId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanId = extractComicId(comicId)
        runCatching {
            ensureLoggedIn()
            getClient().favouriteComic(cleanId).getOrThrow()
            Unit
        }
    }

    override suspend fun removeFavoriteComic(comicId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanId = extractComicId(comicId)
        runCatching {
            ensureLoggedIn()
            getClient().favouriteComic(cleanId).getOrThrow()
            Unit
        }
    }

    override val supportsComments: Boolean = true
    override val canPostComment: Boolean get() = isUserLoggedIn

    override suspend fun fetchComments(comicId: String, page: Int): AlignedCommentPage = withContext(Dispatchers.IO) {
        val cleanId = extractComicId(comicId)
        val query = io.github.jukomu.jmcomic.api.model.ForumQuery.album(cleanId)
            .mode(io.github.jukomu.jmcomic.api.enums.ForumMode.ALL)
            .page(page)
            .build()
        val commentList = getClient().getComments(query).getOrThrow()
        val list: List<io.github.jukomu.jmcomic.api.model.JmComment> = commentList.list() ?: emptyList()
        val totalComments = commentList.total()

        fun mapJmComment(c: io.github.jukomu.jmcomic.api.model.JmComment): AlignedComment {
            val level = c.expinfoData?.level ?: c.expinfo?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val author = c.nickname.takeIf { !it.isNullOrBlank() }
                ?: c.username.takeIf { !it.isNullOrBlank() }
                ?: "Anonymous"
            val rawReplies = c.replys ?: emptyList()
            val replies = rawReplies.map { reply -> mapJmComment(reply) }

            return AlignedComment(
                id = c.commentId ?: "",
                author = author,
                avatarUrl = c.photo,
                level = level,
                slogan = c.expinfoData?.levelName,
                content = c.content ?: "",
                createdAt = c.postDate,
                likesCount = if (c.likes > 0) c.likes else c.voteUp,
                isLiked = false,
                floor = 0,
                replyCount = replies.size,
                isTop = false,
                replies = replies,
            )
        }

        val mapped = list.map { mapJmComment(it) }
        val pageSize = 20
        val totalPages = if (totalComments > 0) ((totalComments + pageSize - 1) / pageSize) else 1

        AlignedCommentPage(
            comments = mapped,
            currentPage = page,
            totalPages = totalPages.coerceAtLeast(1),
            totalComments = totalComments,
            hasNextPage = page < totalPages,
        )
    }

    override suspend fun postComment(comicId: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureLoggedIn()
            val cleanId = extractComicId(comicId)
            getClient().postComment(cleanId, content).getOrThrow()
            Unit
        }
    }

    override suspend fun replyComment(comicId: String, commentId: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureLoggedIn()
            val cleanId = extractComicId(comicId)
            getClient().replyToComment(cleanId, content, commentId).getOrThrow()
            Unit
        }
    }

    override suspend fun likeComment(commentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureLoggedIn()
            getClient().voteComment(commentId, io.github.jukomu.jmcomic.api.enums.VoteType.UP).getOrThrow()
            Unit
        }
    }

    override suspend fun fetchSearchComics(page: Int, query: String, filters: FilterList): AlignedPageResult {
        ensureLoggedIn()
        var orderBy = OrderBy.LATEST
        var category = Category.ALL

        for (filter in filters) {
            when (filter) {
                is OrderFilter -> orderBy = filter.toOrderBy()
                is CategoryFilter -> category = filter.toCategory()
                else -> Unit
            }
        }

        val cleanQuery = query.trim()
        val cacheKey = "search:$cleanQuery:${orderBy.name}:${category.name}"

        return fetchChunkedComics(cacheKey, page) { sPage ->
            val queryBuilder = SearchQuery.builder()
                .text(cleanQuery)
                .orderBy(orderBy)
                .category(category)
                .page(sPage)

            val builtQuery = queryBuilder.build()
            if (cleanQuery.isBlank()) {
                getClient().getCategories(builtQuery).getOrThrow()
            } else {
                getClient().searchComics(builtQuery).getOrThrow()
            }
        }
    }

    override suspend fun fetchMangaDetails(comicId: String): SManga {
        ensureLoggedIn()
        val album = getClient().getComicDetail(comicId).getOrThrow()

        return SManga.create().apply {
            this.url = "/album/$comicId"
            this.title = album.title().orEmpty()
            this.author = album.authors()?.joinToString(", ").orEmpty()
            this.description = album.description().orEmpty()

            val genres = mutableListOf<String>()
            album.category()?.title()?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
            album.tags()?.let { genres.addAll(it) }
            this.genre = genres.distinct().joinToString(", ")

            this.thumbnail_url = album.image()
            this.status = SManga.UNKNOWN
        }
    }

    /**
     * 获取章节列表。
     * JMComic 细微区别：单行本/单话本子的 photoMetas 可能为空，需自动降级为单话章节。
     */
    override suspend fun fetchChapterList(comicId: String): List<SChapter> {
        ensureLoggedIn()
        val album = getClient().getComicDetail(comicId).getOrThrow()
        val metas = album.photoMetas()

        if (metas.isNullOrEmpty()) {
            return listOf(
                SChapter.create().apply {
                    this.url = "/album/$comicId/photo/$comicId"
                    this.name = "全一话"
                    this.chapter_number = 1f
                    this.date_upload = album.addTime()?.toLongOrNull()?.let { it * 1000L } ?: 0L
                }
            )
        }

        return metas.map { meta ->
            SChapter.create().apply {
                this.url = "/album/$comicId/photo/${meta.id()}"
                this.name = meta.title().takeIf { !it.isNullOrBlank() } ?: "第 ${meta.sortOrder()} 话"
                this.chapter_number = meta.sortOrder().toFloat()
                this.date_upload = album.addTime()?.toLongOrNull()?.let { it * 1000L } ?: 0L
            }
        }.sortedByDescending { it.chapter_number }
    }

    /**
     * 获取单章节的图片列表。
     * JMComic 细微区别：图片存在分块混淆重组算法，需要将 scrambleId/photoId/filename 携带在 URL fragment 中。
     */
    override suspend fun fetchComicPages(chapter: SChapter): List<Page> {
        ensureLoggedIn()
        val photoId = chapter.url.substringAfter("/photo/").substringBefore('?')
        val images = getClient().getComicPages(photoId).getOrThrow()

        return images.mapIndexed { index, img ->
            val pageUrl = img.downloadUrl
            val fragment = "scrambleId=${img.scrambleId()}&photoId=${img.photoId()}&filename=${img.filename()}"
            val fullUrl = "$pageUrl#$fragment"
            Page(index, url = fullUrl, imageUrl = fullUrl)
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        OrderFilter(),
        CategoryFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val clientTypePref = ListPreference(screen.context).apply {
            key = PREF_KEY_CLIENT_TYPE
            title = "客户端模式"
            entries = arrayOf("网页模式 (HTML 推荐，免探测更稳定)", "App 模式 (API 速度更快)")
            entryValues = arrayOf("HTML", "API")
            setDefaultValue("HTML")
            summary = "%s"
            setOnPreferenceChangeListener { _, _ ->
                _jmClient = null
                true
            }
        }
        val accountPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_USERNAME
            title = "用户名 / 账号 (可选)"
            summary = "登录后可同步收藏夹与历史记录"
            dialogTitle = "输入账号"
        }
        val passwordPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_PASSWORD
            title = "密码 (可选)"
            summary = "用于登录 JMComic"
            dialogTitle = "输入密码"
        }
        val domainPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_BASE_URL
            title = "API 主机节点"
            setDefaultValue(DEFAULT_DOMAIN)
            summary = "%s"
            dialogTitle = "输入 API 域名（如 www.cdnaspa.vip）"
            setOnPreferenceChangeListener { _, _ ->
                _jmClient = null
                true
            }
        }
        val proxyHostPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_PROXY_HOST
            title = "HTTP 代理主机 IP"
            summary = "如果直连无法访问，可配置本地代理（如 127.0.0.1）"
            dialogTitle = "输入代理 IP"
            setOnPreferenceChangeListener { _, _ ->
                _jmClient = null
                true
            }
        }
        val proxyPortPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_PROXY_PORT
            title = "HTTP 代理端口"
            summary = "代理端口（如 7890）"
            dialogTitle = "输入代理端口"
            setOnPreferenceChangeListener { _, _ ->
                _jmClient = null
                true
            }
        }

        screen.addPreference(clientTypePref)
        screen.addPreference(accountPref)
        screen.addPreference(passwordPref)
        screen.addPreference(domainPref)
        screen.addPreference(proxyHostPref)
        screen.addPreference(proxyPortPref)
    }

    private data class ServerCache(
        val key: String,
        val serverPage: Int,
        val searchPage: JmSearchPage,
    )

    @Volatile
    private var lastServerCache: ServerCache? = null

    /**
     * 将 JMComic 服务端默认返回的每页 80 条数据虚拟分块为每页 20 条，
     * 显著降低主界面与图源浏览界面初次加载与渲染的负担，同时利用内存缓存避免滑动时重复请求。
     */
    private suspend fun fetchChunkedComics(
        cacheKey: String,
        clientPage: Int,
        fetcher: suspend (serverPage: Int) -> JmSearchPage,
    ): AlignedPageResult {
        val chunksPerPage = ITEMS_PER_SERVER_PAGE / CLIENT_PAGE_SIZE
        val serverPage = (clientPage - 1) / chunksPerPage + 1
        val chunkIndex = (clientPage - 1) % chunksPerPage

        val cached = lastServerCache
        val searchPage = if (clientPage > 1 && cached != null && cached.key == cacheKey && cached.serverPage == serverPage) {
            cached.searchPage
        } else {
            val fetched = fetcher(serverPage)
            lastServerCache = ServerCache(cacheKey, serverPage, fetched)
            fetched
        }

        val allItems = searchPage.content() ?: emptyList()
        val start = chunkIndex * CLIENT_PAGE_SIZE
        if (start >= allItems.size) {
            return AlignedPageResult(emptyList(), false)
        }
        val end = minOf(start + CLIENT_PAGE_SIZE, allItems.size)
        val chunk = allItems.subList(start, end)

        val mangas = chunk.map { albumMeta ->
            SManga.create().apply {
                this.url = "/album/${albumMeta.id()}"
                this.title = albumMeta.title().orEmpty()
                this.author = albumMeta.authors()?.joinToString(", ").orEmpty()
                this.thumbnail_url = albumMeta.image()
            }
        }

        val hasMoreInCurrentServerPage = end < allItems.size
        val hasNextServerPage = searchPage.currentPage() < searchPage.totalPages()
        val hasNext = hasMoreInCurrentServerPage || hasNextServerPage

        return AlignedPageResult(mangas, hasNext)
    }

    private fun parseSearchPage(searchPage: JmSearchPage?): AlignedPageResult {
        if (searchPage == null || searchPage.content().isNullOrEmpty()) {
            return AlignedPageResult(emptyList(), false)
        }
        val mangas = searchPage.content().map { albumMeta ->
            SManga.create().apply {
                this.url = "/album/${albumMeta.id()}"
                this.title = albumMeta.title().orEmpty()
                this.author = albumMeta.authors()?.joinToString(", ").orEmpty()
                this.thumbnail_url = albumMeta.image()
            }
        }
        val hasNext = searchPage.currentPage() < searchPage.totalPages()
        return AlignedPageResult(mangas, hasNext)
    }

    private fun ensureLoggedIn() {
        val username = getSourcePreferences().getString(PREF_KEY_USERNAME, null)
        val password = getSourcePreferences().getString(PREF_KEY_PASSWORD, null)
        if (username.isNullOrBlank() || password.isNullOrBlank()) return

        val account = username.trim()
        if (authenticatedAccount == account) return

        synchronized(this) {
            if (authenticatedAccount == account) return
            runCatching { getClient().login(account, password.trim()) }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        authenticatedAccount = account
                    }
                }
        }
    }

    private class OrderFilter : Filter.Select<String>("排序方式", ORDER_LABELS, 0) {
        fun toOrderBy(): OrderBy = ORDER_VALUES[state]
    }

    private class CategoryFilter : Filter.Select<String>("分类筛选", CATEGORY_LABELS, 0) {
        fun toCategory(): Category = CATEGORY_VALUES[state]
    }

    override val supportsCategories: Boolean = true

    override suspend fun fetchCategories() = withContext(Dispatchers.IO) {
        ensureLoggedIn()
        val categories = getClient().getCategoriesList().getOrThrow().categories()
        val options = categoryOptions { CategoryFilter() }
        categories.mapNotNull { category ->
            val index = CATEGORY_VALUES.indexOfFirst { it.value == category.slug() || it.value == category.id() }
            options.getOrNull(index - 1)?.copy(name = category.name().takeUnless { it.isNullOrBlank() } ?: CATEGORY_LABELS[index])
        }.distinctBy { it.name }
    }

    companion object {
        private const val PREF_KEY_CLIENT_TYPE = "pref_jmcomic_client_type"
        private const val PREF_KEY_USERNAME = "pref_jmcomic_username"
        private const val PREF_KEY_PASSWORD = "pref_jmcomic_password"
        private const val PREF_KEY_BASE_URL = "pref_jmcomic_base_url"
        private const val PREF_KEY_PROXY_HOST = "pref_jmcomic_proxy_host"
        private const val PREF_KEY_PROXY_PORT = "pref_jmcomic_proxy_port"
        private const val DEFAULT_DOMAIN = "www.cdnaspa.vip"
        private const val CLIENT_PAGE_SIZE = 20
        private const val ITEMS_PER_SERVER_PAGE = 80

        private val ORDER_LABELS = arrayOf("最新发布", "最多点击", "最多图片", "最多爱心")
        private val ORDER_VALUES = arrayOf(OrderBy.LATEST, OrderBy.MOST_VIEWED, OrderBy.MOST_IMAGES, OrderBy.MOST_LIKED)

        private val CATEGORY_LABELS = arrayOf("全部", "同人", "单本", "短篇", "其他", "韩漫", "美漫", "Cosplay", "3D")
        private val CATEGORY_VALUES = arrayOf(
            Category.ALL, Category.DOUJIN, Category.SINGLE, Category.SHORT,
            Category.OTHER, Category.KOREAN, Category.AMERICAN, Category.COSPLAY, Category.IMAGE_3D
        )
    }
}
