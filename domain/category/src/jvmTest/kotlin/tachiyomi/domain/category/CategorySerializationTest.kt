package tachiyomi.domain.category

import tachiyomi.domain.category.model.Category
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class CategorySerializationTest {
    @Test
    fun categoryStillSupportsJavaSerialization() {
        val category = Category(1, "Favorites", 2, 3)
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(category) }
            output.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }

        assertEquals(category, restored)
    }
}
