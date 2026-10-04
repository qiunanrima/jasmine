package eu.kanade.tachiyomi.source.builtin.base

import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一抽象基类：适用于 PicACG 与 JMComic 等已做语义对齐的现代漫画 API 源。
 *
 * 核心对齐业务抽象：
 *  - 搜索与多维检索 (searchComics)
 *  - 热门排行榜 (getPopularManga)
 *  - 最新上架 (getLatestUpdates)
 *  - 漫画详情解析 (getComicDetail)
 *  - 章节/分卷获取 (getComicEpisodes)
 *  - 单章节分页图片加载 (getComicPages)
 *  - 混淆图片解码扩展支持
 */
abstract class BaseAlignedMangaSource : HttpSource(), ConfigurableSource {

    override val lang: String = "zh"
    override val supportsLatest: Boolean = true

    open val supportsCategories: Boolean = false

    open suspend fun fetchCategories(): List<AlignedSourceCategory> = emptyList()

    protected fun categoryOptions(factory: () -> Filter.Select<String>): List<AlignedSourceCategory> =
        buildSourceCategories(::getFilterList, factory)

    /**
     * 是否强制要求登录才能浏览（例如 PicACG 需要，JMComic 支持访客免登）。
     */
    open val requiresLogin: Boolean = false

    /**
     * 当前是否已有登录态或已配置有效令牌。
     */
    open val isUserLoggedIn: Boolean = false

    /**
     * 当前已保存的账号/用户名。
     */
    open val savedAccount: String? = null

    /**
     * 执行登录操作并持久化凭据。
     */
    open suspend fun login(account: String, password: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("该图源暂未实现登录接口"))

    /**
     * 退出登录并清除持久化凭据。
     */
    open fun logout() {}

    /**
     * 该 API 是否支持远程“我喜欢/我的收藏”。
     */
    open val supportsFavorites: Boolean get() = false

    /**
     * 分页异步获取当前登录用户的远程收藏列表。
     */
    open suspend fun fetchFavoriteComics(page: Int): AlignedPageResult {
        throw UnsupportedOperationException("该图源暂不支持远程收藏")
    }

    /**
     * 添加远程收藏 / 喜欢。
     */
    open suspend fun addFavoriteComic(comicId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("该图源暂不支持添加远程收藏"))
    }

    /**
     * 移除远程收藏 / 取消喜欢。
     */
    open suspend fun removeFavoriteComic(comicId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("该图源暂不支持移除远程收藏"))
    }

    /**
     * 该 API 是否支持漫画评论功能。
     */
    open val supportsComments: Boolean get() = false

    /**
     * 当前是否可以发表评论（通常需登录）。
     */
    open val canPostComment: Boolean get() = false

    /**
     * 分页异步获取漫画评论列表。
     */
    open suspend fun fetchComments(comicId: String, page: Int): AlignedCommentPage {
        throw UnsupportedOperationException("该图源暂不支持评论")
    }

    /**
     * 异步发表评论。
     */
    open suspend fun postComment(comicId: String, content: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("该图源暂不支持发表评论"))
    }

    /**
     * 异步给评论点赞。
     */
    open suspend fun likeComment(commentId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("该图源暂不支持评论点赞"))
    }

    /**
     * 异步获取评论的子回复列表。
     */
    open suspend fun fetchCommentReplies(commentId: String, page: Int): AlignedCommentPage {
        throw UnsupportedOperationException("该图源暂不支持获取子回复")
    }

    /**
     * 异步回复特定评论（部分图源如 JMComic 需要所属漫画 ID）。
     */
    open suspend fun replyComment(comicId: String, commentId: String, content: String): Result<Unit> {
        return replyComment(commentId, content)
    }

    /**
     * 异步回复评论。
     */
    open suspend fun replyComment(commentId: String, content: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("该图源暂不支持回复评论"))
    }

    /**
     * 异步获取热门/推荐本子。
     */
    abstract suspend fun fetchPopularComics(page: Int): AlignedPageResult

    /**
     * 异步获取最新上架本子。
     */
    abstract suspend fun fetchLatestComics(page: Int): AlignedPageResult

    /**
     * 异步执行关键词或高级过滤搜索。
     */
    abstract suspend fun fetchSearchComics(page: Int, query: String, filters: FilterList): AlignedPageResult

    /**
     * 异步获取漫画详细信息。
     */
    abstract suspend fun fetchMangaDetails(comicId: String): SManga

    /**
     * 异步获取章节列表（已处理单行本与分页差异）。
     */
    abstract suspend fun fetchChapterList(comicId: String): List<SChapter>

    /**
     * 异步获取某一章节的所有单页图片。
     */
    abstract suspend fun fetchComicPages(chapter: SChapter): List<Page>

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        val result = fetchPopularComics(page)
        MangasPage(result.mangas, result.hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = withContext(Dispatchers.IO) {
        val result = fetchLatestComics(page)
        MangasPage(result.mangas, result.hasNextPage)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(Dispatchers.IO) {
        val result = fetchSearchComics(page, query, filters)
        MangasPage(result.mangas, result.hasNextPage)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val comicId = extractComicId(manga.url)
        val updatedManga = if (fetchDetails) {
            fetchMangaDetails(comicId).apply {
                this.url = manga.url
                this.initialized = true
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            fetchChapterList(comicId)
        } else {
            chapters
        }
        SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        fetchComicPages(chapter)
    }

    /**
     * 从漫画 URL 中提取 ID。
     */
    open fun extractComicId(url: String): String {
        val cleanUrl = url.substringBefore('?').trim('/')
        return cleanUrl.substringAfterLast('/')
    }
}

/**
 * 分页漫画返回结果封装。
 */
data class AlignedPageResult(
    val mangas: List<SManga>,
    val hasNextPage: Boolean,
)

/**
 * 跨源对齐的评论实体。
 */
data class AlignedComment(
    val id: String,
    val author: String,
    val avatarUrl: String? = null,
    val level: Int = 0,
    val slogan: String? = null,
    val content: String,
    val createdAt: String? = null,
    val likesCount: Int = 0,
    val isLiked: Boolean = false,
    val floor: Int = 0,
    val replyCount: Int = 0,
    val isTop: Boolean = false,
    val replies: List<AlignedComment> = emptyList(),
) {
    val formattedTime: String
        get() = formatCommentTime(createdAt)
}

/**
 * 分页评论结果封装。
 */
data class AlignedCommentPage(
    val comments: List<AlignedComment>,
    val currentPage: Int,
    val totalPages: Int,
    val totalComments: Int,
    val hasNextPage: Boolean,
)

/**
 * 针对各图源不同时间戳格式的统一优雅格式化函数。
 * 支持 ISO 8601、标准 SQL 日期时间、时间戳秒/毫秒及各类常见时间格式，
 * 并转换为易读的相对时间（刚刚、X分钟前、X小时前、昨天 HH:mm、MM-dd HH:mm 或 yyyy-MM-dd HH:mm）。
 */
fun formatCommentTime(rawTime: String?): String {
    if (rawTime.isNullOrBlank()) return ""
    val trimmed = rawTime.trim()

    // 若已经是相对时间直接返回
    if (trimmed == "刚刚" || trimmed.endsWith("前") || trimmed.contains("ago")) {
        return trimmed
    }

    val epochMillis = parseCommentTimeToMillis(trimmed) ?: return trimmed
    return formatRelativeCommentTime(epochMillis)
}

private fun parseCommentTimeToMillis(raw: String): Long? {
    // 1. 纯数字时间戳 (秒或毫秒)
    val number = raw.toLongOrNull()
    if (number != null) {
        return if (raw.length <= 10) number * 1000L else number
    }

    // 2. ISO-8601 格式 (如 2023-08-15T09:32:15.123Z, 2023-08-15T09:32:15+08:00)
    try {
        return java.time.Instant.parse(raw).toEpochMilli()
    } catch (_: Throwable) {}

    try {
        return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    } catch (_: Throwable) {}

    // 3. 常见日期时间格式解析
    val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd",
    )
    for (pattern in patterns) {
        try {
            val formatter = java.time.format.DateTimeFormatter.ofPattern(pattern)
            val ldt = if (pattern.contains("HH:mm")) {
                java.time.LocalDateTime.parse(raw, formatter)
            } else {
                java.time.LocalDate.parse(raw, formatter).atStartOfDay()
            }
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Throwable) {}
    }

    // 4. E-Hentai 格式: "Posted on 15 Feb 2024, 08:30 UTC" 或 "15 Feb 2024, 08:30 UTC"
    try {
        val cleanEh = raw.removePrefix("Posted on").removePrefix("Posted").trim()
            .substringBefore(" by").trim()
        val ehFormatter = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.US)
        val cleanDateTime = cleanEh.removeSuffix("UTC").trim()
        val ldt = java.time.LocalDateTime.parse(cleanDateTime, ehFormatter)
        return ldt.atZone(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli()
    } catch (_: Throwable) {}

    return null
}

private fun formatRelativeCommentTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    val systemZone = java.time.ZoneId.systemDefault()
    val commentTime = java.time.Instant.ofEpochMilli(epochMillis).atZone(systemZone).toLocalDateTime()
    val nowTime = java.time.Instant.ofEpochMilli(now).atZone(systemZone).toLocalDateTime()

    // 服务器时钟微弱超前 (< 5分钟) 视作刚刚
    if (diff < 0) {
        return if (diff > -300_000L) "刚刚" else {
            val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            commentTime.format(fmt)
        }
    }

    val seconds = diff / 1000L
    val minutes = seconds / 60L
    val hours = minutes / 60L

    return when {
        seconds < 60L -> "刚刚"
        minutes < 60L -> "${minutes}分钟前"
        hours < 24L && commentTime.toLocalDate() == nowTime.toLocalDate() -> "${hours}小时前"
        commentTime.toLocalDate() == nowTime.toLocalDate().minusDays(1) -> {
            val timePart = commentTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            "昨天 $timePart"
        }
        commentTime.year == nowTime.year -> {
            commentTime.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }
        else -> {
            commentTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }
    }
}
