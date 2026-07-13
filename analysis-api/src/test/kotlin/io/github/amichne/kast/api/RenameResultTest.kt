package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.RenameResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenameResultTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `affected files derive from distinct edit paths in edit order`() {
        val result = RenameResult.of(
            edits = listOf(
                edit("/workspace/src/First.kt", startOffset = 1),
                edit("/workspace/src/First.kt", startOffset = 10),
                edit("/workspace/src/Second.kt", startOffset = 2),
            ),
            fileHashes = emptyList(),
        )

        assertEquals(
            listOf("/workspace/src/First.kt", "/workspace/src/Second.kt"),
            result.affectedFiles,
        )
    }

    @Test
    fun `factory snapshots mutable plan collections`() {
        val originalEdit = edit("/workspace/src/Original.kt", startOffset = 1)
        val originalHash = FileHash(originalEdit.filePath, "original-hash")
        val edits = mutableListOf(originalEdit)
        val fileHashes = mutableListOf(originalHash)
        val result = RenameResult.of(edits = edits, fileHashes = fileHashes)

        edits += edit("/workspace/src/AddedLater.kt", startOffset = 2)
        fileHashes.clear()

        assertNotSame(edits, result.edits)
        assertNotSame(fileHashes, result.fileHashes)
        assertEquals(listOf(originalEdit), result.edits)
        assertEquals(listOf(originalHash), result.fileHashes)
        assertEquals(listOf(originalEdit.filePath), result.affectedFiles)
    }

    @Test
    fun `equivalent rename results retain structural value semantics`() {
        val edits = listOf(edit("/workspace/src/Value.kt", startOffset = 1))
        val fileHashes = listOf(FileHash(edits.single().filePath, "value-hash"))
        val first = RenameResult.of(edits = edits, fileHashes = fileHashes)
        val equivalent = RenameResult.of(edits = edits.toList(), fileHashes = fileHashes.toList())
        val different = RenameResult.of(
            edits = listOf(edit("/workspace/src/Different.kt", startOffset = 1)),
            fileHashes = fileHashes,
        )

        assertEquals(first, equivalent)
        assertEquals(first.hashCode(), equivalent.hashCode())
        assertNotEquals(first, different)
        assertEquals(
            "RenameResult(" +
                "edits=${first.edits}, " +
                "fileHashes=${first.fileHashes}, " +
                "affectedFiles=${first.affectedFiles}, " +
                "searchScope=${first.searchScope}, " +
                "schemaVersion=${first.schemaVersion}" +
                ")",
            first.toString(),
        )
    }

    @Test
    fun `deserialization rejects affected files that contradict edit paths`() {
        val valid = RenameResult.of(
            edits = listOf(edit("/workspace/src/Actual.kt", startOffset = 1)),
            fileHashes = emptyList(),
        )
        val validJson = json.encodeToJsonElement(RenameResult.serializer(), valid).jsonObject
        val malformedJson = JsonObject(
            validJson + ("affectedFiles" to JsonArray(listOf(JsonPrimitive("/workspace/src/Fabricated.kt")))),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromJsonElement(RenameResult.serializer(), malformedJson)
        }

        assertTrue(checkNotNull(failure.message).contains("affectedFiles"))
    }

    private fun edit(filePath: String, startOffset: Int): TextEdit = TextEdit(
        filePath = filePath,
        startOffset = startOffset,
        endOffset = startOffset + 1,
        newText = "renamed",
    )
}
