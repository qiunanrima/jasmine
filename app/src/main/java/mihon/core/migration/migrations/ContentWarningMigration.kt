package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@ContributesIntoSet(AppScope::class)
class ContentWarningMigration(
    private val preferenceStore: PreferenceStore,
) : Migration {
    override val version: Float = 30f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val showNsfwSource = preferenceStore.getBoolean("show_nsfw_source", true)
        if (showNsfwSource.isSet()) {
            showNsfwSource.delete()
        }
        return true
    }
}
