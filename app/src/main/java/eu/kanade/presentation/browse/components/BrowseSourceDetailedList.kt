package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaDetailedListItem
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceDetailedList(
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            if (mangaList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceDetailedListItem(
                manga = manga,
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
            )
        }

        item {
            if (mangaList.loadState.append is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceDetailedListItem(
    manga: Manga,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    val categories = manga.genre.orEmpty()
    val extraInfo = manga.description?.takeIf { it.isNotBlank() }
    MangaDetailedListItem(
        title = manga.title,
        author = manga.author ?: manga.artist,
        categories = categories,
        extraInfo = extraInfo,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        badge = if (manga.favorite) {
            { InLibraryBadge(enabled = true) }
        } else {
            null
        },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
