package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileOperationTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
        classDiscriminator = "type"
    }

    @Test
    fun `CreateFile serializes with polymorphic type discriminator`() {
        val encoded = json.encodeToString<FileOperation>(
            FileOperation.CreateFile(
                filePath = "/tmp/New.kt",
                content = "class New",
            ),
        )

        assertTrue(encoded.contains(""""type":"CREATE_FILE""""))
    }

    @Test
    fun `DeleteFile serializes with polymorphic type discriminator`() {
        val encoded = json.encodeToString<FileOperation>(
            FileOperation.DeleteFile(
                filePath = "/tmp/Old.kt",
                expectedHash = "abc123",
            ),
        )

        assertTrue(encoded.contains(""""type":"DELETE_FILE""""))
    }

    @Test
    fun `ApplyEditsQuery with empty fileOperations is backward compatible`() {
        val decoded = json.decodeFromString(
            ApplyEditsQuery.serializer(),
            """
                {
                  "edits": [
                    {
                      "filePath": "/tmp/Sample.kt",
                      "startOffset": 0,
                      "endOffset": 0,
                      "newText": "hello"
                    }
                  ],
                  "fileHashes": [
                    {
                      "filePath": "/tmp/Sample.kt",
                      "hash": "hash"
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(emptyList<FileOperation>(), decoded.fileOperations)
    }
}
