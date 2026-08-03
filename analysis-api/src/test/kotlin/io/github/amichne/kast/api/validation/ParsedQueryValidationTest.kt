package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.SemanticGraphExternalBoundaryFailureId
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParsedQueryValidationTest {

    @Test
    fun `ReferencesQuery parsed rejects non canonical continuation tokens`() {
        val position = FilePosition("/workspace/src/Main.kt", 0)

        for (pageToken in listOf("", "-1", "01", "not-a-page", "v1:INDEX:0:0")) {
            assertThrows<ValidationException> {
                ReferencesQuery(position = position, pageToken = pageToken).parsed()
            }
        }
    }

    @Test
    fun `DiagnosticsQuery parsed accepts only opaque continuation tokens`() {
        val token = "00000000-0000-0000-0000-000000000338"

        assertEquals(
            token,
            DiagnosticsQuery(listOf("/workspace/src/Main.kt"), pageToken = token).parsed().pageToken?.value,
        )
        assertThrows<ValidationException> {
            DiagnosticsQuery(listOf("/workspace/src/Main.kt"), pageToken = "8").parsed()
        }
    }

    @Test
    fun `depth bounded query parsed rejects negative depths`() {
        val position = FilePosition("/workspace/src/Main.kt", 0)

        assertThrows<ValidationException> { CallHierarchyQuery(position, CallDirection.INCOMING, depth = -1).parsed() }
        assertThrows<ValidationException> { TypeHierarchyQuery(position, depth = -1).parsed() }
    }

    @Test
    fun `call hierarchy parsed rejects invalid call limits and timeout`() {
        val position = FilePosition("/workspace/src/Main.kt", 0)

        assertThrows<ValidationException> {
            CallHierarchyQuery(position, CallDirection.INCOMING, maxTotalCalls = 0).parsed()
        }
        assertThrows<ValidationException> {
            CallHierarchyQuery(position, CallDirection.INCOMING, maxChildrenPerNode = 0).parsed()
        }
        assertThrows<ValidationException> {
            CallHierarchyQuery(position, CallDirection.INCOMING, timeoutMillis = 0).parsed()
        }
    }

    @Test
    fun `file path list query parsed rejects empty or relative paths`() {
        assertThrows<ValidationException> { DiagnosticsQuery(emptyList()).parsed() }
        assertThrows<ValidationException> { DiagnosticsQuery(listOf("relative.kt")).parsed() }
        assertThrows<ValidationException> { ImportOptimizeQuery(emptyList()).parsed() }
        assertThrows<ValidationException> { ImportOptimizeQuery(listOf("relative.kt")).parsed() }
        assertThrows<ValidationException> { RefreshQuery(listOf("relative.kt")).parsed() }
    }

    @Test
    fun `refresh query parses external failure IDs and rejects ambiguous requests`() {
        val failureId = "00000000-0000-0000-0000-000000000451"

        assertEquals(
            listOf(SemanticGraphExternalBoundaryFailureId.parse(failureId)),
            RefreshQuery(externalFailureIds = listOf(failureId)).parsed().externalFailureIds,
        )
        assertThrows<ValidationException> {
            RefreshQuery(
                filePaths = listOf("/workspace/src/Main.kt"),
                externalFailureIds = listOf(failureId),
            ).parsed()
        }
        assertThrows<ValidationException> {
            RefreshQuery(externalFailureIds = listOf("not-a-failure-id")).parsed()
        }
        assertThrows<ValidationException> {
            RefreshQuery(externalFailureIds = listOf(failureId, failureId)).parsed()
        }
    }

    @Test
    fun `blank string query parsed rejects blank values`() {
        val position = FilePosition("/workspace/src/Main.kt", 0)

        assertThrows<ValidationException> { RenameQuery(position, " ").parsed() }
        assertThrows<ValidationException> { WorkspaceSymbolQuery(" ").parsed() }
        assertThrows<ValidationException> { WorkspaceSearchQuery(" ").parsed() }
        assertThrows<ValidationException> { WorkspaceFilesQuery(moduleName = " ").parsed() }
    }

    @Test
    fun `apply edits parsed validates nested paths and can convert back to wire query`() {
        val parsed = ApplyEditsQuery(
            edits = listOf(TextEdit("/workspace/src/Main.kt", 0, 1, "x")),
            fileHashes = listOf(FileHash("/workspace/src/Main.kt", "hash")),
            fileOperations = listOf(FileOperation.DeleteFile("/workspace/src/Old.kt", "oldHash")),
        ).parsed()

        assertInstanceOf(ParsedFileOperation.DeleteFile::class.java, parsed.fileOperations.single())
        assertEquals("/workspace/src/Main.kt", parsed.toWire().edits.single().filePath)
        assertThrows<ValidationException> {
            ApplyEditsQuery(
                edits = listOf(TextEdit("relative.kt", 0, 1, "x")),
                fileHashes = emptyList(),
            ).parsed()
        }
    }
}
