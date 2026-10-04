package eu.kanade.tachiyomi.source.builtin.ehentai

import android.content.Context
import android.webkit.CookieManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.ehapi.EhClient
import com.ehapi.EhConfig
import com.ehapi.EhConstants
import com.ehapi.EhQuery
import com.ehapi.objects.EhSearchResponse
import com.ehapi.parser.EhParser
import com.ehapi.toProxyUrl
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.builtin.base.AlignedComment
import eu.kanade.tachiyomi.source.builtin.base.AlignedCommentPage
import eu.kanade.tachiyomi.source.builtin.base.AlignedPageResult
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/**
 * E-Hentai 内置图源适配器。
 * Built-in source adapter for E-Hentai.
 */
@Inject
@SingleIn(AppScope::class)
class EhentaiSource(
    private val context: Context,
) : BaseAlignedMangaSource() {

    override val name: String = "E-Hentai"
    override val lang: String = "all"
    private val authenticationVersion = MutableStateFlow(0L)
    val authenticationChanges = authenticationVersion.asStateFlow()

    override val baseUrl: String
        get() {
            val saved = getSourcePreferences().getString(PREF_KEY_BASE_URL, null)?.trim()
            if (saved.isNullOrBlank() || saved == "http://127.0.0.1:8000/" || saved == "http://127.0.0.1:8000") {
                return EhConstants.DEFAULT_BASE_URL
            }
            return EhConfig(baseUrl = saved).normalizedBaseUrl()
        }

    @Volatile
    private var _ehClient: EhClient? = null

    private fun getClient(): EhClient {
        _ehClient?.let { return it }
        synchronized(this) {
            _ehClient?.let { return it }
            val prefs = getSourcePreferences()
            val cookie = prefs.getString(PREF_KEY_COOKIE, null)
            val proxyHost = prefs.getString(PREF_KEY_PROXY_HOST, null)?.trim()?.takeIf { it.isNotEmpty() }
            val proxyPort = prefs.getString(PREF_KEY_PROXY_PORT, null)?.toIntOrNull()
            val quality = prefs.getString(PREF_KEY_QUALITY, "75")?.toIntOrNull() ?: 75
            val width = prefs.getString(PREF_KEY_WIDTH, "1280")?.toIntOrNull() ?: 1280

            val config = EhConfig.builder()
                .baseUrl(baseUrl)
                .cookie(cookie)
                .proxy(proxyHost, proxyPort)
                .defaultImageQuality(quality)
                .defaultImageWidth(width)
                .build()

            // 继承 Mihon 应用层 network.client，享有全局 DoH (DNS-over-HTTPS)、全局代理与连接池
            val baseOkHttpClient = try {
                network.client
            } catch (_: Throwable) {
                null
            }

            val client = EhClient(config, baseOkHttpClient)
            _ehClient = client
            return client
        }
    }

    override fun headersBuilder(): okhttp3.Headers.Builder {
        val isEx = baseUrl.contains("exhentai")
        val referer = if (isEx) "https://exhentai.org/" else "https://e-hentai.org/"
        val builder = okhttp3.Headers.Builder()
            .add("User-Agent", EhConstants.DEFAULT_USER_AGENT)
            .add("Referer", referer)
            .add("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7,ja;q=0.6")
        return builder
    }

    override val requiresLogin: Boolean = false

    override val isUserLoggedIn: Boolean
        get() = hasAuthCookies(getSourcePreferences().getString(PREF_KEY_COOKIE, null).orEmpty())

    override val savedAccount: String?
        get() = if (isUserLoggedIn) "已登录 E-Hentai" else null

    override suspend fun login(account: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cookie = if (account.contains("ipb_member_id") || account.contains("=")) {
                account.trim()
            } else if (password.contains("ipb_member_id") || password.contains("=")) {
                password.trim()
            } else {
                "ipb_member_id=${account.trim()}; ipb_pass_hash=${password.trim()};"
            }
            validateAndSaveCookie(cookie)
            Unit
        }
    }

    override fun logout() {
        getSourcePreferences().edit()
            .remove(PREF_KEY_COOKIE)
            .apply()
        _ehClient?.updateCookie(null)
        val manager = CookieManager.getInstance()
        for (url in listOf("https://forums.e-hentai.org", "https://e-hentai.org", "https://exhentai.org")) {
            val existingCookies = manager.getCookie(url).orEmpty()
            network.cookieJar.remove(url.toHttpUrl())
            existingCookies.split(';').forEach {
                val name = it.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    manager.setCookie(url, "$name=; Max-Age=0; Path=/")
                    val domain = if (url.contains("exhentai.org")) ".exhentai.org" else ".e-hentai.org"
                    manager.setCookie(url, "$name=; Max-Age=0; Domain=$domain; Path=/")
                }
            }
        }
        manager.flush()
        authenticationVersion.update { it + 1 }
    }

    private fun hasAuthCookies(cookie: String): Boolean {
        val values = cookie.split(';').associate { it.trim().substringBefore('=') to it.trim().substringAfter('=', "") }
        return values["ipb_member_id"]?.toLongOrNull()?.let { it > 0 } == true && !values["ipb_pass_hash"].isNullOrBlank()
    }

    private fun validateAndSaveCookie(cookie: String) {
        require(hasAuthCookies(cookie)) { "Cookie 缺少有效的 ipb_member_id 或 ipb_pass_hash" }
        val validationClient = network.client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        validationClient.newCall(GET("https://forums.e-hentai.org/", headersBuilder().set("Cookie", cookie).build())).execute().use {
            check(it.isSuccessful && Jsoup.parse(it.body.string()).selectFirst("#userlinks p.home b a") != null) {
                "Cookie 验证失败，请重新登录"
            }
        }
        getSourcePreferences().edit().putString(PREF_KEY_COOKIE, cookie).apply()
        _ehClient?.updateCookie(cookie)
        val manager = CookieManager.getInstance()
        cookie.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name in listOf("ipb_member_id", "ipb_pass_hash", "igneous", "star")) {
                for (domain in listOf(".e-hentai.org", ".exhentai.org")) {
                    if (name == "igneous" && domain != ".exhentai.org") continue
                    manager.setCookie("https://${domain.removePrefix(".")}", "${part.trim()}; Domain=$domain; Path=/; Secure")
                }
            }
        }
        manager.flush()
        authenticationVersion.update { it + 1 }
    }

    suspend fun importWebLoginCookies(): Result<Unit> {
        val manager = CookieManager.getInstance()
        val cookies = linkedMapOf<String, String>()
        for (url in listOf("https://exhentai.org", "https://e-hentai.org", "https://forums.e-hentai.org")) {
            manager.getCookie(url).orEmpty().split(';').forEach {
                val name = it.substringBefore('=').trim()
                val value = it.substringAfter('=', "").trim()
                if (name in listOf("ipb_member_id", "ipb_pass_hash", "igneous", "star") && value.isNotBlank() && (name != "igneous" || url.contains("exhentai.org"))) cookies[name] = value
            }
        }
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val result = withContext(Dispatchers.IO) { runCatching { validateAndSaveCookie(cookie) } }
        return result
    }

    override val client: OkHttpClient
        get() = getClient().rawHttpClient

    override suspend fun fetchPopularComics(page: Int): AlignedPageResult = withContext(Dispatchers.IO) {
        val res = getClient().getPopularComics(page).getOrThrow()
        parseSearchResponse(res)
    }

    override suspend fun fetchLatestComics(page: Int): AlignedPageResult = withContext(Dispatchers.IO) {
        val res = getClient().searchComics(query = "", page = page).getOrThrow()
        parseSearchResponse(res)
    }

    override suspend fun fetchSearchComics(page: Int, query: String, filters: FilterList): AlignedPageResult = withContext(Dispatchers.IO) {
        val builder = EhQuery.builder().page(page)

        for (filter in filters) {
            when (filter) {
                is CategoryFilter -> filter.selected()?.let { builder.category(it) }
                is LanguageFilter -> filter.selected()?.let { builder.language(it) }
                is UploaderFilter -> if (filter.state.isNotBlank()) builder.uploader(filter.state.trim())
                else -> Unit
            }
        }

        if (query.isNotBlank()) {
            builder.keyword(query.trim())
        }

        val res = getClient().searchComics(builder.build()).getOrThrow()
        parseSearchResponse(res)
    }

    override val supportsComments: Boolean = true
    override val canPostComment: Boolean = false

    override suspend fun fetchComments(comicId: String, page: Int): AlignedCommentPage = withContext(Dispatchers.IO) {
        val cleanId = extractComicId(comicId)
        val parts = cleanId.split('_')
        if (parts.size < 2) {
            return@withContext AlignedCommentPage(emptyList(), 1, 1, 0, false)
        }
        val gid = parts[0]
        val token = parts[1]
        val galleryUrl = "${getClient().getEffectiveBaseUrl()}g/$gid/$token/"

        val html = client.newCall(GET(galleryUrl, headers)).execute().use { response ->
            check(response.isSuccessful) { "获取 E-Hentai 评论失败: HTTP ${response.code}" }
            response.body.string()
        }
        val doc = org.jsoup.Jsoup.parse(html)
        val commentDivs = doc.select("#cdiv .c1")

        val comments = commentDivs.mapIndexed { index, elem ->
            val postInfo = elem.selectFirst(".c2 .c3")?.text().orEmpty()
            val author = elem.selectFirst(".c2 .c3 a")?.text().orEmpty().ifBlank { "Anonymous" }
            val postDate = postInfo.substringBefore(" by:").removePrefix("Posted on ").trim()
            val scoreText = elem.selectFirst(".c2 .c5 span")?.text().orEmpty()
            val score = scoreText.filter { it.isDigit() || it == '-' || it == '+' }.toIntOrNull() ?: 0
            val body = elem.selectFirst(".c6")?.text().orEmpty()
            val cId = elem.selectFirst(".c6")?.id()?.removePrefix("comment_body_") ?: "eh_$index"

            AlignedComment(
                id = cId,
                author = author,
                avatarUrl = null,
                level = 0,
                content = body,
                createdAt = postDate.ifBlank { null },
                likesCount = score,
                isLiked = false,
                floor = index + 1,
                replyCount = 0,
                isTop = false,
            )
        }

        AlignedCommentPage(
            comments = comments,
            currentPage = 1,
            totalPages = 1,
            totalComments = comments.size,
            hasNextPage = false,
        )
    }

    override suspend fun fetchMangaDetails(comicId: String): SManga = withContext(Dispatchers.IO) {
        val cleanId = extractComicId(comicId)
        val detail = getClient().getComicDetail(cleanId).getOrThrow()
        SManga.create().apply {
            this.url = "/comic/${detail.itemId}"
            this.title = detail.name
            this.thumbnail_url = detail.cover
            this.description = buildString {
                append("评分: ").append(detail.rate).append(" ★\n")
                append("总页数: ").append(detail.pageCount).append(" P\n")
                append("虚拟卷数: ").append(detail.totalChapters).append("\n")
                if (detail.tags.isNotEmpty()) {
                    append("\n标签: ").append(detail.tags.joinToString(", "))
                }
            }
            this.genre = detail.tags.joinToString(", ")
            this.status = SManga.COMPLETED
        }
    }

    override suspend fun fetchChapterList(comicId: String): List<SChapter> = withContext(Dispatchers.IO) {
        val cleanId = extractComicId(comicId)
        val detail = getClient().getComicDetail(cleanId).getOrThrow()
        val total = detail.totalChapters.coerceAtLeast(1)
        if (total == 1) {
            val ch = SChapter.create().apply {
                this.url = "/comic/$cleanId/chapter/1"
                this.name = "全本 (${detail.pageCount}P)"
                this.chapter_number = 1f
            }
            listOf(ch)
        } else {
            (1..total).map { chNum ->
                SChapter.create().apply {
                    this.url = "/comic/$cleanId/chapter/$chNum"
                    val startP = (chNum - 1) * 20 + 1
                    val endP = (chNum * 20).coerceAtMost(detail.pageCount)
                    this.name = "第 $chNum 卷/部分 ($startP - $endP P)"
                    this.chapter_number = chNum.toFloat()
                }
            }.reversed()
        }
    }

    override suspend fun fetchComicPages(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val comicId = chapter.url.substringAfter("/comic/").substringBefore("/chapter/")
        val chapterNum = chapter.url.substringAfter("/chapter/").substringBefore('?').toIntOrNull() ?: 1
        val res = getClient().getComicImages(comicId, chapterNum).getOrThrow()
        val prefs = getSourcePreferences()
        val isOfficialDirect = baseUrl.contains("e-hentai.org") || baseUrl.contains("exhentai.org")
        val useProxy = !isOfficialDirect && prefs.getBoolean(PREF_KEY_USE_PROXY_FOR_IMAGE, false)
        val quality = prefs.getString(PREF_KEY_QUALITY, "75")?.toIntOrNull() ?: 75
        val width = prefs.getString(PREF_KEY_WIDTH, "1280")?.toIntOrNull() ?: 1280

        res.images.mapIndexed { index, img ->
            val imgUrl = if (useProxy) {
                img.toProxyUrl(
                    baseUrl = baseUrl,
                    width = width,
                    quality = quality,
                )
            } else {
                img.url
            }
            val isViewerPage = imgUrl.contains("/s/")
            Page(index, url = imgUrl, imageUrl = if (isViewerPage) null else imgUrl)
        }
    }

    @Deprecated("Inherited from HttpSource")
    override fun imageUrlParse(response: okhttp3.Response): String {
        val html = response.body.string()
        return EhParser.parseViewerImageUrl(html)
            ?: throw Exception("未能从阅读器页面解析到大图地址")
    }

    override fun extractComicId(url: String): String {
        EhParser.extractGidAndToken(url)?.let { (gid, token) -> return "${gid}_$token" }
        val clean = url.substringBefore('?').trim('/')
        return when {
            clean.contains("/chapter/") -> clean.substringAfter("/comic/").substringBefore("/chapter/")
            clean.contains("/comic/") -> clean.substringAfter("/comic/")
            else -> clean.substringAfterLast('/')
        }
    }

    override fun getMangaUrl(manga: SManga): String {
        if (!EhConfig(baseUrl = baseUrl).isDirectSite()) return super.getMangaUrl(manga)
        val id = extractComicId(manga.url)
        val parts = getClient().parseComicId(id) ?: return super.getMangaUrl(manga)
        return "${getClient().getEffectiveBaseUrl()}g/${parts.first}/${parts.second}/"
    }

    override fun getFilterList(): FilterList = FilterList(
        CategoryFilter(),
        LanguageFilter(),
        UploaderFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_BASE_URL
            title = "站点 / API 服务端地址"
            setDefaultValue(EhConstants.DEFAULT_BASE_URL)
            summary = "%s"
            dialogTitle = "输入 E-Hentai 官方站或自建 API 服务端地址"
            setOnPreferenceChangeListener { _, _ ->
                _ehClient = null
                true
            }
        }
        val cookiePref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_COOKIE
            title = "Cookie (可选，用于 ExHentai/权限)"
            summary = "如 ipb_member_id=...; ipb_pass_hash=...; igneous=..."
            dialogTitle = "输入 Cookie"
            setOnPreferenceChangeListener { _, newValue ->
                _ehClient?.updateCookie(newValue?.toString())
                true
            }
        }
        val useProxyPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_KEY_USE_PROXY_FOR_IMAGE
            title = "通过自建代理服务端加速/裁剪图片"
            summary = "仅在使用自建 Python 服务端时有效。直连官方站时自动使用原图直连"
            setDefaultValue(false)
        }
        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_KEY_QUALITY
            title = "代理图片压缩质量"
            entries = arrayOf("高画质 (90%)", "标准 (75%)", "适中 (60%)", "低流量 (40%)")
            entryValues = arrayOf("90", "75", "60", "40")
            setDefaultValue("75")
            summary = "%s"
            setOnPreferenceChangeListener { _, _ ->
                _ehClient = null
                true
            }
        }
        val widthPref = ListPreference(screen.context).apply {
            key = PREF_KEY_WIDTH
            title = "代理图片最大宽度"
            entries = arrayOf("超高清 (1920px)", "高清 (1280px)", "标准 (780px)", "小屏 (400px)")
            entryValues = arrayOf("1920", "1280", "780", "400")
            setDefaultValue("1280")
            summary = "%s"
            setOnPreferenceChangeListener { _, _ ->
                _ehClient = null
                true
            }
        }
        val proxyHostPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_PROXY_HOST
            title = "HTTP 代理主机 IP"
            summary = "如果直连无法访问，可配置本地代理（如 127.0.0.1）"
            dialogTitle = "输入代理 IP"
            setOnPreferenceChangeListener { _, _ ->
                _ehClient = null
                true
            }
        }
        val proxyPortPref = EditTextPreference(screen.context).apply {
            key = PREF_KEY_PROXY_PORT
            title = "HTTP 代理端口"
            summary = "代理端口（如 7890）"
            dialogTitle = "输入代理端口"
            setOnPreferenceChangeListener { _, _ ->
                _ehClient = null
                true
            }
        }

        screen.addPreference(baseUrlPref)
        screen.addPreference(cookiePref)
        screen.addPreference(useProxyPref)
        screen.addPreference(qualityPref)
        screen.addPreference(widthPref)
        screen.addPreference(proxyHostPref)
        screen.addPreference(proxyPortPref)
    }

    private fun parseSearchResponse(response: EhSearchResponse): AlignedPageResult {
        val mangas = response.results.map { item ->
            SManga.create().apply {
                this.url = "/comic/${item.comicId}"
                this.title = item.title
                this.thumbnail_url = item.coverUrl
                this.status = SManga.COMPLETED
            }
        }
        return AlignedPageResult(mangas, response.isHasMore)
    }

    // region Filters --------------------------------------------------------------------------------

    override val supportsCategories: Boolean = true

    override suspend fun fetchCategories() = categoryOptions { CategoryFilter() }

    private class CategoryFilter : Filter.Select<String>("分类筛选", CATEGORY_NAMES) {
        fun selected(): String? {
            val idx = state
            return if (idx in CATEGORY_VALUES.indices && CATEGORY_VALUES[idx].isNotBlank()) {
                CATEGORY_VALUES[idx]
            } else {
                null
            }
        }

        companion object {
            private val CATEGORIES = listOf(
                "全部 (All)" to "",
                "同人志 (Doujinshi)" to "doujinshi",
                "漫画 (Manga)" to "manga",
                "画师 CG (Artist CG)" to "artistcg",
                "游戏 CG (Game CG)" to "gamecg",
                "欧美 (Western)" to "western",
                "非 H (Non-H)" to "non-h",
                "图集 (Image Set)" to "imageset",
                "Cosplay" to "cosplay",
                "亚洲色情 (Asian Porn)" to "asianporn",
                "杂项 (Misc)" to "misc",
            )
            val CATEGORY_NAMES = CATEGORIES.map { it.first }.toTypedArray()
            val CATEGORY_VALUES = CATEGORIES.map { it.second }.toTypedArray()
        }
    }

    private class LanguageFilter : Filter.Select<String>("语言筛选", LANGUAGE_NAMES) {
        fun selected(): String? {
            val idx = state
            return if (idx in LANGUAGE_VALUES.indices && LANGUAGE_VALUES[idx].isNotBlank()) {
                LANGUAGE_VALUES[idx]
            } else {
                null
            }
        }

        companion object {
            private val LANGUAGES = listOf(
                "全部语言 (All)" to "",
                "中文 (Chinese)" to "chinese",
                "日文 (Japanese)" to "japanese",
                "英文 (English)" to "english",
            )
            val LANGUAGE_NAMES = LANGUAGES.map { it.first }.toTypedArray()
            val LANGUAGE_VALUES = LANGUAGES.map { it.second }.toTypedArray()
        }
    }

    private class UploaderFilter : Filter.Text("上传者 (Uploader)")

    companion object {
        private const val PREF_KEY_BASE_URL = "pref_eh_base_url"
        private const val PREF_KEY_COOKIE = "pref_eh_cookie"
        private const val PREF_KEY_USE_PROXY_FOR_IMAGE = "pref_eh_use_proxy_for_image"
        private const val PREF_KEY_QUALITY = "pref_eh_quality"
        private const val PREF_KEY_WIDTH = "pref_eh_width"
        private const val PREF_KEY_PROXY_HOST = "pref_eh_proxy_host"
        private const val PREF_KEY_PROXY_PORT = "pref_eh_proxy_port"
    }
}
