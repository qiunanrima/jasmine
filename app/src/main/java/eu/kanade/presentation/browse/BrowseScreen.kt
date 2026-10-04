package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.BrowseViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.TravelExplore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun BrowseScreen(
    state: BrowseViewModel.State,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickSource: (Source) -> Unit,
    onClickItem: (Manga) -> Unit,
    onLongClickItem: (Manga) -> Unit,
    onClickSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.browse),
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_global_search),
                                icon = MaterialSymbols.Rounded.TravelExplore,
                                onClick = onClickSearch,
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        when {
            state.isLoading && state.items.isEmpty() -> {
                LoadingScreen(Modifier.padding(paddingValues))
            }
            state.isEmpty -> {
                EmptyScreen(
                    stringRes = MR.strings.source_empty_screen,
                    modifier = Modifier.padding(paddingValues),
                )
            }
            else -> {
                BrowseContent(
                    items = state.items,
                    contentPadding = paddingValues,
                    getManga = getManga,
                    onClickSource = onClickSource,
                    onClickItem = onClickItem,
                    onLongClickItem = onLongClickItem,
                    refreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}

@Composable
private fun BrowseContent(
    items: Map<Source, SearchItemResult>,
    contentPadding: PaddingValues,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickSource: (Source) -> Unit,
    onClickItem: (Manga) -> Unit,
    onLongClickItem: (Manga) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    PullRefresh(
        refreshing = refreshing,
        enabled = true,
        indicatorPadding = contentPadding,
        onRefresh = onRefresh,
    ) {
        ScrollbarLazyColumn(
            contentPadding = contentPadding,
        ) {
            items.forEach { (source, result) ->
                item(key = source.id) {
                    val lang = LocaleHelper.getLocalizedDisplayName(source.lang)
                    val subtitle = if (lang.isNotBlank()) {
                        "$lang • ${stringResource(MR.strings.popular)}"
                    } else {
                        stringResource(MR.strings.popular)
                    }

                    GlobalSearchResultItem(
                        title = source.name,
                        subtitle = subtitle,
                        onClick = { onClickSource(source) },
                        modifier = Modifier.animateItem(),
                    ) {
                        when (result) {
                            SearchItemResult.Loading -> {
                                GlobalSearchLoadingResultItem()
                            }
                            is SearchItemResult.Success -> {
                                GlobalSearchCardRow(
                                    titles = result.result,
                                    getManga = getManga,
                                    onClick = onClickItem,
                                    onLongClick = onLongClickItem,
                                )
                            }
                            is SearchItemResult.Error -> {
                                GlobalSearchErrorResultItem(message = result.throwable.message)
                            }
                        }
                    }
                }
            }
        }
    }
}
