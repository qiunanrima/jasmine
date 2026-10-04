package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PicacgBackupVerifyTest {

    @Test
    fun testDecodePicacgBackup() {
        val file = File("F:\\Downloads\\picacg_backup.tachibk")
        assertTrue(file.exists(), "Backup file must exist")

        val rawBytes = file.source().buffer().gzip().buffer().use { it.readByteArray() }
        assertNotNull(rawBytes)
        println("Decompressed raw bytes size: ${rawBytes.size}")

        val backup = ProtoBuf.decodeFromByteArray(Backup.serializer(), rawBytes)
        assertNotNull(backup)

        println("Decoded successfully!")
        println("Total manga: ${backup.backupManga.size}")
        println("Total categories: ${backup.backupCategories.size}")
        println("Total sources: ${backup.backupSources.size}")

        assertEquals(1094, backup.backupManga.size)
        assertEquals(1, backup.backupCategories.size)
        assertEquals("PicACG", backup.backupCategories[0].name)
        assertEquals(1, backup.backupSources.size)
        assertEquals(2697056176517831306L, backup.backupSources[0].sourceId)

        val favorites = backup.backupManga.filter { it.favorite }
        println("Favorite manga count: ${favorites.size}")
        assertEquals(178, favorites.size)

        val withHistory = backup.backupManga.filter { it.history.isNotEmpty() }
        println("Manga with history count: ${withHistory.size}")
        assertEquals(703, withHistory.size)

        val firstFav = favorites.first()
        println("Sample favorite: title='${firstFav.title}', url='${firstFav.url}', chapters=${firstFav.chapters.size}")
        assertTrue(firstFav.url.startsWith("/comics/"))
    }
}
