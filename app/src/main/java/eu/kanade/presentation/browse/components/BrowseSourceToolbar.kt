package eu.kanade.presentation.browse.components

import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.FormatListBulleted
import mihon.icons.materialsymbols.automirroredrounded.ViewList
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.ViewModule
import mihon.icons.materialsymbols.roundedfilled.Favorite
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: Source?,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearch: (String) -> Unit,
    onLoginClick: (() -> Unit)? = null,
    onFavoritesClick: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    // Avoid capturing unstable source in actions lambda
    val title = source?.name
    val isLocalSource = source is LocalSource
    val isConfigurableSource = source is ConfigurableSource

    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(title) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        actions = {
            AppBarActions(
                actions = buildList {
                    if (source is BaseAlignedMangaSource && onLoginClick != null) {
                        add(
                            AppBar.Action(
                                title = if (source.isUserLoggedIn) "账号: ${source.savedAccount ?: ""}" else stringResource(MR.strings.login),
                                icon = MaterialSymbols.Rounded.Person,
                                onClick = onLoginClick,
                            ),
                        )
                    }
                    if (source is BaseAlignedMangaSource && source.supportsFavorites && onFavoritesClick != null) {
                        add(
                            AppBar.Action(
                                title = "我的收藏",
                                icon = MaterialSymbols.RoundedFilled.Favorite,
                                onClick = onFavoritesClick,
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_display_mode),
                            icon = when (displayMode) {
                                LibraryDisplayMode.List -> MaterialSymbols.AutoMirroredRounded.ViewList
                                LibraryDisplayMode.DetailedList -> MaterialSymbols.AutoMirroredRounded.FormatListBulleted
                                else -> MaterialSymbols.Rounded.ViewModule
                            },
                            onClick = { selectingDisplayMode = true },
                        ),
                    )
                    if (isLocalSource) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.label_help),
                                onClick = onHelpClick,
                            ),
                        )
                    } else {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_web_view),
                                onClick = onWebViewClick,
                            ),
                        )
                    }
                    if (isConfigurableSource) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_settings),
                                onClick = onSettingsClick,
                            ),
                        )
                    }
                },
            )

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
                RadioMenuItem(
                    text = { Text(text = "卡片列表") },
                    isChecked = displayMode == LibraryDisplayMode.DetailedList,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.DetailedList)
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
