package eu.kanade.tachiyomi.ui.manga.comment

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.browse.components.AlignedSourceLoginDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.source.builtin.base.AlignedComment
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.screens.EmptyScreen
import mihon.app.di.appGraph

/**
 * 漫画评论页面，支持 PicACG、JMComic、E-Hentai 等图源的评论列表、分页加载与交互。
 */
data class MangaCommentScreen(
    val mangaId: Long,
    val initialComicTitle: String? = null,
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        var manga by remember { mutableStateOf<Manga?>(null) }
        var alignedSource by remember { mutableStateOf<BaseAlignedMangaSource?>(null) }
        var comicId by remember { mutableStateOf<String?>(null) }

        val comments = remember { mutableStateListOf<AlignedComment>() }
        var isLoading by remember { mutableStateOf(true) }
        var isRefreshing by remember { mutableStateOf(false) }
        var currentPage by remember { mutableIntStateOf(1) }
        var totalPages by remember { mutableIntStateOf(1) }
        var totalComments by remember { mutableIntStateOf(0) }
        var hasNextPage by remember { mutableStateOf(false) }

        var inputText by remember { mutableStateOf("") }
        var isSubmitting by remember { mutableStateOf(false) }
        var replyTarget by remember { mutableStateOf<AlignedComment?>(null) }
        var showLoginDialog by remember { mutableStateOf(false) }

        val listState = rememberLazyListState()

        suspend fun loadComments(page: Int, isRefresh: Boolean = false) {
            val src = alignedSource ?: return
            val cId = comicId ?: return
            if (isRefresh) {
                isRefreshing = true
            } else {
                isLoading = true
            }

            try {
                val res = src.fetchComments(cId, page)
                if (isRefresh || page == 1) {
                    comments.clear()
                }
                comments.addAll(res.comments)
                currentPage = res.currentPage
                totalPages = res.totalPages
                totalComments = res.totalComments
                hasNextPage = res.hasNextPage
            } catch (e: Throwable) {
                snackbarHostState.showSnackbar("加载评论失败: ${e.message ?: "未知错误"}")
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }

        // 初始化加载漫画与图源
        LaunchedEffect(mangaId) {
            withContext(Dispatchers.IO) {
                val m = context.appGraph.getManga.await(mangaId) ?: return@withContext
                manga = m
                val s = context.appGraph.sourceManager.get(m.source) as? BaseAlignedMangaSource
                alignedSource = s
                comicId = s?.extractComicId(m.url)
            }
            loadComments(page = 1)
        }

        // 滑动到底部自动加载更多
        LaunchedEffect(listState, hasNextPage, isLoading, isRefreshing) {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems > 0 && lastVisibleIndex >= totalItems - 3
            }
                .distinctUntilChanged()
                .filter { it && hasNextPage && !isLoading && !isRefreshing }
                .collect {
                    loadComments(page = currentPage + 1)
                }
        }

        Scaffold(
            topBar = {
                val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
                AppBar(
                    titleContent = {
                        val countText = if (totalComments > 0) " ($totalComments)" else ""
                        AppBarTitle(
                            title = "评论$countText",
                            subtitle = manga?.title ?: initialComicTitle,
                        )
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            actions = listOf(
                                AppBar.Action(
                                    title = "刷新",
                                    icon = MaterialSymbols.Rounded.Refresh,
                                    onClick = {
                                        scope.launch { loadComments(page = 1, isRefresh = true) }
                                    },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                val src = alignedSource
                if (src != null && src.supportsComments) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding(),
                        tonalElevation = 3.dp,
                        shadowElevation = 8.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            // 回复目标指示
                            replyTarget?.let { target ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "回复 @${target.author}：${target.content.take(20)}...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = { replyTarget = null },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = MaterialSymbols.Rounded.Close,
                                            contentDescription = "取消回复",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }

                            if (src.canPostComment) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = inputText,
                                        onValueChange = { inputText = it.take(200) },
                                        placeholder = {
                                            Text(
                                                text = if (replyTarget != null) "写下你的回复..." else "发表你的评论...",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        maxLines = 3,
                                        shape = RoundedCornerShape(20.dp),
                                    )

                                    IconButton(
                                        onClick = {
                                            if (inputText.isBlank() || isSubmitting) return@IconButton
                                            scope.launch {
                                                isSubmitting = true
                                                val cId = comicId ?: return@launch
                                                val contentToSend = inputText.trim()
                                                val postRes = if (replyTarget != null) {
                                                    src.replyComment(cId, replyTarget!!.id, contentToSend)
                                                } else {
                                                    src.postComment(cId, contentToSend)
                                                }
                                                if (postRes.isSuccess) {
                                                    inputText = ""
                                                    replyTarget = null
                                                    snackbarHostState.showSnackbar("评论发送成功！")
                                                    loadComments(page = 1, isRefresh = true)
                                                } else {
                                                    val err = postRes.exceptionOrNull()?.message ?: "发表失败"
                                                    snackbarHostState.showSnackbar(err)
                                                }
                                                isSubmitting = false
                                            }
                                        },
                                        enabled = inputText.isNotBlank() && !isSubmitting,
                                    ) {
                                        if (isSubmitting) {
                                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(
                                                imageVector = MaterialSymbols.Rounded.Check,
                                                contentDescription = "发送",
                                                tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                            )
                                        }
                                    }
                                }
                            } else if (!src.isUserLoggedIn && src.requiresLogin) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "登录后即可发表评论",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { showLoginDialog = true }) {
                                        Text("立即登录")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            PullRefresh(
                refreshing = isRefreshing,
                enabled = !isLoading,
                onRefresh = { scope.launch { loadComments(page = 1, isRefresh = true) } },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                when {
                    isLoading && comments.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    comments.isEmpty() -> {
                        EmptyScreen(
                            message = "暂无评论，快来抢首评吧！",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(
                                items = comments,
                                key = { index, item -> "${item.id}_${item.floor}_$index" },
                            ) { index, comment ->
                                CommentCardItem(
                                    comment = comment,
                                    alignedSource = alignedSource,
                                    context = context,
                                    floor = if (comment.floor > 0) comment.floor else (comments.size - index),
                                    canReply = alignedSource?.canPostComment == true,
                                    onReply = {
                                        replyTarget = comment
                                    },
                                    onReplyTarget = { targetReply ->
                                        replyTarget = targetReply
                                    },
                                    onLike = {
                                        scope.launch {
                                            val res = alignedSource?.likeComment(comment.id)
                                            if (res?.isSuccess == true) {
                                                snackbarHostState.showSnackbar("点赞成功！")
                                            } else {
                                                val msg = res?.exceptionOrNull()?.message ?: "点赞未支持或已点赞"
                                                snackbarHostState.showSnackbar(msg)
                                            }
                                        }
                                    },
                                )
                            }

                            if (hasNextPage) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showLoginDialog && alignedSource != null) {
            AlignedSourceLoginDialog(
                source = alignedSource!!,
                onDismissRequest = { showLoginDialog = false },
                onLoginSuccess = {
                    showLoginDialog = false
                    scope.launch { loadComments(page = 1, isRefresh = true) }
                },
            )
        }
    }
}

@Composable
private fun CommentCardItem(
    comment: AlignedComment,
    alignedSource: BaseAlignedMangaSource?,
    context: Context,
    floor: Int,
    canReply: Boolean,
    onReply: () -> Unit,
    onReplyTarget: (AlignedComment) -> Unit,
    onLike: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expandedReplies by remember { mutableStateOf(false) }
    val loadedReplies = remember(comment.id) {
        mutableStateListOf<AlignedComment>().apply {
            addAll(comment.replies)
        }
    }
    var isRepliesLoading by remember { mutableStateOf(false) }
    var repliesPage by remember { mutableIntStateOf(1) }
    var hasNextRepliesPage by remember { mutableStateOf(false) }
    var repliesError by remember { mutableStateOf<String?>(null) }

    fun loadReplies(page: Int) {
        val src = alignedSource ?: return
        scope.launch {
            isRepliesLoading = true
            repliesError = null
            try {
                val pageResult = src.fetchCommentReplies(comment.id, page)
                if (page == 1) {
                    loadedReplies.clear()
                }
                loadedReplies.addAll(pageResult.comments)
                repliesPage = page
                hasNextRepliesPage = pageResult.hasNextPage
            } catch (e: Throwable) {
                repliesError = e.message ?: "加载回复失败"
            } finally {
                isRepliesLoading = false
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (comment.isTop) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 用户信息行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 头像
                if (!comment.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(comment.avatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = comment.author,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // 作者、等级与时间
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = comment.author,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        if (comment.level > 0) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = "Lv.${comment.level}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }

                        if (comment.isTop) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Text(
                                    text = "置顶",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val timeStr = comment.formattedTime
                        if (timeStr.isNotBlank()) {
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (comment.slogan != null) {
                            Text(
                                text = comment.slogan,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 楼层标记
                Text(
                    text = "#$floor",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 评论正文
            Text(
                text = comment.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )

            // 操作底栏（点赞、回复、子评论展开）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 点赞按钮
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onLike)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Favorite,
                        contentDescription = "点赞",
                        tint = if (comment.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    if (comment.likesCount > 0) {
                        Text(
                            text = "${comment.likesCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 子回复展开按钮
                if (loadedReplies.isNotEmpty() || comment.replyCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (!expandedReplies) {
                                expandedReplies = true
                                if (loadedReplies.isEmpty() && comment.replyCount > 0) {
                                    loadReplies(page = 1)
                                }
                            } else {
                                expandedReplies = false
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        val count = if (loadedReplies.isNotEmpty()) loadedReplies.size else comment.replyCount
                        Text(
                            text = if (expandedReplies) "收起回复 ($count)" else "查看回复 ($count)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                // 回复按钮
                if (canReply) {
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = onReply,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "回复",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // 展开的子回复列表
            if (expandedReplies) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isRepliesLoading && loadedReplies.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else if (repliesError != null && loadedReplies.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = repliesError ?: "加载回复失败",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { loadReplies(page = 1) }) {
                                Text("重试", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else {
                        loadedReplies.forEach { reply ->
                            SubReplyItem(
                                reply = reply,
                                context = context,
                                canReply = canReply,
                                onReply = { onReplyTarget(reply) },
                            )
                        }

                        if (hasNextRepliesPage) {
                            if (isRepliesLoading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                TextButton(
                                    onClick = { loadReplies(repliesPage + 1) },
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                ) {
                                    Text("加载更多回复...", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubReplyItem(
    reply: AlignedComment,
    context: Context,
    canReply: Boolean,
    onReply: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!reply.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(reply.avatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = reply.author,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = reply.author,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (reply.level > 0) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = "Lv.${reply.level}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp),
                                )
                            }
                        }
                    }
                    val timeStr = reply.formattedTime
                    if (timeStr.isNotBlank()) {
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (canReply) {
                    TextButton(
                        onClick = onReply,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("回复", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Text(
                text = reply.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 34.dp),
            )
        }
    }
}
