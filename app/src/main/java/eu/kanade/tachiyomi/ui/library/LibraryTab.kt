package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.util.fastAll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibraryOverviewContent
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesContent
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Help
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocal

data object LibraryTab : Tab {

    const val PAGE_LIBRARY = 0
    const val PAGE_UPDATES = 1

    private var currentSubPage = PAGE_LIBRARY
    private val pageEvent = Channel<Int>(Channel.CONFLATED)

    suspend fun switchToPage(page: Int) = pageEvent.send(page)

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        if (currentSubPage == PAGE_UPDATES) {
            navigator.push(DownloadQueueScreen)
        } else {
            requestOpenSettingsSheet()
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        var selectedSubTab by rememberSaveable { mutableIntStateOf(PAGE_LIBRARY) }

        DisposableEffect(selectedSubTab) {
            currentSubPage = selectedSubTab
            onDispose { }
        }

        val viewModel = metroViewModel<LibraryViewModel>()
        val settingsViewModel = metroViewModel<LibrarySettingsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        val libraryFavoritesSync = remember { context.appGraph.libraryFavoritesSync }
        var selectedCategoryForFullView by rememberSaveable { mutableStateOf<Long?>(null) }

        BackHandler(enabled = selectedCategoryForFullView != null) {
            selectedCategoryForFullView = null
        }

        LaunchedEffect(Unit) {
            scope.launchIO {
                libraryFavoritesSync.syncRemoteFavorites()
            }
        }

        val snackbarHostState = remember { SnackbarHostState() }

        val onClickRefresh: (Category?) -> Boolean = { category ->
            val started = LibraryUpdateJob.startNow(context.workManager, category)
            scope.launchIO {
                libraryFavoritesSync.syncRemoteFavorites()
            }
            scope.launch {
                val msgRes = when {
                    !started -> MR.strings.update_already_running
                    category != null -> MR.strings.updating_category
                    else -> MR.strings.updating_library
                }
                snackbarHostState.showSnackbar(context.stringResource(msgRes))
            }
            started
        }

        val onClickSync: () -> Unit = {
            scope.launch {
                snackbarHostState.showSnackbar("正在同步各账号收藏...")
                val result = libraryFavoritesSync.syncAll()
                snackbarHostState.showSnackbar("同步完成：上传 ${result.uploaded} 本，下载 ${result.downloaded} 本")
            }
        }

        val newUpdatesCount by produceState(initialValue = 0) {
            val graph = context.appGraph
            combine(
                graph.libraryPreferences.newShowUpdatesCount.changes(),
                graph.libraryPreferences.newUpdatesCount.changes(),
            ) { show, count ->
                if (show) count else 0
            }
                .collectLatest { value = it }
        }

        val topBarTabs: @Composable () -> Unit = {
            PrimaryTabRow(
                selectedTabIndex = selectedSubTab,
            ) {
                Tab(
                    selected = selectedSubTab == PAGE_LIBRARY,
                    onClick = { selectedSubTab = PAGE_LIBRARY },
                    text = { TabText(text = stringResource(MR.strings.label_library)) },
                )
                Tab(
                    selected = selectedSubTab == PAGE_UPDATES,
                    onClick = { selectedSubTab = PAGE_UPDATES },
                    text = {
                        TabText(
                            text = stringResource(MR.strings.label_recent_updates),
                            badgeCount = newUpdatesCount.takeIf { it > 0 },
                        )
                    },
                )
            }
        }

        if (selectedSubTab == PAGE_LIBRARY) {
            Scaffold(
                topBar = { scrollBehavior ->
                    Column {
                        val currentCategory = state.displayedCategories.find { it.id == selectedCategoryForFullView }
                        val title = if (selectedCategoryForFullView != null && currentCategory != null) {
                            eu.kanade.presentation.library.components.LibraryToolbarTitle(
                                text = currentCategory.name,
                                numberOfManga = state.getItemCountForCategory(currentCategory),
                            )
                        } else {
                            state.getToolbarTitle(
                                defaultTitle = stringResource(MR.strings.label_library),
                                defaultCategoryTitle = stringResource(MR.strings.label_default),
                                page = state.coercedActiveCategoryIndex,
                            )
                        }
                        LibraryToolbar(
                            hasActiveFilters = state.hasActiveFilters,
                            selectedCount = state.selection.size,
                            title = title,
                            onClickUnselectAll = viewModel::clearSelection,
                            onClickSelectAll = viewModel::selectAll,
                            onClickInvertSelection = viewModel::invertSelection,
                            onClickFilter = viewModel::showSettingsDialog,
                            onClickRefresh = { onClickRefresh(state.activeCategory) },
                            onClickGlobalUpdate = { onClickRefresh(null) },
                            onClickOpenRandomManga = {
                                scope.launch {
                                    val randomItem = viewModel.getRandomLibraryItemForCurrentCategory()
                                    if (randomItem != null) {
                                        navigator.push(MangaScreen(randomItem.libraryManga.manga.id))
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            context.stringResource(MR.strings.information_no_entries_found),
                                        )
                                    }
                                }
                            },
                            searchQuery = state.searchQuery,
                            onSearchQueryChange = viewModel::search,
                            // For scroll overlay when no tab
                            scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                            navigateUp = selectedCategoryForFullView?.let { { selectedCategoryForFullView = null } },
                            onClickSync = onClickSync,
                        )
                        if (!state.selectionMode) {
                            topBarTabs()
                        }
                    }
                },
            bottomBar = {
                LibraryBottomActionMenu(
                    visible = state.selectionMode,
                    onChangeCategoryClicked = viewModel::openChangeCategoryDialog,
                    onMarkAsReadClicked = { viewModel.markReadSelection(true) },
                    onMarkAsUnreadClicked = { viewModel.markReadSelection(false) },
                    onDownloadClicked = viewModel::performDownloadAction
                        .takeIf { state.selectedManga.fastAll { !it.isLocal() } },
                    onDeleteClicked = viewModel::openDeleteMangaDialog,
                    onMigrateClicked = {
                        val selection = state.selection
                        viewModel.clearSelection()
                        navigator.push(MigrationConfigScreen(selection))
                    },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                state.isLoading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
                state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                    val handler = LocalUriHandler.current
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.getting_started_guide,
                                icon = MaterialSymbols.AutoMirroredRounded.Help,
                                onClick = { handler.openUri(GETTING_STARTED_URL) },
                            ),
                        ),
                    )
                }
                selectedCategoryForFullView == null -> {
                    var isRefreshingOverview by remember { mutableStateOf(false) }
                    LibraryOverviewContent(
                        categories = state.displayedCategories,
                        getItemsForCategory = { state.getItemsForCategory(it) },
                        contentPadding = contentPadding,
                        onOpenCategory = { category ->
                            val index = state.displayedCategories.indexOfFirst { it.id == category.id }
                            if (index != -1) {
                                viewModel.updateActiveCategoryIndex(index)
                            }
                            selectedCategoryForFullView = category.id
                        },
                        onClickManga = { navigator.push(MangaScreen(it)) },
                        onLongClickManga = { navigator.push(MangaScreen(it)) },
                        refreshing = isRefreshingOverview,
                        onRefresh = {
                            isRefreshingOverview = true
                            onClickRefresh(null)
                            scope.launch {
                                kotlinx.coroutines.delay(1000)
                                isRefreshingOverview = false
                            }
                        },
                    )
                }
                else -> {
                    val currentCategory = state.displayedCategories.find { it.id == selectedCategoryForFullView }
                    val categoriesToShow = listOfNotNull(currentCategory)
                    LibraryContent(
                        categories = categoriesToShow,
                        searchQuery = state.searchQuery,
                        selection = state.selection,
                        contentPadding = contentPadding,
                        currentPage = 0,
                        hasActiveFilters = state.hasActiveFilters,
                        showPageTabs = false,
                        onChangeCurrentPage = { },
                        onClickManga = { navigator.push(MangaScreen(it)) },
                        onContinueReadingClicked = { it: LibraryManga ->
                            scope.launchIO {
                                val chapter = viewModel.getNextUnreadChapter(it.manga)
                                if (chapter != null) {
                                    context.startActivity(
                                        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                                }
                            }
                            Unit
                        }.takeIf { state.showMangaContinueButton },
                        onToggleSelection = viewModel::toggleSelection,
                        onToggleRangeSelection = { category, manga ->
                            viewModel.toggleRangeSelection(category, manga)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onRefresh = { onClickRefresh(currentCategory) },
                        onGlobalSearchClicked = {
                            navigator.push(GlobalSearchScreen(viewModel.state.value.searchQuery ?: ""))
                        },
                        getItemCountForCategory = { state.getItemCountForCategory(it) },
                        getDisplayMode = { viewModel.getDisplayMode() },
                        getColumnsForOrientation = { viewModel.getColumnsForOrientation(it) },
                        getItemsForCategory = { state.getItemsForCategory(it) },
                    )
                }
            }
        }

        val onDismissRequest = viewModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryViewModel.Dialog.SettingsSheet -> run {
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsViewModel,
                    category = state.activeCategory,
                )
            }
            is LibraryViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        viewModel.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        viewModel.clearSelection()
                        viewModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }
            is LibraryViewModel.Dialog.DeleteManga -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = dialog.manga.any(Manga::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteManga, deleteChapter ->
                        viewModel.removeMangas(dialog.manga, deleteManga, deleteChapter)
                        viewModel.clearSelection()
                    },
                )
            }
            null -> {}
        }

        } else {
            UpdatesContent(
                topBarTabs = topBarTabs,
            )
        }

        BackHandler(enabled = (selectedSubTab == PAGE_LIBRARY && (state.selectionMode || state.searchQuery != null)) || selectedSubTab == PAGE_UPDATES) {
            when {
                selectedSubTab == PAGE_LIBRARY && state.selectionMode -> viewModel.clearSelection()
                selectedSubTab == PAGE_LIBRARY && state.searchQuery != null -> viewModel.search(null)
                selectedSubTab == PAGE_UPDATES -> selectedSubTab = PAGE_LIBRARY
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog, selectedSubTab) {
            if (selectedSubTab == PAGE_LIBRARY) {
                HomeScreen.showBottomNav(!state.selectionMode)
            }
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(viewModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { viewModel.showSettingsDialog() } }
            launch {
                pageEvent.receiveAsFlow().collectLatest {
                    selectedSubTab = it
                }
            }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
