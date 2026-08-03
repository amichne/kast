package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.validation.EditPlanValidator
import io.github.amichne.kast.api.validation.ParsedFileOperation
import io.github.amichne.kast.api.validation.ValidatedFileOperation
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.api.validation.toWire

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
    fun `CreateFile existing parent policy survives wire parsing and validation while legacy defaults`() {
        val legacy = json.decodeFromString<FileOperation>(
            """
                {
                  "type": "CREATE_FILE",
                  "filePath": "/tmp/Legacy.kt",
                  "content": "class Legacy"
                }
            """.trimIndent(),
        ) as FileOperation.CreateFile
        assertEquals(CreateFileParentPolicy.CREATE_MISSING_PARENTS, legacy.parentPolicy)

        val requested = FileOperation.CreateFile(
            filePath = "/tmp/ExistingParent.kt",
            content = "class ExistingParent",
            parentPolicy = CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS,
        )
        val encoded = json.encodeToString<FileOperation>(requested)
        assertTrue(encoded.contains(""""parentPolicy":"REQUIRE_EXISTING_PARENTS""""))
        assertEquals(requested, json.decodeFromString<FileOperation>(encoded))

        val parsed = requested.parsed() as ParsedFileOperation.CreateFile
        assertEquals(CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS, parsed.parentPolicy)
        assertEquals(requested, parsed.toWire())

        val validated = EditPlanValidator.validateFileOperations(listOf(requested)).single()
            as ValidatedFileOperation.CreateFile
        assertEquals(CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS, validated.parentPolicy)
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
    fun `ApplyEditsQuery defaults fileOperations to empty`() {
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
