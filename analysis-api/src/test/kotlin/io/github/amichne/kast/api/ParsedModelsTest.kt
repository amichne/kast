package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.protocol.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParsedModelsTest {

    @Test
    fun `FilePosition parsed validates path and offset`() {
        val fp = FilePosition(filePath = "/workspace/src/Main.kt", offset = 42)
        val parsed = fp.parsed()
        assertEquals(42, parsed.offset.value)
        assert(parsed.filePath.value.endsWith("Main.kt"))
    }

    @Test
    fun `FilePosition parsed rejects relative path`() {
        val fp = FilePosition(filePath = "relative/path.kt", offset = 0)
        assertThrows<ValidationException> { fp.parsed() }
    }

    @Test
    fun `FilePosition parsed rejects negative offset`() {
        val fp = FilePosition(filePath = "/workspace/src/Main.kt", offset = -1)
        assertThrows<IllegalArgumentException> { fp.parsed() }
    }

    @Test
    fun `Location parsed validates all fields`() {
        val loc = Location(
            filePath = "/workspace/src/Main.kt",
            startOffset = 10,
            endOffset = 20,
            startLine = 1,
            startColumn = 5,
            preview = "fun main()",
        )
        val parsed = loc.parsed()
        assertEquals(10, parsed.startOffset.value)
        assertEquals(20, parsed.endOffset.value)
        assertEquals(1, parsed.startLine.value)
        assertEquals(5, parsed.startColumn.value)
        assertEquals("fun main()", parsed.preview)
    }

    @Test
    fun `Location parsed rejects zero startLine`() {
        val loc = Location(
            filePath = "/workspace/src/Main.kt",
            startOffset = 0,
            endOffset = 5,
            startLine = 0,
            startColumn = 1,
            preview = "test",
        )
        assertThrows<IllegalArgumentException> { loc.parsed() }
    }

    @Test
    fun `TextEdit parsed validates path and offsets`() {
        val edit = TextEdit(
            filePath = "/workspace/src/Main.kt",
            startOffset = 5,
            endOffset = 10,
            newText = "newValue",
        )
        val parsed = edit.parsed()
        assertEquals(5, parsed.startOffset.value)
        assertEquals(10, parsed.endOffset.value)
        assertEquals("newValue", parsed.newText)
    }

    @Test
    fun `TextEdit parsed rejects relative path`() {
        val edit = TextEdit(
            filePath = "relative/Main.kt",
            startOffset = 0,
            endOffset = 5,
            newText = "x",
        )
        assertThrows<ValidationException> { edit.parsed() }
    }

    @Test
    fun `query parsed happy paths create typed models`() {
        val position = FilePosition("/workspace/src/Main.kt", 3)
        val filePaths = listOf("/workspace/src/Main.kt")

        val parsedQueries = listOf(
            SymbolQuery(position).parsed(),
            ReferencesQuery(position).parsed(),
            CallHierarchyQuery(position, CallDirection.INCOMING).parsed(),
            TypeHierarchyQuery(position).parsed(),
            SemanticInsertionQuery(position, SemanticInsertionTarget.FILE_TOP).parsed(),
            DiagnosticsQuery(filePaths).parsed(),
            RenameQuery(position, "renamed").parsed(),
            ImportOptimizeQuery(filePaths).parsed(),
            ApplyEditsQuery(
                edits = listOf(TextEdit("/workspace/src/Main.kt", 0, 1, "x")),
                fileHashes = listOf(FileHash("/workspace/src/Main.kt", "hash")),
                fileOperations = listOf(FileOperation.CreateFile("/workspace/src/New.kt", "class New")),
            ).parsed(),
            RefreshQuery(filePaths).parsed(),
            FileOutlineQuery("/workspace/src/Main.kt").parsed(),
            WorkspaceSymbolQuery("Main").parsed(),
            WorkspaceSearchQuery("Hello").parsed(),
            WorkspaceFilesQuery(moduleName = "main", maxFilesPerModule = 1).parsed(),
            ImplementationsQuery(position).parsed(),
            CodeActionsQuery(position).parsed(),
            CompletionsQuery(position).parsed(),
        )

        assertEquals(17, parsedQueries.size)
        assertEquals(PositiveInt(100), (parsedQueries.last() as ParsedCompletionsQuery).maxResults)
    }

    @Test
    fun `ReferencesQuery parsed keeps usage site scope opt in`() {
        val position = FilePosition("/workspace/src/Main.kt", 3)

        val defaults = ReferencesQuery(position).parsed()
        assertEquals(false, defaults.includeUsageSiteScope)
        assertEquals(PositiveInt(100), defaults.maxResults)
        assertEquals(null, defaults.pageToken)
        assertEquals(
            true,
            ReferencesQuery(
                position = position,
                includeUsageSiteScope = true,
            ).parsed().includeUsageSiteScope,
        )
    }

    @Test
    fun `ReferencesQuery parsed carries typed bounds and opaque continuation`() {
        val token = "00000000-0000-0000-0000-000000000337"
        val parsed = ReferencesQuery(
            position = FilePosition("/workspace/src/Main.kt", 3),
            maxResults = 7,
            pageToken = token,
        ).parsed()

        assertEquals(PositiveInt(7), parsed.maxResults)
        assertEquals(token, parsed.pageToken?.value)
    }

    @Test
    fun `position query parsed rejects invalid position`() {
        assertThrows<ValidationException> {
            SymbolQuery(FilePosition("relative.kt", 0)).parsed()
        }
        assertThrows<ValidationException> {
            ReferencesQuery(FilePosition("/workspace/src/Main.kt", -1)).parsed()
        }
    }

    @Test
    fun `bounded query parsed rejects non-positive limits`() {
        val position = FilePosition("/workspace/src/Main.kt", 0)

        assertThrows<ValidationException> { CompletionsQuery(position, maxResults = 0).parsed() }
        assertThrows<ValidationException> { ReferencesQuery(position, maxResults = 0).parsed() }
        assertThrows<ValidationException> { TypeHierarchyQuery(position, maxResults = 0).parsed() }
        assertThrows<ValidationException> { ImplementationsQuery(position, maxResults = 0).parsed() }
        assertThrows<ValidationException> { WorkspaceSymbolQuery("Main", maxResults = 0).parsed() }
        assertThrows<ValidationException> { WorkspaceSearchQuery("Main", maxResults = 0).parsed() }
        assertThrows<ValidationException> { WorkspaceFilesQuery(maxFilesPerModule = 0).parsed() }
    }

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
