package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.BrowseScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object BrowseTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(GlobalSearchScreen())
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val viewModel = metroViewModel<BrowseViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        BrowseScreen(
            state = state,
            getManga = { viewModel.getManga(it) },
            onClickSource = { source ->
                navigator.push(BrowseSourceScreen(source.id, null))
            },
            onClickItem = { manga ->
                navigator.push(MangaScreen(manga.id, true))
            },
            onLongClickItem = { manga ->
                navigator.push(MangaScreen(manga.id, true))
            },
            onClickSearch = {
                navigator.push(GlobalSearchScreen())
            },
            onRefresh = {
                viewModel.loadPopularManga(isRefresh = true)
            },
        )

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
