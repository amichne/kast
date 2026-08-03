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

class ParsedModelsTest {

    @Test
    fun `addition queries parse strict inline normalized Kotlin content`() {
        val target = AdditionTargetPath.parse("/workspace/src/Added.kt")
        assertEquals(
            "package sample\n\nclass Added\n",
            AddFilePlanQuery(target, "package sample\n\nclass Added\n").parsed().proposedContent.value,
        )
        assertEquals(
            "class Added",
            AddDeclarationPlanQuery(
                target,
                AdditionTargetPreimageSha256.of("1".repeat(64)),
                "class Added",
            ).parsed().proposedDeclaration.value,
        )
        assertThrows<ValidationException> { AddFilePlanQuery(target, "class Added\r\n").parsed() }
        assertThrows<ValidationException> { AddFilePlanQuery(target, "class Bad\uD800").parsed() }
        assertThrows<ValidationException> {
            AddDeclarationPlanQuery(
                target,
                AdditionTargetPreimageSha256.of("1".repeat(64)),
                "class Added\n",
            ).parsed()
        }
    }

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
    fun `wire offsets preserve IntelliJ UTF-16 code units around non-BMP text`() {
        val prefix = "a😀"
        val utf16Offset = prefix.length
        val utf8Offset = prefix.toByteArray(Charsets.UTF_8).size
        check(utf16Offset != utf8Offset)
        val path = "/workspace/src/Main.kt"

        val position = Json.decodeFromString<FilePosition>(
            Json.encodeToString(FilePosition(path, utf16Offset)),
        )
        val location = Json.decodeFromString<Location>(
            Json.encodeToString(Location(path, utf16Offset, utf16Offset + 1, 1, utf16Offset + 1, "b")),
        )
        val edit = Json.decodeFromString<TextEdit>(
            Json.encodeToString(TextEdit(path, utf16Offset, utf16Offset + 1, "c")),
        )

        assertEquals(utf16Offset, position.parsed().offset.value)
        assertEquals(utf16Offset, location.parsed().startOffset.value)
        assertEquals(utf16Offset, edit.parsed().startOffset.value)
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
    fun `WorkspaceFilesQuery parsed accepts canonical opaque paging handles`() {
        val snapshotToken = "00000000-0000-0000-0000-000000000338"
        val pageToken = "00000000-0000-0000-0000-000000000339"

        val parsed = WorkspaceFilesQuery(
            moduleName = "main",
            includeFiles = true,
            maxFilesPerModule = 2,
            snapshotToken = snapshotToken,
            pageToken = pageToken,
        ).parsed()

        assertEquals(snapshotToken, parsed.snapshotToken?.value)
        assertEquals(pageToken, parsed.pageToken?.value)
    }

    @Test
    fun `WorkspaceFilesQuery parsed rejects a page handle without its snapshot handle`() {
        assertThrows<ValidationException> {
            WorkspaceFilesQuery(
                moduleName = "main",
                includeFiles = true,
                maxFilesPerModule = 2,
                pageToken = "00000000-0000-0000-0000-000000000339",
            ).parsed()
        }
    }

    @Test
    fun `WorkspaceFilesQuery parsed accepts only exact-module paging or workspace validation handles`() {
        val snapshotToken = "00000000-0000-0000-0000-000000000338"
        val pageToken = "00000000-0000-0000-0000-000000000339"

        WorkspaceFilesQuery(
            includeFiles = false,
            snapshotToken = snapshotToken,
        ).parsed()

        val illegalQueries = listOf(
            WorkspaceFilesQuery(includeFiles = true, snapshotToken = snapshotToken),
            WorkspaceFilesQuery(moduleName = "main", includeFiles = false, snapshotToken = snapshotToken),
            WorkspaceFilesQuery(includeFiles = false, snapshotToken = snapshotToken, pageToken = pageToken),
        )
        illegalQueries.forEach { query ->
            assertThrows<ValidationException> { query.parsed() }
        }
    }

    @Test
    fun `WorkspaceFilesQuery parsed rejects noncanonical workspace handles`() {
        val canonicalSnapshot = "123e4567-e89b-12d3-a456-426614174000"
        val invalidHandles = listOf("", "not-a-handle", canonicalSnapshot.uppercase())

        invalidHandles.forEach { invalidHandle ->
            assertThrows<ValidationException> {
                WorkspaceFilesQuery(
                    moduleName = "main",
                    includeFiles = true,
                    maxFilesPerModule = 2,
                    snapshotToken = invalidHandle,
                ).parsed()
            }
            assertThrows<ValidationException> {
                WorkspaceFilesQuery(
                    moduleName = "main",
                    includeFiles = true,
                    maxFilesPerModule = 2,
                    snapshotToken = canonicalSnapshot,
                    pageToken = invalidHandle,
                ).parsed()
            }
        }
    }

    @Test
    fun `WorkspaceFilesQuery parsed preserves the closed file kind domain`() {
        assertEquals(WorkspaceFileKindDomain.MIXED, WorkspaceFilesQuery().parsed().kindDomain)

        WorkspaceFileKindDomain.entries.forEach { kindDomain ->
            assertEquals(
                kindDomain,
                WorkspaceFilesQuery(kindDomain = kindDomain).parsed().kindDomain,
            )
        }
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
}
