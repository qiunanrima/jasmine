package eu.kanade.tachiyomi.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.builtin.base.AlignedComment
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Compact comment list shown beside the reader on landscape screens. */
@Composable
fun ReaderCommentSidebar(
    source: BaseAlignedMangaSource?,
    comicId: String?,
    enabled: Boolean,
    onHasContentChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val comments = remember(source, comicId) { mutableStateListOf<AlignedComment>() }
    val listState = rememberLazyListState()
    var isLoading by remember(source, comicId) { mutableStateOf(false) }
    var currentPage by remember(source, comicId) { mutableIntStateOf(1) }
    var hasNextPage by remember(source, comicId) { mutableStateOf(false) }

    suspend fun loadComments(page: Int) {
        val currentSource = source ?: return
        val currentComicId = comicId ?: return
        if (!currentSource.supportsComments || currentComicId.isBlank()) return

        isLoading = true
        try {
            val result = currentSource.fetchComments(currentComicId, page)
            if (page == 1) comments.clear()
            comments.addAll(result.comments)
            currentPage = result.currentPage
            hasNextPage = result.hasNextPage
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (page == 1) comments.clear()
            hasNextPage = false
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(source, comicId, enabled) {
        comments.clear()
        currentPage = 1
        hasNextPage = false
        if (enabled) loadComments(1)
        else onHasContentChanged(false)
    }

    LaunchedEffect(enabled, comments.size, isLoading) {
        onHasContentChanged(enabled && (isLoading || comments.isNotEmpty()))
    }

    LaunchedEffect(listState, enabled, hasNextPage, isLoading, comments.size) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            layoutInfo.totalItemsCount > 0 && lastVisible >= layoutInfo.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .filter { it && enabled && hasNextPage && !isLoading }
            .collect {
                loadComments(currentPage + 1)
            }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        when {
            !enabled -> Box(modifier = Modifier.fillMaxSize())
            isLoading && comments.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            comments.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无评论",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = comments,
                    key = { index, comment -> "${comment.id}_$index" },
                ) { index, comment ->
                    ReaderCommentCard(
                        comment = comment,
                        floor = if (comment.floor > 0) comment.floor else comments.size - index,
                    )
                }
                if (isLoading) {
                    item(key = "reader_comment_loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderCommentCard(comment: AlignedComment, floor: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (comment.isTop) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = comment.author.ifBlank { "Anonymous" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "#$floor",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (comment.formattedTime.isNotBlank()) {
                Text(
                    text = comment.formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = comment.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            if (comment.replyCount > 0 || comment.likesCount > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = buildString {
                        if (comment.likesCount > 0) append("赞 ${comment.likesCount}")
                        if (comment.replyCount > 0) {
                            if (isNotEmpty()) append("  ·  ")
                            append("回复 ${comment.replyCount}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
