package eu.kanade.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.flow.Flow

@Inject
class GetIncognitoState(
    private val basePreferences: BasePreferences,
) {
    suspend fun await(sourceId: Long?): Boolean {
        return basePreferences.incognitoMode.get()
    }

    fun subscribe(sourceId: Long?): Flow<Boolean> {
        return basePreferences.incognitoMode.changes()
    }
}
