package eu.kanade.tachiyomi.ui.browse.source.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.picaapi.PicaSort
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.source.builtin.base.AlignedPageResult
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import eu.kanade.tachiyomi.source.builtin.picacg.PicacgSource
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Schedule

/**
 * PicACG 专属收藏/喜欢界面：
 * 继承 [BaseRemoteFavoritesScreen] 通用基类，并实现 PicACG 特有的功能：
 *  1. 最新/最旧 (dd/da) 双向排序切换；
 *  2. PicACG 专属专属标题与账号标识；
 *  3. PicACG 专属取消收藏调用。
 */
class PicacgFavoritesScreen(
    override val sourceId: Long,
) : BaseRemoteFavoritesScreen(sourceId) {

    // PicACG 特有状态：排序方式（默认最新排序 PicaSort.NEWEST = "dd"）
    private var sortMode by mutableStateOf(PicaSort.NEWEST)

    override fun getTitle(source: BaseAlignedMangaSource?): String {
        return "PicACG - 我喜欢"
    }

    override suspend fun loadPage(source: BaseAlignedMangaSource, page: Int): AlignedPageResult {
        return if (source is PicacgSource) {
            source.fetchFavoriteComics(page = page, sort = sortMode)
        } else {
            super.loadPage(source, page)
        }
    }

    @Composable
    override fun SubheaderContent(source: BaseAlignedMangaSource, onRefresh: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = sortMode == PicaSort.NEWEST,
                onClick = {
                    if (sortMode != PicaSort.NEWEST) {
                        sortMode = PicaSort.NEWEST
                        onRefresh()
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                label = {
                    Text(
                        text = "最新收藏",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )

            FilterChip(
                selected = sortMode == PicaSort.OLDEST,
                onClick = {
                    if (sortMode != PicaSort.OLDEST) {
                        sortMode = PicaSort.OLDEST
                        onRefresh()
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                label = {
                    Text(
                        text = "最旧收藏",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }

    override fun getToolbarActions(source: BaseAlignedMangaSource): List<AppBar.Action> {
        val account = source.savedAccount
        return if (!account.isNullOrBlank()) {
            listOf(
                AppBar.Action(
                    title = "用户: $account",
                    icon = MaterialSymbols.Rounded.Schedule,
                    onClick = {},
                ),
            )
        } else {
            emptyList()
        }
    }

    override suspend fun removeFavorite(source: BaseAlignedMangaSource, comicId: String): Result<Unit> {
        return if (source is PicacgSource) {
            source.removeFavoriteComic(comicId)
        } else {
            super.removeFavorite(source, comicId)
        }
    }
}
