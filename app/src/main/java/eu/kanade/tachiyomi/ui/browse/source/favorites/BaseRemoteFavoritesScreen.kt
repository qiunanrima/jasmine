package eu.kanade.tachiyomi.ui.browse.source.favorites

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.AlignedSourceLoginDialog
import eu.kanade.presentation.browse.components.BrowseSourceLoadingItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.tachiyomi.source.builtin.base.AlignedPageResult
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.util.plus
import mihon.app.di.appGraph
import mihon.domain.manga.model.toDomainManga
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ViewList
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.ViewModule
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * 远程“我喜欢/我的收藏”界面父类：
 * 提供通用的多页漫画加载、下拉刷新、网格/列表展示、空状态/错误重试、点击跳转详情等能力。
 * 任何可登录或具备收藏接口的现代化 API 均可复用此类，特定源（如 PicACG）可通过继承此类重写特定方法。
 */
open class BaseRemoteFavoritesScreen(
    open val sourceId: Long,
) : Screen {

    /**
     * 界面标题，子类可覆盖自定义
     */
    open fun getTitle(source: BaseAlignedMangaSource?): String {
        return source?.let { "${it.name} - 我的收藏" } ?: "我的收藏"
    }

    /**
     * 分页异步拉取收藏数据，子类可覆盖以传入特定参数（例如自定义排序）
     */
    open suspend fun loadPage(source: BaseAlignedMangaSource, page: Int): AlignedPageResult {
        return source.fetchFavoriteComics(page)
    }

    /**
     * 在顶部导航栏下方显示的自定义子区域（如标签切换、排序芯片），默认无
     */
    @Composable
    open fun SubheaderContent(source: BaseAlignedMangaSource, onRefresh: () -> Unit) {
    }

    /**
     * 子类扩展的 AppBar 操作项
     */
    open fun getToolbarActions(source: BaseAlignedMangaSource): List<AppBar.Action> {
        return emptyList()
    }

    /**
     * 是否支持长按快速取消收藏
     */
    open fun supportQuickRemove(): Boolean = true

    /**
     * 移除收藏操作，默认调用图源的 removeFavoriteComic
     */
    open suspend fun removeFavorite(source: BaseAlignedMangaSource, comicId: String): Result<Unit> {
        return source.removeFavoriteComic(comicId)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        var source by remember { mutableStateOf<BaseAlignedMangaSource?>(null) }
        LaunchedEffect(sourceId) {
            val sourceManager = context.appGraph.sourceManager
            source = sourceManager.get(sourceId) as? BaseAlignedMangaSource
        }

        var showLoginDialog by remember { mutableStateOf(false) }
        var pendingRemoveComic by remember { mutableStateOf<SManga?>(null) }
        var isRemoving by remember { mutableStateOf(false) }

        var displayMode by remember { mutableStateOf<LibraryDisplayMode>(LibraryDisplayMode.ComfortableGrid) }
        var items by remember { mutableStateOf<List<SManga>>(emptyList()) }
        var currentPage by remember { mutableIntStateOf(1) }
        var hasNextPage by remember { mutableStateOf(false) }
        var isLoading by remember { mutableStateOf(true) }
        var isRefreshing by remember { mutableStateOf(false) }
        var isLoadingMore by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        val loadData: (isRefresh: Boolean) -> Unit = { isRefresh ->
            val curSource = source
            if (curSource != null) {
                if (isRefresh) {
                    isRefreshing = true
                } else {
                    isLoading = true
                }
                errorMessage = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            loadPage(curSource, 1)
                        }
                        items = result.mangas
                        currentPage = 1
                        hasNextPage = result.hasNextPage
                    } catch (e: Throwable) {
                        errorMessage = e.message ?: "加载收藏列表失败"
                    } finally {
                        isLoading = false
                        isRefreshing = false
                    }
                }
            }
        }

        val loadNextPage: () -> Unit = {
            val curSource = source
            if (curSource != null && hasNextPage && !isLoadingMore && !isLoading && !isRefreshing) {
                isLoadingMore = true
                val nextPage = currentPage + 1
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            loadPage(curSource, nextPage)
                        }
                        val newItems = result.mangas
                        items = items + newItems
                        currentPage = nextPage
                        hasNextPage = result.hasNextPage
                    } catch (e: Throwable) {
                        snackbarHostState.showSnackbar("加载更多失败: ${e.message ?: "未知错误"}")
                    } finally {
                        isLoadingMore = false
                    }
                }
            }
        }

        LaunchedEffect(sourceId, source?.isUserLoggedIn) {
            val curSource = source
            if (curSource != null && (!curSource.requiresLogin || curSource.isUserLoggedIn)) {
                loadData(false)
            } else {
                isLoading = false
            }
        }

        val gridState = rememberLazyGridState()
        val listState = rememberLazyListState()

        // 监听滚动到底部自动加载更多
        LaunchedEffect(gridState, displayMode, hasNextPage, isLoadingMore) {
            snapshotFlow {
                val layoutInfo = gridState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems to lastVisibleItem
            }.collect { (totalItems, lastVisible) ->
                if (totalItems > 0 && lastVisible >= totalItems - 4) {
                    loadNextPage()
                }
            }
        }
        LaunchedEffect(listState, displayMode, hasNextPage, isLoadingMore) {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems to lastVisibleItem
            }.collect { (totalItems, lastVisible) ->
                if (totalItems > 0 && lastVisible >= totalItems - 4) {
                    loadNextPage()
                }
            }
        }

        val onMangaClick: (SManga) -> Unit = { sManga ->
            scope.launch {
                try {
                    val networkToLocalManga = context.appGraph.networkToLocalManga
                    val localManga = withContext(Dispatchers.IO) {
                        networkToLocalManga(sManga.toDomainManga(sourceId))
                    }
                    navigator.push(MangaScreen(localManga.id))
                } catch (e: Throwable) {
                    snackbarHostState.showSnackbar("打开漫画失败: ${e.message}")
                }
            }
        }

        Scaffold(
            topBar = {
                Column {
                    AppBar(
                        title = getTitle(source),
                        navigateUp = { navigator.pop() },
                        actions = {
                            AppBarActions(
                                actions = buildList {
                                    val curSource = source
                                    if (curSource != null && !curSource.isUserLoggedIn) {
                                        add(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.login),
                                                icon = MaterialSymbols.Rounded.Person,
                                                onClick = { showLoginDialog = true },
                                            ),
                                        )
                                    }
                                    if (curSource != null) {
                                        addAll(getToolbarActions(curSource))
                                    }
                                    add(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_display_mode),
                                            icon = if (displayMode == LibraryDisplayMode.List) {
                                                MaterialSymbols.AutoMirroredRounded.ViewList
                                            } else {
                                                MaterialSymbols.Rounded.ViewModule
                                            },
                                            onClick = {
                                                displayMode = if (displayMode == LibraryDisplayMode.List) {
                                                    LibraryDisplayMode.ComfortableGrid
                                                } else {
                                                    LibraryDisplayMode.List
                                                }
                                            },
                                        ),
                                    )
                                },
                            )
                        },
                    )
                    val curSource = source
                    if (curSource != null) {
                        SubheaderContent(
                            source = curSource,
                            onRefresh = { loadData(true) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            val curSource = source
            if (curSource == null) {
                EmptyScreen(
                    message = "找不到该图源",
                    modifier = Modifier.padding(paddingValues),
                )
                return@Scaffold
            }

            if (curSource.requiresLogin && !curSource.isUserLoggedIn) {
                EmptyScreen(
                    message = "请先登录 ${curSource.name} 账号以查看我喜欢/我的收藏",
                    modifier = Modifier.padding(paddingValues),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.login,
                            icon = MaterialSymbols.Rounded.Person,
                            onClick = { showLoginDialog = true },
                        ),
                    ),
                )
                if (showLoginDialog) {
                    AlignedSourceLoginDialog(
                        source = curSource,
                        onDismissRequest = { showLoginDialog = false },
                        onLoginSuccess = {
                            showLoginDialog = false
                            loadData(true)
                        },
                    )
                }
                return@Scaffold
            }

            if (isLoading) {
                LoadingScreen(Modifier.padding(paddingValues))
                return@Scaffold
            }

            PullRefresh(
                refreshing = isRefreshing,
                enabled = !isRefreshing && !isLoading,
                indicatorPadding = paddingValues,
                onRefresh = { loadData(true) },
            ) {
                if (items.isEmpty()) {
                    EmptyScreen(
                        message = errorMessage ?: "暂无收藏或加载为空",
                        modifier = Modifier.padding(paddingValues),
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.action_retry,
                                icon = MaterialSymbols.Rounded.Refresh,
                                onClick = { loadData(false) },
                            ),
                        ),
                    )
                } else {
                    val orientation = LocalConfiguration.current.orientation
                    val columns = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        GridCells.Adaptive(168.dp)
                    } else {
                        GridCells.Adaptive(128.dp)
                    }

                    if (displayMode == LibraryDisplayMode.List) {
                        LazyColumn(
                            state = listState,
                            contentPadding = paddingValues + PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(items, key = { it.url }) { item ->
                                MangaListItem(
                                    title = item.title,
                                    coverData = MangaCover(
                                        mangaId = -1L,
                                        sourceId = sourceId,
                                        isMangaFavorite = true,
                                        url = item.thumbnail_url,
                                        lastModified = 0L,
                                    ),
                                    badge = {},
                                    isSelected = false,
                                    onClick = { onMangaClick(item) },
                                    onLongClick = {
                                        if (supportQuickRemove()) {
                                            pendingRemoveComic = item
                                        } else {
                                            onMangaClick(item)
                                        }
                                    },
                                )
                            }
                            if (isLoadingMore) {
                                item {
                                    BrowseSourceLoadingItem()
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = columns,
                            contentPadding = paddingValues + PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                            horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(items, key = { it.url }) { item ->
                                MangaComfortableGridItem(
                                    title = item.title,
                                    coverData = MangaCover(
                                        mangaId = -1L,
                                        sourceId = sourceId,
                                        isMangaFavorite = true,
                                        url = item.thumbnail_url,
                                        lastModified = 0L,
                                    ),
                                    coverBadgeStart = {},
                                    isSelected = false,
                                    onClick = { onMangaClick(item) },
                                    onLongClick = {
                                        if (supportQuickRemove()) {
                                            pendingRemoveComic = item
                                        } else {
                                            onMangaClick(item)
                                        }
                                    },
                                )
                            }
                            if (isLoadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    BrowseSourceLoadingItem()
                                }
                            }
                        }
                    }
                }
            }
        }

        val curSource = source
        if (showLoginDialog && curSource != null) {
            AlignedSourceLoginDialog(
                source = curSource,
                onDismissRequest = { showLoginDialog = false },
                onLoginSuccess = {
                    showLoginDialog = false
                    loadData(true)
                },
            )
        }

        // 取消收藏确认对话框
        pendingRemoveComic?.let { comic ->
            AlertDialog(
                onDismissRequest = { if (!isRemoving) pendingRemoveComic = null },
                title = { Text("取消收藏") },
                text = { Text("确定要将《${comic.title}》从远程收藏夹移除吗？") },
                confirmButton = {
                    TextButton(
                        enabled = !isRemoving,
                        onClick = {
                            val alertSource = source
                            if (alertSource != null) {
                                isRemoving = true
                                scope.launch {
                                    val comicId = alertSource.extractComicId(comic.url)
                                    val result = withContext(Dispatchers.IO) {
                                        removeFavorite(alertSource, comicId)
                                    }
                                    isRemoving = false
                                    pendingRemoveComic = null
                                    if (result.isSuccess) {
                                        items = items.filter { it.url != comic.url }
                                        snackbarHostState.showSnackbar("已取消收藏《${comic.title}》")
                                    } else {
                                        snackbarHostState.showSnackbar("取消收藏失败: ${result.exceptionOrNull()?.message ?: "未知错误"}")
                                    }
                                }
                            }
                        },
                    ) {
                        Text(if (isRemoving) "正在移除..." else "确定移除")
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isRemoving,
                        onClick = { pendingRemoveComic = null },
                    ) {
                        Text("取消")
                    }
                },
            )
        }
    }
}
