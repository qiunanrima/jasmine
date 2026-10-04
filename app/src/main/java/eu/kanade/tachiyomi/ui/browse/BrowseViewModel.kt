package eu.kanade.tachiyomi.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import java.util.concurrent.Executors

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class BrowseViewModel(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val coroutineDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher()
    private var loadJob: Job? = null

    init {
        loadPopularManga()

        viewModelScope.launchIO {
            combine(
                sourcePreferences.disabledSources.changes(),
                sourcePreferences.pinnedSources.changes(),
                sourcePreferences.enabledLanguages.changes(),
            ) { _, _, _ -> }
                .drop(1)
                .collectLatest {
                    loadPopularManga()
                }
        }
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    private suspend fun getEnabledSources(): List<Source> {
        val disabledSources = sourcePreferences.disabledSources.get()
        val pinnedSources = sourcePreferences.pinnedSources.get()
        val enabledLanguages = sourcePreferences.enabledLanguages.get()

        val allCatalogueSources = sourceManager.getAll()
            .filterNot { it.isLocal() }

        val filtered = allCatalogueSources.filter {
            (it.lang in enabledLanguages || enabledLanguages.isEmpty()) &&
                "${it.id}" !in disabledSources
        }

        val sourcesToUse = if (filtered.isNotEmpty()) {
            filtered
        } else {
            allCatalogueSources.filter { "${it.id}" !in disabledSources }
        }

        return sourcesToUse.sortedWith(
            compareBy(
                { "${it.id}" !in pinnedSources },
                { it.name.lowercase() },
            ),
        )
    }

    fun loadPopularManga(isRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launchIO {
            val sources = getEnabledSources()

            if (sources.isEmpty()) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        items = emptyMap(),
                    )
                }
                return@launchIO
            }

            _state.update { currentState ->
                val initialMap = sources.associateWith { source ->
                    if (isRefresh) {
                        currentState.items[source] ?: SearchItemResult.Loading
                    } else {
                        SearchItemResult.Loading
                    }
                }
                currentState.copy(
                    isLoading = !isRefresh,
                    isRefreshing = isRefresh,
                    items = initialMap,
                )
            }

            sources.map { source ->
                async {
                    try {
                        val page = withContext(coroutineDispatcher) {
                            source.getPopularManga(1)
                        }

                        val titles = page.mangas
                            .take(20)
                            .map { it.toDomainManga(source.id) }
                            .distinctBy { it.url }
                            .let { networkToLocalManga(it) }

                        if (isActive) {
                            updateItem(source, SearchItemResult.Success(titles))
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    }
                }
            }.awaitAll()

            if (isActive) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
            }
        }
    }

    private fun updateItem(source: Source, result: SearchItemResult) {
        _state.update { current ->
            current.copy(
                items = current.items + (source to result),
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        coroutineDispatcher.close()
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val items: Map<Source, SearchItemResult> = emptyMap(),
    ) {
        val isEmpty: Boolean
            get() = !isLoading && items.isEmpty()
    }
}
