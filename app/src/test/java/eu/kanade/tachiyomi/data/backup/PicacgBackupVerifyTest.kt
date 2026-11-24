package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.zip.GZIPInputStream

class PicacgBackupVerifyTest {

    @Test
    fun testDecodePicacgBackup() {
        // Synthetic legacy protobuf fixture, gzip-compressed and encoded for source control.
        val fixture = Base64.getDecoder().decode(
            "H4sIAAAAAAACA+PK5Oja+vPJjZPft6kK8ekn5+dmJhfrFyfmFuSkSrEFg+kmRmUuMVQp/fyilNQifUMhTueMxIKS1C" +
                "IFww5GxkNs0rgUClziFOLiYgvITHZ0dpdgXMUmBOMIwO0HAFQBP++MAAAA",
        )
        val bytes = GZIPInputStream(fixture.inputStream()).use { it.readBytes() }
        val backup = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)

        val source = backup.backupSources.single()
        assertEquals(2697056176517831306L, source.sourceId)
        assertEquals("PicACG", backup.backupCategories.single().name)
        val manga = backup.backupManga.single()
        assertEquals(source.sourceId, manga.source)
        assertEquals("/comics/sample", manga.url)
        assertTrue(manga.favorite)
        val chapter = manga.chapters.single()
        assertEquals("/comics/sample/order/1", chapter.url)
        assertEquals(chapter.url, manga.history.single().url)
    }
}
