package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@ContributesIntoSet(AppScope::class)
class TrustExtensionRepositoryMigration(
    private val preferenceStore: PreferenceStore,
) : Migration {
    override val version: Float = 7f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val repoPref = preferenceStore.getStringSet("extension_repos", emptySet())
        if (repoPref.isSet()) {
            repoPref.delete()
        }
        return true
    }
}
