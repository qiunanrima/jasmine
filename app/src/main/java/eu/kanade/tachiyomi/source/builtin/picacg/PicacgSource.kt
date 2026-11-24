package eu.kanade.tachiyomi.source.builtin.picacg

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.picaapi.FilePicaTokenStore
import com.picaapi.Pica
import com.picaapi.PicaClient
import com.picaapi.PicaConfig
import com.picaapi.PicaImageQuality
import com.picaapi.PicaResult
import com.picacomic.fregata.objects.requests.SignInBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.picaapi.PicaSort
import com.picaapi.coverUrl
import com.picaapi.toImageUrl
import com.picacomic.fregata.objects.ComicEpisodeObject
import com.picacomic.fregata.objects.ComicListObject
import com.picacomic.fregata.objects.responses.DataClass.ComicListResponse.ComicListData
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
import okhttp3.OkHttpClient
import okhttp3.Request
import android.widget.Toast
import androidx.preference.Preference
import com.picaapi.PicaNetworking
import com.picaapi.PicaSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class PicacgSource(
    private val context: Context,
) : BaseAlignedMangaSource() {

    override val name: String = "PicACG"
    override val baseUrl: String get() = getSourcePreferences().getString(PREF_KEY_BASE_URL, PicaConfig.DEFAULT_BASE_URL)
        ?: PicaConfig.DEFAULT_BASE_URL

    private val tokenStore = FilePicaTokenStore(File(context.filesDir, "picacg_token.txt"))

    private val loginMutex = Mutex()
    private val sessionLock = Any()
    private var sessionVersion = 0L

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        coroutineScope.launch {
            initChannelOnStartup()
        }
    }

    private val picaClient: PicaClient by lazy {
        val prefs = getSourcePreferences()
        val quality = prefs.getString(PREF_KEY_IMAGE_QUALITY, PicaImageQuality.HIGH) ?: PicaImageQuality.HIGH
        val channel = getEffectiveChannel()
        val config = PicaConfig(
            baseUrl = baseUrl,
            imageQuality = quality,
            appChannel = channel,
        )
        Pica.init(config, tokenStore)
        Pica.client
    }

    private fun getEffectiveChannel(): Int {
        val prefs = getSourcePreferences()
        val mode = prefs.getString(PREF_KEY_CHANNEL, CHANNEL_AUTO) ?: CHANNEL_AUTO
        return if (mode != CHANNEL_AUTO) {
            mode.toIntOrNull() ?: 1
        } else {
            prefs.getInt(PREF_KEY_ACTIVE_CHANNEL, 1)
        }
    }

    private fun applyChannel(channel: Int) {
        val current = picaClient.config
        if (current.appChannel != channel) {
            picaClient.updateConfig(current.copy(appChannel = channel))
        }
    }

    private suspend fun initChannelOnStartup() {
        val prefs = getSourcePreferences()
        val mode = prefs.getString(PREF_KEY_CHANNEL, CHANNEL_AUTO) ?: CHANNEL_AUTO
        if (mode != CHANNEL_AUTO) {
            // 用户手动设置的权重大于自动测速，直接使用用户设置的分流
            val manualChannel = mode.toIntOrNull() ?: 1
            applyChannel(manualChannel)
        } else {
            // 自动测速模式：先使用上次生效的分流，随后进行后台测速选取最优分流
            val prev = prefs.getInt(PREF_KEY_ACTIVE_CHANNEL, 1)
            applyChannel(prev)
            runSpeedTest()
        }
    }

    private suspend fun measureChannelLatency(channel: Int): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val testClient = OkHttpClient.Builder()
                .apply { PicaNetworking.applySystemTls(this) }
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .build()

            val time = (System.currentTimeMillis() / 1000).toString()
            val nonce = UUID.randomUUID().toString().replace("-", "")
            val path = "keywords"
            val method = "GET"
            val signature = PicaSignature.sign(
                path = path,
                time = time,
                nonce = nonce,
                method = method,
                apiKey = PicaConfig.DEFAULT_API_KEY,
                hmacKey = PicaConfig.DEFAULT_HMAC_KEY,
            )

            val targetUrl = baseUrl.trimEnd('/') + "/" + path
            val requestBuilder = Request.Builder()
                .url(targetUrl)
                .header("api-key", PicaConfig.DEFAULT_API_KEY)
                .header("accept", "application/vnd.picacomic.com.v1+json")
                .header("app-channel", channel.toString())
                .header("time", time)
                .header("nonce", nonce)
                .header("signature", signature)
                .header("app-version", PicaConfig.DEFAULT_APP_VERSION)
                .header("app-uuid", PicaConfig.DEFAULT_APP_UUID)
                .header("image-quality", "low")
                .header("app-platform", "android")
                .header("app-build-version", PicaConfig.DEFAULT_APP_BUILD_VERSION)
                .header("User-Agent", PicaConfig.DEFAULT_USER_AGENT)

            val token = tokenStore.loadToken()
            if (!token.isNullOrBlank()) {
                requestBuilder.header("authorization", token)
            }

            testClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) System.currentTimeMillis() - start else Long.MAX_VALUE
            }
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    suspend fun runSpeedTest(): List<Pair<Int, Long>> = withContext(Dispatchers.IO) {
        val results = listOf(1, 2, 3).map { ch ->
            async { ch to measureChannelLatency(ch) }
        }.awaitAll()

        val summary = results.joinToString(" | ") { (ch, latency) ->
            val latencyStr = if (latency == Long.MAX_VALUE) "不可用" else "${latency}ms"
            "分流$ch: $latencyStr"
        }

        val prefs = getSourcePreferences()
        prefs.edit()
            .putString(PREF_KEY_CHANNEL_LATENCIES, summary)
            .apply()

        val mode = prefs.getString(PREF_KEY_CHANNEL, CHANNEL_AUTO) ?: CHANNEL_AUTO
        if (mode == CHANNEL_AUTO) {
            val valid = results.filter { it.second < Long.MAX_VALUE }
            val best = valid.minByOrNull { it.second }
            if (best != null) {
                prefs.edit().putInt(PREF_KEY_ACTIVE_CHANNEL, best.first).apply()
                applyChannel(best.first)
            }
        } else {
            // 用户手动设置的权重大于自动测速
            val manualChannel = mode.toIntOrNull() ?: 1
            applyChannel(manualChannel)
        }

        results
    }

    private fun getChannelSummaryText(mode: String): String {
        return if (mode == CHANNEL_AUTO) {
            val active = getSourcePreferences().getInt(PREF_KEY_ACTIVE_CHANNEL, 1)
            "自动测速选取（当前生效: 分流 $active）"
        } else {
            "手动设置: 分流 $mode（用户设置优先于自动测速）"
        }
    }

    override val requiresLogin: Boolean = true

    override val supportsCategories: Boolean = true

    override suspend fun fetchCategories() = withContext(Dispatchers.IO) {
        val names = authenticated { getCategories() }.getOrThrow().categories.orEmpty()
            .filter { !it.isWeb }
            .mapNotNull { it.title?.takeIf(String::isNotBlank) }
            .distinct()
        categoryOptions { CategoryFilter((listOf("全部") + names).toTypedArray()) }
    }

    override val isUserLoggedIn: Boolean
        get() = !tokenStore.loadToken().isNullOrBlank() || !picaClient.authorization.isNullOrBlank()

    override val savedAccount: String?
        get() = getSourcePreferences().getString(PREF_KEY_EMAIL, null)

    override suspend fun login(account: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val version = synchronized(sessionLock) { ++sessionVersion }
            loginMutex.withLock {
                val token = picaClient.signIn(SignInBody(account.trim(), password.trim())).getOrThrow().token
                synchronized(sessionLock) {
                    check(version == sessionVersion) { "PicACG 会话已变更" }
                    setToken(token)
                    getSourcePreferences().edit()
                        .putString(PREF_KEY_EMAIL, account.trim())
                        .putString(PREF_KEY_PASSWORD, password.trim())
                        .apply()
                }
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    override fun logout() {
        synchronized(sessionLock) {
            sessionVersion++
            setToken(null)
            getSourcePreferences().edit().remove(PREF_KEY_PASSWORD).apply()
        }
    }

    private fun setToken(token: String?) {
        tokenStore.saveToken(token)
        picaClient.updateAuthorization(token)
    }

    override val client: OkHttpClient get() = picaClient.rawHttpClient

    override suspend fun fetchPopularComics(page: Int): AlignedPageResult {
        val res = authenticated { getComics(page = page, sort = PicaSort.MOST_LIKES) }.getOrThrow()
        return parseComicListData(res.comics)
    }

    override suspend fun fetchLatestComics(page: Int): AlignedPageResult {
        val res = authenticated { getComics(page = page, sort = PicaSort.NEWEST) }.getOrThrow()
        return parseComicListData(res.comics)
    }

    override val supportsFavorites: Boolean get() = true

    override suspend fun fetchFavoriteComics(page: Int): AlignedPageResult {
        return fetchFavoriteComics(page, PicaSort.NEWEST)
    }

    suspend fun fetchFavoriteComics(page: Int, sort: String): AlignedPageResult {
        val res = authenticated { getFavouriteComics(sort = sort, page = page) }.getOrThrow()
        return parseComicListData(res.comics)
    }

    private val favoriteMutex = Mutex()

    override suspend fun addFavoriteComic(comicId: String) = setFavorite(comicId, true)

    override suspend fun removeFavoriteComic(comicId: String) = setFavorite(comicId, false)

    private suspend fun setFavorite(comicId: String, desired: Boolean): Result<Unit> = favoriteMutex.withLock {
        runCatching {
            val cleanId = extractComicId(comicId)
            val version = synchronized(sessionLock) { sessionVersion }
            val comic = authenticated(version) { getComicDetail(cleanId) }.getOrThrow().comic
                ?: error("无法查询 PicACG 收藏状态")
            if (comic.isFavourite != desired) {
                val action = authenticated(version) { favouriteComic(cleanId) }.getOrThrow().action
                check(action == if (desired) "favourite" else "un_favourite") {
                    "PicACG 收藏状态已变化，请重试"
                }
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    override val supportsComments: Boolean = true
    override val canPostComment: Boolean get() = isUserLoggedIn

    override suspend fun fetchComments(comicId: String, page: Int): AlignedCommentPage = withContext(Dispatchers.IO) {
        val cleanId = extractComicId(comicId)
        val res = authenticated { getComicComments(cleanId, page) }.getOrThrow()
        val commentsData = res.comments
        val rawList = commentsData?.docs.orEmpty()
        val topList = if (page == 1) res.topComments.orEmpty() else emptyList()
        val imageServer = picaClient.config.imageServer

        fun mapComment(c: com.picacomic.fregata.objects.CommentObject, isTop: Boolean = false): AlignedComment {
            val user = c.user
            val avatarUrl = user?.avatar?.toImageUrl(imageServer)
            return AlignedComment(
                id = c.commentId.orEmpty(),
                author = user?.name.orEmpty().ifBlank { "Anonymous" },
                avatarUrl = avatarUrl,
                level = user?.level ?: 0,
                slogan = user?.slogan,
                content = c.content.orEmpty(),
                createdAt = c.createdAt,
                likesCount = c.likesCount,
                isLiked = c.isLiked,
                floor = 0,
                replyCount = c.childsCount,
                isTop = isTop || c.isTop,
            )
        }

        val topMapped = topList.map { mapComment(it, isTop = true) }
        val normalMapped = rawList.map { mapComment(it) }

        val totalPages = commentsData?.pages ?: 1
        val totalComments = commentsData?.total ?: (topMapped.size + normalMapped.size)
        val currentPage = commentsData?.page ?: page

        AlignedCommentPage(
            comments = topMapped + normalMapped,
            currentPage = currentPage,
            totalPages = totalPages,
            totalComments = totalComments,
            hasNextPage = currentPage < totalPages,
        )
    }

    override suspend fun postComment(comicId: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanId = extractComicId(comicId)
            val res = authenticated { postComicComment(cleanId, content) }
            if (res is com.picaapi.PicaResult.Failure) {
                throw res.toException()
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun replyComment(commentId: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val res = authenticated { replyComment(commentId, content) }
            if (res is com.picaapi.PicaResult.Failure) {
                throw res.toException()
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun fetchCommentReplies(commentId: String, page: Int): AlignedCommentPage = withContext(Dispatchers.IO) {
        val res = authenticated { getCommentChildren(commentId, page) }.getOrThrow()
        val commentsData = res.comments
        val rawList = commentsData?.docs.orEmpty()
        val imageServer = picaClient.config.imageServer

        fun mapReply(c: com.picacomic.fregata.objects.CommentObject): AlignedComment {
            val user = c.user
            val avatarUrl = user?.avatar?.toImageUrl(imageServer)
            return AlignedComment(
                id = c.commentId.orEmpty(),
                author = user?.name.orEmpty().ifBlank { "Anonymous" },
                avatarUrl = avatarUrl,
                level = user?.level ?: 0,
                slogan = user?.slogan,
                content = c.content.orEmpty(),
                createdAt = c.createdAt,
                likesCount = c.likesCount,
                isLiked = c.isLiked,
                floor = 0,
                replyCount = c.childsCount,
                isTop = c.isTop,
            )
        }

        val mapped = rawList.map { mapReply(it) }
        val totalPages = commentsData?.pages ?: 1
        val totalComments = commentsData?.total ?: mapped.size
        val currentPage = commentsData?.page ?: page

        AlignedCommentPage(
            comments = mapped,
            currentPage = currentPage,
            totalPages = totalPages,
            totalComments = totalComments,
            hasNextPage = currentPage < totalPages,
        )
    }

    override suspend fun likeComment(commentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val res = authenticated { likeComment(commentId) }
            if (res is com.picaapi.PicaResult.Failure) {
                throw res.toException()
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun fetchSearchComics(page: Int, query: String, filters: FilterList): AlignedPageResult {
        var category: String? = null
        var sort = PicaSort.NEWEST

        for (filter in filters) {
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state > 0) {
                        category = filter.values[filter.state]
                    }
                }
                is SortFilter -> {
                    sort = filter.toSortParam()
                }
                else -> Unit
            }
        }

        val res = if (query.isNotBlank()) {
            authenticated { searchComics(keyword = query.trim(), sort = sort, categories = category?.let { listOf(it) }, page = page) }.getOrThrow()
        } else {
            authenticated { getComics(page = page, category = category, sort = sort) }.getOrThrow()
        }
        return parseComicListData(res.comics)
    }

    override suspend fun fetchMangaDetails(comicId: String): SManga {
        val detail = authenticated { getComicDetail(comicId) }.getOrThrow().comic
            ?: throw IllegalStateException("未获取到漫画详情: $comicId")

        return SManga.create().apply {
            this.url = "/comics/$comicId"
            this.title = detail.title.orEmpty()
            this.author = detail.author.orEmpty()
            this.artist = detail.chineseTeam.takeIf { !it.isNullOrBlank() } ?: detail.creator?.name.orEmpty()
            this.description = detail.description.orEmpty()

            val genres = mutableListOf<String>()
            detail.categories?.let { genres.addAll(it) }
            detail.tags?.let { genres.addAll(it) }
            this.genre = genres.distinct().joinToString(", ")

            this.status = if (detail.isFinished) SManga.COMPLETED else SManga.ONGOING
            this.thumbnail_url = detail.thumb?.toImageUrl(picaClient.config.imageServer).orEmpty()
        }
    }

    /**
     * 获取所有章节。由于 PicACG 接口章节做了分页 (20 条/页)，此处遍历所有分页合并。
     */
    override suspend fun fetchChapterList(comicId: String): List<SChapter> {
        val allEpisodes = mutableListOf<ComicEpisodeObject>()
        var currentPage = 1
        while (true) {
            val response = authenticated { getComicEpisodes(comicId, page = currentPage) }.getOrThrow()
            val epsData = response.eps ?: break
            val docs = epsData.docs ?: emptyList()
            allEpisodes.addAll(docs)
            if (currentPage >= epsData.pages || docs.isEmpty()) {
                break
            }
            currentPage++
        }

        return allEpisodes.map { ep ->
            SChapter.create().apply {
                this.url = "/comics/$comicId/order/${ep.order}"
                this.name = ep.title.takeIf { !it.isNullOrBlank() } ?: "第 ${ep.order} 话"
                this.chapter_number = ep.order.toFloat()
                this.date_upload = parseIsoDate(ep.updatedAt)
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun fetchComicPages(chapter: SChapter): List<Page> {
        // 章节 URL 形如 /comics/{comicId}/order/{order}?epId={epId}。
        // 不能用 extractComicId()（它对章节 URL 会错误地取到末尾的 order，
        // 导致服务端返回 400/1022 "invalid id"）。
        val comicId = chapter.url.substringAfter("/comics/").substringBefore("/order/")
        val order = chapter.url.substringAfter("/order/").substringBefore('?').toIntOrNull() ?: 1
        val pages = authenticated { getAllComicPagesByOrder(comicId, order) }.getOrThrow()

        return pages.mapIndexed { index, pageObj ->
            val imgUrl = pageObj.toImageUrl(picaClient.config.imageServer).orEmpty()
            Page(index, url = imgUrl, imageUrl = imgUrl)
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        CategoryFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val accountPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_EMAIL
            title = "账号 / 邮箱"
            summary = "用于登录 PicACG"
            dialogTitle = "输入账号或邮箱"
        }
        val passwordPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_PASSWORD
            title = "密码"
            summary = "用于登录 PicACG"
            dialogTitle = "输入密码"
        }
        val channelPref = ListPreference(screen.context).apply {
            key = PREF_KEY_CHANNEL
            title = "分流选项"
            entries = CHANNEL_ENTRIES
            entryValues = CHANNEL_VALUES
            setDefaultValue(CHANNEL_AUTO)
            val currentMode = getSourcePreferences().getString(PREF_KEY_CHANNEL, CHANNEL_AUTO) ?: CHANNEL_AUTO
            summary = getChannelSummaryText(currentMode)
            dialogTitle = "选择分流"
        }

        val speedTestPref = Preference(screen.context).apply {
            key = "pref_picacg_speed_test_action"
            title = "分流测速"
            val latencies = getSourcePreferences().getString(PREF_KEY_CHANNEL_LATENCIES, null)
            summary = if (!latencies.isNullOrBlank()) "$latencies\n点击重新测速" else "点击测试各分流延迟并显示结果"
        }

        channelPref.setOnPreferenceChangeListener { _, newValue ->
            val newMode = newValue.toString()
            channelPref.summary = getChannelSummaryText(newMode)
            if (newMode == CHANNEL_AUTO) {
                coroutineScope.launch {
                    val results = runSpeedTest()
                    withContext(Dispatchers.Main) {
                        channelPref.summary = getChannelSummaryText(CHANNEL_AUTO)
                        val summaryText = results.joinToString(" | ") { (ch, latency) ->
                            val latencyStr = if (latency == Long.MAX_VALUE) "不可用" else "${latency}ms"
                            "分流$ch: $latencyStr"
                        }
                        speedTestPref.summary = "$summaryText\n点击重新测速"
                    }
                }
            } else {
                val ch = newMode.toIntOrNull() ?: 1
                applyChannel(ch)
            }
            true
        }

        speedTestPref.setOnPreferenceClickListener {
            Toast.makeText(screen.context, "正在测试各分流延迟...", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val results = runSpeedTest()
                withContext(Dispatchers.Main) {
                    val currentMode = getSourcePreferences().getString(PREF_KEY_CHANNEL, CHANNEL_AUTO) ?: CHANNEL_AUTO
                    channelPref.summary = getChannelSummaryText(currentMode)
                    val summaryText = results.joinToString(" | ") { (ch, latency) ->
                        val latencyStr = if (latency == Long.MAX_VALUE) "不可用" else "${latency}ms"
                        "分流$ch: $latencyStr"
                    }
                    speedTestPref.summary = "$summaryText\n点击重新测速"
                    val activeCh = getEffectiveChannel()
                    val msg = if (results.none { it.second < Long.MAX_VALUE }) {
                        "测速失败，保留当前分流。\n$summaryText"
                    } else if (currentMode == CHANNEL_AUTO) {
                        "测速完成！已自动选取最优分流 $activeCh\n$summaryText"
                    } else {
                        "测速完成！当前保持手动分流 $activeCh（用户设置优先）\n$summaryText"
                    }
                    Toast.makeText(screen.context, msg, Toast.LENGTH_LONG).show()
                }
            }
            true
        }

        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_KEY_IMAGE_QUALITY
            title = "图片画质"
            entries = arrayOf("低画质 (low)", "中等画质 (medium)", "高画质 (high)", "原图 (original)")
            entryValues = arrayOf(PicaImageQuality.LOW, PicaImageQuality.MEDIUM, PicaImageQuality.HIGH, PicaImageQuality.ORIGINAL)
            setDefaultValue(PicaImageQuality.HIGH)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                picaClient.updateConfig(picaClient.config.copy(imageQuality = newValue.toString()))
                true
            }
        }
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_BASE_URL
            title = "API 服务器地址"
            setDefaultValue(PicaConfig.DEFAULT_BASE_URL)
            summary = "%s"
            dialogTitle = "输入 API 根地址"
            setOnPreferenceChangeListener { _, newValue ->
                picaClient.updateConfig(picaClient.config.copy(baseUrl = newValue.toString()))
                true
            }
        }

        screen.addPreference(accountPref)
        screen.addPreference(passwordPref)
        screen.addPreference(channelPref)
        screen.addPreference(speedTestPref)
        screen.addPreference(qualityPref)
        screen.addPreference(baseUrlPref)
    }

    private fun parseComicListData(comicsData: ComicListData?): AlignedPageResult {
        if (comicsData == null || comicsData.docs.isNullOrEmpty()) {
            return AlignedPageResult(emptyList(), false)
        }
        val mangas = comicsData.docs.map { comic ->
            SManga.create().apply {
                this.url = "/comics/${comic.comicId}"
                this.title = comic.title.orEmpty()
                this.author = comic.author.orEmpty()
                this.thumbnail_url = comic.thumb?.toImageUrl(picaClient.config.imageServer).orEmpty()
                this.genre = comic.categories?.joinToString(", ")
                this.status = if (comic.isFinished) SManga.COMPLETED else SManga.ONGOING
                val metaParts = mutableListOf<String>()
                if (comic.likesCount > 0) metaParts.add("${comic.likesCount} 爱心")
                if (comic.pagesCount > 0) metaParts.add("${comic.pagesCount}P")
                if (metaParts.isNotEmpty()) {
                    this.description = metaParts.joinToString(" · ")
                }
            }
        }
        val hasNext = comicsData.page < comicsData.pages
        return AlignedPageResult(mangas, hasNext)
    }

    private fun PicaResult.Failure.isSessionExpired() = httpCode == 401 || errorCode == "1005"

    private suspend fun <T> authenticated(
        version: Long = synchronized(sessionLock) { sessionVersion },
        call: PicaClient.() -> PicaResult<T>,
    ): PicaResult<T> =
        withContext(Dispatchers.IO) {
            suspend fun recover(rejectedToken: String?): String = loginMutex.withLock {
                val credentials = synchronized(sessionLock) {
                    check(version == sessionVersion) { "PicACG 会话已变更" }
                    val active = picaClient.authorization
                    if (!active.isNullOrBlank() && active != rejectedToken) return@withLock active
                    val prefs = getSourcePreferences()
                    val email = prefs.getString(PREF_KEY_EMAIL, null)
                    val password = prefs.getString(PREF_KEY_PASSWORD, null)
                    if (email.isNullOrBlank() || password.isNullOrBlank()) {
                        setToken(null)
                        error("请重新登录 PicACG")
                    }
                    email to password
                }
                val result = picaClient.signIn(SignInBody(credentials.first, credentials.second))
                synchronized(sessionLock) {
                    check(version == sessionVersion) { "PicACG 会话已变更" }
                    if (result is PicaResult.Failure && (result.httpCode == 401 || result.httpCode == 403)) {
                        setToken(null)
                        getSourcePreferences().edit().remove(PREF_KEY_PASSWORD).apply()
                        throw IllegalStateException("请重新登录 PicACG", result.toException())
                    }
                    result.getOrThrow().token.also { setToken(it) }
                }
            }

            val token = picaClient.authorization?.takeIf { it.isNotBlank() } ?: recover(null)
            val result = loginMutex.withLock {
                synchronized(sessionLock) { check(version == sessionVersion) { "PicACG 会话已变更" } }
                picaClient.call()
            }
            synchronized(sessionLock) { check(version == sessionVersion) { "PicACG 会话已变更" } }
            if (result !is PicaResult.Failure || !result.isSessionExpired()) return@withContext result
            val renewed = recover(token)
            val retry = loginMutex.withLock {
                synchronized(sessionLock) { check(version == sessionVersion) { "PicACG 会话已变更" } }
                picaClient.call()
            }
            synchronized(sessionLock) {
                check(version == sessionVersion) { "PicACG 会话已变更" }
                if (retry is PicaResult.Failure && retry.isSessionExpired() && picaClient.authorization == renewed) {
                    setToken(null)
                    getSourcePreferences().edit().remove(PREF_KEY_PASSWORD).apply()
                    error("请重新登录 PicACG")
                }
            }
            retry
        }

    private fun parseIsoDate(isoString: String?): Long {
        if (isoString.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(isoString).toEpochMilli() }.getOrDefault(0L)
    }

    private class SortFilter : Filter.Select<String>("排序方式", SORT_LABELS, 0) {
        fun toSortParam(): String = SORT_VALUES[state]
    }

    private class CategoryFilter(values: Array<String> = CATEGORIES) : Filter.Select<String>("分类过滤", values, 0)

    companion object {
        private const val PREF_KEY_EMAIL = "pref_picacg_email"
        private const val PREF_KEY_PASSWORD = "pref_picacg_password"
        private const val PREF_KEY_IMAGE_QUALITY = "pref_picacg_quality"
        private const val PREF_KEY_BASE_URL = "pref_picacg_base_url"
        private const val PREF_KEY_CHANNEL = "pref_picacg_channel"
        private const val PREF_KEY_ACTIVE_CHANNEL = "pref_picacg_active_channel"
        private const val PREF_KEY_CHANNEL_LATENCIES = "pref_picacg_channel_latencies"

        private const val CHANNEL_AUTO = "auto"
        private val CHANNEL_ENTRIES = arrayOf("自动测速选取 (默认)", "分流 1", "分流 2", "分流 3")
        private val CHANNEL_VALUES = arrayOf(CHANNEL_AUTO, "1", "2", "3")

        private val SORT_LABELS = arrayOf("最新发布", "最早发布", "最多爱心", "最多观看")
        private val SORT_VALUES = arrayOf(PicaSort.NEWEST, PicaSort.OLDEST, PicaSort.MOST_LIKES, PicaSort.MOST_VIEWS)

        private val CATEGORIES = arrayOf(
            "全部", "大家都在看", "那年今天", "官方Web", "嗶咔漢化", "全彩", "長篇", "短篇",
            "同人志", "單行本", "生肉", "純愛", "百合", "耽美", "偽娘", "後宮",
            "治癒", "美食", "Cosplay", "扶他乐园", "CG雜圖", "英語 ENG", "重口"
        )
    }
}
