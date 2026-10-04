package eu.kanade.presentation.history.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.util.lang.toTimestampString
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun HistoryItem(
    history: HistoryWithRelations,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickDelete: () -> Unit,
    onClickFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    sourceName: String? = null,
) {
    val context = LocalContext.current
    val resolvedSourceName by produceState(initialValue = sourceName, sourceName, history.coverData.sourceId) {
        if (sourceName != null) {
            value = sourceName
        } else {
            value = runCatching {
                context.appGraph.sourceManager.getOrStub(history.coverData.sourceId).getNameForMangaInfo()
            }.getOrNull()
        }
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        onClick = onClickCover,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                modifier = Modifier.width(72.dp),
                shape = MaterialTheme.shapes.small,
                data = history.coverData,
                onClick = onClickCover,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = history.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val readAt = remember { history.readAt?.toTimestampString() ?: "" }
                val timeText = if (history.chapterNumber > -1) {
                    stringResource(
                        MR.strings.recent_manga_time,
                        formatChapterNumber(history.chapterNumber),
                        readAt,
                    )
                } else {
                    readAt
                }
                if (timeText.isNotEmpty()) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val source = resolvedSourceName
                if (!source.isNullOrBlank()) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .heightIn(min = 108.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!history.coverData.isMangaFavorite) {
                        IconButton(
                            onClick = onClickFavorite,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Favorite,
                                contentDescription = stringResource(MR.strings.add_to_library),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    IconButton(
                        onClick = onClickDelete,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                FilledIconButton(
                    onClick = onClickResume,
                    shape = MaterialTheme.shapes.small,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        contentColor = contentColorFor(MaterialTheme.colorScheme.primaryContainer),
                    ),
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = MaterialSymbols.RoundedFilled.PlayArrow,
                        contentDescription = stringResource(MR.strings.action_resume),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun HistoryItemPreviews(
    @PreviewParameter(HistoryWithRelationsProvider::class)
    historyWithRelations: HistoryWithRelations,
) {
    TachiyomiPreviewTheme {
        Surface {
            HistoryItem(
                history = historyWithRelations,
                onClickCover = {},
                onClickResume = {},
                onClickDelete = {},
                onClickFavorite = {},
            )
        }
    }
}
