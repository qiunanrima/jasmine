package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource

@Composable
fun LibraryOverviewContent(
    categories: List<Category>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    contentPadding: PaddingValues,
    onOpenCategory: (Category) -> Unit,
    onClickManga: (Long) -> Unit,
    onLongClickManga: (Long) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullRefresh(
        refreshing = refreshing,
        enabled = true,
        indicatorPadding = contentPadding,
        onRefresh = onRefresh,
    ) {
        ScrollbarLazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            val nonEmptyCategories = categories.map { it to getItemsForCategory(it) }
                .filter { it.second.isNotEmpty() }

            items(nonEmptyCategories, key = { it.first.id }) { (category, items) ->
                GlobalSearchResultItem(
                    title = category.name,
                    subtitle = "${items.size} 本漫画",
                    onClick = { onOpenCategory(category) },
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        items(items, key = { it.id }) { item ->
                            val manga = item.libraryManga.manga
                            Box(modifier = Modifier.width(96.dp)) {
                                MangaComfortableGridItem(
                                    title = manga.title,
                                    titleMaxLines = 3,
                                    coverData = manga.asMangaCover(),
                                    coverBadgeStart = {
                                        DownloadsBadge(count = item.badges.downloadCount)
                                        UnreadBadge(count = item.badges.unreadCount)
                                    },
                                    coverBadgeEnd = {
                                        LanguageBadge(
                                            isLocal = item.badges.isLocal,
                                            sourceLanguage = item.badges.sourceLanguage,
                                        )
                                    },
                                    onClick = { onClickManga(manga.id) },
                                    onLongClick = { onLongClickManga(manga.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
